/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.downloader.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.downloader.DownloadEngine
import app.mawja.downloader.DownloadItem
import app.mawja.downloader.DownloadMath
import app.mawja.downloader.DownloadService
import app.mawja.downloader.DownloaderPrefs
import app.mawja.downloader.LinkRules
import app.mawja.downloader.R
import app.mawja.downloader.api.MawjaDownloaderContract.State
import app.mawja.downloader.remote.DownloaderRemoteConfig
import app.mawja.theme.MawjaLookSheet
import app.mawja.theme.MawjaLookTheme
import app.mawja.theme.MawjaSound
import app.mawja.theme.MawjaThemeStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Date

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** A link shared to the app ("Share → Mawja Downloader"), shown in the add sheet. */
    private val sharedLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        DownloaderRemoteConfig.init(this)
        DownloadEngine.init(this)
        handleShare(intent)
        setContent {
            DownloaderTheme {
                RemoteGateHost { notices ->
                    val shared by sharedLink.collectAsStateWithLifecycle()
                    DownloadsScreen(notices, shared, onSharedConsumed = { sharedLink.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        LinkRules.firstUrl(intent.getStringExtra(Intent.EXTRA_TEXT))?.let { sharedLink.value = it }
    }

    override fun onStart() {
        super.onStart()
        // Opening the app reads config/downloader again unless it was read in the last minutes.
        DownloaderRemoteConfig.refreshIfStale(this, DownloaderRemoteConfig.APP_OPEN_MAX_AGE_MS)
    }
}

/** The Downloader wears the same Mawja palettes and sounds as the app (shared-theme). */
@Composable
private fun DownloaderTheme(content: @Composable () -> Unit) = MawjaLookTheme(content)

private enum class Filter { ALL, ACTIVE, DONE }

private enum class Sort { NEWEST, NAME, SIZE }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DownloadsScreen(
    notices: @Composable () -> Unit,
    sharedLink: String?,
    onSharedConsumed: () -> Unit,
) {
    val items by DownloadEngine.items.collectAsStateWithLifecycle()
    val transfers by app.mawja.downloader.RangeFetcher.active.collectAsStateWithLifecycle()
    val transferred by app.mawja.downloader.RangeFetcher.bytesTotal.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showLook by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var addUrl by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Filter.ALL) }
    var sort by remember { mutableStateOf(Sort.NEWEST) }
    var clip by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sharedLink) {
        if (sharedLink != null) {
            addUrl = sharedLink
            onSharedConsumed()
        }
    }
    // Clipboard watch: Android only lets the visible app read it, so this runs while the list is open.
    LaunchedEffect(Unit) {
        delay(600)
        if (DownloaderPrefs.watchClipboard(context)) {
            val cm = context.getSystemService(ClipboardManager::class.java)
            val text = runCatching { cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
            val url = LinkRules.firstUrl(text)
            if (url != null && url != DownloaderPrefs.lastClip(context) && items.none { it.url == url }) clip = url
        }
    }

    if (showLook) MawjaLookSheet(onDismiss = { showLook = false })
    if (showSettings) SettingsSheet(onDismiss = { showSettings = false })
    addUrl?.let { initial -> AddLinkSheet(initial, onDismiss = { addUrl = null }) }

    val shown =
        items
            .filter {
                when (filter) {
                    Filter.ALL -> true
                    Filter.ACTIVE -> it.isActive
                    Filter.DONE -> !it.isActive
                }
            }.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) || it.fileName.contains(query, ignoreCase = true) }
            .let { list ->
                when (sort) {
                    Sort.NEWEST -> list.sortedWith(compareByDescending<DownloadItem> { it.isActive }.thenByDescending { it.createdAt })
                    Sort.NAME -> list.sortedBy { it.title.lowercase() }
                    Sort.SIZE -> list.sortedByDescending { it.total }
                }
            }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                        if (transfers > 0 || transferred > 0) {
                            Text(
                                stringResource(R.string.mawja_transfers_status, transfers, Formatter.formatShortFileSize(context, transferred)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text(
                                stringResource(R.string.subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showLook = true }) {
                        Icon(Icons.Rounded.Palette, contentDescription = stringResource(app.mawja.theme.R.string.mawja_look_open))
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { addUrl = "" },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(stringResource(R.string.action_add)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Box(Modifier.padding(horizontal = 16.dp)) { notices() }
            clip?.let { url ->
                ClipBanner(
                    url = url,
                    onDownload = {
                        DownloaderPrefs.setLastClip(context, url)
                        clip = null
                        addUrl = url
                    },
                    onDismiss = {
                        DownloaderPrefs.setLastClip(context, url)
                        clip = null
                    },
                )
            }
            if (items.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Filter.entries.forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = {
                                Text(
                                    stringResource(
                                        when (f) {
                                            Filter.ALL -> R.string.filter_all
                                            Filter.ACTIVE -> R.string.filter_active
                                            Filter.DONE -> R.string.filter_done
                                        },
                                    ),
                                )
                            },
                        )
                    }
                    SortMenu(sort) { sort = it }
                    if (items.any { it.isActive && !it.paused }) {
                        TextButton(onClick = DownloadEngine::pauseAll) { Text(stringResource(R.string.action_pause_all)) }
                    } else if (items.any { it.isActive && it.paused }) {
                        TextButton(onClick = DownloadEngine::resumeAll) { Text(stringResource(R.string.action_resume_all)) }
                    }
                    if (items.any { !it.isActive }) {
                        TextButton(onClick = DownloadEngine::clearFinished) { Text(stringResource(R.string.action_clear)) }
                    }
                }
            }
            if (items.isEmpty()) {
                EmptyState(Modifier.padding(bottom = padding.calculateBottomPadding()))
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp + padding.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(shown, key = { it.id }) { DownloadRow(it) }
                }
            }
        }
    }
}

