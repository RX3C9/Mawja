/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ai

import android.content.Context
import app.mawja.android.data.transcript.TranscriptCue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Cloud AI on the free path: Gemini called directly with the member's OWN free Google AI Studio key
 * (no server of ours, no billing, nothing shared). Google no longer lets an app-embedded key be tied
 * to an Android app for Gemini, so the key is pasted once, kept on this phone only and sent to
 * Google alone. Only what the user asks for is sent: the spoken words of one video, when they tap
 * "AI summary". A small daily cap per device keeps the personal free quota from running out.
 */
object CloudAi {
    /** Tried in order; the first one this key can use wins (free-tier models of the Gemini API). */
    val MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash")
    const val KEY_PREF = "user_key"
    private const val MIN_KEY_LENGTH = 30
    const val MAX_TRANSCRIPT_CHARS = 24_000
    const val PLUS_DAILY_CAP = 100
    const val MAX_QUESTION_CHARS = 300

    sealed interface Outcome {
        data class Text(
            val text: String,
        ) : Outcome

        /** The member's tier does not include cloud AI. */
        data object Locked : Outcome

        /** No personal Gemini key has been pasted yet. */
        data object NeedKey : Outcome

        /** Today's allowance for this device is used up. */
        data object CapReached : Outcome

        /** No Firebase config in this build, no words to summarise, or the service said no. */
        data class Unavailable(
            val reason: String,
        ) : Outcome
    }

    /** "[mm:ss] words" lines; a transcript over [maxChars] keeps an even sample across the whole video. */
    fun transcriptForPrompt(
        cues: List<TranscriptCue>,
        maxChars: Int = MAX_TRANSCRIPT_CHARS,
    ): String {
        val lines = cues.filter { it.text.isNotBlank() }.map { "[${StudyNotes.stamp(it.startMs)}] ${it.text.trim()}" }
        val total = lines.sumOf { it.length + 1 }
        if (total <= maxChars) return lines.joinToString("\n")
        val keepEvery = (total.toDouble() / maxChars).let { kotlin.math.ceil(it).toInt() }
        return lines.filterIndexed { i, _ -> i % keepEvery == 0 }.joinToString("\n").take(maxChars)
    }

    fun summaryPrompt(
        transcript: String,
        languageTag: String,
    ): String =
        "You summarise a video from its transcript. Write in the language with tag \"$languageTag\" " +
            "(Egyptian Arabic style is fine for ar). Give: one sentence saying what the video is about, " +
            "then 4 to 7 short bullet points with the key ideas, each starting with its [mm:ss] time from the transcript. " +
            "Use only what the transcript says; do not invent. Plain text, no markdown headings.\n\nTranscript:\n$transcript"

