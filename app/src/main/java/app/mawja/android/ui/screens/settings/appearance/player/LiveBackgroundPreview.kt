/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.screens.settings.appearance.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mawja.android.R
import app.mawja.android.data.local.MusicPlayerBackgroundStyle
import app.mawja.android.ui.components.musicplayer.common.playerBackdropColors
import app.mawja.android.ui.components.musicplayer.live.LiveBackground
import app.mawja.android.ui.components.musicplayer.live.LiveBackgroundStyle

private val LivePreviewHeight = 72.dp

/**
 * A live background option drawn in the theme's colours, moving under the same rules as the
 * player. Off previews the music background the user already picked (Flow's look).
 */
@Composable
internal fun LiveBackgroundPreview(
    style: LiveBackgroundStyle,
    locked: Boolean,
    flowStyle: MusicPlayerBackgroundStyle,
    followLightTheme: Boolean,
) {
    if (style == LiveBackgroundStyle.OFF) {
        MusicBackgroundPreview(flowStyle, followLightTheme)
        return
    }
    val context = LocalContext.current
    val artwork = remember { "android.resource://${context.packageName}/${R.drawable.ic_launcher_foreground}" }
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(LivePreviewHeight)
                .clip(MaterialTheme.shapes.medium),
    ) {
        LiveBackground(
            style = style,
            backdrop = remember(scheme) { playerBackdropColors(scheme.primary, scheme.tertiary, false) },
            darkText = false,
            thumbnailUrl = artwork,
            playerVisible = true,
        )
        if (locked) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = stringResource(R.string.premium_available_in_plus),
                tint = Color.White,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(18.dp),
            )
        }
    }
}
