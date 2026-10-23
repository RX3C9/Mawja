package app.mawja.android.ui.screens.settings.network

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.NetworkPing
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.android.R
import app.mawja.android.network.AppProxyType
import app.mawja.android.network.ConnectionProfile
import app.mawja.android.network.ConnectionProfilesCodec
import app.mawja.android.network.ConnectionTestResult
import app.mawja.android.network.DnsProvider
import app.mawja.android.ui.components.settings.nav
import app.mawja.android.ui.components.shared.FlowAlertDialog
import app.mawja.android.network.NetworkProfile
import app.mawja.android.network.SmartNetwork
import app.mawja.android.ui.components.settings.SettingEntry
import app.mawja.android.ui.components.settings.SettingsGroupScope
import app.mawja.android.ui.components.settings.SettingsPage
import app.mawja.android.ui.components.settings.info
import app.mawja.android.ui.components.settings.notice
import app.mawja.android.ui.components.settings.option
import app.mawja.android.ui.components.settings.switch
import app.mawja.android.ui.components.settings.toggleGroup
import app.mawja.android.ui.components.shared.FlowToggleOption
import app.mawja.android.ui.screens.settings.index.NetworkIndex
import app.mawja.android.ui.utils.rememberIsOnWifi
import app.mawja.android.utils.NetworkState
import kotlinx.coroutines.launch

private val FieldPadding = 12.dp

