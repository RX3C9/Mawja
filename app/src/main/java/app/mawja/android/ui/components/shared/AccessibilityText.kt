/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.components.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.mawja.android.R
import app.mawja.android.accessibility.AccessibilityPolicy

/**
 * A duration as TalkBack should say it (#160): "1 hour, 4 minutes, 2 seconds" rather than "1:04:02",
 * which screen readers read as a clock time or a ratio. Zero parts are left out.
 */
@Composable
fun spokenDuration(totalSeconds: Int): String {
    val (hours, minutes, seconds) = AccessibilityPolicy.durationParts(totalSeconds)
    val parts = mutableListOf<String>()
    if (hours > 0) parts += pluralStringResource(R.plurals.a11y_duration_hours, hours, hours)
    if (minutes > 0) parts += pluralStringResource(R.plurals.a11y_duration_minutes, minutes, minutes)
    if (seconds > 0 || parts.isEmpty()) parts += pluralStringResource(R.plurals.a11y_duration_seconds, seconds, seconds)
    return parts.joinToString(stringResource(R.string.a11y_list_separator))
}
