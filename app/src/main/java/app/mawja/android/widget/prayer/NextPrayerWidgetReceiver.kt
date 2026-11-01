/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.widget.prayer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.state.updateAppWidgetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wakes the Next Prayer widget only when its picture changes: 15 minutes before a prayer, at it,
 * and when its "now" window ends. One inexact, non-wakeup alarm (no exact-alarm permission); a
 * sleeping phone simply redraws when it next wakes.
 */
object NextPrayerRefresh {
    internal const val ACTION_TICK = "app.mawja.android.widget.prayer.TICK"
    private const val REQUEST = 47_164
    private const val WINDOW_MS = 10 * 60_000L

    /** Asks every placed Next Prayer widget to redraw now, e.g. after a prayer setting changed. */
    fun request(context: Context) {
        val app = context.applicationContext
        runCatching { app.sendBroadcast(Intent(app, NextPrayerWidgetReceiver::class.java).setAction(ACTION_TICK)) }
    }

    /** Arms the next redraw from the current settings, or cancels it when nothing will change. */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val now = System.currentTimeMillis()
        val at = NextPrayerModel.nextRefresh(now, currentPrayerState(app, now))
        runCatching {
            if (at == null) {
                am.cancel(pending(app))
            } else {
                am.setWindow(AlarmManager.RTC, at, WINDOW_MS, pending(app))
            }
        }.onFailure { Log.w(TAG, "Could not arm the next prayer refresh", it) }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pending(app)) }
    }

    private fun pending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST,
            Intent(context, NextPrayerWidgetReceiver::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private const val TAG = "NextPrayerRefresh"
}

class NextPrayerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextPrayerWidget()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        super.onReceive(context, intent)
        when (intent.action) {
            NextPrayerRefresh.ACTION_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> refresh(context)
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        NextPrayerRefresh.cancel(context)
    }

    private fun refresh(context: Context) {
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val ids = GlanceAppWidgetManager(app).getGlanceIds(NextPrayerWidget::class.java)
                if (ids.isEmpty()) {
                    NextPrayerRefresh.cancel(app)
                    return@launch
                }
                ids.forEach { id ->
                    // A new value in the widget's own state recomposes a session that is still running.
                    updateAppWidgetState(app, id) { prefs -> prefs[NextPrayerWidget.TICK] = now }
                    glanceAppWidget.update(app, id)
                }
                NextPrayerRefresh.schedule(app)
            } catch (t: Throwable) {
                Log.w("NextPrayerWidget", "Refresh failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
