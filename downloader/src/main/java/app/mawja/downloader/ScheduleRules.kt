/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.downloader

/**
 * Smart scheduling (plan §6): Wi-Fi only, only while charging, battery above a floor, only inside
 * a night window, and a daily data cap. Pure: the engine reads the phone's state into [Conditions].
 */
object ScheduleRules {
    data class Rules(
        val wifiOnly: Boolean = false,
        val chargingOnly: Boolean = false,
        /** 0 = no floor. */
        val minBatteryPercent: Int = 0,
        val nightOnly: Boolean = false,
        /** Minutes after midnight; the window may wrap past midnight (e.g. 23:00 → 07:00). */
        val windowStart: Int = 60,
        val windowEnd: Int = 7 * 60,
        /** 0 = no cap. Counts every byte the engine downloads today, on any network. */
        val dailyCapBytes: Long = 0,
    )

    data class Conditions(
        val unmetered: Boolean,
        val charging: Boolean,
        /** -1 when unknown (never blocks). */
        val batteryPercent: Int,
        val minuteOfDay: Int,
        val bytesToday: Long,
    )

    /** Why nothing may start now; [OK] lets the queue run. Order = the order the user sees them in. */
    enum class Verdict { OK, WAIT_WIFI, WAIT_CHARGING, WAIT_BATTERY, WAIT_NIGHT, DAILY_CAP }

    /** The battery floor offered in settings ("above 30%"). */
    const val BATTERY_FLOOR = 30

    /** The daily cap choices offered in settings, bytes; 0 = no cap. */
    val DAILY_CAPS = listOf(0L, 500L shl 20, 1L shl 30, 2L shl 30, 5L shl 30)

    /** First rule that blocks, or OK. [itemWifiOnly] is the download's own Wi-Fi flag. */
    fun evaluate(
        rules: Rules,
        now: Conditions,
        itemWifiOnly: Boolean = false,
    ): Verdict =
        when {
            (rules.wifiOnly || itemWifiOnly) && !now.unmetered -> Verdict.WAIT_WIFI
            rules.chargingOnly && !now.charging -> Verdict.WAIT_CHARGING
            rules.minBatteryPercent > 0 && now.batteryPercent in 0 until rules.minBatteryPercent && !now.charging -> Verdict.WAIT_BATTERY
            rules.nightOnly && !inWindow(now.minuteOfDay, rules.windowStart, rules.windowEnd) -> Verdict.WAIT_NIGHT
            rules.dailyCapBytes > 0 && now.bytesToday >= rules.dailyCapBytes -> Verdict.DAILY_CAP
            else -> Verdict.OK
        }

    /** True when [minute] lies in [start, end), wrapping past midnight when end <= start. */
    fun inWindow(
        minute: Int,
        start: Int,
        end: Int,
    ): Boolean {
        val m = Math.floorMod(minute, DAY)
        val s = Math.floorMod(start, DAY)
        val e = Math.floorMod(end, DAY)
        return when {
            s == e -> true
            s < e -> m in s until e
            else -> m >= s || m < e
        }
    }

    /** Minutes from [minute] until [target] (0..1439). */
    fun minutesUntil(
        minute: Int,
        target: Int,
    ): Int = Math.floorMod(target - minute, DAY)

    /**
     * When to look again for a blocking [verdict], in ms. Night waits for the window to open, the
     * cap waits for midnight; battery and charging re-check every 15 minutes (WorkManager also
     * wakes on charging), Wi-Fi is left to the network constraint (0).
     */
    fun recheckInMs(
        verdict: Verdict,
        rules: Rules,
        minuteOfDay: Int,
    ): Long =
        when (verdict) {
            Verdict.OK, Verdict.WAIT_WIFI -> 0L
            Verdict.WAIT_CHARGING, Verdict.WAIT_BATTERY -> 15 * MINUTE_MS
            Verdict.WAIT_NIGHT -> minutesUntil(minuteOfDay, rules.windowStart).coerceAtLeast(1) * MINUTE_MS
            Verdict.DAILY_CAP -> minutesUntil(minuteOfDay, 0).let { if (it == 0) DAY else it } * MINUTE_MS
        }

    /** Bytes downloaded today, reset when the calendar day changes ([day] like 20261122). */
    data class Ledger(
        val day: Int,
        val bytes: Long,
    ) {
        fun add(
            today: Int,
            delta: Long,
        ): Ledger = if (today == day) copy(bytes = bytes + delta.coerceAtLeast(0)) else Ledger(today, delta.coerceAtLeast(0))

        fun bytesOn(today: Int): Long = if (today == day) bytes else 0L
    }

    private const val DAY = 24 * 60
    private const val MINUTE_MS = 60_000L
}
