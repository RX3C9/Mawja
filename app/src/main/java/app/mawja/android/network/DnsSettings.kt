/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.network

/**
 * Who answers the app's DNS lookups (PLAN_V2 #71). [SYSTEM] keeps the device's resolver; the rest
 * are DNS over HTTPS. A known provider carries literal bootstrap addresses, so reaching it never
 * needs the system resolver that a user may be trying to avoid.
 */
enum class DnsProvider(
    val key: String,
    val url: String?,
    val bootstrap: List<String>,
) {
    SYSTEM("system", null, emptyList()),
    CLOUDFLARE(
        "cloudflare",
        "https://cloudflare-dns.com/dns-query",
        listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"),
    ),
    GOOGLE(
        "google",
        "https://dns.google/dns-query",
        listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844"),
    ),
    QUAD9(
        "quad9",
        "https://dns.quad9.net/dns-query",
        listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9"),
    ),
    ADGUARD(
        "adguard",
        "https://dns.adguard-dns.com/dns-query",
        listOf("94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff"),
    ),

    /** A resolver the user typed; its host is found with the system resolver (no bootstrap). */
    CUSTOM("custom", null, emptyList()),
    ;

    companion object {
        fun fromKey(key: String?): DnsProvider = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

data class DnsSettings(
    val provider: DnsProvider = DnsProvider.SYSTEM,
    val customUrl: String = "",
    /** When the DoH server cannot be reached, ask the system resolver instead of failing. */
    val fallbackToSystem: Boolean = true,
) {
    /** The DoH endpoint in force, or null when lookups stay with the system (also for a bad custom URL). */
    fun resolverUrl(): String? =
        when (provider) {
            DnsProvider.SYSTEM -> null
            DnsProvider.CUSTOM -> customUrl.trim().takeIf { isValidCustomUrl(it) }
            else -> provider.url
        }

    fun bootstrapHosts(): List<String> = if (provider == DnsProvider.CUSTOM) emptyList() else provider.bootstrap

    fun isActive(): Boolean = resolverUrl() != null

    /** Changes whenever the route lookups take changes, so a cached client is rebuilt. */
    fun signature(): String = if (!isActive()) "dns:system" else "dns:${resolverUrl()}|${if (fallbackToSystem) 1 else 0}"

    companion object {
        /** An https URL with a host, no spaces and no credentials, such as https://dns.example/dns-query. */
        fun isValidCustomUrl(url: String): Boolean {
            val value = url.trim()
            if (!value.startsWith("https://", ignoreCase = true)) return false
            if (value.any { it.isWhitespace() }) return false
            val authority = value.substring("https://".length).substringBefore('/').substringBefore('?').substringBefore('#')
            if (authority.isEmpty() || '@' in authority) return false
            val host =
                if (authority.startsWith("[")) {
                    authority.substringBefore(']').removePrefix("[")
                } else {
                    authority.substringBefore(':')
                }
            if (host.isEmpty()) return false
            val port = if (authority.startsWith("[")) authority.substringAfter("]", "").removePrefix(":") else authority.substringAfter(':', "")
            if (port.isNotEmpty() && port.toIntOrNull()?.let { it in 1..65535 } != true) return false
            return host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' } && !host.startsWith(".") && !host.endsWith(".")
        }
    }
}
