/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.jobs

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.mawja.android.data.repository.StorageGuardWorker
import app.mawja.android.notification.SubscriptionCheckWorker
import app.mawja.android.notification.UpdateCheckWorker
import app.mawja.android.smartnotify.SmartNotifyWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Live list of the app's background jobs, with Run now and Stop. */
@HiltViewModel
class BackgroundJobsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val workManager = WorkManager.getInstance(context)

        val jobs: StateFlow<List<JobSummary>> =
            combine(MawjaJob.entries.map { job -> workManager.getWorkInfosForUniqueWorkFlow(job.uniqueName) }) { lists ->
                val now = System.currentTimeMillis()
                MawjaJob.entries.mapIndexed { index, job -> JobCenter.summarize(job, lists[index].map(::toRecord), now) }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        fun runNow(job: MawjaJob) {
            when (job) {
                MawjaJob.SUBSCRIPTION_CHECK -> SubscriptionCheckWorker.runImmediateCheck(context)
                MawjaJob.SMART_NOTIFY -> workManager.enqueue(OneTimeWorkRequestBuilder<SmartNotifyWorker>().build())
                MawjaJob.STORAGE_GUARD -> workManager.enqueue(OneTimeWorkRequestBuilder<StorageGuardWorker>().build())
                MawjaJob.UPDATE_CHECK -> workManager.enqueue(OneTimeWorkRequestBuilder<UpdateCheckWorker>().build())
                else -> Unit
            }
        }

        /** Cancels the job's pending and running work; periodic jobs are set up again by their owner on the next start. */
        fun stop(job: MawjaJob) {
            workManager.cancelUniqueWork(job.uniqueName)
        }

        private fun toRecord(info: WorkInfo): JobRecord =
            JobRecord(
                state =
                    when (info.state) {
                        WorkInfo.State.ENQUEUED -> JobRunState.ENQUEUED
                        WorkInfo.State.RUNNING -> JobRunState.RUNNING
                        WorkInfo.State.SUCCEEDED -> JobRunState.SUCCEEDED
                        WorkInfo.State.FAILED -> JobRunState.FAILED
                        WorkInfo.State.BLOCKED -> JobRunState.BLOCKED
                        WorkInfo.State.CANCELLED -> JobRunState.CANCELLED
                    },
                nextRunAt = info.nextScheduleTimeMillis,
            )
    }
