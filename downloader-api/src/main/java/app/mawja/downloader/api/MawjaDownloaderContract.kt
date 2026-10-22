/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.downloader.api

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle

/** Shared constants between the Mawja app and the Mawja Downloader app. */
object MawjaDownloaderContract {
    const val API_VERSION = 3

    /** First version whose service runs the app's byte transfers (fetchRange). */
    const val API_RANGE_TRANSFER = 2

    /**
     * First version that refuses new work when its remote config (config/downloader) blocks it:
     * fetchRange answers with [RangeResult.BLOCKED] and enqueue throws an IllegalStateException whose
     * message is [blockedMessage] (read it back with [blockedCode]). Older clients see a plain failure.
     */
    const val API_REMOTE_BLOCK = 3

    /** The downloader is switched off remotely (kill switch). */
    const val ERROR_REMOTE_BLOCKED = 1001

    /** The downloader is under maintenance; [RangeResult.BLOCK_MESSAGE] may carry the remote message. */
    const val ERROR_MAINTENANCE = 1002

    /** This downloader build is older than config/downloader minVersion. */
    const val ERROR_UPDATE_REQUIRED = 1003

    /** config/downloader features.downloads is off. */
    const val ERROR_DOWNLOADS_DISABLED = 1004

    private const val BLOCKED_PREFIX = "mawja-blocked:"

    /** The exception message enqueue throws over binder for a refusal [code]. */
    fun blockedMessage(
        code: Int,
        detail: String,
    ): String = "$BLOCKED_PREFIX$code:$detail"

    /** The ERROR_* code in an exception message from enqueue, or 0 when it is not a refusal. */
    fun blockedCode(message: String?): Int =
        message
            ?.takeIf { it.startsWith(BLOCKED_PREFIX) }
            ?.removePrefix(BLOCKED_PREFIX)
            ?.substringBefore(':')
            ?.toIntOrNull() ?: 0

    /** Release package name; debug builds append ".debug". */
    const val PACKAGE_RELEASE = "app.mawja.downloader"

    const val ACTION_BIND = "app.mawja.downloader.action.BIND"

    /** Signature-level permission guarding the service (debug builds use the _DEBUG variant). */
    const val PERMISSION_RELEASE = "app.mawja.permission.DOWNLOAD"
    const val PERMISSION_DEBUG = "app.mawja.permission.DOWNLOAD_DEBUG"

    /** Where users get the downloader when it is missing. */
    const val INSTALL_URL = "https://github.com/RX3C9/Mawja/releases/latest"

    object Request {
        const val URL = "url"
        const val TITLE = "title"
        const val FILE_NAME = "file_name"
        const val MIME_TYPE = "mime_type"
        const val SUBFOLDER = "subfolder"
        const val HEADERS = "headers" // Bundle of String -> String
        const val SOURCE_ID = "source_id" // e.g. a video id, used for de-duplication
    }

    object Item {
        const val ID = "id"
        const val TITLE = "title"
        const val STATE = "state"
        const val DOWNLOADED = "downloaded"
        const val TOTAL = "total"
        const val CONTENT_URI = "content_uri"
        const val ERROR = "error"
        const val SOURCE_ID = "source_id"
    }

    /** Request keys of fetchRange. */
    object Range {
        const val URL = "url"
        const val HEADERS = "headers" // Bundle of String -> String

        /** When true the body is written only for a 206 answer, so a server ignoring Range cannot corrupt the file. */
        const val REQUIRE_PARTIAL = "require_partial"

        /**
         * Optional id of the download this request belongs to (API 3). A job that was running before
         * the downloader got blocked may finish; requests without it are treated as new work.
         */
        const val JOB_ID = "job_id"
    }

    /** Result keys of fetchRange. */
    object RangeResult {
        /** HTTP status, or 0 when no response arrived. */
        const val CODE = "code"
        const val WRITTEN = "written"
        const val CONTENT_RANGE = "content_range"
        const val RETRY_AFTER = "retry_after"
        const val ERROR = "error"
        const val DISK_FULL = "disk_full"
        const val CANCELLED = "cancelled"

        /** Int ERROR_* code when the downloader refused the request (API 3); absent or 0 otherwise. */
        const val BLOCKED = "blocked"

        /** Remote maintenance message in the downloader's language, when it has one. */
        const val BLOCK_MESSAGE = "block_message"
    }

    enum class State { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

    fun bindIntent(): Intent = Intent(ACTION_BIND)

    /** Finds the installed downloader whose package matches this build flavour. */
    fun resolvePackage(context: Context): String? {
        val pm = context.packageManager
        val matches =
            if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentServices(bindIntent(), PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(bindIntent(), 0)
            }
        val packages = matches.mapNotNull { it.serviceInfo?.packageName }.filter { it.startsWith(PACKAGE_RELEASE) }
        val wantDebug = context.packageName.endsWith(".debug")
        return packages.firstOrNull { it.endsWith(".debug") == wantDebug } ?: packages.firstOrNull()
    }

    fun buildRequest(
        url: String,
        title: String,
        fileName: String,
        mimeType: String,
        subfolder: String? = null,
        sourceId: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Bundle =
        Bundle().apply {
            putString(Request.URL, url)
            putString(Request.TITLE, title)
            putString(Request.FILE_NAME, fileName)
            putString(Request.MIME_TYPE, mimeType)
            subfolder?.let { putString(Request.SUBFOLDER, it) }
            sourceId?.let { putString(Request.SOURCE_ID, it) }
            putBundle(Request.HEADERS, Bundle().apply { headers.forEach { (k, v) -> putString(k, v) } })
        }
}
