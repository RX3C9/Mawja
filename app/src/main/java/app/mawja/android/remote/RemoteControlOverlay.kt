/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.android.R
import app.mawja.android.account.EntitlementsRepository
import app.mawja.remote.Announcement
import app.mawja.remote.AnnouncementFilter
import app.mawja.remote.RemoteGate

/**
 * What config/app asks of this build, drawn over the whole app: a full-screen block when it is
 * killed, under maintenance or too old; otherwise a soft-update banner and the announcements meant
 * for this user (banner/news as a dismissible card, dialog as a dialog). Draws nothing when the
 * remote config is off (foss, no Firebase project) or has nothing to say.
 */
@Composable
fun RemoteControlOverlay(
    remote: RemoteConfigRepository,
    entitlements: EntitlementsRepository,
) {
    if (!remote.enabled) return
    val gate by remote.gate.collectAsStateWithLifecycle()
    val settings by remote.settings.collectAsStateWithLifecycle()
    val all by remote.announcements.collectAsStateWithLifecycle()
    val dismissed by remote.dismissed.collectAsStateWithLifecycle()
    val tier by entitlements.entitlements.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language

    when (val g = gate) {
        RemoteGate.Killed -> {
            BlockScreen(Icons.Outlined.Block, stringResource(R.string.remote_killed_title), stringResource(R.string.remote_killed_body))
            return
        }

        is RemoteGate.Maintenance -> {
            BlockScreen(
                Icons.Outlined.Construction,
                stringResource(R.string.remote_maintenance_title),
                g.message(language).ifBlank { stringResource(R.string.remote_maintenance_body) },
            )
            return
        }

        is RemoteGate.ForceUpdate -> {
            BlockScreen(
                Icons.Outlined.SystemUpdate,
                stringResource(R.string.remote_force_update_title),
                stringResource(R.string.remote_force_update_body),
                actionLabel = stringResource(R.string.remote_update_button).takeIf { g.updateUrl.isNotBlank() },
                onAction = { openLink(context, g.updateUrl) },
            )
            return
        }

        RemoteGate.Open -> {}
    }

    var softUpdateHidden by rememberSaveable { mutableStateOf(false) }
    val visible =
        if (settings.isEnabled("announcements")) {
            AnnouncementFilter.visible(all, System.currentTimeMillis(), tier.tier.id, language, dismissed)
        } else {
            emptyList()
        }
    val dialog = visible.firstOrNull { it.kind == Announcement.KIND_DIALOG }
    val card = visible.firstOrNull { it.kind != Announcement.KIND_DIALOG }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!softUpdateHidden && settings.softUpdate(remote.versionCode)) {
                val url = settings.updateUrl.trim().takeIf { it.startsWith("https://") }
                NoticeCard(
                    icon = Icons.Outlined.SystemUpdate,
                    title = stringResource(R.string.remote_soft_update_title),
                    body = stringResource(R.string.remote_soft_update_body),
                    actionLabel = url?.let { stringResource(R.string.remote_update_button) },
                    onAction = { url?.let { openLink(context, it) } },
                    onDismiss = { softUpdateHidden = true },
                )
            }
            card?.let { a ->
                NoticeCard(
                    icon = Icons.Outlined.Campaign,
                    label = if (a.kind == Announcement.KIND_NEWS) stringResource(R.string.remote_news_label) else null,
                    title = a.title,
                    body = a.body,
                    actionLabel = a.ctaLabel.takeIf { a.hasCta },
                    onAction = {
                        openLink(context, a.ctaUrl)
                        remote.dismiss(a.id)
                    },
                    onDismiss = { remote.dismiss(a.id) },
                )
            }
        }
    }

    dialog?.let { a ->
        AlertDialog(
            onDismissRequest = { remote.dismiss(a.id) },
            icon = { Icon(Icons.Outlined.Campaign, contentDescription = null) },
            title = { Text(a.title) },
            text = { Text(a.body) },
            confirmButton = {
                if (a.hasCta) {
                    TextButton(onClick = {
                        openLink(context, a.ctaUrl)
                        remote.dismiss(a.id)
                    }) { Text(a.ctaLabel) }
                } else {
                    TextButton(onClick = { remote.dismiss(a.id) }) { Text(stringResource(R.string.remote_ok)) }
                }
            },
            dismissButton =
                if (a.hasCta) {
                    { TextButton(onClick = { remote.dismiss(a.id) }) { Text(stringResource(R.string.remote_dismiss)) } }
                } else {
                    null
                },
        )
    }
}

@Composable
private fun BlockScreen(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val context = LocalContext.current
    // Back leaves the app instead of navigating the screens hidden underneath.
    BackHandler { (context as? Activity)?.finish() }
    // A non-clickable Material 3 Surface swallows touches, so nothing underneath can be used.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(20.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            if (actionLabel != null) {
                Spacer(Modifier.height(24.dp))
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun NoticeCard(
    icon: ImageVector,
    title: String,
    body: String,
    onDismiss: () -> Unit,
    label: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(22.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                label?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodyMedium)
                if (actionLabel != null) {
                    TextButton(onClick = onAction, modifier = Modifier.padding(top = 2.dp)) { Text(actionLabel) }
                }
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.remote_dismiss)) }
        }
    }
}

/** https:// opens in the browser; mawja:// stays inside the app. Anything else is ignored. */
private fun openLink(
    context: Context,
    url: String,
) {
    if (!Announcement.isSafeCta(url)) return
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (url.startsWith("mawja://")) intent.setPackage(context.packageName)
        context.startActivity(intent)
    }
}
