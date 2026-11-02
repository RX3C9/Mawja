package app.mawja.android.ui.screens.settings.appearance

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.AppShortcut
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.android.R
import app.mawja.android.account.MawjaFeature
import app.mawja.android.data.local.AppFontSelection
import app.mawja.android.data.local.HomeFeedColumns
import app.mawja.android.data.local.HomeViewMode
import app.mawja.android.platform.AppUiMode
import app.mawja.android.ui.components.settings.SettingsDestination
import app.mawja.android.ui.components.settings.SettingsPage
import app.mawja.android.ui.components.settings.SettingsTarget
import app.mawja.android.ui.components.settings.nav
import app.mawja.android.ui.components.settings.switch
import app.mawja.android.ui.components.settings.toggleGroup
import app.mawja.android.sound.UiSoundPack
import app.mawja.android.ui.components.shared.FlowChoice
import app.mawja.android.ui.components.shared.FlowChoiceDialog
import app.mawja.android.ui.components.shared.FlowToggleOption
import app.mawja.android.ui.screens.settings.appearance.theme.PremiumThemeUpsellSheet
import app.mawja.android.ui.screens.settings.index.AppearanceIndex
import app.mawja.android.ui.screens.settings.index.DestinationIndex
import app.mawja.android.ui.theme.AppFont
import app.mawja.android.ui.theme.GridItemSize
import app.mawja.android.ui.theme.ThemeCatalog
import app.mawja.android.ui.theme.ThemeMode
import app.mawja.android.util.PremiumIcons
import kotlinx.coroutines.launch

