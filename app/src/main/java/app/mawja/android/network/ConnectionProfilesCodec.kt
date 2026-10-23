/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A named proxy + DNS setup the user can switch to in one tap (#71), such as "Home" and "Work".
 * [sealedPassword] is the proxy password as KeystoreSecretBox sealed it; the codec never sees the
 * plain password.
 */
@Serializable
data class ConnectionProfile(
    val id: String,
    val name: String,
    val proxyEnabled: Boolean = false,
    val proxyType: String = AppProxyType.HTTP.storageValue,
    val host: String = "",
    val port: Int = 8080,
    val username: String = "",
    val sealedPassword: String = "",
    val bypassOnVpn: Boolean = false,
    val dnsProvider: String = DnsProvider.SYSTEM.key,
    val dnsCustomUrl: String = "",
    val dnsFallback: Boolean = true,
) {
    /** The proxy this profile sets, with [password] opened by the caller. */
    fun proxyConfig(password: String): AppProxyConfig =
        AppProxyConfig(
            enabled = proxyEnabled,
            type = AppProxyType.fromStorageValue(proxyType),
            host = host,
            port = port,
            username = username,
            password = password,
            bypassOnVpn = bypassOnVpn,
        )

    fun dnsSettings(): DnsSettings =
        DnsSettings(
            provider = DnsProvider.fromKey(dnsProvider),
            customUrl = dnsCustomUrl,
            fallbackToSystem = dnsFallback,
        )

    /** Whether applying this profile would change nothing; the password is the caller's to compare. */
    fun sameSetupAs(
        proxy: AppProxyConfig,
        dns: DnsSettings,
    ): Boolean = proxyConfig(proxy.password) == proxy.normalized() && dnsSettings() == dns.copy(customUrl = dns.customUrl.trim())

    companion object {
        fun of(
            id: String,
            name: String,
            proxy: AppProxyConfig,
            sealedPassword: String,
            dns: DnsSettings,
        ): ConnectionProfile {
            val p = proxy.normalized()
            return ConnectionProfile(
                id = id,
                name = ConnectionProfilesCodec.cleanName(name),
                proxyEnabled = p.enabled,
                proxyType = p.type.storageValue,
                host = p.host,
                port = p.port,
                username = p.username,
                sealedPassword = sealedPassword,
                bypassOnVpn = p.bypassOnVpn,
                dnsProvider = dns.provider.key,
                dnsCustomUrl = dns.customUrl.trim(),
                dnsFallback = dns.fallbackToSystem,
            )
        }
    }
}

@Serializable
private data class ProfilesFile(
    val v: Int = 1,
    val profiles: List<ConnectionProfile> = emptyList(),
)

/** Reads and writes the saved profiles as one JSON value; bad or unknown data is dropped, never thrown. */
object ConnectionProfilesCodec {
    const val MAX_PROFILES = 20
    const val MAX_NAME = 40

    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }

    fun encode(profiles: List<ConnectionProfile>): String = json.encodeToString(ProfilesFile.serializer(), ProfilesFile(profiles = sanitize(profiles)))

    fun decode(raw: String?): List<ConnectionProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        val file = runCatching { json.decodeFromString(ProfilesFile.serializer(), raw) }.getOrNull() ?: return emptyList()
        return sanitize(file.profiles)
    }

    /** One line, trimmed, at most [MAX_NAME] characters. */
    fun cleanName(name: String): String =
        name
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_NAME)
            .trim()

    /** Adds [profile], or replaces the one with its id or (case-insensitively) its name, newest last. */
    fun upsert(
        profiles: List<ConnectionProfile>,
        profile: ConnectionProfile,
    ): List<ConnectionProfile> {
        val rest = profiles.filterNot { it.id == profile.id || it.name.equals(profile.name, ignoreCase = true) }
        return sanitize(rest + profile)
    }

    fun remove(
        profiles: List<ConnectionProfile>,
        id: String,
    ): List<ConnectionProfile> = profiles.filterNot { it.id == id }

    private fun sanitize(profiles: List<ConnectionProfile>): List<ConnectionProfile> =
        profiles
            .map { it.copy(name = cleanName(it.name), host = it.host.trim(), username = it.username.trim()) }
            .filter { it.id.isNotBlank() && it.name.isNotEmpty() && it.port in 1..65535 }
            .distinctBy { it.id }
            .distinctBy { it.name.lowercase() }
            .takeLast(MAX_PROFILES)
}
