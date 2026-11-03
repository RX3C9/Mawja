/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.youtubeaccount

import android.app.Activity
import android.content.Intent

/** The FOSS build ships without Google Play services, so it offers Google Takeout instead. */
object YouTubeAuthorizerFactory {
    fun create(): YouTubeAuthorizer =
        object : YouTubeAuthorizer {
            override suspend fun request(activity: Activity): YouTubeAuthStep = YouTubeAuthStep.Unavailable

            override fun tokenFromResult(
                activity: Activity,
                data: Intent?,
            ): String? = null
        }
}
