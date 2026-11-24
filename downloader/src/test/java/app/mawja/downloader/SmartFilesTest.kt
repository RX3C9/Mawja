/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartFilesTest {
    @Test
    fun readsExtensions() {
        assertEquals("mp4", SmartNaming.extension("Clip.MP4"))
        assertEquals("gz", SmartNaming.extension("archive.tar.gz"))
        assertEquals("", SmartNaming.extension("noext"))
        assertEquals("", SmartNaming.extension("v1.2 final"))
        assertEquals("", SmartNaming.extension("file.123"))
        assertEquals("mp4", SmartNaming.extensionFor("video/mp4; codecs=avc1"))
        assertEquals("", SmartNaming.extensionFor(null))
        assertEquals("application/pdf", SmartNaming.mimeFor("book.pdf"))
        assertEquals("application/octet-stream", SmartNaming.mimeFor("thing.unknownext"))
    }

    @Test
    fun cleansNames() {
        assertEquals("hidden file name.mp4", SmartNaming.clean("  ..hidden__file   name.mp4 "))
        assertEquals("a b c.txt", SmartNaming.clean("a/b:c?.txt"))
        assertEquals("ab.pdf", SmartNaming.clean("a\u200Bb.pdf"))
        assertEquals("فيديو جميل.mp4", SmartNaming.clean("فيديو جميل.mp4"))
        assertEquals("download", SmartNaming.clean(""))
        assertEquals("download", SmartNaming.clean("..."))
        assertEquals("x", SmartNaming.clean("", fallback = "x"))
    }

    @Test
    fun longNamesKeepTheirExtension() {
        val latin = SmartNaming.clean("x".repeat(300) + ".mp4")
        assertEquals(SmartNaming.MAX_NAME_BYTES, latin.length)
        assertTrue(latin.endsWith(".mp4"))
        val arabic = SmartNaming.clean("ع".repeat(200) + ".txt")
        assertTrue(arabic.toByteArray().size <= SmartNaming.MAX_NAME_BYTES)
        assertTrue(arabic.endsWith(".txt"))
    }

    @Test
    fun addsExtensionFromMime() {
        assertEquals("song.mp3", SmartNaming.withExtension("song", "audio/mpeg"))
        assertEquals("a.pdf", SmartNaming.withExtension("a.pdf", "video/mp4"))
        assertEquals("x", SmartNaming.withExtension("x", null))
    }

    @Test
    fun uniqueNamesCountUp() {
        val taken = setOf("a.mp4", "a (1).mp4")
        assertEquals("a (2).mp4", SmartNaming.unique("a.mp4") { it in taken })
        assertEquals("a (2).mp4", SmartNaming.unique("a (1).mp4") { it in taken })
        assertEquals("b.mp4", SmartNaming.unique("b.mp4") { it in taken })
        assertEquals("README (1)", SmartNaming.unique("README") { it == "README" })
    }

    @Test
    fun sortsIntoCategories() {
        assertEquals(FileCategory.VIDEO, SmartNaming.category("x.mkv", null))
        assertEquals(FileCategory.MUSIC, SmartNaming.category("x.flac", null))
        assertEquals(FileCategory.IMAGES, SmartNaming.category("x", "image/png"))
        assertEquals(FileCategory.APPS, SmartNaming.category("x.apk", null))
        assertEquals(FileCategory.ARCHIVES, SmartNaming.category("x.7z", null))
        assertEquals(FileCategory.DOCUMENTS, SmartNaming.category("x", "text/html"))
        assertEquals(FileCategory.OTHER, SmartNaming.category("x.bin", null))
    }

    @Test
    fun normalizesLinks() {
        assertEquals(
            "https://example.com/path?a=1&b=2",
            DuplicateFinder.normalizeUrl("HTTPS://Example.com:443/path/?utm_source=x&b=2&a=1#frag"),
        )
        assertEquals(DuplicateFinder.normalizeUrl("https://x.com/f.zip"), DuplicateFinder.normalizeUrl("https://X.com/f.zip?fbclid=abc"))
    }

    private fun record(
        id: String,
        url: String,
        name: String = "f.zip",
        total: Long = 1000,
        sha: String? = null,
        completed: Boolean = true,
        active: Boolean = false,
        finishedAt: Long = 1_000_000,
    ) = DuplicateFinder.Record(id, url, null, name, total, sha, completed, active, finishedAt)

    @Test
    fun findsDuplicates() {
        val records =
            listOf(
                record("q", "https://a.com/queued.zip", completed = false, active = true, finishedAt = 0),
                record("l", "https://a.com/f.zip?utm_source=x"),
                record("c", "https://b.com/other.zip", name = "other.zip", sha = "ABC"),
                record("s", "https://c.com/same.iso", name = "same.iso", total = 4242),
            )
        assertEquals(DuplicateFinder.Kind.IN_QUEUE, DuplicateFinder.find(records, "https://a.com/queued.zip")?.kind)
        assertEquals("l", DuplicateFinder.find(records, "https://A.com/f.zip")?.id)
        assertEquals(DuplicateFinder.Kind.SAME_LINK, DuplicateFinder.find(records, "https://A.com/f.zip")?.kind)
        assertEquals(DuplicateFinder.Kind.SAME_CONTENT, DuplicateFinder.find(records, "https://z.com/x", sha256 = "abc")?.kind)
        assertEquals(
            DuplicateFinder.Kind.SAME_FILE,
            DuplicateFinder.find(records, "https://mirror.com/same.iso", fileName = "SAME.iso", total = 4242)?.kind,
        )
        assertNull(DuplicateFinder.find(records, "https://mirror.com/same.iso", fileName = "same.iso", total = 1))
        assertNull(DuplicateFinder.find(records, "https://a.com/f.zip", excludeId = "l"))
        assertNull(DuplicateFinder.find(records, "https://new.com/file"))
    }

    @Test
    fun matchAgeInDays() {
        val day = 86_400_000L
        val match = DuplicateFinder.Match(DuplicateFinder.Kind.SAME_LINK, "x", "f", finishedAt = 10 * day)
        assertEquals(3, match.ageDays(13 * day + 5))
        assertEquals(0, match.copy(finishedAt = 0).ageDays(13 * day))
    }

    @Test
    fun learnsSpeedPerHour() {
        val speed = HourlySpeed()
        assertEquals(0L, speed.estimate(10))
        speed.record(10, 1000)
        assertEquals(1000L, speed.estimate(10))
        speed.record(10, 2000, weight = 0.5)
        assertEquals(1500L, speed.estimate(10))
        speed.record(10, -5)
        assertEquals(1500L, speed.estimate(10))
        assertEquals(1500L, speed.estimate(12))
        assertEquals(2L, speed.eta(3000, 10, liveBps = 0))
        assertEquals(0L, speed.eta(0, 10, liveBps = 0))
        assertEquals(1500L, HourlySpeed.decode(speed.encode()).estimate(10))
        assertEquals(0L, HourlySpeed.decode("garbage").estimate(10))
        assertEquals(0L, HourlySpeed.decode(null).estimate(10))
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun sniffsFileKinds() {
        assertEquals(FileSafety.Kind.ZIP, FileSafety.sniff(bytes(0x50, 0x4B, 0x03, 0x04)))
        assertEquals(FileSafety.Kind.APK, FileSafety.sniff(bytes(0x50, 0x4B, 0x03, 0x04), hasManifest = true))
        assertEquals(FileSafety.Kind.WINDOWS_EXE, FileSafety.sniff(bytes(0x4D, 0x5A, 0x90, 0x00)))
        assertEquals(FileSafety.Kind.PDF, FileSafety.sniff("%PDF-1.7".toByteArray()))
        assertEquals(FileSafety.Kind.MP4, FileSafety.sniff(bytes(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70)))
        assertEquals(FileSafety.Kind.WEBP, FileSafety.sniff("RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray()))
        assertEquals(FileSafety.Kind.UNKNOWN, FileSafety.sniff(ByteArray(0)))
        assertEquals(FileSafety.Kind.UNKNOWN, FileSafety.sniff(bytes(0x00, 0x01)))
    }

    @Test
    fun warnsAboutProgramsAndDisguises() {
        assertEquals(FileSafety.Verdict.DISGUISED, FileSafety.verdict("movie.mp4", FileSafety.Kind.WINDOWS_EXE))
        assertEquals(FileSafety.Verdict.DISGUISED, FileSafety.verdict("movie.mp4", FileSafety.Kind.APK))
        assertEquals(FileSafety.Verdict.APP, FileSafety.verdict("app.apk", FileSafety.Kind.ZIP))
        assertEquals(FileSafety.Verdict.APP, FileSafety.verdict("game.zip", FileSafety.Kind.APK))
        assertEquals(FileSafety.Verdict.EXECUTABLE, FileSafety.verdict("tool.exe", FileSafety.Kind.WINDOWS_EXE))
        assertEquals(FileSafety.Verdict.EXECUTABLE, FileSafety.verdict("setup.sh", FileSafety.Kind.UNKNOWN))
        assertEquals(FileSafety.Verdict.OK, FileSafety.verdict("doc.pdf", FileSafety.Kind.PDF))
    }

    @Test
    fun zipPathsNeverEscape() {
        assertEquals("a/b.txt", ZipPaths.safe("a/b.txt"))
        assertEquals("a/b", ZipPaths.safe("./a//b"))
        assertEquals("dir", ZipPaths.safe("dir/"))
        assertNull(ZipPaths.safe("../evil"))
        assertNull(ZipPaths.safe("a/../../b"))
        assertNull(ZipPaths.safe("/etc/passwd"))
        assertNull(ZipPaths.safe("C:\\win\\x"))
        assertNull(ZipPaths.safe(""))
    }
}
