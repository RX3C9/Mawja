/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.resume

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InstantResumePolicyTest {
    private val hour = 60 * 60 * 1000L

    @Test
    fun pathAndQueryArgumentsAreFilledAndEncoded() {
        assertThat(InstantResumePolicy.concreteRoute("playlist/{playlistId}", mapOf("playlistId" to "PL a/b")))
            .isEqualTo("playlist/PL%20a%2Fb")
        assertThat(InstantResumePolicy.concreteRoute("channel?url={channelUrl}", mapOf("channelUrl" to "/@mawja")))
            .isEqualTo("channel?url=%2F%40mawja")
        assertThat(InstantResumePolicy.concreteRoute("library", emptyMap())).isEqualTo("library")
        assertThat(InstantResumePolicy.concreteRoute("youtube_browse/{browseId}?params={params}", mapOf("browseId" to "FE1")))
            .isEqualTo("youtube_browse/FE1")
        assertThat(InstantResumePolicy.concreteRoute("settings?target={target}", mapOf("target" to "playback")))
            .isEqualTo("settings?target=playback")
    }

    @Test
    fun arabicIsEncodedAsUtf8() {
        assertThat(InstantResumePolicy.encode("موجة")).isEqualTo("%D9%85%D9%88%D8%AC%D8%A9")
        assertThat(InstantResumePolicy.encode("a-b_c.d~e")).isEqualTo("a-b_c.d~e")
    }

    @Test
    fun missingPathArgumentOrTransientPageIsNotRestorable() {
        assertThat(InstantResumePolicy.concreteRoute("playlist/{playlistId}", emptyMap())).isNull()
        assertThat(InstantResumePolicy.concreteRoute(null, emptyMap())).isNull()
        for (pattern in listOf("onboarding", "player/{videoId}", "musicPlayer/{videoId}?list={list}", "shorts", "onRepeatShuffle", "musicRecognize", "update")) {
            assertThat(InstantResumePolicy.concreteRoute(pattern, mapOf("videoId" to "x"))).isNull()
        }
    }

    @Test
    fun routeIsRestoredOnlyWhenOnAndFresh() {
        val saved = SavedRoute("playlist/PL1", savedAt = 1_000L)
        assertThat(InstantResumePolicy.routeToRestore(saved, 1_000L + hour, enabled = true)).isEqualTo("playlist/PL1")
        assertThat(InstantResumePolicy.routeToRestore(saved, 1_000L + hour, enabled = false)).isNull()
        assertThat(InstantResumePolicy.routeToRestore(saved, 1_000L + 13 * hour, enabled = true)).isNull()
        assertThat(InstantResumePolicy.routeToRestore(saved, 500L, enabled = true)).isNull()
        assertThat(InstantResumePolicy.routeToRestore(null, 0L, enabled = true)).isNull()
        assertThat(InstantResumePolicy.routeToRestore(SavedRoute("player/x", 1_000L), 2_000L, enabled = true)).isNull()
    }

    @Test
    fun scrollComesBackOnlyOnTheReopenedPage() {
        val scroll = SavedScroll("playlist/PL1", index = 12, offset = 40, savedAt = 1_000L)
        assertThat(InstantResumePolicy.scrollToRestore(scroll, "playlist/PL1", 2_000L)).isEqualTo(scroll)
        assertThat(InstantResumePolicy.scrollToRestore(scroll, "playlist/PL2", 2_000L)).isNull()
        assertThat(InstantResumePolicy.scrollToRestore(scroll, null, 2_000L)).isNull()
        assertThat(InstantResumePolicy.scrollToRestore(scroll, "playlist/PL1", 1_000L + 13 * hour)).isNull()
        assertThat(InstantResumePolicy.scrollToRestore(scroll.copy(index = 0, offset = 0), "playlist/PL1", 2_000L)).isNull()
    }

    @Test
    fun codecRoundTripsAndRejectsDamage() {
        val route = SavedRoute("channel?url=%2F%40mawja", 123L)
        assertThat(InstantResumePolicy.decodeRoute(InstantResumePolicy.encodeRoute(route))).isEqualTo(route)
        val scroll = SavedScroll("library", 3, 7, 99L)
        assertThat(InstantResumePolicy.decodeScroll(InstantResumePolicy.encodeScroll(scroll))).isEqualTo(scroll)
        assertThat(InstantResumePolicy.decodeRoute("garbage")).isNull()
        assertThat(InstantResumePolicy.decodeRoute("2\n1\nlibrary")).isNull()
        assertThat(InstantResumePolicy.decodeRoute(null)).isNull()
        assertThat(InstantResumePolicy.decodeScroll("1\nx\n1\n1\nlibrary")).isNull()
        assertThat(InstantResumePolicy.decodeScroll("1\n1\n-4\n1\nlibrary")).isNull()
    }
}
