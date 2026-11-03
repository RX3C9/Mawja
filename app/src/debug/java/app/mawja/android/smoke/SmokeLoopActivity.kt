// SPDX-License-Identifier: GPL-3.0-or-later
package app.mawja.android.smoke

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import app.mawja.android.MainActivity
import app.mawja.android.player.EnhancedMusicPlayerManager
import app.mawja.android.player.GlobalPlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Debug-only playback check for Firebase Test Lab's game-loop runner (free on the Spark plan, real
 * phones on residential-grade Google lab networks instead of bot-walled CI runners). Test Lab sends
 * TEST_LOOP; this opens the real app on a video, then on a song, waits for the app's own players to
 * report playing and advancing, logs "MAWJA_LOOP ..." lines and writes a JSON verdict to the results
 * file Test Lab handed over, then finishes so the run ends.
 */
class SmokeLoopActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action != ACTION) {
            finish()
            return
        }
        scope.launch {
            val result = JSONObject()
            result.put("pipeline", checkPipeline())
            result.put("video", checkVideo())
            result.put("music", checkMusic())
            Log.i(TAG, "MAWJA_LOOP done $result")
            runCatching {
                intent.data?.let { uri -> contentResolver.openOutputStream(uri, "w")?.use { it.write(result.toString(2).toByteArray()) } }
            }.onFailure { Log.w(TAG, "MAWJA_LOOP results file: ${it.message}") }
            delay(1_000)
            finish()
        }
    }

    private fun open(extras: Intent.() -> Unit) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_BENCHMARK_BYPASS_ONBOARDING, true)
                .apply(extras),
        )
    }

    private suspend fun checkVideo(): JSONObject {
        val out = JSONObject()
        open { putExtra("video_id", VIDEO_ID) }
        val playing =
            withTimeoutOrNull(WAIT_MS) {
                GlobalPlayerState.playerState.first { it.isPlaying && it.currentVideoId == VIDEO_ID }
            }
        delay(8_000)
        val after = GlobalPlayerState.playerState.value
        val ok = playing != null && after.isPlaying && after.error == null
        out.put("ok", ok).put("playing", playing != null).put("stillPlaying", after.isPlaying).put("error", after.error ?: "")
        Log.i(TAG, "MAWJA_LOOP video ${if (ok) "ok" else "FAILED"} $out")
        return out
    }

    private suspend fun checkMusic(): JSONObject {
        val out = JSONObject()
        open { putExtra("widget_route", "musicPlayer/$MUSIC_ID") }
        val playing = withTimeoutOrNull(WAIT_MS) { EnhancedMusicPlayerManager.playerState.first { it.isPlaying } }
        val p1 = EnhancedMusicPlayerManager.playerState.value.position
        delay(8_000)
        val after = EnhancedMusicPlayerManager.playerState.value
        val ok = playing != null && after.isPlaying && after.position > p1
        out.put("ok", ok).put("playing", playing != null).put("advancedMs", after.position - p1)
        Log.i(TAG, "MAWJA_LOOP music ${if (ok) "ok" else "FAILED"} $out")
        return out
    }

    /**
     * Device media pipeline (decoders, audio output, surface) on a non-YouTube clip, so a lab whose
     * network YouTube walls still proves the phone can decode and render what the app hands it.
     */
    private suspend fun checkPipeline(): JSONObject {
        val out = JSONObject()
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(this).build()
        try {
            val surface = android.view.SurfaceView(this)
            setContentView(surface)
            player.setVideoSurfaceView(surface)
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri(PIPELINE_CLIP))
            player.volume = 0f
            player.prepare()
            player.play()
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 20_000 && player.currentPosition < 4_000 && player.playerError == null) delay(250)
            val pos = player.currentPosition
            val size = player.videoSize
            out.put("position_ms", pos)
            out.put("video", "${size.width}x${size.height}")
            player.playerError?.let { out.put("error", it.errorCodeName) }
            val ok = pos >= 4_000 && size.width > 0
            out.put("ok", ok)
            Log.i(TAG, "MAWJA_LOOP pipeline ${if (ok) "ok" else "FAILED"} $out")
        } finally {
            player.release()
        }
        return out
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private companion object {
        private const val PIPELINE_CLIP = "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"
        const val TAG = "MawjaSmokeLoop"
        const val ACTION = "com.google.intent.action.TEST_LOOP"
        const val VIDEO_ID = "jNQXAC9IVRw"
        const val MUSIC_ID = "kJQP7kiw5Fk"
        const val WAIT_MS = 90_000L
    }
}
