/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import app.mawja.android.player.EnhancedPlayerManager
import kotlin.math.sqrt

/** Listens for a light shake only while a video plays and the Lab switch is on; rewinds to the sentence. */
@Composable
fun ShakeRewindHost() {
    val enabled by MawjaLab.shakeRewind.collectAsState()
    val manager = EnhancedPlayerManager.getInstance()
    val state by manager.playerState.collectAsState()
    val active = enabled && state.isPlaying && !state.isLive
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose { }
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var last = 0L
        val listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                    val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                    val now = System.currentTimeMillis()
                    if (g > SHAKE_G && now - last > COOLDOWN_MS) {
                        last = now
                        val pos = manager.getCurrentPosition()
                        val id = manager.playerState.value.currentVideoId
                        manager.seekTo(SentenceRewind.target(pos, MawjaLab.cuesFor(id)))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }

                override fun onAccuracyChanged(
                    sensor: Sensor?,
                    accuracy: Int,
                ) = Unit
            }
        if (accel != null) sensors?.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensors?.unregisterListener(listener) }
    }
}

private const val SHAKE_G = 2.6f
private const val COOLDOWN_MS = 1_500L