@Composable
private fun SortMenu(
    sort: Sort,
    onSort: (Sort) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = false, onClick = { open = true }, label = { Text(stringResource(sortLabel(sort))) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Sort.entries.forEach { s ->
                DropdownMenuItem(text = { Text(stringResource(sortLabel(s))) }, onClick = {
                    onSort(s)
                    open = false
                })
            }
        }
    }
}

private fun sortLabel(s: Sort): Int =
    when (s) {
        Sort.NEWEST -> R.string.sort_newest
        Sort.NAME -> R.string.sort_name
        Sort.SIZE -> R.string.sort_size
    }

@Composable
private fun ClipBanner(
    url: String,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ContentPaste, null)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.clip_found), style = MaterialTheme.typography.titleSmall)
                Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.clip_dismiss)) }
            TextButton(onClick = onDownload) { Text(stringResource(R.string.clip_download)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddLinkSheet(
    initial: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf("") }
    var wifiOnly by remember { mutableStateOf(false) }
    var tonight by remember { mutableStateOf(false) }
    val link = LinkRules.firstUrl(url)
    val youtube = link != null && LinkRules.isYouTube(link)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.add_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.add_url)) },
                isError = url.isNotBlank() && link == null,
                supportingText = { if (url.isNotBlank() && link == null) Text(stringResource(R.string.add_bad_url)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (youtube) {
                Text(stringResource(R.string.add_youtube), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = {
                    openInMawja(context, link!!)
                    onDismiss()
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.add_open_mawja)) }
            } else {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.add_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = wifiOnly, onClick = { wifiOnly = !wifiOnly }, label = { Text(stringResource(R.string.add_wifi)) })
                    FilterChip(selected = tonight, onClick = { tonight = !tonight }, label = { Text(stringResource(R.string.add_tonight)) })
                }
                Button(
                    enabled = link != null,
                    onClick = {
                        val target = link ?: return@Button
                        val fileName = name.trim().ifBlank { LinkRules.fileName(target, null) }
                        DownloadEngine.enqueue(
                            context,
                            DownloadItem(
                                id = "",
                                title = fileName,
                                url = target,
                                fileName = fileName,
                                mimeType = mimeFor(fileName),
                                subfolder = "",
                                headers = emptyMap(),
                                sourceId = target,
                                wifiOnly = wifiOnly,
                                notBefore = if (tonight) DownloadEngine.tonight() else 0,
                            ),
                        )
                        DownloadService.kick(context)
                        MawjaThemeStore.get(context).play(MawjaSound.SUCCESS)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.add_start)) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun mimeFor(fileName: String): String =
    android.webkit.MimeTypeMap
        .getSingleton()
        .getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

