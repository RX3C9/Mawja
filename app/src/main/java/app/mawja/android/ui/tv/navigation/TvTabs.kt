package app.mawja.android.ui.tv.navigation

import androidx.compose.runtime.Immutable
import app.mawja.android.ui.components.layout.navigation.MawjaTab
import app.mawja.android.ui.components.layout.navigation.NavigationVisibility
import app.mawja.android.ui.components.layout.navigation.resolveDefaultFlowTab
import app.mawja.android.ui.components.layout.navigation.visibleFlowTabs

/** The rail's foot: Settings is a TV root destination, not a tab the user orders. */
const val TV_SETTINGS_ROUTE = "settings"

/** The rail's tabs in the user's order, and the tab the app opens on. */
@Immutable
data class TvNavigation(
    val tabs: List<MawjaTab>,
    val startTab: MawjaTab,
) {
    val rootRoutes: Set<String> get() = tabs.mapTo(mutableSetOf(), MawjaTab::route) + TV_SETTINGS_ROUTE
}

/**
 * The user's tabs as TV shows them. Shorts and Explore stay off TV, and Search is always on: a phone
 * keeps it in the top bar, which TV does not have.
 */
fun tvNavigation(
    order: List<Int>,
    defaultTabId: Int,
    visibility: NavigationVisibility,
): TvNavigation {
    val tvVisibility = visibility.copy(shorts = false, search = true, categories = false)
    return TvNavigation(
        tabs = visibleFlowTabs(order, tvVisibility),
        startTab = resolveDefaultFlowTab(defaultTabId, order, tvVisibility),
    )
}
