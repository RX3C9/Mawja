/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.dubbing

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mawja.android.R
import app.mawja.android.accessibility.MawjaAccessibility
import app.mawja.android.ui.components.shared.FlowRowGroup
import app.mawja.android.ui.components.shared.FlowSectionHeader
import app.mawja.android.ui.components.shared.FlowSelectionRow
import app.mawja.android.ui.components.shared.flowRowGroupShape
import kotlin.math.PI
import kotlin.math.sin

/** Player settings: Arabic AI dubbing Off / voice-over / Arabic only, with a live voice wave while it speaks. */
@Composable
fun DubbingRows() {
    val mode by ArabicDubbing.mode.collectAsState()
    val status by ArabicDubbing.status.collectAsState()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        FlowSectionHeader(text = stringResource(R.string.dubbing_header))
        if (status == ArabicDubbing.Status.SPEAKING || status == ArabicDubbing.Status.PREPARING) {
            VoiceWave(active = status == ArabicDubbing.Status.SPEAKING, modifier = Modifier.padding(end = 16.dp).size(width = 44.dp, height = 18.dp))
        }
    }
    val options =
        listOf(
            DubMode.OFF to R.string.off,
            DubMode.VOICE_OVER to R.string.dubbing_voice_over,
            DubMode.REPLACE to R.string.dubbing_replace,
        )
    FlowRowGroup {
        options.forEachIndexed { index, (option, label) ->
            FlowSelectionRow(
                title = stringResource(label),
                supportingText =
                    when {
                        option != mode || option == DubMode.OFF -> if (option == DubMode.VOICE_OVER) stringResource(R.string.dubbing_voice_over_desc) else null
                        status == ArabicDubbing.Status.NO_CAPTIONS -> stringResource(R.string.dubbing_no_captions)
                        status == ArabicDubbing.Status.NO_VOICE -> stringResource(R.string.dubbing_no_voice)
                        status == ArabicDubbing.Status.PREPARING -> stringResource(R.string.dubbing_preparing)
                        else -> stringResource(R.string.dubbing_on)
                    },
                selected = option == mode,
                shape = flowRowGroupShape(index, options.size),
                onClick = { ArabicDubbing.setMode(option) },
            )
        }
    }
}

/** Five bars rippling like a voice in the Mawja wave colours; still when Reduce motion is on. */
@Composable
private fun VoiceWave(
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val still = MawjaAccessibility.reduceMotionNow()
    val phase by rememberInfiniteTransition(label = "dubWave").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(if (active) 900 else 1800), RepeatMode.Restart),
        label = "dubPhase",
    )
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    Canvas(modifier) {
        val bars = 5
        val gap = size.width / (bars * 2 - 1)
        val brush = Brush.verticalGradient(listOf(tertiary, primary))
        for (i in 0 until bars) {
            val wave = if (still) 0.6f else (0.35f + 0.65f * ((sin(phase + i * 1.1f) + 1f) / 2f)) * if (active) 1f else 0.5f
            val h = size.height * wave
            drawRoundRect(
                brush = brush,
                topLeft = Offset(i * gap * 2, (size.height - h) / 2),
                size = Size(gap, h),
                cornerRadius = CornerRadius(gap / 2, gap / 2),
            )
        }
    }
}
