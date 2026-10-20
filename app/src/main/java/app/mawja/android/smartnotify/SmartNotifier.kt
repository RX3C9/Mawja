/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.smartnotify

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.mawja.android.MainActivity
import app.mawja.android.R

/** Posts adaptive notifications on their own channel, so people can silence them from system settings. */
object SmartNotifier {
    const val CHANNEL_SMART = "mawja_smart_channel"
    const val EXTRA_SMART_HASH = "app.mawja.android.extra.SMART_NOTIFY_HASH"
    const val ACTION_DISMISSED = "app.mawja.android.action.SMART_NOTIFY_DISMISSED"
    private const val NOTIFICATION_BASE = 91000

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_SMART) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SMART,
                context.getString(R.string.smart_notify_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.smart_notify_channel_description)
                setShowBadge(true)
            },
        )
    }

    /** Posts [candidate]; the caller has already checked permission and the engine's decision. */
    @SuppressLint("MissingPermission")
    fun post(
        context: Context,
        candidate: SmartCandidate,
        hash: String,
    ) {
        ensureChannel(context)
        val id = NOTIFICATION_BASE + candidate.kind.ordinal
        val open =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_SMART_HASH, hash)
                candidate.videoId?.let { putExtra("video_id", it) }
            }
        val contentIntent =
            PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismissed =
            Intent(context, SmartNotifyFeedbackReceiver::class.java).apply {
                action = ACTION_DISMISSED
                putExtra(EXTRA_SMART_HASH, hash)
            }
        val deleteIntent =
            PendingIntent.getBroadcast(context, id, dismissed, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_SMART)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(candidate.title)
                .setContentText(candidate.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(candidate.text))
                .setContentIntent(contentIntent)
                .setDeleteIntent(deleteIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
