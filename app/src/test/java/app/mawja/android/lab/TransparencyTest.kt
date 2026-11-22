/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

import app.mawja.android.data.recommendation.UserBrain
import org.junit.Assert.assertEquals
import org.junit.Test

class TransparencyTest {
    @Test fun topicsAreRankedByAffinity() {
        val brain = UserBrain(topicAffinities = mapOf("cooking" to 0.4, "physics" to 0.9, "news" to 0.1))
        assertEquals(listOf("physics", "cooking", "news"), Transparency.glimpse(brain).topics)
    }

    @Test fun limitsAndSkipsNonPositive() {
        val brain = UserBrain(topicAffinities = mapOf("a" to 0.9, "b" to 0.8, "c" to 0.0, "d" to -0.5, "e" to 0.7))
        assertEquals(listOf("a", "b"), Transparency.glimpse(brain, limit = 2).topics)
        assertEquals(listOf("a", "b", "e"), Transparency.glimpse(brain).topics)
    }

    @Test fun countsComeStraightFromTheProfile() {
        val brain =
            UserBrain(
                channelScores = mapOf("c1" to 0.6, "c2" to 0.7),
                totalInteractions = 42,
                blockedTopics = setOf("x"),
                blockedChannels = setOf("c9", "c8"),
            )
        val g = Transparency.glimpse(brain)
        assertEquals(2, g.channelsKnown)
        assertEquals(42, g.interactions)
        assertEquals(3, g.hidden)
    }

    @Test fun anEmptyProfileSaysNothing() {
        val g = Transparency.glimpse(UserBrain())
        assertEquals(emptyList<String>(), g.topics)
        assertEquals(0, g.interactions)
    }
}
