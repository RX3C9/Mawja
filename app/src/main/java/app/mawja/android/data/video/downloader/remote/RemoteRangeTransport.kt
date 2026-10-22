/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.data.video.downloader.remote

import android.os.Bundle
import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import app.mawja.downloader.api.IMawjaDownloader
import app.mawja.downloader.api.IRangeProgress
import app.mawja.downloader.api.MawjaDownloaderContract.Range
import app.mawja.downloader.api.MawjaDownloaderContract.RangeResult
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.UUID

/** What one request run in Mawja Downloader ended on. */
data class RemoteRangeResult(
    /** HTTP status, or 0 when no response arrived. */
    val code: Int,
    val written: Long,
    val contentRange: String? = null,
    val retryAfter: String? = null,
    val error: String? = null,
    val diskFull: Boolean = false,
    val cancelled: Boolean = false,
    /** MawjaDownloaderContract.ERROR_* when the downloader refused new work (its remote config), else 0. */
    val blocked: Int = 0,
    /** The downloader's remote maintenance message, when it sent one. */
    val blockMessage: String? = null,
) {
    val isSuccessful: Boolean get() = code in 200..299
    val isBlocked: Boolean get() = blocked != 0
}

/**
 * Runs the app's HTTP range requests inside Mawja Downloader: the request is built here exactly
 * as a local one would be, and the downloader writes the body into the part file through a file
 * descriptor this side opens. Block bookkeeping, retries and resume state stay in the app.
 */
class RemoteRangeTransport(
    private val service: IMawjaDownloader,
) {
    /**
     * Fetches [request] into [file] from [offset], at most [maxBytes] bytes ([file] may be null
     * for a header-only probe with [maxBytes] 0). [onBytes] gets each chunk's size as it lands;
     * [registerCancel] gets the action that stops the transfer and returns its untrack handle.
     *
     * @throws IOException when the downloader went away mid-call.
     */
    fun fetch(
        request: Request,
        file: File?,
        offset: Long,
        maxBytes: Long,
        requirePartial: Boolean,
        onBytes: (Long) -> Unit,
        registerCancel: (() -> Unit) -> (() -> Unit),
        unregisterCancel: (() -> Unit) -> Unit,
        jobId: String? = null,
    ): RemoteRangeResult {
        val token = UUID.randomUUID().toString()
        val args =
            Bundle().apply {
                putString(Range.URL, request.url.toString())
                putBoolean(Range.REQUIRE_PARTIAL, requirePartial)
                // Lets a download that was already running finish if the downloader gets blocked.
                jobId?.let { putString(Range.JOB_ID, it) }
                putBundle(
                    Range.HEADERS,
                    Bundle().apply { request.headers.forEach { (name, value) -> putString(name, value) } },
                )
            }
        val lock = Any()
        var finished = false
        var reported = 0L
        val progress =
            object : IRangeProgress.Stub() {
                override fun onBytes(
                    token: String?,
                    delta: Long,
                ) {
                    synchronized(lock) {
                        if (finished) return
                        reported += delta
                    }
                    onBytes(delta)
                }
            }
        val handle = registerCancel { runCatching { service.cancelRange(token) } }
        val pfd = file?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE) }
        try {
            val result =
                try {
                    service.fetchRange(args, pfd, offset, maxBytes, token, progress)
                } catch (e: DeadObjectException) {
                    throw IOException("Mawja Downloader stopped", e)
                } catch (e: RemoteException) {
                    throw IOException("Mawja Downloader failed: ${e.message}", e)
                } catch (e: RuntimeException) {
                    // An exception the service threw back over binder (bad argument, security).
                    throw IOException("Mawja Downloader refused: ${e.message}", e)
                }
            val parsed = parse(result)
            // Late oneway progress calls are dropped; whatever they would have added is settled here.
            val rest =
                synchronized(lock) {
                    finished = true
                    parsed.written - reported
                }
            if (rest > 0) onBytes(rest)
            return parsed
        } finally {
            synchronized(lock) { finished = true }
            runCatching { pfd?.close() }
            unregisterCancel(handle)
        }
    }

    private fun parse(bundle: Bundle?): RemoteRangeResult {
        val b = bundle ?: return RemoteRangeResult(0, 0, error = "empty result")
        return RemoteRangeResult(
            code = b.getInt(RangeResult.CODE),
            written = b.getLong(RangeResult.WRITTEN),
            contentRange = b.getString(RangeResult.CONTENT_RANGE),
            retryAfter = b.getString(RangeResult.RETRY_AFTER),
            error = b.getString(RangeResult.ERROR),
            diskFull = b.getBoolean(RangeResult.DISK_FULL),
            cancelled = b.getBoolean(RangeResult.CANCELLED),
            blocked = b.getInt(RangeResult.BLOCKED, 0),
            blockMessage = b.getString(RangeResult.BLOCK_MESSAGE),
        )
    }
}
