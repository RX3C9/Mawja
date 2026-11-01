/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.screens.settings.index

import app.mawja.android.R
import app.mawja.android.ui.components.settings.SettingEntry
import app.mawja.android.ui.components.settings.SettingsDestination

/** The optional Islamic section (#147): every option but the switch sits behind it while it is off. */
internal object IslamicIndex {
    private val page = SettingsDestination.ISLAMIC
    const val ENABLE_KEY = "islamic.enabled"

    private fun entry(
        key: String,
        title: Int,
        section: Int,
        summary: Int? = null,
    ) = SettingEntry(
        key = "islamic.$key",
        title = title,
        summary = summary,
        section = section,
        keywords = R.string.islamic_keywords,
        destination = page,
        revealVia = ENABLE_KEY.takeIf { key != "enabled" },
    )

    val enabled = entry("enabled", R.string.islamic_enable, R.string.islamic_title, R.string.islamic_enable_summary)
    val useLocation = entry("use_location", R.string.islamic_use_location, R.string.islamic_section_location, R.string.islamic_use_location_summary)
    val city = entry("city", R.string.islamic_city, R.string.islamic_section_location)
    val manual = entry("manual", R.string.islamic_manual, R.string.islamic_section_location, R.string.islamic_manual_summary)
    val method = entry("method", R.string.islamic_method, R.string.islamic_section_calculation)
    val asr = entry("asr", R.string.islamic_asr_method, R.string.islamic_section_calculation, R.string.islamic_asr_summary)
    val highLatitude = entry("high_latitude", R.string.islamic_high_lat, R.string.islamic_section_calculation, R.string.islamic_high_lat_summary)
    val offsets = entry("offsets", R.string.islamic_offsets, R.string.islamic_section_calculation, R.string.islamic_offsets_summary)
    val adhan = entry("adhan", R.string.islamic_adhan, R.string.islamic_section_adhan, R.string.islamic_adhan_summary)
    val showHijri = entry("show_hijri", R.string.islamic_show_hijri, R.string.islamic_section_hijri)
    val hijriAdjust = entry("hijri_adjust", R.string.islamic_hijri_adjust, R.string.islamic_section_hijri, R.string.islamic_hijri_adjust_summary)

    val all = listOf(enabled, useLocation, city, manual, method, asr, highLatitude, offsets, adhan, showHijri, hijriAdjust)
}
