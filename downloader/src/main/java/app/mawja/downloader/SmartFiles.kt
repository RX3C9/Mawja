/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.downloader

import java.util.Locale

/** Where a finished file is sorted to (plan §6 "auto-sort"). Folder names stay English on disk. */
enum class FileCategory(
    val folder: String,
) {
    VIDEO("Video"),
    MUSIC("Music"),
    IMAGES("Images"),
    DOCUMENTS("Documents"),
    APPS("Apps"),
    ARCHIVES("Archives"),
    OTHER("Other"),
}

/** Clean, readable file names and the category of a file (pure, JVM-tested). */
object SmartNaming {
    /** Longest name kept, in UTF-8 bytes (most Android file systems allow 255). */
    const val MAX_NAME_BYTES = 180

    private val ILLEGAL = Regex("""[\\/:*?"<>|\u0000-\u001F\u007F]""")
    private val INVISIBLE = Regex("""[\u200B-\u200F\u202A-\u202E\u2066-\u2069\uFEFF]""")
    private val SPACES = Regex("""[\s_]+""")
    private val REPEATED_PUNCT = Regex("""([.\-])\1+""")
    private val COUNTER_TAIL = Regex("""\s*\(\d+\)$""")

    private val VIDEO = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "flv", "wmv", "ts", "mpg", "mpeg")
    private val MUSIC = setOf("mp3", "m4a", "aac", "opus", "ogg", "flac", "wav", "wma", "amr", "mka")
    private val IMAGES = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "avif")
    private val DOCUMENTS =
        setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "txt", "rtf", "epub", "csv", "md", "srt", "vtt")
    private val APPS = setOf("apk", "apks", "xapk", "aab", "exe", "msi", "dmg", "deb", "rpm", "appimage")
    private val ARCHIVES = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "iso")

    private val MIME_EXT =
        mapOf(
            "video/mp4" to "mp4",
            "video/webm" to "webm",
            "video/x-matroska" to "mkv",
            "video/quicktime" to "mov",
            "video/mp2t" to "ts",
            "audio/mpeg" to "mp3",
            "audio/mp4" to "m4a",
            "audio/aac" to "aac",
            "audio/ogg" to "ogg",
            "audio/opus" to "opus",
            "audio/flac" to "flac",
            "audio/wav" to "wav",
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/gif" to "gif",
            "image/webp" to "webp",
            "application/pdf" to "pdf",
            "application/zip" to "zip",
            "application/x-7z-compressed" to "7z",
            "application/vnd.rar" to "rar",
            "application/x-rar-compressed" to "rar",
            "application/gzip" to "gz",
            "application/vnd.android.package-archive" to "apk",
            "application/epub+zip" to "epub",
            "text/plain" to "txt",
            "text/csv" to "csv",
        )

    /** The lower-case extension, or "" (an extension has 1-10 characters, no spaces and at least one letter). */
    fun extension(name: String): String =
        name
            .substringAfterLast('.', "")
            .takeIf { it.length in 1..10 && !it.contains(' ') && it.any(Char::isLetter) }
            ?.lowercase(Locale.ROOT)
            .orEmpty()

    /** The usual extension for [mime] (parameters ignored), or "" when unknown. */
    fun extensionFor(mime: String?): String = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.let { MIME_EXT[it] }.orEmpty()

    /** The MIME type for a file name, or application/octet-stream. */
    fun mimeFor(name: String): String {
        val ext = extension(name)
        return MIME_EXT.entries.firstOrNull { it.value == ext }?.key ?: "application/octet-stream"
    }

    /**
     * A readable name without odd symbols: illegal and invisible characters out, runs of spaces,
     * underscores, dots and dashes squeezed, no leading dots (hidden files), trimmed to
     * [MAX_NAME_BYTES] while keeping the extension. Arabic and other scripts are kept as they are.
     */
    fun clean(
        raw: String,
        fallback: String = "download",
    ): String {
        var name = raw.replace(INVISIBLE, "").replace(ILLEGAL, " ")
        name = name.replace(SPACES, " ").replace(REPEATED_PUNCT, "$1").trim().trimStart('.', '-', ' ').trimEnd('.', ' ')
        if (name.isBlank()) return fallback
        val ext = extension(name)
        val base = if (ext.isNotEmpty()) name.dropLast(ext.length + 1).trimEnd('.', ' ', '-') else name
        val kept = fitBytes(base.ifBlank { fallback }, MAX_NAME_BYTES - (if (ext.isEmpty()) 0 else ext.toByteArray().size + 1))
        return if (ext.isEmpty()) kept else "$kept.$ext"
    }

    /** [name] with the extension [mime] implies when it has none (or a wrong-looking one is absent). */
    fun withExtension(
        name: String,
        mime: String?,
    ): String {
        if (extension(name).isNotEmpty()) return name
        val ext = extensionFor(mime)
        return if (ext.isEmpty()) name else "$name.$ext"
    }

    /** The first of "name.ext", "name (1).ext", "name (2).ext" … that [taken] does not report as used. */
    fun unique(
        name: String,
        taken: (String) -> Boolean,
    ): String {
        if (!taken(name)) return name
        val ext = extension(name)
        val base = (if (ext.isNotEmpty()) name.dropLast(ext.length + 1) else name).replace(COUNTER_TAIL, "").ifBlank { "download" }
        for (n in 1..999) {
            val candidate = if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext"
            if (!taken(candidate)) return candidate
        }
        return if (ext.isEmpty()) "$base (${System.currentTimeMillis()})" else "$base (${System.currentTimeMillis()}).$ext"
    }

    fun category(
        name: String,
        mime: String?,
    ): FileCategory {
        val ext = extension(name)
        val type = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            ext in VIDEO -> FileCategory.VIDEO
            ext in MUSIC -> FileCategory.MUSIC
            ext in IMAGES -> FileCategory.IMAGES
            ext in DOCUMENTS -> FileCategory.DOCUMENTS
            ext in APPS -> FileCategory.APPS
            ext in ARCHIVES -> FileCategory.ARCHIVES
            type.startsWith("video/") -> FileCategory.VIDEO
            type.startsWith("audio/") -> FileCategory.MUSIC
            type.startsWith("image/") -> FileCategory.IMAGES
            type == "application/pdf" || type.startsWith("text/") -> FileCategory.DOCUMENTS
            type == "application/vnd.android.package-archive" -> FileCategory.APPS
            type in setOf("application/zip", "application/x-7z-compressed", "application/vnd.rar", "application/gzip") -> FileCategory.ARCHIVES
            else -> FileCategory.OTHER
        }
    }

    private fun fitBytes(
        text: String,
        maxBytes: Int,
    ): String {
        if (text.toByteArray().size <= maxBytes) return text
        val out = StringBuilder()
        var used = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val chunk = String(Character.toChars(cp))
            val size = chunk.toByteArray().size
            if (used + size > maxBytes) break
            out.append(chunk)
            used += size
            i += Character.charCount(cp)
        }
        return out.toString().trimEnd(' ', '.', '-')
    }
}

