/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.data.repository

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * Mawja storage guard (PLAN_V2 #57): when the phone itself runs low on space, the app's caches give
 * way first, cheapest to refill first: network/lyrics, then images, then videos, then songs.
 * Downloads are never touched.
 */
object StorageGuard {
    private const val MB = 1024L * 1024L

    /** Clearing order: what is cheapest to fetch again goes first. */
    val ORDER = listOf(MediaCacheType.OTHER, MediaCacheType.ARTWORK, MediaCacheType.VIDEOS, MediaCacheType.SONGS)

    /** Free space below which the guard acts: 3% of the disk, at least 500 MB, at most 2 GB. */
    fun lowThreshold(totalBytes: Long): Long = (totalBytes * 3 / 100).coerceIn(500 * MB, 2048 * MB)

    /**
     * Which caches to clear, in [ORDER], so free space climbs back to twice the low threshold — or
     * every non-empty cache when even that is not enough. Empty when there is room.
     */
    fun plan(
        usage: MediaCacheUsage,
        freeBytes: Long,
        totalBytes: Long,
    ): List<MediaCacheType> {
        val low = lowThreshold(totalBytes)
        if (freeBytes >= low) return emptyList()
        val target = low * 2
        var projected = freeBytes
        val chosen = mutableListOf<MediaCacheType>()
        for (type in ORDER) {
            if (projected >= target) break
            val size = usage.of(type)
            if (size <= 0L) continue
            chosen += type
            projected += size
        }
        return chosen
    }

    private const val PREFS = "mawja_storage_guard"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_AT = "last_at"
    private const val KEY_LAST_FREED = "last_freed"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
        if (enabled) StorageGuardWorker.schedule(context) else StorageGuardWorker.cancel(context)
    }

    /** When the guard last cleared something, and how many bytes; null if never. */
    fun lastCleanup(context: Context): Pair<Long, Long>? {
        val at = prefs(context).getLong(KEY_LAST_AT, 0L)
        return if (at == 0L) null else at to prefs(context).getLong(KEY_LAST_FREED, 0L)
    }

    internal fun recordCleanup(
        context: Context,
        at: Long,
        freed: Long,
    ) = prefs(context).edit {
        putLong(KEY_LAST_AT, at)
        putLong(KEY_LAST_FREED, freed)
    }

    /** Free and total bytes of the volume that holds the app's data and caches. */
    fun deviceSpace(context: Context): Pair<Long, Long> =
        runCatching {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBytes to stat.totalBytes
        }.getOrDefault(0L to 0L)
}

/** Checks free space about twice a day and runs [StorageGuard] when the phone is low. */
class StorageGuardWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun mediaCacheRepository(): MediaCacheRepository
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!StorageGuard.isEnabled(context)) return Result.success()
        val (free, total) = StorageGuard.deviceSpace(context)
        if (total <= 0L || free >= StorageGuard.lowThreshold(total)) return Result.success()
        runCatching {
            val repository = EntryPointAccessors.fromApplication(context, Dependencies::class.java).mediaCacheRepository()
            val usage = repository.usage()
            val plan = StorageGuard.plan(usage, free, total)
            plan.forEach { repository.clear(it) }
            if (plan.isNotEmpty()) StorageGuard.recordCleanup(context, System.currentTimeMillis(), plan.sumOf { usage.of(it) })
        }.onFailure { Log.w(TAG, "Storage guard failed", it) }
        return Result.success()
    }

    companion object {
        private const val TAG = "StorageGuard"
        private const val WORK_NAME = "mawja_storage_guard"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<StorageGuardWorker>(12, TimeUnit.HOURS, 2, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
