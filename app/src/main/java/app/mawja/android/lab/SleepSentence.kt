/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

import app.mawja.android.data.transcript.TranscriptCue

/**
 * "Finish the sentence": when a sleep timer runs out in the middle of someone speaking, playback
 * waits for the end of that sentence (a few seconds at most) instead of cutting the speaker off.
 */
object SleepSentence {
    /** Never hold the timer longer than this, whatever the captions say. */
    const val MAX_WAIT_MS = 12_000L

    private const val GAP_MS = 1_500L

    /** Real time to wait from [positionMs] (at [speed]) until the sentence being spoken ends; 0 when nothing is mid-sentence. */
    fun extraWaitMs(
        positionMs: Long,
        speed: Float,
        cues: List<TranscriptCue>,
    ): Long {
        val sorted = cues.filter { it.durationMs > 0 }.sortedBy { it.startMs }
        val first = sorted.indexOfFirst { positionMs >= it.startMs && positionMs < it.endMs }
        if (first < 0) return 0L
        var last = first
        while (
            !endsSentence(sorted[last].text) &&
            last + 1 < sorted.size &&
            sorted[last + 1].startMs - sorted[last].endMs <= GAP_MS &&
            sorted[last + 1].endMs - positionMs <= MAX_WAIT_MS
        ) {
            last++
        }
        val rate = if (speed.isFinite() && speed > 0f) speed else 1f
        return ((sorted[last].endMs - positionMs) / rate).toLong().coerceIn(0L, MAX_WAIT_MS)
    }

    private fun endsSentence(text: String): Boolean {
        val c = text.trimEnd().lastOrNull() ?: return true
        return c == '.' || c == '?' || c == '!' || c == '\u061F' || c == '\u2026'
    }
}
