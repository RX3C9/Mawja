/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.reorder

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ListOrderTest {
    private val defaults = listOf("A", "B", "C", "D")

    @Test
    fun moveDownAndUp() {
        assertThat(ListOrder.move(defaults, 0, 2)).containsExactly("B", "C", "A", "D").inOrder()
        assertThat(ListOrder.move(defaults, 3, 0)).containsExactly("D", "A", "B", "C").inOrder()
    }

    @Test
    fun moveOutOfRangeOrSameIsUnchanged() {
        assertThat(ListOrder.move(defaults, 1, 1)).isSameInstanceAs(defaults)
        assertThat(ListOrder.move(defaults, -1, 2)).isSameInstanceAs(defaults)
        assertThat(ListOrder.move(defaults, 0, 4)).isSameInstanceAs(defaults)
    }

    @Test
    fun emptyStoredKeepsDefaults() {
        assertThat(ListOrder.apply(defaults, emptyList()) { it }).isEqualTo(defaults)
    }

    @Test
    fun storedOrderWins() {
        assertThat(ListOrder.apply(defaults, listOf("D", "C", "B", "A")) { it }).containsExactly("D", "C", "B", "A").inOrder()
    }

    @Test
    fun unknownAndDuplicateKeysDropped() {
        assertThat(ListOrder.apply(defaults, listOf("C", "X", "C", "A", "B", "D")) { it })
            .containsExactly("C", "A", "B", "D")
            .inOrder()
    }

    @Test
    fun missingDefaultGoesAfterItsPredecessor() {
        // C is new: normally after B, so it follows B wherever B was dragged.
        assertThat(ListOrder.apply(defaults, listOf("B", "D", "A")) { it }).containsExactly("B", "C", "D", "A").inOrder()
    }

    @Test
    fun missingFirstDefaultGoesFirst() {
        assertThat(ListOrder.apply(defaults, listOf("D", "C", "B")) { it }).containsExactly("A", "D", "C", "B").inOrder()
    }

    @Test
    fun separatePlaylistKindsSwitchKeepsRest() {
        val split = listOf("HISTORY", "VIDEO_PLAYLISTS", "MUSIC_PLAYLISTS", "WATCH_LATER")
        val stored = listOf("WATCH_LATER", "PLAYLISTS", "HISTORY")
        assertThat(ListOrder.apply(split, stored) { it })
            .containsExactly("WATCH_LATER", "HISTORY", "VIDEO_PLAYLISTS", "MUSIC_PLAYLISTS")
            .inOrder()
    }

    @Test
    fun isDefaultComparesKeys() {
        assertThat(ListOrder.isDefault(defaults, listOf("A", "B", "C", "D")) { it }).isTrue()
        assertThat(ListOrder.isDefault(defaults, listOf("B", "A", "C", "D")) { it }).isFalse()
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val encoded = ListOrder.encode(listOf("A", "B", "A", " ", "C,D", "E"))
        assertThat(encoded).isEqualTo("A,B,E")
        assertThat(ListOrder.decode(encoded)).containsExactly("A", "B", "E").inOrder()
        assertThat(ListOrder.decode(null)).isEmpty()
        assertThat(ListOrder.decode(" , A ,,A")).containsExactly("A")
    }
}