/** YouTube links go to Mawja, which extracts the streams and lets the user pick quality and format. */
private fun openInMawja(
    context: Context,
    url: String,
) {
    val packages = listOf("app.mawja.android", "app.mawja.android.debug")
    val pkg = packages.firstOrNull { runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess }
    if (pkg == null) {
        Toast.makeText(context, R.string.mawja_missing, Toast.LENGTH_LONG).show()
        return
    }
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(context, R.string.mawja_missing, Toast.LENGTH_LONG).show() }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var limit by remember { mutableStateOf(DownloaderPrefs.speedLimit(context)) }
    var wifi by remember { mutableStateOf(DownloaderPrefs.wifiOnly(context)) }
    var clip by remember { mutableStateOf(DownloaderPrefs.watchClipboard(context)) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.settings_speed), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DownloadMath.SPEED_LIMITS.forEach { bps ->
                    FilterChip(
                        selected = limit == bps,
                        onClick = {
                            limit = bps
                            DownloadEngine.setSpeedLimit(context, bps)
                        },
                        label = {
                            Text(
                                if (bps == 0L) {
                                    stringResource(R.string.settings_unlimited)
                                } else {
                                    stringResource(R.string.speed_only, Formatter.formatShortFileSize(context, bps))
                                },
                            )
                        },
                    )
                }
            }
            SettingSwitch(stringResource(R.string.settings_wifi), wifi) {
                wifi = it
                DownloadEngine.setWifiOnly(context, it)
            }
            SettingSwitch(stringResource(R.string.settings_clipboard), clip) {
                clip = it
                DownloaderPrefs.setWatchClipboard(context, it)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(88.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Download, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DownloadRow(item: DownloadItem) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier =
            Modifier.fillMaxWidth().clickable(enabled = item.state == State.COMPLETED && item.contentUri != null) {
                openFile(context, item)
            },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (icon, tint) =
                    when {
                        item.state == State.COMPLETED && item.verified != false -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.primary
                        item.state == State.COMPLETED || item.state == State.FAILED || item.state == State.CANCELLED ->
                            Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
                        item.paused -> Icons.Rounded.Pause to MaterialTheme.colorScheme.onSurfaceVariant
                        else -> Icons.Rounded.Download to MaterialTheme.colorScheme.primary
                    }
                Icon(icon, null, tint = tint)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        statusLine(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    item.state == State.RUNNING || (item.state == State.QUEUED && !item.paused) ->
                        IconButton(onClick = { DownloadEngine.pause(item.id) }) { Icon(Icons.Rounded.Pause, stringResource(R.string.action_pause)) }
                    item.state == State.QUEUED && item.paused ->
                        IconButton(onClick = {
                            DownloadEngine.resume(item.id)
                            DownloadService.kick(context)
                        }) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.action_resume)) }
                    item.state == State.FAILED ->
                        IconButton(onClick = {
                            DownloadEngine.retry(item.id)
                            DownloadService.kick(context)
                        }) { Icon(Icons.Rounded.Refresh, stringResource(R.string.action_retry)) }
                }
                if (item.isActive) {
                    IconButton(onClick = { DownloadEngine.cancel(item.id) }) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.action_cancel))
                    }
                }
                RowMenu(item)
            }
            if (item.state == State.RUNNING) {
                Spacer(Modifier.height(10.dp))
                if (item.total > 0) {
                    LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                SpeedGraph(item.id)
            } else if (item.isActive && item.total > 0 && item.downloaded > 0) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
            }
            if (item.state == State.COMPLETED && item.verified == true) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Verified, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.verified), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Live speed sparkline: one sample a second over the last minute. */