/** Everything about how Flow looks: theme, icon, layout, and the sub-pages for finer control. */
@Composable
internal fun AppearanceScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    onNavigate: (SettingsTarget) -> Unit,
    viewModel: AppearanceViewModel = hiltViewModel(),
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val themeVariant by viewModel.themeVariant.collectAsStateWithLifecycle()
    val customThemeName by viewModel.customThemeName.collectAsStateWithLifecycle()
    val interfaceMode by viewModel.interfaceMode.collectAsStateWithLifecycle()
    val font by viewModel.font.collectAsStateWithLifecycle()
    val appIcon by viewModel.appIcon.collectAsStateWithLifecycle()
    val entitlements by viewModel.entitlements.collectAsStateWithLifecycle()
    var iconUpsellName by rememberSaveable { mutableStateOf<Int?>(null) }
    val uiSounds by viewModel.uiSounds.collectAsStateWithLifecycle()
    var showSoundsUpsell by rememberSaveable { mutableStateOf(false) }
    val soundsLocked = !entitlements.has(MawjaFeature.UI_SOUNDS)
    val soundsLockedSummary = stringResource(R.string.premium_available_in_pro)
    val homeViewMode by viewModel.homeViewMode.collectAsStateWithLifecycle()
    val homeColumns by viewModel.homeColumns.collectAsStateWithLifecycle()
    val gridItemSize by viewModel.gridItemSize.collectAsStateWithLifecycle()
    val libraryPreviews by viewModel.libraryPreviews.collectAsStateWithLifecycle()
    val separatePlaylists by viewModel.separatePlaylists.collectAsStateWithLifecycle()
    val appLogo by viewModel.appLogo.collectAsStateWithLifecycle()
    val groupBadges by viewModel.groupBadges.collectAsStateWithLifecycle()
    val cardLikeButtons by viewModel.cardLikeButtons.collectAsStateWithLifecycle()
    val cardMarkWatched by viewModel.cardMarkWatched.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val iconAppliedMessage = stringResource(R.string.app_icon_apply_toast)
    var showIconDialog by rememberSaveable { mutableStateOf(false) }
    var showInterfaceDialog by rememberSaveable { mutableStateOf(false) }
    var showSoundPackDialog by rememberSaveable { mutableStateOf(false) }
    val uiSoundPack by viewModel.uiSoundPack.collectAsStateWithLifecycle()
    val soundPackName = stringResource(soundPackLabel(uiSoundPack))

    val themeSummary =
        if (themeMode == ThemeMode.SYSTEM) {
            stringResource(R.string.theme_name_system_default)
        } else {
            stringResource(
                R.string.settings_value_pair,
                customThemeName.takeIf { themeMode == ThemeMode.CUSTOM } ?: stringResource(ThemeCatalog.nameRes(themeMode)),
                stringResource(themeVariantLabel(themeVariant)),
            )
        }
    val iconSummary = stringResource(appIconOption(appIcon).nameRes)
    val fontSummary = fontLabel(font)
    val interfaceSummary = stringResource(interfaceModeLabel(interfaceMode))
    val viewModeOptions =
        listOf(
            FlowToggleOption(HomeViewMode.GRID, stringResource(R.string.content_settings_layout_grid), Icons.Outlined.GridView),
            FlowToggleOption(
                HomeViewMode.LIST,
                stringResource(R.string.content_settings_layout_list),
                Icons.AutoMirrored.Outlined.ViewList,
            ),
        )
    val columnOptions =
        HomeFeedColumns.entries.map { columns ->
            FlowToggleOption(
                columns,
                columns.fixedCount?.toString() ?: stringResource(R.string.content_settings_home_columns_auto),
            )
        }
    val artworkOptions =
        listOf(
            FlowToggleOption(GridItemSize.BIG, stringResource(R.string.content_settings_grid_big_title)),
            FlowToggleOption(GridItemSize.SMALL, stringResource(R.string.content_settings_grid_small_title)),
        )

    SettingsPage(
        title = stringResource(R.string.appearance_title),
        onBack = onBack,
        highlight = highlight,
        snackbarHostState = snackbarHostState,
    ) {
        group(key = "appearance.style", header = R.string.settings_section_style) {
            nav(
                DestinationIndex.entry(SettingsDestination.THEME),
                value = themeSummary,
                icon = Icons.Outlined.Palette,
                onClick = { onNavigate(SettingsTarget(SettingsDestination.THEME)) },
            )
            nav(
                DestinationIndex.entry(SettingsDestination.FONT),
                value = fontSummary,
                icon = Icons.Outlined.TextFields,
                onClick = { onNavigate(SettingsTarget(SettingsDestination.FONT)) },
            )
            nav(
                AppearanceIndex.appIcon,
                value = iconSummary,
                icon = Icons.Outlined.AppShortcut,
                showChevron = false,
                onClick = { showIconDialog = true },
            )
            switch(
                AppearanceIndex.uiSounds,
                checked = uiSounds && !soundsLocked,
                onCheckedChange = { on ->
                    if (soundsLocked) showSoundsUpsell = true else viewModel.setUiSounds(on)
                },
                summary = if (soundsLocked) soundsLockedSummary else null,
                icon = Icons.Outlined.MusicNote,
            )
            if (uiSounds && !soundsLocked) {
                nav(
                    AppearanceIndex.uiSoundPack,
                    value = soundPackName,
                    icon = Icons.Outlined.GraphicEq,
                    showChevron = false,
                    onClick = { showSoundPackDialog = true },
                )
            }
            nav(
                AppearanceIndex.interfaceMode,
                value = interfaceSummary,
                icon = Icons.Outlined.Tv,
                showChevron = false,
                onClick = { showInterfaceDialog = true },
            )
        }
        group(key = "appearance.layout", header = R.string.settings_section_layout) {
            toggleGroup(AppearanceIndex.homeViewMode, viewModeOptions, homeViewMode, viewModel::setHomeViewMode)
            if (homeViewMode == HomeViewMode.GRID) {
                toggleGroup(AppearanceIndex.homeColumns, columnOptions, homeColumns, viewModel::setHomeColumns)
            }
            toggleGroup(AppearanceIndex.musicArtworkSize, artworkOptions, gridItemSize, viewModel::setGridItemSize)
            switch(AppearanceIndex.libraryPreviews, libraryPreviews, viewModel::setLibraryPreviews)
            switch(AppearanceIndex.separatePlaylists, separatePlaylists, viewModel::setSeparatePlaylists)
            switch(AppearanceIndex.appLogo, appLogo, viewModel::setAppLogo)
            switch(AppearanceIndex.groupBadges, groupBadges, viewModel::setGroupBadges)
        }
        group(key = "appearance.cards", header = R.string.settings_section_video_cards) {
            switch(AppearanceIndex.cardLikeButtons, cardLikeButtons, viewModel::setCardLikeButtons)
            switch(AppearanceIndex.cardWatchProgress, viewModel.cardWatchProgress, viewModel::setCardWatchProgress)
            switch(AppearanceIndex.cardMarkWatched, cardMarkWatched, viewModel::setCardMarkWatched)
        }
        group(key = "appearance.more", header = R.string.settings_section_more) {
            listOf(
                SettingsDestination.NAVIGATION_BAR,
                SettingsDestination.DATE_TIME,
                SettingsDestination.PLAYER_APPEARANCE,
            ).forEach { destination ->
                nav(DestinationIndex.entry(destination), onClick = { onNavigate(SettingsTarget(destination)) })
            }
        }
    }

    if (showIconDialog) {
        AppIconDialog(
            selected = appIcon,
            onApply = { suffix ->
                viewModel.setAppIcon(suffix)
                viewModel.playSuccess()
                scope.launch { snackbarHostState.showSnackbar(iconAppliedMessage) }
            },
            onDismiss = { showIconDialog = false },
            isLocked = { PremiumIcons.isLocked(it, entitlements) },
            onLockedClick = { icon -> iconUpsellName = icon.nameRes },
        )
    }

    if (showSoundsUpsell) {
        PremiumThemeUpsellSheet(
            themeName = stringResource(R.string.settings_item_ui_sounds),
            titleRes = R.string.premium_available_in_pro,
            bodyRes = R.string.premium_sounds_upsell_body,
            freeNoteRes = R.string.premium_sounds_upsell_free_note,
            onSeePlans = {
                showSoundsUpsell = false
                onNavigate(SettingsTarget(SettingsDestination.UPGRADE))
            },
            onDismiss = { showSoundsUpsell = false },
        )
    }

    iconUpsellName?.let { nameRes ->
        PremiumThemeUpsellSheet(
            themeName = stringResource(nameRes),
            bodyRes = R.string.premium_icon_upsell_body,
            freeNoteRes = R.string.premium_icon_upsell_free_note,
            onSeePlans = {
                iconUpsellName = null
                showIconDialog = false
                onNavigate(SettingsTarget(SettingsDestination.UPGRADE))
            },
            onDismiss = { iconUpsellName = null },
        )
    }

    if (showSoundPackDialog) {
        FlowChoiceDialog(
            title = stringResource(R.string.settings_item_ui_sound_pack),
            options = UiSoundPack.entries.map { pack -> FlowChoice(pack, stringResource(soundPackLabel(pack)), stringResource(soundPackSummary(pack))) },
            selected = uiSoundPack,
            onSelect = viewModel::setUiSoundPack,
            onDismiss = { showSoundPackDialog = false },
        )
    }
    if (showInterfaceDialog) {
        FlowChoiceDialog(
            title = stringResource(R.string.interface_mode_title),
            options =
                AppUiMode.entries.map { mode ->
                    FlowChoice(mode, stringResource(interfaceModeLabel(mode)), stringResource(interfaceModeSummary(mode)))
                },
            selected = interfaceMode,
            onSelect = viewModel::setInterfaceMode,
            onDismiss = { showInterfaceDialog = false },
        )
    }
}

