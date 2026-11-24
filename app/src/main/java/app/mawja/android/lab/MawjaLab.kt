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

    private val _sleepSentence = MutableStateFlow(true)
    private val _nightSounds = MutableStateFlow(true)
    private val _bedtime = MutableStateFlow(false)
    private val _bedtimeStartHour = MutableStateFlow(0)
    private val _nightLeveling = MutableStateFlow(false)
    private val _oneHand = MutableStateFlow(false)

    /** Bed mode evens out the video's loud and quiet moments through the night window. */
    val nightLeveling: StateFlow<Boolean> = _nightLeveling.asStateFlow()

    /** One-hand mode: on a tall fullscreen video the main buttons sit in the bottom third. */
    val oneHand: StateFlow<Boolean> = _oneHand.asStateFlow()

    fun setNightLeveling(on: Boolean) = save("night_leveling", on) { _nightLeveling.value = on }

    fun setOneHand(on: Boolean) = save("one_hand", on) { _oneHand.value = on }

    /** A sleep timer waits for the end of the sentence being spoken. */
    val sleepSentence: StateFlow<Boolean> = _sleepSentence.asStateFlow()

    /** UI sounds get quieter at night. */
    val nightSounds: StateFlow<Boolean> = _nightSounds.asStateFlow()

    /** Warm amber tint at night. */
    val bedtime: StateFlow<Boolean> = _bedtime.asStateFlow()

    /** Hour of day (22, 23 or 0) the bedtime tint begins to fade in. */
    val bedtimeStartHour: StateFlow<Int> = _bedtimeStartHour.asStateFlow()

    fun setSleepSentence(on: Boolean) = save("sleep_sentence", on) { _sleepSentence.value = on }

    fun setNightSounds(on: Boolean) = save("night_sounds", on) { _nightSounds.value = on }

    fun setBedtime(on: Boolean) = save("bedtime", on) { _bedtime.value = on }

    fun setBedtimeStartHour(hour: Int) {
        prefs?.edit()?.putInt("bedtime_start", hour)?.apply()
        _bedtimeStartHour.value = hour
    }

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
        _sleepSentence.value = p.getBoolean("sleep_sentence", true)
        _nightSounds.value = p.getBoolean("night_sounds", true)
        _bedtime.value = p.getBoolean("bedtime", false)
        _nightLeveling.value = p.getBoolean("night_leveling", false)
        _oneHand.value = p.getBoolean("one_hand", false)
        _bedtimeStartHour.value = p.getInt("bedtime_start", 0).takeIf { it == 22 || it == 23 || it == 0 } ?: 0
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
