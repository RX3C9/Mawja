/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.screens.settings.lab

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.android.R
import app.mawja.android.data.recommendation.MawjaNeuroEngine
import app.mawja.android.lab.BrainGlimpse
import app.mawja.android.lab.FeedMood
import app.mawja.android.lab.MawjaLab
import app.mawja.android.lab.Transparency
import app.mawja.android.lab.WeekMemo
import app.mawja.android.lab.WeekPace
import app.mawja.android.ui.components.settings.SettingsPage
import app.mawja.android.ui.components.settings.info
import app.mawja.android.ui.components.settings.nav
import app.mawja.android.ui.components.settings.switch
import app.mawja.android.ui.components.settings.toggleGroup
import app.mawja.android.ui.components.shared.MawjaToggleOption
import app.mawja.android.ui.screens.settings.index.LabIndex
import kotlinx.coroutines.launch

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
    val sleepSentence by MawjaLab.sleepSentence.collectAsStateWithLifecycle()
    val nightSounds by MawjaLab.nightSounds.collectAsStateWithLifecycle()
    val bedtime by MawjaLab.bedtime.collectAsStateWithLifecycle()
    val bedtimeStart by MawjaLab.bedtimeStartHour.collectAsStateWithLifecycle()
    val nightLeveling by MawjaLab.nightLeveling.collectAsStateWithLifecycle()
    val oneHand by MawjaLab.oneHand.collectAsStateWithLifecycle()
    val bedtimeOptions = listOf(22, 23, 0).map { h -> MawjaToggleOption(h, "%02d:00".format(h)) }
    val paceText =
        stringResource(
            when (WeekMemo.pace(week.dailyAverageMinutes)) {
                WeekPace.QUIET -> R.string.lab_memo_pace_quiet
                WeekPace.STEADY -> R.string.lab_memo_pace_steady
                WeekPace.DEEP -> R.string.lab_memo_pace_deep
            },
        )
    val busiestAgo = WeekMemo.busiestDaysAgo(week.perDay)
    val busiestText =
        when {
            busiestAgo == null -> ""
            busiestAgo == 0 -> stringResource(R.string.lab_memo_busiest_today)
            else -> stringResource(R.string.lab_memo_busiest_ago, busiestAgo)
        }
    val memoText =
        listOf(
            stringResource(R.string.lab_memo_text, week.minutes, week.videos, paceText, week.topChannel ?: "—"),
            busiestText,
        ).filter { it.isNotBlank() }.joinToString(" ")
    val memoChooser = stringResource(R.string.lab_memo_chooser)
    var glimpse by androidx.compose.runtime.remember { mutableStateOf<BrainGlimpse?>(null) }
    var confirmForget by androidx.compose.runtime.remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        glimpse = runCatching { Transparency.glimpse(MawjaNeuroEngine.getInstance(context).getBrainSnapshot()) }.getOrNull()
    }
    val knowTopicsTitle = stringResource(R.string.lab_know_topics)
    val knowTopics = glimpse?.topics?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: stringResource(R.string.lab_know_none)
    val knowCountsTitle = stringResource(R.string.lab_know_counts_title)
    val knowCounts =
        glimpse?.let { stringResource(R.string.lab_know_counts, it.interactions, it.channelsKnown, it.hidden) }
            ?: stringResource(R.string.lab_know_none)
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.lab_forget_title)) },
            text = { Text(stringResource(R.string.lab_forget_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmForget = false
                        scope.launch {
                            runCatching { MawjaNeuroEngine.getInstance(context).resetBrain() }
                            glimpse = runCatching { Transparency.glimpse(MawjaNeuroEngine.getInstance(context).getBrainSnapshot()) }.getOrNull()
                        }
                    },
                ) { Text(stringResource(R.string.lab_forget_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
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
        group(key = "lab.player", header = R.string.lab_section_player) {
            switch(LabIndex.oneHand, oneHand, { MawjaLab.setOneHand(it) }, icon = Icons.Outlined.PhoneAndroid)
        }
        group(key = "lab.night", header = R.string.lab_section_night) {
            switch(LabIndex.sleepSentence, sleepSentence, { MawjaLab.setSleepSentence(it) }, icon = Icons.Outlined.Bedtime)
            switch(LabIndex.nightSounds, nightSounds, { MawjaLab.setNightSounds(it) }, icon = Icons.Outlined.MusicNote)
            switch(LabIndex.bedtime, bedtime, { MawjaLab.setBedtime(it) }, icon = Icons.Outlined.NightsStay)
            switch(LabIndex.nightLeveling, nightLeveling, { MawjaLab.setNightLeveling(it) }, icon = Icons.Outlined.GraphicEq)
            toggleGroup(
                LabIndex.bedtimeStart,
                bedtimeOptions,
                bedtimeStart,
                { MawjaLab.setBedtimeStartHour(it) },
                enabled = bedtime || nightLeveling,
            )
        }
        group(key = "lab.know", header = R.string.lab_section_know) {
            info("lab.know.topics", knowTopicsTitle, knowTopics)
            info("lab.know.counts", knowCountsTitle, knowCounts)
            nav(LabIndex.forget, onClick = { confirmForget = true }, showChevron = false, icon = Icons.Outlined.DeleteSweep)
        }
        group(key = "lab.stats", header = R.string.lab_section_stats) {
            row("lab.stats.bars") { shape -> app.mawja.android.lab.WeekBars(week.perDay, statsMinutes, shape) }
            info("lab.stats.minutes", statsTitle, statsMinutes)
            info("lab.stats.average", statsAvgTitle, statsAvg)
            info("lab.stats.top", statsTopTitle, week.topChannel ?: "—")
            info("lab.stats.mix", statsMixTitle, statsMix)
            nav(
                LabIndex.memo,
                onClick = {
                    val send =
                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, memoText)
                        }
                    context.startActivity(android.content.Intent.createChooser(send, memoChooser))
                },
                showChevron = false,
                icon = Icons.Outlined.Share,
            )
        }
    }
}
