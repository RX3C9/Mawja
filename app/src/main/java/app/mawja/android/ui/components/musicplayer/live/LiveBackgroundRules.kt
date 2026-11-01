/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.components.musicplayer.live

import app.mawja.android.account.Entitlements
import app.mawja.android.account.MawjaFeature
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Live full-player backgrounds (#165). [OFF] keeps Flow's own player look and is the default;
 * every other option is a Plus extra ([MawjaFeature.LIVE_BACKGROUNDS]).
 */
enum class LiveBackgroundStyle(
    val id: String,
    /** True for styles that move; [STATIC_BLUR] never does. */
    val animates: Boolean,
) {
    OFF("off", false),
    WAVES("waves", true),
    AURORA("aurora", true),
    PARTICLES("particles", true),
    STATIC_BLUR("static_blur", false),
    ;

    companion object {
        /** Unknown or missing ids read as [OFF], so a removed option never strands a user. */
        fun of(id: String?): LiveBackgroundStyle = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/** Which live background the user may actually see. Pure, so it is covered by JVM tests. */
object LiveBackgroundGate {
    fun isLocked(
        style: LiveBackgroundStyle,
        entitlements: Entitlements,
    ): Boolean = style != LiveBackgroundStyle.OFF && !entitlements.has(MawjaFeature.LIVE_BACKGROUNDS)

    /** The stored choice, or [LiveBackgroundStyle.OFF] when the membership no longer covers it. */
    fun effective(
        selected: LiveBackgroundStyle,
        entitlements: Entitlements,
    ): LiveBackgroundStyle = if (isLocked(selected, entitlements)) LiveBackgroundStyle.OFF else selected
}

/**
 * When a live background may move. Anything else draws one still gradient frame: the player is
 * hidden or behind other UI, the app is in the background, reduce motion is on, or battery saver is.
 */
object LiveMotionPolicy {
    fun shouldAnimate(
        style: LiveBackgroundStyle,
        playerVisible: Boolean,
        appForeground: Boolean,
        reduceMotion: Boolean,
        batterySaver: Boolean,
    ): Boolean = style.animates && playerVisible && appForeground && !reduceMotion && !batterySaver
}

/**
 * 30 fps pacing for the live backgrounds. Frames arrive on vsync (60, 90, 120 Hz); a frame is
 * drawn only once a full 30 fps interval (less a small tolerance for vsync jitter) has passed.
 * Time handed to the shaders wraps every [LOOP_SECONDS]; every motion uses a whole number of
 * cycles per loop, so the wrap is seamless and floats never lose precision on long sessions.
 */
object LiveFramePacing {
    const val TARGET_FPS = 30
    const val FRAME_NANOS = 1_000_000_000L / TARGET_FPS
    const val TOLERANCE_NANOS = 4_000_000L
    const val LOOP_SECONDS = 120f
    private const val LOOP_NANOS = 120_000_000_000L

    /** Whether a frame at [nowNanos] should draw, given the last drawn frame (negative for none). */
    fun shouldDraw(
        lastFrameNanos: Long,
        nowNanos: Long,
    ): Boolean = lastFrameNanos < 0 || nowNanos - lastFrameNanos >= FRAME_NANOS - TOLERANCE_NANOS

    /** Seconds since [startNanos], wrapped into [0, LOOP_SECONDS). */
    fun wrappedSeconds(
        startNanos: Long,
        nowNanos: Long,
    ): Float {
        val elapsed = Math.floorMod(nowNanos - startNanos, LOOP_NANOS)
        return (elapsed / 1_000_000L).toFloat() / 1000f
    }

    /** The start that makes [wrappedSeconds] continue from [seconds] at [nowNanos] after a pause. */
    fun resumeStart(
        nowNanos: Long,
        seconds: Float,
    ): Long = nowNanos - (seconds.toDouble() * 1_000_000_000.0).toLong()
}

/** The four colours a live background draws with, as ARGB ints. */
data class LiveColors(
    val ground: Int,
    val primary: Int,
    val secondary: Int,
    val highlight: Int,
    /** True when the player's text is light, so every colour here is dark. */
    val dark: Boolean,
) {
    val all: List<Int> get() = listOf(ground, primary, secondary, highlight)
}

/**
 * Derives [LiveColors] from the cover's base and accent. Dark players cap every colour's
 * luminance so white text keeps at least 4.5:1 anywhere on the background; light players lift
 * every colour to a pale tone that dark text reads on. The shaders and canvas layers only ever
 * mix these colours (convex blends in sRGB), and sRGB decoding is convex, so a blend is never
 * brighter (or darker) than the brightest (darkest) colour it came from.
 */
object LiveColorDerivation {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val MIN_TEXT_CONTRAST = 4.5

    /** Highest luminance with white at 4.5:1: (1.05 / 4.5) - 0.05, kept a hair under. */
    const val DARK_MAX_LUMINANCE = 0.180

    /** Lowest luminance for the pale light-player tones. */
    const val LIGHT_MIN_LUMINANCE = 0.62

    fun derive(
        baseArgb: Int,
        accentArgb: Int,
        dark: Boolean,
    ): LiveColors =
        if (dark) {
            LiveColors(
                ground = capLuminance(mix(baseArgb, BLACK, 0.82f), 0.020),
                primary = capLuminance(mix(baseArgb, BLACK, 0.35f), 0.110),
                secondary = capLuminance(mix(accentArgb, BLACK, 0.25f), 0.150),
                highlight = capLuminance(mix(accentArgb, WHITE, 0.18f), DARK_MAX_LUMINANCE),
                dark = true,
            )
        } else {
            LiveColors(
                ground = floorLuminance(mix(baseArgb, WHITE, 0.90f), 0.86),
                primary = floorLuminance(mix(baseArgb, WHITE, 0.70f), 0.72),
                secondary = floorLuminance(mix(accentArgb, WHITE, 0.62f), 0.66),
                highlight = floorLuminance(mix(accentArgb, WHITE, 0.50f), LIGHT_MIN_LUMINANCE),
                dark = false,
            )
        }

    /** WCAG relative luminance of an opaque ARGB colour. */
    fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    fun contrast(
        a: Int,
        b: Int,
    ): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Linear blend of two opaque colours in sRGB space; [t] = 0 is [a]. */
    fun mix(
        a: Int,
        b: Int,
        t: Float,
    ): Int {
        val f = t.coerceIn(0f, 1f)

        fun ch(shift: Int): Int {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            return (ca + (cb - ca) * f + 0.5f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Darkens towards black until the luminance is at most [maxLum]; hue stays. */
    fun capLuminance(
        argb: Int,
        maxLum: Double,
    ): Int {
        val opaque = argb or (0xFF shl 24)
        if (luminance(opaque) <= maxLum) return opaque
        var lo = 0f
        var hi = 1f
        repeat(24) {
            val mid = (lo + hi) / 2f
            if (luminance(mix(opaque, BLACK, mid)) <= maxLum) hi = mid else lo = mid
        }
        return mix(opaque, BLACK, hi)
    }

    /** Lightens towards white until the luminance is at least [minLum]. */
    fun floorLuminance(
        argb: Int,
        minLum: Double,
    ): Int {
        val opaque = argb or (0xFF shl 24)
        if (luminance(opaque) >= minLum) return opaque
        var lo = 0f
        var hi = 1f
        repeat(24) {
            val mid = (lo + hi) / 2f
            if (luminance(mix(opaque, WHITE, mid)) >= minLum) hi = mid else lo = mid
        }
        return mix(opaque, WHITE, hi)
    }

    private const val BLACK = 0xFF000000.toInt()
}

/** Seeded, deterministic particles; positions repeat exactly every [LiveFramePacing.LOOP_SECONDS]. */
object LiveParticles {
    data class Particle(
        val x: Float,
        val y: Float,
        val radiusFraction: Float,
        val rise: Int,
        val sway: Float,
        val twinkle: Int,
    )

    fun seed(
        count: Int,
        seed: Int = 165,
    ): List<Particle> {
        val random = java.util.Random(seed.toLong())
        return List(count.coerceAtLeast(0)) {
            Particle(
                x = random.nextFloat(),
                y = random.nextFloat(),
                radiusFraction = 0.004f + random.nextFloat() * 0.010f,
                rise = 2 + random.nextInt(5),
                sway = 0.01f + random.nextFloat() * 0.03f,
                twinkle = 3 + random.nextInt(8),
            )
        }
    }

    /** Vertical position in [0, 1) after [seconds]: [Particle.rise] whole trips per loop. */
    fun yAt(
        particle: Particle,
        seconds: Float,
    ): Float {
        val travelled = particle.y - particle.rise * (seconds / LiveFramePacing.LOOP_SECONDS)
        return travelled - kotlin.math.floor(travelled)
    }
}
