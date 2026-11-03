/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ai

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.mawja.android.BuildConfig
import app.mawja.android.account.EntitlementsRepository
import app.mawja.android.account.MawjaFeature
import app.mawja.android.data.transcript.TranscriptCue
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Cloud AI on the free path: Gemini through Firebase AI Logic on the Spark plan, called with the
 * app's own Firebase key (no server of ours, no billing). Only what the user asks for is sent: the
 * spoken words of one video, when they tap "AI summary". It is a Plus extra, and each device has a
 * small daily allowance so the shared free quota lasts for everyone.
 */
object CloudAi {
    const val MODEL = "gemini-2.5-flash"
    const val MAX_TRANSCRIPT_CHARS = 24_000
    const val PLUS_DAILY_CAP = 40
    const val MAX_QUESTION_CHARS = 300

    sealed interface Outcome {
        data class Text(
            val text: String,
        ) : Outcome

        /** The member's tier does not include cloud AI. */
        data object Locked : Outcome

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

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun entitlements(): EntitlementsRepository
    }

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
            val entitlements = EntryPointAccessors.fromApplication(appCtx, Dependencies::class.java).entitlements().entitlements.value
            if (!entitlements.has(MawjaFeature.CLOUD_AI)) return@withContext Outcome.Locked
            if (BuildConfig.FLAVOR == "foss") return@withContext Outcome.Unavailable("foss")
            if (BuildConfig.FIREBASE_API_KEY.isBlank() || BuildConfig.FIREBASE_PROJECT_ID.isBlank()) {
                return@withContext Outcome.Unavailable("not configured")
            }
            val transcript = transcriptForPrompt(cues)
            if (transcript.length < 200) return@withContext Outcome.Unavailable("short")
            val prefs = appCtx.getSharedPreferences("mawja_cloud_ai", Context.MODE_PRIVATE)
            val today = LocalDate.now().toString()
            val used = usedToday(prefs.getString("day", "") ?: "", prefs.getInt("count", 0), today)
            if (!allowed(used)) return@withContext Outcome.CapReached
            try {
                val url =
                    "https://firebasevertexai.googleapis.com/v1beta/projects/${BuildConfig.FIREBASE_PROJECT_ID}" +
                        "/models/$MODEL:generateContent"
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("x-goog-api-key", BuildConfig.FIREBASE_API_KEY)
                        .header("X-Android-Package", appCtx.packageName)
                        .apply { certSha1(appCtx)?.let { header("X-Android-Cert", it) } }
                        .post(requestBody(promptFor(transcript)).toRequestBody("application/json".toMediaType()))
                        .build()
                client.newCall(request).execute().use { r ->
                    val body = r.body.string()
                    if (!r.isSuccessful) return@withContext Outcome.Unavailable(parseError(body))
                    val text = parseReply(body) ?: return@withContext Outcome.Unavailable("empty")
                    prefs.edit().putString("day", today).putInt("count", used + 1).apply()
                    Outcome.Text(text)
                }
            } catch (e: java.io.IOException) {
                Outcome.Unavailable("offline")
            }
        }

    @Suppress("DEPRECATION")
    private fun certSha1(context: Context): String? =
        runCatching {
            val signer =
                if (Build.VERSION.SDK_INT >= 28) {
                    context.packageManager
                        .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                        .signingInfo
                        ?.apkContentsSigners
                        ?.firstOrNull()
                } else {
                    context.packageManager
                        .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                        .signatures
                        ?.firstOrNull()
                }
            signer?.let { s -> MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02X".format(it) } }
        }.getOrNull()
}
