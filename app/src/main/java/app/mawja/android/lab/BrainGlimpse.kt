/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

import app.mawja.android.data.recommendation.UserBrain

/** What Mawja has learned about you, in four numbers and a few words; computed from the on-device profile only. */
data class BrainGlimpse(
    val topics: List<String>,
    val channelsKnown: Int,
    val interactions: Int,
    val hidden: Int,
)

/** "Transparent mode": shows the taste profile instead of keeping it a secret. */
object Transparency {
    fun glimpse(
        brain: UserBrain,
        limit: Int = 5,
    ): BrainGlimpse =
        BrainGlimpse(
            topics =
                brain.topicAffinities.entries
                    .filter { it.value > 0.0 && it.key.isNotBlank() }
                    .sortedByDescending { it.value }
                    .take(limit)
                    .map { it.key },
            channelsKnown = brain.channelScores.size,
            interactions = brain.totalInteractions,
            hidden = brain.blockedTopics.size + brain.blockedChannels.size,
        )
}
