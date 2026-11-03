/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import app.mawja.android.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReportViewModel
    @Inject
    constructor(
        val repository: ReportRepository,
    ) : ViewModel()

/**
 * "Report" (P9): kind, what happened, and an opt-in for this app's recent log. Opened from About
 * and from the video page (with [videoId]). Signed-out users are told reports need an account.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReportSheet(
    onDismiss: () -> Unit,
    videoId: String? = null,
    initialType: ReportType = if (videoId != null) ReportType.VIDEO_NOT_WORKING else ReportType.BUG,
    vm: ReportViewModel = hiltViewModel(),
) {
    val repo = vm.repository
    val scope = rememberCoroutineScope()
    var type by rememberSaveable { mutableStateOf(initialType) }
    var text by rememberSaveable { mutableStateOf("") }
    var attach by rememberSaveable { mutableStateOf(false) }
    var sending by rememberSaveable { mutableStateOf(false) }
    var outcome by rememberSaveable { mutableStateOf<ReportOutcome?>(null) }
    val draft = ReportDraft(type, text, attach, videoId)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.report_title), style = MaterialTheme.typography.titleLarge)
            when {
                !repo.available -> Text(stringResource(R.string.report_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                !repo.signedIn() -> Text(stringResource(R.string.report_sign_in), color = MaterialTheme.colorScheme.onSurfaceVariant)
                outcome == ReportOutcome.SENT -> {
                    Text(stringResource(R.string.report_sent), color = MaterialTheme.colorScheme.primary)
                    Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.report_done)) }
                }
                else -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReportType.entries.forEach { t ->
                            FilterChip(selected = type == t, onClick = { type = t }, label = { Text(stringResource(label(t))) })
                        }
                    }
                    OutlinedTextField(
                        text,
                        { text = it.take(ReportDraft.MAX) },
                        label = { Text(stringResource(R.string.report_message)) },
                        minLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        supportingText = { Text("${text.trim().length}/${ReportDraft.MAX}") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(attach, { attach = it })
                        Text(stringResource(R.string.report_attach_log), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(stringResource(R.string.report_device_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when (outcome) {
                        ReportOutcome.RESTRICTED -> Text(stringResource(R.string.report_restricted), color = MaterialTheme.colorScheme.error)
                        ReportOutcome.FAILED -> Text(stringResource(R.string.report_failed), color = MaterialTheme.colorScheme.error)
                        ReportOutcome.SIGNED_OUT -> Text(stringResource(R.string.report_sign_in), color = MaterialTheme.colorScheme.error)
                        else -> {}
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.report_cancel)) }
                        Button(
                            enabled = draft.valid && !sending,
                            onClick = {
                                sending = true
                                scope.launch {
                                    outcome = repo.send(draft)
                                    sending = false
                                }
                            },
                        ) { Text(stringResource(if (sending) R.string.report_sending else R.string.report_send)) }
                    }
                }
            }
        }
    }
}

private fun label(t: ReportType): Int =
    when (t) {
        ReportType.VIDEO_NOT_WORKING -> R.string.report_type_video
        ReportType.BUG -> R.string.report_type_bug
        ReportType.CONTENT -> R.string.report_type_content
        ReportType.SUGGESTION -> R.string.report_type_suggestion
    }
