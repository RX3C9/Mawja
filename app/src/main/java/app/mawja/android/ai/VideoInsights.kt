/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ai

import app.mawja.android.data.transcript.TranscriptCue
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * P10 on-device video intelligence, free and offline, from the transcript alone:
 * - [matches]: speech search that forgives Arabic spelling (hamza/alef forms, ta marbuta, alef maqsura,
 *   tashkeel, tatweel) and case;
 * - [autoChapters]: topic chapters for videos that have none (lexical-cohesion valleys, TextTiling);
 * - [keyMoments]: an extractive summary, the most informative sentences in time order (TF-IDF centrality).
 * No model download and no server: it runs in milliseconds on any phone.
 */
object VideoInsights {
    data class Chapter(
        val startMs: Long,
        val title: String,
    )

    data class Moment(
        val startMs: Long,
        val text: String,
    )

    private val TASHKEEL = Regex("[\\u064B-\\u065F\\u0670\\u0640]")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** Folds a string so that the same word written differently compares equal. */
    fun normalise(text: String): String =
        TASHKEEL
            .replace(text.lowercase(), "")
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ٱ', 'ا')
            .replace('ة', 'ه')
            .replace('ى', 'ي')
            .replace('ؤ', 'و')
            .replace('ئ', 'ي')

    fun matches(
        text: String,
        query: String,
    ): Boolean {
        val q = normalise(query.trim())
        if (q.isEmpty()) return true
        val t = normalise(text)
        return q.split(' ').filter { it.isNotBlank() }.all { t.contains(it) }
    }

    private val STOP =
        (
            "في من على الى إلى عن ان أن إن هذا هذه ذلك التي الذي اللي و ف ثم او أو لا ما لم لن مع كان كانت يكون هو هي هم انا أنا انت احنا " +
                "إحنا كل بس يعني كده كدا ده دي دا عشان علشان لما قد لقد كما بعد قبل عند حتى اي أي ايه إيه هنا هناك جدا جداً طيب " +
                "the a an and or of to in on for is are was were be been it this that with as at by from you we they i he she not " +
                "so but do does did have has had just like yeah okay ok um uh what there here my your our their about can will would"
        ).split(' ').map { normalise(it) }.toSet()

    private fun words(text: String): List<String> =
        NON_WORD
            .split(normalise(text))
            .filter { it.length > 2 && it !in STOP && it.any(Char::isLetter) }
            .map { stem(it) }

    /** Light stemming: strips the commonest Arabic article/prefixes and English plural. */
    private fun stem(w: String): String {
        var s = w
        if (s.length > 4 && s.startsWith("ال")) s = s.substring(2)
        if (s.length > 4 && (s.startsWith("وال") || s.startsWith("بال") || s.startsWith("فال") || s.startsWith("كال"))) s = s.substring(3)
        if (s.length > 4 && s.endsWith("ات")) s = s.dropLast(2)
        if (s.length > 4 && s.endsWith("ين")) s = s.dropLast(2)
        if (s.length > 4 && s.endsWith("s") && !s.endsWith("ss")) s = s.dropLast(1)
        return s
    }

    private fun idf(docs: List<List<String>>): Map<String, Double> {
        val df = HashMap<String, Int>()
        docs.forEach { d -> d.toSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
        return df.mapValues { (_, n) -> ln((docs.size + 1.0) / (n + 0.5)) }
    }

    private fun vector(
        terms: List<String>,
        idf: Map<String, Double>,
    ): Map<String, Double> {
        val tf = HashMap<String, Double>()
        terms.forEach { tf[it] = (tf[it] ?: 0.0) + 1.0 }
        return tf.mapValues { (t, f) -> f * (idf[t] ?: 0.0) }
    }

    private fun cosine(
        a: Map<String, Double>,
        b: Map<String, Double>,
    ): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var dot = 0.0
        a.forEach { (k, v) -> b[k]?.let { dot += v * it } }
        val na = sqrt(a.values.sumOf { it * it })
        val nb = sqrt(b.values.sumOf { it * it })
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (na * nb)
    }

