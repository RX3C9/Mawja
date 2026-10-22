/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.data.video.downloader.remote

import androidx.annotation.StringRes
import app.mawja.android.R
import app.mawja.android.data.video.downloader.work.FetchFailure
import app.mawja.downloader.api.MawjaDownloaderContract

/**
 * Maps Mawja Downloader's remote-config refusals (MawjaDownloaderContract.ERROR_*) to the app's
 * download failures and the message the user sees. A refusal never falls back to a local download.
 */
object DownloaderBlocks {
    /** The failure for a refusal [code]; an unknown (newer) non-zero code still counts as a refusal. */
    fun failureFor(code: Int): FetchFailure? =
        when (code) {
            0 -> null
            MawjaDownloaderContract.ERROR_REMOTE_BLOCKED -> FetchFailure.DOWNLOADER_KILLED
            MawjaDownloaderContract.ERROR_MAINTENANCE -> FetchFailure.DOWNLOADER_MAINTENANCE
            MawjaDownloaderContract.ERROR_UPDATE_REQUIRED -> FetchFailure.DOWNLOADER_UPDATE_REQUIRED
            MawjaDownloaderContract.ERROR_DOWNLOADS_DISABLED -> FetchFailure.DOWNLOADER_DISABLED
            else -> FetchFailure.DOWNLOADER_DISABLED
        }

    fun isBlock(reason: FetchFailure): Boolean =
        reason == FetchFailure.DOWNLOADER_KILLED ||
            reason == FetchFailure.DOWNLOADER_MAINTENANCE ||
            reason == FetchFailure.DOWNLOADER_UPDATE_REQUIRED ||
            reason == FetchFailure.DOWNLOADER_DISABLED

    @StringRes
    fun messageRes(reason: FetchFailure): Int? =
        when (reason) {
            FetchFailure.DOWNLOADER_KILLED -> R.string.downloader_blocked_killed
            FetchFailure.DOWNLOADER_MAINTENANCE -> R.string.downloader_blocked_maintenance
            FetchFailure.DOWNLOADER_UPDATE_REQUIRED -> R.string.downloader_blocked_update
            FetchFailure.DOWNLOADER_DISABLED -> R.string.downloader_blocked_disabled
            else -> null
        }

    /** The text shown: the downloader's own maintenance message when it sent one, else [fallback]. */
    fun message(
        reason: FetchFailure,
        remoteMessage: String?,
        fallback: String,
    ): String = remoteMessage?.trim()?.takeIf { reason == FetchFailure.DOWNLOADER_MAINTENANCE && it.isNotEmpty() } ?: fallback
}
