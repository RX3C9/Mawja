package app.mawja.android.ui.startup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.font.FontFamily
import app.mawja.android.account.Entitlements
import app.mawja.android.data.local.AppFontPreferences
import app.mawja.android.data.local.LocalDataManager
import app.mawja.android.ui.theme.CustomTheme
import app.mawja.android.ui.theme.FlowFontFamily
import app.mawja.android.ui.theme.FlowTheme
import app.mawja.android.ui.theme.PremiumThemes
import app.mawja.android.ui.theme.ThemeMode
import app.mawja.android.ui.theme.ThemeVariant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Every stored theme choice the root theme needs, read as one value so the first frame has all of them. */
@Immutable
data class ThemeSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val themeVariant: ThemeVariant = ThemeVariant.DARK,
    val customTheme: CustomTheme? = null,
    val systemLightThemeMode: ThemeMode = ThemeMode.DARK,
    val systemDarkThemeMode: ThemeMode = ThemeMode.DARK,
    val systemDarkThemeVariant: ThemeVariant = ThemeVariant.DARK,
    val fontFamily: FontFamily = FlowFontFamily,
)

fun LocalDataManager.themeSettings(fonts: AppFontPreferences): Flow<ThemeSettings> =
    combine(themeMode, themeVariant, activeCustomTheme, systemLightThemeMode, systemDarkThemeMode) { mode, variant, custom, light, dark ->
        ThemeSettings(mode, variant, custom, light, dark)
    }.combine(systemDarkThemeVariant) { settings, darkVariant -> settings.copy(systemDarkThemeVariant = darkVariant) }
        .combine(fonts.fontFamily) { settings, family -> settings.copy(fontFamily = family) }

/** The same settings with any premium palette the user does not have shown as Flow Default. */
fun ThemeSettings.gatedBy(entitlements: Entitlements): ThemeSettings =
    copy(
        themeMode = PremiumThemes.effective(themeMode, entitlements),
        systemLightThemeMode = PremiumThemes.effective(systemLightThemeMode, entitlements),
        systemDarkThemeMode = PremiumThemes.effective(systemDarkThemeMode, entitlements),
    )

@Composable
fun FlowTheme(
    settings: ThemeSettings,
    content: @Composable () -> Unit,
) = FlowTheme(
    themeMode = settings.themeMode,
    themeVariant = settings.themeVariant,
    customTheme = settings.customTheme,
    systemLightThemeMode = settings.systemLightThemeMode,
    systemDarkThemeMode = settings.systemDarkThemeMode,
    systemDarkThemeVariant = settings.systemDarkThemeVariant,
    fontFamily = settings.fontFamily,
    content = content,
)
