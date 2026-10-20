package app.mawja.android.ui.tv.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.mawja.android.ui.components.settings.SettingsDestination
import app.mawja.android.ui.screens.recap.RecapRoutes
import app.mawja.android.ui.screens.recap.RecapScreen
import app.mawja.android.ui.screens.recap.story.RecapStoryScreen
import app.mawja.android.ui.screens.settings.SettingsHost
import app.mawja.android.ui.screens.settings.SettingsHostOptions
import app.mawja.android.ui.screens.settings.about.DonationsScreen
import app.mawja.android.ui.screens.sync.SyncScreen
import app.mawja.android.ui.screens.update.UPDATE_ROUTE
import app.mawja.android.ui.screens.update.UpdateScreen
import app.mawja.android.ui.tv.components.TvScreenFrame
import app.mawja.android.ui.tv.screens.TvRemoteGuideScreen

/** Settings, hosted from the phone with TV's own Sync and remote guide pages, and the screens it opens. */
internal fun NavGraphBuilder.tvSettingsRoutes(navController: NavHostController) {
    composable(TV_SETTINGS_ROUTE) {
        val options =
            remember {
                SettingsHostOptions(
                    pages =
                        mapOf(
                            SettingsDestination.SYNC to { onBack -> SyncScreen(onBack = onBack, hostOnly = true) },
                            SettingsDestination.REMOTE_GUIDE to { onBack -> TvRemoteGuideScreen(onBack = onBack) },
                        ),
                )
            }
        TvScreenFrame {
            SettingsHost(
                start = null,
                onExit = { navController.popBackStack() },
                onOpenDonations = { navController.navigate(TvRoutes.DONATIONS) },
                onOpenRecap = { navController.navigate(RecapRoutes.stats()) },
                onOpenUpdate = { navController.navigate(UPDATE_ROUTE) },
                options = options,
            )
        }
    }
    composable(TvRoutes.DONATIONS) {
        TvScreenFrame { DonationsScreen(onNavigateBack = { navController.popBackStack() }) }
    }
    composable(UPDATE_ROUTE) {
        TvScreenFrame { UpdateScreen(onClose = { navController.popBackStack() }) }
    }
    composable(
        route = RecapRoutes.STATS,
        arguments =
            listOf(
                navArgument(RecapRoutes.ARG_PERIOD) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) { backStackEntry ->
        TvScreenFrame {
            RecapScreen(
                onBack = { navController.popBackStack() },
                onPlayStory = { period, source -> navController.navigate(RecapRoutes.story(period, source)) },
                startAt = RecapRoutes.decode(backStackEntry.arguments?.getString(RecapRoutes.ARG_PERIOD)),
            )
        }
    }
    composable(
        route = RecapRoutes.STORY,
        arguments =
            listOf(
                navArgument(RecapRoutes.ARG_PERIOD) { type = NavType.StringType },
                navArgument(RecapRoutes.ARG_SOURCE) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) { backStackEntry ->
        val period = RecapRoutes.decode(backStackEntry.arguments?.getString(RecapRoutes.ARG_PERIOD))
        if (period == null) {
            LaunchedEffect(Unit) { navController.popBackStack() }
        } else {
            RecapStoryScreen(
                period = period,
                source = RecapRoutes.decodeSource(backStackEntry.arguments?.getString(RecapRoutes.ARG_SOURCE)),
                onClose = { navController.popBackStack() },
            )
        }
    }
}
