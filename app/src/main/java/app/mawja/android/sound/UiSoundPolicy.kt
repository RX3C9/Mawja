/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.sound

/** The three Mawja UI sounds. Original clips generated for Mawja, a few KB each, in res/raw. */
enum class UiSound {
    TAP,
    TOGGLE,
    SUCCESS,
}

/** The five Mawja sound packs; each has its own tap, toggle and success clip in res/raw. Stored by [storageId]. */
enum class UiSoundPack(
    val storageId: String,
) {
    WAVE("wave"),
    DROP("drop"),
    CRYSTAL("crystal"),
    SOFT("soft"),
    RETRO("retro"),
    ;

    companion object {
        fun fromStorage(id: String?): UiSoundPack = entries.firstOrNull { it.storageId == id } ?: WAVE
    }
}

/** The device state a UI sound has to respect, read fresh before every play. */
data class UiSoundConditions(
    /** The user's Mawja switch (off by default). */
    val enabled: Boolean,
    /** The membership has [app.mawja.android.account.MawjaFeature.UI_SOUNDS] (Pro). */
    val entitled: Boolean,
    /** Settings > Sound > Touch sounds (Settings.System.SOUND_EFFECTS_ENABLED). */
    val systemTouchSounds: Boolean,
    /** AudioManager ringer mode is normal (not silent, not vibrate). */
    val ringerNormal: Boolean,
    /** Do Not Disturb is filtering interruptions (any filter other than ALL). */
    val doNotDisturb: Boolean,
)

/**
 * Pure rules for Mawja UI sounds (MawjaFeature.UI_SOUNDS, Pro). A sound plays only when the user
 * turned it on, the membership includes it, the system's own touch sounds are on, the phone is not
 * silent or on vibrate, and Do Not Disturb is off. Volume is kept low so it never competes with media.
 */
object UiSoundPolicy {
    /** SoundPool left/right volume, 0..1. Deliberately quiet. */
    const val VOLUME = 0.18f

    /** Minimum gap between two sounds, so fast scrolling taps or repeated toggles never rattle. */
    const val MIN_INTERVAL_MS = 60L

    fun shouldPlay(conditions: UiSoundConditions): Boolean =
        conditions.enabled &&
            conditions.entitled &&
            conditions.systemTouchSounds &&
            conditions.ringerNormal &&
            !conditions.doNotDisturb

    /** Whether enough time passed since [lastPlayedAt] (elapsed-realtime millis; 0 = never). */
    fun outsideCooldown(
        lastPlayedAt: Long,
        now: Long,
    ): Boolean = lastPlayedAt == 0L || now - lastPlayedAt >= MIN_INTERVAL_MS
}
