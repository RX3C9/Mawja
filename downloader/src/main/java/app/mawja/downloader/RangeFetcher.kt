/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.downloader

import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import app.mawja.downloader.api.IRangeProgress
import app.mawja.downloader.api.MawjaDownloaderContract.Range
import app.mawja.downloader.api.MawjaDownloaderContract.RangeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Runs the byte transfers Mawja hands over (API version 2). Mawja keeps the bookkeeping (which
 * blocks are done, retries, resume state) and its files; this app does the network work and
 * writes each body straight into the file descriptor Mawja passed for it.
 */
object RangeFetcher {
    private const val BUFFER_SIZE = 256 * 1024
    private const val PROGRESS_STEP_BYTES = 256 * 1024L

    /** Headers OkHttp manages itself; a client-supplied value would break the request framing. */
    private val FORBIDDEN_HEADERS = setOf("host", "content-length", "transfer-encoding", "connection")

    private val calls = ConcurrentHashMap<String, Call>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    private val _active = MutableStateFlow(0)

    /** How many Mawja transfers are running here right now (shown in the UI and the notification). */
    val active: StateFlow<Int> = _active.asStateFlow()

    private val _bytesTotal = MutableStateFlow(0L)

    /** Bytes moved for Mawja since this process started. */
    val bytesTotal: StateFlow<Long> = _bytesTotal.asStateFlow()

    private val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun cancel(token: String) {
        cancelled += token
        calls.remove(token)?.cancel()
    }

    fun fetch(
        request: Bundle,
        out: ParcelFileDescriptor?,
        offset: Long,
        maxBytes: Long,
        token: String,
        progress: IRangeProgress?,
    ): Bundle {
        val url = request.getString(Range.URL)
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            out?.closeQuietly()
            return result(code = 0, written = 0, error = "unsupported url")
        }
        if (maxBytes > 0 && out == null) return result(code = 0, written = 0, error = "no output")
        if (offset < 0 || maxBytes < 0) {
            out?.closeQuietly()
            return result(code = 0, written = 0, error = "bad range")
        }
        val requirePartial = request.getBoolean(Range.REQUIRE_PARTIAL, true)
        val builder = Request.Builder().url(url)
        request.getBundle(Range.HEADERS)?.let { headers ->
            headers.keySet().filter { it.lowercase() !in FORBIDDEN_HEADERS }.forEach { name ->
                headers.getString(name)?.let { builder.header(name, it) }
            }
        }
        val call = client.newCall(builder.build())
        calls[token] = call
        // A client that dies mid-transfer must not leave this app downloading for nobody.
        val death = IBinder.DeathRecipient { cancel(token) }
        val binder = progress?.asBinder()
        runCatching { binder?.linkToDeath(death, 0) }
        if (token in cancelled) call.cancel()
        _active.update { it + 1 }
        var written = 0L
        try {
            val response =
                try {
                    call.execute()
                } catch (e: IOException) {
                    return result(0, 0, error = e.message ?: e.javaClass.simpleName, cancelled = call.isCanceled())
                }
            response.use {
                val headers =
                    Bundle().apply {
                        response.header("Content-Range")?.let { putString(RangeResult.CONTENT_RANGE, it) }
                        response.header("Retry-After")?.let { putString(RangeResult.RETRY_AFTER, it) }
                    }
                if (!response.isSuccessful || maxBytes == 0L || (requirePartial && response.code != 206)) {
                    return result(response.code, 0).apply { putAll(headers) }
                }
                val target = out ?: return result(response.code, 0, error = "no output")
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(target).use { stream ->
                        val channel = stream.channel
                        channel.position(offset)
                        val buffer = ByteArray(BUFFER_SIZE)
                        var reported = 0L
                        response.body.byteStream().use { input ->
                            while (written < maxBytes) {
                                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - written).toInt())
                                if (read < 0) break
                                val bytes = ByteBuffer.wrap(buffer, 0, read)
                                while (bytes.hasRemaining()) channel.write(bytes)
                                written += read
                                if (written - reported >= PROGRESS_STEP_BYTES) {
                                    val delta = written - reported
                                    reported = written
                                    _bytesTotal.update { it + delta }
                                    runCatching { progress?.onBytes(token, delta) }
                                }
                            }
                        }
                        _bytesTotal.update { it + (written - reported) }
                    }
                } catch (e: IOException) {
                    val message = e.message ?: e.javaClass.simpleName
                    val full = message.contains("ENOSPC") || message.contains("No space left")
                    return result(response.code, written, error = message, diskFull = full, cancelled = call.isCanceled())
                }
                return result(response.code, written).apply { putAll(headers) }
            }
        } finally {
            out?.closeQuietly()
            calls.remove(token)
            cancelled -= token
            runCatching { binder?.unlinkToDeath(death, 0) }
            _active.update { it - 1 }
        }
    }

    private fun result(
        code: Int,
        written: Long,
        error: String? = null,
        diskFull: Boolean = false,
        cancelled: Boolean = false,
    ): Bundle =
        Bundle().apply {
            putInt(RangeResult.CODE, code)
            putLong(RangeResult.WRITTEN, written)
            error?.let { putString(RangeResult.ERROR, it) }
            putBoolean(RangeResult.DISK_FULL, diskFull)
            putBoolean(RangeResult.CANCELLED, cancelled)
        }

    private fun ParcelFileDescriptor.closeQuietly() {
        runCatching { close() }
    }
}
