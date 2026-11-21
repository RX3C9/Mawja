/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.screens.settings.lab

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.android.R
import app.mawja.android.lab.FeedMood
import app.mawja.android.lab.MawjaLab
import app.mawja.android.ui.components.settings.SettingsPage
import app.mawja.android.ui.components.settings.info
import app.mawja.android.ui.components.settings.switch
import app.mawja.android.ui.components.settings.toggleGroup
import app.mawja.android.ui.components.shared.MawjaToggleOption
import app.mawja.android.ui.screens.settings.index.LabIndex

/** Mawja Lab: the feed mood and the gentle time guardian. */
@Composable
internal fun LabScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
) {
    val mood by MawjaLab.mood.collectAsStateWithLifecycle()
    val guardian by MawjaLab.guardian.collectAsStateWithLifecycle()
    val shake by MawjaLab.shakeRewind.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val history by androidx.compose.runtime.remember(context) {
        app.mawja.android.data.local.ViewHistory.getInstance(context).getAllHistory()
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val week = androidx.compose.runtime.remember(history) { app.mawja.android.lab.WatchStats.week(history, System.currentTimeMillis()) }
    val statsTitle = stringResource(R.string.lab_stats_minutes)
    val statsMinutes = stringResource(R.string.lab_stats_minutes_value, week.minutes, week.videos)
    val statsAvgTitle = stringResource(R.string.lab_stats_average)
    val statsAvg = stringResource(R.string.lab_stats_average_value, week.dailyAverageMinutes)
    val statsTopTitle = stringResource(R.string.lab_stats_top_channel)
    val statsMixTitle = stringResource(R.string.lab_stats_mix)
    val statsMix = stringResource(R.string.lab_stats_mix_value, week.musicPercent, week.shortsPercent)
    val moodOptions =
        listOf(
            MawjaToggleOption(FeedMood.OFF, stringResource(R.string.lab_mood_off)),
            MawjaToggleOption(FeedMood.FOCUS, stringResource(R.string.lab_mood_focus)),
            MawjaToggleOption(FeedMood.LAUGH, stringResource(R.string.lab_mood_laugh)),
            MawjaToggleOption(FeedMood.LEARN, stringResource(R.string.lab_mood_learn)),
            MawjaToggleOption(FeedMood.CALM, stringResource(R.string.lab_mood_calm)),
        )
    SettingsPage(
        title = stringResource(R.string.lab_title),
        subtitle = stringResource(R.string.lab_summary),
        onBack = onBack,
        highlight = highlight,
    ) {
        group(key = "lab.feed", header = R.string.lab_section_feed) {
            toggleGroup(LabIndex.mood, moodOptions, mood, { MawjaLab.setMood(it) })
            switch(LabIndex.guardian, guardian, { MawjaLab.setGuardian(it) }, icon = Icons.Outlined.SelfImprovement)
            switch(LabIndex.shake, shake, { MawjaLab.setShakeRewind(it) }, icon = Icons.Outlined.Vibration)
        }
        group(key = "lab.stats", header = R.string.lab_section_stats) {
            row("lab.stats.bars") { shape -> app.mawja.android.lab.WeekBars(week.perDay, statsMinutes, shape) }
            info("lab.stats.minutes", statsTitle, statsMinutes)
            info("lab.stats.average", statsAvgTitle, statsAvg)
            info("lab.stats.top", statsTopTitle, week.topChannel ?: "—")
            info("lab.stats.mix", statsMixTitle, statsMix)
        }
    }
}
