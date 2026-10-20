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
    const val API_VERSION = 1

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
