/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DnsSettingsTest {
    @Test
    fun systemIsTheDefaultAndInactive() {
        val s = DnsSettings()
        assertThat(s.provider).isEqualTo(DnsProvider.SYSTEM)
        assertThat(s.isActive()).isFalse()
        assertThat(s.resolverUrl()).isNull()
        assertThat(s.signature()).isEqualTo("dns:system")
    }

    @Test
    fun knownProvidersHaveHttpsUrlsAndLiteralBootstraps() {
        DnsProvider.entries.filter { it != DnsProvider.SYSTEM && it != DnsProvider.CUSTOM }.forEach { p ->
            val s = DnsSettings(provider = p)
            assertThat(s.resolverUrl()).startsWith("https://")
            assertThat(DnsSettings.isValidCustomUrl(s.resolverUrl()!!)).isTrue()
            assertThat(s.bootstrapHosts()).isNotEmpty()
            s.bootstrapHosts().forEach { ip -> assertThat(ip.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' }).isTrue() }
        }
    }

    @Test
    fun customNeedsAValidHttpsUrl() {
        assertThat(DnsSettings(DnsProvider.CUSTOM, "https://dns.example/dns-query").resolverUrl())
            .isEqualTo("https://dns.example/dns-query")
        assertThat(DnsSettings(DnsProvider.CUSTOM, "  https://1.2.3.4:8443/q ").resolverUrl()).isEqualTo("https://1.2.3.4:8443/q")
        assertThat(DnsSettings(DnsProvider.CUSTOM, "https://[2001:db8::1]/dns-query").isActive()).isTrue()
        listOf(
            "",
            "http://dns.example/dns-query",
            "dns.example",
            "https://",
            "https://user:pw@dns.example/q",
            "https://dns example/q",
            "https://dns.example:0/q",
            "https://dns.example:99999/q",
            "https://.example/q",
            "https://dns_example/q",
        ).forEach { url ->
            assertThat(DnsSettings.isValidCustomUrl(url)).isFalse()
            assertThat(DnsSettings(DnsProvider.CUSTOM, url).isActive()).isFalse()
        }
        assertThat(DnsSettings(DnsProvider.CUSTOM, "https://dns.example/q").bootstrapHosts()).isEmpty()
    }

    @Test
    fun signatureChangesWithTheRoute() {
        val a = DnsSettings(DnsProvider.CLOUDFLARE)
        val b = DnsSettings(DnsProvider.GOOGLE)
        val c = a.copy(fallbackToSystem = false)
        assertThat(setOf(a.signature(), b.signature(), c.signature())).hasSize(3)
        // A bad custom URL means lookups stay with the system, so nothing is rebuilt for it.
        assertThat(DnsSettings(DnsProvider.CUSTOM, "nope").signature()).isEqualTo("dns:system")
    }

    @Test
    fun keysRoundTrip() {
        DnsProvider.entries.forEach { assertThat(DnsProvider.fromKey(it.key)).isEqualTo(it) }
        assertThat(DnsProvider.fromKey("bogus")).isEqualTo(DnsProvider.SYSTEM)
        assertThat(DnsProvider.fromKey(null)).isEqualTo(DnsProvider.SYSTEM)
    }
}
