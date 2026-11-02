/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.theme

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The five shared sound packs; the same clips as the main app. Stored by [storageId]. */
enum class MawjaSoundPack(
    val storageId: String,
) {
    WAVE("wave"),
    DROP("drop"),
    CRYSTAL("crystal"),
    SOFT("soft"),
    RETRO("retro"),
    ;

    companion object {
        fun fromStorage(id: String?): MawjaSoundPack = entries.firstOrNull { it.storageId == id } ?: WAVE
    }
}

enum class MawjaSound { TAP, TOGGLE, SUCCESS }

/**
 * The look-and-feel choices of the companion apps (Downloader, admin): palette, sound pack, sound
 * on/off, volume and a light haptic tick. One instance per process ([get]).
 */
class MawjaThemeStore private constructor(
    private val context: Context,
) {
    private val prefs = context.getSharedPreferences("mawja_theme", Context.MODE_PRIVATE)

    private val _palette = MutableStateFlow(MawjaPalette.fromStorage(prefs.getString(KEY_PALETTE, null)))
    val palette: StateFlow<MawjaPalette> = _palette.asStateFlow()

    private val _paletteChosen = MutableStateFlow(prefs.contains(KEY_PALETTE))

    /** Whether the user ever picked a palette (an app with its own default look keeps it until then). */
    val paletteChosen: StateFlow<Boolean> = _paletteChosen.asStateFlow()

    private val _pack = MutableStateFlow(MawjaSoundPack.fromStorage(prefs.getString(KEY_PACK, null)))
    val pack: StateFlow<MawjaSoundPack> = _pack.asStateFlow()

    private val _sounds = MutableStateFlow(prefs.getBoolean(KEY_SOUNDS, false))
    val sounds: StateFlow<Boolean> = _sounds.asStateFlow()

    private val _volume = MutableStateFlow(prefs.getFloat(KEY_VOLUME, DEFAULT_VOLUME))

    /** 0..1, applied on top of the system touch-sound volume. */
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _haptics = MutableStateFlow(prefs.getBoolean(KEY_HAPTICS, true))
    val haptics: StateFlow<Boolean> = _haptics.asStateFlow()

    fun setPalette(palette: MawjaPalette) {
        prefs.edit { putString(KEY_PALETTE, palette.storageId) }
        _palette.value = palette
        _paletteChosen.value = true
    }

    fun setPack(pack: MawjaSoundPack) {
        prefs.edit { putString(KEY_PACK, pack.storageId) }
        _pack.value = pack
        releasePool()
    }

    fun setSounds(on: Boolean) {
        prefs.edit { putBoolean(KEY_SOUNDS, on) }
        _sounds.value = on
        if (!on) releasePool()
    }

    fun setVolume(value: Float) {
        val v = value.coerceIn(0f, 1f)
        prefs.edit { putFloat(KEY_VOLUME, v) }
        _volume.value = v
    }

    fun setHaptics(on: Boolean) {
        prefs.edit { putBoolean(KEY_HAPTICS, on) }
        _haptics.value = on
    }

    private var pool: SoundPool? = null
    private val ids = mutableMapOf<MawjaSound, Int>()
    private val ready = mutableSetOf<Int>()
    private var lastAt = 0L

    /** Plays [sound] with a synced haptic tick, respecting silent mode and the system touch-sound switch. */
    fun play(sound: MawjaSound) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAt < MIN_GAP_MS) return
        lastAt = now
        if (_haptics.value) tick(sound)
        if (!_sounds.value || !soundAllowed()) return
        val p = ensurePool()
        val id = ids[sound] ?: return
        if (synchronized(ready) { id !in ready }) return
        val v = BASE_VOLUME * _volume.value
        p.play(id, v, v, 1, 0, 1f)
    }

    private fun soundAllowed(): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        val touch =
            runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 1 }
                .getOrDefault(true)
        return touch && audio.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }

    private fun tick(sound: MawjaSound) {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val effect = if (sound == MawjaSound.SUCCESS) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_TICK
                vibrator.vibrate(VibrationEffect.createPredefined(effect))
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(if (sound == MawjaSound.SUCCESS) 18L else 8L, 60))
            }
        }
    }

    @Synchronized
    private fun ensurePool(): SoundPool {
        pool?.let { return it }
        val created =
            SoundPool
                .Builder()
                .setMaxStreams(2)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                ).build()
        created.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(ready) { ready += id } }
        MawjaSound.entries.forEach { ids[it] = created.load(context, clip(_pack.value, it), 1) }
        pool = created
        return created
    }

    @Synchronized
    private fun releasePool() {
        pool?.release()
        pool = null
        ids.clear()
        synchronized(ready) { ready.clear() }
    }

    companion object {
        private const val KEY_PALETTE = "palette"
        private const val KEY_PACK = "pack"
        private const val KEY_SOUNDS = "sounds"
        private const val KEY_VOLUME = "volume"
        private const val KEY_HAPTICS = "haptics"
        private const val DEFAULT_VOLUME = 0.6f
        private const val BASE_VOLUME = 0.3f
        private const val MIN_GAP_MS = 60L

        @Volatile
        private var instance: MawjaThemeStore? = null

        fun get(context: Context): MawjaThemeStore =
            instance ?: synchronized(this) {
                instance ?: MawjaThemeStore(context.applicationContext).also { instance = it }
            }

        fun clip(
            pack: MawjaSoundPack,
            sound: MawjaSound,
        ): Int {
            val name = "ui_${pack.storageId}_${sound.name.lowercase()}"
            return when (name) {
                "ui_wave_tap" -> R.raw.ui_wave_tap
                "ui_wave_toggle" -> R.raw.ui_wave_toggle
                "ui_wave_success" -> R.raw.ui_wave_success
                "ui_drop_tap" -> R.raw.ui_drop_tap
                "ui_drop_toggle" -> R.raw.ui_drop_toggle
                "ui_drop_success" -> R.raw.ui_drop_success
                "ui_crystal_tap" -> R.raw.ui_crystal_tap
                "ui_crystal_toggle" -> R.raw.ui_crystal_toggle
                "ui_crystal_success" -> R.raw.ui_crystal_success
                "ui_soft_tap" -> R.raw.ui_soft_tap
                "ui_soft_toggle" -> R.raw.ui_soft_toggle
                "ui_soft_success" -> R.raw.ui_soft_success
                "ui_retro_tap" -> R.raw.ui_retro_tap
                "ui_retro_toggle" -> R.raw.ui_retro_toggle
                else -> R.raw.ui_retro_success
            }
        }
    }
}
