/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

/** Moods the home feed can lean towards. Stored by name; never rename. */
enum class FeedMood(val boost: Set<String>, val damp: Set<String>) {
    FOCUS(setOf("education", "science", "technology", "lecture", "tutorial", "study", "programming", "شرح", "درس", "محاضرة", "برمجة", "مذاكرة", "تعلم"), setOf("prank", "reaction", "gaming", "comedy", "مقلب", "مقالب", "تحدي")),
    LAUGH(setOf("comedy", "funny", "sketch", "standup", "animation", "memes", "ضحك", "كوميدي", "كوميديا", "مضحك", "نكت"), setOf("news", "lecture", "politics")),
    LEARN(setOf("education", "documentary", "history", "science", "language", "howto", "tutorial", "وثائقي", "تاريخ", "علوم", "شرح", "تعلم"), setOf("prank", "drama", "مقلب")),
    CALM(setOf("nature", "ambient", "relax", "quran", "asmr", "lofi", "travel", "قرآن", "طبيعة", "هادئ", "استرخاء", "تلاوة"), setOf("news", "horror", "politics", "prank")),
    OFF(emptySet(), emptySet()),
}

/**
 * Re-ranks a scored feed for a mood without hiding anything: matching topics gain up to [lift], damped
 * topics lose up to [lift]. A stable sort keeps the original order among equals, so the feed never
 * jumps for items the mood does not touch.
 */
object MoodRanker {
    fun <T> rerank(
        items: List<T>,
        mood: FeedMood,
        baseScore: (T) -> Double,
        topics: (T) -> Collection<String>,
        lift: Double = 0.35,
    ): List<T> {
        if (mood == FeedMood.OFF || items.size < 2) return items
        return items
            .mapIndexed { i, item ->
                val t = topics(item).map { it.lowercase() }
                val up = t.count { it in mood.boost }.coerceAtMost(2) / 2.0
                val down = t.count { it in mood.damp }.coerceAtMost(2) / 2.0
                Triple(i, item, baseScore(item) * (1.0 + lift * up - lift * down))
            }.sortedWith(compareByDescending<Triple<Int, T, Double>> { it.third }.thenBy { it.first })
            .map { it.second }
    }
}

/** Words of a video's title and description, lower-cased, for [MoodRanker]. */
fun moodTopics(text: String): List<String> = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }
