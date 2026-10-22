/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.mawja.android.data.local.VideoQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Mawja battery saver (PLAN_V2 #62), the runtime side. The battery level, charging state and the
 * system power-save mode arrive by broadcast (ACTION_BATTERY_CHANGED is sticky, so the first value is
 * immediate); there is no polling loop. [active] is what every call site reads.
 *
 * Settings live in plain SharedPreferences because quality and prefetch decisions read them
 * synchronously. The remote switch `features.batterySaver` (on by default) is pushed in by
 * [setRemoteEnabled] from the activity that owns RemoteConfigRepository, and its last value is kept
 * so a worker in a cold process obeys it too. Free for every tier (PLAN_V2 marks #62 F).
 */
object BatterySaver {
    private const val TAG = "BatterySaver"
    const val REMOTE_SWITCH = "batterySaver"

    private const val PREFS = "mawja_battery_saver"
    private const val KEY_MODE = "mode"
    private const val KEY_THRESHOLD = "threshold"
    private const val KEY_BLACK_THEME = "black_theme"
    private const val KEY_REDUCE_MOTION = "reduce_motion"
    private const val KEY_REMOTE = "remote_enabled"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var started = false

    private val _settings = MutableStateFlow(BatterySaverSettings())
    val settings: StateFlow<BatterySaverSettings> = _settings.asStateFlow()

    private val _reading = MutableStateFlow(BatteryReading.UNKNOWN)
    val reading: StateFlow<BatteryReading> = _reading.asStateFlow()

    private val _remoteEnabled = MutableStateFlow(true)
    val remoteEnabled: StateFlow<Boolean> = _remoteEnabled.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _reason = MutableStateFlow(BatterySaverReason.BATTERY_OK)
    val reason: StateFlow<BatterySaverReason> = _reason.asStateFlow()

    /** Called once from Application.onCreate. Safe to call again. */
    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = context.applicationContext
        _settings.value = readSettings(app)
        _remoteEnabled.value = prefs(app).getBoolean(KEY_REMOTE, true)
        _reading.value = readNow(app)
        recompute()
        scope.launch {
            batteryReadings(app).distinctUntilChanged().collect {
                _reading.value = it
            }
        }
        scope.launch {
            combine(_settings, _reading, _remoteEnabled) { _, _, _ -> }.collect { recompute() }
        }
    }

    @Synchronized
    private fun recompute() {
        val s = _settings.value
        val r = _reading.value
        val remote = _remoteEnabled.value
        val was = _active.value
        val why = BatterySaverPolicy.reason(s, r, remote, was)
        val now = BatterySaverPolicy.isActive(s, r, remote, was)
        _reason.value = why
        if (now != was) Log.i(TAG, "battery saver ${if (now) "on" else "off"} ($why, level=${r.levelPercent})")
        _active.value = now
    }

    /** Whether the saver applies right now. Before [start] it reads the battery synchronously. */
    fun isActive(): Boolean = _active.value

    fun isActive(context: Context): Boolean {
        if (started) return _active.value
        val app = context.applicationContext
        return BatterySaverPolicy.isActive(readSettings(app), readNow(app), prefs(app).getBoolean(KEY_REMOTE, true))
    }

    fun reduceMotion(): Boolean = BatterySaverPolicy.reduceMotion(_settings.value, _active.value)

    fun capQuality(quality: VideoQuality): VideoQuality = BatterySaverPolicy.capQuality(quality, _active.value)

    fun capHeight(height: Int): Int = BatterySaverPolicy.capHeight(height, _active.value)

    fun allowPrefetch(): Boolean = BatterySaverPolicy.allowPrefetch(_active.value)

    fun allowNonEssentialJob(context: Context): Boolean = BatterySaverPolicy.allowNonEssentialJob(isActive(context))

    fun setMode(
        context: Context,
        mode: BatterySaverMode,
    ) = update(context) { copy(mode = mode) }

    fun setThreshold(
        context: Context,
        percent: Int,
    ) = update(context) { copy(thresholdPercent = BatterySaverPolicy.clampThreshold(percent)) }

    fun setBlackTheme(
        context: Context,
        on: Boolean,
    ) = update(context) { copy(blackTheme = on) }

    fun setReduceMotion(
        context: Context,
        on: Boolean,
    ) = update(context) { copy(reduceMotion = on) }

    /** config/app features.batterySaver; false turns the saver off whatever the user chose. */
    fun setRemoteEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        if (_remoteEnabled.value == enabled && prefs(context).contains(KEY_REMOTE)) return
        prefs(context).edit { putBoolean(KEY_REMOTE, enabled) }
        _remoteEnabled.value = enabled
    }

    private fun update(
        context: Context,
        change: BatterySaverSettings.() -> BatterySaverSettings,
    ) {
        val next = _settings.value.change()
        prefs(context).edit {
            putString(KEY_MODE, next.mode.name)
            putInt(KEY_THRESHOLD, next.thresholdPercent)
            putBoolean(KEY_BLACK_THEME, next.blackTheme)
            putBoolean(KEY_REDUCE_MOTION, next.reduceMotion)
        }
        _settings.value = next
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readSettings(context: Context): BatterySaverSettings {
        val p = prefs(context)
        return BatterySaverSettings(
            mode = BatterySaverMode.fromKey(p.getString(KEY_MODE, null)),
            thresholdPercent = BatterySaverPolicy.clampThreshold(p.getInt(KEY_THRESHOLD, BatterySaverPolicy.DEFAULT_THRESHOLD)),
            blackTheme = p.getBoolean(KEY_BLACK_THEME, true),
            reduceMotion = p.getBoolean(KEY_REDUCE_MOTION, true),
        )
    }

    /** The sticky battery intent read without a receiver, plus the power-save mode. */
    private fun readNow(context: Context): BatteryReading {
        val sticky =
            runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        return reading(context, sticky)
    }

    private fun reading(
        context: Context,
        battery: Intent?,
    ): BatteryReading {
        val powerSave = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
        if (battery == null) return BatteryReading.UNKNOWN.copy(powerSave = powerSave)
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryReading(levelPercent = percent, charging = charging, powerSave = powerSave)
    }

    /** Battery and power-save broadcasts as a flow; the sticky battery intent gives the first value at once. */
    private fun batteryReadings(context: Context) =
        callbackFlow {
            var lastBattery: Intent? = null
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        c: Context,
                        intent: Intent,
                    ) {
                        if (intent.action == Intent.ACTION_BATTERY_CHANGED) lastBattery = intent
                        trySend(reading(context, lastBattery))
                    }
                }
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                    addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                }
            val sticky =
                runCatching {
                    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                }.onFailure { Log.w(TAG, "Could not watch the battery", it) }
                    .getOrNull()
            if (sticky != null) {
                lastBattery = sticky
                trySend(reading(context, sticky))
            }
            awaitClose { runCatching { context.unregisterReceiver(receiver) } }
        }
}
