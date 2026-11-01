/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.islamic

/** Where prayer times are calculated for. */
enum class LocationSource(
    val key: String,
) {
    /** The phone's last known coarse location, read on the device; it never leaves it. */
    DEVICE("device"),
    CITY("city"),
    MANUAL("manual"),
    ;

    companion object {
        fun fromKey(key: String?): LocationSource = entries.firstOrNull { it.key == key } ?: CITY
    }
}

data class IslamicPlace(
    val lat: Double,
    val lng: Double,
    /** A bundled city when the place is one (or a device fix's nearest one, for a label). */
    val city: IslamicCity?,
)

/**
 * The optional Islamic section (#147). Off by default; nothing is computed or scheduled until the
 * user turns it on. Everything is on-device.
 */
data class IslamicSettings(
    val enabled: Boolean = false,
    /** Null: pick the convention for the user's country. */
    val methodKey: String? = null,
    val asr: AsrMethod = AsrMethod.STANDARD,
    val highLatitude: HighLatitudeRule = HighLatitudeRule.ANGLE_BASED,
    val offsets: Map<Prayer, Int> = emptyMap(),
    val locationSource: LocationSource = LocationSource.CITY,
    val cityId: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val adhan: AdhanMode = AdhanMode.OFF,
    val showHijri: Boolean = true,
    val hijriAdjust: Int = 0,
) {
    fun method(countryCode: String?): CalcMethod = CalcMethod.fromKey(methodKey) ?: CalcMethod.defaultFor(countryCode)

    fun params(countryCode: String?): PrayerParams =
        PrayerParams(method = method(countryCode), asr = asr, highLatitude = highLatitude, offsets = offsets.filterValues { it != 0 })

    /**
     * The point to calculate for, or null until the user has chosen one. A device or manual point
     * needs valid coordinates; a city falls back to nothing rather than a guess.
     */
    fun place(): IslamicPlace? =
        when (locationSource) {
            LocationSource.CITY -> {
                IslamicCities.byId(cityId)?.let { IslamicPlace(it.lat, it.lng, it) }
            }

            LocationSource.DEVICE, LocationSource.MANUAL -> {
                val la = lat
                val ln = lng
                if (la != null && ln != null && validLat(la) && validLng(ln)) {
                    IslamicPlace(la, ln, if (locationSource == LocationSource.DEVICE) IslamicCities.nearest(la, ln) else null)
                } else {
                    null
                }
            }
        }

    companion object {
        const val MAX_OFFSET = 30

        fun validLat(v: Double) = !v.isNaN() && v in -90.0..90.0

        fun validLng(v: Double) = !v.isNaN() && v in -180.0..180.0

        /** Parses a typed coordinate, accepting an Arabic comma or Eastern Arabic digits. */
        fun parseCoordinate(text: String): Double? {
            val western =
                text
                    .trim()
                    .map { c -> if (c in '\u0660'..'\u0669') '0' + (c - '\u0660') else if (c == '\u066B' || c == ',') '.' else c }
                    .joinToString("")
                    .replace('\u2212', '-')
            return western.toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
        }

        /** Stored offsets: "fajr:2,maghrib:3", unknown names and out-of-range values dropped. */
        fun encodeOffsets(offsets: Map<Prayer, Int>): String =
            offsets.filterValues { it != 0 }.entries.joinToString(",") { "${it.key.key}:${it.value}" }

        fun decodeOffsets(raw: String?): Map<Prayer, Int> =
            raw
                .orEmpty()
                .split(',')
                .mapNotNull { part ->
                    val (k, v) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                    val prayer = Prayer.entries.firstOrNull { it.key == k.trim() } ?: return@mapNotNull null
                    val minutes = v.trim().toIntOrNull()?.takeIf { it in -MAX_OFFSET..MAX_OFFSET && it != 0 } ?: return@mapNotNull null
                    prayer to minutes
                }.toMap()
    }
}
