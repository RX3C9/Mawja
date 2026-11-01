/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.components.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.mawja.android.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay

/** Whether a list's first-load cascade is still playing; it ends after [StaggerTiming.totalMillis]. */
@Stable
class FirstLoadStagger internal constructor() {
    var playing by mutableStateOf(true)
        internal set
}

/** One cascade per list composition: a list that first appears plays it once, refreshes do not. */
@Composable
fun rememberFirstLoadStagger(): FirstLoadStagger {
    val stagger = remember { FirstLoadStagger() }
    LaunchedEffect(stagger) {
        delay(StaggerTiming.totalMillis.toLong())
        stagger.playing = false
    }
    return stagger
}

/**
 * Fades item [index] in with a small rise as part of the first-load cascade. Items past the
 * cascade, items composed after it ended, and everything under reduce motion draw at once.
 */
@Composable
fun Modifier.staggeredAppear(
    stagger: FirstLoadStagger,
    index: Int,
): Modifier {
    val delayMillis = StaggerTiming.delayMillis(index, stagger.playing, LocalReduceMotion.current) ?: return this
    val progress = remember { Animatable(0f) }
    val risePx = with(LocalDensity.current) { StaggerTiming.RISE_DP.dp.toPx() }
    LaunchedEffect(progress) {
        delay(delayMillis.toLong())
        progress.animateTo(1f, tween(StaggerTiming.DURATION_MILLIS, easing = FastOutSlowInEasing))
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = risePx * (1f - progress.value)
    }
}
