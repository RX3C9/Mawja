package app.mawja.android.ui.screens.settings.network

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import app.mawja.android.data.local.PlayerPreferences
import app.mawja.android.network.AppProxyConfig
import app.mawja.android.network.AppDns
import app.mawja.android.network.AppProxyType
import app.mawja.android.network.ConnectionProfile
import app.mawja.android.network.ConnectionProfiles
import app.mawja.android.network.ConnectionTestResult
import app.mawja.android.network.ConnectionTester
import app.mawja.android.network.DnsProvider
import app.mawja.android.network.DnsSettings
import app.mawja.android.network.LinkCleaner
import app.mawja.android.network.VpnStateMonitor
import app.mawja.android.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The proxy form as typed, before it is validated and saved. */
@Immutable
data class ProxyDraft(
    val enabled: Boolean,
    val type: AppProxyType,
    val host: String,
    val port: String,
    val username: String,
    val password: String,
    val bypassOnVpn: Boolean = false,
) {
    val hostError: Boolean get() = enabled && host.isBlank()
    val portError: Boolean get() = enabled && port.toIntOrNull()?.let { it in PORT_RANGE } != true
    val valid: Boolean get() = !hostError && !portError

    fun toConfig(fallbackPort: Int) =
        AppProxyConfig(
            enabled = enabled,
            type = type,
            host = host.trim(),
            port = port.toIntOrNull() ?: fallbackPort,
            username = username.trim(),
            password = password,
            bypassOnVpn = bypassOnVpn,
        )

    companion object {
        val PORT_RANGE = 1..65535

        fun of(config: AppProxyConfig) =
            ProxyDraft(
                config.enabled,
                config.type,
                config.host,
                config.port.toString(),
                config.username,
                config.password,
                config.bypassOnVpn,
            )
    }
}

/** The connection test row: idle, running, or the last answer. */
sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState

    data object Running : ConnectionTestState

    data class Done(
        val result: ConnectionTestResult,
    ) : ConnectionTestState
}

