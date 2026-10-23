/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** What one connection test found: [millis] for the round trip, or the step that failed. */
sealed interface ConnectionTestResult {
    data class Ok(
        val millis: Long,
    ) : ConnectionTestResult

    /** The DNS lookup failed (with no proxy) or the proxy could not resolve the test host. */
    data object DnsFailed : ConnectionTestResult

    /** The proxy refused the sign-in (HTTP 407 or a SOCKS5 rejection). */
    data object ProxyAuthFailed : ConnectionTestResult

    /** Connected, but the answer was not YouTube's empty 204, such as a captive portal page. */
    data class UnexpectedAnswer(
        val code: Int,
    ) : ConnectionTestResult

    data class Failed(
        val message: String,
    ) : ConnectionTestResult
}

/**
 * Tries a proxy + DNS setup before it is saved (#71) by fetching YouTube's generate_204, the same
 * host the app talks to most. Nothing it builds is shared with the app's clients.
 */
object ConnectionTester {
    const val TEST_URL = "https://www.youtube.com/generate_204"

    suspend fun test(
        proxy: AppProxyConfig,
        dns: DnsSettings,
    ): ConnectionTestResult =
        withContext(Dispatchers.IO) {
            val candidate = proxy.normalized()
            AppProxyManager.withTestProxy(candidate) { run(candidate, dns) }
        }

    private fun run(
        proxy: AppProxyConfig,
        dns: DnsSettings,
    ): ConnectionTestResult {
        val builder =
            OkHttpClient
                .Builder()
                .proxy(proxy.toProxy() ?: Proxy.NO_PROXY)
                .dns(if (AppDns.remoteEnabled.value) AppDns.resolverFor(dns) else okhttp3.Dns.SYSTEM)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
        proxy.httpProxyAuthorizationHeader()?.let { header ->
            builder.proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") == header) {
                    null
                } else {
                    response.request
                        .newBuilder()
                        .header("Proxy-Authorization", header)
                        .build()
                }
            }
        }
        val client = builder.build()
        val started = System.nanoTime()
        return try {
            client.newCall(Request.Builder().url(TEST_URL).build()).execute().use { response ->
                val millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                when (response.code) {
                    204 -> ConnectionTestResult.Ok(millis)
                    407 -> ConnectionTestResult.ProxyAuthFailed
                    else -> ConnectionTestResult.UnexpectedAnswer(response.code)
                }
            }
        } catch (e: UnknownHostException) {
            ConnectionTestResult.DnsFailed
        } catch (e: IOException) {
            val message = e.message.orEmpty()
            when {
                message.contains("407") || message.contains("authentication", ignoreCase = true) ->
                    ConnectionTestResult.ProxyAuthFailed
                else -> ConnectionTestResult.Failed(message.ifBlank { e.javaClass.simpleName })
            }
        } finally {
            client.connectionPool.evictAll()
        }
    }
}