/** "You already have this from 3 days ago" (plan §6 duplicate detector). */
object DuplicateFinder {
    data class Record(
        val id: String,
        val url: String,
        val sourceId: String?,
        val fileName: String,
        val total: Long,
        val sha256: String?,
        val completed: Boolean,
        val active: Boolean,
        val finishedAt: Long,
    )

    enum class Kind {
        /** Already queued or downloading. */
        IN_QUEUE,

        /** Same link (or same source id) finished before. */
        SAME_LINK,

        /** A finished file with the same name and size. */
        SAME_FILE,

        /** A finished file with the same SHA-256. */
        SAME_CONTENT,
    }

    data class Match(
        val kind: Kind,
        val id: String,
        val fileName: String,
        val finishedAt: Long,
    ) {
        fun ageDays(now: Long): Int = if (finishedAt <= 0) 0 else ((now - finishedAt).coerceAtLeast(0) / 86_400_000L).toInt()
    }

    /** The strongest match for a new download, or null. [total] / [sha256] may be unknown (-1 / null). */
    fun find(
        records: List<Record>,
        url: String,
        sourceId: String? = null,
        fileName: String? = null,
        total: Long = -1,
        sha256: String? = null,
        excludeId: String? = null,
    ): Match? {
        val pool = records.filter { it.id != excludeId }
        val key = normalizeUrl(url)
        pool.firstOrNull { it.active && (normalizeUrl(it.url) == key || (sourceId != null && it.sourceId == sourceId)) }?.let {
            return Match(Kind.IN_QUEUE, it.id, it.fileName, 0)
        }
        val done = pool.filter { it.completed }.sortedByDescending { it.finishedAt }
        if (sha256 != null) {
            done.firstOrNull { it.sha256.equals(sha256, ignoreCase = true) }?.let { return Match(Kind.SAME_CONTENT, it.id, it.fileName, it.finishedAt) }
        }
        done.firstOrNull { normalizeUrl(it.url) == key || (sourceId != null && it.sourceId == sourceId) }?.let {
            return Match(Kind.SAME_LINK, it.id, it.fileName, it.finishedAt)
        }
        if (fileName != null && total > 0) {
            done.firstOrNull { it.total == total && it.fileName.equals(fileName, ignoreCase = true) }?.let {
                return Match(Kind.SAME_FILE, it.id, it.fileName, it.finishedAt)
            }
        }
        return null
    }

