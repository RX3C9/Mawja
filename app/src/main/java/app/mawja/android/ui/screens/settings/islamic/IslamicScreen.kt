/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.screens.settings.islamic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationCity
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.android.R
import app.mawja.android.islamic.AdhanMode
import app.mawja.android.islamic.AsrMethod
import app.mawja.android.islamic.CalcMethod
import app.mawja.android.islamic.HighLatitudeRule
import app.mawja.android.islamic.HijriCalendar
import app.mawja.android.islamic.IslamicCities
import app.mawja.android.islamic.IslamicCity
import app.mawja.android.islamic.IslamicSettings
import app.mawja.android.islamic.LocationSource
import app.mawja.android.islamic.MawjaIslamic
import app.mawja.android.islamic.Prayer
import app.mawja.android.islamic.PrayerAlarms
import app.mawja.android.islamic.PrayerDay
import app.mawja.android.islamic.PrayerSchedule
import app.mawja.android.islamic.Qibla
import app.mawja.android.islamic.UpcomingPrayer
import app.mawja.android.ui.components.settings.SettingsDestination
import app.mawja.android.ui.components.settings.SettingsPage
import app.mawja.android.ui.components.settings.SettingsTarget
import app.mawja.android.ui.components.settings.nav
import app.mawja.android.ui.components.settings.notice
import app.mawja.android.ui.components.settings.switch
import app.mawja.android.ui.components.settings.toggleGroup
import app.mawja.android.ui.components.shared.FlowToggleOption
import app.mawja.android.ui.screens.settings.index.DestinationIndex
import app.mawja.android.ui.screens.settings.index.IslamicIndex
import java.time.ZoneId
import java.util.Date
import kotlinx.coroutines.delay

private enum class IslamicDialog { METHOD, HIGH_LAT, CITY, MANUAL, OFFSETS }

/**
 * The optional Islamic section (#147): off by default; when on, today's prayer times with the next
 * one, the qibla, location and calculation choices, pause at adhan and the Hijri date. All of it
 * is calculated on the phone.
 */