    /**
     * Chapters for a transcript: blocks of [blockMs] are compared with their neighbours and cut where
     * the vocabulary changes most, at least [minChapterMs] apart, at most [maxChapters]. Each title is
     * the chapter's two most distinctive words. Empty for transcripts too short to split.
     */
    fun autoChapters(
        cues: List<TranscriptCue>,
        blockMs: Long = 45_000,
        minChapterMs: Long = 120_000,
        maxChapters: Int = 8,
    ): List<Chapter> {
        val sorted = cues.filter { it.text.isNotBlank() }.sortedBy { it.startMs }
        if (sorted.isEmpty()) return emptyList()
        val total = sorted.last().endMs.coerceAtLeast(sorted.last().startMs)
        if (total < minChapterMs * 2) return emptyList()
        val blocks = sorted.groupBy { it.startMs / blockMs }.toSortedMap().values.toList()
        if (blocks.size < 4) return emptyList()
        val blockTerms = blocks.map { b -> b.flatMap { words(it.text) } }
        val idf = idf(blockTerms)
        val vecs = blockTerms.map { vector(it, idf) }
        // Gap score between block i-1 and i: lower similarity = likelier topic change. Smooth with neighbours.
        val sim = (1 until vecs.size).map { i -> cosine(vecs[i - 1] + vecs.getOrElse(i - 2) { emptyMap() }, vecs[i] + vecs.getOrElse(i + 1) { emptyMap() }) }
        val depth =
            sim.indices.map { i ->
                val left = (0..i).maxOf { sim[it] }
                val right = (i until sim.size).maxOf { sim[it] }
                (left - sim[i]) + (right - sim[i])
            }
        val cutsWanted = (maxChapters - 1).coerceAtMost((total / minChapterMs).toInt() - 1).coerceAtLeast(0)
        // TextTiling: only valleys clearly deeper than usual are topic changes.
        val mean = depth.average()
        val sd = sqrt(depth.sumOf { (it - mean) * (it - mean) } / depth.size)
        val threshold = mean + sd / 2
        val cuts = ArrayList<Long>()
        depth
            .withIndex()
            .filter { it.value > 0.0 && it.value > threshold }
            .sortedByDescending { it.value }
            .forEach { (i, _) ->
                val at = blocks[i + 1].first().startMs
                if (cuts.size < cutsWanted && at >= minChapterMs && total - at >= minChapterMs / 2 && cuts.all { kotlin.math.abs(it - at) >= minChapterMs }) cuts += at
            }
        val starts = (listOf(0L) + cuts.sorted())
        if (starts.size < 2) return emptyList()
        val chapterTerms =
            starts.mapIndexed { i, s ->
                val end = starts.getOrElse(i + 1) { Long.MAX_VALUE }
                sorted.filter { it.startMs in s until end }.flatMap { words(it.text) }
            }
        val cIdf = idf(chapterTerms)
        val original = originalForms(sorted)
        return starts.mapIndexed { i, s ->
            val top =
                vector(chapterTerms[i], cIdf)
                    .entries
                    .sortedByDescending { it.value }
                    .take(2)
                    .map { original[it.key] ?: it.key }
            Chapter(s, top.joinToString(" · ").ifBlank { "${i + 1}" })
        }
    }

    /** The way each stemmed word was most often written, so titles read naturally. */
    private fun originalForms(cues: List<TranscriptCue>): Map<String, String> {
        val counts = HashMap<String, HashMap<String, Int>>()
        cues.forEach { c ->
            TASHKEEL.replace(c.text, "").split(NON_WORD).filter { it.length > 2 }.forEach { raw ->
                val w = normalise(raw)
                if (w in STOP) return@forEach
                val key = stem(w)
                val m = counts.getOrPut(key) { HashMap() }
                m[raw] = (m[raw] ?: 0) + 1
            }
        }
        return counts.mapValues { (_, m) -> m.maxByOrNull { it.value }!!.key }
    }

    /**
     * The [count] sentences that carry the most of what the video is about (each scored by its
     * similarity to the whole transcript, length-normalised, near-duplicates skipped), in time order.
     */
    fun keyMoments(
        cues: List<TranscriptCue>,
        count: Int = 5,
    ): List<Moment> {
        val sentences = cues.filter { words(it.text).size >= 3 }
        if (sentences.size <= count) return sentences.map { Moment(it.startMs, it.text.trim()) }
        val terms = sentences.map { words(it.text) }
        val idf = idf(terms)
        val vecs = terms.map { vector(it, idf) }
        val centroid = HashMap<String, Double>()
        vecs.forEach { v -> v.forEach { (k, x) -> centroid[k] = (centroid[k] ?: 0.0) + x } }
        val ranked = sentences.indices.sortedByDescending { cosine(vecs[it], centroid) }
        val chosen = ArrayList<Int>()
        for (i in ranked) {
            if (chosen.size == count) break
            if (chosen.none { cosine(vecs[it], vecs[i]) > 0.6 }) chosen += i
        }
        return chosen.sorted().map { Moment(sentences[it].startMs, sentences[it].text.trim()) }
    }
}
