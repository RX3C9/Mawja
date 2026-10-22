/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.cloudsync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.mawja.android.battery.BatterySaver
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * Periodic cloud sync (every [SyncLimits.PERIOD_HOURS] h, network connected, battery not low).
 * Skips its run while the deep battery saver is on; a manual run (Job center "Run now") does not.
 */
class CloudSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun cloudSync(): CloudSyncRepository
    }

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if (!CloudSyncGate.shouldRun(manual, BatterySaver.isActive(applicationContext))) return Result.success()
        val repo = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).cloudSync()
        if (!repo.awaitReady()) return Result.success()
        return when (repo.sync(manual)) {
            CloudSyncOutcome.OFFLINE, CloudSyncOutcome.FAILED ->
                if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
            else -> Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "mawja_cloud_sync"
        private const val KEY_MANUAL = "manual"
        private const val MAX_RETRIES = 3

        private fun constraints(): Constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<CloudSyncWorker>(SyncLimits.PERIOD_HOURS, TimeUnit.HOURS)
                    .setConstraints(constraints())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                    .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /** One manual run now (needs a network), e.g. from the Background jobs page. */
        fun runOnce(context: Context) {
            val request =
                OneTimeWorkRequestBuilder<CloudSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(workDataOf(KEY_MANUAL to true))
                    .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
