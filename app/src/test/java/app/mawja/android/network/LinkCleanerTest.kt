/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LinkCleanerTest {
    @Test
    fun youtubeShareIdIsDroppedButVideoTimeAndListStay() {
        assertThat(LinkCleaner.clean("https://youtu.be/abcdefghijk?si=XyZ123&t=42"))
            .isEqualTo("https://youtu.be/abcdefghijk?t=42")
        assertThat(LinkCleaner.clean("https://www.youtube.com/watch?v=abcdefghijk&list=PL1&index=3&pp=ygU&feature=shared"))
            .isEqualTo("https://www.youtube.com/watch?v=abcdefghijk&list=PL1&index=3")
        assertThat(LinkCleaner.clean("https://music.youtube.com/watch?v=abcdefghijk&si=a"))
            .isEqualTo("https://music.youtube.com/watch?v=abcdefghijk")
    }

    @Test
    fun genericTrackersAreDroppedEverywhereKeepingOrderAndFragment() {
        assertThat(LinkCleaner.clean("https://example.com/a?utm_source=x&id=7&UTM_Medium=y&fbclid=z&b=2#part"))
            .isEqualTo("https://example.com/a?id=7&b=2#part")
        assertThat(LinkCleaner.clean("https://example.com/?gclid=1&msclkid=2&igshid=3&mc_cid=4&mc_eid=5"))
            .isEqualTo("https://example.com/")
        assertThat(LinkCleaner.clean("https://example.com/p?utm_campaign=a#top")).isEqualTo("https://example.com/p#top")
    }

    @Test
    fun shareParamsSurviveOnOtherSites() {
        val url = "https://example.com/search?feature=maps&si=2&pp=1"
        assertThat(LinkCleaner.clean(url)).isEqualTo(url)
    }

    @Test
    fun linksWithoutTrackersOrNotHttpAreUntouched() {
        listOf(
            "https://www.youtube.com/watch?v=abcdefghijk&t=10s",
            "https://youtu.be/abcdefghijk",
            "mailto:me@example.com?utm_source=x",
            "flow://widget/video/abc?si=1",
            "not a link",
        ).forEach { assertThat(LinkCleaner.clean(it)).isEqualTo(it) }
    }

    @Test
    fun cleanTextRewritesOnlyTheLinksAndKeepsPunctuation() {
        val text = "Watch this: https://youtu.be/abcdefghijk?si=AbC. Also (https://example.com/x?utm_source=a&k=1), ok"
        assertThat(LinkCleaner.cleanText(text))
            .isEqualTo("Watch this: https://youtu.be/abcdefghijk. Also (https://example.com/x?k=1), ok")
        assertThat(LinkCleaner.cleanText("no links here")).isEqualTo("no links here")
    }

    @Test
    fun emptyParamsAndDuplicateSeparatorsAreTidied() {
        assertThat(LinkCleaner.clean("https://example.com/a?&utm_source=x&&q=1")).isEqualTo("https://example.com/a?q=1")
    }
}
