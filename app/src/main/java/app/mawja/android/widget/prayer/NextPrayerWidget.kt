/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.widget.prayer

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.mawja.android.R
import app.mawja.android.islamic.MawjaIslamic
import app.mawja.android.islamic.Prayer
import app.mawja.android.islamic.PrayerAlarms
import app.mawja.android.islamic.PrayerDay
import app.mawja.android.islamic.PrayerSchedule
import app.mawja.android.widget.core.action.WidgetDeepLink
import app.mawja.android.widget.core.component.WidgetEmptyState
import app.mawja.android.widget.core.component.setColorProvider
import app.mawja.android.widget.core.theme.FlowGlanceTheme
import app.mawja.android.widget.core.theme.WidgetDimens
import app.mawja.android.widget.core.theme.WidgetText
import app.mawja.android.widget.core.theme.widgetColorsFlow
import app.mawja.android.widget.core.theme.widgetSurface
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * The next prayer with a launcher-driven countdown; at 4x2 also the day's five times with the next
 * one highlighted. Redrawn only at the moments [NextPrayerModel.nextRefresh] names, never polled.
 */
class NextPrayerWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, FULL))

    override suspend fun providePreview(
        context: Context,
        widgetCategory: Int,
    ) {
        val colors = widgetColorsFlow(context).first()
        val now = System.currentTimeMillis()
        val state = previewState(now)
        provideContent { FlowGlanceTheme(colors) { NextPrayerContent(state, now) } }
    }

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val app = context.applicationContext
        NextPrayerRefresh.schedule(app)
        val colorsFlow = widgetColorsFlow(app)
        val initialColors = colorsFlow.first()
        provideContent {
            val colors by colorsFlow.collectAsState(initialColors)
            // Read so a tick written by the receiver recomposes a running session.
            currentState(TICK)
            val now = System.currentTimeMillis()
            val state = currentPrayerState(app, now)
            FlowGlanceTheme(colors) {
                NextPrayerContent(state, now)
            }
        }
    }

    internal companion object {
        val COMPACT = DpSize(110.dp, 48.dp)
        val FULL = DpSize(250.dp, 110.dp)
        val TICK = longPreferencesKey("next_prayer_tick")

        /** A plausible day for the picker preview: Dhuhr in 42 minutes. */
        private fun previewState(now: Long): NextPrayerState {
            val base = now + 42 * 60_000L
            val hour = 60 * 60_000L
            val times =
                mapOf(
                    Prayer.FAJR to base - 7 * hour,
                    Prayer.SUNRISE to base - 5 * hour,
                    Prayer.DHUHR to base,
                    Prayer.ASR to base + 3 * hour,
                    Prayer.MAGHRIB to base + 6 * hour,
                    Prayer.ISHA to base + 7 * hour,
                )
            val day =
                PrayerDay(LocalDate.now(), times)
            val tomorrow = day.copy(times = times.mapValues { it.value + 24 * hour })
            return NextPrayerModel.state(now, enabled = true, today = day, tomorrow = tomorrow)
        }
    }
}

/** Today's state from the saved prayer settings, on the device's clock and zone. */
internal fun currentPrayerState(
    context: Context,
    now: Long,
): NextPrayerState {
    val s = MawjaIslamic.settings.value
    if (!s.enabled) return NextPrayerState.Off
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    return NextPrayerModel.state(
        now = now,
        enabled = true,
        today = runCatching { MawjaIslamic.day(context, today, zone, s) }.getOrNull(),
        tomorrow = runCatching { MawjaIslamic.day(context, today.plusDays(1), zone, s) }.getOrNull(),
    )
}

@Composable
private fun NextPrayerContent(
    state: NextPrayerState,
    now: Long,
) {
    val context = LocalContext.current
    val open: Action = actionStartActivity(WidgetDeepLink.openRoute(context, WidgetDeepLink.ROUTE_PRAYER_SETTINGS))
    when (state) {
        NextPrayerState.Off ->
            WidgetEmptyState(
                icon = R.drawable.ic_widget_prayer,
                message = context.getString(R.string.widget_next_prayer_off),
                action = open,
                modifier = GlanceModifier.widgetSurface(),
            )

        NextPrayerState.NoLocation ->
            WidgetEmptyState(
                icon = R.drawable.ic_widget_prayer,
                message = context.getString(R.string.widget_next_prayer_no_place),
                action = open,
                modifier = GlanceModifier.widgetSurface(),
            )

        is NextPrayerState.Upcoming, is NextPrayerState.Now -> PrayerTimesContent(state, now, open)
    }
}

