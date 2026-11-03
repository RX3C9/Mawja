/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.youtubeaccount

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import app.mawja.android.R
import kotlinx.coroutines.launch

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/**
 * Returns an action that asks Google for read-only YouTube access and hands the token to
 * [onToken]; [onMessage] gets a short explanation when it can't (cancelled, FOSS build, error).
 */
@Composable
fun rememberYouTubeAccountConnect(
    onToken: (String) -> Unit,
    onMessage: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authorizer = remember { YouTubeAuthorizerFactory.create() }
    val consent =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val activity = context.findActivity()
            val token =
                if (result.resultCode == Activity.RESULT_OK && activity != null) authorizer.tokenFromResult(activity, result.data) else null
            if (token.isNullOrBlank()) onMessage(context.getString(R.string.youtube_account_cancelled)) else onToken(token)
        }
    return {
        val activity = context.findActivity()
        if (activity == null) {
            onMessage(context.getString(R.string.youtube_account_error_unavailable))
        } else {
            scope.launch {
                when (val step = authorizer.request(activity)) {
                    is YouTubeAuthStep.Granted -> onToken(step.accessToken)
                    is YouTubeAuthStep.NeedsConsent ->
                        runCatching { consent.launch(IntentSenderRequest.Builder(step.intentSender).build()) }
                            .onFailure { onMessage(context.getString(R.string.youtube_account_error_unavailable)) }
                    YouTubeAuthStep.Unavailable -> onMessage(context.getString(R.string.youtube_account_unavailable_foss))
                    is YouTubeAuthStep.Failed -> onMessage(context.getString(R.string.youtube_account_error_auth))
                }
            }
        }
    }
}