/** The optional proxy for remote traffic, edited as a form and applied on Save. */
@Composable
internal fun NetworkSettingsScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    viewModel: NetworkSettingsViewModel = hiltViewModel(),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val pausedByVpn by viewModel.pausedByVpn.collectAsStateWithLifecycle()
    val dnsDraft by viewModel.dnsDraft.collectAsStateWithLifecycle()
    val dnsSaved by viewModel.dnsSaved.collectAsStateWithLifecycle()
    val dnsUrlError = stringResource(R.string.dns_custom_url_invalid)
    val remoteEnabled by viewModel.remoteEnabled.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val testState by viewModel.test.collectAsStateWithLifecycle()
    val testStatus = testStatusText(testState)
    val directLabel = stringResource(R.string.network_profile_direct)
    val profileAppliedMessage = stringResource(R.string.network_profile_applied)
    var naming by rememberSaveable { mutableStateOf(false) }
    var openProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    val dnsLabels = DnsProvider.entries.associateWith { stringResource(dnsLabelRes(it)) }
    val dnsSummaries = DnsProvider.entries.associateWith { p -> dnsSummaryRes(p)?.let { stringResource(it) } }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.network_settings_saved)
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val types =
        listOf(
            FlowToggleOption(AppProxyType.HTTP, stringResource(R.string.proxy_type_http)),
            FlowToggleOption(AppProxyType.SOCKS5, stringResource(R.string.proxy_type_socks5)),
        )
    val hostError = stringResource(R.string.proxy_settings_invalid_host)
    val portError = stringResource(R.string.proxy_settings_invalid_port)
    val passwordToggle = stringResource(if (passwordVisible) R.string.settings_hide_password else R.string.settings_show_password)
    val context = LocalContext.current
    var smartNetwork by remember { mutableStateOf(SmartNetwork.isEnabled(context)) }
    val onWifi = rememberIsOnWifi()
    val profile = remember(onWifi, smartNetwork) { NetworkState.profile(context) }
    val profileLabel = stringResource(profileRes(profile))
    val statusTitle = stringResource(R.string.smart_network_status)

    SettingsPage(
        title = stringResource(R.string.settings_network_title),
        onBack = onBack,
        highlight = highlight,
        snackbarHostState = snackbarHostState,
        actions = {
            TextButton(
                enabled =
                    draft != null &&
                        (draft != saved || dnsDraft != dnsSaved) &&
                        draft?.valid == true &&
                        !NetworkSettingsViewModel.dnsError(dnsDraft),
                onClick = {
                    if (viewModel.save()) scope.launch { snackbarHostState.showSnackbar(savedMessage) }
                },
            ) { Text(stringResource(R.string.save)) }
        },
    ) {
        group(key = "network.smart", header = R.string.smart_network_title, footer = R.string.smart_network_footer) {
            switch(
                NetworkIndex.smart,
                smartNetwork,
                { on ->
                    SmartNetwork.setEnabled(context, on)
                    smartNetwork = on
                },
                icon = Icons.Outlined.NetworkCheck,
            )
            info("network.smart_status", statusTitle, profileLabel, icon = Icons.Outlined.Speed)
        }
        val form = draft ?: return@SettingsPage
        notice("network.about", text = { stringResource(R.string.proxy_settings_description) }, icon = Icons.Outlined.Info)
        group(key = "network.proxy", header = R.string.proxy_settings_title) {
            switch(NetworkIndex.enabled, form.enabled, { on -> viewModel.edit { it.copy(enabled = on) } }, icon = Icons.Outlined.Public)
            toggleGroup(NetworkIndex.type, types, form.type, { type -> viewModel.edit { it.copy(type = type) } })
            switch(
                NetworkIndex.bypassOnVpn,
                form.bypassOnVpn,
                { on -> viewModel.edit { it.copy(bypassOnVpn = on) } },
                enabled = form.enabled,
                icon = Icons.Outlined.VpnLock,
            )
            if (pausedByVpn) info(NetworkIndex.vpnPaused, value = null, icon = Icons.Outlined.Info)
        }
        group(key = "network.server", header = R.string.settings_section_proxy_server) {
            field(NetworkIndex.host, form.host, { value -> viewModel.edit { it.copy(host = value) } }, Icons.Outlined.Public) {
                error = hostError.takeIf { form.hostError }
            }
            field(
                NetworkIndex.port,
                form.port,
                { value -> viewModel.edit { it.copy(port = value.filter(Char::isDigit)) } },
                Icons.Outlined.Tag,
            ) {
                error = portError.takeIf { form.portError }
                keyboardType = KeyboardType.Number
            }
        }
        group(
            key = "network.sign_in",
            header = R.string.settings_section_proxy_sign_in,
            footer = R.string.proxy_settings_optional_auth,
        ) {
            field(NetworkIndex.username, form.username, { value -> viewModel.edit { it.copy(username = value) } }, Icons.Outlined.Person)
            field(NetworkIndex.password, form.password, { value -> viewModel.edit { it.copy(password = value) } }, Icons.Outlined.Lock) {
                keyboardType = KeyboardType.Password
                hidden = !passwordVisible
                trailing = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = passwordToggle,
                        )
                    }
                }
            }
        }
        notice("network.restart", text = { stringResource(R.string.proxy_settings_restart_notice) })

        group(key = NetworkIndex.dnsProvider.key, header = R.string.dns_title, footer = R.string.dns_footer) {
            DnsProvider.entries.forEach { provider ->
                option(
                    key = "network.dns.${provider.key}",
                    label = dnsLabels.getValue(provider),
                    selected = dnsDraft.provider == provider,
                    onClick = { viewModel.editDns { it.copy(provider = provider) } },
                    supportingText = dnsSummaries.getValue(provider) ?: provider.url,
                )
            }
            if (dnsDraft.provider == DnsProvider.CUSTOM) {
                field(
                    NetworkIndex.dnsCustomUrl,
                    dnsDraft.customUrl,
                    { value -> viewModel.editDns { it.copy(customUrl = value.trim()) } },
                    Icons.Outlined.Dns,
                ) {
                    error = dnsUrlError.takeIf { NetworkSettingsViewModel.dnsError(dnsDraft) && dnsDraft.customUrl.isNotEmpty() }
                    keyboardType = KeyboardType.Uri
                }
            }
            switch(
                NetworkIndex.dnsFallback,
                dnsDraft.fallbackToSystem,
                { on -> viewModel.editDns { it.copy(fallbackToSystem = on) } },
                enabled = dnsDraft.provider != DnsProvider.SYSTEM,
                icon = Icons.Outlined.SettingsBackupRestore,
            )
        }

        group(key = "network.test", footer = R.string.network_test_footer) {
            nav(
                NetworkIndex.testConnection,
                onClick = viewModel::testConnection,
                value = testStatus,
                enabled = testState != ConnectionTestState.Running && form.valid && !NetworkSettingsViewModel.dnsError(dnsDraft),
                showChevron = false,
                icon = Icons.Outlined.NetworkPing,
            )
        }

        if (remoteEnabled) {
            group(key = NetworkIndex.profiles.key, header = R.string.network_profiles_title, footer = R.string.network_profiles_footer) {
                profiles.forEach { profile ->
                    option(
                        key = "network.profile.${profile.id}",
                        label = profile.name,
                        selected = profile.id == activeProfileId,
                        onClick = { openProfile = profile },
                        supportingText = profileSummary(profile, directLabel, dnsLabels),
                    )
                }
                nav(
                    NetworkIndex.saveProfile,
                    onClick = { naming = true },
                    enabled = form.valid && !NetworkSettingsViewModel.dnsError(dnsDraft),
                    showChevron = false,
                    icon = Icons.Outlined.BookmarkAdd,
                )
            }
        } else {
            notice("network.custom_off", text = { stringResource(R.string.network_custom_off) }, icon = Icons.Outlined.Info)
        }

        group(key = "network.links", header = R.string.link_cleaner_title, footer = R.string.link_cleaner_footer) {
            switch(NetworkIndex.linkCleaner, viewModel.linkCleaner, viewModel::setLinkCleaner, icon = Icons.Outlined.Link)
        }
    }

    if (naming) {
        ProfileNameDialog(
            onDismiss = { naming = false },
            onSave = { name ->
                if (viewModel.saveProfile(name)) naming = false
            },
        )
    }
    openProfile?.let { profile ->
        FlowAlertDialog(
            onDismissRequest = { openProfile = null },
            title = { Text(profile.name) },
            text = { Text(profileSummary(profile, directLabel, dnsLabels)) },
            confirmButton = {
                TextButton(onClick = {
                    openProfile = null
                    if (viewModel.applyProfile(profile)) scope.launch { snackbarHostState.showSnackbar(profileAppliedMessage) }
                }) { Text(stringResource(R.string.network_profile_apply)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    openProfile = null
                    viewModel.deleteProfile(profile.id)
                }) { Text(stringResource(R.string.network_profile_delete)) }
            },
        )
    }
}

