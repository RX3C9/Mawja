package app.mawja.android.network

import android.util.Base64
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList

enum class AppProxyType(
    val storageValue: String,
) {
    HTTP("http"),
    SOCKS5("socks5"),
    ;

    companion object {
        fun fromStorageValue(value: String?): AppProxyType =
            when (value?.lowercase()) {
                SOCKS5.storageValue -> SOCKS5
                else -> HTTP
            }
    }
}

data class AppProxyConfig(
    val enabled: Boolean = false,
    val type: AppProxyType = AppProxyType.HTTP,
    val host: String = "",
    val port: Int = 8080,
    val username: String = "",
    val password: String = "",
    val bypassOnVpn: Boolean = false,
) {
    fun normalized(): AppProxyConfig =
        copy(
            host = host.trim(),
            username = username.trim(),
        )

    /** The proxy traffic should follow right now: a VPN pauses it when [bypassOnVpn] is set. */
    fun effective(vpnActive: Boolean): AppProxyConfig = if (bypassOnVpn && vpnActive) copy(enabled = false) else this

    /** Whether a VPN can change this proxy, so only then is the network watched for one. */
    fun watchesVpn(): Boolean = bypassOnVpn && hasUsableEndpoint()

    fun hasUsableEndpoint(): Boolean = enabled && host.isNotBlank() && port in 1..65535

    fun toProxy(): Proxy? {
        if (!hasUsableEndpoint()) return null
        val proxyType =
            when (type) {
                AppProxyType.HTTP -> Proxy.Type.HTTP
                AppProxyType.SOCKS5 -> Proxy.Type.SOCKS
            }
        return Proxy(proxyType, InetSocketAddress.createUnresolved(host, port))
    }

    fun hasCredentials(): Boolean = username.isNotBlank()

    fun httpProxyAuthorizationHeader(): String? {
        if (!hasUsableEndpoint() || type != AppProxyType.HTTP || !hasCredentials()) return null
        val credentials = "$username:$password"
        val encoded = Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Basic $encoded"
    }

    fun signature(): String =
        listOf(
            enabled,
            type.storageValue,
            host,
            port,
            username,
            password,
        ).joinToString(separator = "|")
}

object AppProxyManager {
    @Volatile
    private var config: AppProxyConfig = AppProxyConfig()

    private val lock = Any()

    private val livePools = CopyOnWriteArrayList<ConnectionPool>()

    fun update(
        newConfig: AppProxyConfig,
        vpnActive: Boolean = false,
    ) {
        val changed =
            synchronized(lock) {
                val previous = config.signature()
                config = newConfig.normalized().effective(vpnActive)
                installJvmAuthenticatorLocked()
                config.signature() != previous
            }
        // A live client's pool is keyed by its selector, not the proxy the selector picks, so a
        // kept-alive connection would otherwise carry on through the previous route.
        if (changed) evictLiveConnections()
    }

    /** Drops kept-alive connections of the live clients, so the next request takes the new route. */
    fun evictLiveConnections() = livePools.forEach { it.evictAll() }

    fun currentConfig(): AppProxyConfig = config

    fun currentProxy(): Proxy? = config.toProxy()

    fun currentHttpProxyAuthorizationHeader(): String? = config.httpProxyAuthorizationHeader()

    /** Proxy and DNS together: a client cached per signature is rebuilt when either changes. */
    fun currentSignature(): String = config.signature() + "|" + AppDns.signature()

    fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val activeConfig = config
        builder.dns(AppDns)
        val proxy = activeConfig.toProxy() ?: return builder
        builder.proxy(proxy)
        if (activeConfig.type == AppProxyType.HTTP) {
            val authHeader = activeConfig.httpProxyAuthorizationHeader()
            if (authHeader != null) {
                builder.proxyAuthenticator { _, response ->
                    response.request
                        .newBuilder()
                        .header("Proxy-Authorization", authHeader)
                        .build()
                }
            }
        }
        return builder
    }

    /**
     * Builds a client that follows every later proxy change, for clients that are built once and
     * live as long as the process. Clients rebuilt per [currentSignature] use [applyTo] instead.
     */
    fun buildLive(builder: OkHttpClient.Builder): OkHttpClient =
        builder
            .proxySelector(AppProxySelector)
            .proxyAuthenticator(AppProxyAuthenticator)
            .dns(AppDns)
            .build()
            .also { livePools.addIfAbsent(it.connectionPool) }

    /** A SOCKS5 proxy being tried by the connection test, answered alongside the saved one. */
    @Volatile
    private var testProxy: AppProxyConfig? = null

    /**
     * Runs [block] while the JVM authenticator also answers for [candidate], so a connection test can
     * sign in to a SOCKS5 proxy that is not saved yet. One authenticator serves both: Java has only
     * one default, and swapping it would break the saved proxy's sign-in mid-test.
     */
    suspend fun <T> withTestProxy(
        candidate: AppProxyConfig,
        block: suspend () -> T,
    ): T {
        synchronized(lock) {
            testProxy = candidate.normalized()
            installJvmAuthenticatorLocked()
        }
        try {
            return block()
        } finally {
            synchronized(lock) {
                testProxy = null
                installJvmAuthenticatorLocked()
            }
        }
    }

    private fun installJvmAuthenticatorLocked() {
        val saved = config.takeIf { it.needsSocksSignIn() }
        val test = testProxy?.takeIf { it.needsSocksSignIn() }
        if (saved == null && test == null) {
            Authenticator.setDefault(null)
            return
        }
        Authenticator.setDefault(SocksAuthenticator)
    }

    /** Reads the proxies in force at each challenge, so it never holds stale credentials. */
    private object SocksAuthenticator : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? {
            if (requestorType != RequestorType.PROXY) return null
            val candidates = listOfNotNull(AppProxyManager.testProxy, AppProxyManager.config)
            val match =
                candidates.firstOrNull { c ->
                    c.needsSocksSignIn() &&
                        requestingHost.equals(c.host, ignoreCase = true) &&
                        (requestingPort == c.port || requestingPort <= 0)
                } ?: return null
            return PasswordAuthentication(match.username, match.password.toCharArray())
        }
    }
}

private fun AppProxyConfig.needsSocksSignIn(): Boolean = hasUsableEndpoint() && type == AppProxyType.SOCKS5 && hasCredentials()
