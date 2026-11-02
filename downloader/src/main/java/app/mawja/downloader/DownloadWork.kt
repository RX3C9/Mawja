/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Keeps unfinished downloads going when no screen is open: WorkManager survives the app being
 * closed and the phone restarting, waits for the network (or Wi-Fi), and for "tonight" items starts
 * at their time. While it runs, it holds a dataSync foreground notification.
 */
class DownloadWork(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        running = true
        try {
            return keep()
        } finally {
            running = false
        }
    }

    private suspend fun keep(): Result {
        DownloadEngine.init(applicationContext)
        runCatching { setForeground(foregroundInfo()) }
        DownloadEngine.pump()
        var idle = 0
        while (idle < 3) {
            delay(5_000)
            DownloadEngine.pump()
            if (DownloadEngine.activeCount() == 0) idle++ else idle = 0
        }
        // Work still waiting (Wi-Fi, tonight, network back later): come back for it.
        if (DownloadEngine.hasPendingWork()) schedule(applicationContext, fromWorker = true)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, applicationContext.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW))
        val n: Notification =
            NotificationCompat
                .Builder(applicationContext, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(applicationContext.getString(R.string.work_resuming))
                .setOngoing(true)
                .setSilent(true)
                .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val NAME = "mawja-downloads"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 7002

        @Volatile
        private var running = false

        /** (Re)schedules the keeper for whatever is still unfinished; a running keeper reschedules itself. */
        fun schedule(
            context: Context,
            fromWorker: Boolean = false,
        ) {
            if (running && !fromWorker) return
            DownloadEngine.init(context)
            if (!DownloadEngine.hasPendingWork()) return
            val wait = (DownloadEngine.nextScheduledAt() - System.currentTimeMillis()).coerceAtLeast(0)
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(if (DownloadEngine.needsOnlyUnmetered()) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build()
            val request =
                OneTimeWorkRequestBuilder<DownloadWork>()
                    .setConstraints(constraints)
                    .apply { if (wait > 0) setInitialDelay(wait, TimeUnit.MILLISECONDS) }
                    .build()
            runCatching { WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request) }
        }
    }
}
