/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mawja.admin.R
import app.mawja.admin.core.AnnouncementPhase
import app.mawja.admin.core.Announcements
import app.mawja.admin.core.Component
import app.mawja.admin.core.Perm
import app.mawja.admin.core.Permissions
import app.mawja.admin.core.RemoteConfig
import app.mawja.admin.core.Tiers
import app.mawja.admin.data.Announcement
import app.mawja.admin.data.Staff
import app.mawja.admin.ui.AnnouncementsViewModel
import app.mawja.admin.ui.ConfigViewModel
import app.mawja.admin.ui.components.ConfirmDialog
import app.mawja.admin.ui.components.EmptyState
import app.mawja.admin.ui.components.LoadMore
import app.mawja.admin.ui.components.Pill
import app.mawja.admin.ui.components.ScreenHeader
import app.mawja.admin.ui.components.SectionCard
import app.mawja.admin.ui.components.dateTime
import app.mawja.admin.ui.components.tierLabel
import app.mawja.admin.ui.theme.Brand
import java.util.Calendar
import java.util.Date

// ---- Announcements ---------------------------------------------------------------------------

@Composable
fun AnnouncementsScreen(
    staff: Staff,
    vm: AnnouncementsViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }
    val items by vm.items.collectAsStateWithLifecycle()
    val end by vm.end.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val manage = Permissions.can(staff.role, Perm.ANNOUNCE_MANAGE)
    val draft = editing
    BackHandler(enabled = draft != null) { vm.edit(null) }
    if (draft != null) {
        AnnouncementEditor(draft, busy, onSave = vm::save, onCancel = { vm.edit(null) })
        return
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                ScreenHeader(stringResource(R.string.nav_announcements), subtitle = stringResource(R.string.announcements_subtitle)) {
                    IconButton(onClick = { vm.load(force = true) }, enabled = !busy) { Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh)) }
                }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (items.isEmpty() && !busy) item { EmptyState(stringResource(R.string.nothing)) }
            items(items, key = { it.id }) { a ->
                val phase = Announcements.phase(a.active, a.startAt?.time, a.endAt?.time, System.currentTimeMillis())
                SectionCard(Modifier.clickable(enabled = manage) { vm.edit(a) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PhasePill(phase)
                        Text(audience(a), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        if (manage) Switch(checked = a.active, onCheckedChange = { vm.setActive(a, it) }, enabled = !busy)
                    }
                    Text(a.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(a.body, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                    Text(
                        stringResource(R.string.ann_window, dateTime(a.startAt), a.endAt?.let { dateTime(it) } ?: "∞"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (items.isNotEmpty()) item { LoadMore(end, busy, vm::more) }
        }
        if (manage) {
            ExtendedFloatingActionButton(
                onClick = vm::newDraft,
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(stringResource(R.string.ann_new)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

@Composable
private fun PhasePill(phase: AnnouncementPhase) =
    when (phase) {
        AnnouncementPhase.LIVE -> Pill(stringResource(R.string.phase_live), Brand.Success)
        AnnouncementPhase.SCHEDULED -> Pill(stringResource(R.string.phase_scheduled), Color(0xFF3E8EF7))
        AnnouncementPhase.ENDED -> Pill(stringResource(R.string.phase_ended), MaterialTheme.colorScheme.outline)
        AnnouncementPhase.OFF -> Pill(stringResource(R.string.phase_off), MaterialTheme.colorScheme.outline)
    }

@Composable
private fun audience(a: Announcement): String =
    listOf(
        a.targetTier?.let { tierLabel(it) } ?: stringResource(R.string.everyone),
        when (a.locale) {
            "ar" -> stringResource(R.string.lang_ar)
            "en" -> stringResource(R.string.lang_en)
            else -> stringResource(R.string.all_languages)
        },
        kindLabel(a.kind),
    ).joinToString(" · ")

@Composable
private fun kindLabel(kind: String): String =
    when (kind) {
        "dialog" -> stringResource(R.string.kind_dialog)
        "news" -> stringResource(R.string.kind_news)
        else -> stringResource(R.string.kind_banner)
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnnouncementEditor(
    initial: Announcement,
    busy: Boolean,
    onSave: (Announcement) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var body by rememberSaveable { mutableStateOf(initial.body) }
    var kind by rememberSaveable { mutableStateOf(initial.kind) }
    var tier by rememberSaveable { mutableStateOf(initial.targetTier) }
    var locale by rememberSaveable { mutableStateOf(initial.locale) }
    var ctaLabel by rememberSaveable { mutableStateOf(initial.ctaLabel) }
    var ctaUrl by rememberSaveable { mutableStateOf(initial.ctaUrl) }
    var active by rememberSaveable { mutableStateOf(initial.active) }
    var start by rememberSaveable { mutableStateOf(initial.startAt?.time ?: System.currentTimeMillis()) }
    var endAt by rememberSaveable { mutableStateOf(initial.endAt?.time) }
    val problems = Announcements.problems(title, body, ctaLabel, ctaUrl, start, endAt)
    val current = initial.copy(
        title = title, body = body, kind = kind, targetTier = tier, locale = locale, ctaLabel = ctaLabel, ctaUrl = ctaUrl,
        active = active, startAt = Date(start), endAt = endAt?.let(::Date),
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                ScreenHeader(stringResource(if (initial.id.isBlank()) R.string.ann_new else R.string.ann_edit))
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(title = stringResource(R.string.ann_content)) {
                OutlinedTextField(
                    title,
                    { title = it.take(Announcements.MAX_TITLE) },
                    label = { Text(stringResource(R.string.title)) },
                    supportingText = { Text("${title.length}/${Announcements.MAX_TITLE}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    body,
                    { body = it.take(Announcements.MAX_BODY) },
                    label = { Text(stringResource(R.string.body)) },
                    supportingText = { Text("${body.length}/${Announcements.MAX_BODY}") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.ann_kind), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Announcements.KINDS.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(kindLabel(k)) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(ctaLabel, { ctaLabel = it.take(Announcements.MAX_CTA_LABEL) }, label = { Text(stringResource(R.string.cta_label)) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        ctaUrl,
                        { ctaUrl = it.trim() },
                        label = { Text(stringResource(R.string.cta_url)) },
                        placeholder = { Text("https:// · mawja://") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            SectionCard(title = stringResource(R.string.ann_audience)) {
                Text(stringResource(R.string.target_tier), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = tier == null, onClick = { tier = null }, label = { Text(stringResource(R.string.everyone)) })
                    Tiers.ALL.forEach { t -> FilterChip(selected = tier == t, onClick = { tier = t }, label = { Text(tierLabel(t)) }) }
                }
                Text(stringResource(R.string.language), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = locale == null, onClick = { locale = null }, label = { Text(stringResource(R.string.all_languages)) })
                    FilterChip(selected = locale == "ar", onClick = { locale = "ar" }, label = { Text(stringResource(R.string.lang_ar)) })
                    FilterChip(selected = locale == "en", onClick = { locale = "en" }, label = { Text(stringResource(R.string.lang_en)) })
                }
            }
            SectionCard(title = stringResource(R.string.ann_schedule)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ann_active), modifier = Modifier.weight(1f))
                    Switch(checked = active, onCheckedChange = { active = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.starts, dateTime(Date(start))), modifier = Modifier.weight(1f))
                    TextButton(onClick = { start = System.currentTimeMillis() }) { Text(stringResource(R.string.now)) }
                    TextButton(onClick = { pickDateTime(context, start) { start = it } }) { Text(stringResource(R.string.change)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ends, endAt?.let { dateTime(Date(it)) } ?: "∞"), modifier = Modifier.weight(1f))
                    if (endAt != null) TextButton(onClick = { endAt = null }) { Text(stringResource(R.string.never)) }
                    TextButton(onClick = { pickDateTime(context, endAt ?: (start + 7 * 86_400_000L)) { endAt = it } }) { Text(stringResource(R.string.change)) }
                }
            }
            Text(stringResource(R.string.preview), style = MaterialTheme.typography.titleMedium)
            AnnouncementPreview(current)
            if (problems.isNotEmpty()) {
                Text(problems.map { problemText(it) }.joinToString("\n"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
                Button(onClick = { onSave(current) }, enabled = !busy && problems.isEmpty(), modifier = Modifier.weight(1f)) {
                    Text(stringResource(if (start > System.currentTimeMillis() + 60_000) R.string.schedule else R.string.publish))
                }
            }
        }
    }
}

@Composable
private fun problemText(code: String): String =
    when (code) {
        "title" -> stringResource(R.string.p_title)
        "body" -> stringResource(R.string.p_body)
        "cta_url" -> stringResource(R.string.p_cta_url)
        "cta_label" -> stringResource(R.string.p_cta_label)
        "end_before_start" -> stringResource(R.string.p_end_before_start)
        else -> code
    }

/** Roughly how the app shows it: a card with the message and an optional button. */
@Composable
private fun AnnouncementPreview(a: Announcement) {
    Card(
        Modifier.fillMaxWidth().widthIn(max = 480.dp),
        shape = RoundedCornerShape(if (a.kind == "dialog") 28.dp else 16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = if (a.kind == "banner") MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Campaign, null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    a.title.ifBlank { "…" },
                    style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(a.body.ifBlank { "…" }, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
            if (a.ctaLabel.isNotBlank()) {
                Button(onClick = {}, modifier = Modifier.align(Alignment.End)) { Text(a.ctaLabel) }
            }
        }
    }
}

private fun pickDateTime(
    context: Context,
    initial: Long,
    onPicked: (Long) -> Unit,
) {
    val cal = Calendar.getInstance().apply { timeInMillis = initial }
    DatePickerDialog(
        context,
        { _, y, m, d ->
            TimePickerDialog(
                context,
                { _, h, min ->
                    cal.set(y, m, d, h, min, 0)
                    onPicked(cal.timeInMillis)
                },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                android.text.format.DateFormat.is24HourFormat(context),
            ).show()
        },
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH),
        cal.get(Calendar.DAY_OF_MONTH),
    ).show()
}

// ---- Remote config ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfigScreen(
    staff: Staff,
    vm: ConfigViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.select(state.component) }
    val canEdit = Permissions.can(staff.role, Perm.CONFIG_EDIT)
    val d = state.draft
    val original = state.original
    val changes = original?.let { d.changesFrom(it) }.orEmpty()
    val problems = d.problems()
    var confirm by remember { mutableStateOf(false) }
    var newKey by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        ScreenHeader(
            stringResource(R.string.nav_config),
            subtitle = state.updatedAt?.let { stringResource(R.string.config_updated, dateTime(it), state.updatedBy ?: "—") } ?: stringResource(R.string.config_subtitle),
        )
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Component.entries.forEach { c ->
                    FilterChip(selected = state.component == c, onClick = { vm.select(c) }, label = { Text(componentLabel(c)) })
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!canEdit) Text(stringResource(R.string.read_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (original != null) {
                SectionCard(title = stringResource(R.string.availability)) {
                    SwitchRow(stringResource(R.string.kill_switch), stringResource(R.string.kill_switch_hint), d.killSwitch, canEdit) { v -> vm.edit { it.copy(killSwitch = v) } }
                    SwitchRow(stringResource(R.string.maintenance), stringResource(R.string.maintenance_hint), d.maintenance, canEdit) { v -> vm.edit { it.copy(maintenance = v) } }
                    OutlinedTextField(
                        d.maintenanceMessage,
                        { v -> vm.edit { it.copy(maintenanceMessage = v.take(RemoteConfig.MAX_MESSAGE)) } },
                        label = { Text(stringResource(R.string.message_en)) },
                        enabled = canEdit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        d.maintenanceMessageAr,
                        { v -> vm.edit { it.copy(maintenanceMessageAr = v.take(RemoteConfig.MAX_MESSAGE)) } },
                        label = { Text(stringResource(R.string.message_ar)) },
                        enabled = canEdit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SectionCard(title = stringResource(R.string.versions)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberInput(stringResource(R.string.min_version), d.minVersion, canEdit, Modifier.weight(1f)) { v -> vm.edit { it.copy(minVersion = v) } }
                        NumberInput(stringResource(R.string.latest_version), d.latestVersion, canEdit, Modifier.weight(1f)) { v -> vm.edit { it.copy(latestVersion = v) } }
                    }
                    OutlinedTextField(d.updateUrl, { v -> vm.edit { it.copy(updateUrl = v.trim()) } }, label = { Text(stringResource(R.string.update_url)) }, singleLine = true, enabled = canEdit, modifier = Modifier.fillMaxWidth())
                    if (state.component == Component.APP) {
                        OutlinedTextField(d.checkoutUrl, { v -> vm.edit { it.copy(checkoutUrl = v.trim()) } }, label = { Text(stringResource(R.string.checkout_url)) }, singleLine = true, enabled = canEdit, modifier = Modifier.fillMaxWidth())
                    }
                    Text(stringResource(R.string.versions_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SectionCard(title = stringResource(R.string.feature_flags)) {
                    val keys = (RemoteConfig.catalogue(state.component) + d.features.keys).distinct().sorted()
                    keys.forEach { key ->
                        val known = key in d.features
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(key, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(if (known) R.string.flag_set else R.string.flag_default),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (known && canEdit && key !in RemoteConfig.catalogue(state.component)) {
                                TextButton(onClick = { vm.edit { it.copy(features = it.features - key) } }) { Text(stringResource(R.string.remove)) }
                            }
                            Switch(checked = d.features[key] ?: true, enabled = canEdit, onCheckedChange = { v -> vm.edit { it.copy(features = it.features + (key to v)) } })
                        }
                    }
                    if (canEdit) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(newKey, { newKey = it.trim() }, label = { Text(stringResource(R.string.new_flag)) }, singleLine = true, isError = newKey.isNotEmpty() && !RemoteConfig.isFlagKey(newKey), modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = {
                                    vm.edit { it.copy(features = it.features + (newKey to true)) }
                                    newKey = ""
                                },
                                enabled = RemoteConfig.isFlagKey(newKey),
                            ) { Text(stringResource(R.string.add)) }
                        }
                    }
                }
                if (state.component == Component.APP) {
                    SectionCard(title = stringResource(R.string.tier_gates)) {
                        Text(stringResource(R.string.tier_gates_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        RemoteConfig.GATEABLE.forEach { feature ->
                            Text(feature, style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    selected = feature !in d.tierGates,
                                    enabled = canEdit,
                                    onClick = { vm.edit { it.copy(tierGates = it.tierGates - feature) } },
                                    label = { Text(stringResource(R.string.app_default)) },
                                )
                                Tiers.ALL.forEach { t ->
                                    FilterChip(
                                        selected = d.tierGates[feature] == t,
                                        enabled = canEdit,
                                        onClick = { vm.edit { it.copy(tierGates = it.tierGates + (feature to t)) } },
                                        label = { Text(tierLabel(t)) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (problems.isNotEmpty()) Text(problems.joinToString(", "), color = MaterialTheme.colorScheme.error)
                if (canEdit) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::discard, enabled = changes.isNotEmpty(), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.discard)) }
                        Button(onClick = { confirm = true }, enabled = !busy && changes.isNotEmpty() && problems.isEmpty(), modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.save_n, changes.size))
                        }
                    }
                }
            }
        }
    }
    if (confirm) {
        ConfirmDialog(
            text = stringResource(R.string.config_confirm, componentLabel(state.component), changes.keys.sorted().joinToString(", ")),
            confirm = stringResource(R.string.save),
            danger = d.killSwitch || d.maintenance,
            onConfirm = vm::save,
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun componentLabel(c: Component): String =
    when (c) {
        Component.APP -> stringResource(R.string.component_app)
        Component.DOWNLOADER -> stringResource(R.string.component_downloader)
        Component.ADMIN -> stringResource(R.string.component_admin)
    }

@Composable
private fun SwitchRow(
    title: String,
    hint: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
    HorizontalDivider()
}

@Composable
private fun NumberInput(
    label: String,
    value: Long,
    enabled: Boolean,
    modifier: Modifier,
    onChange: (Long) -> Unit,
) {
    OutlinedTextField(
        if (value == 0L) "" else value.toString(),
        { onChange(it.filter(Char::isDigit).take(9).toLongOrNull() ?: 0L) },
        label = { Text(label) },
        placeholder = { Text("0") },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