@Composable
private fun SpeedGraph(id: String) {
    var samples by remember(id) { mutableStateOf(emptyList<Long>()) }
    LaunchedEffect(id) {
        while (true) {
            samples = DownloadEngine.speedHistory(id)
            delay(1_000)
        }
    }
    if (samples.size < 2) return
    val line = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val label = stringResource(R.string.speed_graph)
    Spacer(Modifier.height(8.dp))
    Canvas(Modifier.fillMaxWidth().height(36.dp).semantics { contentDescription = label }) {
        val max = (samples.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
        val step = size.width / (samples.size - 1)
        val path = Path()
        samples.forEachIndexed { i, v ->
            val x = i * step
            val y = size.height - (v / max) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val area = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, fill)
        drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
        drawCircle(line, 3.dp.toPx(), Offset(size.width, size.height - (samples.last() / max) * size.height))
    }
}

@Composable
private fun RowMenu(item: DownloadItem) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.action_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun entry(
                label: Int,
                action: () -> Unit,
            ): @Composable () -> Unit =
                {
                    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                        open = false
                        action()
                    })
                }
            val entries = mutableListOf<@Composable () -> Unit>()
            if (item.state == State.QUEUED) {
                entries += entry(R.string.action_start_now) {
                    DownloadEngine.startNow(item.id)
                    DownloadService.kick(context)
                }
                entries += entry(R.string.action_move_up) { DownloadEngine.movePriority(item.id, 1) }
                entries += entry(R.string.action_move_down) { DownloadEngine.movePriority(item.id, -1) }
            }
            if (item.state == State.COMPLETED && item.contentUri != null) {
                entries += entry(R.string.action_open) { openFile(context, item) }
                entries += entry(R.string.action_share) { shareFile(context, item) }
                item.sha256?.let { sha ->
                    entries += entry(R.string.action_copy_sha) {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("SHA-256", sha))
                        Toast.makeText(context, R.string.sha_copied, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            entries += entry(R.string.action_remove) { DownloadEngine.remove(item.id) }
            entries.forEach { it() }
        }
    }
}

private fun openFile(
    context: Context,
    item: DownloadItem,
) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(item.contentUri), item.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}

private fun shareFile(
    context: Context,
    item: DownloadItem,
) {
    runCatching {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType(item.mimeType)
                    .putExtra(Intent.EXTRA_STREAM, Uri.parse(item.contentUri))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                null,
            ),
        )
    }
}

@Composable
private fun statusLine(item: DownloadItem): String {
    val context = LocalContext.current
    fun size(v: Long) = Formatter.formatShortFileSize(context, v)
    val parts = mutableListOf<String>()
    when (item.state) {
        State.QUEUED ->
            parts +=
                when {
                    item.paused -> stringResource(R.string.state_paused)
                    item.notBefore > System.currentTimeMillis() ->
                        stringResource(R.string.state_tonight, DateFormat.getTimeFormat(context).format(Date(item.notBefore)))
                    item.wifiOnly || DownloaderPrefs.wifiOnly(context) -> stringResource(R.string.state_waiting_wifi)
                    else -> stringResource(R.string.state_queued)
                }
        State.RUNNING -> {
            val speed = size(item.speedBps)
            parts +=
                if (item.etaSeconds > 0) {
                    stringResource(R.string.speed_eta, speed, android.text.format.DateUtils.formatElapsedTime(item.etaSeconds))
                } else {
                    stringResource(R.string.speed_only, speed)
                }
            if (item.segments.size > 1) parts += stringResource(R.string.parts, item.segments.size)
        }
        State.COMPLETED -> {
            parts += stringResource(R.string.state_completed)
            if (item.verified == false) parts += stringResource(R.string.not_verified)
        }
        State.FAILED -> parts += listOfNotNull(stringResource(R.string.state_failed), item.error)
        State.CANCELLED -> parts += stringResource(R.string.state_cancelled)
    }
    if (item.isActive || item.state == State.COMPLETED) {
        parts += if (item.total > 0 && item.state != State.COMPLETED) "${size(item.downloaded)} / ${size(item.total)}" else size(item.downloaded.coerceAtLeast(0))
    }
    return parts.joinToString(" · ")
}
