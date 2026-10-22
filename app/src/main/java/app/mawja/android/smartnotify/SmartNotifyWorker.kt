/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.smartnotify

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.mawja.android.R
import app.mawja.android.data.local.ViewHistory
import app.mawja.android.data.subscriptions.SubscriptionFeedRepository
import app.mawja.android.notification.NotificationHelper
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Wakes about once an hour, reads only what is already on the device (watch history and the stored
 * subscription feed), and posts at most one adaptive notification when the engine says now is a
 * good moment. No network, no server.
 */
class SmartNotifyWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun subscriptionFeedRepository(): SubscriptionFeedRepository

        fun remoteConfig(): app.mawja.android.remote.RemoteConfigRepository
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        val store = SmartNotifyStore(context)
        val config = store.config.first()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        store.update { SmartNotifyEngine.housekeep(it, config, now) }
        if (!config.enabled || !NotificationHelper.hasNotificationPermission(context)) return Result.success()
        // config/app features.smartNotifications: switched off remotely, nothing is posted.
        val remote = EntryPointAccessors.fromApplication(context, Dependencies::class.java).remoteConfig()
        if (!remote.isEnabled("smartNotifications")) return Result.success()

        val candidates =
            runCatching {
                SmartCandidates.build(
                    history = readHistory(context),
                    feed = readFeed(context),
                    now = now,
                    zone = zone,
                    texts = ResourceTexts(context),
                )
            }.onFailure { Log.w(TAG, "Could not build candidates", it) }
                .getOrDefault(emptyList())
        if (candidates.isEmpty()) return Result.success()

        val state = store.state()
        val chosen = SmartNotifyEngine.pick(candidates, state, config, now, zone) ?: return Result.success()
        val hash = SmartNotifyEngine.contentHash(chosen.kind, chosen.contentKey)
        store.update { SmartNotifyEngine.recordSent(it, chosen, now) }
        SmartNotifier.post(context, chosen, hash)
        return Result.success()
    }

    private suspend fun readHistory(context: Context): List<HistoryItem> =
        ViewHistory
            .getInstance(context)
            .getRecentLibraryHistory(HISTORY_LIMIT)
            .first()
            .filter { !it.isShort && !it.isLocal }
            .map {
                HistoryItem(
                    videoId = it.videoId,
                    title = it.title,
                    channelName = it.channelName,
                    channelId = it.channelId,
                    positionMillis = it.position,
                    durationMillis = it.duration,
                    watchedAt = it.timestamp,
                    isMusic = it.isMusic,
                )
            }

    private suspend fun readFeed(context: Context): List<FeedItem> =
        runCatching {
            EntryPointAccessors
                .fromApplication(context, Dependencies::class.java)
                .subscriptionFeedRepository()
                .observeFeed()
                .first()
                .filter { it.timestampIsExact && !it.isUpcoming && !it.isLive }
                .map { FeedItem(it.id, it.title, it.channelName, it.channelId, it.timestamp, it.isShort) }
        }.getOrDefault(emptyList())

    private class ResourceTexts(
        private val context: Context,
    ) : SmartTexts {
        override fun continueTitle() = context.getString(R.string.smart_notify_continue_title)

        override fun continueText(videoTitle: String) = context.getString(R.string.smart_notify_continue_text, videoTitle)

        override fun channelTitle(channelName: String) = context.getString(R.string.smart_notify_channel_title, channelName)

        override fun recapTitle() = context.getString(R.string.smart_notify_recap_title)

        override fun recapText(
            videos: Int,
            songs: Int,
            topChannel: String?,
        ): String {
            val counts = context.getString(R.string.smart_notify_recap_counts, videos, songs)
            return if (topChannel == null) counts else context.getString(R.string.smart_notify_recap_with_top, counts, topChannel)
        }

        override fun djTitle() = context.getString(R.string.smart_notify_dj_title)

        override fun djText(artist: String) = context.getString(R.string.smart_notify_dj_text, artist)
    }

    companion object {
        private const val TAG = "SmartNotifyWorker"
        const val WORK_NAME = "mawja_smart_notify"
        private const val HISTORY_LIMIT = 400

        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<SmartNotifyWorker>(1, TimeUnit.HOURS, 20, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
