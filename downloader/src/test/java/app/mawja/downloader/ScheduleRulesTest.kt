/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.downloader

import app.mawja.downloader.ScheduleRules.Conditions
import app.mawja.downloader.ScheduleRules.Ledger
import app.mawja.downloader.ScheduleRules.Rules
import app.mawja.downloader.ScheduleRules.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleRulesTest {
    private val gib = 1L shl 30

    private fun now(
        unmetered: Boolean = true,
        charging: Boolean = false,
        battery: Int = 80,
        minute: Int = 12 * 60,
        bytesToday: Long = 0,
    ) = Conditions(unmetered, charging, battery, minute, bytesToday)

    @Test
    fun noRulesAlwaysRuns() {
        assertEquals(Verdict.OK, ScheduleRules.evaluate(Rules(), now(unmetered = false, battery = 5)))
    }

    @Test
    fun wifiOnlyWaitsOnMobileData() {
        assertEquals(Verdict.WAIT_WIFI, ScheduleRules.evaluate(Rules(wifiOnly = true), now(unmetered = false)))
        assertEquals(Verdict.WAIT_WIFI, ScheduleRules.evaluate(Rules(), now(unmetered = false), itemWifiOnly = true))
        assertEquals(Verdict.OK, ScheduleRules.evaluate(Rules(wifiOnly = true), now(unmetered = true)))
    }

    @Test
    fun chargingAndBattery() {
        assertEquals(Verdict.WAIT_CHARGING, ScheduleRules.evaluate(Rules(chargingOnly = true), now(charging = false)))
        assertEquals(Verdict.OK, ScheduleRules.evaluate(Rules(chargingOnly = true), now(charging = true)))
        val floor = Rules(minBatteryPercent = 30)
        assertEquals(Verdict.WAIT_BATTERY, ScheduleRules.evaluate(floor, now(battery = 20)))
        assertEquals(Verdict.OK, ScheduleRules.evaluate(floor, now(battery = 20, charging = true)))
        assertEquals(Verdict.OK, ScheduleRules.evaluate(floor, now(battery = 30)))
        assertEquals(Verdict.OK, ScheduleRules.evaluate(floor, now(battery = -1)))
    }

    @Test
    fun nightWindowAndDailyCap() {
        val night = Rules(nightOnly = true, windowStart = 60, windowEnd = 7 * 60)
        assertEquals(Verdict.OK, ScheduleRules.evaluate(night, now(minute = 120)))
        assertEquals(Verdict.WAIT_NIGHT, ScheduleRules.evaluate(night, now(minute = 600)))
        val cap = Rules(dailyCapBytes = gib)
        assertEquals(Verdict.DAILY_CAP, ScheduleRules.evaluate(cap, now(bytesToday = gib)))
        assertEquals(Verdict.OK, ScheduleRules.evaluate(cap, now(bytesToday = gib - 1)))
    }

    @Test
    fun firstBlockingRuleIsReported() {
        val all = Rules(wifiOnly = true, chargingOnly = true, nightOnly = true, dailyCapBytes = 1)
        assertEquals(Verdict.WAIT_WIFI, ScheduleRules.evaluate(all, now(unmetered = false, minute = 600, bytesToday = 5)))
        assertEquals(Verdict.WAIT_CHARGING, ScheduleRules.evaluate(all, now(minute = 600, bytesToday = 5)))
    }

    @Test
    fun windowWrapsPastMidnight() {
        val start = 23 * 60
        val end = 7 * 60
        assertTrue(ScheduleRules.inWindow(23 * 60 + 30, start, end))
        assertTrue(ScheduleRules.inWindow(23 * 60, start, end))
        assertTrue(ScheduleRules.inWindow(3 * 60, start, end))
        assertFalse(ScheduleRules.inWindow(7 * 60, start, end))
        assertFalse(ScheduleRules.inWindow(12 * 60, start, end))
        assertTrue(ScheduleRules.inWindow(-30, start, end))
        assertTrue(ScheduleRules.inWindow(500, 100, 100))
        assertTrue(ScheduleRules.inWindow(100, 60, 420))
        assertFalse(ScheduleRules.inWindow(420, 60, 420))
    }

    @Test
    fun recheckTimes() {
        assertEquals(120, ScheduleRules.minutesUntil(23 * 60, 60))
        assertEquals(0, ScheduleRules.minutesUntil(60, 60))
        val rules = Rules(nightOnly = true, windowStart = 60)
        assertEquals(0L, ScheduleRules.recheckInMs(Verdict.OK, rules, 600))
        assertEquals(0L, ScheduleRules.recheckInMs(Verdict.WAIT_WIFI, rules, 600))
        assertEquals(15 * 60_000L, ScheduleRules.recheckInMs(Verdict.WAIT_BATTERY, rules, 600))
        assertEquals(900 * 60_000L, ScheduleRules.recheckInMs(Verdict.WAIT_NIGHT, rules, 600))
        assertEquals(60 * 60_000L, ScheduleRules.recheckInMs(Verdict.DAILY_CAP, rules, 23 * 60))
        assertEquals(24 * 60 * 60_000L, ScheduleRules.recheckInMs(Verdict.DAILY_CAP, rules, 0))
    }

    @Test
    fun ledgerResetsEachDay() {
        val l = Ledger(20261122, 100)
        assertEquals(Ledger(20261122, 150), l.add(20261122, 50))
        assertEquals(Ledger(20261123, 70), l.add(20261123, 70))
        assertEquals(Ledger(20261122, 100), l.add(20261122, -10))
        assertEquals(100L, l.bytesOn(20261122))
        assertEquals(0L, l.bytesOn(20261123))
    }
}
