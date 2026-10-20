/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.data.video.downloader

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.VisibleForTesting
import app.mawja.android.BuildConfig
import app.mawja.downloader.api.MawjaDownloaderContract
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Mawja hands every download to the separate Mawja Downloader app. Before a request is queued
 * this checks that the downloader is installed and signed with the same key as this app; when it
 * is not, it asks the UI to show the install prompt instead of downloading here.
 */
object DownloaderGate {
    /** Unit tests set this so they do not need a real PackageManager. */
    @VisibleForTesting
    @Volatile
    var overrideForTests: Boolean? = null

    private val _installPrompts =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits each time a download was refused because the downloader is missing. */
    val installPrompts: SharedFlow<Unit> = _installPrompts.asSharedFlow()

    /** Package of the trusted downloader, or null when none is installed. */
    fun trustedPackage(context: Context): String? {
        val pkg = MawjaDownloaderContract.resolvePackage(context) ?: return null
        val match = context.packageManager.checkSignatures(context.packageName, pkg)
        return pkg.takeIf { match == PackageManager.SIGNATURE_MATCH }
    }

    fun isAvailable(context: Context): Boolean {
        overrideForTests?.let { return it }
        if (!BuildConfig.REQUIRE_DOWNLOADER) return true
        return runCatching { trustedPackage(context) != null }.getOrDefault(false)
    }

    /** [isAvailable], and asks for the install prompt when it is not. */
    fun ensureAvailable(context: Context): Boolean {
        val ok = isAvailable(context)
        if (!ok) _installPrompts.tryEmit(Unit)
        return ok
    }
}
