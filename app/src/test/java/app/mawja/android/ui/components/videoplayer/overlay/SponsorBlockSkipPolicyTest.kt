package app.mawja.android.ui.components.videoplayer.overlay

import com.google.common.truth.Truth.assertThat
import app.mawja.android.data.local.SponsorBlockAction
import app.mawja.android.data.model.SponsorBlockSegment
import org.junit.Test

class SponsorBlockSkipPolicyTest {
    private val outro =
        SponsorBlockSegment(
            category = "outro",
            segment = listOf(110f, 125f),
            uuid = "outro-id",
            actionType = "skip",
        )

    @Test
    fun `manual outro is visible while playback is active`() {
        val active =
            findActiveManualSponsorSegment(
                sponsorSegments = listOf(outro),
                currentPositionMs = 120_000L,
                skippedUuids = emptySet(),
                categoryActions = mapOf("outro" to SponsorBlockAction.SHOW_TOAST),
                playbackEnded = false,
            )

        assertThat(active).isEqualTo(outro)
    }

    @Test
    fun `manual outro is hidden after playback ends`() {
        val active =
            findActiveManualSponsorSegment(
                sponsorSegments = listOf(outro),
                currentPositionMs = 120_000L,
                skippedUuids = emptySet(),
                categoryActions = mapOf("outro" to SponsorBlockAction.SHOW_TOAST),
                playbackEnded = true,
            )

        assertThat(active).isNull()
    }
}
