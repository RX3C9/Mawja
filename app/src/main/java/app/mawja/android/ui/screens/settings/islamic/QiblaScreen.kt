/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.screens.settings.islamic

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface as DisplaySurface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.android.R
import app.mawja.android.accessibility.MawjaAccessibility
import app.mawja.android.islamic.MawjaIslamic
import app.mawja.android.islamic.Qibla
import app.mawja.android.ui.components.settings.SettingsPage
import app.mawja.android.ui.components.settings.notice
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The qibla compass: the phone's rotation-vector sensor (corrected from magnetic to true north
 * with the on-device geomagnetic model) turns a dial whose arrow points at the Kaaba. Without a
 * compass it shows the bearing as text. Reduce motion turns off the smoothing animation of the
 * needle, so it follows the sensor directly.
 */
@Composable
internal fun QiblaScreen(onBack: (() -> Unit)?) {
    val settings by MawjaIslamic.settings.collectAsStateWithLifecycle()
    val reduceMotion by MawjaAccessibility.reduceMotion.collectAsStateWithLifecycle()
    val place = settings.place()
    val points = stringArrayResource(R.array.islamic_compass_points)
    SettingsPage(title = stringResource(R.string.islamic_qibla_title), onBack = onBack) {
        if (place == null) {
            notice("qibla.need_place", text = { stringResource(R.string.islamic_qibla_need_place) })
            return@SettingsPage
        }
        val bearing = Qibla.bearing(place.lat, place.lng)
        item("qibla.compass") {
            QiblaCompass(lat = place.lat, lng = place.lng, bearing = bearing, reduceMotion = reduceMotion, points = points)
        }
        notice("qibla.calibrate", text = { stringResource(R.string.islamic_qibla_calibrate) })
    }
}

private class Heading(
    val degrees: Double?,
    val unreliable: Boolean,
)

@Composable
private fun QiblaCompass(
    lat: Double,
    lng: Double,
    bearing: Double,
    reduceMotion: Boolean,
    points: Array<String>,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
    val sensor =
        remember(sensorManager) {
            sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        }
    var heading by remember { mutableStateOf(Heading(null, false)) }
    val declination =
        remember(lat, lng) {
            GeomagneticField(lat.toFloat(), lng.toFloat(), 0f, System.currentTimeMillis()).declination.toDouble()
        }

    if (sensorManager != null && sensor != null) {
        DisposableEffect(sensor, lifecycle, reduceMotion) {
            val matrix = FloatArray(9)
            val remapped = FloatArray(9)
            val orientation = FloatArray(3)
            var unreliable = false
            var smoothed: Double? = null
            val listener =
                object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        SensorManager.getRotationMatrixFromVector(matrix, event.values)
                        val (x, y) =
                            when (view.display?.rotation ?: DisplaySurface.ROTATION_0) {
                                DisplaySurface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                                DisplaySurface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                                DisplaySurface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                                else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                            }
                        SensorManager.remapCoordinateSystem(matrix, x, y, remapped)
                        SensorManager.getOrientation(remapped, orientation)
                        val magnetic = Math.toDegrees(orientation[0].toDouble())
                        val reading = Qibla.normalize(magnetic + declination)
                        // Reduce motion: no easing, the needle simply follows the sensor.
                        smoothed = Qibla.smooth(smoothed, reading, if (reduceMotion) 1.0 else 0.2)
                        heading = Heading(smoothed, unreliable)
                    }

                    override fun onAccuracyChanged(
                        s: Sensor?,
                        accuracy: Int,
                    ) {
                        unreliable = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE || accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
                        heading = Heading(smoothed, unreliable)
                    }
                }
            fun register() = sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            fun unregister() = sensorManager.unregisterListener(listener)
            val observer =
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> register()
                        Lifecycle.Event.ON_PAUSE -> unregister()
                        else -> Unit
                    }
                }
            lifecycle.addObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) register()
            onDispose {
                lifecycle.removeObserver(observer)
                unregister()
            }
        }
    }

    val bearingInt = bearing.roundToInt() % 360
    val point = points[Qibla.compassPoint(bearing)]
    val current = heading.degrees
    val relative = current?.let { Qibla.relative(bearing, it) }
    val aligned = current != null && Qibla.isAligned(bearing, current)
    val instruction =
        when {
            current == null -> stringResource(R.string.islamic_qibla_no_sensor, bearingInt, point)
            aligned -> stringResource(R.string.islamic_qibla_aligned)
            relative!! < 0 -> stringResource(R.string.islamic_qibla_turn_left, abs(relative).roundToInt())
            else -> stringResource(R.string.islamic_qibla_turn_right, relative.roundToInt())
        }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(aligned) { if (aligned) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val dialColor = colors.onSurfaceVariant
            val northColor = colors.error
            val arrowColor = if (aligned) colors.tertiary else colors.primary
            val description = stringResource(R.string.islamic_qibla_cd, instruction)
            Canvas(
                modifier =
                    Modifier
                        .size(240.dp)
                        .clearAndSetSemantics { contentDescription = description },
            ) {
                val radius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(color = dialColor, radius = radius - 4.dp.toPx(), center = center, style = Stroke(width = 3.dp.toPx()))
                // The dial turns so its north mark points at true north.
                rotate(degrees = -(current ?: 0.0).toFloat(), pivot = center) {
                    for (i in 0 until 36) {
                        val long = i % 9 == 0
                        val len = if (long) 16.dp.toPx() else 8.dp.toPx()
                        rotate(degrees = i * 10f, pivot = center) {
                            drawLine(
                                color = if (i == 0) northColor else dialColor,
                                start = Offset(center.x, center.y - radius + 6.dp.toPx()),
                                end = Offset(center.x, center.y - radius + 6.dp.toPx() + len),
                                strokeWidth = if (long) 4.dp.toPx() else 2.dp.toPx(),
                            )
                        }
                    }
                    // Qibla arrow on the dial, at its bearing from north.
                    rotate(degrees = bearing.toFloat(), pivot = center) {
                        val tip = Offset(center.x, center.y - radius + 28.dp.toPx())
                        val half = 14.dp.toPx()
                        val path =
                            Path().apply {
                                moveTo(tip.x, tip.y)
                                lineTo(tip.x - half, tip.y + half * 2.2f)
                                lineTo(tip.x + half, tip.y + half * 2.2f)
                                close()
                            }
                        drawLine(arrowColor, center, Offset(tip.x, tip.y + half), strokeWidth = 6.dp.toPx())
                        drawPath(path, arrowColor)
                        // Kaaba marker: a small square at the tip.
                        val k = 9.dp.toPx()
                        drawRect(
                            color = arrowColor,
                            topLeft = Offset(tip.x - k / 2, tip.y - k * 1.6f),
                            size = androidx.compose.ui.geometry.Size(k, k),
                        )
                    }
                }
                // Fixed index mark: where the top of the phone points.
                drawLine(colors.onSurface, Offset(center.x, 0f), Offset(center.x, 14.dp.toPx()), strokeWidth = 4.dp.toPx())
                drawCircle(colors.onSurface, radius = 6.dp.toPx(), center = center)
            }
            Text(
                text = instruction,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = if (aligned) FontWeight.Bold else FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text =
                    stringResource(
                        R.string.islamic_qibla_summary,
                        bearingInt,
                        point,
                        "%,d".format(Math.round(Qibla.distanceKm(lat, lng))),
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (heading.unreliable) {
                Text(
                    text = stringResource(R.string.islamic_qibla_unreliable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
