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
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.mawja.android.MainActivity
import app.mawja.android.R
import app.mawja.android.notification.NotificationHelper
import app.mawja.remote.Announcement
import app.mawja.remote.AnnouncementFilter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Which admin "push" announcements this phone still has to post. Pure, for tests. */
object AdminPush {
    const val MAX_PER_RUN = 3

    fun pending(
        all: List<Announcement>,
        now: Long,
        tierId: String,
        language: String,
        alreadyPosted: Set<String>,
    ): List<Announcement> =
        AnnouncementFilter
            .visible(all, now, tierId, language, alreadyPosted)
            .filter { it.kind == Announcement.KIND_PUSH }
            .take(MAX_PER_RUN)

    /** Keeps only ids that still exist, so the remembered set never grows without bound. */
    fun prune(
        posted: Set<String>,
        all: List<Announcement>,
    ): Set<String> {
        val ids = all.mapTo(HashSet()) { it.id }
        return posted.filterTo(HashSet()) { it in ids }
    }
}

/**
 * Free stand-in for FCM (Spark plan, no Cloud Functions): about once an hour, with a network, reads
 * the public announcements and posts each new admin "push" aimed at this user's tier and language
 * as a real system notification. Each one is posted once per install.
 */
class AdminPushWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun remoteConfig(): app.mawja.android.remote.RemoteConfigRepository

        fun entitlements(): app.mawja.android.account.EntitlementsRepository
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        val deps = EntryPointAccessors.fromApplication(context, Dependencies::class.java)
        val remote = deps.remoteConfig()
        if (!remote.enabled || !NotificationHelper.hasNotificationPermission(context)) return Result.success()
        runCatching { remote.refresh() }
        if (!remote.isEnabled("announcements")) return Result.success()
        val all = remote.announcements.first()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val posted = prefs.getStringSet(KEY_POSTED, emptySet()).orEmpty()
        val tier = runCatching { deps.entitlements().entitlements.value.tier.id }.getOrDefault("free")
        val fresh = AdminPush.pending(all, System.currentTimeMillis(), tier, Locale.getDefault().language, posted)
        fresh.forEach { post(context, it) }
        prefs.edit().putStringSet(KEY_POSTED, AdminPush.prune(posted, all) + fresh.map { it.id }).apply()
        return Result.success()
    }

    @SuppressLint("MissingPermission")
    private fun post(
        context: Context,
        a: Announcement,
    ) {
        ensureChannel(context)
        val id = NOTIFICATION_BASE + (a.id.hashCode() and 0xFFF)
        val open =
            if (a.hasCta && a.ctaUrl.startsWith("https://")) {
                Intent(Intent.ACTION_VIEW, Uri.parse(a.ctaUrl))
            } else {
                Intent(context, MainActivity::class.java)
            }.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pending = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_NEWS)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setColor(0xFF19C3B1.toInt())
                .setContentTitle(a.title)
                .setContentText(a.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(a.body).setSummaryText(context.getString(R.string.admin_push_summary)))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (a.hasCta && a.ctaUrl.startsWith("https://")) builder.addAction(0, a.ctaLabel, pending)
        runCatching { NotificationManagerCompat.from(context).notify(id, builder.build()) }
    }

    companion object {
        const val CHANNEL_NEWS = "mawja_news_channel"
        private const val WORK = "mawja_admin_push"
        private const val PREFS = "mawja_admin_push"
        private const val KEY_POSTED = "posted"
        private const val NOTIFICATION_BASE = 93000

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_NEWS) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_NEWS, context.getString(R.string.admin_push_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.admin_push_channel_desc)
                },
            )
        }

        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<AdminPushWorker>(1, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }
    }
}
