/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.islamic

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Qibla: the great-circle direction from anywhere to the Kaaba. */
object Qibla {
    const val KAABA_LAT = 21.4225
    const val KAABA_LNG = 39.8262
    private const val EARTH_RADIUS_KM = 6371.0

    /** Initial bearing in degrees clockwise from true north, 0 until < 360. */
    fun bearing(
        lat: Double,
        lng: Double,
    ): Double {
        val phi = Math.toRadians(lat)
        val phiK = Math.toRadians(KAABA_LAT)
        val dLng = Math.toRadians(KAABA_LNG - lng)
        val deg = Math.toDegrees(atan2(sin(dLng), cos(phi) * tan(phiK) - sin(phi) * cos(dLng)))
        return normalize(deg)
    }

    /** Great-circle distance to the Kaaba in kilometres (haversine). */
    fun distanceKm(
        lat: Double,
        lng: Double,
    ): Double {
        val dPhi = Math.toRadians(KAABA_LAT - lat)
        val dLng = Math.toRadians(KAABA_LNG - lng)
        val a = sin(dPhi / 2) * sin(dPhi / 2) + cos(Math.toRadians(lat)) * cos(Math.toRadians(KAABA_LAT)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /**
     * Where the qibla is relative to the top of the phone, -180..180 degrees: negative means turn
     * left. [heading] is the phone's direction from true north.
     */
    fun relative(
        qibla: Double,
        heading: Double,
    ): Double {
        val r = normalize(qibla - heading)
        return if (r > 180.0) r - 360.0 else r
    }

    /** Whether the phone points at the qibla, within [toleranceDeg]. */
    fun isAligned(
        qibla: Double,
        heading: Double,
        toleranceDeg: Double = 5.0,
    ): Boolean = kotlin.math.abs(relative(qibla, heading)) <= toleranceDeg

    /** The nearest of 8 compass points, 0 = N, 1 = NE, ... 7 = NW. */
    fun compassPoint(bearing: Double): Int = (normalize(bearing) / 45.0).roundToInt() % 8

    /**
     * Low-pass filter for compass readings that wraps across north, so 359° and 1° average to 0°,
     * not 180°. [alpha] 1 = no smoothing.
     */
    fun smooth(
        previous: Double?,
        reading: Double,
        alpha: Double,
    ): Double {
        if (previous == null) return normalize(reading)
        val delta = relative(reading, previous)
        return normalize(previous + alpha * delta)
    }

    fun normalize(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0) r + 360.0 else r
    }
}
