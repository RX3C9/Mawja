/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mawja.admin.R
import app.mawja.admin.core.AuditCategories
import app.mawja.admin.core.Perm
import app.mawja.admin.core.Permissions
import app.mawja.admin.core.ReportStatus
import app.mawja.admin.core.StaffRole
import app.mawja.admin.data.AuditEntry
import app.mawja.admin.data.Report
import app.mawja.admin.data.Staff
import app.mawja.admin.data.StaffMember
import app.mawja.admin.ui.AuditViewModel
import app.mawja.admin.ui.ReportsViewModel
import app.mawja.admin.ui.StaffViewModel
import app.mawja.admin.ui.UsersViewModel
import app.mawja.admin.ui.components.EmptyState
import app.mawja.admin.ui.components.InfoRow
import app.mawja.admin.ui.components.LoadMore
import app.mawja.admin.ui.components.Pill
import app.mawja.admin.ui.components.ScreenHeader
import app.mawja.admin.ui.components.SectionCard
import app.mawja.admin.ui.components.dateTime
import app.mawja.admin.ui.components.roleLabel
import app.mawja.admin.ui.theme.Brand

// ---- Reports ---------------------------------------------------------------------------------

@Composable
private fun reportStatusLabel(s: String): String =
    when (s) {
        ReportStatus.OPEN -> stringResource(R.string.rs_open)
        ReportStatus.IN_PROGRESS -> stringResource(R.string.rs_in_progress)
        ReportStatus.RESOLVED -> stringResource(R.string.rs_resolved)
        ReportStatus.DISMISSED -> stringResource(R.string.rs_dismissed)
        else -> s
    }

