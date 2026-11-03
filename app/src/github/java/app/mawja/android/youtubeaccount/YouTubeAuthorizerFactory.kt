/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.youtubeaccount

import android.app.Activity
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Google Play services authorization for the read-only YouTube scope. */
object YouTubeAuthorizerFactory {
    fun create(): YouTubeAuthorizer = PlayYouTubeAuthorizer
}

private object PlayYouTubeAuthorizer : YouTubeAuthorizer {
    private val request
        get() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(YouTubeAccountClient.SCOPE))).build()

    override suspend fun request(activity: Activity): YouTubeAuthStep =
        try {
            val result = Identity.getAuthorizationClient(activity).authorize(request).await()
            val pending = result.pendingIntent
            when {
                result.hasResolution() && pending != null -> YouTubeAuthStep.NeedsConsent(pending.intentSender)
                !result.accessToken.isNullOrBlank() -> YouTubeAuthStep.Granted(result.accessToken!!)
                else -> YouTubeAuthStep.Failed(null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            YouTubeAuthStep.Failed(e.message)
        }

    override fun tokenFromResult(
        activity: Activity,
        data: Intent?,
    ): String? = runCatching { Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data).accessToken }.getOrNull()
}
