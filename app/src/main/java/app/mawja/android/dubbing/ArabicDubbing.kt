/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.dubbing

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import app.mawja.android.data.transcript.TranscriptEntryPoint
import app.mawja.android.player.EnhancedPlayerManager
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Arabic AI dubbing on the phone itself, free and offline once the voice is installed: the Arabic
 * caption track (YouTube's own or its machine translation) is spoken by Android's Arabic voice in
 * time with the video while the original sound is lowered or muted.
 */
object ArabicDubbing {
    private const val TAG = "ArabicDubbing"
    private const val PREFS = "mawja_dubbing"
    private const val KEY_MODE = "mode"
    private const val TICK_MS = 120L

    enum class Status { OFF, PREPARING, SPEAKING, NO_CAPTIONS, NO_VOICE }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _mode = MutableStateFlow(DubMode.OFF)
    val mode: StateFlow<DubMode> = _mode.asStateFlow()
    private val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status.asStateFlow()

    private lateinit var appContext: Context
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var started = false
    private var loop: Job? = null

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        _mode.value = runCatching { DubMode.valueOf(prefs().getString(KEY_MODE, DubMode.OFF.name)!!) }.getOrDefault(DubMode.OFF)
        scope.launch {
            val manager = EnhancedPlayerManager.getInstance()
            combine(_mode, manager.playerState.map { it.currentVideoId to DubPlan.trackUrl(it.availableSubtitles) }.distinctUntilChanged()) { m, v -> m to v }
                .collectLatest { (m, video) ->
                    loop?.cancel()
                    restoreVolume()
                    if (m == DubMode.OFF) {
                        tts?.stop()
                        _status.value = Status.OFF
                        return@collectLatest
                    }
                    val (videoId, url) = video
                    if (videoId == null) return@collectLatest
                    if (url == null) {
                        _status.value = Status.NO_CAPTIONS
                        return@collectLatest
                    }
                    _status.value = Status.PREPARING
                    if (!ensureTts()) {
                        _status.value = Status.NO_VOICE
                        return@collectLatest
                    }
                    val repo = EntryPointAccessors.fromApplication(appContext, TranscriptEntryPoint::class.java).transcriptRepository()
                    val lines = DubPlan.lines(runCatching { repo.captionCues(url) }.getOrDefault(emptyList()))
                    if (lines.isEmpty()) {
                        _status.value = Status.NO_CAPTIONS
                        return@collectLatest
                    }
                    _status.value = Status.SPEAKING
                    run(lines, m)
                }
        }
    }

    fun setMode(mode: DubMode) {
        if (!started) return
        prefs().edit().putString(KEY_MODE, mode.name).apply()
        _mode.value = mode
    }

    private suspend fun run(
        lines: List<DubLine>,
        mode: DubMode,
    ) {
        val manager = EnhancedPlayerManager.getInstance()
        var spoken = -1
        var lastPos = -1L
        while (scope.isActive) {
            val playing = manager.isPlaying()
            val pos = manager.getCurrentPosition()
            if (!playing) {
                if (tts?.isSpeaking == true) tts?.stop()
                restoreVolume()
            } else {
                setOriginalVolume(mode.originalVolume)
                // A seek backwards, or a jump, lets the line under the playhead be spoken again.
                if (lastPos >= 0 && (pos < lastPos - 1500 || pos > lastPos + 4000)) {
                    tts?.stop()
                    spoken = -1
                }
                val at = DubPlan.lineAt(lines, pos)
                if (at != null && at > spoken) {
                    val line = lines[at]
                    // Joined late (seek, start mid-line): speak only if most of the slot remains.
                    if (pos - line.startMs < (line.endMs - line.startMs) / 2) speak(line, manager.getPlayer()?.playbackParameters?.speed ?: 1f)
                    spoken = at
                }
            }
            lastPos = pos
            delay(TICK_MS)
        }
    }

    private fun speak(
        line: DubLine,
        playbackSpeed: Float,
    ) {
        val engine = tts ?: return
        engine.setSpeechRate((line.rate * playbackSpeed).coerceIn(0.5f, 2.5f))
        engine.speak(line.text, TextToSpeech.QUEUE_FLUSH, null, "dub-${line.startMs}")
    }

    private suspend fun ensureTts(): Boolean {
        if (ttsReady) return true
        if (tts == null) {
            val ready = MutableStateFlow<Int?>(null)
            tts = TextToSpeech(appContext) { ready.value = it }
            var waited = 0
            while (ready.value == null && waited < 5000) {
                delay(100)
                waited += 100
            }
            if (ready.value != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS init failed: ${ready.value}")
                tts = null
                return false
            }
            tts?.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) = Unit

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = Unit
                },
            )
        }
        val result = tts?.setLanguage(Locale.forLanguageTag("ar")) ?: TextToSpeech.LANG_NOT_SUPPORTED
        ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        return ttsReady
    }

    private var ducked = false

    private fun setOriginalVolume(volume: Float) {
        val player = EnhancedPlayerManager.getInstance().getPlayer() ?: return
        if (player.volume != volume) player.volume = volume
        ducked = true
    }

    private fun restoreVolume() {
        if (!ducked) return
        EnhancedPlayerManager.getInstance().getPlayer()?.volume = 1f
        ducked = false
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
