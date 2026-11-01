/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.screens.library

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.android.R
import app.mawja.android.islamic.MawjaIslamic
import app.mawja.android.islamic.PrayerAlarms
import app.mawja.android.islamic.PrayerSchedule
import app.mawja.android.ui.components.library.LibraryNavigationRow
import app.mawja.android.ui.components.shared.FlowMaxContentWidth
import app.mawja.android.ui.screens.settings.islamic.countdownText
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay

/** Library's entry to the optional Islamic section (#147), shown only while it is turned on. */
@Composable
internal fun LibraryIslamicRow(onOpen: () -> Unit) {
    val context = LocalContext.current
    val settings by MawjaIslamic.settings.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            now = System.currentTimeMillis()
        }
    }
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val today = remember(settings, date) { MawjaIslamic.day(context, date, zone, settings) }
    val tomorrow = remember(settings, date) { MawjaIslamic.day(context, date.plusDays(1), zone, settings) }
    val next = if (today != null && tomorrow != null) PrayerSchedule.next(now, today, tomorrow) else null
    val subtitle =
        if (next != null) {
            stringResource(R.string.islamic_next, stringResource(PrayerAlarms.prayerName(next.prayer)), countdownText(now, next.atMillis))
        } else {
            stringResource(R.string.islamic_no_place)
        }
    LibraryNavigationRow(
        icon = Icons.Outlined.NightsStay,
        title = stringResource(R.string.islamic_title),
        subtitle = subtitle,
        onClick = onOpen,
        modifier = Modifier.widthIn(max = FlowMaxContentWidth).padding(horizontal = 16.dp),
    )
}