private fun reportStatusColor(s: String): Color =
    when (s) {
        ReportStatus.OPEN -> Brand.Danger
        ReportStatus.IN_PROGRESS -> Color(0xFF3E8EF7)
        ReportStatus.RESOLVED -> Brand.Success
        else -> Color(0xFF8B8D98)
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportsScreen(
    staff: Staff,
    onOpenUser: () -> Unit,
    vm: ReportsViewModel = viewModel(),
    users: UsersViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }
    val status by vm.status.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val end by vm.end.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val open = selected
    BackHandler(enabled = open != null) { vm.open(null) }
    if (open != null) {
        ReportDetail(
            open,
            myUid = staff.uid,
            busy = busy,
            onBack = { vm.open(null) },
            onSave = { s, a, n, r -> vm.save(open, s, a, n, r) },
            onOpenUser = { uid ->
                users.open(uid)
                onOpenUser()
            },
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            ScreenHeader(stringResource(R.string.nav_reports), subtitle = stringResource(R.string.reports_subtitle)) {
                IconButton(onClick = { vm.load(force = true) }, enabled = !busy) { Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh)) }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (ReportStatus.ALL + listOf<String?>(null)).forEach { s ->
                    FilterChip(
                        selected = status == s,
                        onClick = { vm.load(s, force = true) },
                        label = { Text(if (s == null) stringResource(R.string.all) else reportStatusLabel(s)) },
                    )
                }
            }
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (items.isEmpty() && !busy) item { EmptyState(stringResource(R.string.inbox_empty)) }
        items(items, key = { it.id }) { r ->
            SectionCard(Modifier.clickable { vm.open(r) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(reportStatusLabel(r.status), reportStatusColor(r.status))
                    Text(r.type, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text(dateTime(r.createdAt), style = MaterialTheme.typography.labelSmall)
                }
                Text(r.message, maxLines = 3, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
                if (!r.assignee.isNullOrBlank()) {
                    Text(
                        stringResource(if (r.assignee == staff.uid) R.string.assigned_to_me else R.string.assigned_to, r.assignee.orEmpty()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (items.isNotEmpty()) item { LoadMore(end, busy, vm::more) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportDetail(
    r: Report,
    myUid: String,
    busy: Boolean,
    onBack: () -> Unit,
    onSave: (String, String?, String, String) -> Unit,
    onOpenUser: (String) -> Unit,
) {
    var status by rememberSaveable(r.id) { mutableStateOf(r.status) }
    var assignee by rememberSaveable(r.id) { mutableStateOf(r.assignee) }
    var note by rememberSaveable(r.id) { mutableStateOf(r.note) }
    var reply by rememberSaveable(r.id) { mutableStateOf(r.reply) }
    val changed = status != r.status || assignee != r.assignee || note != r.note || reply != r.reply
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
            Box(Modifier.weight(1f)) { ScreenHeader(r.type, subtitle = dateTime(r.createdAt)) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(title = stringResource(R.string.message)) {
                Text(r.message, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
                InfoRow(stringResource(R.string.report_id), r.id, copy = true, mono = true)
                InfoRow(stringResource(R.string.reporter), r.uid, copy = true, mono = true)
                r.extra.forEach { (k, v) -> InfoRow(k, v) }
                r.uid?.let { uid -> TextButton(onClick = { onOpenUser(uid) }) { Text(stringResource(R.string.open_user)) } }
            }
            SectionCard(title = stringResource(R.string.handling)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReportStatus.ALL.forEach { s -> FilterChip(selected = status == s, onClick = { status = s }, label = { Text(reportStatusLabel(s)) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            assignee.isNullOrBlank() -> stringResource(R.string.unassigned)
                            assignee == myUid -> stringResource(R.string.assigned_to_me)
                            else -> stringResource(R.string.assigned_to, assignee.orEmpty())
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (assignee != myUid) TextButton(onClick = { assignee = myUid }) { Text(stringResource(R.string.assign_me)) }
                    if (!assignee.isNullOrBlank()) TextButton(onClick = { assignee = null }) { Text(stringResource(R.string.unassign)) }
                }
                OutlinedTextField(note, { note = it.take(2000) }, label = { Text(stringResource(R.string.internal_note)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(reply, { reply = it.take(2000) }, label = { Text(stringResource(R.string.reply_to_user)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.reply_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { onSave(status, assignee, note, reply) }, enabled = !busy && changed, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}

// ---- Audit -----------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuditScreen(vm: AuditViewModel = viewModel()) {
    LaunchedEffect(Unit) { vm.load() }
    val items by vm.items.collectAsStateWithLifecycle()
    val end by vm.end.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    var detail by remember { mutableStateOf<AuditEntry?>(null) }
    val shown = items.filter { AuditCategories.matches(it.action, it.target, it.actorUid, category, text) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenHeader(stringResource(R.string.nav_audit), subtitle = stringResource(R.string.audit_subtitle, items.size)) {
                IconButton(onClick = { vm.load(force = true) }, enabled = !busy) { Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh)) }
            }
        }
        item {
            OutlinedTextField(
                text,
                { text = it },
                placeholder = { Text(stringResource(R.string.audit_search)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = category == null, onClick = { category = null }, label = { Text(stringResource(R.string.all)) })
                AuditCategories.ALL.forEach { c -> FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) }) }
            }
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
        if (shown.isEmpty() && !busy) item { EmptyState(stringResource(R.string.nothing)) }
        items(shown, key = { it.id }) { a ->
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { detail = a },
                overlineContent = { Text(dateTime(a.at)) },
                headlineContent = { Text(a.action, fontFamily = FontFamily.Monospace) },
                supportingContent = { Text("${a.target} · ${a.actorUid.take(10)}", maxLines = 1) },
                trailingContent = { Text(a.source, style = MaterialTheme.typography.labelSmall) },
            )
        }
        if (items.isNotEmpty()) item { LoadMore(end, busy, vm::more) }
    }
    detail?.let { a ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(a.action, fontFamily = FontFamily.Monospace) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoRow(stringResource(R.string.when_label), dateTime(a.at))
                    InfoRow(stringResource(R.string.actor), a.actorUid, copy = true, mono = true)
                    InfoRow(stringResource(R.string.target), a.target, copy = true, mono = true)
                    InfoRow(stringResource(R.string.source), a.source)
                    a.details.forEach { (k, v) -> InfoRow(k, v) }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}

// ---- Staff -----------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StaffScreen(
    staff: Staff,
    vm: StaffViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }
    val items by vm.items.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val owner = Permissions.can(staff.role, Perm.STAFF_MANAGE)
    var editing by remember { mutableStateOf<StaffMember?>(null) }
    var who by rememberSaveable { mutableStateOf("") }
    var newRole by rememberSaveable { mutableStateOf(StaffRole.SUPPORT.id) }
    var note by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            ScreenHeader(stringResource(R.string.nav_staff), subtitle = stringResource(if (owner) R.string.staff_subtitle_owner else R.string.staff_subtitle)) {
                IconButton(onClick = vm::load, enabled = !busy) { Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh)) }
            }
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (owner) {
            item {
                SectionCard(title = stringResource(R.string.add_staff)) {
                    OutlinedTextField(who, { who = it.trim() }, label = { Text(stringResource(R.string.uid_or_email)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StaffRole.ASSIGNABLE.forEach { r -> FilterChip(selected = newRole == r.id, onClick = { newRole = r.id }, label = { Text(roleLabel(r.id)) }) }
                    }
                    OutlinedTextField(note, { note = it.take(500) }, label = { Text(stringResource(R.string.note)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = {
                            if ('@' in who) vm.addByEmail(who, newRole, note) else vm.setRole(who, newRole, null, note)
                            who = ""
                            note = ""
                        },
                        enabled = !busy && who.length >= 6 && who != staff.uid && '/' !in who,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.grant_role)) }
                    Text(stringResource(R.string.owner_console_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (items.isEmpty() && !busy) item { EmptyState(stringResource(R.string.nothing)) }
        items(items, key = { it.uid }) { m ->
            val editable = Permissions.canEditStaff(staff.role, staff.uid, m.uid, m.role)
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(enabled = editable) { editing = m },
                headlineContent = { Text(m.email ?: m.uid, maxLines = 1) },
                supportingContent = { Text(listOfNotNull(m.uid.takeIf { m.email != null }, m.note.ifBlank { null }).joinToString(" · "), maxLines = 1) },
                trailingContent = {
                    Pill(
                        roleLabel(m.role) + if (m.uid == staff.uid) " · " + stringResource(R.string.you) else "",
                        when (m.role) {
                            "owner" -> Brand.Gold
                            "admin" -> Color(0xFF8E4EC6)
                            "moderator" -> Color(0xFF3E8EF7)
                            "support" -> Brand.Teal
                            else -> Color(0xFF8B8D98)
                        },
                    )
                },
            )
        }
    }
    editing?.let { m ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(m.email ?: m.uid) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(m.uid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (StaffRole.ASSIGNABLE.map { it.id } + StaffRole.REMOVED).forEach { r ->
                            FilterChip(
                                selected = m.role == r,
                                onClick = {
                                    vm.setRole(m.uid, r, m.email, m.note)
                                    editing = null
                                },
                                label = { Text(roleLabel(r)) },
                            )
                        }
                    }
                    Text(stringResource(R.string.claims_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { OutlinedButton(onClick = { editing = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}
