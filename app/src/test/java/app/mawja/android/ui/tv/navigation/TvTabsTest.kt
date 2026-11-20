package app.mawja.android.ui.tv.navigation

import com.google.common.truth.Truth.assertThat
import app.mawja.android.ui.components.layout.navigation.MawjaTab
import app.mawja.android.ui.components.layout.navigation.NavigationVisibility
import org.junit.Test

class TvTabsTest {
    private val allOn = NavigationVisibility(home = true, shorts = true, music = true, search = true, categories = true)

    @Test
    fun `follows the user's order and leaves Shorts and Explore off`() {
        val order = listOf(MawjaTab.Library, MawjaTab.Shorts, MawjaTab.Music, MawjaTab.Explore, MawjaTab.Home).map(MawjaTab::id)

        val tabs = tvNavigation(order, defaultTabId = MawjaTab.Home.id, visibility = allOn).tabs

        assertThat(tabs.take(3)).containsExactly(MawjaTab.Library, MawjaTab.Music, MawjaTab.Home).inOrder()
        assertThat(tabs).containsNoneOf(MawjaTab.Shorts, MawjaTab.Explore)
    }

    @Test
    fun `always shows Search, which TV has nowhere else to put`() {
        val tabs = tvNavigation(emptyList(), MawjaTab.Home.id, NavigationVisibility(search = false)).tabs

        assertThat(tabs).contains(MawjaTab.Search)
    }

    @Test
    fun `hides the tabs the user hid`() {
        val tabs = tvNavigation(emptyList(), MawjaTab.Home.id, NavigationVisibility(home = false, music = false)).tabs

        assertThat(tabs).containsNoneOf(MawjaTab.Home, MawjaTab.Music)
    }

    @Test
    fun `opens on the user's default tab, or the first tab when TV leaves it off`() {
        assertThat(tvNavigation(emptyList(), MawjaTab.Library.id, allOn).startTab).isEqualTo(MawjaTab.Library)

        val fromShorts = tvNavigation(emptyList(), MawjaTab.Shorts.id, allOn)
        assertThat(fromShorts.startTab).isEqualTo(fromShorts.tabs.first())
    }

    @Test
    fun `roots are the tabs plus Settings`() {
        val navigation = tvNavigation(emptyList(), MawjaTab.Home.id, allOn)

        assertThat(navigation.rootRoutes).containsExactlyElementsIn(navigation.tabs.map(MawjaTab::route) + TV_SETTINGS_ROUTE)
    }
}