    fun requestBody(prompt: String): String =
        JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt))),
                ),
            ).put("generationConfig", JSONObject().put("temperature", 0.3).put("maxOutputTokens", 700))
            .toString()

    /** The reply text, or null when the response carries none (blocked, empty or malformed). */
    fun parseReply(json: String): String? =
        runCatching {
            val parts =
                JSONObject(json)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
            (0 until parts.length())
                .joinToString("") { parts.getJSONObject(it).optString("text") }
                .trim()
                .ifEmpty { null }
        }.getOrNull()

    fun parseError(json: String): String =
        runCatching {
            val e = JSONObject(json).getJSONObject("error")
            "${e.optString("status")}: ${e.optString("message")}".take(160)
        }.getOrDefault("error")

    /** One allowance per calendar day: the count carries over only while [today] matches [day]. */
    fun usedToday(
        day: String,
        count: Int,
        today: String,
    ): Int = if (day == today) count else 0

    fun allowed(
        used: Int,
        cap: Int = PLUS_DAILY_CAP,
    ): Boolean = used < cap

    /** A key looks usable when it is long enough, has no spaces and starts the way Google keys do. */
    fun looksLikeKey(raw: String): Boolean {
        val k = raw.trim()
        return k.length >= MIN_KEY_LENGTH && k.none { it.isWhitespace() } && k.startsWith("AIza")
    }

    fun userKey(context: Context): String =
        context.applicationContext
            .getSharedPreferences("mawja_cloud_ai", Context.MODE_PRIVATE)
            .getString(KEY_PREF, "")
            .orEmpty()

    fun saveUserKey(
        context: Context,
        raw: String,
    ): Boolean {
        val prefs = context.applicationContext.getSharedPreferences("mawja_cloud_ai", Context.MODE_PRIVATE)
        if (raw.isBlank()) {
            prefs.edit().remove(KEY_PREF).apply()
            return true
        }
        if (!looksLikeKey(raw)) return false
        prefs.edit().putString(KEY_PREF, raw.trim()).apply()
        return true
    }

    /** Models this key may call, newest flash first: parsed from GET v1beta/models. */
    fun pickModels(json: String): List<String> =
        try {
            val arr = JSONObject(json).optJSONArray("models") ?: JSONArray()
            (0 until arr.length())
                .mapNotNull { arr.optJSONObject(it) }
                .filter { m ->
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                }.map { it.optString("name").removePrefix("models/") }
                .filter { it.contains("flash") && !it.contains("image") && !it.contains("tts") && !it.contains("live") }
                .sortedWith(compareByDescending<String> { name -> Regex("""(\d+)\.(\d+)""").find(name)?.let { it.groupValues[1].toInt() * 100 + it.groupValues[2].toInt() } ?: 0 }
                    .thenBy { it.contains("preview") || it.contains("exp") })
        } catch (e: Exception) {
            emptyList()
        }

    private fun availableModels(key: String): List<String> =
        try {
            client
                .newCall(Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200").header("x-goog-api-key", key).build())
                .execute()
                .use { r -> if (r.isSuccessful) pickModels(r.body.string()) else emptyList() }
        } catch (e: java.io.IOException) {
            emptyList()
        }

    fun endpoint(model: String): String = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

    private val client by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun summarize(
        context: Context,
        cues: List<TranscriptCue>,
        languageTag: String,
    ): Outcome = generate(context, cues) { summaryPrompt(it, languageTag) }

    /** "Ask Mawja AI": a free-form question answered only from what this video says. */
    suspend fun ask(
        context: Context,
        cues: List<TranscriptCue>,
        question: String,
        languageTag: String,
    ): Outcome {
        val q = question.trim().take(MAX_QUESTION_CHARS)
        if (q.isEmpty()) return Outcome.Unavailable("empty question")
        return generate(context, cues) { askPrompt(it, q, languageTag) }
    }

    fun askPrompt(
        transcript: String,
        question: String,
        languageTag: String,
    ): String =
        "You answer a viewer's question about a video using only its transcript. Write in the language with tag " +
            "\"$languageTag\" (Egyptian Arabic style is fine for ar). Be short and direct, and mention the [mm:ss] time where the " +
            "answer is said. If the transcript does not contain the answer, say so plainly. Plain text, no markdown headings.\n\n" +
            "Question: $question\n\nTranscript:\n$transcript"

    private suspend fun generate(
        context: Context,
        cues: List<TranscriptCue>,
        promptFor: (String) -> String,
    ): Outcome =
        withContext(Dispatchers.IO) {
            val appCtx = context.applicationContext
            val key = userKey(appCtx)
            if (key.isBlank()) return@withContext Outcome.NeedKey
            val transcript = transcriptForPrompt(cues)
            if (transcript.length < 200) return@withContext Outcome.Unavailable("short")
            val prefs = appCtx.getSharedPreferences("mawja_cloud_ai", Context.MODE_PRIVATE)
            val today = LocalDate.now().toString()
            val used = usedToday(prefs.getString("day", "") ?: "", prefs.getInt("count", 0), today)
            if (!allowed(used)) return@withContext Outcome.CapReached
            try {
                var lastError = "error"
                val cached = prefs.getString("model", "").orEmpty()
                val order = (listOf(cached).filter { it.isNotBlank() } + MODELS).distinct().toMutableList()
                var listed = false
                var i = 0
                while (i < order.size) {
                    val model = order[i++]
                    val request =
                        Request
                            .Builder()
                            .url(endpoint(model))
                            .header("x-goog-api-key", key)
                            .post(requestBody(promptFor(transcript)).toRequestBody("application/json".toMediaType()))
                            .build()
                    client.newCall(request).execute().use { r ->
                        val body = r.body.string()
                        if (r.isSuccessful) {
                            val text = parseReply(body) ?: return@withContext Outcome.Unavailable("empty")
                            prefs.edit().putString("day", today).putInt("count", used + 1).putString("model", model).apply()
                            return@withContext Outcome.Text(text)
                        }
                        lastError = parseError(body)
                        // 400/401/403 = the key itself is refused; 404 = model gone, 429 = busy: try the next model.
                        if (r.code == 400 || r.code == 401 || r.code == 403) return@withContext Outcome.Unavailable(lastError)
                    }
                    // Every known name is gone: ask Google which models this key can use, once.
                    if (i == order.size && !listed) {
                        listed = true
                        order += availableModels(key).filterNot { it in order }
                    }
                }
                Outcome.Unavailable(lastError)
            } catch (e: java.io.IOException) {
                Outcome.Unavailable("offline")
            }
        }
}
