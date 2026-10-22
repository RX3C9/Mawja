/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.battery

import app.mawja.android.data.local.VideoQuality
import app.mawja.android.ui.theme.ThemeVariant

/** When Mawja's battery saver applies. */
enum class BatterySaverMode {
    OFF,

    /** When the battery is at or below the threshold, or the system power-save mode is on. */
    AUTO,
    ALWAYS,
    ;

    companion object {
        fun fromKey(key: String?): BatterySaverMode = entries.firstOrNull { it.name == key } ?: AUTO
    }
}

/** One reading of the battery, decoupled from Android so the rules are unit-testable. */
data class BatteryReading(
    /** 0–100, or -1 when the platform gave no level. */
    val levelPercent: Int,
    val charging: Boolean,
    /** The system battery saver (PowerManager.isPowerSaveMode). */
    val powerSave: Boolean,
) {
    companion object {
        val UNKNOWN = BatteryReading(levelPercent = -1, charging = false, powerSave = false)
    }
}

/** The user's battery saver settings. */
data class BatterySaverSettings(
    val mode: BatterySaverMode = BatterySaverMode.AUTO,
    val thresholdPercent: Int = BatterySaverPolicy.DEFAULT_THRESHOLD,
    /** Switch to the black (AMOLED) style of the current theme while active. */
    val blackTheme: Boolean = true,
    /** Stop decorative motion (animated artwork, mini player waveform/marquee) while active. */
    val reduceMotion: Boolean = true,
)

/** Why the saver is on or off right now — the status line in Settings. */
enum class BatterySaverReason {
    OFF,
    REMOTE_DISABLED,
    ALWAYS,
    SYSTEM_POWER_SAVE,
    LOW_BATTERY,
    CHARGING,
    BATTERY_OK,
}

/**
 * Mawja deep battery saver (PLAN_V2 #62), the pure rules. While active: the default video quality is
 * capped at [MAX_VIDEO_HEIGHT], prefetch/preload and auto-downloads stop, non-essential background
 * work (smart notifications) skips its run, decorative motion stops and the theme switches to its
 * black style. Playback the user starts, downloads they queue and essential jobs keep working.
 */
object BatterySaverPolicy {
    const val DEFAULT_THRESHOLD = 20
    const val MIN_THRESHOLD = 5
    const val MAX_THRESHOLD = 50

    /** Thresholds offered in Settings. */
    val THRESHOLD_CHOICES = listOf(10, 15, 20, 30)

    /** Once on because of a low battery, it stays on until the level climbs this far above the threshold. */
    const val HYSTERESIS = 3

    /** The highest default video height while the saver is active (480p). */
    const val MAX_VIDEO_HEIGHT = 480

    fun clampThreshold(percent: Int): Int = percent.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)

    fun reason(
        settings: BatterySaverSettings,
        reading: BatteryReading,
        remoteEnabled: Boolean,
        wasActive: Boolean = false,
    ): BatterySaverReason {
        if (settings.mode == BatterySaverMode.OFF) return BatterySaverReason.OFF
        if (!remoteEnabled) return BatterySaverReason.REMOTE_DISABLED
        if (settings.mode == BatterySaverMode.ALWAYS) return BatterySaverReason.ALWAYS
        if (reading.powerSave) return BatterySaverReason.SYSTEM_POWER_SAVE
        if (reading.charging) return BatterySaverReason.CHARGING
        val level = reading.levelPercent
        if (level < 0) return BatterySaverReason.BATTERY_OK
        val threshold = clampThreshold(settings.thresholdPercent)
        val limit = if (wasActive) threshold + HYSTERESIS else threshold
        return if (level <= limit) BatterySaverReason.LOW_BATTERY else BatterySaverReason.BATTERY_OK
    }

    fun isActive(
        settings: BatterySaverSettings,
        reading: BatteryReading,
        remoteEnabled: Boolean,
        wasActive: Boolean = false,
    ): Boolean =
        when (reason(settings, reading, remoteEnabled, wasActive)) {
            BatterySaverReason.ALWAYS, BatterySaverReason.SYSTEM_POWER_SAVE, BatterySaverReason.LOW_BATTERY -> true
            else -> false
        }

    /** The default quality to start a video at: Auto and anything above 480p become 480p while active. */
    fun capQuality(
        quality: VideoQuality,
        active: Boolean,
    ): VideoQuality =
        if (active && (quality == VideoQuality.AUTO || quality.height > MAX_VIDEO_HEIGHT)) VideoQuality.Q_480P else quality

    /** A target height (0 = no preference) capped the same way. */
    fun capHeight(
        height: Int,
        active: Boolean,
    ): Int = if (active && (height <= 0 || height > MAX_VIDEO_HEIGHT)) MAX_VIDEO_HEIGHT else height

    /** Speculative network work: prefetching streams for reels ahead, related-video prewarm, auto-download. */
    fun allowPrefetch(active: Boolean): Boolean = !active

    /** Non-essential background jobs (smart notifications) skip their run while active. */
    fun allowNonEssentialJob(active: Boolean): Boolean = !active

    fun reduceMotion(
        settings: BatterySaverSettings,
        active: Boolean,
    ): Boolean = active && settings.reduceMotion

    /** The theme style to draw: the black style of the chosen theme while active with black theme on. */
    fun themeVariant(
        variant: ThemeVariant,
        settings: BatterySaverSettings,
        active: Boolean,
    ): ThemeVariant = if (active && settings.blackTheme) ThemeVariant.AMOLED else variant
}
