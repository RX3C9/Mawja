/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.screens.settings.account

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.android.R
import app.mawja.android.account.AccountError
import app.mawja.android.account.AccountState
import app.mawja.android.account.MawjaBadge
import app.mawja.android.account.MawjaProfile
import app.mawja.android.account.MawjaTier
import app.mawja.android.account.UsernameRules
import app.mawja.android.ui.components.settings.SettingsPage
import app.mawja.android.utils.copyPlainText
import kotlinx.coroutines.launch

private const val MIN_PASSWORD = 8

/**
 * The Mawja account page. Guests see what an account adds and the ways in (Google, sign in, create
 * account) with a clear note that guest mode keeps working; signed-in users see their Mawja ID,
 * username, membership and badges.
 */
@Composable
internal fun AccountScreen(
    onBack: (() -> Unit)?,
    onUpgrade: () -> Unit = {},
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val noticeText = ui.notice?.let { stringResource(noticeRes(it)) }
    LaunchedEffect(ui.notice) {
        if (noticeText != null) {
            snackbar.showSnackbar(noticeText)
            viewModel.dismissMessage()
        }
    }

    SettingsPage(
        title = stringResource(R.string.mawja_account_title),
        onBack = onBack,
        snackbarHostState = snackbar,
    ) {
        if (ui.busy) item("account.busy") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        when (val state = account) {
            is AccountState.SignedIn -> {
                item("account.profile") { ProfileCard(state.profile, viewModel) }
                ui.error?.let { error -> item("account.error") { ErrorText(error) } }
                if (state.profile.username == null) item("account.username") { UsernameClaim(viewModel, ui.busy) }
                item("account.upgrade") { UpgradeEntry(state.profile.tier, onUpgrade) }
                item("account.manage") { ManageSection(state.profile, viewModel, ui.busy) }
            }

            AccountState.Loading -> {
                item("account.loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }

            AccountState.Guest -> {
                item("account.guest") { GuestHero(viewModel.available) }
                if (viewModel.available) {
                    ui.error?.let { error -> item("account.error") { ErrorText(error) } }
                    when (ui.mode) {
                        AccountMode.WELCOME -> item("account.welcome") { WelcomeActions(viewModel, ui.busy) }
                        AccountMode.SIGN_IN -> item("account.signin") { SignInForm(viewModel, ui.busy) }
                        AccountMode.SIGN_UP -> item("account.signup") { SignUpForm(viewModel, ui.busy) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuestHero(available: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.account_guest_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                stringResource(if (available) R.string.account_guest_body else R.string.account_unavailable_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (available) {
                Benefit(Icons.Outlined.CloudSync, stringResource(R.string.account_benefit_sync))
                Benefit(Icons.Outlined.Badge, stringResource(R.string.account_benefit_id))
                Benefit(Icons.Outlined.WorkspacePremium, stringResource(R.string.account_benefit_tiers))
            }
        }
    }
}

@Composable
private fun Benefit(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun WelcomeActions(
    viewModel: AccountViewModel,
    busy: Boolean,
) {
    val activity = LocalContext.current.findActivity()
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (viewModel.googleAvailable && activity != null) {
            Button(onClick = { viewModel.signInWithGoogle(activity) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.account_continue_google))
            }
        }
        FilledTonalButton(onClick = { viewModel.show(AccountMode.SIGN_IN) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.account_sign_in))
        }
        OutlinedButton(onClick = { viewModel.show(AccountMode.SIGN_UP) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.account_create))
        }
        Text(
            stringResource(R.string.account_keep_guest),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SignInForm(
    viewModel: AccountViewModel,
    busy: Boolean,
) {
    var login by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = login,
            onValueChange = { login = it },
            label = { Text(stringResource(R.string.account_login_label)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.AlternateEmail, contentDescription = null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        PasswordField(password, { password = it }, R.string.account_password_label, null)
        Button(
            onClick = { viewModel.signIn(login, password) },
            enabled = !busy && login.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.account_sign_in)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { viewModel.sendPasswordReset(login) }, enabled = !busy && login.isNotBlank()) {
                Text(stringResource(R.string.account_forgot_password))
            }
            TextButton(onClick = { viewModel.show(AccountMode.WELCOME) }) { Text(stringResource(R.string.account_back)) }
        }
        TextButton(onClick = { viewModel.show(AccountMode.SIGN_UP) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.account_no_account))
        }
    }
}

@Composable
private fun SignUpForm(
    viewModel: AccountViewModel,
    busy: Boolean,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val usernameOk = username.isBlank() || UsernameRules.isValid(username)
    val ready =
        name.isNotBlank() && UsernameRules.isValid(username) && UsernameRules.looksLikeEmail(email) && password.length >= MIN_PASSWORD
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(60) },
            label = { Text(stringResource(R.string.account_display_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = UsernameRules.normalize(it).take(20) },
            label = { Text(stringResource(R.string.account_username_label)) },
            prefix = { Text("@") },
            singleLine = true,
            isError = !usernameOk,
            supportingText = { Text(stringResource(R.string.account_username_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it.trim() },
            label = { Text(stringResource(R.string.account_email_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        PasswordField(password, { password = it }, R.string.account_password_label, R.string.account_password_hint)
        Button(
            onClick = { viewModel.signUp(name, username, email, password) },
            enabled = !busy && ready,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.account_create)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { viewModel.show(AccountMode.SIGN_IN) }) { Text(stringResource(R.string.account_have_account)) }
            TextButton(onClick = { viewModel.show(AccountMode.WELCOME) }) { Text(stringResource(R.string.account_back)) }
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    hint: Int?,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        supportingText = if (hint != null) { { Text(stringResource(hint)) } } else null,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = null)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ProfileCard(
    profile: MawjaProfile,
    viewModel: AccountViewModel,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copyLabel = stringResource(R.string.account_mawja_id)
    var editing by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Avatar(profile)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            profile.displayName ?: profile.email ?: "",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (MawjaBadge.VERIFIED in profile.badges) {
                            Icon(
                                Icons.Outlined.CheckCircle,
                                contentDescription = stringResource(R.string.account_badge_verified),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    profile.username?.let {
                        Text("@$it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { editing = true }) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.account_edit_name))
                }
            }
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(copyLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(
                            profile.mawjaId ?: stringResource(R.string.account_mawja_id_pending),
                            style = if (profile.mawjaId != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                            fontWeight = if (profile.mawjaId != null) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                    profile.mawjaId?.let { id ->
                        IconButton(onClick = { scope.launch { clipboard.copyPlainText(copyLabel, id) } }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.account_copied))
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.account_tier) + " · " + stringResource(tierRes(profile.tier))) },
                    leadingIcon = { Icon(Icons.Outlined.WorkspacePremium, contentDescription = null, Modifier.size(18.dp)) },
                )
            }
            if (profile.badges.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    profile.badges.forEach { badge -> AssistChip(onClick = {}, label = { Text(stringResource(badgeRes(badge))) }) }
                }
            }
            if (profile.status != "active") {
                Text(
                    stringResource(R.string.account_status_limited),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    if (editing) {
        var name by rememberSaveable { mutableStateOf(profile.displayName.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.account_edit_name)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.account_display_name_label)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateDisplayName(name)
                    editing = false
                }, enabled = name.isNotBlank()) { Text(stringResource(R.string.account_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.account_back)) } },
        )
    }
}

@Composable
private fun Avatar(profile: MawjaProfile) {
    val initial = (profile.displayName ?: profile.username ?: profile.email ?: "M").trim().firstOrNull()?.uppercase() ?: "M"
    Box(
        Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun UsernameClaim(
    viewModel: AccountViewModel,
    busy: Boolean,
) {
    var username by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.account_choose_username), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = username,
            onValueChange = { username = UsernameRules.normalize(it).take(20) },
            prefix = { Text("@") },
            singleLine = true,
            isError = username.isNotBlank() && !UsernameRules.isValid(username),
            supportingText = { Text(stringResource(R.string.account_username_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(
            onClick = { viewModel.claimUsername(username) },
            enabled = !busy && UsernameRules.isValid(username),
            modifier = Modifier.align(Alignment.End),
        ) { Text(stringResource(R.string.account_save)) }
    }
}

@Composable
private fun ManageSection(
    profile: MawjaProfile,
    viewModel: AccountViewModel,
    busy: Boolean,
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        profile.email?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(onClick = { confirm = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.account_sign_out))
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            text = { Text(stringResource(R.string.account_sign_out_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    viewModel.signOut()
                }) { Text(stringResource(R.string.account_sign_out)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.account_back)) } },
        )
    }
}

@Composable
private fun ErrorText(error: AccountError) {
    Text(
        stringResource(errorRes(error)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
}

internal fun tierRes(tier: MawjaTier): Int =
    when (tier) {
        MawjaTier.FREE -> R.string.account_tier_free
        MawjaTier.PLUS -> R.string.account_tier_plus
        MawjaTier.PRO -> R.string.account_tier_pro
        MawjaTier.LIFETIME -> R.string.account_tier_lifetime
        MawjaTier.FAMILY -> R.string.account_tier_family
    }

private fun badgeRes(badge: MawjaBadge): Int =
    when (badge) {
        MawjaBadge.VERIFIED -> R.string.account_badge_verified
        MawjaBadge.CREATOR -> R.string.account_badge_creator
        MawjaBadge.FOUNDER -> R.string.account_badge_founder
        MawjaBadge.PRO -> R.string.account_badge_pro
        MawjaBadge.TEAM -> R.string.account_badge_team
    }

private fun noticeRes(notice: AccountNotice): Int =
    when (notice) {
        AccountNotice.RESET_SENT -> R.string.account_notice_reset
        AccountNotice.USERNAME_SAVED -> R.string.account_notice_username
        AccountNotice.NAME_SAVED -> R.string.account_notice_name
        AccountNotice.SIGNED_OUT -> R.string.account_notice_signed_out
        AccountNotice.VERIFY_EMAIL -> R.string.account_notice_verify
    }

private fun errorRes(error: AccountError): Int =
    when (error) {
        AccountError.NOT_CONFIGURED -> R.string.account_error_not_configured
        AccountError.INVALID_LOGIN -> R.string.account_error_invalid_login
        AccountError.UNKNOWN_USERNAME -> R.string.account_error_unknown_username
        AccountError.USERNAME_TAKEN -> R.string.account_error_username_taken
        AccountError.USERNAME_INVALID -> R.string.account_error_username_invalid
        AccountError.EMAIL_INVALID -> R.string.account_error_email_invalid
        AccountError.EMAIL_IN_USE -> R.string.account_error_email_in_use
        AccountError.WEAK_PASSWORD -> R.string.account_error_weak_password
        AccountError.TOO_MANY_ATTEMPTS -> R.string.account_error_too_many
        AccountError.NETWORK -> R.string.account_error_network
        AccountError.ACCOUNT_DISABLED -> R.string.account_error_disabled
        AccountError.CANCELLED, AccountError.UNKNOWN -> R.string.account_error_unknown
    }

internal tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@Composable
private fun UpgradeEntry(
    tier: MawjaTier,
    onUpgrade: () -> Unit,
) {
    val label = if (tier == MawjaTier.FREE) R.string.upgrade_cta else R.string.upgrade_cta_member
    FilledTonalButton(onClick = onUpgrade, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Icon(Icons.Outlined.WorkspacePremium, contentDescription = null, Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(label))
    }
}
