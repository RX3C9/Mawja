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

    val all = listOf(mood, guardian, shake)
}