@Composable
internal fun IslamicScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    onNavigate: (SettingsTarget) -> Unit,
) {
    val context = LocalContext.current
    val settings by MawjaIslamic.settings.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<IslamicDialog?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(settings.enabled) {
        while (settings.enabled) {
            now = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    val country = remember { MawjaIslamic.countryCode(context) }
    val zone = ZoneId.systemDefault()
    val date = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val today = remember(settings, date) { MawjaIslamic.day(context, date, zone, settings) }
    val tomorrow = remember(settings, date) { MawjaIslamic.day(context, date.plusDays(1), zone, settings) }
    val next = if (today != null && tomorrow != null) PrayerSchedule.next(now, today, tomorrow) else null

    val locationDenied = stringResource(R.string.islamic_location_denied)
    val locationUnavailable = stringResource(R.string.islamic_location_unavailable)
    val locationUpdated = stringResource(R.string.islamic_location_updated)
    fun readLocation() {
        val ok = MawjaIslamic.useDeviceLocation(context)
        Toast.makeText(context, if (ok) locationUpdated else locationUnavailable, Toast.LENGTH_LONG).show()
    }
    val locationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) readLocation() else Toast.makeText(context, locationDenied, Toast.LENGTH_LONG).show()
        }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun setAdhan(mode: AdhanMode) {
        MawjaIslamic.setAdhan(context, mode)
        if (mode != AdhanMode.OFF &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val notificationsBlocked =
        settings.adhan != AdhanMode.OFF &&
            !androidx.core.app.NotificationManagerCompat
                .from(context)
                .areNotificationsEnabled()

    val arabic = LocalConfiguration.current.locales[0].language == "ar"
    val place = settings.place()
    val placeLabel =
        place?.let {
            when (settings.locationSource) {
                LocationSource.CITY -> stringResource(R.string.islamic_place_city, it.city?.name(arabic).orEmpty())
                LocationSource.DEVICE -> stringResource(R.string.islamic_place_device, it.city?.name(arabic).orEmpty())
                LocationSource.MANUAL -> stringResource(R.string.islamic_place_manual, "%.4f".format(it.lat), "%.4f".format(it.lng))
            }
        }
    val hijriMonths = stringArrayResource(R.array.islamic_hijri_months)
    val hijriText =
        if (settings.showHijri) {
            val h = HijriCalendar.fromGregorian(date, settings.hijriAdjust)
            stringResource(R.string.islamic_hijri_format, h.day, hijriMonths[h.month - 1], h.year)
        } else {
            null
        }
    val points = stringArrayResource(R.array.islamic_compass_points)
    val qiblaValue =
        place?.let {
            val bearing = Qibla.bearing(it.lat, it.lng)
            stringResource(
                R.string.islamic_qibla_summary,
                Math.round(bearing).toInt(),
                points[Qibla.compassPoint(bearing)],
                "%,d".format(Math.round(Qibla.distanceKm(it.lat, it.lng))),
            )
        } ?: stringResource(R.string.islamic_qibla_need_place)

    val methodName = stringResource(methodLabel(settings.method(country)))
    val methodValue = if (settings.methodKey == null) stringResource(R.string.islamic_method_auto, methodName) else methodName
    val adhanOptions =
        listOf(
            FlowToggleOption(AdhanMode.OFF, stringResource(R.string.islamic_adhan_off)),
            FlowToggleOption(AdhanMode.NOTIFY, stringResource(R.string.islamic_adhan_notify)),
            FlowToggleOption(AdhanMode.PAUSE, stringResource(R.string.islamic_adhan_pause)),
        )
    val asrOptions =
        listOf(
            FlowToggleOption(AsrMethod.STANDARD, stringResource(R.string.islamic_asr_standard)),
            FlowToggleOption(AsrMethod.HANAFI, stringResource(R.string.islamic_asr_hanafi)),
        )
    val cityValue =
        IslamicCities.byId(settings.cityId)?.name(arabic)?.takeIf { settings.locationSource == LocationSource.CITY }
            ?: stringResource(R.string.islamic_city_none)
    val highLatValue = stringResource(highLatLabel(settings.highLatitude))
    val autoMethodLabel = stringResource(R.string.islamic_method_auto, stringResource(methodLabel(CalcMethod.defaultFor(country))))
    val prayerNames = mutableMapOf<Prayer, String>()
    for (p in Prayer.entries) prayerNames[p] = stringResource(PrayerAlarms.prayerName(p))
    val offsetsValue =
        settings.offsets
            .takeIf { it.isNotEmpty() }
            ?.entries
            ?.joinToString(" · ") { (p, m) -> "${prayerNames[p]} ${signed(m)}" }

    SettingsPage(
        title = stringResource(R.string.islamic_title),
        subtitle = hijriText.takeIf { settings.enabled },
        onBack = onBack,
        highlight = highlight,
    ) {
        group(key = "islamic.main") {
            switch(
                IslamicIndex.enabled,
                settings.enabled,
                { MawjaIslamic.setEnabled(context, it) },
                icon = Icons.Outlined.NightsStay,
            )
        }
        if (!settings.enabled) return@SettingsPage

        header("islamic.today", R.string.islamic_section_today)
        item("islamic.today.card") {
            if (today == null) {
                NoPlaceCard(onChooseCity = { dialog = IslamicDialog.CITY })
            } else {
                PrayerDayCard(day = today, next = next, now = now, placeLabel = placeLabel)
            }
        }
        group(key = "islamic.qibla") {
            nav(
                DestinationIndex.entry(SettingsDestination.QIBLA),
                onClick = { onNavigate(SettingsTarget(SettingsDestination.QIBLA)) },
                value = qiblaValue,
                icon = Icons.Outlined.Explore,
            )
        }
        group(key = "islamic.location", header = R.string.islamic_section_location) {
            nav(
                IslamicIndex.useLocation,
                onClick = {
                    if (MawjaIslamic.hasLocationPermission(context)) readLocation() else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                },
                showChevron = false,
                selected = settings.locationSource == LocationSource.DEVICE && place != null,
                icon = Icons.Outlined.MyLocation,
            )
            nav(
                IslamicIndex.city,
                onClick = { dialog = IslamicDialog.CITY },
                value = cityValue,
                showChevron = false,
                selected = settings.locationSource == LocationSource.CITY && place != null,
                icon = Icons.Outlined.LocationCity,
            )
            nav(
                IslamicIndex.manual,
                onClick = { dialog = IslamicDialog.MANUAL },
                value = placeLabel?.takeIf { settings.locationSource == LocationSource.MANUAL },
                showChevron = false,
                selected = settings.locationSource == LocationSource.MANUAL && place != null,
                icon = Icons.Outlined.Pin,
            )
        }
        group(key = "islamic.calculation", header = R.string.islamic_section_calculation) {
            nav(IslamicIndex.method, onClick = { dialog = IslamicDialog.METHOD }, value = methodValue, showChevron = false)
            toggleGroup(IslamicIndex.asr, asrOptions, settings.asr, { MawjaIslamic.setAsr(context, it) })
            nav(
                IslamicIndex.highLatitude,
                onClick = { dialog = IslamicDialog.HIGH_LAT },
                value = highLatValue,
                showChevron = false,
            )
            nav(IslamicIndex.offsets, onClick = { dialog = IslamicDialog.OFFSETS }, value = offsetsValue, showChevron = false, icon = Icons.Outlined.Tune)
        }
        group(key = "islamic.adhan", header = R.string.islamic_section_adhan) {
            toggleGroup(IslamicIndex.adhan, adhanOptions, settings.adhan, ::setAdhan)
        }
        if (notificationsBlocked) {
            notice("islamic.notifications_blocked", text = { stringResource(R.string.islamic_notifications_blocked) }, isWarning = true)
        }
        group(key = "islamic.hijri", header = R.string.islamic_section_hijri) {
            switch(
                IslamicIndex.showHijri,
                settings.showHijri,
                { MawjaIslamic.setShowHijri(context, it) },
                summary = hijriText,
                icon = Icons.Outlined.CalendarMonth,
            )
            if (settings.showHijri) {
                row(IslamicIndex.hijriAdjust.key) { shape ->
                    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Stepper(
                            title = stringResource(R.string.islamic_hijri_adjust),
                            valueText = stringResource(R.string.islamic_days_value, signed(settings.hijriAdjust)),
                            canDecrease = settings.hijriAdjust > -HijriCalendar.MAX_ADJUST,
                            canIncrease = settings.hijriAdjust < HijriCalendar.MAX_ADJUST,
                            onDecrease = { MawjaIslamic.setHijriAdjust(context, settings.hijriAdjust - 1) },
                            onIncrease = { MawjaIslamic.setHijriAdjust(context, settings.hijriAdjust + 1) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
        notice("islamic.privacy", text = { stringResource(R.string.islamic_enable_summary) }, icon = Icons.Outlined.Info)
    }

    when (dialog) {
        IslamicDialog.METHOD -> {
            val options: List<Pair<CalcMethod?, String>> =
                listOf<Pair<CalcMethod?, String>>(null to autoMethodLabel) +
                    CalcMethod.entries.map { it to stringResource(methodLabel(it)) }
            ChoiceDialog(
                title = stringResource(R.string.islamic_method),
                options = options,
                selected = CalcMethod.fromKey(settings.methodKey),
                onSelect = {
                    MawjaIslamic.setMethod(context, it)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }

        IslamicDialog.HIGH_LAT -> {
            ChoiceDialog(
                title = stringResource(R.string.islamic_high_lat),
                options = HighLatitudeRule.entries.map { it to stringResource(highLatLabel(it)) },
                selected = settings.highLatitude,
                onSelect = {
                    MawjaIslamic.setHighLatitude(context, it)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }

        IslamicDialog.CITY -> {
            CityDialog(
                arabic = arabic,
                selectedId = settings.cityId.takeIf { settings.locationSource == LocationSource.CITY },
                onSelect = {
                    MawjaIslamic.setCity(context, it)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }

        IslamicDialog.MANUAL -> {
            ManualDialog(
                settings = settings,
                onSave = { lat, lng ->
                    MawjaIslamic.setManual(context, lat, lng)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }

        IslamicDialog.OFFSETS -> {
            OffsetsDialog(settings = settings, onDismiss = { dialog = null })
        }

        null -> {
            Unit
        }
    }
}

internal fun methodLabel(method: CalcMethod): Int =
    when (method) {
        CalcMethod.EGYPTIAN -> R.string.islamic_method_egyptian
        CalcMethod.UMM_AL_QURA -> R.string.islamic_method_umm_al_qura
        CalcMethod.MWL -> R.string.islamic_method_mwl
        CalcMethod.ISNA -> R.string.islamic_method_isna
        CalcMethod.KARACHI -> R.string.islamic_method_karachi
    }

private fun highLatLabel(rule: HighLatitudeRule): Int =
    when (rule) {
        HighLatitudeRule.NONE -> R.string.islamic_high_lat_none
        HighLatitudeRule.MIDDLE_OF_NIGHT -> R.string.islamic_high_lat_middle
        HighLatitudeRule.ONE_SEVENTH -> R.string.islamic_high_lat_seventh
        HighLatitudeRule.ANGLE_BASED -> R.string.islamic_high_lat_angle
    }

/** "+2", "-1", "0": a sign makes a direction readable in both languages. */
internal fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()

/** The phone's own time format (12/24 h, digits of the locale). */
@Composable
internal fun rememberTimeFormatter(): (Long) -> String {
    val context = LocalContext.current
    val format = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    return { millis -> format.format(Date(millis)) }
}

@Composable
internal fun countdownText(
    now: Long,
    at: Long,
): String {
    val (h, m) = PrayerSchedule.countdown(now, at)
    return if (h > 0) stringResource(R.string.islamic_countdown_hm, h, m) else stringResource(R.string.islamic_countdown_m, m)
}

@Composable
private fun PrayerDayCard(
    day: PrayerDay,
    next: UpcomingPrayer?,
    now: Long,
    placeLabel: String?,
) {
    val time = rememberTimeFormatter()
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (next != null) {
                val nextText = stringResource(R.string.islamic_next, stringResource(PrayerAlarms.prayerName(next.prayer)), countdownText(now, next.atMillis))
                Text(
                    text = nextText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primary,
                    modifier = Modifier.semantics { heading() },
                )
            }
            placeLabel?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.padding(top = 4.dp))
            Prayer.entries.forEach { prayer ->
                val at = day[prayer] ?: return@forEach
                val isNext = next?.prayer == prayer && next.atMillis == at
                val name = stringResource(PrayerAlarms.prayerName(prayer))
                val clock = time(at)
                val description =
                    if (isNext) stringResource(R.string.islamic_time_next_cd, name, clock) else stringResource(R.string.islamic_time_cd, name, clock)
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    // Not colour alone: the next prayer is also bold and named as next to TalkBack.
                    color = if (isNext) colors.primaryContainer else colors.surfaceContainerHigh,
                    contentColor = if (isNext) colors.onPrimaryContainer else colors.onSurface,
                    modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
                ) {
                    Row(
                        modifier = Modifier.heightIn(min = 44.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
                            color = if (prayer.isPrayer || isNext) androidx.compose.ui.graphics.Color.Unspecified else colors.onSurfaceVariant,
                        )
                        Text(
                            text = clock,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoPlaceCard(onChooseCity: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.islamic_no_place), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onChooseCity) { Text(stringResource(R.string.islamic_choose_city)) }
        }
    }
}

@Composable
private fun Stepper(
    title: String,
    valueText: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    decreaseLabel: String = "−",
    increaseLabel: String = "+",
    decreaseDescription: String? = null,
    increaseDescription: String? = null,
) {
    Row(modifier = modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalIconButton(
            onClick = onDecrease,
            enabled = canDecrease,
            modifier = Modifier.semantics { contentDescription = decreaseDescription ?: "$title $decreaseLabel" },
        ) { Text(decreaseLabel, style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.width(8.dp))
        FilledTonalIconButton(
            onClick = onIncrease,
            enabled = canIncrease,
            modifier = Modifier.semantics { contentDescription = increaseDescription ?: "$title $increaseLabel" },
        ) { Text(increaseLabel, style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                items(options) { (value, label) ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun CityDialog(
    arabic: Boolean,
    selectedId: String?,
    onSelect: (IslamicCity) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(query) { IslamicCities.search(query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.islamic_city)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.islamic_city_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (matches.isEmpty()) {
                    Text(stringResource(R.string.islamic_city_no_match), modifier = Modifier.padding(top = 12.dp))
                }
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(matches, key = { it.id }) { city ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .selectable(selected = city.id == selectedId, role = Role.RadioButton, onClick = { onSelect(city) }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = city.id == selectedId, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(city.name(arabic), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun ManualDialog(
    settings: IslamicSettings,
    onSave: (Double, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val start = settings.place()
    var lat by rememberSaveable { mutableStateOf(start?.lat?.let { "%.4f".format(java.util.Locale.ROOT, it) }.orEmpty()) }
    var lng by rememberSaveable { mutableStateOf(start?.lng?.let { "%.4f".format(java.util.Locale.ROOT, it) }.orEmpty()) }
    val la = IslamicSettings.parseCoordinate(lat)
    val ln = IslamicSettings.parseCoordinate(lng)
    val valid = la != null && ln != null && IslamicSettings.validLat(la) && IslamicSettings.validLng(ln)
    val showError = (lat.isNotBlank() || lng.isNotBlank()) && !valid
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.islamic_manual)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = lat,
                    onValueChange = { lat = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.islamic_latitude)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = showError,
                )
                OutlinedTextField(
                    value = lng,
                    onValueChange = { lng = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.islamic_longitude)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = showError,
                )
                if (showError) {
                    Text(stringResource(R.string.islamic_manual_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (valid) onSave(la!!, ln!!) }, enabled = valid) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun OffsetsDialog(
    settings: IslamicSettings,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.islamic_offsets)) },
        text = {
            LazyColumn {
                items(Prayer.entries.filter { it.isPrayer }) { prayer ->
                    val name = stringResource(PrayerAlarms.prayerName(prayer))
                    val value = settings.offsets[prayer] ?: 0
                    Stepper(
                        title = name,
                        valueText = stringResource(R.string.islamic_offset_value, signed(value)),
                        canDecrease = value > -IslamicSettings.MAX_OFFSET,
                        canIncrease = value < IslamicSettings.MAX_OFFSET,
                        onDecrease = { MawjaIslamic.setOffset(context, prayer, value - 1) },
                        onIncrease = { MawjaIslamic.setOffset(context, prayer, value + 1) },
                        decreaseDescription = stringResource(R.string.islamic_offset_earlier, name),
                        increaseDescription = stringResource(R.string.islamic_offset_later, name),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = {
            TextButton(onClick = {
                Prayer.entries.forEach { MawjaIslamic.setOffset(context, it, 0) }
            }) { Text(stringResource(R.string.islamic_reset)) }
        },
    )
}
