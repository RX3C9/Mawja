/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.admin.R

/**
 * Mawja Admin. Locked behind the device biometric or screen lock every time it is opened,
 * screenshots are blocked, and every action is checked by role on the server.
 */
class AdminActivity : FragmentActivity() {
    private val vm: AdminViewModel by viewModels()
    private var unlocked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            AdminTheme {
                if (!unlocked) LockScreen(onUnlock = ::promptUnlock) else AdminApp(vm)
            }
        }
        promptUnlock()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) unlocked = false
    }

    override fun onRestart() {
        super.onRestart()
        promptUnlock()
    }

    private fun promptUnlock() {
        val authenticators =
            // API 28–29 cannot pair a strong biometric with the screen lock, so fall back to weak there.
            if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        val prompt =
            BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        unlocked = true
                    }
                },
            )
        prompt.authenticate(
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle(getString(R.string.unlock_title))
                .setSubtitle(getString(R.string.unlock_subtitle))
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }
}

private val Gold = Color(0xFFF2C14E)

@Composable
private fun AdminTheme(content: @Composable () -> Unit) {
    val scheme =
        if (isSystemInDarkTheme()) {
            darkColorScheme(primary = Gold, onPrimary = Color(0xFF1A1A1A), background = Color(0xFF0F0F0F), surface = Color(0xFF0F0F0F))
        } else {
            lightColorScheme(primary = Color(0xFF8A6A12))
        }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onUnlock) { Text(stringResource(R.string.unlock_again)) }
    }
}

private enum class Tab(
    val label: Int,
    val icon: ImageVector,
) {
    USERS(R.string.users, Icons.Rounded.People),
    FEATURES(R.string.features, Icons.Rounded.ToggleOn),
    ANNOUNCE(R.string.announce, Icons.Rounded.Campaign),
    REPORTS(R.string.reports, Icons.Rounded.Flag),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminApp(vm: AdminViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val done = stringResource(R.string.done)
    LaunchedEffect(state.message, state.error) {
        val text = state.error ?: state.message?.let { done }
        if (text != null) {
            snackbar.showSnackbar(text)
            vm.consumeMessage()
        }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val staff = state.staff

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.app_name))
                        staff?.let { Text(stringResource(R.string.role_label, it.role), style = MaterialTheme.typography.labelSmall) }
                    }
                },
                actions = {
                    if (staff != null) {
                        IconButton(onClick = vm::signOut) {
                            Icon(Icons.AutoMirrored.Rounded.Logout, stringResource(R.string.sign_out))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (staff != null) {
                NavigationBar {
                    Tab.entries.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(t.icon, null) },
                            label = { Text(stringResource(t.label)) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                !state.configured -> Centered(stringResource(R.string.not_configured))
                staff == null -> SignIn(state.notStaff, vm::signIn)
                else ->
                    when (Tab.entries[tab]) {
                        Tab.USERS -> UsersTab(state, vm)
                        Tab.FEATURES -> FeaturesTab(state, vm)
                        Tab.ANNOUNCE -> AnnounceTab(vm)
                        Tab.REPORTS -> ReportsTab(state, vm)
                    }
            }
        }
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text(text) }
}

@Composable
private fun SignIn(
    notStaff: Boolean,
    onSignIn: (String, String) -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
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
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (notStaff) Text(stringResource(R.string.not_staff), color = MaterialTheme.colorScheme.error)
        Button(onClick = { onSignIn(email, password) }, enabled = email.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sign_in))
        }
    }
}

private val TIERS = listOf("free", "plus", "pro", "lifetime", "family")
private val BADGES = listOf("verified", "creator", "founder", "pro", "team")

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun UsersTab(
    state: AdminState,
    vm: AdminViewModel,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.lookup_hint)) }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.lookup(query) }, enabled = query.isNotBlank()) { Text(stringResource(R.string.lookup)) }
        }
        if (state.userNotFound) Text(stringResource(R.string.not_found))
        state.user?.let { user ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(user.displayName ?: user.username ?: user.uid, style = MaterialTheme.typography.titleMedium)
                    Text(listOfNotNull(user.mawjaId, user.username?.let { "@$it" }, user.email).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    Text("${user.tier} · ${user.status}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.userAction(mapOf("action" to "user.suspend", "uid" to user.uid)) }) { Text(stringResource(R.string.suspend)) }
                        OutlinedButton(onClick = { vm.userAction(mapOf("action" to "user.ban", "uid" to user.uid)) }) { Text(stringResource(R.string.ban)) }
                        OutlinedButton(onClick = { vm.userAction(mapOf("action" to "user.unban", "uid" to user.uid)) }) { Text(stringResource(R.string.unban)) }
                    }
                    Text(stringResource(R.string.set_tier), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TIERS.forEach { tier ->
                            FilterChip(
                                selected = user.tier == tier,
                                onClick = { vm.userAction(mapOf("action" to "user.tier", "uid" to user.uid, "tier" to tier)) },
                                label = { Text(tier) },
                            )
                        }
                    }
                    Text(stringResource(R.string.badge), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BADGES.forEach { badge ->
                            val has = badge in user.badges
                            FilterChip(
                                selected = has,
                                onClick = { vm.userAction(mapOf("action" to "user.badge", "uid" to user.uid, "badge" to badge, "grant" to !has)) },
                                label = { Text(badge) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeaturesTab(
    state: AdminState,
    vm: AdminViewModel,
) {
    LaunchedEffect(Unit) { vm.loadFeatures() }
    var newKey by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newKey, { newKey = it.trim() }, label = { Text(stringResource(R.string.feature_key)) }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    vm.toggleFeature(newKey, true)
                    newKey = ""
                }, enabled = newKey.isNotBlank()) { Text(stringResource(R.string.apply)) }
            }
        }
        if (state.features.isEmpty()) item { Centered(stringResource(R.string.nothing)) }
        items(state.features.entries.toList(), key = { it.key }) { (key, enabled) ->
            ListItem(
                headlineContent = { Text(key) },
                trailingContent = { Switch(checked = enabled, onCheckedChange = { vm.toggleFeature(key, it) }) },
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AnnounceTab(vm: AdminViewModel) {
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(body, { body = it }, label = { Text(stringResource(R.string.body)) }, minLines = 4, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = target == null, onClick = { target = null }, label = { Text("all") })
            TIERS.forEach { tier -> FilterChip(selected = target == tier, onClick = { target = tier }, label = { Text(tier) }) }
        }
        Button(
            onClick = {
                vm.announce(title, body, target)
                title = ""
                body = ""
            },
            enabled = title.isNotBlank() && body.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.send)) }
    }
}

@Composable
private fun ReportsTab(
    state: AdminState,
    vm: AdminViewModel,
) {
    LaunchedEffect(Unit) { vm.loadReports() }
    if (state.reports.isEmpty()) {
        Centered(stringResource(R.string.nothing))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.reports, key = { it.id }) { report ->
            ListItem(
                overlineContent = { Text(report.type) },
                headlineContent = { Text(report.message, maxLines = 4) },
                trailingContent = { TextButton(onClick = { vm.resolveReport(report.id) }) { Text(stringResource(R.string.resolve)) } },
            )
        }
    }
}
