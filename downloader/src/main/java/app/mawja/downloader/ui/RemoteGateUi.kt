/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.downloader.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.rounded.Close
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
import app.mawja.downloader.R
import app.mawja.downloader.remote.DownloaderRemoteConfig
import app.mawja.remote.DownloadBlock
import app.mawja.remote.RemoteGate

/**
 * What config/downloader asks of this build: a full-screen block when it is killed, under
 * maintenance or too old; otherwise [content] with a soft-update card and, when downloads are
 * switched off, a notice that new downloads are paused.
 */
@Composable
fun RemoteGateHost(content: @Composable (notices: @Composable () -> Unit) -> Unit) {
    val settings by DownloaderRemoteConfig.settings.collectAsStateWithLifecycle()
    val versionCode = DownloaderRemoteConfig.versionCode
    val gate = if (DownloaderRemoteConfig.enabled) settings.gate(versionCode) else RemoteGate.Open
    val language = LocalConfiguration.current.locales[0].language
    val context = LocalContext.current

    when (gate) {
        RemoteGate.Killed -> {
            BlockScreen(Icons.Outlined.Block, stringResource(R.string.remote_killed_title), stringResource(R.string.remote_killed_body))
            return
        }

        is RemoteGate.Maintenance -> {
            BlockScreen(
                Icons.Outlined.Construction,
                stringResource(R.string.remote_maintenance_title),
                gate.message(language).ifBlank { stringResource(R.string.remote_maintenance_body) },
            )
            return
        }

        is RemoteGate.ForceUpdate -> {
            BlockScreen(
                Icons.Outlined.SystemUpdate,
                stringResource(R.string.remote_force_update_title),
                stringResource(R.string.remote_force_update_body),
                actionLabel = stringResource(R.string.remote_update_button).takeIf { gate.updateUrl.startsWith("https://") },
                onAction = { openHttps(context, gate.updateUrl) },
            )
            return
        }

        RemoteGate.Open -> {}
    }

    var softUpdateHidden by rememberSaveable { mutableStateOf(false) }
    val downloadsOff = DownloaderRemoteConfig.enabled && DownloadBlock.of(settings, versionCode) == DownloadBlock.DOWNLOADS_DISABLED
    content {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (downloadsOff) {
                NoticeCard(Icons.Outlined.PauseCircle, stringResource(R.string.remote_downloads_paused_title), stringResource(R.string.remote_downloads_paused_body))
            }
            if (DownloaderRemoteConfig.enabled && !softUpdateHidden && settings.softUpdate(versionCode)) {
                val url = settings.updateUrl.trim().takeIf { it.startsWith("https://") }
                NoticeCard(
                    icon = Icons.Outlined.SystemUpdate,
                    title = stringResource(R.string.remote_soft_update_title),
                    body = stringResource(R.string.remote_soft_update_body),
                    actionLabel = url?.let { stringResource(R.string.remote_update_button) },
                    onAction = { url?.let { openHttps(context, it) } },
                    onDismiss = { softUpdateHidden = true },
                )
            }
        }
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
    BackHandler { (context as? Activity)?.finish() }
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
    onDismiss: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(22.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodyMedium)
                if (actionLabel != null) {
                    TextButton(onClick = onAction, modifier = Modifier.padding(top = 2.dp)) { Text(actionLabel) }
                }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.remote_dismiss)) }
            }
        }
    }
}

/** Only https:// leaves the app. */
private fun openHttps(
    context: Context,
    url: String,
) {
    val safe = url.trim()
    if (!safe.startsWith("https://")) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safe)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