    /** A link without its fragment, default port, trailing slash and common tracking parameters. */
    fun normalizeUrl(url: String): String {
        val noFragment = url.trim().substringBefore('#')
        val scheme = noFragment.substringBefore("://", "").lowercase(Locale.ROOT)
        val rest = noFragment.substringAfter("://", noFragment)
        val hostPort = rest.substringBefore('/').substringBefore('?').lowercase(Locale.ROOT)
        val host =
            when {
                scheme == "https" && hostPort.endsWith(":443") -> hostPort.removeSuffix(":443")
                scheme == "http" && hostPort.endsWith(":80") -> hostPort.removeSuffix(":80")
                else -> hostPort
            }
        val pathAndQuery = rest.removePrefix(rest.substringBefore('/').substringBefore('?'))
        val path = pathAndQuery.substringBefore('?').trimEnd('/')
        val query =
            pathAndQuery
                .substringAfter('?', "")
                .split('&')
                .filter { it.isNotBlank() }
                .filterNot { p -> TRACKING.any { p.substringBefore('=').lowercase(Locale.ROOT).startsWith(it) } }
                .sorted()
                .joinToString("&")
        return "$scheme://$host$path" + if (query.isEmpty()) "" else "?$query"
    }

    private val TRACKING = listOf("utm_", "fbclid", "gclid", "igshid", "mc_cid", "mc_eid", "ref_src")
}

/**
 * Learns how fast this network is at each hour of the day (plan §6 "real ETA"): an exponential
 * average per hour, so a queued download can say when it will really finish.
 */
class HourlySpeed(
    private val buckets: LongArray = LongArray(24),
) {
    /** Folds a speed sample (bytes/s) into [hour]'s average; 0 or negative samples are ignored. */
    @Synchronized
    fun record(
        hour: Int,
        bps: Long,
        weight: Double = 0.2,
    ) {
        if (bps <= 0) return
        val h = Math.floorMod(hour, 24)
        val old = buckets[h]
        buckets[h] = if (old <= 0) bps else (old + (bps - old) * weight).toLong()
    }

    /** The learned speed for [hour], borrowing the nearest learned hour, or 0 when nothing is known. */
    @Synchronized
    fun estimate(hour: Int): Long {
        val h = Math.floorMod(hour, 24)
        for (d in 0..12) {
            val a = buckets[Math.floorMod(h + d, 24)]
            val b = buckets[Math.floorMod(h - d, 24)]
            if (a > 0 && b > 0) return (a + b) / 2
            if (a > 0) return a
            if (b > 0) return b
        }
        return 0
    }

    /**
     * Seconds left for [remaining] bytes: the live speed when there is one, blended with the
     * learned hour (live speed counts 70%), the learned hour alone when nothing is moving.
     */
    fun eta(
        remaining: Long,
        hour: Int,
        liveBps: Long,
    ): Long {
        if (remaining <= 0) return 0
        val learned = estimate(hour)
        val bps =
            when {
                liveBps > 0 && learned > 0 -> (liveBps * 0.7 + learned * 0.3).toLong()
                liveBps > 0 -> liveBps
                else -> learned
            }
        return DownloadMath.etaSeconds(remaining, bps)
    }

    @Synchronized
    fun encode(): String = buckets.joinToString(",")

    companion object {
        fun decode(text: String?): HourlySpeed {
            val values = text?.split(',')?.mapNotNull { it.trim().toLongOrNull() }
            return if (values != null && values.size == 24) HourlySpeed(values.toLongArray()) else HourlySpeed()
        }
    }
}

