/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.islamic

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.mawja.android.MainActivity
import app.mawja.android.R
import app.mawja.android.player.EnhancedMusicPlayerManager
import app.mawja.android.player.EnhancedPlayerManager
import java.time.LocalDate
import java.time.ZoneId

/**
 * "Pause at adhan": one inexact, idle-allowed alarm for the next prayer at a time (no exact-alarm
 * permission). Doze may deliver it a few minutes late; a late alarm still notifies, and pauses only
 * within [PrayerSchedule.CURRENT_WINDOW_MS]. Re-armed after each fire, at app start, after a
 * reboot and when the clock or time zone changes.
 */
object PrayerAlarms {
    const val CHANNEL = "mawja_prayer_channel"
    private const val ACTION_FIRE = "app.mawja.android.islamic.PRAYER_ALARM"
    private const val EXTRA_PRAYER = "prayer"
    private const val EXTRA_AT = "at"
    private const val REQUEST = 47_147
    private const val NOTIFICATION_ID = 47_148

    fun reschedule(context: Context) {
        val s = MawjaIslamic.settings.value
        if (!s.enabled || s.adhan == AdhanMode.OFF) {
            cancel(context)
            return
        }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val todayTimes = MawjaIslamic.day(context, today, zone, s)
        val tomorrowTimes = MawjaIslamic.day(context, today.plusDays(1), zone, s)
        if (todayTimes == null || tomorrowTimes == null) {
            cancel(context)
            return
        }
        val next = PrayerSchedule.next(System.currentTimeMillis(), todayTimes, tomorrowTimes) ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atMillis, pending(context, next)) }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pending(context, null)) }
    }

    private fun pending(
        context: Context,
        next: UpcomingPrayer?,
    ): PendingIntent {
        val intent =
            Intent(context, PrayerAlarmReceiver::class.java).setAction(ACTION_FIRE).apply {
                next?.let {
                    putExtra(EXTRA_PRAYER, it.prayer.key)
                    putExtra(EXTRA_AT, it.atMillis)
                }
            }
        return PendingIntent.getBroadcast(context, REQUEST, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    internal fun onFire(
        context: Context,
        intent: Intent,
    ) {
        val s = MawjaIslamic.settings.value
        val prayer = Prayer.entries.firstOrNull { it.key == intent.getStringExtra(EXTRA_PRAYER) }
        val at = intent.getLongExtra(EXTRA_AT, 0L)
        if (s.enabled && s.adhan != AdhanMode.OFF && prayer != null && prayer.isPrayer && at > 0L) {
            val now = System.currentTimeMillis()
            if (PrayerSchedule.isTimely(now, at)) {
                val paused = s.adhan == AdhanMode.PAUSE && pausePlayback()
                notify(context, prayer, paused)
            }
        }
        reschedule(context)
    }

    /** Pauses whatever Mawja is playing in this process (receivers run on the main thread, as the players need). */
    private fun pausePlayback(): Boolean {
        var paused = false
        runCatching {
            val video = EnhancedPlayerManager.getInstance()
            if (video.isPlaying()) {
                video.pause()
                paused = true
            }
        }
        runCatching {
            if (EnhancedMusicPlayerManager.isPlaying()) {
                EnhancedMusicPlayerManager.pause()
                paused = true
            }
        }
        return paused
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL) != null) return
        // The system's default notification sound; Mawja ships no adhan recording.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.islamic_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.islamic_channel_description)
            },
        )
    }

    @SuppressLint("MissingPermission")
    private fun notify(
        context: Context,
        prayer: Prayer,
        paused: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val name = context.getString(prayerName(prayer))
        val title = context.getString(R.string.islamic_notification_title, name)
        val text = context.getString(if (paused) R.string.islamic_notification_paused else R.string.islamic_notification_text)
        val open =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val contentIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setTimeoutAfter(PrayerSchedule.CURRENT_WINDOW_MS * 3)
                .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    fun prayerName(prayer: Prayer): Int =
        when (prayer) {
            Prayer.FAJR -> R.string.islamic_fajr
            Prayer.SUNRISE -> R.string.islamic_sunrise
            Prayer.DHUHR -> R.string.islamic_dhuhr
            Prayer.ASR -> R.string.islamic_asr
            Prayer.MAGHRIB -> R.string.islamic_maghrib
            Prayer.ISHA -> R.string.islamic_isha
        }
}

/** Fires at prayer time, and re-arms the alarm after a reboot or a clock / time-zone change. */
class PrayerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val app = context.applicationContext
        MawjaIslamic.start(app)
        if (!MawjaIslamic.settings.value.enabled) return
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> PrayerAlarms.reschedule(app)
            else -> PrayerAlarms.onFire(app, intent)
        }
    }
}
