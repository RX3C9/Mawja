/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.islamic

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

/** The five daily prayers plus sunrise, in the order of the day. */
enum class Prayer(
    val key: String,
) {
    FAJR("fajr"),
    SUNRISE("sunrise"),
    DHUHR("dhuhr"),
    ASR("asr"),
    MAGHRIB("maghrib"),
    ISHA("isha"),
    ;

    /** Sunrise is shown but is not a prayer, so it never pauses playback or notifies. */
    val isPrayer: Boolean get() = this != SUNRISE
}

/**
 * Calculation conventions. Isha is either an angle below the horizon or a fixed time after
 * Maghrib (Umm al-Qura: 90 minutes, 120 in Ramadan).
 */
enum class CalcMethod(
    val key: String,
    val fajrAngle: Double,
    val ishaAngle: Double?,
    val ishaMinutes: Int?,
) {
    EGYPTIAN("egyptian", 19.5, 17.5, null),
    UMM_AL_QURA("umm_al_qura", 18.5, null, 90),
    MWL("mwl", 18.0, 17.0, null),
    ISNA("isna", 15.0, 15.0, null),
    KARACHI("karachi", 18.0, 18.0, null),
    ;

    companion object {
        fun fromKey(key: String?): CalcMethod? = entries.firstOrNull { it.key == key }

        /** The convention most people use in [countryCode] (ISO 3166 alpha-2); Egypt's authority by default. */
        fun defaultFor(countryCode: String?): CalcMethod =
            when (val c = countryCode?.uppercase().orEmpty()) {
                "", "EG", "SD", "LY", "SY", "LB", "IQ", "JO", "PS", "MY", "SG", "ID" -> EGYPTIAN
                "SA", "YE", "BH", "KW", "QA", "AE", "OM" -> UMM_AL_QURA
                "PK", "IN", "BD", "AF" -> KARACHI
                "US", "CA" -> ISNA
                else -> if (c.length == 2) MWL else EGYPTIAN
            }
    }
}

/** Shadow length for Asr: one object length (Shafi'i, Maliki, Hanbali) or two (Hanafi). */
enum class AsrMethod(
    val key: String,
    val shadowFactor: Int,
) {
    STANDARD("standard", 1),
    HANAFI("hanafi", 2),
    ;

    companion object {
        fun fromKey(key: String?): AsrMethod = entries.firstOrNull { it.key == key } ?: STANDARD
    }
}

/** How Fajr and Isha are placed when the sun never gets low enough (summer at high latitudes). */
enum class HighLatitudeRule(
    val key: String,
) {
    NONE("none"),
    MIDDLE_OF_NIGHT("middle_of_night"),
    ONE_SEVENTH("one_seventh"),
    ANGLE_BASED("angle_based"),
    ;

    companion object {
        fun fromKey(key: String?): HighLatitudeRule = entries.firstOrNull { it.key == key } ?: ANGLE_BASED
    }
}

data class PrayerParams(
    val method: CalcMethod = CalcMethod.EGYPTIAN,
    val asr: AsrMethod = AsrMethod.STANDARD,
    val highLatitude: HighLatitudeRule = HighLatitudeRule.ANGLE_BASED,
    /** Manual adjustment per prayer in minutes, e.g. +2 for Maghrib as many mosques do. */
    val offsets: Map<Prayer, Int> = emptyMap(),
)

/** One day's times as epoch milliseconds; a missing entry is a time that does not exist that day. */
data class PrayerDay(
    val date: LocalDate,
    val times: Map<Prayer, Long>,
) {
    operator fun get(prayer: Prayer): Long? = times[prayer]
}

/**
 * On-device prayer times, the well-known praytimes.org algorithm (sun position from the Astronomical
 * Almanac's low-precision formulas). It agrees with published tables to within a minute or two; no
 * network, no library.
 */
object PrayerTimes {
    private const val SUN_ALTITUDE_AT_RISE = 0.833