/** A first look at a finished file's bytes: what it really is, and whether to warn before opening. */
object FileSafety {
    enum class Kind { APK, ZIP, RAR, SEVEN_Z, GZIP, PDF, PNG, JPEG, GIF, WEBP, MP4, MKV, MP3, OGG, FLAC, WINDOWS_EXE, ELF, SCRIPT, UNKNOWN }

    enum class Verdict {
        OK,

        /** An Android app: installs code; only from sources you trust. */
        APP,

        /** A Windows or Linux program, or a script. */
        EXECUTABLE,

        /** The name says one thing, the bytes another (e.g. "movie.mp4" that is a program). */
        DISGUISED,
    }

    /** How many leading bytes [sniff] needs. */
    const val HEADER_BYTES = 64

    /** [header] is the start of the file; [hasManifest] = a zip that holds AndroidManifest.xml. */
    fun sniff(
        header: ByteArray,
        hasManifest: Boolean = false,
    ): Kind {
        fun at(
            offset: Int,
            vararg bytes: Int,
        ) = header.size >= offset + bytes.size && bytes.indices.all { header[offset + it].toInt() and 0xFF == bytes[it] }
        return when {
            at(0, 0x50, 0x4B, 0x03, 0x04) || at(0, 0x50, 0x4B, 0x05, 0x06) -> if (hasManifest) Kind.APK else Kind.ZIP
            at(0, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) -> Kind.RAR
            at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> Kind.SEVEN_Z
            at(0, 0x1F, 0x8B) -> Kind.GZIP
            at(0, 0x25, 0x50, 0x44, 0x46) -> Kind.PDF
            at(0, 0x89, 0x50, 0x4E, 0x47) -> Kind.PNG
            at(0, 0xFF, 0xD8, 0xFF) -> Kind.JPEG
            at(0, 0x47, 0x49, 0x46, 0x38) -> Kind.GIF
            at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50) -> Kind.WEBP
            at(4, 0x66, 0x74, 0x79, 0x70) -> Kind.MP4
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> Kind.MKV
            at(0, 0x49, 0x44, 0x33) || at(0, 0xFF, 0xFB) || at(0, 0xFF, 0xF3) -> Kind.MP3
            at(0, 0x4F, 0x67, 0x67, 0x53) -> Kind.OGG
            at(0, 0x66, 0x4C, 0x61, 0x43) -> Kind.FLAC
            at(0, 0x4D, 0x5A) -> Kind.WINDOWS_EXE
            at(0, 0x7F, 0x45, 0x4C, 0x46) -> Kind.ELF
            at(0, 0x23, 0x21) -> Kind.SCRIPT
            else -> Kind.UNKNOWN
        }
    }

    fun verdict(
        fileName: String,
        kind: Kind,
    ): Verdict {
        val ext = SmartNaming.extension(fileName)
        val runnable = kind == Kind.WINDOWS_EXE || kind == Kind.ELF || kind == Kind.SCRIPT
        val namedRunnable = ext in setOf("exe", "msi", "bat", "cmd", "sh", "elf", "bin", "com", "scr")
        val namedMedia = SmartNaming.category(fileName, null).let { it == FileCategory.VIDEO || it == FileCategory.MUSIC || it == FileCategory.IMAGES || it == FileCategory.DOCUMENTS }
        return when {
            (runnable || kind == Kind.APK) && namedMedia -> Verdict.DISGUISED
            kind == Kind.APK || ext in setOf("apk", "apks", "xapk") -> Verdict.APP
            runnable || namedRunnable -> Verdict.EXECUTABLE
            else -> Verdict.OK
        }
    }
}

/** Zip extraction guard: entry names that would escape the target folder ("zip slip") are refused. */
object ZipPaths {
    /** A safe relative path for [entryName], or null to skip the entry. */
    fun safe(entryName: String): String? {
        val parts =
            entryName
                .replace('\\', '/')
                .split('/')
                .filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        if (entryName.startsWith("/") || entryName.matches(Regex("^[A-Za-z]:.*"))) return null
        return parts.joinToString("/") { SmartNaming.clean(it, fallback = "_") }
    }
}
