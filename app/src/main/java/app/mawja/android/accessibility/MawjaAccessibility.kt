/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.accessibility

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.edit
import app.mawja.android.battery.BatterySaver
import app.mawja.android.battery.BatterySaverPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Mawja accessibility options (#161 large text and high contrast, #162 colour-blind mode, #163 reduce
 * motion), the runtime side. Settings live in plain SharedPreferences so non-Compose code (the
 * animated-artwork loader, the marquee modifier) reads them synchronously; the system's animator
 * duration scale is watched with a ContentObserver, so turning animations off in Android's own
 * accessibility settings takes effect without a restart. Free for every tier.
 */
object MawjaAccessibility {
    private const val PREFS = "mawja_accessibility"
    private const val KEY_HIGH_CONTRAST = "high_contrast"
    private const val KEY_REDUCE_MOTION = "reduce_motion"
    private const val KEY_TEXT_SIZE = "text_size"
    private const val KEY_COLOR_VISION = "color_vision"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var started = false

    private val _settings = MutableStateFlow(AccessibilitySettings())
    val settings: StateFlow<AccessibilitySettings> = _settings.asStateFlow()

    private val _animatorScale = MutableStateFlow(1f)

    /** The system's animator duration scale; 0 means the user turned animations off. */
    val animatorScale: StateFlow<Float> = _animatorScale.asStateFlow()

    /** Whether non-essential motion is off right now: in-app switch, system setting, or the battery saver's extra. */
    val reduceMotion: StateFlow<Boolean> =
        combine(_settings, _animatorScale, BatterySaver.settings, BatterySaver.active) { s, scale, saver, active ->
            AccessibilityPolicy.reduceMotion(s, scale, BatterySaverPolicy.reduceMotion(saver, active))
        }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Called once from Application.onCreate. Safe to call again. */
    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = context.applicationContext
        _settings.value = readSettings(app)
        _animatorScale.value = readAnimatorScale(app)
        runCatching {
            app.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        _animatorScale.value = readAnimatorScale(app)
                    }
                },
            )
        }
    }

    /** Synchronous read for code outside composition. */
    fun reduceMotionNow(): Boolean =
        AccessibilityPolicy.reduceMotion(_settings.value, _animatorScale.value, BatterySaver.reduceMotion())

    fun colorVisionNow(): ColorVisionMode = _settings.value.colorVision

    fun setHighContrast(
        context: Context,
        on: Boolean,
    ) = update(context) { copy(highContrast = on) }

    fun setReduceMotion(
        context: Context,
        on: Boolean,
    ) = update(context) { copy(reduceMotion = on) }

    fun setTextSize(
        context: Context,
        mode: TextSizeMode,
    ) = update(context) { copy(textSize = mode) }

    fun setColorVision(
        context: Context,
        mode: ColorVisionMode,
    ) = update(context) { copy(colorVision = mode) }

    private fun update(
        context: Context,
        change: AccessibilitySettings.() -> AccessibilitySettings,
    ) {
        val next = _settings.value.change()
        prefs(context).edit {
            putBoolean(KEY_HIGH_CONTRAST, next.highContrast)
            putBoolean(KEY_REDUCE_MOTION, next.reduceMotion)
            putString(KEY_TEXT_SIZE, next.textSize.key)
            putString(KEY_COLOR_VISION, next.colorVision.key)
        }
        _settings.value = next
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readSettings(context: Context): AccessibilitySettings {
        val p = prefs(context)
        return AccessibilitySettings(
            highContrast = p.getBoolean(KEY_HIGH_CONTRAST, false),
            reduceMotion = p.getBoolean(KEY_REDUCE_MOTION, false),
            textSize = TextSizeMode.fromKey(p.getString(KEY_TEXT_SIZE, null)),
            colorVision = ColorVisionMode.fromKey(p.getString(KEY_COLOR_VISION, null)),
        )
    }

    private fun readAnimatorScale(context: Context): Float =
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
}
