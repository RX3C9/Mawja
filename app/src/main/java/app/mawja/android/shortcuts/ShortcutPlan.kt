/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.shortcuts

import java.net.URI

/**
 * Dynamic launcher shortcuts (#79), the pure part: which shortcuts the icon shows, the mawja://
 * links they open, and how such a link is read back. No Android types, so it is tested on the JVM.
 *
 * Links (also usable by announcements, which may point at mawja://):
 *  mawja://search · mawja://downloads · mawja://library · mawja://history · mawja://subscriptions ·
 *  mawja://music · mawja://settings · mawja://dj (Smart DJ: shuffles what you have on repeat) ·
 *  mawja://playlist/{id} (mawja://playlist/{id}?music=1 opens it as a music collection)
 */
object ShortcutPlan {
    const val SCHEME = "mawja"

    /** Shortcut ids, stable so launchers keep pinned copies pointing at the same thing. */
    const val ID_PLAYLIST = "last_playlist"
    const val ID_SEARCH = "search"
    const val ID_DOWNLOADS = "downloads"
    const val ID_DJ = "smart_dj"

    private val SIMPLE_HOSTS = setOf("search", "downloads", "library", "history", "subscriptions", "music", "settings")
    private val PLAYLIST_ID = Regex("^[A-Za-z0-9_.:\\-]{1,128}$")

    /** The playlist the user opened last, shown first on the icon. */
    data class LastPlaylist(
        val id: String,
        val title: String,
        val isMusic: Boolean,
    )

    enum class Kind { PLAYLIST, SEARCH, DOWNLOADS, DJ }

    data class Shortcut(
        val id: String,
        val kind: Kind,
        val link: String,
        /** The playlist's own name for [Kind.PLAYLIST]; the others use string resources. */
        val label: String?,
        val rank: Int,
    )

    /**
     * Most useful first; launchers show only [maxCount] (usually 4–5, some fewer), so the list is cut
     * to it. Smart DJ needs music history to have anything to shuffle, so it is left out without it.
     */
    fun plan(
        lastPlaylist: LastPlaylist?,
        hasMusic: Boolean,
        maxCount: Int,
    ): List<Shortcut> {
        val all =
            buildList {
                lastPlaylist?.takeIf { PLAYLIST_ID.matches(it.id) && it.title.isNotBlank() }?.let {
                    add(Shortcut(ID_PLAYLIST, Kind.PLAYLIST, playlistLink(it.id, it.isMusic), it.title.trim().take(MAX_LABEL), 0))
                }
                add(Shortcut(ID_SEARCH, Kind.SEARCH, "$SCHEME://search", null, 0))
                add(Shortcut(ID_DOWNLOADS, Kind.DOWNLOADS, "$SCHEME://downloads", null, 0))
                if (hasMusic) add(Shortcut(ID_DJ, Kind.DJ, "$SCHEME://dj", null, 0))
            }
        return all.take(maxCount.coerceAtLeast(0)).mapIndexed { index, s -> s.copy(rank = index) }
    }

    /** One string per plan, so the shortcuts are pushed again only when something on them changed. */
    fun signature(
        plan: List<Shortcut>,
        language: String,
    ): String = language + "|" + plan.joinToString("|") { "${it.id}=${it.link}=${it.label.orEmpty()}" }

    fun playlistLink(
        id: String,
        isMusic: Boolean,
    ): String = "$SCHEME://playlist/$id" + if (isMusic) "?music=1" else ""

    /** What a mawja:// link asks for; null for anything that is not one of ours. */
    sealed interface Target {
        data class Page(
            val route: String,
        ) : Target

        data class Playlist(
            val id: String,
            val isMusic: Boolean,
        ) : Target

        data object SmartDj : Target
    }

    fun parse(link: String?): Target? {
        if (link.isNullOrBlank()) return null
        val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        val host = uri.host?.lowercase() ?: uri.rawAuthority?.lowercase() ?: return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        return when {
            host in SIMPLE_HOSTS && segments.isEmpty() -> Target.Page(host)
            host == "dj" && segments.isEmpty() -> Target.SmartDj
            host == "playlist" && segments.size == 1 && PLAYLIST_ID.matches(segments[0]) -> {
                val music = uri.query.orEmpty().split('&').any { it == "music=1" || it == "music=true" }
                Target.Playlist(segments[0], music)
            }
            else -> null
        }
    }

    private const val MAX_LABEL = 40
}
