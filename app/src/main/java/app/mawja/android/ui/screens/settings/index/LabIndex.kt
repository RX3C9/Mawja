/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.screens.settings.index

import app.mawja.android.R
import app.mawja.android.ui.components.settings.SettingEntry
import app.mawja.android.ui.components.settings.SettingsDestination

/** Mawja Lab: feed mood and the time guardian. */
internal object LabIndex {
    private val page = SettingsDestination.LAB

    private fun entry(
        key: String,
        title: Int,
        section: Int,
        summary: Int? = null,
    ) = SettingEntry(
        key = "lab.$key",
        title = title,
        summary = summary,
        section = section,
        keywords = R.string.lab_keywords,
        destination = page,
    )

    val mood = entry("mood", R.string.lab_mood, R.string.lab_section_feed, R.string.lab_mood_summary)
    val guardian = entry("guardian", R.string.lab_guardian, R.string.lab_section_feed, R.string.lab_guardian_summary)

    val shake = entry("shake", R.string.lab_shake, R.string.lab_section_feed, R.string.lab_shake_summary)

    val sleepSentence =
        entry("sleep_sentence", R.string.lab_sleep_sentence, R.string.lab_section_night, R.string.lab_sleep_sentence_summary)
    val nightSounds = entry("night_sounds", R.string.lab_night_sounds, R.string.lab_section_night, R.string.lab_night_sounds_summary)
    val bedtime = entry("bedtime", R.string.lab_bedtime, R.string.lab_section_night, R.string.lab_bedtime_summary)
    val bedtimeStart = entry("bedtime_start", R.string.lab_bedtime_start, R.string.lab_section_night, R.string.lab_bedtime_start_summary)
    val memo = entry("memo", R.string.lab_memo, R.string.lab_section_stats, R.string.lab_memo_summary)

    val forget = entry("forget", R.string.lab_forget, R.string.lab_section_know, R.string.lab_forget_summary)

    val all = listOf(mood, guardian, shake, sleepSentence, nightSounds, bedtime, bedtimeStart, memo, forget)
}
