/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.downloader

/**
 * Extension point for links that are not a plain file (plan §6): site extractors (a yt-dlp based
 * engine), torrents and magnet links, FTP/SFTP. Each needs either a large native runtime (Python
 * for yt-dlp, libtorrent) or a protocol client OkHttp does not speak, so none ships in this build;
 * a resolver plugs in here without touching the engine:
 *
 * - [canHandle] says whether a link is yours;
 * - [resolve] turns it into one or more direct HTTP(S) files the segmented engine can fetch
 *   (with headers and mirrors), or throws [UnsupportedLinkException];
 * - [refresh] is called when a direct link expired (403/410) mid-download: hand back a fresh URL
 *   for the same bytes and the engine continues from the same byte (plan §6 "refresh expired links").
 */
interface LinkResolver {
    val id: String

    fun canHandle(url: String): Boolean

    fun resolve(url: String): List<ResolvedFile>

    fun refresh(item: DownloadItem): String? = null
}

/** One direct file a resolver found. */
data class ResolvedFile(
    val url: String,
    val fileName: String,
    val mimeType: String = "application/octet-stream",
    val headers: Map<String, String> = emptyMap(),
    val mirrors: List<String> = emptyList(),
    val sizeBytes: Long = -1,
    val checksum: String? = null,
)

class UnsupportedLinkException(
    val kind: LinkKind,
) : IllegalArgumentException("Unsupported link: ${kind.name.lowercase()}")

/** What kind of link the user gave, decided from its text alone. */
enum class LinkKind { HTTP, YOUTUBE, MAGNET, TORRENT_FILE, FTP, SFTP, HLS, DASH, UNKNOWN }

object LinkResolvers {
    private val resolvers = mutableListOf<LinkResolver>()

    @Synchronized
    fun register(resolver: LinkResolver) {
        resolvers.removeAll { it.id == resolver.id }
        resolvers += resolver
    }

    @Synchronized
    fun forUrl(url: String): LinkResolver? = resolvers.firstOrNull { runCatching { it.canHandle(url) }.getOrDefault(false) }

    /** A fresh URL for an expired download, from the resolver that handles its link; null when none can. */
    fun refresh(item: DownloadItem): String? = forUrl(item.url)?.let { r -> runCatching { r.refresh(item) }.getOrNull() }

    fun kind(url: String): LinkKind {
        val u = url.trim()
        val lower = u.lowercase()
        val path = lower.substringBefore('?').substringBefore('#')
        return when {
            lower.startsWith("magnet:") -> LinkKind.MAGNET
            lower.startsWith("ftp://") || lower.startsWith("ftps://") -> LinkKind.FTP
            lower.startsWith("sftp://") -> LinkKind.SFTP
            !(lower.startsWith("http://") || lower.startsWith("https://")) -> LinkKind.UNKNOWN
            LinkRules.isYouTube(u) -> LinkKind.YOUTUBE
            path.endsWith(".torrent") -> LinkKind.TORRENT_FILE
            path.endsWith(".m3u8") -> LinkKind.HLS
            path.endsWith(".mpd") -> LinkKind.DASH
            else -> LinkKind.HTTP
        }
    }

    /** True when this build can fetch [kind] itself (plain files; YouTube is handed to Mawja). */
    fun supported(kind: LinkKind): Boolean = kind == LinkKind.HTTP || kind == LinkKind.TORRENT_FILE
}
