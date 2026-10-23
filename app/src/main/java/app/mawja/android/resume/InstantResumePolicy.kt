/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.resume

/**
 * Instant Resume (#75), the pure part: which page is worth reopening after the process was killed,
 * how it is written down, and where a list was scrolled to on it. No Android types, so it is tested
 * on the JVM.
 */
object InstantResumePolicy {
    /** A page older than this opens the start screen again: by then it is a new visit, not a resume. */
    const val MAX_AGE_MS: Long = 12 * 60 * 60 * 1000L

    /**
     * Routes that start something or only pass through (players, widget launchers, onboarding,
     * the recogniser that opens the microphone). Reopening them would play, record or loop.
     */
    private val TRANSIENT_PREFIXES =
        listOf(
            "onboarding",
            "player/",
            "musicPlayer",
            "widgetPlaylist",
            "onRepeatShuffle",
            "shorts",
            "update",
            "musicRecognize",
        )

    fun isRestorable(pattern: String): Boolean = pattern.isNotBlank() && TRANSIENT_PREFIXES.none { pattern.startsWith(it) }

    /**
     * The concrete route for a navigation [pattern] ("playlist/{playlistId}", "channel?url={channelUrl}")
     * with its [args] filled in and encoded. A missing path argument makes it unrestorable (null);
     * a missing query argument just drops that query pair.
     */
    fun concreteRoute(
        pattern: String?,
        args: Map<String, String?>,
    ): String? {
        if (pattern == null || !isRestorable(pattern)) return null
        val queryStart = pattern.indexOf('?')
        val path = if (queryStart < 0) pattern else pattern.substring(0, queryStart)
        val query = if (queryStart < 0) "" else pattern.substring(queryStart + 1)
        if (PLACEHOLDER.findAll(path).any { args[it.groupValues[1]].isNullOrEmpty() }) return null
        val filledPath = PLACEHOLDER.replace(path) { match -> encode(args[match.groupValues[1]].orEmpty()) }
        val pairs =
            query.split('&').filter { it.isNotEmpty() }.mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq < 0) return@mapNotNull pair
                val key = pair.substring(0, eq)
                val rawValue = pair.substring(eq + 1)
                val placeholder = PLACEHOLDER.matchEntire(rawValue)
                if (placeholder == null) {
                    pair
                } else {
                    args[placeholder.groupValues[1]]?.takeIf { it.isNotEmpty() }?.let { "$key=${encode(it)}" }
                }
            }
        if (filledPath.isBlank()) return null
        return if (pairs.isEmpty()) filledPath else filledPath + "?" + pairs.joinToString("&")
    }

    /** Percent-encodes like android.net.Uri.encode: letters, digits and -_.~!*'() stay as they are. */
    fun encode(value: String): String {
        val out = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xff
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in "-_.~!*'()")) {
                out.append(ch)
            } else {
                out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0f])
            }
        }
        return out.toString()
    }

    /** The route to reopen on a cold launch, or null for the start screen. */
    fun routeToRestore(
        saved: SavedRoute?,
        now: Long,
        enabled: Boolean,
    ): String? {
        if (!enabled || saved == null) return null
        if (saved.route.isBlank() || !isRestorable(saved.route)) return null
        val age = now - saved.savedAt
        if (age < 0 || age > MAX_AGE_MS) return null
        return saved.route
    }

    /** A list position is put back only on the page that was reopened, and only while it is fresh. */
    fun scrollToRestore(
        saved: SavedScroll?,
        restoredRoute: String?,
        now: Long,
    ): SavedScroll? {
        if (saved == null || restoredRoute == null || saved.route != restoredRoute) return null
        val age = now - saved.savedAt
        if (age < 0 || age > MAX_AGE_MS) return null
        if (saved.index <= 0 && saved.offset <= 0) return null
        return saved
    }

    // --- Storage codec (one line each in SharedPreferences) ----------------------------------------

    fun encodeRoute(route: SavedRoute): String = "1\n${route.savedAt}\n${route.route}"

    fun decodeRoute(raw: String?): SavedRoute? {
        val parts = raw?.split('\n', limit = 3) ?: return null
        if (parts.size != 3 || parts[0] != "1") return null
        val at = parts[1].toLongOrNull() ?: return null
        return SavedRoute(parts[2], at).takeIf { it.route.isNotBlank() }
    }

    fun encodeScroll(scroll: SavedScroll): String = "1\n${scroll.savedAt}\n${scroll.index}\n${scroll.offset}\n${scroll.route}"

    fun decodeScroll(raw: String?): SavedScroll? {
        val parts = raw?.split('\n', limit = 5) ?: return null
        if (parts.size != 5 || parts[0] != "1") return null
        val at = parts[1].toLongOrNull() ?: return null
        val index = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val offset = parts[3].toIntOrNull()?.coerceAtLeast(0) ?: return null
        return SavedScroll(parts[4], index, offset, at)
    }

    /** Scroll writes wait until the list has been still this long, so a fling costs one write. */
    const val SCROLL_SETTLE_MS: Long = 1_500L

    private val PLACEHOLDER = Regex("\\{([^}]+)\\}")
    private val HEX = "0123456789ABCDEF".toCharArray()
}

data class SavedRoute(
    val route: String,
    val savedAt: Long,
)

data class SavedScroll(
    val route: String,
    val index: Int,
    val offset: Int,
    val savedAt: Long,
)
