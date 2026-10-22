/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.admin.BuildConfig
import app.mawja.admin.R
import app.mawja.admin.core.RemoteConfig
import app.mawja.admin.data.Staff
import app.mawja.admin.ui.Dest
import app.mawja.admin.ui.SessionViewModel
import app.mawja.admin.ui.components.Centered
import app.mawja.admin.ui.components.InfoRow
import app.mawja.admin.ui.components.ScreenHeader
import app.mawja.admin.ui.components.SectionCard
import app.mawja.admin.ui.components.copyText
import app.mawja.admin.ui.components.roleLabel
import java.util.Locale

@Composable
fun SignInScreen(
    session: SessionViewModel,
    busy: Boolean,
) {
    val context = LocalContext.current
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Rounded.Waves, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.sign_in_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                email,
                { email = it },
                label = { Text(stringResource(R.string.email)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                password,
                { password = it },
                label = { Text(stringResource(R.string.password)) },
                singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(
                onClick = { session.signIn(email, password) },
                enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text(stringResource(R.string.sign_in)) }
            if (session.googleAvailable) {
                OutlinedButton(
                    onClick = { (context as? Activity)?.let(session::signInWithGoogle) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Rounded.AccountCircle, null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.sign_in_google))
                }
            }
            TextButton(onClick = { session.resetPassword(email) }, enabled = !busy && '@' in email, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.forgot_password))
            }
        }
    }
}

@Composable
fun NotAuthorizedScreen(
    uid: String,
    email: String?,
    busy: Boolean,
    onRecheck: () -> Unit,
    onSignOut: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(56.dp))
        Text(stringResource(R.string.not_authorized_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(stringResource(R.string.not_authorized_body, email ?: "—"), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionCard(Modifier.widthIn(max = 480.dp), title = stringResource(R.string.your_uid)) {
            Text(uid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = { copyText(context, uid, sensitive = false) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.ContentCopy, null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.copy_uid))
            }
            Text(stringResource(R.string.owner_setup_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (busy) LinearProgressIndicator(Modifier.widthIn(max = 320.dp).fillMaxWidth())
        OutlinedButton(onClick = onRecheck, enabled = !busy) { Text(stringResource(R.string.check_again)) }
        TextButton(onClick = onSignOut) { Text(stringResource(R.string.sign_out)) }
    }
}

@Composable
fun BlockedScreen(
    config: RemoteConfig?,
    onSignOut: () -> Unit,
) {
    val ar = Locale.getDefault().language == "ar"
    val message = config?.let { if (ar && it.maintenanceMessageAr.isNotBlank()) it.maintenanceMessageAr else it.maintenanceMessage }.orEmpty()
    Centered {
        Icon(Icons.Rounded.Block, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.admin_blocked), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (message.isNotBlank()) Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onSignOut) { Text(stringResource(R.string.sign_out)) }
    }
}

@Composable
fun MoreScreen(
    items: List<Dest>,
    open: (Dest) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(stringResource(R.string.nav_more))
        items.forEach { d ->
            ListItem(
                leadingContent = { Icon(d.icon, null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(stringResource(d.label)) },
                trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                modifier = Modifier.clickable { open(d) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    staff: Staff,
    session: SessionViewModel,
) {
    val context = LocalContext.current
    val theme by session.theme.collectAsStateWithLifecycle()
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenHeader(stringResource(R.string.nav_settings))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(title = stringResource(R.string.language)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("" to R.string.follow_system, "ar" to R.string.lang_ar, "en" to R.string.lang_en).forEach { (tag, label) ->
                        FilterChip(
                            selected = current == tag || (tag.isNotEmpty() && current.startsWith(tag)),
                            onClick = { AppCompatDelegate.setApplicationLocales(if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)) },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
            }
            SectionCard(title = stringResource(R.string.theme)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to R.string.follow_system, "light" to R.string.theme_light, "dark" to R.string.theme_dark).forEach { (value, label) ->
                        FilterChip(selected = theme == value, onClick = { session.setTheme(value) }, label = { Text(stringResource(label)) })
                    }
                }
            }
            SectionCard(title = stringResource(R.string.account)) {
                InfoRow(stringResource(R.string.email), staff.email, copy = true)
                InfoRow(stringResource(R.string.uid), staff.uid, copy = true, mono = true)
                InfoRow(stringResource(R.string.role), roleLabel(staff.role.id))
                InfoRow(stringResource(R.string.role_source), stringResource(if (staff.viaClaim) R.string.role_source_claim else R.string.role_source_doc))
                HorizontalDivider()
                Text(stringResource(R.string.security_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = session::signOut, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.sign_out))
                }
            }
            SectionCard(title = stringResource(R.string.about)) {
                InfoRow(stringResource(R.string.version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.license_credit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.SOURCE_URL))) }) {
                    Text(stringResource(R.string.source_code))
                }
            }
        }
    }
}
