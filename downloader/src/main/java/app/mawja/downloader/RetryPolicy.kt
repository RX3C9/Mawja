/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.downloader

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** What to do after a failed HTTP answer or a dropped connection. */
object RetryPolicy {
    enum class Outcome {
        /** Try again after a pause (timeouts, rate limits, server errors, dropped connections). */
        RETRY,

        /** The link no longer works as is (403, 410): ask a resolver for a fresh one, else stop. */
        EXPIRED,

        /** Retrying cannot help (404, 400, 416 on a fresh request …). */
        FATAL,
    }

    const val MAX_ATTEMPTS = DownloadMath.MAX_RETRIES
    private const val BASE_MS = 1_000L
    private const val CAP_MS = 30_000L

    /** Longest Retry-After honoured; a server asking for more is treated as a failure. */
    const val MAX_RETRY_AFTER_MS = 5 * 60_000L

    /** [code] 0 means no answer arrived (network error): always worth another try. */
    fun classify(code: Int): Outcome =
        when (code) {
            0, 408, 425, 429, 500, 502, 503, 504, 507, 509, 520, 521, 522, 523, 524 -> Outcome.RETRY
            401, 403, 410 -> Outcome.EXPIRED
            else -> if (code >= 500) Outcome.RETRY else Outcome.FATAL
        }

    /**
     * Pause before retry [attempt] (0-based): exponential backoff with "equal jitter" (half fixed,
     * half random) so parallel parts do not hammer the server in step, capped at 30 s. A server's
     * Retry-After wins when present and sane. [random] is a value in [0, 1).
     */
    fun delayMs(
        attempt: Int,
        retryAfterMs: Long? = null,
        random: Double = Math.random(),
    ): Long {
        if (retryAfterMs != null && retryAfterMs in 0..MAX_RETRY_AFTER_MS) return retryAfterMs
        val exp = (BASE_MS shl attempt.coerceIn(0, 5)).coerceAtMost(CAP_MS)
        val half = exp / 2
        return half + (half * random.coerceIn(0.0, 0.999_999)).toLong()
    }

    /** Retry-After as delay-seconds or an HTTP date, in ms from [nowMs]; null when absent or unreadable. */
    fun parseRetryAfter(
        header: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Long? {
        val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toLongOrNull()?.let { return (it * 1000).coerceAtLeast(0) }
        val format =
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        val at = runCatching { format.parse(value)?.time }.getOrNull() ?: return null
        return (at - nowMs).coerceAtLeast(0)
    }
}

/** An HTTP failure the engine classified; [code] 0 = no answer. */
class HttpFailure(
    val code: Int,
    val retryAfterMs: Long? = null,
    message: String = if (code == 0) "Network error" else "HTTP $code",
) : java.io.IOException(message) {
    val outcome: RetryPolicy.Outcome get() = RetryPolicy.classify(code)
}

/** The file on the server changed since the download started (ETag / Last-Modified / size). */
class RemoteFileChanged : java.io.IOException("The file on the server changed")

/** An expected checksum that came with a request, e.g. "sha256:9f86d0…" or a bare hex digest. */
data class ExpectedChecksum(
    val algorithm: Algorithm,
    val hex: String,
) {
    enum class Algorithm(
        val javaName: String,
        val hexLength: Int,
        val label: String,
    ) {
        MD5("MD5", 32, "md5"),
        SHA1("SHA-1", 40, "sha1"),
        SHA256("SHA-256", 64, "sha256"),
        SHA512("SHA-512", 128, "sha512"),
    }

    fun encode(): String = "${algorithm.label}:$hex"

    fun matches(actualHex: String?): Boolean = actualHex != null && actualHex.trim().equals(hex, ignoreCase = true)

    companion object {
        private val HEX = Regex("^[0-9a-fA-F]+$")

        /** Reads "algo:hex", "algo=hex" or a bare hex digest (algorithm guessed from its length). */
        fun parse(text: String?): ExpectedChecksum? {
            val raw = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val sep = raw.indexOfFirst { it == ':' || it == '=' }
            val (label, hex) =
                if (sep > 0) {
                    raw.substring(0, sep).trim().lowercase().replace("-", "") to raw.substring(sep + 1).trim()
                } else {
                    null to raw
                }
            if (!HEX.matches(hex)) return null
            val algorithm =
                if (label != null) {
                    Algorithm.entries.firstOrNull { it.label == label } ?: return null
                } else {
                    Algorithm.entries.firstOrNull { it.hexLength == hex.length } ?: return null
                }
            if (hex.length != algorithm.hexLength) return null
            return ExpectedChecksum(algorithm, hex.lowercase())
        }
    }
}
