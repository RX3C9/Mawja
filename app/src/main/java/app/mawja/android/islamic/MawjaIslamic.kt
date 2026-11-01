/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.islamic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.mawja.android.widget.prayer.NextPrayerRefresh
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Runtime side of the optional Islamic section (#147): settings in plain SharedPreferences (read
 * synchronously by the alarm receiver), today's times, and the device's last known location.
 * Nothing runs while the section is off. No network, no Play services.
 */
object MawjaIslamic {
    private const val PREFS = "mawja_islamic"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_METHOD = "method"
    private const val KEY_ASR = "asr"
    private const val KEY_HIGH_LAT = "high_latitude"
    private const val KEY_OFFSETS = "offsets"
    private const val KEY_SOURCE = "location_source"
    private const val KEY_CITY = "city"
    private const val KEY_LAT = "lat"
    private const val KEY_LNG = "lng"
    private const val KEY_ADHAN = "adhan"
    private const val KEY_HIJRI = "show_hijri"
    private const val KEY_HIJRI_ADJUST = "hijri_adjust"

    @Volatile
    private var started = false

    private val _settings = MutableStateFlow(IslamicSettings())
    val settings: StateFlow<IslamicSettings> = _settings.asStateFlow()

    /** Called from Application.onCreate and from the alarm receiver. Cheap when the section is off. */
    fun start(context: Context) {
        if (!started) {
            synchronized(this) {
                if (!started) {
                    _settings.value = read(context.applicationContext)
                    started = true
                }
            }
        }
        if (_settings.value.enabled) PrayerAlarms.reschedule(context.applicationContext)
    }

    /** The user's country for the default calculation convention: SIM/network region, else the locale's. */
    fun countryCode(context: Context): String? {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        val fromNetwork = runCatching { tm?.networkCountryIso?.takeIf { it.length == 2 } ?: tm?.simCountryIso?.takeIf { it.length == 2 } }.getOrNull()
        return (fromNetwork ?: Locale.getDefault().country.takeIf { it.length == 2 })?.uppercase()
    }

    fun day(
        context: Context,
        date: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        s: IslamicSettings = _settings.value,
    ): PrayerDay? {
        val place = s.place() ?: return null
        return PrayerTimes.forDate(date, zone, place.lat, place.lng, s.params(countryCode(context)), s.hijriAdjust)
    }

    fun setEnabled(
        context: Context,
        on: Boolean,
    ) = update(context) { copy(enabled = on) }

    fun setMethod(
        context: Context,
        method: CalcMethod?,
    ) = update(context) { copy(methodKey = method?.key) }

    fun setAsr(
        context: Context,
        asr: AsrMethod,
    ) = update(context) { copy(asr = asr) }

    fun setHighLatitude(
        context: Context,
        rule: HighLatitudeRule,
    ) = update(context) { copy(highLatitude = rule) }

    fun setOffset(
        context: Context,
        prayer: Prayer,
        minutes: Int,
    ) = update(context) {
        copy(offsets = (offsets + (prayer to minutes.coerceIn(-IslamicSettings.MAX_OFFSET, IslamicSettings.MAX_OFFSET))).filterValues { it != 0 })
    }

    fun setCity(
        context: Context,
        city: IslamicCity,
    ) = update(context) { copy(locationSource = LocationSource.CITY, cityId = city.id) }

    fun setManual(
        context: Context,
        lat: Double,
        lng: Double,
    ) = update(context) { copy(locationSource = LocationSource.MANUAL, lat = lat, lng = lng) }

    fun setAdhan(
        context: Context,
        mode: AdhanMode,
    ) = update(context) { copy(adhan = mode) }

    fun setShowHijri(
        context: Context,
        on: Boolean,
    ) = update(context) { copy(showHijri = on) }

    fun setHijriAdjust(
        context: Context,
        days: Int,
    ) = update(context) { copy(hijriAdjust = days.coerceIn(-HijriCalendar.MAX_ADJUST, HijriCalendar.MAX_ADJUST)) }

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Uses the newest last-known fix any provider holds (no live request, no GPS spin-up). Returns
     * false when there is no permission or no fix yet, so the UI can ask for a city instead.
     */
    @SuppressLint("MissingPermission")
    fun useDeviceLocation(context: Context): Boolean {
        if (!hasLocationPermission(context)) return false
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        val fix =
            runCatching {
                lm
                    .getProviders(true)
                    .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                    .maxByOrNull { it.time }
            }.getOrNull() ?: return false
        update(context) { copy(locationSource = LocationSource.DEVICE, lat = fix.latitude, lng = fix.longitude) }
        return true
    }

    private fun update(
        context: Context,
        change: IslamicSettings.() -> IslamicSettings,
    ) {
        val app = context.applicationContext
        val next = _settings.value.change()
        prefs(app).edit {
            putBoolean(KEY_ENABLED, next.enabled)
            putString(KEY_METHOD, next.methodKey)
            putString(KEY_ASR, next.asr.key)
            putString(KEY_HIGH_LAT, next.highLatitude.key)
            putString(KEY_OFFSETS, IslamicSettings.encodeOffsets(next.offsets))
            putString(KEY_SOURCE, next.locationSource.key)
            putString(KEY_CITY, next.cityId)
            if (next.lat != null && next.lng != null) {
                putString(KEY_LAT, next.lat.toString())
                putString(KEY_LNG, next.lng.toString())
            } else {
                remove(KEY_LAT)
                remove(KEY_LNG)
            }
            putString(KEY_ADHAN, next.adhan.key)
            putBoolean(KEY_HIJRI, next.showHijri)
            putInt(KEY_HIJRI_ADJUST, next.hijriAdjust)
        }
        _settings.value = next
        started = true
        if (next.enabled) PrayerAlarms.reschedule(app) else PrayerAlarms.cancel(app)
        NextPrayerRefresh.request(app)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context): IslamicSettings {
        val p = prefs(context)
        return IslamicSettings(
            enabled = p.getBoolean(KEY_ENABLED, false),
            methodKey = p.getString(KEY_METHOD, null)?.takeIf { CalcMethod.fromKey(it) != null },
            asr = AsrMethod.fromKey(p.getString(KEY_ASR, null)),
            highLatitude = HighLatitudeRule.fromKey(p.getString(KEY_HIGH_LAT, null)),
            offsets = IslamicSettings.decodeOffsets(p.getString(KEY_OFFSETS, null)),
            locationSource = LocationSource.fromKey(p.getString(KEY_SOURCE, null)),
            cityId = p.getString(KEY_CITY, null),
            lat = p.getString(KEY_LAT, null)?.toDoubleOrNull(),
            lng = p.getString(KEY_LNG, null)?.toDoubleOrNull(),
            adhan = AdhanMode.fromKey(p.getString(KEY_ADHAN, null)),
            showHijri = p.getBoolean(KEY_HIJRI, true),
            hijriAdjust = p.getInt(KEY_HIJRI_ADJUST, 0).coerceIn(-HijriCalendar.MAX_ADJUST, HijriCalendar.MAX_ADJUST),
        )
    }
}
