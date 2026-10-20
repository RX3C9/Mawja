/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.smartnotify

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.exp
import kotlin.math.ln

/**
 * The pure decision logic of the adaptive notifications: activity learning, quiet hours, daily
 * cap, de-duplication by content hash, and learning from opens, dismissals and ignores. No Android
 * types, so it is unit-tested on the JVM.
 */
object SmartNotifyEngine {
    /** Activity half-life: a habit from a month ago counts a quarter as much as this week's. */
    const val ACTIVITY_HALF_LIFE_MILLIS = 14L * SmartNotifyConfig.DAY

    /** Below this many (decayed) app sessions the engine falls back to evening hours. */
    const val MIN_EVENTS_TO_LEARN = 6

    /** Default active hours before anything is learned: 18:00–22:59. */
    val DEFAULT_ACTIVE_HOURS = 18..22

    /** A kind under this weight is muted; it gets one probe again after [MUTED_PROBE_MILLIS]. */
    const val MUTE_THRESHOLD = 0.25
    const val MUTED_PROBE_MILLIS = 14L * SmartNotifyConfig.DAY

    /**
     * An hour counts as active when its score reaches this. The score blends the hour-of-week habit
     * (Thursday 21:00) with the hour-of-day habit (21:00 any day), each relative to its busiest hour,
     * so a sparse week still learns from the daily pattern.
     */
    const val ACTIVE_SCORE = 0.4

    fun hourOfWeek(
        epochMillis: Long,
        zone: ZoneId,
    ): Int {
        val time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
        return (time.dayOfWeek.value - 1) * 24 + time.hour
    }