private fun soundPackLabel(pack: UiSoundPack): Int =
    when (pack) {
        UiSoundPack.WAVE -> R.string.sound_pack_wave
        UiSoundPack.DROP -> R.string.sound_pack_drop
        UiSoundPack.CRYSTAL -> R.string.sound_pack_crystal
        UiSoundPack.SOFT -> R.string.sound_pack_soft
        UiSoundPack.RETRO -> R.string.sound_pack_retro
    }

private fun soundPackSummary(pack: UiSoundPack): Int =
    when (pack) {
        UiSoundPack.WAVE -> R.string.sound_pack_wave_summary
        UiSoundPack.DROP -> R.string.sound_pack_drop_summary
        UiSoundPack.CRYSTAL -> R.string.sound_pack_crystal_summary
        UiSoundPack.SOFT -> R.string.sound_pack_soft_summary
        UiSoundPack.RETRO -> R.string.sound_pack_retro_summary
    }

private fun interfaceModeLabel(mode: AppUiMode): Int =
    when (mode) {
        AppUiMode.AUTOMATIC -> R.string.interface_mode_automatic
        AppUiMode.MOBILE -> R.string.interface_mode_mobile
        AppUiMode.TV -> R.string.interface_mode_tv
    }

private fun interfaceModeSummary(mode: AppUiMode): Int =
    when (mode) {
        AppUiMode.AUTOMATIC -> R.string.interface_mode_automatic_summary
        AppUiMode.MOBILE -> R.string.interface_mode_mobile_summary
        AppUiMode.TV -> R.string.interface_mode_tv_summary
    }

@Composable
private fun fontLabel(font: AppFontSelection): String =
    when (font.font) {
        AppFont.MAWJA -> stringResource(R.string.font_mawja)
        AppFont.SYSTEM -> stringResource(R.string.font_system_default)
        AppFont.CONDENSED -> stringResource(R.string.font_condensed)
        AppFont.SERIF -> stringResource(R.string.font_serif)
        AppFont.CUSTOM -> font.customName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.font_custom)
    }
