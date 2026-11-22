/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

/** Bedtime mode: a warm amber tint that fades in after the chosen hour and fades out toward morning. */
object BedtimeFilter {
    /** The tint is gone by this minute of the day (06:00). */
    const val END_MINUTE = 6 * 60

    private const val RAMP_MINUTES = 30
    private const val DAY_MINUTES = 24 * 60

    /** 0 outside the window, easing up to 1 over the first half hour and back down over the last. */
    fun strength(
        minuteOfDay: Int,
        startMinute: Int,
        endMinute: Int = END_MINUTE,
    ): Float {
        val length = (endMinute - startMinute + DAY_MINUTES) % DAY_MINUTES
        if (length == 0) return 0f
        val into = (minuteOfDay - startMinute + DAY_MINUTES) % DAY_MINUTES
        if (into >= length) return 0f
        val edge = minOf(into, length - into)
        return (edge.toFloat() / RAMP_MINUTES).coerceIn(0f, 1f)
    }
}

/** Draws the bedtime tint over the whole app. It never takes a touch. */
@Composable
fun BedtimeOverlay() {
    val on by MawjaLab.bedtime.collectAsState()
    val startHour by MawjaLab.bedtimeStartHour.collectAsState()
    var strength by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(on, startHour) {
        if (!on) {
            strength = 0f
            return@LaunchedEffect
        }
        while (true) {
            val now = java.util.Calendar.getInstance()
            val minute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
            strength = BedtimeFilter.strength(minute, startHour * 60)
            delay(60_000L)
        }
    }
    if (on && strength > 0f) {
        Box(Modifier.fillMaxSize().background(Color(0xFFFF9A3C).copy(alpha = strength * 0.16f)))
    }
}
