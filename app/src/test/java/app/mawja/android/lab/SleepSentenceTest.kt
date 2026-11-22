/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

import app.mawja.android.data.transcript.TranscriptCue
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepSentenceTest {
    private val cues =
        listOf(
            TranscriptCue(0, "Hello and welcome to the show,", 3000),
            TranscriptCue(3000, "today we talk about tides.", 3000),
            TranscriptCue(9000, "Next topic.", 2000),
        )

    @Test fun waitsThroughToTheEndOfTheSentence() = assertEquals(4000L, SleepSentence.extraWaitMs(2000, 1f, cues))

    @Test fun lastLineOfASentenceOnlyWaitsForItself() = assertEquals(1000L, SleepSentence.extraWaitMs(5000, 1f, cues))

    @Test fun silenceBetweenLinesMeansNoWait() = assertEquals(0L, SleepSentence.extraWaitMs(7000, 1f, cues))

    @Test fun noCaptionsMeansNoWait() = assertEquals(0L, SleepSentence.extraWaitMs(2000, 1f, emptyList()))

    @Test fun fasterPlaybackWaitsLessRealTime() = assertEquals(2000L, SleepSentence.extraWaitMs(2000, 2f, cues))

    @Test fun neverWaitsLongerThanTheCap() {
        val long = listOf(TranscriptCue(0, "A very long sentence without an ending", 60_000))
        assertEquals(SleepSentence.MAX_WAIT_MS, SleepSentence.extraWaitMs(1000, 1f, long))
    }

    @Test fun arabicQuestionMarkEndsASentence() {
        val ar = listOf(TranscriptCue(0, "هل تعرف الإجابة\u061F", 2000), TranscriptCue(2000, "نكمل", 2000))
        assertEquals(1000L, SleepSentence.extraWaitMs(1000, 1f, ar))
    }
}
