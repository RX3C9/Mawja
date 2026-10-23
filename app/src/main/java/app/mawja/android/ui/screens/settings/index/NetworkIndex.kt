package app.mawja.android.ui.screens.settings.index

import app.mawja.android.R
import app.mawja.android.ui.components.settings.SettingEntry
import app.mawja.android.ui.components.settings.SettingsDestination

internal object NetworkIndex {
    private val page = SettingsDestination.NETWORK

    private fun entry(
        key: String,
        title: Int,
        section: Int,
        summary: Int? = null,
    ) = SettingEntry(
        key = "network.$key",
        title = title,
        summary = summary,
        section = section,
        keywords = R.string.settings_keywords_network,
        destination = page,
    )

    val smart = entry("smart", R.string.smart_network_switch, R.string.smart_network_title, R.string.smart_network_switch_summary)
    val enabled = entry("enabled", R.string.proxy_settings_enabled, R.string.proxy_settings_title, R.string.proxy_settings_enabled_subtitle)
    val type = entry("type", R.string.proxy_settings_type, R.string.proxy_settings_title)
    val bypassOnVpn =
        entry("bypass_vpn", R.string.proxy_settings_bypass_vpn, R.string.proxy_settings_title, R.string.proxy_settings_bypass_vpn_subtitle)

    /** A status row shown only while a VPN pauses the proxy, so it is left out of search. */
    val vpnPaused =
        entry("vpn_paused", R.string.proxy_settings_vpn_paused, R.string.proxy_settings_title, R.string.proxy_settings_vpn_paused_subtitle)
    val host = entry("host", R.string.proxy_settings_host, R.string.settings_section_proxy_server)
    val port = entry("port", R.string.proxy_settings_port, R.string.settings_section_proxy_server)
    val username = entry("username", R.string.proxy_settings_username, R.string.settings_section_proxy_sign_in)
    val password = entry("password", R.string.proxy_settings_password, R.string.settings_section_proxy_sign_in)

    val dnsProvider = entry("dns_provider", R.string.dns_provider, R.string.dns_title, R.string.dns_provider_summary)
    val dnsCustomUrl = entry("dns_custom_url", R.string.dns_custom_url, R.string.dns_title)
    val dnsFallback = entry("dns_fallback", R.string.dns_fallback, R.string.dns_title, R.string.dns_fallback_summary)
    val linkCleaner =
        entry("link_cleaner", R.string.link_cleaner_switch, R.string.link_cleaner_title, R.string.link_cleaner_switch_summary)

    val all = listOf(smart, enabled, type, bypassOnVpn, host, port, username, password, dnsProvider, dnsFallback, linkCleaner)
}
