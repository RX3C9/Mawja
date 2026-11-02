/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Verified
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mawja.admin.R
import app.mawja.admin.core.ManualTier
import app.mawja.admin.core.Perm
import app.mawja.admin.core.Permissions
import app.mawja.admin.core.Tiers
import app.mawja.admin.core.UserStatus
import app.mawja.admin.data.Staff
import app.mawja.admin.data.UserRecord
import app.mawja.admin.ui.UsersViewModel
import app.mawja.admin.ui.components.ConfirmDialog
import app.mawja.admin.ui.components.EmptyState
import app.mawja.admin.ui.components.InfoRow
import app.mawja.admin.ui.components.LoadMore
import app.mawja.admin.ui.components.ScreenHeader
import app.mawja.admin.ui.components.SectionCard
import app.mawja.admin.ui.components.StatusPill
import app.mawja.admin.ui.components.TierPill
import app.mawja.admin.ui.components.dateTime
import app.mawja.admin.ui.components.day
import app.mawja.admin.ui.components.statusLabel
import app.mawja.admin.ui.components.tierLabel

@Composable
fun UsersScreen(
    staff: Staff,
    vm: UsersViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (state.list.isEmpty()) vm.browse(null) }
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    val selected = state.selected
    BackHandler(enabled = selected != null) { vm.close() }
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(0.45f).fillMaxHeight()) { UserList(staff, vm, busy) }
            VerticalDivider()
            Box(Modifier.weight(0.55f).fillMaxHeight()) {
                if (selected != null) UserDetail(staff, selected, vm, busy, showBack = false) else EmptyState(stringResource(R.string.pick_user))
            }
        }
    } else if (selected != null) {
        UserDetail(staff, selected, vm, busy, showBack = true)
    } else {
        UserList(staff, vm, busy)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserList(
    staff: Staff,
    vm: UsersViewModel,
    busy: Boolean,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val canBulk = Permissions.can(staff.role, Perm.USERS_STATUS)
    var query by rememberSaveable { mutableStateOf(state.query) }
    val filters =
        listOf<Pair<String, String>?>(null) + Tiers.PAID.map { "tier" to it } + listOf("status" to UserStatus.SUSPENDED, "status" to UserStatus.BANNED)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenHeader(stringResource(R.string.nav_users)) }
        item {
            OutlinedTextField(
                query,
                { query = it },
                placeholder = { Text(stringResource(R.string.user_search_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            vm.clearSearch()
                        }) { Icon(Icons.Rounded.Close, stringResource(R.string.clear)) }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) vm.search(query) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
        if (canBulk && state.picked.isNotEmpty()) item { BulkBar(staff, vm, state.picked.size, busy) }
        if (state.searched) {
            item { Text(stringResource(R.string.search_results), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 8.dp)) }
            if (state.results.isEmpty()) item { EmptyState(stringResource(R.string.not_found)) }
            items(state.results, key = { "s_" + it.uid }) { UserRow(it, picked = it.uid in state.picked, onLongClick = if (canBulk) ({ vm.togglePick(it.uid) }) else null) { if (state.picked.isNotEmpty() && canBulk) vm.togglePick(it.uid) else vm.open(it.uid) } }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        }
        item {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                filters.forEach { f ->
                    FilterChip(
                        selected = state.filter == f,
                        onClick = { vm.browse(f) },
                        label = {
                            Text(
                                when {
                                    f == null -> stringResource(R.string.newest)
                                    f.first == "tier" -> tierLabel(f.second)
                                    else -> statusLabel(f.second)
                                },
                            )
                        },
                    )
                }
            }
        }
        if (state.list.isEmpty() && !busy) item { EmptyState(stringResource(R.string.no_users_hint)) }
        items(state.list, key = { "l_" + it.uid }) { UserRow(it, picked = it.uid in state.picked, onLongClick = if (canBulk) ({ vm.togglePick(it.uid) }) else null) { if (state.picked.isNotEmpty() && canBulk) vm.togglePick(it.uid) else vm.open(it.uid) } }
        if (state.list.isNotEmpty()) item { LoadMore(state.end, busy, vm::more) }
    }
}

