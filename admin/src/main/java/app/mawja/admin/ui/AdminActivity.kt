/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.admin.R
import app.mawja.admin.core.Perm
import app.mawja.admin.core.Permissions
import app.mawja.admin.core.StaffRole
import app.mawja.admin.data.AdminServices
import app.mawja.admin.data.Problem
import app.mawja.admin.data.Session
import app.mawja.admin.data.Staff
import app.mawja.admin.ui.components.Centered
import app.mawja.admin.ui.screens.AnnouncementsScreen
import app.mawja.admin.ui.screens.AuditScreen
import app.mawja.admin.ui.screens.BlockedScreen
import app.mawja.admin.ui.screens.CodesScreen
import app.mawja.admin.ui.screens.ConfigScreen
import app.mawja.admin.ui.screens.DashboardScreen
import app.mawja.admin.ui.screens.MoreScreen
import app.mawja.admin.ui.screens.NotAuthorizedScreen
import app.mawja.admin.ui.screens.ReportsScreen
import app.mawja.admin.ui.screens.SettingsScreen
import app.mawja.admin.ui.screens.SignInScreen
import app.mawja.admin.ui.screens.StaffScreen
import app.mawja.admin.ui.screens.UsersScreen
import app.mawja.admin.ui.theme.AdminTheme

/**
 * Mawja Admin. Locked behind the device biometric or screen lock every time it comes back from the
 * background, screenshots and the recents preview are blocked, and every write is checked by role
 * in the Firestore rules (and audited).
 */
class AdminActivity : AppCompatActivity() {
    private val session: SessionViewModel by viewModels()
    private var unlocked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        unlocked = session.unlocked
        setContent {
            val theme by session.theme.collectAsStateWithLifecycle()
            AdminTheme(theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (!unlocked) LockScreen(onUnlock = ::promptUnlock) else AdminRoot(session)
                }
            }
        }
        if (!unlocked) promptUnlock()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            session.unlocked = false
            unlocked = false
        }
    }

    override fun onRestart() {
        super.onRestart()
        if (!unlocked) promptUnlock()
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
                        session.unlocked = true
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

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Centered {
        Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.locked_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onUnlock) { Text(stringResource(R.string.unlock_again)) }
    }
}

enum class Dest(
    val label: Int,
    val icon: ImageVector,
    val perm: Perm?,
) {
    DASHBOARD(R.string.nav_dashboard, Icons.Rounded.Dashboard, null),
    USERS(R.string.nav_users, Icons.Rounded.People, Perm.USERS_VIEW),
    CODES(R.string.nav_codes, Icons.Rounded.ConfirmationNumber, Perm.CODES_VIEW),
    REPORTS(R.string.nav_reports, Icons.Rounded.Flag, Perm.REPORTS_HANDLE),
    ANNOUNCEMENTS(R.string.nav_announcements, Icons.Rounded.Campaign, Perm.ANNOUNCE_VIEW),
    CONFIG(R.string.nav_config, Icons.Rounded.Tune, Perm.CONFIG_VIEW),
    AUDIT(R.string.nav_audit, Icons.Rounded.History, Perm.AUDIT_VIEW),
    STAFF(R.string.nav_staff, Icons.Rounded.AdminPanelSettings, Perm.STAFF_VIEW),
    SETTINGS(R.string.nav_settings, Icons.Rounded.Settings, null),
    MORE(R.string.nav_more, Icons.Rounded.MoreHoriz, null),
    ;

    companion object {
        val PHONE_BAR = listOf(DASHBOARD, USERS, CODES, REPORTS, MORE)
        val IN_MORE = listOf(ANNOUNCEMENTS, CONFIG, AUDIT, STAFF, SETTINGS)
        val RAIL = entries.filter { it != MORE }
    }
}

