/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.screens.settings.index

import app.mawja.android.R
import app.mawja.android.ui.components.settings.SettingEntry
import app.mawja.android.ui.components.settings.SettingsDestination

internal object BatterySaverIndex {
    private val page = SettingsDestination.BATTERY_SAVER

    private fun entry(
        key: String,
        title: Int,
        section: Int,
        summary: Int? = null,
    ) = SettingEntry(
        key = "battery.$key",
        title = title,
        summary = summary,
        section = section,
        keywords = R.string.battery_saver_keywords,
        destination = page,
    )

    val mode = entry("mode", R.string.battery_saver_mode, R.string.battery_saver_title, R.string.battery_saver_mode_summary)
    val threshold = entry("threshold", R.string.battery_saver_threshold, R.string.battery_saver_title, R.string.battery_saver_threshold_summary)
    val blackTheme =
        entry("black_theme", R.string.battery_saver_black_theme, R.string.battery_saver_section_extras, R.string.battery_saver_black_theme_summary)
    val reduceMotion =
        entry("reduce_motion", R.string.battery_saver_reduce_motion, R.string.battery_saver_section_extras, R.string.battery_saver_reduce_motion_summary)

    val all = listOf(mode, threshold, blackTheme, reduceMotion)
}
