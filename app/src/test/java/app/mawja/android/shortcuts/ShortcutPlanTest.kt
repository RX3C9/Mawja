/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.shortcuts

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShortcutPlanTest {
    private val playlist = ShortcutPlan.LastPlaylist("PLabc_12-x", "  Road trip  ", isMusic = false)

    @Test
    fun lastPlaylistComesFirstThenSearchDownloadsDj() {
        val plan = ShortcutPlan.plan(playlist, hasMusic = true, maxCount = 4)
        assertThat(plan.map { it.id })
            .containsExactly(ShortcutPlan.ID_PLAYLIST, ShortcutPlan.ID_SEARCH, ShortcutPlan.ID_DOWNLOADS, ShortcutPlan.ID_DJ)
            .inOrder()
        assertThat(plan.map { it.rank }).containsExactly(0, 1, 2, 3).inOrder()
        assertThat(plan[0].label).isEqualTo("Road trip")
        assertThat(plan[0].link).isEqualTo("mawja://playlist/PLabc_12-x")
    }

    @Test
    fun withoutPlaylistOrMusicAndCutToLauncherLimit() {
        assertThat(ShortcutPlan.plan(null, hasMusic = false, maxCount = 4).map { it.id })
            .containsExactly(ShortcutPlan.ID_SEARCH, ShortcutPlan.ID_DOWNLOADS)
            .inOrder()
        assertThat(ShortcutPlan.plan(playlist, hasMusic = true, maxCount = 2).map { it.id })
            .containsExactly(ShortcutPlan.ID_PLAYLIST, ShortcutPlan.ID_SEARCH)
            .inOrder()
        assertThat(ShortcutPlan.plan(playlist, hasMusic = true, maxCount = -1)).isEmpty()
        // A damaged or untitled playlist is left out rather than shown blank.
        assertThat(ShortcutPlan.plan(playlist.copy(title = " "), true, 4).map { it.id }).doesNotContain(ShortcutPlan.ID_PLAYLIST)
        assertThat(ShortcutPlan.plan(playlist.copy(id = "bad/id"), true, 4).map { it.id }).doesNotContain(ShortcutPlan.ID_PLAYLIST)
    }

    @Test
    fun signatureChangesOnlyWithContentOrLanguage() {
        val a = ShortcutPlan.plan(playlist, true, 4)
        assertThat(ShortcutPlan.signature(a, "ar")).isEqualTo(ShortcutPlan.signature(ShortcutPlan.plan(playlist, true, 4), "ar"))
        assertThat(ShortcutPlan.signature(a, "ar")).isNotEqualTo(ShortcutPlan.signature(a, "en"))
        val renamed = ShortcutPlan.plan(playlist.copy(title = "Gym"), true, 4)
        assertThat(ShortcutPlan.signature(renamed, "ar")).isNotEqualTo(ShortcutPlan.signature(a, "ar"))
    }

    @Test
    fun linksAreParsedBack() {
        assertThat(ShortcutPlan.parse("mawja://search")).isEqualTo(ShortcutPlan.Target.Page("search"))
        assertThat(ShortcutPlan.parse("MAWJA://Downloads")).isEqualTo(ShortcutPlan.Target.Page("downloads"))
        assertThat(ShortcutPlan.parse("mawja://library/")).isEqualTo(ShortcutPlan.Target.Page("library"))
        assertThat(ShortcutPlan.parse("mawja://dj")).isEqualTo(ShortcutPlan.Target.SmartDj)
        assertThat(ShortcutPlan.parse("mawja://playlist/PLabc_12-x")).isEqualTo(ShortcutPlan.Target.Playlist("PLabc_12-x", false))
        assertThat(ShortcutPlan.parse(ShortcutPlan.playlistLink("RDCLAK5", isMusic = true)))
            .isEqualTo(ShortcutPlan.Target.Playlist("RDCLAK5", true))
    }

    @Test
    fun foreignOrUnknownLinksAreRefused() {
        assertThat(ShortcutPlan.parse(null)).isNull()
        assertThat(ShortcutPlan.parse("")).isNull()
        assertThat(ShortcutPlan.parse("https://search")).isNull()
        assertThat(ShortcutPlan.parse("mawja://player/abc")).isNull()
        assertThat(ShortcutPlan.parse("mawja://search/extra")).isNull()
        assertThat(ShortcutPlan.parse("mawja://playlist")).isNull()
        assertThat(ShortcutPlan.parse("mawja://playlist/a/b")).isNull()
        assertThat(ShortcutPlan.parse("mawja://settings?target=x")).isEqualTo(ShortcutPlan.Target.Page("settings"))
        assertThat(ShortcutPlan.parse("not a uri at all %%")).isNull()
    }
}
