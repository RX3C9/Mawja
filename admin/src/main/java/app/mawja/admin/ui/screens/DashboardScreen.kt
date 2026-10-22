/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mawja.admin.R
import app.mawja.admin.data.Staff
import app.mawja.admin.ui.DashboardViewModel
import app.mawja.admin.ui.Dest
import app.mawja.admin.ui.components.BarChart
import app.mawja.admin.ui.components.ScreenHeader
import app.mawja.admin.ui.components.SectionCard
import app.mawja.admin.ui.components.StatTile
import app.mawja.admin.ui.components.TierPill
import app.mawja.admin.ui.components.dateTime
import app.mawja.admin.ui.components.day
import app.mawja.admin.ui.components.roleLabel
import app.mawja.admin.ui.components.tierLabel
import app.mawja.admin.ui.theme.Brand

@Composable
fun DashboardScreen(
    staff: Staff,
    open: (Dest) -> Unit,
    vm: DashboardViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }
    val data by vm.data.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val columns = if (LocalConfiguration.current.screenWidthDp >= 840) 4 else 2
    val d = data
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenHeader(
            title = stringResource(R.string.hello, staff.name?.substringBefore(' ') ?: staff.email?.substringBefore('@') ?: ""),
            subtitle = stringResource(R.string.dashboard_subtitle, roleLabel(staff.role.id), dateTime(d?.loadedAt)),
        ) {
            IconButton(onClick = { vm.load(force = true) }, enabled = !busy) { Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh)) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val tiles =
                listOf(
                    Triple(stringResource(R.string.stat_users), d?.users, Brand.Teal),
                    Triple(stringResource(R.string.stat_active_timed), d?.activeTimed, Brand.Gold),
                    Triple(stringResource(R.string.stat_open_reports), d?.openReports, Brand.Danger),
                    Triple(stringResource(R.string.stat_staff), d?.staff, MaterialTheme.colorScheme.secondary),
                    Triple(stringResource(R.string.stat_codes_7), d?.codes7, Color(0xFF3E8EF7)),
                    Triple(stringResource(R.string.stat_codes_30), d?.codes30, Color(0xFF3E8EF7)),
                    Triple(stringResource(R.string.stat_used_7), d?.used7, Brand.Success),
                    Triple(stringResource(R.string.stat_used_30), d?.used30, Brand.Success),
                )
            tiles.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (label, value, color) -> StatTile(label, value, Modifier.weight(1f), color) }
                    repeat(columns - row.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
                }
            }
            SectionCard(title = stringResource(R.string.members_by_tier)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("plus", "pro", "family", "lifetime").forEach { t ->
                        StatTile(tierLabel(t), d?.tierCounts?.get(t), Modifier.weight(1f), Brand.tierColor(t))
                    }
                }
                Text(
                    stringResource(R.string.members_note, (d?.banned ?: 0L).toString(), (d?.suspended ?: 0L).toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val wide = columns == 4
            val charts: @Composable (Modifier) -> Unit = { m ->
                SectionCard(m, title = stringResource(R.string.chart_activations)) {
                    BarChart(d?.activationsByDay.orEmpty(), color = Brand.Success)
                    Text(stringResource(R.string.chart_total, d?.activationsByDay?.sum() ?: 0), style = MaterialTheme.typography.labelMedium)
                }
                SectionCard(m, title = stringResource(R.string.chart_codes)) {
                    BarChart(d?.codesByDay.orEmpty(), color = Color(0xFF3E8EF7))
                    Text(stringResource(R.string.chart_total, d?.codesByDay?.sum() ?: 0), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { charts(Modifier.weight(1f)) }
            } else {
                charts(Modifier)
            }
            SectionCard(title = stringResource(R.string.recent_activations)) {
                val recent = d?.recentActivations.orEmpty()
                if (recent.isEmpty()) Text(stringResource(R.string.nothing), color = MaterialTheme.colorScheme.onSurfaceVariant)
                recent.forEach { r ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(r.uid, maxLines = 1) },
                        supportingContent = { Text(stringResource(R.string.until_date, day(r.tierUntil))) },
                        leadingContent = { TierPill(r.tier) },
                        trailingContent = { Text(dateTime(r.createdAt), style = MaterialTheme.typography.labelSmall) },
                    )
                }
                Text(
                    stringResource(R.string.open_codes),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { open(Dest.CODES) }.padding(vertical = 4.dp),
                )
            }
            val audit = d?.recentAudit.orEmpty()
            if (audit.isNotEmpty()) {
                SectionCard(title = stringResource(R.string.recent_audit)) {
                    audit.forEach { a ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.action, style = MaterialTheme.typography.bodyMedium)
                                Text(a.target, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Text(dateTime(a.at), style = MaterialTheme.typography.labelSmall, modifier = Modifier.widthIn(max = 140.dp))
                        }
                    }
                    Text(
                        stringResource(R.string.open_audit),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { open(Dest.AUDIT) }.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}
