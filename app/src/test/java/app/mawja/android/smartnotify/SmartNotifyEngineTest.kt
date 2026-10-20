/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.smartnotify

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SmartNotifyEngineTest {
    private val zone: ZoneId = ZoneId.of("Africa/Cairo")
    private val config = SmartNotifyConfig()
    private val hour = SmartNotifyConfig.HOUR
    private val day = SmartNotifyConfig.DAY

    /** 2026-10-21 is a Wednesday. */
    private fun at(
        day: Int,
        hour: Int,
        minute: Int = 0,
    ): Long = LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun candidate(
        key: String = "v1",
        kind: SmartKind = SmartKind.CONTINUE_WATCHING,
        priority: Double = 0.8,
    ) = SmartCandidate(kind, key, "t", "x", priority = priority)

    /** A state that learned this person uses the app around 21:00 every day for two weeks. */
    private fun eveningUser(): SmartNotifyState {
        var state = SmartNotifyState()
        for (d in 1..14) state = SmartNotifyEngine.recordActivity(state, at(d, 21), zone)
        return state
    }

    @Test
    fun hourOfWeekStartsOnMonday() {
        assertThat(SmartNotifyEngine.hourOfWeek(at(19, 0), zone)).isEqualTo(0) // Monday 19 Oct
        assertThat(SmartNotifyEngine.hourOfWeek(at(21, 21), zone)).isEqualTo(2 * 24 + 21)
    }

    @Test
    fun quietHoursWrapMidnight() {
        assertThat(SmartNotifyEngine.isQuietHour(23, config)).isTrue()
        assertThat(SmartNotifyEngine.isQuietHour(3, config)).isTrue()
        assertThat(SmartNotifyEngine.isQuietHour(7, config)).isTrue()
        assertThat(SmartNotifyEngine.isQuietHour(8, config)).isFalse()
        assertThat(SmartNotifyEngine.isQuietHour(22, config)).isFalse()
        assertThat(SmartNotifyEngine.isQuietHour(12, config.copy(quietStartHour = 9, quietEndHour = 17))).isTrue()
        assertThat(SmartNotifyEngine.isQuietHour(12, config.copy(quietStartHour = 0, quietEndHour = 0))).isFalse()
    }

    @Test
    fun learnsActiveHoursAndFallsBackToEveningsBeforeThat() {
        val fresh = SmartNotifyState()
        assertThat(SmartNotifyEngine.isActiveTime(fresh, at(21, 20), zone)).isTrue()
        assertThat(SmartNotifyEngine.isActiveTime(fresh, at(21, 10), zone)).isFalse()

        val learned = eveningUser()
        assertThat(SmartNotifyEngine.isActiveTime(learned, at(22, 21), zone)).isTrue()
        assertThat(SmartNotifyEngine.isActiveTime(learned, at(22, 20), zone)).isTrue() // neighbour hour
        assertThat(SmartNotifyEngine.isActiveTime(learned, at(22, 10), zone)).isFalse()
    }

    @Test
    fun opensWithinOneSessionCountOnce() {
        var state = SmartNotifyEngine.recordActivity(SmartNotifyState(), at(21, 21), zone)
        state = SmartNotifyEngine.recordActivity(state, at(21, 21, 5), zone)
        assertThat(state.activityEvents).isEqualTo(1)
        state = SmartNotifyEngine.recordActivity(state, at(21, 22), zone)
        assertThat(state.activityEvents).isEqualTo(2)
    }

    @Test
    fun olderActivityDecays() {
        var state = SmartNotifyEngine.recordActivity(SmartNotifyState(), at(1, 9), zone)
        state = SmartNotifyEngine.recordActivity(state, at(15, 9), zone)
        val thursdayMorning = state.hourWeights[SmartNotifyEngine.hourOfWeek(at(1, 9), zone)]
        assertThat(thursdayMorning).isWithin(0.01).of(1.5) // 0.5 after one half-life, plus today's 1
    }

    @Test
    fun sendsAtAnActiveHourOutsideQuietHours() {
        assertThat(SmartNotifyEngine.decide(candidate(), eveningUser(), config, at(22, 21), zone)).isEqualTo(SmartDecision.SEND)
    }

    @Test
    fun respectsSwitchQuietHoursAndActiveTime() {
        val state = eveningUser()
        assertThat(SmartNotifyEngine.decide(candidate(), state, config.copy(enabled = false), at(22, 21), zone))
            .isEqualTo(SmartDecision.DISABLED)
        assertThat(SmartNotifyEngine.decide(candidate(), state, config, at(22, 23, 30), zone)).isEqualTo(SmartDecision.QUIET_HOURS)
        assertThat(SmartNotifyEngine.decide(candidate(), state, config, at(22, 10), zone)).isEqualTo(SmartDecision.NOT_ACTIVE_TIME)
    }

    @Test
    fun neverSendsTheSameContentTwice() {
        val sent = SmartNotifyEngine.recordSent(eveningUser(), candidate("v1"), at(20, 21))
        assertThat(SmartNotifyEngine.decide(candidate("v1"), sent, config, at(22, 21), zone)).isEqualTo(SmartDecision.DUPLICATE)
        assertThat(SmartNotifyEngine.decide(candidate("v2"), sent, config, at(22, 21), zone)).isEqualTo(SmartDecision.SEND)
        assertThat(SmartNotifyEngine.contentHash(SmartKind.CONTINUE_WATCHING, "v1"))
            .isNotEqualTo(SmartNotifyEngine.contentHash(SmartKind.SMART_DJ, "v1"))
    }

    @Test
    fun dailyLimitAndMinimumGap() {
        var state = SmartNotifyEngine.recordSent(eveningUser(), candidate("a"), at(22, 9))
        assertThat(SmartNotifyEngine.decide(candidate("b"), state, config, at(22, 10), zone)).isEqualTo(SmartDecision.TOO_SOON)
        state = SmartNotifyEngine.recordSent(state, candidate("b"), at(22, 13))
        assertThat(SmartNotifyEngine.decide(candidate("c"), state, config, at(22, 21), zone)).isEqualTo(SmartDecision.DAILY_LIMIT)
        assertThat(SmartNotifyEngine.decide(candidate("c"), state, config.copy(dailyLimit = 3), at(22, 21), zone))
            .isEqualTo(SmartDecision.SEND)
    }

    @Test
    fun learnsFromOpensDismissalsAndIgnores() {
        val c = candidate("a", SmartKind.SMART_DJ)
        val hash = SmartNotifyEngine.contentHash(c.kind, c.contentKey)
        val base = SmartNotifyEngine.recordSent(eveningUser(), c, at(20, 21))
        val opened = SmartNotifyEngine.recordOutcome(base, hash, SmartOutcome.OPENED)
        val dismissed = SmartNotifyEngine.recordOutcome(base, hash, SmartOutcome.DISMISSED)
        assertThat(opened.kindWeight(SmartKind.SMART_DJ)).isGreaterThan(SmartNotifyState.DEFAULT_KIND_WEIGHT)
        assertThat(dismissed.kindWeight(SmartKind.SMART_DJ)).isLessThan(SmartNotifyState.DEFAULT_KIND_WEIGHT)
        // Only the first outcome counts.
        assertThat(SmartNotifyEngine.recordOutcome(opened, hash, SmartOutcome.DISMISSED)).isEqualTo(opened)

        val ignored = SmartNotifyEngine.housekeep(base, config, at(20, 21) + day + hour)
        assertThat(ignored.sent.single().outcome).isEqualTo(SmartOutcome.IGNORED)
        assertThat(ignored.kindWeight(SmartKind.SMART_DJ)).isLessThan(SmartNotifyState.DEFAULT_KIND_WEIGHT)
    }

    @Test
    fun repeatedlyDismissedKindIsMutedThenProbedAgain() {
        var state = eveningUser()
        var t = at(15, 21)
        repeat(6) { i ->
            val c = candidate("dj$i", SmartKind.SMART_DJ)
            state = SmartNotifyEngine.recordSent(state, c, t)
            state = SmartNotifyEngine.recordOutcome(state, SmartNotifyEngine.contentHash(c.kind, c.contentKey), SmartOutcome.DISMISSED)
            t += day
        }
        assertThat(state.kindWeight(SmartKind.SMART_DJ)).isLessThan(SmartNotifyEngine.MUTE_THRESHOLD)
        val soon = at(22, 21)
        assertThat(SmartNotifyEngine.decide(candidate("dj-new", SmartKind.SMART_DJ), state, config, soon, zone))
            .isEqualTo(SmartDecision.KIND_MUTED)
        // Other kinds are unaffected.
        assertThat(SmartNotifyEngine.decide(candidate("cw", SmartKind.CONTINUE_WATCHING), state, config, soon, zone))
            .isEqualTo(SmartDecision.SEND)
        val later = t + SmartNotifyEngine.MUTED_PROBE_MILLIS + day
        assertThat(SmartNotifyEngine.decide(candidate("dj-new", SmartKind.SMART_DJ), state, config, later - (later % day) + 19 * hour, zone))
            .isAnyOf(SmartDecision.SEND, SmartDecision.NOT_ACTIVE_TIME)
    }

    @Test
    fun pickPrefersLikedKinds() {
        val state = eveningUser().copy(kindWeights = mapOf(SmartKind.SMART_DJ to 1.0, SmartKind.CONTINUE_WATCHING to 0.3))
        val chosen =
            SmartNotifyEngine.pick(
                listOf(candidate("cw", SmartKind.CONTINUE_WATCHING, 0.8), candidate("dj", SmartKind.SMART_DJ, 0.5)),
                state,
                config,
                at(22, 21),
                zone,
            )
        assertThat(chosen?.kind).isEqualTo(SmartKind.SMART_DJ)
    }

    @Test
    fun housekeepingForgetsOldRecords() {
        val state = SmartNotifyEngine.recordSent(SmartNotifyState(), candidate("old"), at(1, 21))
        assertThat(SmartNotifyEngine.housekeep(state, config, at(1, 21) + 31 * day).sent).isEmpty()
    }
}