@Composable
private fun PrayerTimesContent(
    state: NextPrayerState,
    now: Long,
    open: Action,
) {
    val context = LocalContext.current
    val full = LocalSize.current.height >= NextPrayerWidget.FULL.height && LocalSize.current.width >= NextPrayerWidget.FULL.width
    val slots =
        when (state) {
            is NextPrayerState.Upcoming -> state.slots
            is NextPrayerState.Now -> state.slots
            else -> emptyList()
        }
    val highlighted = state is NextPrayerState.Now || (state is NextPrayerState.Upcoming && state.soon)
    val surface = if (highlighted) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.widgetBackground
    val onSurface = if (highlighted) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSurface
    Column(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .widgetSurface(surface)
                .padding(WidgetDimens.ContentPadding)
                .clickable(open)
                .semantics { contentDescription = describe(context, state, now) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.Start,
    ) {
        Headline(state, onSurface)
        if (full && slots.isNotEmpty()) {
            Spacer(GlanceModifier.height(WidgetDimens.ItemGap))
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                slots.forEachIndexed { index, slot ->
                    if (index > 0) Spacer(GlanceModifier.width(WidgetDimens.SmallGap))
                    SlotCell(slot, GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

@Composable
private fun Headline(
    state: NextPrayerState,
    onSurface: androidx.glance.unit.ColorProvider,
) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_prayer),
            contentDescription = null,
            modifier = GlanceModifier.size(20.dp),
            colorFilter = ColorFilter.tint(onSurface),
        )
        Spacer(GlanceModifier.width(WidgetDimens.ItemGap))
        Column {
            when (state) {
                is NextPrayerState.Upcoming -> {
                    val name = context.getString(PrayerAlarms.prayerName(state.next.prayer))
                    val label = if (state.soon) context.getString(R.string.widget_next_prayer_soon, name) else name
                    Text(text = label, style = WidgetText.titleSmall(onSurface), maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CountdownChronometer(state.next.atMillis, WidgetText.titleMedium(onSurface))
                        Spacer(GlanceModifier.width(WidgetDimens.SmallGap))
                        Text(
                            text = context.getString(R.string.widget_next_prayer_at, formatTime(context, state.next.atMillis)),
                            style = WidgetText.bodySmall(onSurface),
                            maxLines = 1,
                        )
                    }
                }

                is NextPrayerState.Now -> {
                    val name = context.getString(PrayerAlarms.prayerName(state.prayer))
                    Text(text = context.getString(R.string.widget_next_prayer_now, name), style = WidgetText.titleMedium(onSurface), maxLines = 1)
                    state.next?.let { next ->
                        Text(
                            text =
                                context.getString(
                                    R.string.islamic_time_cd,
                                    context.getString(PrayerAlarms.prayerName(next.prayer)),
                                    formatTime(context, next.atMillis),
                                ),
                            style = WidgetText.bodySmall(onSurface),
                            maxLines = 1,
                        )
                    }
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun SlotCell(
    slot: PrayerSlot,
    modifier: GlanceModifier,
) {
    val context = LocalContext.current
    val fg = if (slot.isNext) GlanceTheme.colors.onSecondaryContainer else GlanceTheme.colors.onSurfaceVariant
    val base = modifier.padding(vertical = WidgetDimens.SmallGap)
    Column(
        modifier =
            if (slot.isNext) {
                base.background(GlanceTheme.colors.secondaryContainer).cornerRadius(WidgetDimens.InnerCornerFallback)
            } else {
                base
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = context.getString(PrayerAlarms.prayerName(slot.prayer)),
            style = WidgetText.centered(WidgetText.labelSmall(fg)),
            maxLines = 1,
        )
        Text(
            text = formatTime(context, slot.atMillis),
            style = WidgetText.centered(WidgetText.labelMedium(fg)),
            maxLines = 1,
        )
    }
}

/**
 * Counts down to [atMillis] in a platform Chronometer the launcher advances each second. Past
 * zero (an inexact refresh can land a few minutes late) it would count up again, so the
 * widget redraws into the "now" state at the prayer time.
 */
@Composable
private fun CountdownChronometer(
    atMillis: Long,
    style: TextStyle,
) {
    val context = LocalContext.current
    val base = SystemClock.elapsedRealtime() + (atMillis - System.currentTimeMillis())
    val views =
        RemoteViews(context.packageName, R.layout.widget_chronometer).apply {
            setChronometer(R.id.widget_chronometer, base, null, true)
            setChronometerCountDown(R.id.widget_chronometer, true)
            style.fontSize?.let { setTextViewTextSize(R.id.widget_chronometer, TypedValue.COMPLEX_UNIT_SP, it.value) }
            style.color?.let { color ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setColorProvider(R.id.widget_chronometer, "setTextColor", color, context)
                } else {
                    setTextColor(R.id.widget_chronometer, color.getColor(context).toArgb())
                }
            }
        }
    // Sized to its text: unsized it fills the row and pushes the time out of sight.
    AndroidRemoteViews(views, GlanceModifier.width(ChronometerWidth))
}

private val ChronometerWidth = 72.dp

internal fun formatTime(
    context: Context,
    atMillis: Long,
): String = android.text.format.DateFormat.getTimeFormat(context).format(Date(atMillis))

/** One sentence for TalkBack, since the countdown digits change every second. */
private fun describe(
    context: Context,
    state: NextPrayerState,
    now: Long,
): String =
    when (state) {
        is NextPrayerState.Upcoming -> {
            val (h, m) = PrayerSchedule.countdown(now, state.next.atMillis)
            val left =
                if (h > 0) context.getString(R.string.islamic_countdown_hm, h, m) else context.getString(R.string.islamic_countdown_m, m)
            context.getString(
                R.string.widget_next_prayer_cd,
                context.getString(PrayerAlarms.prayerName(state.next.prayer)),
                formatTime(context, state.next.atMillis),
                left,
            )
        }

        is NextPrayerState.Now ->
            context.getString(
                R.string.widget_next_prayer_now_cd,
                context.getString(PrayerAlarms.prayerName(state.prayer)),
                formatTime(context, state.atMillis),
            )

        NextPrayerState.Off -> context.getString(R.string.widget_next_prayer_off)
        NextPrayerState.NoLocation -> context.getString(R.string.widget_next_prayer_no_place)
    }
