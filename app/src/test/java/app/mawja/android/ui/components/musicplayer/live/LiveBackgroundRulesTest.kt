/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.components.musicplayer.live

import app.mawja.android.account.Entitlements
import app.mawja.android.account.MawjaFeature
import app.mawja.android.account.MawjaTier
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveBackgroundRulesTest {
    // --- Styles and gating ---

    @Test
    fun unknownOrMissingIdReadsAsOff() {
        assertThat(LiveBackgroundStyle.of(null)).isEqualTo(LiveBackgroundStyle.OFF)
        assertThat(LiveBackgroundStyle.of("lava")).isEqualTo(LiveBackgroundStyle.OFF)
        LiveBackgroundStyle.entries.forEach { assertThat(LiveBackgroundStyle.of(it.id)).isEqualTo(it) }
    }

    @Test
    fun offIsTheDefaultAndNeverLocked() {
        MawjaTier.entries.forEach { tier ->
            assertThat(LiveBackgroundGate.isLocked(LiveBackgroundStyle.OFF, Entitlements.forTier(tier))).isFalse()
        }
    }

    @Test
    fun liveStylesNeedPlus() {
        val free = Entitlements.FREE
        val plus = Entitlements.forTier(MawjaTier.PLUS)
        LiveBackgroundStyle.entries.filter { it != LiveBackgroundStyle.OFF }.forEach { style ->
            assertThat(LiveBackgroundGate.isLocked(style, free)).isTrue()
            assertThat(LiveBackgroundGate.effective(style, free)).isEqualTo(LiveBackgroundStyle.OFF)
            assertThat(LiveBackgroundGate.isLocked(style, plus)).isFalse()
            assertThat(LiveBackgroundGate.effective(style, plus)).isEqualTo(style)
        }
        assertThat(Entitlements.minimumTier(MawjaFeature.LIVE_BACKGROUNDS)).isEqualTo(MawjaTier.PLUS)
        listOf(MawjaTier.PRO, MawjaTier.LIFETIME, MawjaTier.FAMILY).forEach {
            assertThat(Entitlements.forTier(it).has(MawjaFeature.LIVE_BACKGROUNDS)).isTrue()
        }
    }

    // --- Motion policy ---

    @Test
    fun animatesOnlyWhenEveryConditionHolds() {
        fun animate(
            style: LiveBackgroundStyle = LiveBackgroundStyle.WAVES,
            visible: Boolean = true,
            foreground: Boolean = true,
            reduceMotion: Boolean = false,
            batterySaver: Boolean = false,
        ) = LiveMotionPolicy.shouldAnimate(style, visible, foreground, reduceMotion, batterySaver)

        assertThat(animate()).isTrue()
        assertThat(animate(style = LiveBackgroundStyle.AURORA)).isTrue()
        assertThat(animate(style = LiveBackgroundStyle.PARTICLES)).isTrue()
        assertThat(animate(style = LiveBackgroundStyle.OFF)).isFalse()
        assertThat(animate(style = LiveBackgroundStyle.STATIC_BLUR)).isFalse()
        assertThat(animate(visible = false)).isFalse()
        assertThat(animate(foreground = false)).isFalse()
        assertThat(animate(reduceMotion = true)).isFalse()
        assertThat(animate(batterySaver = true)).isFalse()
    }

    // --- Frame pacing ---

    private fun framesDrawnInOneSecond(refreshHz: Int): Int {
        var last = -1L
        var drawn = 0
        for (i in 0 until refreshHz) {
            val now = i * 1_000_000_000L / refreshHz
            if (LiveFramePacing.shouldDraw(last, now)) {
                last = now
                drawn++
            }
        }
        return drawn
    }

    @Test
    fun pacesToThirtyFramesPerSecondOnCommonDisplays() {
        listOf(60, 90, 120, 144).forEach { hz ->
            assertThat(framesDrawnInOneSecond(hz)).isIn(28..31)
        }
    }

    @Test
    fun firstFrameAlwaysDraws() {
        assertThat(LiveFramePacing.shouldDraw(-1L, 0L)).isTrue()
        assertThat(LiveFramePacing.shouldDraw(-1L, 123_456L)).isTrue()
    }

    @Test
    fun timeWrapsAtTheLoop() {
        val start = 5_000_000_000L
        assertThat(LiveFramePacing.wrappedSeconds(start, start)).isEqualTo(0f)
        assertThat(LiveFramePacing.wrappedSeconds(start, start + 1_500_000_000L)).isWithin(1e-3f).of(1.5f)
        val afterOneLoop = start + 121_000_000_000L
        assertThat(LiveFramePacing.wrappedSeconds(start, afterOneLoop)).isWithin(1e-3f).of(1f)
        val longSession = start + 10L * 3_600_000_000_000L + 250_000_000L
        val t = LiveFramePacing.wrappedSeconds(start, longSession)
        assertThat(t).isAtLeast(0f)
        assertThat(t).isLessThan(LiveFramePacing.LOOP_SECONDS)
    }

    @Test
    fun resumeContinuesFromTheHeldTime() {
        val now = 900_000_000_000L
        val start = LiveFramePacing.resumeStart(now, 42.25f)
        assertThat(LiveFramePacing.wrappedSeconds(start, now)).isWithin(1e-3f).of(42.25f)
    }

    // --- Colour derivation ---

    private val samples =
        listOf(
            0xFFFFFFFF.toInt(),
            0xFFFFFF00.toInt(),
            0xFF00FFFF.toInt(),
            0xFFFF0000.toInt(),
            0xFF00FF00.toInt(),
            0xFF0000FF.toInt(),
            0xFF000000.toInt(),
            0xFF808080.toInt(),
            0xFFE8C07A.toInt(),
            0xFF1B4D3E.toInt(),
            0xFFF5F5DC.toInt(),
        )

    @Test
    fun darkColoursKeepWhiteTextReadable() {
        samples.forEach { base ->
            samples.forEach { accent ->
                val colors = LiveColorDerivation.derive(base, accent, dark = true)
                assertThat(colors.dark).isTrue()
                colors.all.forEach { c ->
                    assertThat(LiveColorDerivation.contrast(LiveColorDerivation.WHITE, c))
                        .isAtLeast(LiveColorDerivation.MIN_TEXT_CONTRAST)
                }
            }
        }
    }

    @Test
    fun blendsOfDarkColoursStayReadable() {
        val colors = LiveColorDerivation.derive(0xFFFFFF00.toInt(), 0xFFFFFFFF.toInt(), dark = true)
        for (a in colors.all) {
            for (b in colors.all) {
                for (step in 0..10) {
                    val blend = LiveColorDerivation.mix(a, b, step / 10f)
                    assertThat(LiveColorDerivation.contrast(LiveColorDerivation.WHITE, blend))
                        .isAtLeast(LiveColorDerivation.MIN_TEXT_CONTRAST)
                }
            }
        }
    }

    @Test
    fun lightColoursArePaleEnoughForDarkText() {
        val nearBlack = 0xFF1C1B1F.toInt()
        samples.forEach { base ->
            samples.forEach { accent ->
                val colors = LiveColorDerivation.derive(base, accent, dark = false)
                assertThat(colors.dark).isFalse()
                colors.all.forEach { c ->
                    assertThat(LiveColorDerivation.luminance(c)).isAtLeast(LiveColorDerivation.LIGHT_MIN_LUMINANCE - 0.01)
                    assertThat(LiveColorDerivation.contrast(nearBlack, c)).isAtLeast(LiveColorDerivation.MIN_TEXT_CONTRAST)
                }
            }
        }
    }

    @Test
    fun derivedColoursAreOpaqueAndKeepSomeHue() {
        val colors = LiveColorDerivation.derive(0xFFB03A2E.toInt(), 0xFF2E86C1.toInt(), dark = true)
        colors.all.forEach { assertThat(it ushr 24).isEqualTo(0xFF) }
        // The accent-led secondary stays bluer than red.
        val r = (colors.secondary shr 16) and 0xFF
        val b = colors.secondary and 0xFF
        assertThat(b).isGreaterThan(r)
    }

    @Test
    fun luminanceMatchesWcagEndpoints() {
        assertThat(LiveColorDerivation.luminance(0xFF000000.toInt())).isWithin(1e-9).of(0.0)
        assertThat(LiveColorDerivation.luminance(0xFFFFFFFF.toInt())).isWithin(1e-9).of(1.0)
        assertThat(LiveColorDerivation.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt())).isWithin(1e-6).of(21.0)
    }

    // --- Particles ---

    @Test
    fun particlesAreDeterministicAndLoopSeamlessly() {
        val a = LiveParticles.seed(20)
        val b = LiveParticles.seed(20)
        assertThat(a).isEqualTo(b)
        a.forEach { p ->
            val start = LiveParticles.yAt(p, 0f)
            val end = LiveParticles.yAt(p, LiveFramePacing.LOOP_SECONDS)
            assertThat(start).isAtLeast(0f)
            assertThat(start).isLessThan(1f)
            // Whole trips per loop: back where it started (mod 1).
            val diff = kotlin.math.abs(start - end)
            assertThat(minOf(diff, 1f - diff)).isLessThan(1e-3f)
        }
    }
}
