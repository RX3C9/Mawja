package app.mawja.android.ai

import app.mawja.android.account.Entitlements
import app.mawja.android.account.MawjaFeature
import app.mawja.android.account.MawjaTier
import app.mawja.android.data.transcript.TranscriptCue
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

class CloudAiTest {
    @Test
    fun `cloud ai is a Plus extra`() {
        assertThat(Entitlements.forTier(MawjaTier.FREE).has(MawjaFeature.CLOUD_AI)).isFalse()
        assertThat(Entitlements.minimumTier(MawjaFeature.CLOUD_AI)).isEqualTo(MawjaTier.PLUS)
        assertThat(Entitlements.forTier(MawjaTier.PRO).has(MawjaFeature.CLOUD_AI)).isTrue()
    }

    @Test
    fun `short transcripts are sent whole with their times`() {
        val cues = listOf(TranscriptCue(0, "hello there"), TranscriptCue(65_000, "second line"), TranscriptCue(70_000, "  "))
        assertThat(CloudAi.transcriptForPrompt(cues)).isEqualTo("[00:00] hello there\n[01:05] second line")
    }

    @Test
    fun `long transcripts are sampled across the whole video and capped`() {
        val cues = (0 until 5000).map { TranscriptCue(it * 2000L, "line number $it with some words in it") }
        val out = CloudAi.transcriptForPrompt(cues, maxChars = 10_000)
        assertThat(out.length).isAtMost(10_000)
        assertThat(out).contains("[00:00]")
        assertThat(out.lines().last()).containsMatch("^\\[\\d:\\d\\d:\\d\\d\\]")
    }

    @Test
    fun `request body carries the prompt and reply parsing reads the text`() {
        val body = JSONObject(CloudAi.requestBody("hi \"there\""))
        assertThat(body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")).isEqualTo("hi \"there\"")
        val reply = """{"candidates":[{"content":{"parts":[{"text":"Hello "},{"text":"world"}]}}]}"""
        assertThat(CloudAi.parseReply(reply)).isEqualTo("Hello world")
        assertThat(CloudAi.parseReply("""{"candidates":[]}""")).isNull()
        assertThat(CloudAi.parseReply("nope")).isNull()
    }

    @Test
    fun `error bodies become a short reason`() {
        val e = """{"error":{"code":403,"message":"API disabled","status":"PERMISSION_DENIED"}}"""
        assertThat(CloudAi.parseError(e)).isEqualTo("PERMISSION_DENIED: API disabled")
        assertThat(CloudAi.parseError("<html>")).isEqualTo("error")
    }

    @Test
    fun `the daily allowance resets on a new day`() {
        assertThat(CloudAi.usedToday("2026-11-03", 7, "2026-11-03")).isEqualTo(7)
        assertThat(CloudAi.usedToday("2026-11-02", 7, "2026-11-03")).isEqualTo(0)
        assertThat(CloudAi.allowed(CloudAi.PLUS_DAILY_CAP - 1)).isTrue()
        assertThat(CloudAi.allowed(CloudAi.PLUS_DAILY_CAP)).isFalse()
    }

    @Test
    fun `the summary prompt names the language and the transcript`() {
        val p = CloudAi.summaryPrompt("[00:00] x", "ar-EG")
        assertThat(p).contains("ar-EG")
        assertThat(p).contains("[00:00] x")
    }
}
