/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteCallbackList
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.mawja.downloader.api.IDownloadCallback
import app.mawja.downloader.api.IMawjaDownloader
import app.mawja.downloader.api.MawjaDownloaderContract
import app.mawja.downloader.api.MawjaDownloaderContract.Item
import app.mawja.downloader.api.MawjaDownloaderContract.Request
import app.mawja.downloader.api.MawjaDownloaderContract.State
import app.mawja.downloader.ui.MainActivity

/**
 * Bound service other Mawja apps talk to. Access is limited by the signature permission
 * declared in the manifest, so a foreign app cannot queue downloads here.
 */
class DownloadService :
    Service(),
    DownloadListener {
    private val callbacks = RemoteCallbackList<IDownloadCallback>()
    private var foreground = false

    private val binder =
        object : IMawjaDownloader.Stub() {
            override fun apiVersion(): Int = MawjaDownloaderContract.API_VERSION

            override fun enqueue(request: Bundle?): String {
                val req = requireNotNull(request) { "request is null" }
                val url = requireNotNull(req.getString(Request.URL)) { "url is required" }
                require(url.startsWith("https://") || url.startsWith("http://")) { "unsupported url" }
                val headersBundle = req.getBundle(Request.HEADERS)
                val headers =
                    headersBundle?.keySet()?.associateWith { headersBundle.getString(it).orEmpty() }.orEmpty()
                val title = req.getString(Request.TITLE).orEmpty().ifBlank { url.substringAfterLast('/') }
                return DownloadEngine.enqueue(
                    this@DownloadService,
                    DownloadItem(
                        id = "",
                        title = title,
                        url = url,
                        fileName = req.getString(Request.FILE_NAME).orEmpty().ifBlank { title },
                        mimeType = req.getString(Request.MIME_TYPE) ?: "application/octet-stream",
                        subfolder = req.getString(Request.SUBFOLDER).orEmpty(),
                        headers = headers,
                        sourceId = req.getString(Request.SOURCE_ID),
                    ),
                )
            }

            override fun cancel(id: String?) {
                id?.let(DownloadEngine::cancel)
            }

            override fun list(): MutableList<Bundle> = DownloadEngine.items.value.map { it.toBundle() }.toMutableList()

            override fun registerCallback(callback: IDownloadCallback?) {
                callback?.let { callbacks.register(it) }
            }

            override fun unregisterCallback(callback: IDownloadCallback?) {
                callback?.let { callbacks.unregister(it) }
            }
        }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        DownloadEngine.addListener(this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        DownloadEngine.removeListener(this)
        callbacks.kill()
        super.onDestroy()
    }

    override fun onChanged(item: DownloadItem) {
        broadcast(item)
        updateForeground()
    }

    @Synchronized
    private fun broadcast(item: DownloadItem) {
        val n = callbacks.beginBroadcast()
        try {
            for (i in 0 until n) {
                val cb = callbacks.getBroadcastItem(i)
                runCatching {
                    when (item.state) {
                        State.QUEUED -> cb.onQueued(item.id, item.title)
                        State.RUNNING -> cb.onProgress(item.id, item.downloaded, item.total)
                        State.COMPLETED -> cb.onCompleted(item.id, item.contentUri, item.mimeType)
                        State.FAILED -> cb.onFailed(item.id, item.error)
                        State.CANCELLED -> cb.onCancelled(item.id)
                    }
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    @Synchronized
    private fun updateForeground() {
        val active = DownloadEngine.activeCount()
        if (active > 0) {
            val notification = buildNotification(active)
            if (!foreground) {
                // Android 12+ may refuse a background foreground-service start; downloads keep running
                // while a client stays bound, so a refusal is not fatal.
                foreground =
                    runCatching {
                        startService(Intent(this, DownloadService::class.java))
                        ServiceCompat.startForeground(
                            this,
                            NOTIFICATION_ID,
                            notification,
                            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
                        )
                    }.isSuccess
            } else {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            }
        } else if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
            stopSelf()
        }
    }

    private fun buildNotification(active: Int): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val running = DownloadEngine.items.value.filter { it.isActive }
        val totalBytes = running.sumOf { it.total.coerceAtLeast(0) }
        val doneBytes = running.sumOf { it.downloaded }
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(getString(R.string.notification_running, active))
            .setContentText(running.firstOrNull()?.title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .setProgress(100, if (totalBytes > 0) ((doneBytes * 100) / totalBytes).toInt() else 0, totalBytes <= 0)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun DownloadItem.toBundle(): Bundle =
        Bundle().apply {
            putString(Item.ID, id)
            putString(Item.TITLE, title)
            putString(Item.STATE, state.name)
            putLong(Item.DOWNLOADED, downloaded)
            putLong(Item.TOTAL, total)
            putString(Item.CONTENT_URI, contentUri)
            putString(Item.ERROR, error)
            putString(Item.SOURCE_ID, sourceId)
        }

    private companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 7001
    }
}
