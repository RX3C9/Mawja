/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.data.video.downloader.remote

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.util.Log
import app.mawja.android.data.video.downloader.DownloaderGate
import app.mawja.downloader.api.IMawjaDownloader
import app.mawja.downloader.api.MawjaDownloaderContract
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The app's binding to Mawja Downloader. It is bound while downloads run and released when the
 * queue empties, so the downloader process is not kept alive for nothing.
 */
object DownloaderConnection {
    private const val TAG = "DownloaderConnection"
    private const val BIND_TIMEOUT_MS = 5_000L

    private val lock = Any()

    @Volatile
    private var service: IMawjaDownloader? = null

    @Volatile
    private var apiVersion: Int = 0
    private var connection: ServiceConnection? = null
    private var ready: CountDownLatch? = null

    /**
     * The downloader when it is installed, trusted and new enough to run transfers, else null.
     * Blocks up to a few seconds for the first bind, so it must not be called on the main thread.
     */
    fun transferService(context: Context): IMawjaDownloader? {
        val current = service
        if (current != null && current.asBinder().isBinderAlive) {
            return current.takeIf { apiVersion >= MawjaDownloaderContract.API_RANGE_TRANSFER }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val latch = bind(context.applicationContext) ?: return null
        latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return service?.takeIf { apiVersion >= MawjaDownloaderContract.API_RANGE_TRANSFER }
    }

    /** Unbinds; the next [transferService] binds again. */
    fun release(context: Context) {
        synchronized(lock) {
            connection?.let { runCatching { context.applicationContext.unbindService(it) } }
            connection = null
            ready = null
            service = null
            apiVersion = 0
        }
    }

    private fun bind(context: Context): CountDownLatch? {
        synchronized(lock) {
            ready?.let { if (connection != null) return it }
            val pkg = runCatching { DownloaderGate.trustedPackage(context) }.getOrNull() ?: return null
            val latch = CountDownLatch(1)
            val conn =
                object : ServiceConnection {
                    override fun onServiceConnected(
                        name: ComponentName?,
                        binder: IBinder?,
                    ) {
                        val remote = binder?.let(IMawjaDownloader.Stub::asInterface)
                        apiVersion = runCatching { remote?.apiVersion() ?: 0 }.getOrDefault(0)
                        service = remote
                        Log.d(TAG, "Bound to $pkg (api $apiVersion)")
                        latch.countDown()
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        // The system rebinds on its own once the downloader restarts.
                        service = null
                    }

                    override fun onBindingDied(name: ComponentName?) {
                        release(context)
                    }

                    override fun onNullBinding(name: ComponentName?) {
                        latch.countDown()
                        release(context)
                    }
                }
            val intent = MawjaDownloaderContract.bindIntent().setPackage(pkg)
            val bound = runCatching { context.bindService(intent, conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
            if (!bound) {
                runCatching { context.unbindService(conn) }
                return null
            }
            connection = conn
            ready = latch
            return latch
        }
    }
}
