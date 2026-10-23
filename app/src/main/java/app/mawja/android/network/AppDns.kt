/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.network

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The resolver every app client uses (PLAN_V2 #71): the system's, or DNS over HTTPS per
 * [DnsSettings]. It reads the settings at each lookup, so a client built once follows later changes.
 * Local names stay with the system resolver, as casting and device sync need. Behind a proxy only the
 * proxy's own host is looked up here; the proxy resolves everything else.
 */
object AppDns : Dns {
    private const val TAG = "AppDns"
    private const val PREFS = "mawja_custom_network"
    private const val KEY_PROVIDER = "dns_provider"
    private const val KEY_CUSTOM_URL = "dns_custom_url"
    private const val KEY_FALLBACK = "dns_fallback_system"
    private const val KEY_REMOTE = "remote_enabled"

    /** config/app features.customNetwork: false keeps lookups on the system resolver (and hides profiles). */
    const val REMOTE_SWITCH = "customNetwork"
    private const val CACHE_TTL_MS = 5 * 60_000L
    private const val CACHE_MAX = 512

    @Volatile
    private var settings = DnsSettings()

    @Volatile
    private var resolver: Dns? = null

    /** Set by the remote switch: false keeps every lookup on the system resolver. */
    @Volatile
    private var allowed = true

    private val cache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()

    fun init(context: Context) {
        allowed = prefs(context).getBoolean(KEY_REMOTE, true)
        _remoteEnabled.value = allowed
        install(read(context))
    }

    private val _remoteEnabled = kotlinx.coroutines.flow.MutableStateFlow(true)

    /** Whether the remote switch lets the custom DNS and connection profiles work. */
    val remoteEnabled: kotlinx.coroutines.flow.StateFlow<Boolean> = _remoteEnabled

    /** Pushed from the activity that owns RemoteConfigRepository; kept so a cold process obeys it too. */
    fun setRemoteEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        if (allowed == enabled && prefs(context).contains(KEY_REMOTE)) return
        prefs(context).edit { putBoolean(KEY_REMOTE, enabled) }
        _remoteEnabled.value = enabled
        setAllowed(enabled)
    }

    fun current(): DnsSettings = settings

    fun save(
        context: Context,
        newSettings: DnsSettings,
    ) {
        prefs(context).edit {
            putString(KEY_PROVIDER, newSettings.provider.key)
            putString(KEY_CUSTOM_URL, newSettings.customUrl.trim())
            putBoolean(KEY_FALLBACK, newSettings.fallbackToSystem)
        }
        install(newSettings)
    }

    private fun setAllowed(on: Boolean) {
        if (allowed == on) return
        allowed = on
        install(settings)
    }

    /** Part of every client signature: [DnsSettings.signature], or system while the remote switch is off. */
    fun signature(): String = if (allowed) settings.signature() else "dns:system"

    fun read(context: Context): DnsSettings {
        val p = prefs(context)
        return DnsSettings(
            provider = DnsProvider.fromKey(p.getString(KEY_PROVIDER, null)),
            customUrl = p.getString(KEY_CUSTOM_URL, "").orEmpty(),
            fallbackToSystem = p.getBoolean(KEY_FALLBACK, true),
        )
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val doh = resolver
        if (doh == null || isLocalHost(hostname)) return Dns.SYSTEM.lookup(hostname)
        val key = hostname.lowercase()
        val now = System.currentTimeMillis()
        cache[key]?.let { (addresses, at) -> if (now - at < CACHE_TTL_MS) return addresses }
        return try {
            doh.lookup(hostname).also {
                if (cache.size > CACHE_MAX) cache.clear()
                cache[key] = it to now
            }
        } catch (e: UnknownHostException) {
            // Names without a public suffix (router.lan, an intranet host) are not sent to DoH at all.
            if (!settings.fallbackToSystem && !isPrivateNameRefusal(e)) throw e
            Log.w(TAG, "DoH lookup failed, using the system resolver: ${e.message}")
            Dns.SYSTEM.lookup(hostname)
        }
    }

    /**
     * A resolver for [s] alone, for the connection test of settings that are not saved yet; it falls
     * back to the system as [s] says.
     */
    fun resolverFor(s: DnsSettings): Dns {
        val doh = build(s) ?: return Dns.SYSTEM
        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (isLocalHost(hostname)) return Dns.SYSTEM.lookup(hostname)
                return try {
                    doh.lookup(hostname)
                } catch (e: UnknownHostException) {
                    if (!s.fallbackToSystem && !isPrivateNameRefusal(e)) throw e
                    Dns.SYSTEM.lookup(hostname)
                }
            }
        }
    }

    private fun install(newSettings: DnsSettings) {
        val before = signature()
        settings = newSettings
        resolver = if (allowed) build(newSettings) else null
        cache.clear()
        if (signature() != before) AppProxyManager.evictLiveConnections()
    }

    private fun build(s: DnsSettings): Dns? {
        val url = s.resolverUrl()?.toHttpUrlOrNull() ?: return null
        return try {
            // Lookups for the DoH server itself use the bootstrap addresses (or the system), and the
            // queries follow the app proxy like any other request.
            val client =
                OkHttpClient
                    .Builder()
                    .proxySelector(AppProxySelector)
                    .proxyAuthenticator(AppProxyAuthenticator)
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .callTimeout(8, TimeUnit.SECONDS)
                    .build()
            DnsOverHttps
                .Builder()
                .client(client)
                .url(url)
                .apply {
                    val bootstrap = s.bootstrapHosts().map(InetAddress::getByName)
                    if (bootstrap.isNotEmpty()) bootstrapDnsHosts(bootstrap)
                }.includeIPv6(true)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Could not set up DNS over HTTPS: ${e.message}")
            null
        }
    }

    /** DnsOverHttps refuses names outside the public suffix list with exactly this message. */
    private fun isPrivateNameRefusal(e: UnknownHostException): Boolean = e.message == "private hosts not resolved"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
