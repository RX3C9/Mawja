/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ai

import app.mawja.android.data.transcript.TranscriptCue

/**
 * Study notes (P10 #130), made on the phone from the transcript: the video is cut into its chapters
 * (the video's own, else [VideoInsights.autoChapters]) and each part keeps the sentences that carry
 * the most. The result is plain text with timestamps, ready to share or paste into any notes app.
 */
object StudyNotes {
    private const val PER_SECTION = 2
    private const val WHOLE_VIDEO = 6

    data class Section(
        val startMs: Long,
        val title: String?,
        val points: List<VideoInsights.Moment>,
    )

    fun sections(
        cues: List<TranscriptCue>,
        chapters: List<VideoInsights.Chapter> = VideoInsights.autoChapters(cues),
    ): List<Section> {
        val sorted = cues.filter { it.text.isNotBlank() }.sortedBy { it.startMs }
        if (sorted.isEmpty()) return emptyList()
        if (chapters.size < 2) {
            val pts = VideoInsights.keyMoments(sorted, WHOLE_VIDEO)
            return if (pts.isEmpty()) emptyList() else listOf(Section(sorted.first().startMs, null, pts))
        }
        val cuts = chapters.sortedBy { it.startMs }
        return cuts.mapIndexedNotNull { i, ch ->
            val end = cuts.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
            val inside = sorted.filter { it.startMs >= ch.startMs && it.startMs < end }
            val pts = VideoInsights.keyMoments(inside, PER_SECTION)
            if (pts.isEmpty()) null else Section(ch.startMs, ch.title, pts)
        }
    }

    /** "00:00" or "1:02:03", the same shape YouTube shows. */
    fun stamp(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** Empty when the transcript has nothing worth noting. [heading] goes on top when given. */
    fun text(
        cues: List<TranscriptCue>,
        heading: String? = null,
        chapters: List<VideoInsights.Chapter> = VideoInsights.autoChapters(cues),
    ): String {
        val parts = sections(cues, chapters)
        if (parts.isEmpty()) return ""
        return buildString {
            if (!heading.isNullOrBlank()) append(heading.trim()).append("\n\n")
            parts.forEachIndexed { i, s ->
                if (i > 0) append('\n')
                if (s.title != null) append("▸ ").append(stamp(s.startMs)).append("  ").append(s.title).append('\n')
                s.points.forEach { append("• ").append(stamp(it.startMs)).append("  ").append(it.text).append('\n') }
            }
        }.trimEnd()
    }
}
