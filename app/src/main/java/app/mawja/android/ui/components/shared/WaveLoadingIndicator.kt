/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.components.shared

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mawja.android.R
import app.mawja.android.ui.theme.LocalReduceMotion

/**
 * Mawja's wave loader (موجة): five rounded bars rising and falling in a travelling wave. It
 * replaces the buffering spinner and the first-load spinner. Under reduce motion it rests as a
 * still wave. TalkBack hears "Loading" and an indeterminate progress role.
 */
@Composable
fun WaveLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 40.dp,
) {
    val label = stringResource(R.string.wave_loading_label)
    val reduceMotion = LocalReduceMotion.current
    val phase: State<Float> =
        if (reduceMotion) {
            StillPhase
        } else {
            rememberInfiniteTransition(label = "waveLoading").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(WaveLoading.CYCLE_MILLIS, easing = LinearEasing), RepeatMode.Restart),
                label = "waveLoadingPhase",
            )
        }
    Canvas(
        modifier =
            modifier
                .size(size)
                .semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                },
    ) {
        val bars = WaveLoading.BAR_COUNT
        val gap = this.size.width / (bars * 2f + 1f)
        val barWidth = gap
        val p = phase.value
        for (i in 0 until bars) {
            val fraction = WaveLoading.barHeight(i, p)
            val h = this.size.height * fraction
            drawRoundRect(
                color = color,
                topLeft = Offset(gap + i * 2f * gap, (this.size.height - h) / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

private val StillPhase: State<Float> =
    object : State<Float> {
        override val value: Float = WaveLoading.STILL_PHASE
    }

/** Pure shape of the wave loader, shared with its JVM test. */
object WaveLoading {
    const val BAR_COUNT = 5
    const val CYCLE_MILLIS = 1100
    const val MIN_HEIGHT = 0.28f
    const val MAX_HEIGHT = 1f

    /** The resting phase under reduce motion: a gentle, readable wave shape. */
    const val STILL_PHASE = 0.25f

    /** Height of bar [index] as a fraction of the box at [phase] in [0, 1). */
    fun barHeight(
        index: Int,
        phase: Float,
    ): Float {
        val angle = 2.0 * Math.PI * (phase - index / BAR_COUNT.toFloat())
        val wave = (kotlin.math.sin(angle) + 1.0) / 2.0
        return (MIN_HEIGHT + (MAX_HEIGHT - MIN_HEIGHT) * wave).toFloat()
    }
}
