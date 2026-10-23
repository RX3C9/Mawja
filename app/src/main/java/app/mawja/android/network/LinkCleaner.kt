/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.network

import android.content.Context
import androidx.core.content.edit

/**
 * Strips tracking parameters from links (PLAN_V2 #72) before they are shared, copied, or opened from
 * another app. Everything that changes what a link opens is kept: the video (`v`), the start time
 * (`t`), the playlist (`list`, `index`), any other parameter and the `#fragment`, all in their order.
 *
 * The rules are pure; only the on/off switch (on by default) touches Android, and it is read
 * synchronously at every share, so it lives in plain SharedPreferences cached in [enabled].
 */
object LinkCleaner {
    /** Parameters dropped from every link: they only tell a site where the click came from. */
    private val TRACKERS =
        setOf(
            "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "igshid", "igsh", "mc_cid", "mc_eid",
            "yclid", "_hsenc", "_hsmi", "ref_src", "twclid", "ttclid", "srsltid",
        )

    /** Parameters dropped only on hosts where they are known to be share trackers (`si` = share id). */
    private val SHARE_TRACKERS = setOf("si", "feature", "pp")

    private val SHARE_TRACKER_HOSTS =
        listOf("youtube.com", "youtu.be", "youtube-nocookie.com", "spotify.com", "spotify.link")

    private val URL_REGEX = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)
    private const val TRAILING_PUNCTUATION = ".,;:!?)]}»…"

    /** [url] without its tracking parameters; anything that is not an http(s) link comes back as is. */
    fun clean(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return url
        val scheme = url.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return url

        val hashAt = url.indexOf('#')
        val beforeFragment = if (hashAt >= 0) url.substring(0, hashAt) else url
        val fragment = if (hashAt >= 0) url.substring(hashAt) else ""
        val queryAt = beforeFragment.indexOf('?')
        if (queryAt < 0) return url
        val base = beforeFragment.substring(0, queryAt)
        val query = beforeFragment.substring(queryAt + 1)
        val host = hostOf(base)
        val shareHost = SHARE_TRACKER_HOSTS.any { host == it || host.endsWith(".$it") }

        val params = query.split('&')
        val kept =
            params.filter { param ->
                if (param.isEmpty()) return@filter false
                val key = param.substringBefore('=').lowercase()
                !(key.startsWith("utm_") || key in TRACKERS || (shareHost && key in SHARE_TRACKERS))
            }
        if (kept.size == params.size) return url
        val cleanedQuery = kept.joinToString("&")
        return buildString {
            append(base)
            if (cleanedQuery.isNotEmpty()) append('?').append(cleanedQuery)
            append(fragment)
        }
    }

    /** [text] with every http(s) link in it cleaned; the words around the links are untouched. */
    fun cleanText(text: String): String {
        if (!text.contains("://")) return text
        return URL_REGEX.replace(text) { match ->
            val raw = match.value
            val core = raw.trimEnd { it in TRAILING_PUNCTUATION }
            clean(core) + raw.substring(core.length)
        }
    }

    // --- The switch -------------------------------------------------------------------------------

    private const val PREFS = "mawja_link_cleaner"
    private const val KEY_ENABLED = "enabled"

    @Volatile
    private var enabled: Boolean = true

    /** Loads the saved switch; called once from Application.onCreate. */
    fun init(context: Context) {
        enabled = prefs(context).getBoolean(KEY_ENABLED, true)
    }

    fun isEnabled(): Boolean = enabled

    fun setEnabled(
        context: Context,
        on: Boolean,
    ) {
        prefs(context).edit { putBoolean(KEY_ENABLED, on) }
        enabled = on
    }

    /** What a share or copy should carry: [text] cleaned while the switch is on. */
    fun forShare(text: String): String = if (enabled) cleanText(text) else text

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun hostOf(base: String): String {
        val afterScheme = base.substringAfter("://")
        val authority = afterScheme.substringBefore('/')
        return authority
            .substringAfterLast('@')
            .substringBefore(':')
            .lowercase()
            .removePrefix("www.")
            .removePrefix("m.")
    }
}
