/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class AccessibilityPolicyTest {
    private fun argb(hex: Long): Int = hex.toInt()

    // --- #161 text size -------------------------------------------------------------------------

    @Test
    fun `system text size follows the system font scale exactly`() {
        assertEquals(1f, AccessibilityPolicy.effectiveFontScale(1f, TextSizeMode.SYSTEM), 0.0001f)
        assertEquals(1.3f, AccessibilityPolicy.effectiveFontScale(1.3f, TextSizeMode.SYSTEM), 0.0001f)
        assertEquals(2.0f, AccessibilityPolicy.effectiveFontScale(2.0f, TextSizeMode.SYSTEM), 0.0001f)
    }

    @Test
    fun `the in-app boost multiplies the system scale`() {
        assertEquals(1.15f, AccessibilityPolicy.effectiveFontScale(1f, TextSizeMode.LARGE), 0.0001f)
        assertEquals(1.3f, AccessibilityPolicy.effectiveFontScale(1f, TextSizeMode.LARGEST), 0.0001f)
        assertEquals(1.495f, AccessibilityPolicy.effectiveFontScale(1.15f, TextSizeMode.LARGEST), 0.0001f)
    }

    @Test
    fun `the boost is capped but never shrinks a large system size`() {
        assertEquals(2.0f, AccessibilityPolicy.effectiveFontScale(1.8f, TextSizeMode.LARGEST), 0.0001f)
        // Android 14 allows 200%; the boost may not push past it, nor bring it down.
        assertEquals(2.0f, AccessibilityPolicy.effectiveFontScale(2.0f, TextSizeMode.LARGE), 0.0001f)
        TextSizeMode.entries.forEach { mode ->
            listOf(0.85f, 1f, 1.3f, 1.8f, 2f).forEach { system ->
                assertTrue("$mode $system", AccessibilityPolicy.effectiveFontScale(system, mode) >= system - 0.0001f)
            }
        }
    }

    @Test
    fun `a broken system scale reads as normal`() {
        assertEquals(1f, AccessibilityPolicy.effectiveFontScale(Float.NaN, TextSizeMode.SYSTEM), 0.0001f)
        assertEquals(1f, AccessibilityPolicy.effectiveFontScale(0f, TextSizeMode.SYSTEM), 0.0001f)
    }

    @Test
    fun `stored keys round trip and unknown keys fall back`() {
        TextSizeMode.entries.forEach { assertEquals(it, TextSizeMode.fromKey(it.key)) }
        ColorVisionMode.entries.forEach { assertEquals(it, ColorVisionMode.fromKey(it.key)) }
        assertEquals(TextSizeMode.SYSTEM, TextSizeMode.fromKey(null))
        assertEquals(TextSizeMode.SYSTEM, TextSizeMode.fromKey("huge"))
        assertEquals(ColorVisionMode.DEFAULT, ColorVisionMode.fromKey("purple"))
    }

    // --- #163 reduce motion ---------------------------------------------------------------------

    @Test
    fun `reduce motion follows the switch, the system and the battery saver`() {
        val off = AccessibilitySettings()
        assertFalse(AccessibilityPolicy.reduceMotion(off, 1f, batterySaverReducesMotion = false))
        assertFalse(AccessibilityPolicy.reduceMotion(off, 0.5f, batterySaverReducesMotion = false))
        assertTrue(AccessibilityPolicy.reduceMotion(off, 0f, batterySaverReducesMotion = false))
        assertTrue(AccessibilityPolicy.reduceMotion(off, 1f, batterySaverReducesMotion = true))
        assertTrue(AccessibilityPolicy.reduceMotion(off.copy(reduceMotion = true), 1f, batterySaverReducesMotion = false))
    }

    @Test
    fun `only an animator scale of zero means animations off`() {
        assertTrue(AccessibilityPolicy.systemAnimationsOff(0f))
        assertFalse(AccessibilityPolicy.systemAnimationsOff(0.5f))
        assertFalse(AccessibilityPolicy.systemAnimationsOff(1f))
        assertFalse(AccessibilityPolicy.systemAnimationsOff(10f))
    }

    // --- #161 contrast --------------------------------------------------------------------------

    @Test
    fun `contrast ratio matches WCAG reference values`() {
        assertEquals(21.0, AccessibilityPolicy.contrastRatio(argb(0xFF000000), argb(0xFFFFFFFF)), 0.01)
        assertEquals(1.0, AccessibilityPolicy.contrastRatio(argb(0xFF777777), argb(0xFF777777)), 0.0001)
        // #767676 on white is the classic 4.54:1 AA boundary.
        assertEquals(4.54, AccessibilityPolicy.contrastRatio(argb(0xFF767676), argb(0xFFFFFFFF)), 0.01)
    }

    @Test
    fun `ensureContrast reaches the target on dark and light surfaces`() {
        val surfaces = listOf(0xFF121212, 0xFF000000, 0xFF1E1B2E, 0xFF2B2930, 0xFFFFFFFF, 0xFFFFFBFE, 0xFFF3EDF7).map(::argb)
        val inks = listOf(0xFF8A8A8A, 0xFF5C5C5C, 0xFFB0A8C0, 0xFF6750A4, 0xFFD0BCFF, 0xFFE8384F, 0xFF3A3A3A).map(::argb)
        listOf(3.0, 4.5, 7.0).forEach { target ->
            surfaces.forEach { bg ->
                inks.forEach { fg ->
                    val out = AccessibilityPolicy.ensureContrast(fg, bg, target)
                    assertTrue(
                        "fg=${Integer.toHexString(fg)} bg=${Integer.toHexString(bg)} target=$target",
                        AccessibilityPolicy.contrastRatio(out, bg) >= target,
                    )
                    assertEquals("opaque", 0xFF, (out ushr 24) and 0xFF)
                }
            }
        }
    }

    @Test
    fun `ensureContrast leaves a colour that already passes untouched`() {
        val white = argb(0xFFFFFFFF)
        val dark = argb(0xFF121212)
        assertEquals(white, AccessibilityPolicy.ensureContrast(white, dark, 7.0))
    }

    @Test
    fun `ensureContrast moves as little as it can, keeping the hue`() {
        val bg = argb(0xFF121212)
        val purple = argb(0xFF6750A4)
        val out = AccessibilityPolicy.ensureContrast(purple, bg, 4.5)
        assertNotEquals(argb(0xFFFFFFFF), out)
        val r = (out shr 16) and 0xFF
        val b = out and 0xFF
        assertTrue("still bluer than red", b > r)
    }

    @Test
    fun `an unreachable target returns the strongest extreme`() {
        val mid = argb(0xFF777777)
        val out = AccessibilityPolicy.ensureContrast(mid, mid, 21.0)
        assertTrue(out == argb(0xFF000000) || out == argb(0xFFFFFFFF))
    }

    @Test
    fun `best text colour is black on light and white on dark`() {
        assertEquals(argb(0xFF000000), AccessibilityPolicy.bestTextOn(argb(0xFFFFFFFF)))
        assertEquals(argb(0xFFFFFFFF), AccessibilityPolicy.bestTextOn(argb(0xFF101010)))
    }

    // --- #162 colour vision ---------------------------------------------------------------------

    @Test
    fun `default mode keeps theme and SponsorBlock colours`() {
        assertNull(AccessibilityPolicy.statePalette(ColorVisionMode.DEFAULT, dark = true))
        assertNull(AccessibilityPolicy.sponsorBlockColor("sponsor", ColorVisionMode.DEFAULT))
    }

    @Test
    fun `success and error stay apart for the vision they are built for`() {
        val cases =
            listOf(
                ColorVisionMode.RED_GREEN to ::deuteranopia,
                ColorVisionMode.RED_GREEN to ::protanopia,
                ColorVisionMode.BLUE_YELLOW to ::tritanopia,
            )
        cases.forEach { (mode, vision) ->
            listOf(true, false).forEach { dark ->
                val p = AccessibilityPolicy.statePalette(mode, dark)!!
                val distance = rgbDistance(vision(p.success), vision(p.error))
                assertTrue("$mode dark=$dark distance=$distance", distance > 120.0)
            }
        }
    }

    @Test
    fun `state colours read on their theme surface`() {
        ColorVisionMode.entries.filter { it != ColorVisionMode.DEFAULT }.forEach { mode ->
            val dark = AccessibilityPolicy.statePalette(mode, dark = true)!!
            val light = AccessibilityPolicy.statePalette(mode, dark = false)!!
            val darkSurface = argb(0xFF121212)
            val lightSurface = argb(0xFFFFFFFF)
            assertTrue("$mode dark success", AccessibilityPolicy.contrastRatio(dark.success, darkSurface) >= 4.5)
            assertTrue("$mode dark error", AccessibilityPolicy.contrastRatio(dark.error, darkSurface) >= 4.5)
            assertTrue("$mode light success", AccessibilityPolicy.contrastRatio(light.success, lightSurface) >= 4.5)
            assertTrue("$mode light error", AccessibilityPolicy.contrastRatio(light.error, lightSurface) >= 4.5)
            assertTrue("$mode dark onError", AccessibilityPolicy.contrastRatio(dark.onError, dark.error) >= 4.5)
            assertTrue("$mode light onError", AccessibilityPolicy.contrastRatio(light.onError, light.error) >= 4.5)
        }
    }

    @Test
    fun `every SponsorBlock category gets a colour and intro shares with outro`() {
        val categories = listOf("sponsor", "selfpromo", "interaction", "intro", "outro", "music_offtopic", "filler", "preview")
        listOf(ColorVisionMode.RED_GREEN, ColorVisionMode.BLUE_YELLOW).forEach { mode ->
            categories.forEach { assertTrue("$mode $it", AccessibilityPolicy.sponsorBlockColor(it, mode) != null) }
            assertEquals(
                AccessibilityPolicy.sponsorBlockColor("intro", mode),
                AccessibilityPolicy.sponsorBlockColor("outro", mode),
            )
            assertEquals(
                "unknown falls back to sponsor",
                AccessibilityPolicy.sponsorBlockColor("sponsor", mode),
                AccessibilityPolicy.sponsorBlockColor("chapter_new_kind", mode),
            )
        }
    }

    @Test
    fun `segment colours stay distinct under the simulated vision`() {
        val cases =
            listOf(
                AccessibilityPolicy.RED_GREEN_SEGMENTS to ::deuteranopia,
                AccessibilityPolicy.BLUE_YELLOW_SEGMENTS to ::tritanopia,
            )
        cases.forEach { (palette, vision) ->
            val colours = palette.values.toList()
            assertEquals("no two categories share a colour", colours.size, colours.toSet().size)
            for (i in colours.indices) {
                for (j in i + 1 until colours.size) {
                    val d = rgbDistance(vision(colours[i]), vision(colours[j]))
                    assertTrue("${Integer.toHexString(colours[i])} vs ${Integer.toHexString(colours[j])}: $d", d > 25.0)
                }
            }
        }
    }

    // --- #160 TalkBack text ---------------------------------------------------------------------

    @Test
    fun `percent rounds and clamps`() {
        assertEquals(0, AccessibilityPolicy.percentOf(0f))
        assertEquals(45, AccessibilityPolicy.percentOf(0.449f))
        assertEquals(100, AccessibilityPolicy.percentOf(1.2f))
        assertEquals(0, AccessibilityPolicy.percentOf(-0.3f))
        assertEquals(0, AccessibilityPolicy.percentOf(Float.NaN))
    }

    @Test
    fun `duration splits into hours minutes and seconds`() {
        assertEquals(Triple(0, 0, 0), AccessibilityPolicy.durationParts(0))
        assertEquals(Triple(0, 12, 4), AccessibilityPolicy.durationParts(724))
        assertEquals(Triple(1, 4, 2), AccessibilityPolicy.durationParts(3842))
        assertEquals(Triple(10, 0, 0), AccessibilityPolicy.durationParts(36000))
        assertEquals(Triple(0, 0, 0), AccessibilityPolicy.durationParts(-5))
    }

    // --- Colour-vision simulation (Machado, Oliveira and Fernandes 2009, severity 1.0) ----------

    private fun linear(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun gamma(v: Double): Double {
        val c = v.coerceIn(0.0, 1.0)
        return 255.0 * (if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055)
    }

    private fun simulateVision(
        argb: Int,
        m: DoubleArray,
    ): DoubleArray {
        val r = linear((argb shr 16) and 0xFF)
        val g = linear((argb shr 8) and 0xFF)
        val b = linear(argb and 0xFF)
        return doubleArrayOf(
            gamma(m[0] * r + m[1] * g + m[2] * b),
            gamma(m[3] * r + m[4] * g + m[5] * b),
            gamma(m[6] * r + m[7] * g + m[8] * b),
        )
    }

    private fun deuteranopia(argb: Int) =
        simulateVision(argb, doubleArrayOf(0.367322, 0.860646, -0.227968, 0.280085, 0.672501, 0.047413, -0.011820, 0.042940, 0.968881))

    private fun protanopia(argb: Int) =
        simulateVision(argb, doubleArrayOf(0.152286, 1.052583, -0.204868, 0.114503, 0.786281, 0.099216, -0.003882, -0.048116, 1.051998))

    private fun tritanopia(argb: Int) =
        simulateVision(argb, doubleArrayOf(1.255528, -0.076749, -0.178779, -0.078411, 0.930809, 0.147602, 0.004733, 0.691367, 0.303900))

    private fun rgbDistance(
        a: DoubleArray,
        b: DoubleArray,
    ): Double = kotlin.math.sqrt((0..2).sumOf { (a[it] - b[it]).pow(2) })
}