    fun localHour(
        epochMillis: Long,
        zone: ZoneId,
    ): Int = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone).hour

    fun localDay(
        epochMillis: Long,
        zone: ZoneId,
    ) = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone).toLocalDate()

    /** Stable id for one piece of content of one kind, so the same nudge is never sent twice. */
    fun contentHash(
        kind: SmartKind,
        contentKey: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("${kind.id}:$contentKey".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun decayFactor(elapsedMillis: Long): Double =
        if (elapsedMillis <= 0) 1.0 else exp(-ln(2.0) * elapsedMillis.toDouble() / ACTIVITY_HALF_LIFE_MILLIS)

    /** App opens closer together than this are one session. */
    const val SESSION_GAP_MILLIS = 20L * 60L * 1000L

    /** Records one app session at [epochMillis], decaying older activity first. */
    fun recordActivity(
        state: SmartNotifyState,
        epochMillis: Long,
        zone: ZoneId,
    ): SmartNotifyState {
        if (state.lastDecayAt != 0L && epochMillis >= state.lastDecayAt && epochMillis - state.lastDecayAt < SESSION_GAP_MILLIS) return state
        val factor = if (state.lastDecayAt == 0L) 1.0 else decayFactor(epochMillis - state.lastDecayAt)
        val bin = hourOfWeek(epochMillis, zone)
        val weights = normalizedWeights(state).mapIndexed { index, w -> w * factor + if (index == bin) 1.0 else 0.0 }
        return state.copy(
            hourWeights = weights,
            activityEvents = state.activityEvents + 1,
            lastDecayAt = maxOf(epochMillis, state.lastDecayAt),
        )
    }

    private fun normalizedWeights(state: SmartNotifyState): List<Double> =
        if (state.hourWeights.size == SmartNotifyState.HOURS_PER_WEEK) {
            state.hourWeights
        } else {
            List(SmartNotifyState.HOURS_PER_WEEK) { 0.0 }
        }

    /** The hour's weight blended with its neighbours, so 20:55 and 21:05 count as the same habit. */
    private fun smoothed(
        weights: List<Double>,
        bin: Int,
    ): Double {
        val n = weights.size
        return weights[bin] + 0.5 * weights[(bin + n - 1) % n] + 0.5 * weights[(bin + 1) % n]
    }

    /** 0..1: how typical [epochMillis] is for this person to be using the app. */
    fun activityScore(
        state: SmartNotifyState,
        epochMillis: Long,
        zone: ZoneId,
    ): Double {
        val weights = normalizedWeights(state)
        val bin = hourOfWeek(epochMillis, zone)
        val weekPeak = weights.indices.maxOf { smoothed(weights, it) }
        val daily = List(24) { h -> (0 until 7).sumOf { d -> weights[d * 24 + h] } }
        val dayPeak = daily.indices.maxOf { smoothed(daily, it) }
        if (weekPeak <= 0.0 || dayPeak <= 0.0) return 0.0
        return 0.5 * smoothed(weights, bin) / weekPeak + 0.5 * smoothed(daily, bin % 24) / dayPeak
    }

    /** Whether [epochMillis] falls in one of this person's usual hours. */
    fun isActiveTime(
        state: SmartNotifyState,
        epochMillis: Long,
        zone: ZoneId,
    ): Boolean {
        if (state.activityEvents < MIN_EVENTS_TO_LEARN || normalizedWeights(state).sum() <= 0.0) {
            return localHour(epochMillis, zone) in DEFAULT_ACTIVE_HOURS
        }
        return activityScore(state, epochMillis, zone) >= ACTIVE_SCORE
    }

    fun isQuietHour(
        hour: Int,
        config: SmartNotifyConfig,
    ): Boolean {
        val start = config.quietStartHour
        val end = config.quietEndHour
        return when {
            start == end -> false
            start < end -> hour in start until end
            else -> hour >= start || hour < end
        }
    }

    fun decide(
        candidate: SmartCandidate,
        state: SmartNotifyState,
        config: SmartNotifyConfig,
        now: Long,
        zone: ZoneId,
    ): SmartDecision {
        if (!config.enabled || config.dailyLimit <= 0) return SmartDecision.DISABLED
        if (isQuietHour(localHour(now, zone), config)) return SmartDecision.QUIET_HOURS
        val hash = contentHash(candidate.kind, candidate.contentKey)
        if (state.sent.any { it.hash == hash && now - it.sentAt < config.dedupeWindowMillis }) return SmartDecision.DUPLICATE
        val today = localDay(now, zone)
        if (state.sent.count { localDay(it.sentAt, zone) == today } >= config.dailyLimit) return SmartDecision.DAILY_LIMIT
        val last = state.sent.maxOfOrNull { it.sentAt }
        if (last != null && now - last < config.minGapMillis) return SmartDecision.TOO_SOON
        if (state.kindWeight(candidate.kind) < MUTE_THRESHOLD) {
            val lastOfKind = state.sent.filter { it.kind == candidate.kind }.maxOfOrNull { it.sentAt }
            if (lastOfKind != null && now - lastOfKind < MUTED_PROBE_MILLIS) return SmartDecision.KIND_MUTED
        }
        if (!isActiveTime(state, now, zone)) return SmartDecision.NOT_ACTIVE_TIME
        return SmartDecision.SEND
    }

    /** The best candidate allowed right now, by its own priority times how much this person likes its kind. */
    fun pick(
        candidates: List<SmartCandidate>,
        state: SmartNotifyState,
        config: SmartNotifyConfig,
        now: Long,
        zone: ZoneId,
    ): SmartCandidate? =
        candidates
            .sortedByDescending { it.priority * state.kindWeight(it.kind) }
            .firstOrNull { decide(it, state, config, now, zone) == SmartDecision.SEND }

    fun recordSent(
        state: SmartNotifyState,
        candidate: SmartCandidate,
        now: Long,
    ): SmartNotifyState =
        state.copy(sent = state.sent + SentRecord(contentHash(candidate.kind, candidate.contentKey), candidate.kind, now))

    private fun adjust(
        weight: Double,
        outcome: SmartOutcome,
    ): Double =
        when (outcome) {
            SmartOutcome.OPENED -> weight + (1.0 - weight) * 0.3
            SmartOutcome.DISMISSED -> weight * 0.7
            SmartOutcome.IGNORED -> weight * 0.85
            SmartOutcome.PENDING -> weight
        }.coerceIn(0.05, 1.0)

    /** Applies what the person did with a posted notification. Only the first outcome counts. */
    fun recordOutcome(
        state: SmartNotifyState,
        hash: String,
        outcome: SmartOutcome,
    ): SmartNotifyState {
        val record = state.sent.firstOrNull { it.hash == hash } ?: return state
        if (record.outcome != SmartOutcome.PENDING || outcome == SmartOutcome.PENDING) return state
        return state.copy(
            sent = state.sent.map { if (it.hash == hash) it.copy(outcome = outcome) else it },
            kindWeights = state.kindWeights + (record.kind to adjust(state.kindWeight(record.kind), outcome)),
        )
    }

    /** Marks old pending notifications as ignored and forgets anything past the de-dup window. */
    fun housekeep(
        state: SmartNotifyState,
        config: SmartNotifyConfig,
        now: Long,
    ): SmartNotifyState {
        var next = state
        state.sent
            .filter { it.outcome == SmartOutcome.PENDING && now - it.sentAt >= config.ignoreAfterMillis }
            .forEach { next = recordOutcome(next, it.hash, SmartOutcome.IGNORED) }
        return next.copy(sent = next.sent.filter { now - it.sentAt < config.dedupeWindowMillis })
    }
}
