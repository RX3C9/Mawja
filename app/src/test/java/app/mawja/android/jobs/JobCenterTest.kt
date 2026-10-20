/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JobCenterTest {
    private fun r(
        state: JobRunState,
        next: Long = 0L,
    ) = JobRecord(state, next)

    @Test
    fun `status precedence`() {
        assertEquals(JobStatus.NOT_SCHEDULED, JobCenter.status(emptyList()))
        assertEquals(JobStatus.RUNNING, JobCenter.status(listOf(r(JobRunState.SUCCEEDED), r(JobRunState.RUNNING), r(JobRunState.ENQUEUED))))
        assertEquals(JobStatus.SCHEDULED, JobCenter.status(listOf(r(JobRunState.FAILED), r(JobRunState.ENQUEUED))))
        assertEquals(JobStatus.WAITING, JobCenter.status(listOf(r(JobRunState.BLOCKED))))
        assertEquals(JobStatus.FAILED, JobCenter.status(listOf(r(JobRunState.SUCCEEDED), r(JobRunState.FAILED))))
        assertEquals(JobStatus.DONE, JobCenter.status(listOf(r(JobRunState.SUCCEEDED), r(JobRunState.CANCELLED))))
        assertEquals(JobStatus.STOPPED, JobCenter.status(listOf(r(JobRunState.CANCELLED))))
    }

    @Test
    fun `next run is the earliest pending time, never in the past`() {
        val now = 1_000_000L
        val s = JobCenter.summarize(MawjaJob.SMART_NOTIFY, listOf(r(JobRunState.ENQUEUED, now + 90_000), r(JobRunState.ENQUEUED, now + 30_000)), now)
        assertEquals(now + 30_000, s.nextRunAt)
        assertEquals(now, JobCenter.summarize(MawjaJob.SMART_NOTIFY, listOf(r(JobRunState.ENQUEUED, now - 5)), now).nextRunAt)
        assertNull(JobCenter.summarize(MawjaJob.SMART_NOTIFY, listOf(r(JobRunState.ENQUEUED, Long.MAX_VALUE)), now).nextRunAt)
        assertNull(JobCenter.summarize(MawjaJob.SMART_NOTIFY, listOf(r(JobRunState.RUNNING, now + 10)), now).nextRunAt)
    }

    @Test
    fun `minutes round up`() {
        assertEquals(0L, JobCenter.minutesUntil(5, 10))
        assertEquals(1L, JobCenter.minutesUntil(11, 10))
        assertEquals(1L, JobCenter.minutesUntil(60_000, 0))
        assertEquals(2L, JobCenter.minutesUntil(60_001, 0))
    }

    @Test
    fun `unique names are distinct`() {
        assertEquals(MawjaJob.entries.size, MawjaJob.entries.map { it.uniqueName }.toSet().size)
    }
}
