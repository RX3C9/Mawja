/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.components.musicplayer.live

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mawja.android.R
import app.mawja.android.ui.components.shared.FlowSheetHeader
import app.mawja.android.ui.components.shared.rememberFlowSheetState

/**
 * Shown when a Free user picks a locked live background. It reuses the Plus copy of the theme
 * upsell; "See membership" opens the Upgrade page. Off, the Flow look, stays free.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiveBackgroundUpsellSheet(
    styleName: String,
    onSeePlans: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberFlowSheetState(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        FlowSheetHeader(
            title = stringResource(R.string.premium_available_in_plus),
            subtitle = styleName,
            onClose = onDismiss,
            showDragHandle = false,
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.live_background_upsell_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.live_background_upsell_free_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onSeePlans, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.premium_see_plans))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.premium_not_now))
            }
        }
    }
}

/** The label for a live background option. */
internal fun LiveBackgroundStyle.liveLabelRes(): Int =
    when (this) {
        LiveBackgroundStyle.OFF -> R.string.live_background_off
        LiveBackgroundStyle.WAVES -> R.string.live_background_waves
        LiveBackgroundStyle.AURORA -> R.string.live_background_aurora
        LiveBackgroundStyle.PARTICLES -> R.string.live_background_particles
        LiveBackgroundStyle.STATIC_BLUR -> R.string.live_background_static_blur
    }
