/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.components.musicplayer.live

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import app.mawja.android.battery.BatterySaver
import app.mawja.android.ui.components.musicplayer.common.PlayerBackdropColors
import app.mawja.android.ui.components.musicplayer.full.BlurredArtworkLayer
import app.mawja.android.ui.theme.LocalReduceMotion
import kotlinx.coroutines.isActive
import kotlin.math.sin

private const val TWO_PI = 6.2831855f
private const val LOOP_RADIANS = TWO_PI / LiveFramePacing.LOOP_SECONDS
private const val PARTICLE_COUNT = 42
private const val WAVE_SAMPLES = 48

/**
 * The full player's live background (#165), replacing the backdrop layer while a style other than
 * Off is on. It moves only while [playerVisible], the app is in front, and neither reduce motion
 * nor battery saver is on; otherwise it rests as one still gradient in the same colours.
 */
@Composable
internal fun LiveBackground(
    style: LiveBackgroundStyle,
    backdrop: PlayerBackdropColors,
    darkText: Boolean,
    thumbnailUrl: String?,
    playerVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val baseArgb = backdrop.base.toArgb()
    val accentArgb = backdrop.accent.toArgb()
    // Dark text means a light player, so the colours go pale.
    val colors = remember(baseArgb, accentArgb, darkText) { LiveColorDerivation.derive(baseArgb, accentArgb, dark = !darkText) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val batterySaver by BatterySaver.active.collectAsState()
    val animate =
        LiveMotionPolicy.shouldAnimate(
            style = style,
            playerVisible = playerVisible,
            appForeground = lifecycleState.isAtLeast(Lifecycle.State.STARTED),
            reduceMotion = LocalReduceMotion.current,
            batterySaver = batterySaver,
        )
    val time = rememberLiveTime(animate)

    Box(modifier = modifier.fillMaxSize().background(Color(colors.ground))) {
        when {
            style == LiveBackgroundStyle.OFF -> Unit

            style == LiveBackgroundStyle.STATIC_BLUR -> {
                BlurredArtworkLayer(thumbnailUrl = thumbnailUrl, alpha = 0.70f)
                Box(Modifier.fillMaxSize().background(stillBrush(colors, scrimAlpha = 0.62f)))
            }

            !animate -> StillGradient(colors)

            style == LiveBackgroundStyle.PARTICLES -> ParticlesLayer(colors) { time.value }

            else -> MovingLayer(style, colors) { time.value }
        }
    }
}

/** Seconds into the loop, advanced at 30 fps only while [animate]; it holds its value while paused. */
@Composable
private fun rememberLiveTime(animate: Boolean): State<Float> {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var start = Long.MIN_VALUE
        var last = -1L
        while (isActive) {
            withFrameNanos { now ->
                if (start == Long.MIN_VALUE) start = LiveFramePacing.resumeStart(now, time.floatValue)
                if (LiveFramePacing.shouldDraw(last, now)) {
                    last = now
                    time.floatValue = LiveFramePacing.wrappedSeconds(start, now)
                }
            }
        }
    }
    return time
}

@Composable
private fun MovingLayer(
    style: LiveBackgroundStyle,
    colors: LiveColors,
    time: () -> Float,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val source = if (style == LiveBackgroundStyle.AURORA) LiveBackgroundShaders.AURORA else LiveBackgroundShaders.WAVES
        val shader = rememberRuntimeShader(source)
        if (shader != null) {
            ShaderLiveLayer(shader = shader, colors = colors, time = time)
            return
        }
    }
    if (style == LiveBackgroundStyle.AURORA) CanvasAurora(colors, time) else CanvasWaves(colors, time)
}

@Composable
private fun StillGradient(colors: LiveColors) {
    Box(Modifier.fillMaxSize().background(stillBrush(colors, scrimAlpha = 1f)))
}

private fun stillBrush(
    colors: LiveColors,
    scrimAlpha: Float,
): Brush =
    Brush.verticalGradient(
        0.00f to Color(colors.primary).copy(alpha = scrimAlpha),
        0.45f to Color(colors.secondary).copy(alpha = scrimAlpha * 0.9f),
        1.00f to Color(colors.ground).copy(alpha = scrimAlpha),
    )

