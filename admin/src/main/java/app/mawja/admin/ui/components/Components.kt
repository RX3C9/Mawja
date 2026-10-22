/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mawja.admin.R
import app.mawja.admin.ui.theme.Brand
import java.text.DateFormat
import java.util.Date

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun StatTile(
    label: String,
    value: Long?,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Card(
        modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(Modifier.size(width = 24.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(accent))
            Spacer(Modifier.height(10.dp))
            Text(value?.let { "%,d".format(it) } ?: "—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Simple bar chart (no chart library): one bar per value, today on the trailing end. */
@Composable
fun BarChart(
    values: List<Int>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.fillMaxWidth().height(96.dp)) {
        if (values.isEmpty()) return@Canvas
        val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (values.size - 1)) / values.size
        val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
        values.forEachIndexed { i, v ->
            val slot = if (rtl) values.size - 1 - i else i
            val x = slot * (w + gap)
            drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(6f, 6f))
            val h = size.height * v / max
            if (v > 0) drawRoundRect(color, Offset(x, size.height - h), Size(w, h), CornerRadius(6f, 6f))
        }
    }
}

@Composable
fun Pill(
    text: String,
    color: Color,
) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun TierPill(tier: String) = Pill(tierLabel(tier), Brand.tierColor(tier))

@Composable
fun StatusPill(status: String) =
    Pill(
        statusLabel(status),
        when (status) {
            "banned" -> Brand.Danger
            "suspended" -> Color(0xFFF76B15)
            else -> Brand.Success
        },
    )

@Composable
fun tierLabel(tier: String): String =
    when (tier) {
        "free" -> stringResource(R.string.tier_free)
        "plus" -> stringResource(R.string.tier_plus)
        "pro" -> stringResource(R.string.tier_pro)
        "family" -> stringResource(R.string.tier_family)
        "lifetime" -> stringResource(R.string.tier_lifetime)
        else -> tier
    }

@Composable
fun statusLabel(status: String): String =
    when (status) {
        "active" -> stringResource(R.string.status_active)
        "suspended" -> stringResource(R.string.status_suspended)
        "banned" -> stringResource(R.string.status_banned)
        else -> status
    }

@Composable
fun roleLabel(role: String): String =
    when (role) {
        "owner" -> stringResource(R.string.role_owner)
        "admin" -> stringResource(R.string.role_admin)
        "moderator" -> stringResource(R.string.role_moderator)
        "support" -> stringResource(R.string.role_support)
        "removed" -> stringResource(R.string.role_removed)
        else -> role
    }

@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Inbox, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(8.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun LoadMore(
    end: Boolean,
    busy: Boolean,
    onMore: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
        when {
            busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            !end -> OutlinedButton(onClick = onMore) { Text(stringResource(R.string.load_more)) }
        }
    }
}

@Composable
fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, content = content)
}

/** A label/value line; [copy] adds a copy button (for UIDs, emails, ids). */
@Composable
fun InfoRow(
    label: String,
    value: String?,
    copy: Boolean = false,
    mono: Boolean = false,
) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(
            value ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f),
        )
        if (copy && !value.isNullOrBlank()) {
            IconButton(onClick = { copyText(context, value, sensitive = false) }) {
                Icon(Icons.Rounded.ContentCopy, stringResource(R.string.copy), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    text: String,
    confirm: String,
    danger: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(confirm, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

fun day(date: Date?): String = date?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(it) } ?: "—"

fun dateTime(date: Date?): String = date?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(it) } ?: "—"

/** Copies text; [sensitive] hides it from the Android 13+ clipboard preview (codes). */
fun copyText(
    context: Context,
    text: String,
    sensitive: Boolean,
) {
    val clip = ClipData.newPlainText("Mawja Admin", text)
    if (sensitive && Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

fun shareText(
    context: Context,
    text: String,
    mime: String = "text/plain",
) {
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}
