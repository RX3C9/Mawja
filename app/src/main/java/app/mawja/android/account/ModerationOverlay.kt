/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.account

import android.app.Activity
import android.content.Context
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.android.R
import kotlinx.coroutines.launch
import java.util.Date

/**
 * What staff set on the signed-in account, drawn over the whole app: a full-screen block while it
 * is banned or suspended (with when it ends and the reason), and the private message once.
 * Guests and active accounts see nothing.
 */
@Composable
fun ModerationOverlay(account: AccountBackend) {
    val state by account.state.collectAsStateWithLifecycle()
    val profile = (state as? AccountState.SignedIn)?.profile ?: return
    val moderation = profile.moderation
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val now = System.currentTimeMillis()

    if (moderation.blocked(now)) {
        BackHandler { (context as? Activity)?.finish() }
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Outlined.Block, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(if (moderation.status == Moderation.STATUS_BANNED) R.string.moderation_banned_title else R.string.moderation_suspended_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                val until = moderation.blockedUntil(now)
                Text(
                    if (until != null) {
                        stringResource(R.string.moderation_until, formatWhen(context, until))
                    } else {
                        stringResource(R.string.moderation_no_end)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                moderation.banReason?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.moderation_reason, it), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
                if (moderation.warnings > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        pluralStringResource(R.plurals.moderation_warnings, moderation.warnings, moderation.warnings),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = { scope.launch { account.signOut() } }) { Text(stringResource(R.string.moderation_sign_out)) }
            }
        }
        return
    }

    val prefs = remember { context.getSharedPreferences("moderation", Context.MODE_PRIVATE) }
    var seen by remember(profile.uid) { mutableStateOf(prefs.getString("seen_${profile.uid}", null)) }
    val message = moderation.unseenMessage(seen) ?: return
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Outlined.MarkEmailUnread, null) },
        title = { Text(stringResource(R.string.moderation_message_title)) },
        text = { Text(message.text) },
        confirmButton = {
            TextButton(onClick = {
                prefs.edit { putString("seen_${profile.uid}", message.id) }
                seen = message.id
            }) { Text(stringResource(R.string.moderation_message_ok)) }
        },
    )
}

private fun formatWhen(
    context: Context,
    at: Long,
): String {
    val d = Date(at)
    return "${DateFormat.getMediumDateFormat(context).format(d)} ${DateFormat.getTimeFormat(context).format(d)}"
}
