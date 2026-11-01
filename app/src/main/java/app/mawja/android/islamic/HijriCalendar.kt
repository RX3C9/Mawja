/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.islamic

import java.time.LocalDate

data class HijriDate(
    val year: Int,
    val month: Int,
    val day: Int,
)

/**
 * The tabular (arithmetic, "Kuwaiti") Islamic calendar. It can differ by a day from the announced
 * calendar of a country, which follows moon sighting or Umm al-Qura, so the user can shift it by
 * up to two days either way.
 */
object HijriCalendar {
    /** Julian Day Number of 1 Muharram 1 AH in the civil (Friday) epoch. */
    private const val EPOCH_JDN = 1948440L
    const val MAX_ADJUST = 2

    fun fromGregorian(
        date: LocalDate,
        adjustDays: Int = 0,
    ): HijriDate {
        val jdn = toJdn(date) + adjustDays.coerceIn(-MAX_ADJUST, MAX_ADJUST)
        var l = jdn - EPOCH_JDN + 10632
        val n = (l - 1) / 10631
        l = l - 10631 * n + 354
        val j = ((10985 - l) / 5316) * ((50 * l) / 17719) + (l / 5670) * ((43 * l) / 15238)
        l = l - ((30 - j) / 15) * ((17719 * j) / 50) - (j / 16) * ((15238 * j) / 43) + 29
        val month = (24 * l) / 709
        val day = l - (709 * month) / 24
        val year = 30 * n + j - 30
        return HijriDate(year.toInt(), month.toInt(), day.toInt())
    }

    fun toGregorian(
        hijri: HijriDate,
        adjustDays: Int = 0,
    ): LocalDate {
        val y = hijri.year.toLong()
        val jdn =
            hijri.day + ceilDiv(295L * (hijri.month - 1), 10) + (y - 1) * 354 +
                Math.floorDiv(3 + 11 * y, 30L) + EPOCH_JDN - 1
        return fromJdn(jdn - adjustDays.coerceIn(-MAX_ADJUST, MAX_ADJUST))
    }

    /** 30 days in odd months, 29 in even ones, and 30 in Dhu al-Hijjah of the 11 leap years of each 30. */
    fun monthLength(
        year: Int,
        month: Int,
    ): Int =
        when {
            month % 2 == 1 -> 30
            month == 12 && isLeap(year) -> 30
            else -> 29
        }

    fun isLeap(year: Int): Boolean = Math.floorMod(14 + 11 * year, 30) < 11

    private fun ceilDiv(
        a: Long,
        b: Long,
    ): Long = -Math.floorDiv(-a, b)

    // java.time's epoch day 0 (1970-01-01) is Julian Day Number 2440588.
    private fun toJdn(date: LocalDate): Long = date.toEpochDay() + 2440588L

    private fun fromJdn(jdn: Long): LocalDate = LocalDate.ofEpochDay(jdn - 2440588L)
}
