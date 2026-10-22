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
            Box(Modifier.weight(0.45f).fillMaxHeight()) { UserList(vm, busy) }
            VerticalDivider()
            Box(Modifier.weight(0.55f).fillMaxHeight()) {
                if (selected != null) UserDetail(staff, selected, vm, busy, showBack = false) else EmptyState(stringResource(R.string.pick_user))
            }
        }
    } else if (selected != null) {
        UserDetail(staff, selected, vm, busy, showBack = true)
    } else {
        UserList(vm, busy)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserList(
    vm: UsersViewModel,
    busy: Boolean,
) {
    val state by vm.state.collectAsStateWithLifecycle()
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
        if (state.searched) {
            item { Text(stringResource(R.string.search_results), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 8.dp)) }
            if (state.results.isEmpty()) item { EmptyState(stringResource(R.string.not_found)) }
            items(state.results, key = { "s_" + it.uid }) { UserRow(it) { vm.open(it.uid) } }
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
        items(state.list, key = { "l_" + it.uid }) { UserRow(it) { vm.open(it.uid) } }
        if (state.list.isNotEmpty()) item { LoadMore(state.end, busy, vm::more) }
    }
}

@Composable
private fun UserRow(
    u: UserRecord,
    onClick: () -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
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
