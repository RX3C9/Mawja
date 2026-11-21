/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

import app.mawja.android.data.transcript.TranscriptCue
import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceRewindTest {
    private val cues = listOf(TranscriptCue(0, "a", 4000), TranscriptCue(4000, "b", 4000), TranscriptCue(8000, "c", 4000))

    @Test fun backToStartOfCurrentLine() = assertEquals(4000L, SentenceRewind.target(6500, cues))

    @Test fun justStartedMeansThePreviousLine() = assertEquals(4000L, SentenceRewind.target(8500, cues))

    @Test fun noCaptionsFallsBackFiveSeconds() = assertEquals(15_000L, SentenceRewind.target(20_000, emptyList()))

    @Test fun neverBeforeZero() = assertEquals(0L, SentenceRewind.target(2000, emptyList()))
}
