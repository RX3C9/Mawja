package app.mawja.android.ui.screens.settings.notifications

import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.Update
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.android.BuildConfig
import app.mawja.android.R
import app.mawja.android.notification.BackgroundWorkPolicy
import app.mawja.android.ui.components.settings.SettingsPage
import app.mawja.android.ui.components.settings.nav
import app.mawja.android.ui.components.settings.switch
import app.mawja.android.smartnotify.SmartNotifyStore
import app.mawja.android.ui.components.shared.FlowAlertDialog
import app.mawja.android.ui.components.shared.FlowChoice
import app.mawja.android.ui.components.shared.FlowChoiceDialog
import app.mawja.android.ui.components.shared.FlowNavRow
import app.mawja.android.ui.screens.settings.index.NotificationsIndex

private val IntervalOptions =
    listOf(
        15 to R.string.notif_interval_15m,
        30 to R.string.notif_interval_30m,
        60 to R.string.notif_interval_1h,
        120 to R.string.notif_interval_2h,
        180 to R.string.notif_interval_3h,
        360 to R.string.notif_interval_6h,
        720 to R.string.notif_interval_12h,
        1440 to R.string.notif_interval_24h,
    )

/** The background feed check, which kinds of alert Flow may post, and the system channel settings. */
@Composable
internal fun NotificationSettingsScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    viewModel: NotificationSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val newVideosOn by viewModel.newVideos.collectAsStateWithLifecycle()
    val interval by viewModel.intervalMinutes.collectAsStateWithLifecycle()
    val backgroundAllowed by viewModel.backgroundAllowed.collectAsStateWithLifecycle()
    var showIntervalDialog by rememberSaveable { mutableStateOf(false) }
    var showSmartLimitDialog by rememberSaveable { mutableStateOf(false) }
    var showSmartResetDialog by rememberSaveable { mutableStateOf(false) }
    val smartOn by viewModel.smartEnabled.collectAsStateWithLifecycle()
    val smartLimit by viewModel.smartDailyLimit.collectAsStateWithLifecycle()
    val smartLimitLabel = pluralStringResource(R.plurals.smart_notify_daily_limit_value, smartLimit, smartLimit)
    LifecycleResumeEffect(Unit) {
        viewModel.refreshBackgroundWork()
        onPauseOrDispose { }
    }

    val intervalLabel =
        IntervalOptions.firstOrNull { it.first == interval }?.let { stringResource(it.second) }
            ?: stringResource(R.string.duration_minutes_short, interval)
    val intervalSummary =
        when {
            !enabled -> stringResource(R.string.notif_check_interval_disabled)
            !newVideosOn -> stringResource(R.string.notif_check_interval_off_new_videos)
            else -> stringResource(R.string.notif_check_interval_subtitle_template, intervalLabel)
        }
    val restrictedTitle = stringResource(R.string.notif_background_restricted_title)
    val restrictedBody = stringResource(R.string.notif_background_restricted_subtitle)

    SettingsPage(
        title = stringResource(R.string.notif_settings_title),
        onBack = onBack,
        highlight = highlight,
    ) {
        group(key = "notifications.schedule", header = R.string.notif_check_interval_section_header) {
            switch(
                NotificationsIndex.enabled,
                enabled,
                { on ->
                    viewModel.setEnabled(on)
                    if (on && !backgroundAllowed) BackgroundWorkPolicy.requestUnrestrictedBackgroundWork(context)
                },
                icon = Icons.Outlined.NotificationsActive,
            )
            if (enabled && !backgroundAllowed) {
                row("notifications.restricted") { shape ->
                    FlowNavRow(
                        title = restrictedTitle,
                        supportingText = restrictedBody,
                        leadingIcon = Icons.Outlined.BatteryAlert,
                        showChevron = false,
                        shape = shape,
                        onClick = { BackgroundWorkPolicy.requestUnrestrictedBackgroundWork(context) },
                    )
                }
            }
            nav(
                NotificationsIndex.interval,
                value = intervalSummary,
                enabled = enabled && newVideosOn,
                showChevron = false,
                icon = Icons.Outlined.Schedule,
                onClick = { showIntervalDialog = true },
            )
        }
        group(key = "notifications.types", header = R.string.settings_section_notification_types) {
            switch(
                NotificationsIndex.newVideos,
                viewModel.newVideos,
                viewModel::setNewVideos,
                enabled = enabled,
                icon = Icons.Outlined.Subscriptions,
            )
            switch(
                NotificationsIndex.shorts,
                viewModel.shorts,
                viewModel::setShorts,
                enabled = enabled && newVideosOn,
                icon = Icons.Outlined.Slideshow,
            )
            switch(
                NotificationsIndex.downloads,
                viewModel.downloads,
                viewModel::setDownloads,
                enabled = enabled,
                icon = Icons.Outlined.Download,
            )
            switch(
                NotificationsIndex.reminders,
                viewModel.reminders,
                viewModel::setReminders,
                enabled = enabled,
                icon = Icons.Outlined.Bedtime,
            )
            if (BuildConfig.UPDATER_ENABLED) {
                switch(
                    NotificationsIndex.updates,
                    viewModel.updates,
                    viewModel::setUpdates,
                    enabled = enabled,
                    icon = Icons.Outlined.Update,
                )
            }
            switch(
                NotificationsIndex.general,
                viewModel.general,
                viewModel::setGeneral,
                enabled = enabled,
                icon = Icons.Outlined.Notifications,
            )
        }
        group(key = "notifications.smart", header = R.string.smart_notify_section) {
            switch(
                NotificationsIndex.smartEnabled,
                smartOn,
                viewModel::setSmartEnabled,
                enabled = enabled,
                icon = Icons.Outlined.AutoAwesome,
            )
            nav(
                NotificationsIndex.smartLimit,
                value = smartLimitLabel,
                enabled = enabled && smartOn,
                showChevron = false,
                icon = Icons.Outlined.Tune,
                onClick = { showSmartLimitDialog = true },
            )
            nav(
                NotificationsIndex.smartReset,
                enabled = enabled,
                showChevron = false,
                icon = Icons.Outlined.RestartAlt,
                onClick = { showSmartResetDialog = true },
            )
        }
        group(key = "notifications.system", header = R.string.settings_section_more) {
            nav(
                NotificationsIndex.system,
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                showChevron = false,
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                },
            )
        }
    }

    if (showSmartLimitDialog) {
        FlowChoiceDialog(
            title = stringResource(R.string.smart_notify_daily_limit),
            description = stringResource(R.string.smart_notify_daily_limit_body),
            options =
                (1..SmartNotifyStore.MAX_DAILY_LIMIT).map { count ->
                    FlowChoice(count, pluralStringResource(R.plurals.smart_notify_daily_limit_value, count, count))
                },
            selected = smartLimit,
            onSelect = viewModel::setSmartDailyLimit,
            onDismiss = { showSmartLimitDialog = false },
        )
    }

    if (showSmartResetDialog) {
        FlowAlertDialog(
            onDismissRequest = { showSmartResetDialog = false },
            title = { Text(stringResource(R.string.smart_notify_reset)) },
            text = { Text(stringResource(R.string.smart_notify_reset_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetSmartLearning()
                        showSmartResetDialog = false
                    },
                ) { Text(stringResource(R.string.smart_notify_reset_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showSmartResetDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showIntervalDialog) {
        FlowChoiceDialog(
            title = stringResource(R.string.notif_check_interval_dialog_title),
            description = stringResource(R.string.notif_check_interval_dialog_body),
            options = IntervalOptions.map { (minutes, label) -> FlowChoice(minutes, stringResource(label)) },
            selected = interval,
            onSelect = viewModel::setInterval,
            onDismiss = { showIntervalDialog = false },
        )
    }
}