@Composable
private fun AdminRoot(session: SessionViewModel) {
    val state by session.session.collectAsStateWithLifecycle()
    val busy by session.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val services = remember { AdminServices.get(context) }
    val messages =
        mapOf(
            Problem.DENIED to stringResource(R.string.err_denied),
            Problem.OFFLINE to stringResource(R.string.err_offline),
            Problem.NOT_FOUND to stringResource(R.string.err_not_found),
            Problem.INDEX to stringResource(R.string.err_index),
            Problem.INVALID to stringResource(R.string.err_invalid),
            Problem.UNKNOWN to stringResource(R.string.err_unknown),
        )
    LaunchedEffect(Unit) {
        services.notices.collect { n ->
            val text =
                when {
                    n.text != null -> context.getString(n.text)
                    n.problem != null -> messages.getValue(n.problem) + (n.detail?.takeIf { n.problem != Problem.DENIED }?.let { "\n$it" } ?: "")
                    else -> n.detail.orEmpty()
                }
            if (text.isNotBlank()) snackbar.showSnackbar(text)
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0)) { padding ->
        val insets = if (state is Session.Active) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
        Box(Modifier.padding(padding).fillMaxSize().then(insets)) {
            when (val s = state) {
                Session.NotConfigured -> Centered { Text(stringResource(R.string.not_configured)) }
                Session.Loading -> Centered { LinearProgressIndicator(Modifier.fillMaxWidth(0.5f)) }
                Session.SignedOut -> SignInScreen(session, busy)
                is Session.NotStaff -> NotAuthorizedScreen(s.uid, s.email, busy, onRecheck = session::recheck, onSignOut = session::signOut)
                is Session.Active -> {
                    val cfg by session.adminConfig.collectAsStateWithLifecycle()
                    if (s.staff.role != StaffRole.OWNER && session.blocked(cfg)) {
                        BlockedScreen(cfg, onSignOut = session::signOut)
                    } else {
                        Console(s.staff, session)
                    }
                }
            }
        }
    }
}

@Composable
private fun Console(
    staff: Staff,
    session: SessionViewModel,
) {
    var dest by rememberSaveable { mutableStateOf(Dest.DASHBOARD) }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val allowed: (Dest) -> Boolean = { d -> d.perm == null || Permissions.can(staff.role, d.perm) }
    val inMore = dest in Dest.IN_MORE
    BackHandler(enabled = dest != Dest.DASHBOARD) {
        dest = if (inMore && !wide) Dest.MORE else Dest.DASHBOARD
    }
    val open: (Dest) -> Unit = { dest = it }
    val content: @Composable () -> Unit = {
        when (if (wide && dest == Dest.MORE) Dest.DASHBOARD else dest) {
            Dest.DASHBOARD -> DashboardScreen(staff, open)
            Dest.USERS -> UsersScreen(staff)
            Dest.CODES -> CodesScreen(staff)
            Dest.REPORTS -> ReportsScreen(staff, onOpenUser = { dest = Dest.USERS })
            Dest.ANNOUNCEMENTS -> AnnouncementsScreen(staff)
            Dest.CONFIG -> ConfigScreen(staff)
            Dest.AUDIT -> AuditScreen()
            Dest.STAFF -> StaffScreen(staff)
            Dest.SETTINGS -> SettingsScreen(staff, session)
            Dest.MORE -> MoreScreen(Dest.IN_MORE.filter(allowed), open)
        }
    }
    if (wide) {
        Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Spacer(Modifier.height(8.dp))
                Dest.RAIL.filter(allowed).forEach { d ->
                    NavigationRailItem(
                        selected = dest == d,
                        onClick = { dest = d },
                        icon = { Icon(d.icon, null) },
                        label = { Text(stringResource(d.label), maxLines = 1) },
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Dest.PHONE_BAR.filter(allowed).forEach { d ->
                        NavigationBarItem(
                            selected = dest == d || (d == Dest.MORE && inMore),
                            onClick = { dest = d },
                            icon = { Icon(d.icon, null) },
                            label = { Text(stringResource(d.label), maxLines = 1) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) { content() }
        }
    }
}
