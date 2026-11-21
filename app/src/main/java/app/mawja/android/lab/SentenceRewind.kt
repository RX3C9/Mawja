/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

import app.mawja.android.data.transcript.TranscriptCue

/** "Take me back to the sentence": where a shake rewinds to, from the captions when there are any. */
object SentenceRewind {
    /** Within this much of a line's start, the viewer missed the line before it. */
    private const val JUST_STARTED_MS = 1_500L
    private const val FALLBACK_MS = 5_000L

    fun target(
        positionMs: Long,
        cues: List<TranscriptCue>,
    ): Long {
        val sorted = cues.filter { it.startMs <= positionMs }.sortedBy { it.startMs }
        if (sorted.isEmpty()) return (positionMs - FALLBACK_MS).coerceAtLeast(0L)
        val current = sorted.last()
        val pick =
            if (positionMs - current.startMs < JUST_STARTED_MS && sorted.size > 1) sorted[sorted.size - 2] else current
        return pick.startMs.coerceAtLeast(0L)
    }
}
