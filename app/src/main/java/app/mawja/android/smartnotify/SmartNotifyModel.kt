/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.smartnotify

/**
 * Mawja's on-device adaptive notifications. Everything here runs on the phone: the engine learns
 * when this person uses the app and which kinds of nudge they open, and never sends that anywhere.
 * The server only ever sends general/admin announcements.
 */
enum class SmartKind(
    val id: String,
) {
    /** A video left half-watched. */
    CONTINUE_WATCHING("continue"),

    /** A new upload from a channel this person watches a lot. */
    FAVOURITE_CHANNEL("channel"),

    /** A weekly look back at what they watched and played. */
    WEEKLY_RECAP("recap"),

    /** A mix suggestion for the hour they usually listen to music. */
    SMART_DJ("dj"),
    ;

    companion object {
        fun of(id: String?): SmartKind? = entries.firstOrNull { it.id == id }
    }
}

/** One notification the engine could send. [contentKey] identifies the content, for de-duplication. */
data class SmartCandidate(
    val kind: SmartKind,
    val contentKey: String,
    val title: String,
    val text: String,
    val videoId: String? = null,
    /** 0..1, how good this candidate is on its own merits. */
    val priority: Double = 0.5,
)

/** User-facing limits. Defaults keep the engine quiet: two a day at most, never at night. */
data class SmartNotifyConfig(
    val enabled: Boolean = true,
    val dailyLimit: Int = 2,
    /** Quiet hours, local time, [quietStartHour] inclusive to [quietEndHour] exclusive; may wrap midnight. */
    val quietStartHour: Int = 23,
    val quietEndHour: Int = 8,
    val minGapMillis: Long = 3 * HOUR,
    val dedupeWindowMillis: Long = 30 * DAY,
    /** A notification neither opened nor swiped away within this long counts as ignored. */
    val ignoreAfterMillis: Long = DAY,
) {
    companion object {
        const val HOUR = 60L * 60L * 1000L
        const val DAY = 24L * HOUR
    }
}

/** What happened to a notification after it was posted. */
enum class SmartOutcome {
    PENDING,
    OPENED,
    DISMISSED,
    IGNORED,
}

data class SentRecord(
    val hash: String,
    val kind: SmartKind,
    val sentAt: Long,
    val outcome: SmartOutcome = SmartOutcome.PENDING,
)

/**
 * Everything the engine has learned. [hourWeights] is a decayed histogram over the 168 hours of a
 * week (Monday 00:00 = 0); [kindWeights] is 0..1 per kind, raised by opens and lowered by dismissals
 * and ignores.
 */
data class SmartNotifyState(
    val hourWeights: List<Double> = List(HOURS_PER_WEEK) { 0.0 },
    val activityEvents: Int = 0,
    val lastDecayAt: Long = 0L,
    val kindWeights: Map<SmartKind, Double> = emptyMap(),
    val sent: List<SentRecord> = emptyList(),
) {
    fun kindWeight(kind: SmartKind): Double = kindWeights[kind] ?: DEFAULT_KIND_WEIGHT

    companion object {
        const val HOURS_PER_WEEK = 168
        const val DEFAULT_KIND_WEIGHT = 0.7
    }
}

/** Why a candidate was or was not sent. */
enum class SmartDecision {
    SEND,
    DISABLED,
    QUIET_HOURS,
    DAILY_LIMIT,
    TOO_SOON,
    DUPLICATE,
    KIND_MUTED,
    NOT_ACTIVE_TIME,
}