/** Canvas waves for Android 12 and older: three filled sine bands over the still gradient. */
@Composable
private fun CanvasWaves(
    colors: LiveColors,
    time: () -> Float,
) {
    val path = remember { Path() }
    Box(
        Modifier.fillMaxSize().background(stillBrush(colors, 1f)).drawBehind {
            val t = time() * LOOP_RADIANS
            drawWave(path, Color(colors.secondary).copy(alpha = 0.45f), 0.50f, 0.05f, 5f, 7f, t)
            drawWave(path, Color(colors.highlight).copy(alpha = 0.30f), 0.64f, 0.045f, 4f, -5f, t + 1.3f)
            drawWave(path, Color(colors.ground).copy(alpha = 0.70f), 0.78f, 0.035f, 6.5f, 9f, t + 2.1f)
        },
    )
}

private fun DrawScope.drawWave(
    path: Path,
    color: Color,
    centre: Float,
    amplitude: Float,
    spatial: Float,
    cyclesPerLoop: Float,
    t: Float,
) {
    path.reset()
    val w = size.width
    val h = size.height
    path.moveTo(0f, h)
    for (i in 0..WAVE_SAMPLES) {
        val x = i / WAVE_SAMPLES.toFloat()
        val y = centre + amplitude * sin(x * spatial + t * cyclesPerLoop)
        path.lineTo(x * w, y * h)
    }
    path.lineTo(w, h)
    path.close()
    drawPath(path, color)
}

/** Canvas aurora for Android 12 and older: soft ribbons that drift up and down. */
@Composable
private fun CanvasAurora(
    colors: LiveColors,
    time: () -> Float,
) {
    Box(
        Modifier.fillMaxSize().background(stillBrush(colors, 1f)).drawBehind {
            val t = time() * LOOP_RADIANS
            drawRibbon(Color(colors.secondary), 0.22f + 0.05f * sin(t * 4f), 0.14f, 0.55f)
            drawRibbon(Color(colors.highlight), 0.34f + 0.04f * sin(-t * 3f + 1.7f), 0.10f, 0.40f)
            drawRibbon(Color(colors.secondary), 0.46f + 0.04f * sin(t * 6f + 0.6f), 0.18f, 0.32f)
        },
    )
}

private fun DrawScope.drawRibbon(
    color: Color,
    centre: Float,
    height: Float,
    alpha: Float,
) {
    val h = size.height
    drawRect(
        brush =
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.5f to color.copy(alpha = alpha),
                1f to Color.Transparent,
                startY = (centre - height / 2f) * h,
                endY = (centre + height / 2f) * h,
            ),
        topLeft = Offset(0f, (centre - height / 2f) * h),
        size = androidx.compose.ui.geometry.Size(size.width, height * h),
    )
}

/** Slowly rising, twinkling motes in the highlight colour, drawn on canvas at every API level. */
@Composable
private fun ParticlesLayer(
    colors: LiveColors,
    time: () -> Float,
) {
    val particles = remember { LiveParticles.seed(PARTICLE_COUNT) }
    val mote = Color(colors.highlight)
    Box(
        Modifier.fillMaxSize().background(stillBrush(colors, 1f)).drawBehind {
            val seconds = time()
            val t = seconds * LOOP_RADIANS
            val minSide = minOf(size.width, size.height)
            particles.forEach { p ->
                val y = LiveParticles.yAt(p, seconds)
                val x = p.x + p.sway * sin(t * p.rise + p.y * TWO_PI)
                val twinkle = 0.5f + 0.5f * sin(t * p.twinkle + p.x * TWO_PI)
                // Fade at the top and bottom edges so motes never pop in or out.
                val edge = (minOf(y, 1f - y) * 8f).coerceIn(0f, 1f)
                drawCircle(
                    color = mote.copy(alpha = (0.18f + 0.42f * twinkle) * edge),
                    radius = p.radiusFraction * minSide,
                    center = Offset(x * size.width, y * size.height),
                )
            }
        },
    )
}
