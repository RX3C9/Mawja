/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings and shared instances for the Mawja Lab features (feed mood, time guardian). */
object MawjaLab {
    private const val PREFS = "mawja_lab"
    private var prefs: SharedPreferences? = null

    private val _mood = MutableStateFlow(FeedMood.OFF)
    private val _guardian = MutableStateFlow(true)

    val mood: StateFlow<FeedMood> = _mood.asStateFlow()
    val guardian: StateFlow<Boolean> = _guardian.asStateFlow()

    private val _shakeRewind = MutableStateFlow(false)
    val shakeRewind: StateFlow<Boolean> = _shakeRewind.asStateFlow()

    fun setShakeRewind(on: Boolean) = save("shake_rewind", on) { _shakeRewind.value = on }

    @Volatile private var lastCues: Pair<String, List<app.mawja.android.data.transcript.TranscriptCue>>? = null

    /** The transcript layer reports every caption track it reads; a shake uses the one for this video. */
    fun rememberCues(
        trackUrl: String,
        cues: List<app.mawja.android.data.transcript.TranscriptCue>,
    ) {
        if (cues.isNotEmpty()) lastCues = trackUrl to cues
    }

    fun cuesFor(videoId: String?): List<app.mawja.android.data.transcript.TranscriptCue> {
        val (url, cues) = lastCues ?: return emptyList()
        return if (videoId != null && url.contains(videoId)) cues else emptyList()
    }

    /** One guardian for the whole process; the player feeds it. */
    val watchGuardian = WatchGuardian()

    private val _breakPrompts =
        kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )

    /** Emits when the guardian holds back an autoplay and the UI should offer a break. */
    val breakPrompts: kotlinx.coroutines.flow.SharedFlow<Unit> = _breakPrompts

    /** The viewer picked a video themselves. */
    fun onUserChose() {
        val now = System.currentTimeMillis()
        watchGuardian.onEvent(WatchGuardian.Event.UserChose(now))
        watchGuardian.onEvent(WatchGuardian.Event.Started(now, autoplayed = false))
    }

    /** Asked before an autoplay: false holds it back (and asks the UI for the break card). */
    fun allowAutoplay(): Boolean {
        if (!_guardian.value) return true
        val nudge = watchGuardian.onEvent(WatchGuardian.Event.Started(System.currentTimeMillis(), autoplayed = true))
        if (nudge) _breakPrompts.tryEmit(Unit)
        return !nudge
    }

    fun start(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _mood.value = runCatching { FeedMood.valueOf(p.getString("mood", null) ?: "OFF") }.getOrDefault(FeedMood.OFF)
        _guardian.value = p.getBoolean("guardian", true)
        _shakeRewind.value = p.getBoolean("shake_rewind", false)
    }

    fun setMood(value: FeedMood) {
        prefs?.edit()?.putString("mood", value.name)?.apply()
        _mood.value = value
    }

    fun setGuardian(on: Boolean) = save("guardian", on) { _guardian.value = on }

    private inline fun save(
        key: String,
        on: Boolean,
        set: () -> Unit,
    ) {
        prefs?.edit()?.putBoolean(key, on)?.apply()
        set()
    }
}