@Composable
private fun ProfileNameDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    val clean = ConnectionProfilesCodec.cleanName(name)
    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.network_profile_save)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(ConnectionProfilesCodec.MAX_NAME) },
                label = { Text(stringResource(R.string.network_profile_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = clean.isNotEmpty(), onClick = { onSave(clean) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private fun profileSummary(
    profile: ConnectionProfile,
    directLabel: String,
    dnsLabels: Map<DnsProvider, String>,
): String {
    val proxy = profile.proxyConfig("")
    val route =
        if (proxy.hasUsableEndpoint()) {
            "${proxy.type.storageValue.uppercase()} ${proxy.host}:${proxy.port}"
        } else {
            directLabel
        }
    val dns = profile.dnsSettings()
    val dnsLabel = if (dns.provider == DnsProvider.CUSTOM) dns.customUrl else dnsLabels.getValue(dns.provider)
    return "$route · DNS: $dnsLabel"
}

@Composable
private fun testStatusText(state: ConnectionTestState): String? =
    when (state) {
        ConnectionTestState.Idle -> null
        ConnectionTestState.Running -> stringResource(R.string.network_test_running)
        is ConnectionTestState.Done ->
            when (val r = state.result) {
                is ConnectionTestResult.Ok -> stringResource(R.string.network_test_ok, r.millis.toInt())
                ConnectionTestResult.DnsFailed -> stringResource(R.string.network_test_dns_failed)
                ConnectionTestResult.ProxyAuthFailed -> stringResource(R.string.network_test_auth_failed)
                is ConnectionTestResult.UnexpectedAnswer -> stringResource(R.string.network_test_unexpected, r.code)
                is ConnectionTestResult.Failed -> stringResource(R.string.network_test_failed, r.message.take(120))
            }
    }

private fun dnsLabelRes(provider: DnsProvider): Int =
    when (provider) {
        DnsProvider.SYSTEM -> R.string.dns_system
        DnsProvider.CLOUDFLARE -> R.string.dns_cloudflare
        DnsProvider.GOOGLE -> R.string.dns_google
        DnsProvider.QUAD9 -> R.string.dns_quad9
        DnsProvider.ADGUARD -> R.string.dns_adguard
        DnsProvider.CUSTOM -> R.string.dns_custom
    }

private fun dnsSummaryRes(provider: DnsProvider): Int? =
    when (provider) {
        DnsProvider.SYSTEM -> R.string.dns_system_summary
        DnsProvider.QUAD9 -> R.string.dns_quad9_summary
        DnsProvider.ADGUARD -> R.string.dns_adguard_summary
        DnsProvider.CUSTOM -> R.string.dns_custom_summary
        else -> null
    }

private class FieldOptions {
    var error: String? = null
    var keyboardType: KeyboardType = KeyboardType.Text
    var hidden: Boolean = false
    var trailing: (@Composable () -> Unit)? = null
}

private fun SettingsGroupScope.field(
    entry: SettingEntry,
    value: String,
    onValueChange: (String) -> Unit,
    icon: ImageVector,
    configure: FieldOptions.() -> Unit = {},
) {
    val options = FieldOptions().apply(configure)
    row(entry.key) { shape -> SettingsTextField(entry, value, onValueChange, icon, options, shape) }
}

@Composable
private fun SettingsTextField(
    entry: SettingEntry,
    value: String,
    onValueChange: (String) -> Unit,
    icon: ImageVector,
    options: FieldOptions,
    shape: Shape,
) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().padding(FieldPadding),
            label = { Text(stringResource(entry.title)) },
            leadingIcon = { Icon(icon, contentDescription = null) },
            trailingIcon = options.trailing,
            isError = options.error != null,
            supportingText = options.error?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = options.keyboardType),
            visualTransformation = if (options.hidden) PasswordVisualTransformation() else VisualTransformation.None,
        )
    }
}

private fun profileRes(profile: NetworkProfile): Int =
    when (profile) {
        NetworkProfile.OFFLINE -> R.string.smart_network_offline
        NetworkProfile.FAST_UNMETERED -> R.string.smart_network_fast
        NetworkProfile.METERED_WIFI -> R.string.smart_network_metered_wifi
        NetworkProfile.SLOW_WIFI -> R.string.smart_network_slow_wifi
        NetworkProfile.CELLULAR -> R.string.smart_network_cellular
        NetworkProfile.SLOW_CELLULAR -> R.string.smart_network_slow_cellular
    }
