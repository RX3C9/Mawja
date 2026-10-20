/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.jobs

/** What a background job is doing right now, reduced from its WorkManager records. */
enum class JobStatus { RUNNING, SCHEDULED, WAITING, DONE, FAILED, STOPPED, NOT_SCHEDULED }

/** Platform-free copy of WorkManager's WorkInfo.State, so the rules are unit-testable. */
enum class JobRunState { ENQUEUED, RUNNING, SUCCEEDED, FAILED, BLOCKED, CANCELLED }

/** One record of a unique work: its state and, for a pending periodic run, when it is due (0 = unknown). */
data class JobRecord(
    val state: JobRunState,
    val nextRunAt: Long = 0L,
)

/** The jobs the centre lists. [uniqueName] is the WorkManager unique-work name the owner enqueues. */
enum class MawjaJob(
    val uniqueName: String,
    val canRunNow: Boolean,
) {
    SUBSCRIPTION_CHECK("subscription_check_work_v2", true),
    SMART_NOTIFY("mawja_smart_notify", true),
    STORAGE_GUARD("mawja_storage_guard", true),
    UPDATE_CHECK("update_check_work", true),
    AUTO_BACKUP("auto_backup_work", false),
    SCROBBLE("flow_scrobble_flush", false),
    DOWNLOAD_QUEUE("flow_download_queue", false),
    DOWNLOAD_RETAG("flow_download_retag", false),
}

/** A job's row on the Background jobs page. */
data class JobSummary(
    val job: MawjaJob,
    val status: JobStatus,
    /** Next scheduled run, or null when none is pending or WorkManager gives no time. */
    val nextRunAt: Long?,
)

object JobCenter {
    /**
     * Running beats everything, then a pending run (scheduled or waiting on constraints/another job),
     * then the outcome of the last finished run. No records at all means the job is not scheduled.
     */
    fun status(records: List<JobRecord>): JobStatus {
        if (records.isEmpty()) return JobStatus.NOT_SCHEDULED
        val states = records.map { it.state }.toSet()
        return when {
            JobRunState.RUNNING in states -> JobStatus.RUNNING
            JobRunState.ENQUEUED in states -> JobStatus.SCHEDULED
            JobRunState.BLOCKED in states -> JobStatus.WAITING
            JobRunState.FAILED in states -> JobStatus.FAILED
            JobRunState.SUCCEEDED in states -> JobStatus.DONE
            else -> JobStatus.STOPPED
        }
    }

    fun summarize(
        job: MawjaJob,
        records: List<JobRecord>,
        now: Long,
    ): JobSummary {
        val next =
            records
                .filter { it.state == JobRunState.ENQUEUED && it.nextRunAt > 0L && it.nextRunAt != Long.MAX_VALUE }
                .minOfOrNull { it.nextRunAt }
                ?.coerceAtLeast(now)
        return JobSummary(job, status(records), next)
    }

    /** Minutes until [at] rounded up, at least 0 — "in 3 h 5 min" style copy is built from this. */
    fun minutesUntil(
        at: Long,
        now: Long,
    ): Long = ((at - now).coerceAtLeast(0L) + 59_999L) / 60_000L
}
