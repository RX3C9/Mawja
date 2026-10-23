/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConnectionProfilesCodecTest {
    private val proxy =
        AppProxyConfig(
            enabled = true,
            type = AppProxyType.SOCKS5,
            host = " proxy.example ",
            port = 1080,
            username = " me ",
            password = "secret",
            bypassOnVpn = true,
        )
    private val dns = DnsSettings(DnsProvider.CUSTOM, " https://dns.example/q ", fallbackToSystem = false)

    @Test
    fun roundTripKeepsEverythingButThePlainPassword() {
        val profile = ConnectionProfile.of("id1", "  Work   VPN  ", proxy, "ks1:sealed", dns)
        val decoded = ConnectionProfilesCodec.decode(ConnectionProfilesCodec.encode(listOf(profile)))
        assertThat(decoded).containsExactly(profile)
        val p = decoded.single()
        assertThat(p.name).isEqualTo("Work VPN")
        assertThat(p.sealedPassword).isEqualTo("ks1:sealed")
        assertThat(ConnectionProfilesCodec.encode(decoded)).doesNotContain("secret")
        assertThat(p.proxyConfig("secret")).isEqualTo(proxy.normalized())
        assertThat(p.dnsSettings()).isEqualTo(dns.copy(customUrl = "https://dns.example/q"))
        assertThat(p.sameSetupAs(proxy, dns)).isTrue()
        assertThat(p.sameSetupAs(proxy.copy(port = 1081), dns)).isFalse()
        assertThat(p.sameSetupAs(proxy, DnsSettings())).isFalse()
    }

    @Test
    fun badInputDecodesToNothing() {
        listOf(null, "", "  ", "not json", "[]", "{\"profiles\":7}").forEach {
            assertThat(ConnectionProfilesCodec.decode(it)).isEmpty()
        }
    }

    @Test
    fun unknownFieldsAreIgnoredAndInvalidProfilesDropped() {
        val raw =
            """{"v":2,"extra":true,"profiles":[
                {"id":"a","name":"Home","host":"h","port":3128,"future":1},
                {"id":"","name":"No id"},
                {"id":"b","name":"   "},
                {"id":"c","name":"Bad port","port":70000},
                {"id":"a","name":"Duplicate id"},
                {"id":"d","name":"home"}
            ]}"""
        val decoded = ConnectionProfilesCodec.decode(raw)
        assertThat(decoded.map { it.id }).containsExactly("a")
        assertThat(decoded.single().dnsSettings()).isEqualTo(DnsSettings())
        assertThat(decoded.single().proxyEnabled).isFalse()
    }

    @Test
    fun upsertReplacesBySameIdOrNameAndKeepsTheNewestWithinTheCap() {
        val home = ConnectionProfile.of("1", "Home", proxy, "", dns)
        val work = ConnectionProfile.of("2", "Work", proxy, "", dns)
        val homeAgain = ConnectionProfile.of("3", "HOME", proxy.copy(port = 9), "", DnsSettings())
        val list = ConnectionProfilesCodec.upsert(ConnectionProfilesCodec.upsert(listOf(home), work), homeAgain)
        assertThat(list.map { it.id }).containsExactly("2", "3").inOrder()
        assertThat(ConnectionProfilesCodec.remove(list, "2").map { it.id }).containsExactly("3")

        var many = emptyList<ConnectionProfile>()
        repeat(ConnectionProfilesCodec.MAX_PROFILES + 5) { i -> many = ConnectionProfilesCodec.upsert(many, ConnectionProfile.of("id$i", "P$i", proxy, "", dns)) }
        assertThat(many).hasSize(ConnectionProfilesCodec.MAX_PROFILES)
        assertThat(many.last().id).isEqualTo("id${ConnectionProfilesCodec.MAX_PROFILES + 4}")
    }

    @Test
    fun namesAreOneLineAndCapped() {
        assertThat(ConnectionProfilesCodec.cleanName("a\n\tb")).isEqualTo("a b")
        assertThat(ConnectionProfilesCodec.cleanName("x".repeat(100))).hasLength(ConnectionProfilesCodec.MAX_NAME)
        assertThat(ConnectionProfilesCodec.cleanName("   ")).isEmpty()
    }
}