@Composable
private fun UserRow(
    u: UserRecord,
    picked: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    ListItem(
        colors =
            ListItemDefaults.colors(
                containerColor = if (picked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            ),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(u.displayName ?: u.username?.let { "@$it" } ?: u.email ?: u.uid, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (u.verified) Icon(Icons.Rounded.Verified, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp))
            }
        },
        supportingContent = { Text(listOfNotNull(u.email, u.mawjaId).joinToString(" · ").ifEmpty { u.uid }, maxLines = 1) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TierPill(ManualTier.effective(u.tier, u.tierUntil?.time, System.currentTimeMillis()))
                if (u.status != UserStatus.ACTIVE) StatusPill(u.status)
                if (u.warnings > 0) Text("⚠ ${u.warnings}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

private val TIER_DAYS = listOf(30, 90, 180, 365, 0)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserDetail(
    staff: Staff,
    u: UserRecord,
    vm: UsersViewModel,
    busy: Boolean,
    showBack: Boolean,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val role = staff.role
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showBack) IconButton(onClick = vm::close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
            Box(Modifier.weight(1f)) {
                ScreenHeader(u.displayName ?: u.username?.let { "@$it" } ?: u.email ?: stringResource(R.string.unnamed_user), subtitle = u.mawjaId)
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(title = stringResource(R.string.profile)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TierPill(ManualTier.effective(u.tier, u.tierUntil?.time, System.currentTimeMillis()))
                    StatusPill(u.status)
                    if (u.verified) Icon(Icons.Rounded.Verified, stringResource(R.string.verified), tint = MaterialTheme.colorScheme.primary)
                }
                if (!u.exists) Text(stringResource(R.string.no_user_doc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                InfoRow(stringResource(R.string.uid), u.uid, copy = true, mono = true)
                InfoRow(stringResource(R.string.email), u.email, copy = true)
                InfoRow(stringResource(R.string.username), u.username?.let { "@$it" })
                InfoRow(stringResource(R.string.mawja_id), u.mawjaId, copy = true, mono = true)
                InfoRow(stringResource(R.string.stored_tier), "${tierLabel(u.tier)} · ${u.tierUntil?.let { stringResource(R.string.until_date, day(it)) } ?: stringResource(R.string.no_end)}")
                InfoRow(stringResource(R.string.tier_source), u.tierSource)
                InfoRow(stringResource(R.string.joined), dateTime(u.createdAt))
                InfoRow(stringResource(R.string.last_seen), dateTime(u.lastSeenAt))
                if (!u.banReason.isNullOrBlank()) InfoRow(stringResource(R.string.reason), u.banReason)
            }

            if (Permissions.can(role, Perm.USERS_TIER)) {
                var tier by remember(u.uid) { mutableStateOf(u.tier) }
                var days by remember(u.uid) { mutableIntStateOf(30) }
                var extend by remember(u.uid) { mutableStateOf(true) }
                SectionCard(title = stringResource(R.string.membership)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tiers.ALL.forEach { t -> FilterChip(selected = tier == t, onClick = { tier = t }, label = { Text(tierLabel(t)) }) }
                    }
                    if (tier != "free" && tier != "lifetime") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TIER_DAYS.forEach { n ->
                                FilterChip(
                                    selected = days == n,
                                    onClick = { days = n },
                                    label = { Text(if (n == 0) stringResource(R.string.open_ended) else stringResource(R.string.days_n, n)) },
                                )
                            }
                        }
                        if (days > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.extend_current), modifier = Modifier.weight(1f))
                                Switch(checked = extend, onCheckedChange = { extend = it })
                            }
                        }
                    }
                    val until = ManualTier.until(tier, days, extend, u.tierUntil?.time, System.currentTimeMillis())
                    Text(
                        stringResource(R.string.tier_preview, tierLabel(tier), until?.let { day(java.util.Date(it)) } ?: stringResource(R.string.no_end)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val text = stringResource(R.string.confirm_tier, tierLabel(tier))
                    Button(onClick = { confirm = text to { vm.setTier(tier, days, extend) } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.apply_membership))
                    }
                }
            }

            if (Permissions.can(role, Perm.USERS_STATUS) && u.uid != staff.uid) {
                var reason by remember(u.uid) { mutableStateOf(u.banReason.orEmpty()) }
                SectionCard(title = stringResource(R.string.account_status)) {
                    OutlinedTextField(reason, { reason = it.take(500) }, label = { Text(stringResource(R.string.reason)) }, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        UserStatus.ALL.filter { Permissions.canSetStatus(role, staff.uid, u.uid, it) }.forEach { s ->
                            val text = stringResource(R.string.confirm_status, statusLabel(s))
                            OutlinedButton(onClick = { confirm = text to { vm.setStatus(s, reason) } }, enabled = !busy && s != u.status) {
                                Text(statusLabel(s))
                            }
                        }
                    }
                }
            }

            if (Permissions.can(role, Perm.USERS_STATUS) && u.uid != staff.uid) {
                ModerationCard(u, vm, busy) { text, action -> confirm = text to action }
            }

            if (Permissions.can(role, Perm.USERS_BADGES)) {
                var badges by remember(u.uid, u.badges) { mutableStateOf(u.badges) }
                var verified by remember(u.uid, u.verified) { mutableStateOf(u.verified) }
                SectionCard(title = stringResource(R.string.badges)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Verified, null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.verified), modifier = Modifier.weight(1f).padding(start = 8.dp))
                        Switch(checked = verified, onCheckedChange = { verified = it })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tiers.BADGES.forEach { b ->
                            FilterChip(selected = b in badges, onClick = { badges = if (b in badges) badges - b else badges + b }, label = { Text(b) })
                        }
                    }
                    OutlinedButton(
                        onClick = { vm.setBadges(badges, verified) },
                        enabled = !busy && (badges.toSet() != u.badges.toSet() || verified != u.verified),
                    ) { Text(stringResource(R.string.save)) }
                }
            }

            if (Permissions.can(role, Perm.USERS_NOTE)) {
                var note by remember(u.uid, u.staffNote) { mutableStateOf(u.staffNote) }
                SectionCard(title = stringResource(R.string.staff_note)) {
                    OutlinedTextField(note, { note = it.take(1000) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { vm.setNote(note) }, enabled = !busy && note != u.staffNote) { Text(stringResource(R.string.save)) }
                }
            }

            SectionCard(title = stringResource(R.string.redemptions)) {
                if (state.redemptions.isEmpty()) Text(stringResource(R.string.nothing), color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.redemptions.forEach { r ->
                    InfoRow(dateTime(r.createdAt), "${tierLabel(r.codeTier)} · ${stringResource(R.string.days_n, r.days)} → ${tierLabel(r.tier)} ${day(r.tierUntil)}")
                }
            }
            SectionCard(title = stringResource(R.string.mod_user_reports, state.reports.size)) {
                if (state.reports.isEmpty()) Text(stringResource(R.string.nothing), color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.reports.take(20).forEach { r ->
                    InfoRow("${dateTime(r.createdAt)} · ${r.type} · ${r.status}", r.message.take(200))
                }
            }
            if (Permissions.can(role, Perm.AUDIT_VIEW)) {
                SectionCard(title = stringResource(R.string.mod_history)) {
                    if (state.history.isEmpty()) Text(stringResource(R.string.nothing), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.history.take(30).forEach { e ->
                        InfoRow("${dateTime(e.at)} · ${e.action}", e.details.entries.joinToString(", ") { "${it.key}: ${it.value}" }.ifBlank { e.actorUid })
                    }
                }
            }
            SectionCard(title = stringResource(R.string.devices)) {
                if (state.devices.isEmpty()) Text(stringResource(R.string.nothing), color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.devices.forEach { d ->
                    Text(d.id, style = MaterialTheme.typography.titleSmall)
                    d.fields.forEach { (k, v) -> InfoRow(k, v) }
                }
            }
            if (Permissions.can(role, Perm.USERS_SYNC_RESET)) {
                SectionCard(title = stringResource(R.string.sync_data)) {
                    Text(stringResource(R.string.sync_reset_body), style = MaterialTheme.typography.bodySmall)
                    val text = stringResource(R.string.confirm_sync_reset)
                    OutlinedButton(onClick = { confirm = text to { vm.resetSync() } }, enabled = !busy) {
                        Text(stringResource(R.string.sync_reset), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    confirm?.let { (text, action) ->
        ConfirmDialog(text = text, confirm = stringResource(R.string.confirm), danger = false, onConfirm = action, onDismiss = { confirm = null })
    }
}

/** Mawja 2.0 moderation: temporary ban, warnings (3 = ban), mute, feature locks, private message, sign out everywhere. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModerationCard(
    u: UserRecord,
    vm: UsersViewModel,
    busy: Boolean,
    ask: (String, () -> Unit) -> Unit,
) {
    val now = System.currentTimeMillis()
    var reason by remember(u.uid) { mutableStateOf("") }
    var hours by remember(u.uid) { mutableIntStateOf(24) }
    var custom by remember(u.uid) { mutableStateOf("") }
    val customDays = app.mawja.admin.core.ModerationActions.customDays(custom)
    val chosenHours = customDays?.let { it * 24 } ?: hours
    SectionCard(title = stringResource(R.string.mod_title)) {
        // Current state
        if (u.banUntil != null && u.banUntil.time > now) InfoRow(stringResource(R.string.mod_banned_until), dateTime(u.banUntil))
        InfoRow(stringResource(R.string.mod_warnings), "${u.warnings} / ${app.mawja.admin.core.ModerationActions.AUTO_BAN_WARNINGS}")
        if (u.mutedUntil != null && u.mutedUntil.time > now) InfoRow(stringResource(R.string.mod_muted_until), dateTime(u.mutedUntil))
        if (u.lockedFeatures.isNotEmpty()) InfoRow(stringResource(R.string.mod_locked), u.lockedFeatures.joinToString(", "))
        u.adminMessage?.let { InfoRow(stringResource(R.string.mod_message_current), it) }

        OutlinedTextField(reason, { reason = it.take(500) }, label = { Text(stringResource(R.string.reason)) }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.mod_duration), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            app.mawja.admin.core.ModerationActions.DURATIONS_H.forEach { h ->
                FilterChip(
                    selected = customDays == null && hours == h,
                    onClick = {
                        hours = h
                        custom = ""
                    },
                    label = { Text(durationLabel(h)) },
                )
            }
        }
        OutlinedTextField(
            custom,
            { custom = it.filter(Char::isDigit).take(4) },
            label = { Text(stringResource(R.string.mod_custom_days)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val banText = stringResource(R.string.mod_confirm_temp_ban, durationLabel(chosenHours))
        val muteText = stringResource(R.string.mod_confirm_mute, durationLabel(chosenHours))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { ask(banText) { vm.tempBan(chosenHours, reason) } }, enabled = !busy) {
                Text(stringResource(R.string.mod_temp_ban), color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(onClick = { ask(muteText) { vm.mute(chosenHours) } }, enabled = !busy) { Text(stringResource(R.string.mod_mute)) }
            if (u.mutedUntil != null && u.mutedUntil.time > now) {
                TextButton(onClick = { vm.mute(0) }, enabled = !busy) { Text(stringResource(R.string.mod_unmute)) }
            }
        }

        val next = app.mawja.admin.core.ModerationActions.warn(u.warnings)
        val warnText = stringResource(if (next.autoBan) R.string.mod_confirm_warn_ban else R.string.mod_confirm_warn, next.warnings)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { ask(warnText) { vm.warn(reason) } }, enabled = !busy) { Text(stringResource(R.string.mod_warn)) }
            if (u.warnings > 0) TextButton(onClick = vm::clearWarnings, enabled = !busy) { Text(stringResource(R.string.mod_clear_warnings)) }
        }

        HorizontalDivider()
        Text(stringResource(R.string.mod_locks), style = MaterialTheme.typography.labelLarge)
        var locks by remember(u.uid, u.lockedFeatures) { mutableStateOf(u.lockedFeatures.toSet()) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            app.mawja.admin.core.ModerationActions.LOCKABLE.forEach { f ->
                FilterChip(selected = f in locks, onClick = { locks = if (f in locks) locks - f else locks + f }, label = { Text(f) })
            }
        }
        OutlinedButton(onClick = { vm.lockFeatures(locks.toList()) }, enabled = !busy && locks != u.lockedFeatures.toSet()) { Text(stringResource(R.string.save)) }

        HorizontalDivider()
        var message by remember(u.uid) { mutableStateOf("") }
        OutlinedTextField(
            message,
            { message = it.take(app.mawja.admin.core.ModerationActions.MAX_MESSAGE) },
            label = { Text(stringResource(R.string.mod_message)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    vm.sendMessage(message)
                    message = ""
                },
                enabled = !busy && app.mawja.admin.core.ModerationActions.messageOk(message),
            ) { Text(stringResource(R.string.mod_send_message)) }
            if (u.adminMessage != null) TextButton(onClick = { vm.sendMessage(null) }, enabled = !busy) { Text(stringResource(R.string.mod_clear_message)) }
        }

        HorizontalDivider()
        val outText = stringResource(R.string.mod_confirm_sign_out)
        OutlinedButton(onClick = { ask(outText) { vm.signOutEverywhere() } }, enabled = !busy) {
            Text(stringResource(R.string.mod_sign_out_all), color = MaterialTheme.colorScheme.error)
        }
        u.signOutAt?.let { InfoRow(stringResource(R.string.mod_last_sign_out), dateTime(it)) }
    }
}

@Composable
private fun durationLabel(hours: Int): String =
    when {
        hours == 24 * 7 -> stringResource(R.string.mod_week)
        hours % 24 == 0 -> stringResource(R.string.days_n, hours / 24)
        else -> stringResource(R.string.mod_hours_n, hours)
    }

/** Bulk moderation on the accounts picked by long-press. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BulkBar(
    staff: Staff,
    vm: UsersViewModel,
    count: Int,
    busy: Boolean,
) {
    var text by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    SectionCard(title = stringResource(R.string.mod_bulk_title, count), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            text,
            { text = it.take(app.mawja.admin.core.ModerationActions.MAX_MESSAGE) },
            label = { Text(stringResource(R.string.mod_bulk_text)) },
            modifier = Modifier.fillMaxWidth(),
        )
        val sure = stringResource(R.string.mod_bulk_confirm, count)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { confirm = sure to { vm.bulk(UsersViewModel.Bulk.WARN, staff.uid, text) } }, enabled = !busy) { Text(stringResource(R.string.mod_warn)) }
            OutlinedButton(onClick = { confirm = sure to { vm.bulk(UsersViewModel.Bulk.BAN_24H, staff.uid, text) } }, enabled = !busy) {
                Text(stringResource(R.string.mod_bulk_ban_day), color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(onClick = { confirm = sure to { vm.bulk(UsersViewModel.Bulk.MUTE_24H, staff.uid) } }, enabled = !busy) { Text(stringResource(R.string.mod_bulk_mute_day)) }
            OutlinedButton(
                onClick = { confirm = sure to { vm.bulk(UsersViewModel.Bulk.MESSAGE, staff.uid, text) } },
                enabled = !busy && app.mawja.admin.core.ModerationActions.messageOk(text),
            ) { Text(stringResource(R.string.mod_send_message)) }
            OutlinedButton(onClick = { confirm = sure to { vm.bulk(UsersViewModel.Bulk.SIGN_OUT, staff.uid) } }, enabled = !busy) { Text(stringResource(R.string.mod_sign_out_all)) }
            TextButton(onClick = vm::clearPicks) { Text(stringResource(R.string.clear)) }
        }
    }
    confirm?.let { (t, action) ->
        ConfirmDialog(text = t, confirm = stringResource(R.string.confirm), danger = true, onConfirm = action, onDismiss = { confirm = null })
    }
}
