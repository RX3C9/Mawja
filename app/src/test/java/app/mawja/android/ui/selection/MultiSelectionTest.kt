/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.selection

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MultiSelectionTest {
    @Test
    fun longPressEntersWithRow() {
        val s = MultiSelection().longPress("a")
        assertThat(s.active).isTrue()
        assertThat(s.ids).containsExactly("a")
        assertThat(s.count).isEqualTo(1)
    }

    @Test
    fun longPressWhileSelectingToggles() {
        val s = MultiSelection().longPress("a").longPress("b")
        assertThat(s.ids).containsExactly("a", "b")
        assertThat(s.longPress("b").ids).containsExactly("a")
    }

    @Test
    fun uncheckingLastLeavesMode() {
        val s = MultiSelection().longPress("a").toggle("a")
        assertThat(s).isEqualTo(MultiSelection())
    }

    @Test
    fun enterStartsEmptyAndStays() {
        val s = MultiSelection().enter()
        assertThat(s.active).isTrue()
        assertThat(s.ids).isEmpty()
        assertThat(s.enter()).isSameInstanceAs(s)
    }

    @Test
    fun toggleAllSelectsThenClearsShown() {
        val shown = listOf("a", "b", "c")
        val all = MultiSelection().longPress("a").toggleAll(shown)
        assertThat(all.ids).containsExactly("a", "b", "c")
        assertThat(all.allSelected(shown)).isTrue()
        val none = all.toggleAll(shown)
        assertThat(none.active).isTrue()
        assertThat(none.ids).isEmpty()
    }

    @Test
    fun toggleAllKeepsRowsHiddenBySearch() {
        val s = MultiSelection().longPress("x").toggleAll(listOf("a", "b"))
        assertThat(s.ids).containsExactly("x", "a", "b")
        assertThat(s.toggleAll(listOf("a", "b")).ids).containsExactly("x")
        assertThat(s.toggleAll(emptyList())).isSameInstanceAs(s)
    }

    @Test
    fun allSelectedFalseForEmptyShown() {
        assertThat(MultiSelection().enter().allSelected(emptyList())).isFalse()
    }

    @Test
    fun retainDropsGoneRows() {
        val s = MultiSelection().longPress("a").toggle("b").toggle("c")
        assertThat(s.retain(listOf("a", "c", "d")).ids).containsExactly("a", "c")
        assertThat(s.retain(setOf("a", "b", "c"))).isSameInstanceAs(s)
        assertThat(s.retain(emptyList())).isEqualTo(MultiSelection())
        assertThat(MultiSelection().retain(listOf("a"))).isEqualTo(MultiSelection())
    }

    @Test
    fun pickKeepsListOrderAndPlayNextReverses() {
        val s = MultiSelection().longPress("c").toggle("a")
        val picked = s.pick(listOf("a", "b", "c", "d")) { it }
        assertThat(picked).containsExactly("a", "c").inOrder()
        assertThat(MultiSelection.playNextOrder(picked)).containsExactly("c", "a").inOrder()
    }

    @Test
    fun clearResets() {
        assertThat(MultiSelection().longPress("a").clear()).isEqualTo(MultiSelection())
        assertThat("a" in MultiSelection().longPress("a")).isTrue()
    }
}