/**
 * Holds the proxy form while it is edited. Nothing reaches the network stack until [save], because a
 * half-typed host would otherwise reroute every request as each key is pressed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NetworkSettingsViewModel
    @Inject
    constructor(
        private val preferences: PlayerPreferences,
        vpnStateMonitor: VpnStateMonitor,
        @ApplicationContext private val context: Context,
    ) : SettingsViewModel() {
        private var savedPort = DEFAULT_PORT
        private val _saved = MutableStateFlow<ProxyDraft?>(null)
        private val _draft = MutableStateFlow<ProxyDraft?>(null)
        val saved: StateFlow<ProxyDraft?> = _saved.asStateFlow()
        val draft: StateFlow<ProxyDraft?> = _draft.asStateFlow()

        /** The DNS choice is saved with the proxy form on Save, so a half-typed custom server is never used. */
        private val _dnsSaved = MutableStateFlow(AppDns.read(context))
        private val _dnsDraft = MutableStateFlow(_dnsSaved.value)
        val dnsSaved: StateFlow<DnsSettings> = _dnsSaved.asStateFlow()
        val dnsDraft: StateFlow<DnsSettings> = _dnsDraft.asStateFlow()

        /** Applied at once: it only changes what later shares and copies carry. */
        private val _linkCleaner = MutableStateFlow(LinkCleaner.isEnabled())
        val linkCleaner: StateFlow<Boolean> = _linkCleaner.asStateFlow()

        /** The remote switch features.customNetwork; off hides profiles and keeps DNS on the system. */
        val remoteEnabled: StateFlow<Boolean> = AppDns.remoteEnabled

        private val _profiles = MutableStateFlow(ConnectionProfiles.list(context))
        val profiles: StateFlow<List<ConnectionProfile>> = _profiles.asStateFlow()

        /** The profile whose setup is the saved one, if any. */
        val activeProfileId: StateFlow<String?> =
            combine(_profiles, _saved, _dnsSaved) { profiles, saved, dns ->
                val config = saved?.toConfig(savedPort) ?: return@combine null
                profiles.firstOrNull { it.sameSetupAs(config, dns) && passwordMatches(it, config) }?.id
            }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        private val _test = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
        val test: StateFlow<ConnectionTestState> = _test.asStateFlow()
        private var testJob: Job? = null

        /** Whether a VPN is pausing the saved proxy right now. */
        val pausedByVpn: StateFlow<Boolean> =
            preferences.proxyConfig
                .distinctUntilChanged()
                .flatMapLatest { config -> if (config.watchesVpn()) vpnStateMonitor.vpnActive() else flowOf(false) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

        init {
            viewModelScope.launch {
                val config = preferences.proxyConfig.first()
                savedPort = config.port
                _saved.value = ProxyDraft.of(config)
                _draft.value = ProxyDraft.of(config)
            }
        }

        fun edit(transform: (ProxyDraft) -> ProxyDraft) = _draft.update { it?.let(transform) }

        fun editDns(transform: (DnsSettings) -> DnsSettings) = _dnsDraft.update(transform)

        fun setLinkCleaner(on: Boolean) {
            LinkCleaner.setEnabled(context, on)
            _linkCleaner.value = on
        }

        /** Saves the current form as profile [name]; reports whether the form was valid. */
        fun saveProfile(name: String): Boolean {
            val current = _draft.value ?: return false
            val dns = _dnsDraft.value
            if (!current.valid || dnsError(dns) || app.mawja.android.network.ConnectionProfilesCodec.cleanName(name).isEmpty()) return false
            _profiles.value = ConnectionProfiles.save(context, name, current.toConfig(savedPort), dns)
            return true
        }

        /** Loads [profile] into the form and saves it, so one tap switches the connection. */
        fun applyProfile(profile: ConnectionProfile): Boolean {
            _draft.value = ProxyDraft.of(profile.proxyConfig(ConnectionProfiles.password(profile)))
            _dnsDraft.value = profile.dnsSettings()
            return save()
        }

        fun deleteProfile(id: String) {
            _profiles.value = ConnectionProfiles.remove(context, id)
        }

        /** Tries the form as it is now, saved or not. */
        fun testConnection() {
            val current = _draft.value ?: return
            if (!current.valid || dnsError(_dnsDraft.value)) return
            testJob?.cancel()
            _test.value = ConnectionTestState.Running
            val config = current.toConfig(savedPort)
            val dns = _dnsDraft.value
            testJob =
                viewModelScope.launch {
                    _test.value = ConnectionTestState.Done(ConnectionTester.test(config, dns))
                }
        }

        private fun passwordMatches(
            profile: ConnectionProfile,
            config: AppProxyConfig,
        ): Boolean = !config.hasCredentials() || ConnectionProfiles.password(profile) == config.password

        /** Saves the proxy and DNS drafts when both are valid; reports whether they were. */
        fun save(): Boolean {
            val current = _draft.value ?: return false
            val dns = _dnsDraft.value
            if (!current.valid || dnsError(dns)) return false
            if (dns != _dnsSaved.value) {
                AppDns.save(context, dns)
                _dnsSaved.value = dns
            }
            if (current == _saved.value) return true
            val config = current.toConfig(savedPort)
            savedPort = config.port
            _saved.value = ProxyDraft.of(config)
            _draft.value = ProxyDraft.of(config)
            write { preferences.setProxyConfig(config) }
            return true
        }

        companion object {
            /** A custom resolver needs a valid https address before it can be saved. */
            fun dnsError(dns: DnsSettings): Boolean =
                dns.provider == DnsProvider.CUSTOM && !DnsSettings.isValidCustomUrl(dns.customUrl)

            private const val DEFAULT_PORT = 8080
            private const val STOP_TIMEOUT_MS = 5_000L
        }
    }