    /**
     * Local clock times as fractional hours (0..24) for [year]-[month]-[day] at ([lat], [lng]) with
     * the zone offset [tzHours]. Offsets are already applied. NaN marks a time that cannot be
     * computed with [HighLatitudeRule.NONE].
     */
    fun hours(
        year: Int,
        month: Int,
        day: Int,
        lat: Double,
        lng: Double,
        tzHours: Double,
        params: PrayerParams,
        ramadan: Boolean = false,
    ): Map<Prayer, Double> {
        val jDate = julian(year, month, day) - lng / (15.0 * 24.0)
        val method = params.method

        fun sunPosition(jd: Double): Pair<Double, Double> {
            val d = jd - 2451545.0
            val g = fixAngle(357.529 + 0.98560028 * d)
            val q = fixAngle(280.459 + 0.98564736 * d)
            val l = fixAngle(q + 1.915 * sinD(g) + 0.020 * sinD(2 * g))
            val e = 23.439 - 0.00000036 * d
            val ra = arctan2D(cosD(e) * sinD(l), cosD(l)) / 15.0
            val eqt = q / 15.0 - fixHour(ra)
            val decl = arcsinD(sinD(e) * sinD(l))
            return decl to eqt
        }

        fun midDay(t: Double): Double = fixHour(12 - sunPosition(jDate + t).second)

        fun sunAngleTime(
            angle: Double,
            t: Double,
            beforeNoon: Boolean,
        ): Double {
            val decl = sunPosition(jDate + t).first
            val noon = midDay(t)
            val v = (-sinD(angle) - sinD(decl) * sinD(lat)) / (cosD(decl) * cosD(lat))
            if (v < -1.0 || v > 1.0) return Double.NaN
            val h = arccosD(v) / 15.0
            return noon + if (beforeNoon) -h else h
        }

        fun asrTime(t: Double): Double {
            val decl = sunPosition(jDate + t).first
            val angle = -arccotD(params.asr.shadowFactor + tanD(abs(lat - decl)))
            return sunAngleTime(angle, t, beforeNoon = false)
        }

        // One pass from rough guesses is what the reference tables use.
        val fajr = sunAngleTime(method.fajrAngle, 5 / 24.0, true)
        val sunrise = sunAngleTime(SUN_ALTITUDE_AT_RISE, 6 / 24.0, true)
        val dhuhr = midDay(12 / 24.0)
        val asr = asrTime(13 / 24.0)
        val sunset = sunAngleTime(SUN_ALTITUDE_AT_RISE, 18 / 24.0, false)
        val ishaAngle = method.ishaAngle?.let { sunAngleTime(it, 18 / 24.0, false) }

        val shift = tzHours - lng / 15.0
        val out =
            linkedMapOf(
                Prayer.FAJR to fajr + shift,
                Prayer.SUNRISE to sunrise + shift,
                Prayer.DHUHR to dhuhr + shift,
                Prayer.ASR to asr + shift,
                Prayer.MAGHRIB to sunset + shift,
                Prayer.ISHA to (ishaAngle ?: Double.NaN) + shift,
            )
        method.ishaMinutes?.let { minutes ->
            val m = if (ramadan && method == CalcMethod.UMM_AL_QURA) 120 else minutes
            out[Prayer.ISHA] = out.getValue(Prayer.MAGHRIB) + m / 60.0
        }

        if (params.highLatitude != HighLatitudeRule.NONE) {
            val night = fixHour(out.getValue(Prayer.SUNRISE) - out.getValue(Prayer.MAGHRIB))
            if (!night.isNaN()) {
                out[Prayer.FAJR] = adjustHighLat(out.getValue(Prayer.FAJR), out.getValue(Prayer.SUNRISE), method.fajrAngle, night, params.highLatitude, beforeBase = true)
                if (method.ishaAngle != null) {
                    out[Prayer.ISHA] = adjustHighLat(out.getValue(Prayer.ISHA), out.getValue(Prayer.MAGHRIB), method.ishaAngle, night, params.highLatitude, beforeBase = false)
                }
            }
        }

        params.offsets.forEach { (prayer, minutes) ->
            out[prayer] = out.getValue(prayer) + minutes / 60.0
        }
        return out
    }

    /** Times for [date] in [zone], as epoch millis rounded to the minute. */
    fun forDate(
        date: LocalDate,
        zone: ZoneId,
        lat: Double,
        lng: Double,
        params: PrayerParams,
        hijriAdjust: Int = 0,
    ): PrayerDay {
        // The offset at local noon: right on DST-change days for every prayer but a rare Fajr.
        val tzHours =
            zone.rules
                .getOffset(date.atTime(12, 0).atZone(zone).toInstant())
                .totalSeconds / 3600.0
        val ramadan = HijriCalendar.fromGregorian(date, hijriAdjust).month == 9
        val local = hours(date.year, date.monthValue, date.dayOfMonth, lat, lng, tzHours, params, ramadan)
        val utcMidnight = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val times =
            local
                .filterValues { !it.isNaN() }
                .mapValues { (_, h) ->
                    // h - tzHours is universal time; round to the whole minute people read.
                    utcMidnight + ((h - tzHours) * 60.0).roundToLong() * 60_000L
                }
        return PrayerDay(date, times)
    }

    /** "HH:mm" in 24-hour form for fractional [hours], rounded to the nearest minute. */
    fun format(hours: Double): String {
        if (hours.isNaN()) return "--:--"
        val total = Math.floorMod((hours * 60.0).roundToLong(), 1440L).toInt()
        return "%02d:%02d".format(total / 60, total % 60)
    }

    private fun adjustHighLat(
        time: Double,
        base: Double,
        angle: Double,
        night: Double,
        rule: HighLatitudeRule,
        beforeBase: Boolean,
    ): Double {
        val portion =
            night *
                when (rule) {
                    HighLatitudeRule.ANGLE_BASED -> angle / 60.0
                    HighLatitudeRule.ONE_SEVENTH -> 1.0 / 7.0
                    HighLatitudeRule.MIDDLE_OF_NIGHT -> 0.5
                    HighLatitudeRule.NONE -> return time
                }
        val diff = if (beforeBase) fixHour(base - time) else fixHour(time - base)
        return if (time.isNaN() || diff > portion) base + if (beforeBase) -portion else portion else time
    }

    internal fun julian(
        year: Int,
        month: Int,
        day: Int,
    ): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private fun fix(
        a: Double,
        b: Double,
    ): Double {
        val r = a - b * floor(a / b)
        return if (r < 0) r + b else r
    }

    private fun fixAngle(a: Double) = fix(a, 360.0)

    private fun fixHour(a: Double) = fix(a, 24.0)

    private fun sinD(d: Double) = sin(Math.toRadians(d))

    private fun cosD(d: Double) = cos(Math.toRadians(d))

    private fun tanD(d: Double) = tan(Math.toRadians(d))

    private fun arcsinD(x: Double) = Math.toDegrees(asin(x))

    private fun arccosD(x: Double) = Math.toDegrees(acos(x))

    private fun arccotD(x: Double) = Math.toDegrees(atan(1.0 / x))

    private fun arctan2D(
        y: Double,
        x: Double,
    ) = Math.toDegrees(atan2(y, x))
}
