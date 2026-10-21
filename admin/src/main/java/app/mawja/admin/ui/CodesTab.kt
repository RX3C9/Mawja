/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.mawja.admin.R
import app.mawja.admin.data.CodeBatch
import app.mawja.admin.data.CodeEntry
import app.mawja.codes.MawjaCodes
import java.text.DateFormat
import java.util.Date

private val CODE_TIERS = listOf("plus", "pro", "family", "lifetime")
private val DAY_PRESETS = listOf(30, 90, 180, 365)

/**
 * Membership codes (docs/codes.md): generate a batch (shown once, only hashes are stored), find a
 * code a customer reads out, list batches and their codes, revoke. Support staff can look, only
 * owner/admin can generate or revoke.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun CodesTab(
    state: AdminState,
    vm: AdminViewModel,
) {
    LaunchedEffect(Unit) { vm.loadCodes() }
    val manage = canManageCodes(state.staff?.role)
    var confirmBatch by remember { mutableStateOf<CodeBatch?>(null) }
    var confirmCode by remember { mutableStateOf<CodeEntry?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("find") { FindCode(state, manage, vm, onRevoke = { confirmCode = it }) }
        if (manage) item("generate") { GenerateForm(enabled = !state.loading, onGenerate = vm::generateCodes) }
        item("batches.title") { Text(stringResource(R.string.codes_batches), style = MaterialTheme.typography.titleMedium) }
        if (state.batches.isEmpty()) item("batches.none") { Text(stringResource(R.string.nothing), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.batches, key = { "b_" + it.id }) { batch ->
            BatchCard(
                batch = batch,
                open = state.openBatch == batch.id,
                codes = if (state.openBatch == batch.id) state.batchCodes else emptyList(),
                manage = manage,
                onToggle = { vm.openBatch(if (state.openBatch == batch.id) null else batch.id) },
                onRevokeBatch = { confirmBatch = batch },
                onRevokeCode = { confirmCode = it },
            )
        }
        item("redemptions.title") { Text(stringResource(R.string.codes_redemptions), style = MaterialTheme.typography.titleMedium) }
        if (state.redemptions.isEmpty()) item("redemptions.none") { Text(stringResource(R.string.nothing), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.redemptions, key = { "r_" + it.id }) { r ->
            ListItem(
                headlineContent = { Text("${r.tier} · ${r.tierUntil?.let(::day) ?: "∞"}") },
                supportingContent = { Text(stringResource(R.string.codes_redemption_line, r.codeTier, r.days, r.uid), style = MaterialTheme.typography.bodySmall) },
                trailingContent = { Text(r.createdAt?.let(::day).orEmpty(), style = MaterialTheme.typography.labelSmall) },
            )
        }
    }

    state.generated?.let { GeneratedDialog(it.codes, it.requested, it.error, onDismiss = vm::dismissGenerated) }
    confirmBatch?.let { batch ->
        ConfirmDialog(
            text = stringResource(R.string.codes_revoke_batch_confirm, batch.count),
            onConfirm = {
                vm.revokeBatch(batch.id)
                confirmBatch = null
            },
            onDismiss = { confirmBatch = null },
        )
    }
    confirmCode?.let { code ->
        ConfirmDialog(
            text = stringResource(R.string.codes_revoke_confirm, masked(code.hint)),
            onConfirm = {
                vm.revokeCode(code.hash)
                confirmCode = null
            },
            onDismiss = { confirmCode = null },
        )
    }
}

@Composable
private fun FindCode(
    state: AdminState,
    manage: Boolean,
    vm: AdminViewModel,
    onRevoke: (CodeEntry) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.codes_find), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { Text("MW-XXXX-XXXX-XXXX") },
                    singleLine = true,
                    isError = query.isNotBlank() && MawjaCodes.normalize(query) == null && MawjaCodes.clean(query).length >= MawjaCodes.LENGTH,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.findCode(query) }, enabled = MawjaCodes.isValid(query)) { Text(stringResource(R.string.lookup)) }
            }
            if (state.codeNotFound) Text(stringResource(R.string.codes_not_found))
            state.foundCode?.let { CodeRow(it, manage, onRevoke) }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GenerateForm(
    enabled: Boolean,
    onGenerate: (tier: String, days: Int, count: Int, maxUses: Int, validForDays: Int, note: String) -> Unit,
) {
    var tier by rememberSaveable { mutableStateOf("plus") }
    var days by rememberSaveable { mutableStateOf("30") }
    var count by rememberSaveable { mutableStateOf("10") }
    var maxUses by rememberSaveable { mutableStateOf("1") }
    var validFor by rememberSaveable { mutableStateOf("0") }
    var note by rememberSaveable { mutableStateOf("") }
    val lifetime = tier == "lifetime"
    val d = days.toIntOrNull()
    val c = count.toIntOrNull()
    val m = maxUses.toIntOrNull()
    val v = validFor.toIntOrNull()
    val valid =
        (lifetime || (d != null && d in 1..3660)) && c != null && c in 1..MawjaCodes.MAX_BATCH &&
            m != null && m in 1..10_000 && v != null && v in 0..3660 && note.length <= 200

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.codes_generate), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.codes_tier), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CODE_TIERS.forEach { t -> FilterChip(selected = tier == t, onClick = { tier = t }, label = { Text(t) }) }
            }
            if (!lifetime) {
                Text(stringResource(R.string.codes_days), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DAY_PRESETS.forEach { p -> FilterChip(selected = d == p, onClick = { days = p.toString() }, label = { Text(stringResource(R.string.codes_days_n, p)) }) }
                }
                NumberField(days, { days = it }, stringResource(R.string.codes_days))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(count, { count = it }, stringResource(R.string.codes_count), Modifier.weight(1f))
                NumberField(maxUses, { maxUses = it }, stringResource(R.string.codes_max_uses), Modifier.weight(1f))
            }
            NumberField(validFor, { validFor = it }, stringResource(R.string.codes_valid_for))
            OutlinedTextField(note, { note = it.take(200) }, label = { Text(stringResource(R.string.codes_note)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { onGenerate(tier, if (lifetime) 0 else d ?: 0, c ?: 0, m ?: 1, v ?: 0, note) },
                enabled = enabled && valid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.codes_generate_n, c ?: 0)) }
            Text(stringResource(R.string.codes_once_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value,
        { onChange(it.filter(Char::isDigit).take(5)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun BatchCard(
    batch: CodeBatch,
    open: Boolean,
    codes: List<CodeEntry>,
    manage: Boolean,
    onToggle: () -> Unit,
    onRevokeBatch: () -> Unit,
    onRevokeCode: (CodeEntry) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                listOf(batch.tier, if (batch.tier == "lifetime") "∞" else stringResource(R.string.codes_days_n, batch.days)).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.codes_batch_line, batch.count, batch.maxUses), style = MaterialTheme.typography.bodySmall)
            if (batch.note.isNotBlank()) Text(batch.note, style = MaterialTheme.typography.bodyMedium)
            Text(
                listOfNotNull(
                    batch.createdAt?.let { stringResource(R.string.codes_created, day(it)) },
                    batch.expiresAt?.let { stringResource(R.string.codes_expires, day(it)) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onToggle) { Text(stringResource(if (open) R.string.codes_hide else R.string.codes_show)) }
                if (batch.revoked) {
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.codes_revoked)) })
                } else if (manage) {
                    TextButton(onClick = onRevokeBatch) { Text(stringResource(R.string.codes_revoke_batch), color = MaterialTheme.colorScheme.error) }
                }
            }
            if (open) {
                HorizontalDivider()
                if (codes.isEmpty()) Text(stringResource(R.string.nothing))
                codes.forEach { CodeRow(it, manage, onRevokeCode) }
            }
        }
    }
}

@Composable
private fun CodeRow(
    code: CodeEntry,
    manage: Boolean,
    onRevoke: (CodeEntry) -> Unit,
) {
    ListItem(
        headlineContent = { Text(masked(code.hint), fontFamily = FontFamily.Monospace) },
        supportingContent = {
            Text(
                listOfNotNull(
                    code.tier,
                    stringResource(R.string.codes_used, code.used, code.maxUses),
                    code.expiresAt?.let { stringResource(R.string.codes_expires, day(it)) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            when {
                code.revoked -> Text(stringResource(R.string.codes_revoked), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                manage -> TextButton(onClick = { onRevoke(code) }) { Text(stringResource(R.string.codes_revoke)) }
            }
        },
    )
}

@Composable
private fun GeneratedDialog(
    codes: List<String>,
    requested: Int,
    error: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val text = codes.joinToString("\n")
    AlertDialog(
        onDismissRequest = {}, // only the Done button closes it: the codes are never shown again
        title = { Text(stringResource(R.string.codes_generated_title, codes.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.codes_generated_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (error != null) Text(stringResource(R.string.codes_partial, codes.size, requested, error), style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Text(
                        text,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { copySensitive(context, text) }, enabled = codes.isNotEmpty()) { Text(stringResource(R.string.codes_copy)) }
                TextButton(onClick = { share(context, text) }, enabled = codes.isNotEmpty()) { Text(stringResource(R.string.codes_share)) }
            }
        },
    )
}

@Composable
private fun ConfirmDialog(
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.codes_revoke), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun masked(hint: String) = "MW-••••-••••-$hint"

private fun day(date: Date): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)

/** Copies codes marked sensitive, so Android 13+ hides them from the clipboard preview. */
private fun copySensitive(
    context: Context,
    text: String,
) {
    val clip = ClipData.newPlainText("Mawja codes", text)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

private fun share(
    context: Context,
    text: String,
) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}
