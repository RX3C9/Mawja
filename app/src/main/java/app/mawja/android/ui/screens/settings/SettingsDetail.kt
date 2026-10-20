package app.mawja.android.ui.screens.settings

import androidx.compose.runtime.Composable
import app.mawja.android.ui.components.settings.SettingsDestination
import app.mawja.android.ui.components.settings.SettingsTarget
import app.mawja.android.ui.screens.equalizer.EqualizerScreen
import app.mawja.android.ui.screens.settings.about.AboutScreen
import app.mawja.android.ui.screens.settings.account.AccountScreen
import app.mawja.android.ui.screens.settings.appearance.AppearanceScreen
import app.mawja.android.ui.screens.settings.appearance.DateTimeScreen
import app.mawja.android.ui.screens.settings.appearance.FontScreen
import app.mawja.android.ui.screens.settings.appearance.NavigationBarScreen
import app.mawja.android.ui.screens.settings.appearance.player.PlayerAppearanceScreen
import app.mawja.android.ui.screens.settings.appearance.theme.CustomThemeEditorScreen
import app.mawja.android.ui.screens.settings.appearance.theme.CustomThemesScreen
import app.mawja.android.ui.screens.settings.appearance.theme.ThemeScreen
import app.mawja.android.ui.screens.settings.backup.BackupScreen
import app.mawja.android.ui.screens.settings.content.ContentSettingsScreen
import app.mawja.android.ui.screens.settings.content.LibrarySettingsScreen
import app.mawja.android.ui.screens.settings.content.MusicHomeSettingsScreen
import app.mawja.android.ui.screens.settings.diagnostics.DiagnosticsScreen
import app.mawja.android.ui.screens.settings.downloads.DownloadSettingsScreen
import app.mawja.android.ui.screens.settings.history.HistorySettingsScreen
import app.mawja.android.ui.screens.settings.integrations.IntegrationsScreen
import app.mawja.android.ui.screens.settings.localmedia.LocalMediaSettingsScreen
import app.mawja.android.ui.screens.settings.network.NetworkSettingsScreen
import app.mawja.android.ui.screens.settings.notifications.NotificationSettingsScreen
import app.mawja.android.ui.screens.settings.playback.BufferSettingsScreen
import app.mawja.android.ui.screens.settings.playback.PlaybackSettingsScreen
import app.mawja.android.ui.screens.settings.quality.QualitySettingsScreen
import app.mawja.android.ui.screens.settings.region.LanguageRegionScreen
import app.mawja.android.ui.screens.settings.scrobbling.ScrobblingScreen
import app.mawja.android.ui.screens.settings.taste.DiscoverChannelsScreen
import app.mawja.android.ui.screens.settings.taste.FavouriteArtistsScreen
import app.mawja.android.ui.screens.settings.taste.HiddenContentScreen
import app.mawja.android.ui.screens.settings.taste.TasteScreen
import app.mawja.android.ui.screens.settings.topics.TopicPreferencesScreen
import app.mawja.android.ui.screens.settings.wellbeing.WellbeingScreen
import app.mawja.android.ui.screens.settings.widgets.WidgetsSettingsScreen
import app.mawja.android.ui.screens.sync.SyncScreen

/**
 * The page a [SettingsTarget] opens. [onBack] is null for the root page beside the settings list,
 * where there is nothing to go back to; [onNavigate] opens a sub-page over this one.
 */
@Composable
internal fun SettingsDetail(
    target: SettingsTarget,
    onBack: (() -> Unit)?,
    onNavigate: (SettingsTarget) -> Unit,
    onOpenRecap: () -> Unit,
    options: SettingsHostOptions = SettingsHostOptions(),
) {
    options.pages[target.destination]?.let { page ->
        page(onBack)
        return
    }
    when (target.destination) {
        SettingsDestination.HOME,
        SettingsDestination.APPEARANCE,
        -> {
            AppearanceScreen(onBack = onBack, highlight = target.highlight, onNavigate = onNavigate)
        }

        SettingsDestination.TASTE -> {
            TasteScreen(onBack = onBack, highlight = target.highlight, onNavigate = onNavigate, onOpenRecap = onOpenRecap)
        }

        SettingsDestination.HIDDEN_CONTENT -> {
            HiddenContentScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.FAVOURITE_ARTISTS -> {
            FavouriteArtistsScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.DISCOVER_CHANNELS -> {
            DiscoverChannelsScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.THEME -> {
            ThemeScreen(onBack = onBack, highlight = target.highlight, onNavigate = onNavigate)
        }

        SettingsDestination.FONT -> {
            FontScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.CUSTOM_THEME -> {
            CustomThemesScreen(onBack = onBack, highlight = target.highlight, onNavigate = onNavigate)
        }

        SettingsDestination.CUSTOM_THEME_EDIT -> {
            CustomThemeEditorScreen(
                themeId = target.tab,
                onBack = onBack,
                highlight = target.highlight,
            )
        }

        SettingsDestination.NAVIGATION_BAR -> {
            NavigationBarScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.CONTENT -> {
            ContentSettingsScreen(onBack = onBack, highlight = target.highlight, onNavigate = onNavigate)
        }

        SettingsDestination.MUSIC_HOME -> {
            MusicHomeSettingsScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.LIBRARY -> {
            LibrarySettingsScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.DATE_TIME -> {
            DateTimeScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.PLAYER_APPEARANCE -> {
            PlayerAppearanceScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.LANGUAGE_REGION -> {
            LanguageRegionScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.PLAYBACK -> {
            PlaybackSettingsScreen(onBack = onBack, highlight = target.highlight, onNavigate = onNavigate)
        }

        SettingsDestination.BUFFER -> {
            BufferSettingsScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.EQUALIZER -> {
            EqualizerScreen(onBack = onBack, inSettings = true)
        }

        SettingsDestination.QUALITY -> {
            QualitySettingsScreen(onBack = onBack, highlight = target.highlight, tab = target.tab)
        }

        SettingsDestination.TOPICS -> {
            TopicPreferencesScreen(onBack = onBack, highlight = target.highlight, tab = target.tab)
        }

        SettingsDestination.INTEGRATIONS -> {
            IntegrationsScreen(onBack = onBack, highlight = target.highlight, onNavigate = onNavigate)
        }

        SettingsDestination.SCROBBLING -> {
            ScrobblingScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.BACKUP -> {
            BackupScreen(onBack = onBack, highlight = target.highlight, tab = target.tab)
        }

        SettingsDestination.SYNC -> {
            SyncScreen(onBack = onBack)
        }

        SettingsDestination.HISTORY -> {
            HistorySettingsScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.DOWNLOADS -> {
            DownloadSettingsScreen(onBack = onBack, highlight = target.highlight, onNavigate = onNavigate)
        }

        SettingsDestination.LOCAL_MEDIA -> {
            LocalMediaSettingsScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.NOTIFICATIONS -> {
            NotificationSettingsScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.NETWORK -> {
            NetworkSettingsScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.WELLBEING -> {
            WellbeingScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.WIDGETS -> {
            WidgetsSettingsScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.ABOUT -> {
            AboutScreen(onBack = onBack, highlight = target.highlight, onNavigate = onNavigate)
        }

        SettingsDestination.REMOTE_GUIDE -> {
            Unit
        }

        SettingsDestination.ACCOUNT -> {
            AccountScreen(onBack = onBack)
        }

        SettingsDestination.DIAGNOSTICS -> {
            DiagnosticsScreen(onBack = onBack, highlight = target.highlight)
        }
    }
}
