/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mawja.admin.R
import app.mawja.admin.core.Csv
import app.mawja.admin.core.Perm
import app.mawja.admin.core.Permissions
import app.mawja.admin.data.CodeBatch
import app.mawja.admin.data.CodeEntry
import app.mawja.admin.data.GeneratedBatch
import app.mawja.admin.data.Staff
import app.mawja.admin.ui.CodesViewModel
import app.mawja.admin.ui.components.ConfirmDialog
import app.mawja.admin.ui.components.EmptyState
import app.mawja.admin.ui.components.LoadMore
import app.mawja.admin.ui.components.Pill
import app.mawja.admin.ui.components.ScreenHeader
import app.mawja.admin.ui.components.SectionCard
import app.mawja.admin.ui.components.TierPill
import app.mawja.admin.ui.components.copyText
import app.mawja.admin.ui.components.dateTime
import app.mawja.admin.ui.components.day
import app.mawja.admin.ui.components.shareText
import app.mawja.admin.ui.components.tierLabel
import app.mawja.admin.ui.theme.Brand
import app.mawja.codes.MawjaCodes
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

private val CODE_TIERS = listOf("plus", "pro", "family", "lifetime")
private val DAY_PRESETS = listOf(30, 90, 180, 365)

/**
 * Membership codes (docs/codes.md): generate a batch (shown once, only hashes are stored), export
 * or share it as CSV, show a QR per code, find a code a customer reads out, list batches and
 * their codes, revoke. Everyone on staff can look; only owner/admin generate or revoke.
 */
@Composable
fun CodesScreen(
    staff: Staff,
    vm: CodesViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val manage = Permissions.can(staff.role, Perm.CODES_MANAGE)
    val context = LocalContext.current
    var confirmBatch by remember { mutableStateOf<CodeBatch?>(null) }
    var confirmCode by remember { mutableStateOf<CodeEntry?>(null) }
    var batchCsv by remember { mutableStateOf("") }
    val exportBatch =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) context.contentResolver.openOutputStream(uri)?.use { it.write(batchCsv.toByteArray()) }
        }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("head") {
            ScreenHeader(stringResource(R.string.nav_codes), subtitle = stringResource(R.string.codes_subtitle)) {
                IconButton(onClick = { vm.load(force = true) }, enabled = !busy) { Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh)) }
            }
        }
        if (busy) item("busy") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item("find") { FindCode(vm, manage, onRevoke = { confirmCode = it }) }
        if (manage) item("generate") { GenerateForm(enabled = !busy, onGenerate = vm::generate) }
        item("batches.title") { Text(stringResource(R.string.codes_batches), style = MaterialTheme.typography.titleMedium) }
        if (state.batches.isEmpty() && !busy) item("batches.none") { EmptyState(stringResource(R.string.nothing)) }
        items(state.batches, key = { "b_" + it.id }) { batch ->
            BatchCard(
                batch = batch,
                open = state.openBatch == batch.id,
                codes = if (state.openBatch == batch.id) state.batchCodes else emptyList(),
                manage = manage,
                onToggle = { vm.toggleBatch(batch.id) },
                onRevokeBatch = { confirmBatch = batch },
                onRevokeCode = { confirmCode = it },
                onExport = { codes ->
                    batchCsv =
                        Csv.table(
                            listOf("hint", "tier", "days", "used", "max_uses", "revoked", "redeem_by", "batch", "note"),
                            codes.map { listOf("MW-••••-••••-" + it.hint, it.tier, it.days, it.used, it.maxUses, it.revoked, it.expiresAt?.let(::day) ?: "", batch.id, batch.note) },
                        )
                    exportBatch.launch("mawja-batch-${batch.id.take(8)}.csv")
                },
            )
        }
        if (state.batches.isNotEmpty()) item("more") { LoadMore(state.end, busy, vm::more) }
        item("redemptions.title") { Text(stringResource(R.string.codes_redemptions), style = MaterialTheme.typography.titleMedium) }
        if (state.redemptions.isEmpty()) item("redemptions.none") { EmptyState(stringResource(R.string.nothing)) }
        items(state.redemptions, key = { "r_" + it.id }) { r ->
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { TierPill(r.tier) },
                headlineContent = { Text(stringResource(R.string.until_date, r.tierUntil?.let(::day) ?: "∞")) },
                supportingContent = { Text(stringResource(R.string.codes_redemption_line, tierLabel(r.codeTier), r.days, r.uid), style = MaterialTheme.typography.bodySmall) },
                trailingContent = { Text(dateTime(r.createdAt), style = MaterialTheme.typography.labelSmall) },
            )
        }
    }

    state.generated?.let { GeneratedDialog(it, onDismiss = vm::dismissGenerated) }
    confirmBatch?.let { batch ->
        ConfirmDialog(
            text = stringResource(R.string.codes_revoke_batch_confirm, batch.count),
            confirm = stringResource(R.string.codes_revoke),
            onConfirm = { vm.revokeBatch(batch.id) },
            onDismiss = { confirmBatch = null },
        )
    }
    confirmCode?.let { code ->
        ConfirmDialog(
            text = stringResource(R.string.codes_revoke_confirm, masked(code.hint)),
            confirm = stringResource(R.string.codes_revoke),
            onConfirm = { vm.revoke(code.hash) },
            onDismiss = { confirmCode = null },
        )
    }
}

