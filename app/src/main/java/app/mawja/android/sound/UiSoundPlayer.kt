/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.sound

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.core.content.edit
import app.mawja.android.R
import app.mawja.android.account.EntitlementsRepository
import app.mawja.android.account.MawjaFeature
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays Mawja's UI sounds through a small [SoundPool] (sonification stream, so it follows the
 * system's touch-sound volume). The pool is created on the first play after the user turns sounds
 * on, so users who never enable them pay nothing. Every play re-checks [UiSoundPolicy].
 */
@Singleton
class UiSoundPlayer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val entitlementsRepository: EntitlementsRepository,
    ) {
        private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

        private val _enabled by lazy { MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false)) }

        /** The user's switch; off by default. */
        val enabled: StateFlow<Boolean> get() = _enabled.asStateFlow()

        private val _pack by lazy { MutableStateFlow(UiSoundPack.fromStorage(prefs.getString(KEY_PACK, null))) }

        /** The chosen sound pack; Wave (Mawja's original clips) by default. */
        val pack: StateFlow<UiSoundPack> get() = _pack.asStateFlow()

        fun setPack(pack: UiSoundPack) {
            if (pack == _pack.value) return
            prefs.edit { putString(KEY_PACK, pack.storageId) }
            _pack.value = pack
            // Reload the pool with the new clips.
            release()
            if (_enabled.value) ensurePool()
        }

        private var pool: SoundPool? = null
        private val loaded = mutableMapOf<UiSound, Int>()
        private val ready = mutableSetOf<Int>()

        @Volatile
        private var lastPlayedAt = 0L

        fun setEnabled(on: Boolean) {
            prefs.edit { putBoolean(KEY_ENABLED, on) }
            _enabled.value = on
            // Load the clips now so the first sound after turning them on is not lost to decoding.
            if (on) ensurePool() else release()
        }

        fun play(sound: UiSound) {
            if (!_enabled.value) return
            val now = SystemClock.elapsedRealtime()
            if (!UiSoundPolicy.outsideCooldown(lastPlayedAt, now)) return
            if (!UiSoundPolicy.shouldPlay(conditions())) return
            val soundPool = ensurePool()
            val id = loaded[sound] ?: return
            if (synchronized(ready) { id !in ready }) return
            lastPlayedAt = now
            soundPool.play(id, UiSoundPolicy.VOLUME, UiSoundPolicy.VOLUME, 1, 0, 1f)
        }

        private fun conditions(): UiSoundConditions {
            val audio = context.getSystemService(AudioManager::class.java)
            val notifications = context.getSystemService(NotificationManager::class.java)
            val touchSounds =
                runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 1 }
                    .getOrDefault(true)
            val filter = notifications?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
            return UiSoundConditions(
                enabled = _enabled.value,
                entitled = entitlementsRepository.entitlements.value.has(MawjaFeature.UI_SOUNDS),
                systemTouchSounds = touchSounds,
                ringerNormal = audio?.ringerMode == AudioManager.RINGER_MODE_NORMAL,
                doNotDisturb =
                    filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                        filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
            )
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
            created.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) synchronized(ready) { ready += sampleId } }
            UiSound.entries.forEach { sound -> loaded[sound] = created.load(context, clipFor(_pack.value, sound), 1) }
            pool = created
            return created
        }

        @Synchronized
        private fun release() {
            pool?.release()
            pool = null
            loaded.clear()
            synchronized(ready) { ready.clear() }
        }

        private companion object {
            const val PREFS = "mawja_ui_sounds"
            const val KEY_ENABLED = "enabled"
            const val KEY_PACK = "pack"

            fun clipFor(
                pack: UiSoundPack,
                sound: UiSound,
            ): Int =
                when (pack) {
                    UiSoundPack.WAVE ->
                        when (sound) {
                            UiSound.TAP -> R.raw.ui_tap
                            UiSound.TOGGLE -> R.raw.ui_toggle
                            UiSound.SUCCESS -> R.raw.ui_success
                        }
                    UiSoundPack.DROP ->
                        when (sound) {
                            UiSound.TAP -> R.raw.ui_drop_tap
                            UiSound.TOGGLE -> R.raw.ui_drop_toggle
                            UiSound.SUCCESS -> R.raw.ui_drop_success
                        }
                    UiSoundPack.CRYSTAL ->
                        when (sound) {
                            UiSound.TAP -> R.raw.ui_crystal_tap
                            UiSound.TOGGLE -> R.raw.ui_crystal_toggle
                            UiSound.SUCCESS -> R.raw.ui_crystal_success
                        }
                    UiSoundPack.SOFT ->
                        when (sound) {
                            UiSound.TAP -> R.raw.ui_soft_tap
                            UiSound.TOGGLE -> R.raw.ui_soft_toggle
                            UiSound.SUCCESS -> R.raw.ui_soft_success
                        }
                    UiSoundPack.RETRO ->
                        when (sound) {
                            UiSound.TAP -> R.raw.ui_retro_tap
                            UiSound.TOGGLE -> R.raw.ui_retro_toggle
                            UiSound.SUCCESS -> R.raw.ui_retro_success
                        }
                }
        }
    }

/** For composables, which Hilt cannot inject. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UiSoundEntryPoint {
    fun uiSoundPlayer(): UiSoundPlayer
}

/** The app's [UiSoundPlayer], or null in previews and non-Hilt hosts (tests), where sounds never play. */
@Composable
fun rememberUiSounds(): UiSoundPlayer? {
    val inspection = LocalInspectionMode.current
    val app = LocalContext.current.applicationContext
    return remember(app, inspection) {
        if (inspection) {
            null
        } else {
            runCatching { EntryPointAccessors.fromApplication(app, UiSoundEntryPoint::class.java).uiSoundPlayer() }.getOrNull()
        }
    }
}
