/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.jobs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.android.R
import app.mawja.android.ui.components.settings.SettingsPage
import kotlinx.coroutines.launch

/** Mawja background job centre (PLAN_V2 #58): every background job, what it is doing, Run now and Stop. */
@Composable
internal fun BackgroundJobsScreen(
    onBack: (() -> Unit)?,
    viewModel: BackgroundJobsViewModel = hiltViewModel(),
) {
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val started = stringResource(R.string.jobs_started)
    val stopped = stringResource(R.string.jobs_stopped)

    SettingsPage(
        title = stringResource(R.string.jobs_title),
        subtitle = stringResource(R.string.jobs_subtitle),
        onBack = onBack,
        snackbarHostState = snackbar,
    ) {
        jobs.forEach { summary ->
            item("jobs.${summary.job.name}") {
                JobCard(
                    summary = summary,
                    onRun = {
                        viewModel.runNow(summary.job)
                        scope.launch { snackbar.showSnackbar(started) }
                    },
                    onStop = {
                        viewModel.stop(summary.job)
                        scope.launch { snackbar.showSnackbar(stopped) }
                    },
                )
            }
        }
        item("jobs.note") {
            Text(
                stringResource(R.string.jobs_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun JobCard(
    summary: JobSummary,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    val statusText = stringResource(statusRes(summary.status))
    val next =
        summary.nextRunAt?.let { at ->
            val minutes = JobCenter.minutesUntil(at, System.currentTimeMillis())
            if (minutes >= 60) {
                stringResource(R.string.jobs_next_hours, (minutes / 60).toInt(), (minutes % 60).toInt())
            } else {
                stringResource(R.string.jobs_next_minutes, minutes.toInt())
            }
        }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(titleRes(summary.job)), style = MaterialTheme.typography.titleMedium)
            Text(
                if (next != null) "$statusText · $next" else statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (summary.status == JobStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                val active = summary.status == JobStatus.RUNNING || summary.status == JobStatus.SCHEDULED || summary.status == JobStatus.WAITING
                if (active) TextButton(onClick = onStop) { Text(stringResource(R.string.jobs_stop)) }
                if (summary.job.canRunNow && summary.status != JobStatus.RUNNING) {
                    TextButton(onClick = onRun) { Text(stringResource(R.string.jobs_run_now)) }
                }
            }
        }
    }
}

private fun statusRes(status: JobStatus): Int =
    when (status) {
        JobStatus.RUNNING -> R.string.jobs_status_running
        JobStatus.SCHEDULED -> R.string.jobs_status_scheduled
        JobStatus.WAITING -> R.string.jobs_status_waiting
        JobStatus.DONE -> R.string.jobs_status_done
        JobStatus.FAILED -> R.string.jobs_status_failed
        JobStatus.STOPPED -> R.string.jobs_status_stopped
        JobStatus.NOT_SCHEDULED -> R.string.jobs_status_off
    }

private fun titleRes(job: MawjaJob): Int =
    when (job) {
        MawjaJob.SUBSCRIPTION_CHECK -> R.string.jobs_subscription_check
        MawjaJob.SMART_NOTIFY -> R.string.jobs_smart_notify
        MawjaJob.STORAGE_GUARD -> R.string.jobs_storage_guard
        MawjaJob.UPDATE_CHECK -> R.string.jobs_update_check
        MawjaJob.AUTO_BACKUP -> R.string.jobs_auto_backup
        MawjaJob.SCROBBLE -> R.string.jobs_scrobble
        MawjaJob.DOWNLOAD_QUEUE -> R.string.jobs_download_queue
        MawjaJob.DOWNLOAD_RETAG -> R.string.jobs_download_retag
    }
