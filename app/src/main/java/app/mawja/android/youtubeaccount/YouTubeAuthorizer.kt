/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.youtubeaccount

import android.app.Activity
import android.content.Intent
import android.content.IntentSender

/** What asking Google for read-only access to the user's YouTube account led to. */
sealed interface YouTubeAuthStep {
    data class Granted(val accessToken: String) : YouTubeAuthStep

    /** Google needs the user to pick an account and agree; launch this and read the result. */
    data class NeedsConsent(val intentSender: IntentSender) : YouTubeAuthStep

    /** This build has no Google Play services sign-in (the FOSS build). */
    data object Unavailable : YouTubeAuthStep

    data class Failed(val message: String?) : YouTubeAuthStep
}

interface YouTubeAuthorizer {
    suspend fun request(activity: Activity): YouTubeAuthStep

    fun tokenFromResult(
        activity: Activity,
        data: Intent?,
    ): String?
}
