/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.screens.settings.index

import app.mawja.android.R
import app.mawja.android.ui.components.settings.SettingEntry
import app.mawja.android.ui.components.settings.SettingsDestination

/** Accessibility (#161 large text and high contrast, #162 colour-blind mode, #163 reduce motion). */
internal object AccessibilityIndex {
    private val page = SettingsDestination.ACCESSIBILITY

    private fun entry(
        key: String,
        title: Int,
        section: Int,
        summary: Int? = null,
    ) = SettingEntry(
        key = "accessibility.$key",
        title = title,
        summary = summary,
        section = section,
        keywords = R.string.a11y_keywords,
        destination = page,
    )

    val textSize = entry("text_size", R.string.a11y_text_size, R.string.a11y_section_text, R.string.a11y_text_size_summary)
    val highContrast = entry("high_contrast", R.string.a11y_high_contrast, R.string.a11y_section_text, R.string.a11y_high_contrast_summary)
    val colorVision = entry("color_vision", R.string.a11y_color_vision, R.string.a11y_section_color, R.string.a11y_color_vision_summary)
    val reduceMotion = entry("reduce_motion", R.string.a11y_reduce_motion, R.string.a11y_section_motion, R.string.a11y_reduce_motion_summary)

    val all = listOf(textSize, highContrast, colorVision, reduceMotion)
}