@Composable
private fun FindCode(
    vm: CodesViewModel,
    manage: Boolean,
    onRevoke: (CodeEntry) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    SectionCard(title = stringResource(R.string.codes_find)) {
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
            TextButton(onClick = { vm.find(query) }, enabled = MawjaCodes.isValid(query)) { Text(stringResource(R.string.lookup)) }
        }
        if (state.notFound) Text(stringResource(R.string.codes_not_found), color = MaterialTheme.colorScheme.error)
        state.found?.let { code ->
            CodeRow(code, manage, onRevoke)
            state.foundRedemptions.forEach { r ->
                Text(stringResource(R.string.codes_used_by, r.uid, dateTime(r.createdAt)), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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

    SectionCard(title = stringResource(R.string.codes_generate)) {
        Text(stringResource(R.string.codes_tier), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CODE_TIERS.forEach { t -> FilterChip(selected = tier == t, onClick = { tier = t }, label = { Text(tierLabel(t)) }) }
        }
        if (!lifetime) {
            Text(stringResource(R.string.codes_days), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DAY_PRESETS.forEach { p -> FilterChip(selected = d == p, onClick = { days = p.toString() }, label = { Text(stringResource(R.string.days_n, p)) }) }
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
    onExport: (List<CodeEntry>) -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TierPill(batch.tier)
            Text(
                if (batch.tier == "lifetime") "∞" else stringResource(R.string.days_n, batch.days),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (batch.revoked) Pill(stringResource(R.string.codes_revoked), Brand.Danger)
        }
        Text(stringResource(R.string.codes_batch_line, batch.count, batch.maxUses), style = MaterialTheme.typography.bodySmall)
        if (batch.note.isNotBlank()) Text(batch.note, style = MaterialTheme.typography.bodyMedium)
        Text(
            listOfNotNull(
                batch.createdAt?.let { stringResource(R.string.codes_created, dateTime(it)) },
                batch.expiresAt?.let { stringResource(R.string.codes_expires, day(it)) },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onToggle) { Text(stringResource(if (open) R.string.codes_hide else R.string.codes_show)) }
            if (open && codes.isNotEmpty()) {
                IconButton(onClick = { onExport(codes) }) { Icon(Icons.Rounded.FileDownload, stringResource(R.string.export_csv)) }
            }
            if (!batch.revoked && manage) {
                TextButton(onClick = onRevokeBatch) { Text(stringResource(R.string.codes_revoke_batch), color = MaterialTheme.colorScheme.error) }
            }
        }
        if (open) {
            HorizontalDivider()
            if (codes.isEmpty()) Text(stringResource(R.string.nothing))
            val used = codes.sumOf { it.used }
            if (codes.isNotEmpty()) Text(stringResource(R.string.codes_batch_usage, used, codes.count { it.revoked }), style = MaterialTheme.typography.labelMedium)
            codes.forEach { CodeRow(it, manage, onRevokeCode) }
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
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(masked(code.hint), fontFamily = FontFamily.Monospace) },
        supportingContent = {
            Text(
                listOfNotNull(
                    tierLabel(code.tier),
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

/** The freshly generated codes: copy, share, CSV export or QR, then gone for good. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GeneratedDialog(
    batch: GeneratedBatch,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val codes = batch.codes
    val text = codes.joinToString("\n")
    var qr by remember { mutableStateOf<String?>(null) }
    var confirmClose by remember { mutableStateOf(false) }
    val csv =
        remember(batch) {
            Csv.table(
                listOf("code", "tier", "days", "max_uses", "redeem_by", "batch", "note"),
                codes.map { listOf(it, batch.tier, batch.days, batch.maxUses, batch.expiresAt?.let(::day) ?: "", batch.batchId, batch.note) },
            )
        }
    val export =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
        }
    AlertDialog(
        onDismissRequest = {}, // only Done closes it: the codes are never shown again
        title = { Text(stringResource(R.string.codes_generated_title, codes.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.codes_generated_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (batch.error != null) Text(stringResource(R.string.codes_partial, codes.size, batch.requested, batch.error), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { copyText(context, text, sensitive = true) }, label = { Text(stringResource(R.string.codes_copy)) }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                    AssistChip(onClick = { shareText(context, text) }, label = { Text(stringResource(R.string.codes_share)) }, leadingIcon = { Icon(Icons.Rounded.Share, null) })
                    AssistChip(onClick = { export.launch("mawja-codes-${batch.batchId.take(8)}.csv") }, label = { Text(stringResource(R.string.export_csv)) }, leadingIcon = { Icon(Icons.Rounded.FileDownload, null) })
                }
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    codes.forEach { code ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(code, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            IconButton(onClick = { qr = code }) { Icon(Icons.Rounded.QrCode2, stringResource(R.string.qr_code)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { confirmClose = true }) { Text(stringResource(R.string.done)) } },
    )
    qr?.let { code ->
        AlertDialog(
            onDismissRequest = { qr = null },
            title = { Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium) },
            text = { QrImage(code, Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { qr = null }) { Text(stringResource(R.string.close)) } },
        )
    }
    if (confirmClose) {
        ConfirmDialog(
            text = stringResource(R.string.codes_close_confirm),
            confirm = stringResource(R.string.done),
            onConfirm = onDismiss,
            onDismiss = { confirmClose = false },
        )
    }
}

/** A QR code drawn module by module (ZXing encoder only, no bitmap). Dark on white for scanners. */
@Composable
private fun QrImage(
    text: String,
    modifier: Modifier = Modifier,
) {
    val matrix = remember(text) { Encoder.encode(text, ErrorCorrectionLevel.M).matrix }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(240.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(16.dp)) {
            val n = matrix.width
            val cell = size.minDimension / n
            for (y in 0 until n) {
                for (x in 0 until n) {
                    if (matrix.get(x, y).toInt() == 1) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

private fun masked(hint: String) = "MW-••••-••••-$hint"
