/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.screens.settings.accessibility

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.android.R
import app.mawja.android.accessibility.AccessibilityPolicy
import app.mawja.android.accessibility.ColorVisionMode
import app.mawja.android.accessibility.MawjaAccessibility
import app.mawja.android.accessibility.TextSizeMode
import app.mawja.android.ui.components.settings.SettingsPage
import app.mawja.android.ui.components.settings.info
import app.mawja.android.ui.components.settings.notice
import app.mawja.android.ui.components.settings.switch
import app.mawja.android.ui.components.settings.toggleGroup
import app.mawja.android.ui.components.shared.FlowToggleOption
import app.mawja.android.ui.screens.settings.index.AccessibilityIndex

/**
 * Mawja accessibility settings: text size on top of the system's, high contrast, a colour-blind
 * mode and reduce motion (#161-#163). Every option applies at once, app-wide, through FlowTheme.
 */
@Composable
internal fun AccessibilityScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
) {
    val context = LocalContext.current
    val settings by MawjaAccessibility.settings.collectAsStateWithLifecycle()
    val animatorScale by MawjaAccessibility.animatorScale.collectAsStateWithLifecycle()
    val reduceMotionActive by MawjaAccessibility.reduceMotion.collectAsStateWithLifecycle()

    val textOptions =
        listOf(
            FlowToggleOption(TextSizeMode.SYSTEM, stringResource(R.string.a11y_text_size_system)),
            FlowToggleOption(TextSizeMode.LARGE, stringResource(R.string.a11y_text_size_large)),
            FlowToggleOption(TextSizeMode.LARGEST, stringResource(R.string.a11y_text_size_largest)),
        )
    val colorOptions =
        listOf(
            FlowToggleOption(ColorVisionMode.DEFAULT, stringResource(R.string.a11y_color_vision_default)),
            FlowToggleOption(ColorVisionMode.RED_GREEN, stringResource(R.string.a11y_color_vision_red_green)),
            FlowToggleOption(ColorVisionMode.BLUE_YELLOW, stringResource(R.string.a11y_color_vision_blue_yellow)),
        )

    // LocalDensity here already carries the boost, so the percentage is what the app renders at.
    val renderedPercent = (LocalDensity.current.fontScale * 100f + 0.5f).toInt()
    val systemAnimationsOff = AccessibilityPolicy.systemAnimationsOff(animatorScale)
    val motionSummary =
        when {
            settings.reduceMotion -> null
            systemAnimationsOff -> stringResource(R.string.a11y_reduce_motion_system_on)
            reduceMotionActive -> stringResource(R.string.a11y_reduce_motion_saver_on)
            else -> null
        }

    val textSizeNow = stringResource(R.string.a11y_text_size_now, renderedPercent)
    val systemAnimationsTitle = stringResource(R.string.a11y_system_animations)
    val systemAnimationsValue =
        stringResource(if (systemAnimationsOff) R.string.a11y_system_animations_off else R.string.a11y_system_animations_on)

    SettingsPage(
        title = stringResource(R.string.a11y_settings_title),
        subtitle = stringResource(R.string.a11y_settings_summary),
        onBack = onBack,
        highlight = highlight,
    ) {
        group(key = "accessibility.text", header = R.string.a11y_section_text, footer = R.string.a11y_text_footer) {
            toggleGroup(
                AccessibilityIndex.textSize,
                textOptions,
                settings.textSize,
                { MawjaAccessibility.setTextSize(context, it) },
                summary = textSizeNow,
            )
            switch(
                AccessibilityIndex.highContrast,
                settings.highContrast,
                { MawjaAccessibility.setHighContrast(context, it) },
                icon = Icons.Outlined.Visibility,
            )
        }
        group(key = "accessibility.color", header = R.string.a11y_section_color, footer = R.string.a11y_color_footer) {
            toggleGroup(
                AccessibilityIndex.colorVision,
                colorOptions,
                settings.colorVision,
                { MawjaAccessibility.setColorVision(context, it) },
            )
        }
        group(key = "accessibility.motion", header = R.string.a11y_section_motion) {
            switch(
                AccessibilityIndex.reduceMotion,
                settings.reduceMotion,
                { MawjaAccessibility.setReduceMotion(context, it) },
                summary = motionSummary,
                icon = Icons.Outlined.Animation,
            )
            info(
                "accessibility.system_animations",
                systemAnimationsTitle,
                systemAnimationsValue,
            )
        }
        notice("accessibility.talkback", text = { stringResource(R.string.a11y_talkback_notice) }, icon = Icons.Outlined.Info)
    }
}
