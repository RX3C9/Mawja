/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.screens.settings.battery

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.android.R
import app.mawja.android.battery.BatterySaver
import app.mawja.android.battery.BatterySaverMode
import app.mawja.android.battery.BatterySaverPolicy
import app.mawja.android.battery.BatterySaverReason
import app.mawja.android.ui.components.settings.SettingsPage
import app.mawja.android.ui.components.settings.info
import app.mawja.android.ui.components.settings.notice
import app.mawja.android.ui.components.settings.switch
import app.mawja.android.ui.components.settings.toggleGroup
import app.mawja.android.ui.components.shared.FlowToggleOption
import app.mawja.android.ui.screens.settings.index.BatterySaverIndex

/** Mawja deep battery saver (PLAN_V2 #62): Off / Auto / Always, the Auto threshold, extras and live status. */
@Composable
internal fun BatterySaverScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
) {
    val context = LocalContext.current
    val settings by BatterySaver.settings.collectAsStateWithLifecycle()
    val active by BatterySaver.active.collectAsStateWithLifecycle()
    val reason by BatterySaver.reason.collectAsStateWithLifecycle()
    val reading by BatterySaver.reading.collectAsStateWithLifecycle()

    val modeOptions =
        listOf(
            FlowToggleOption(BatterySaverMode.OFF, stringResource(R.string.battery_saver_mode_off)),
            FlowToggleOption(BatterySaverMode.AUTO, stringResource(R.string.battery_saver_mode_auto)),
            FlowToggleOption(BatterySaverMode.ALWAYS, stringResource(R.string.battery_saver_mode_always)),
        )
    val thresholdOptions =
        BatterySaverPolicy.THRESHOLD_CHOICES.map { FlowToggleOption(it, stringResource(R.string.battery_saver_percent, it)) }
    val selectedThreshold =
        BatterySaverPolicy.THRESHOLD_CHOICES.minByOrNull { kotlin.math.abs(it - settings.thresholdPercent) } ?: BatterySaverPolicy.DEFAULT_THRESHOLD

    val statusTitle = stringResource(R.string.battery_saver_status)
    val statusValue =
        stringResource(if (active) R.string.battery_saver_status_active else R.string.battery_saver_status_inactive) +
            " · " + stringResource(reasonRes(reason))
    val levelTitle = stringResource(R.string.battery_saver_level)
    val levelValue =
        if (reading.levelPercent < 0) {
            stringResource(R.string.battery_saver_level_unknown)
        } else {
            stringResource(R.string.battery_saver_percent, reading.levelPercent) +
                if (reading.charging) " · " + stringResource(R.string.battery_saver_charging) else ""
        }

    SettingsPage(
        title = stringResource(R.string.battery_saver_title),
        subtitle = stringResource(R.string.battery_saver_summary),
        onBack = onBack,
        highlight = highlight,
    ) {
        group(key = "battery.status", header = R.string.battery_saver_section_status) {
            info("battery.status_now", statusTitle, statusValue, icon = Icons.Outlined.BatterySaver)
            info("battery.level_now", levelTitle, levelValue, icon = Icons.Outlined.BatteryChargingFull)
        }
        group(key = "battery.main", header = R.string.battery_saver_title, footer = R.string.battery_saver_footer) {
            toggleGroup(
                BatterySaverIndex.mode,
                modeOptions,
                settings.mode,
                { BatterySaver.setMode(context, it) },
            )
            toggleGroup(
                BatterySaverIndex.threshold,
                thresholdOptions,
                selectedThreshold,
                { BatterySaver.setThreshold(context, it) },
                enabled = settings.mode == BatterySaverMode.AUTO,
            )
        }
        group(key = "battery.extras", header = R.string.battery_saver_section_extras) {
            switch(
                BatterySaverIndex.blackTheme,
                settings.blackTheme,
                { BatterySaver.setBlackTheme(context, it) },
                icon = Icons.Outlined.DarkMode,
            )
            switch(
                BatterySaverIndex.reduceMotion,
                settings.reduceMotion,
                { BatterySaver.setReduceMotion(context, it) },
                icon = Icons.Outlined.Animation,
            )
        }
        notice("battery.about", text = { stringResource(R.string.battery_saver_about) }, icon = Icons.Outlined.Info)
    }
}

private fun reasonRes(reason: BatterySaverReason): Int =
    when (reason) {
        BatterySaverReason.OFF -> R.string.battery_saver_reason_off
        BatterySaverReason.REMOTE_DISABLED -> R.string.battery_saver_reason_remote
        BatterySaverReason.ALWAYS -> R.string.battery_saver_reason_always
        BatterySaverReason.SYSTEM_POWER_SAVE -> R.string.battery_saver_reason_system
        BatterySaverReason.LOW_BATTERY -> R.string.battery_saver_reason_low
        BatterySaverReason.CHARGING -> R.string.battery_saver_reason_charging
        BatterySaverReason.BATTERY_OK -> R.string.battery_saver_reason_ok
    }
