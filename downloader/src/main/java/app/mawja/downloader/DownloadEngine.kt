/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.downloader

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.mawja.downloader.api.MawjaDownloaderContract.State
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

data class DownloadItem(
    val id: String,
    val title: String,
    val url: String,
    val fileName: String,
    val mimeType: String,
    val subfolder: String,
    val headers: Map<String, String>,
    val sourceId: String?,
    val state: State = State.QUEUED,
    val downloaded: Long = 0,
    val total: Long = -1,
    val contentUri: String? = null,
    val error: String? = null,
    /** Paused by the user: stays QUEUED for clients, never picked until resumed. */
    val paused: Boolean = false,
    val priority: Int = 0,
    val wifiOnly: Boolean = false,
    /** Epoch ms before which it must not start ("download tonight"); 0 = now. */
    val notBefore: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
    val segments: List<Segment> = emptyList(),
    val speedBps: Long = 0,
    val etaSeconds: Long = -1,
    val sha256: String? = null,
    /** Size check after the download: true when the file has exactly the expected bytes. */
    val verified: Boolean? = null,
    /** Extra servers holding the same file; parts are pulled from all of them at once. */
    val mirrors: List<String> = emptyList(),
    /** Working servers (the link plus its mirrors), fastest first, measured before the start. */
    val sources: List<String> = emptyList(),
    /** Expected checksum ("sha256:…", "md5:…" or bare hex) checked after the download. */
    val checksum: String? = null,
    /** True / false once [checksum] was checked; null when there was none to check. */
    val checksumOk: Boolean? = null,
    /** Validators of the server copy, sent as If-Range so a changed file is never mixed with the old one. */
    val etag: String? = null,
    val lastModified: String? = null,
    /** Connections asked for this download; 0 = the setting. */
    val connections: Int = 0,
    /** The name came from the link, so the server's Content-Disposition name may replace it. */
    val autoName: Boolean = false,
    /** "Start now": ignores Wi-Fi-only, charging, battery, night window and the daily cap. */
    val ignoreRules: Boolean = false,
    /** FileSafety.Verdict name after the download (OK, APP, EXECUTABLE, DISGUISED). */
    val safety: String? = null,
) {
    val progress: Float get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    val isActive: Boolean get() = state == State.QUEUED || state == State.RUNNING
    val category: FileCategory get() = SmartNaming.category(fileName, mimeType)
}

/** Event sink so the binder service can forward changes to its clients. */
interface DownloadListener {
    fun onChanged(item: DownloadItem)
}

/**
 * Process-wide download engine («مدّ»). Every file is split into up to 8 ranges (32 for Royal)
 * fetched in parallel into a private part file; a connection that finishes early steals half of the
 * slowest part, and mirrors are pulled from at the same time, fastest server first. Pause and resume
 * survive a closed app or a reboot ([DownloadWork] picks unfinished work up again), each part retries
 * with jittered backoff (honouring Retry-After), If-Range keeps a changed server file from being mixed
 * with the old bytes, a shared token bucket limits speed, and the queue obeys priority, a parallel
 * limit and the smart rules (Wi-Fi only, charging, battery floor, night window, daily cap). At the
 * end the size, SHA-256 and any expected checksum are checked, the file is sniffed for programs in
 * disguise, named cleanly and sorted into its category folder under Downloads/Mawja.
 */
object DownloadEngine {
    private const val TICK_MS = 500L
    private const val PERSIST_EVERY_MS = 3_000L
    private const val BUFFER_SIZE = 64 * 1024
    private const val HOURLY_SAMPLE_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val pausing = ConcurrentHashMap.newKeySet<String>()
    private val meters = ConcurrentHashMap<String, SpeedMeter>()
    private val listeners = mutableSetOf<DownloadListener>()
    private val limiter = SpeedLimiter()

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private val _waitReason = MutableStateFlow(ScheduleRules.Verdict.OK)

    /** Why the queue is holding right now (OK when it may run). */
    val waitReason: StateFlow<ScheduleRules.Verdict> = _waitReason.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastPersist = 0L

    /** Bytes counted since the ledger was last written (folded in by [persist]). */
    private val unsavedBytes = AtomicLong(0)

    @Volatile
    private var ledger = ScheduleRules.Ledger(0, 0)

    @Volatile
    private var hourly = HourlySpeed()

    @Volatile
    private var lastHourlySample = 0L

    private val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Loads saved downloads once and starts the queue ticker. Safe to call from anywhere. */
    fun init(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext != null) return
            val ctx = context.applicationContext
            appContext = ctx
            limiter.limitBps = DownloaderPrefs.speedLimit(ctx)
            ledger = DownloaderPrefs.ledger(ctx)
            hourly = HourlySpeed.decode(DownloaderPrefs.hourlySpeed(ctx))
            val saved = DownloadStore.load(ctx).map { if (it.state == State.RUNNING) it.copy(state = State.QUEUED, speedBps = 0) else it }
            _items.value = saved
            scope.launch {
                while (isActive) {
                    enforceRules()
                    pump()
                    delay(10_000)
                }
            }
        }
    }

    fun addListener(listener: DownloadListener) = synchronized(listeners) { listeners += listener }

    fun removeListener(listener: DownloadListener) = synchronized(listeners) { listeners -= listener }

    /** Transfers running now (what keeps the foreground notification up). */
    fun activeCount(): Int = jobs.size

    /** Unfinished items that are neither paused nor running: [DownloadWork] keeps them alive. */
    fun hasPendingWork(): Boolean = _items.value.any { it.state == State.QUEUED && !it.paused } || jobs.isNotEmpty()

    /** Earliest "tonight" start among waiting items, or 0. */
    fun nextScheduledAt(): Long =
        _items.value
            .filter { it.state == State.QUEUED && !it.paused && it.notBefore > System.currentTimeMillis() }
            .minOfOrNull { it.notBefore } ?: 0L

    fun needsOnlyUnmetered(): Boolean {
        val waiting = _items.value.filter { it.state == State.QUEUED && !it.paused }
        val ctx = appContext
        return waiting.isNotEmpty() && waiting.none { it.ignoreRules } && (waiting.all { it.wifiOnly } || (ctx != null && DownloaderPrefs.wifiOnly(ctx)))
    }

    /** True when the queue may only run on a charger (WorkManager then waits for one). */
    fun needsCharging(): Boolean {
        val ctx = appContext ?: return false
        return DownloaderPrefs.rules(ctx).chargingOnly && _items.value.none { it.isActive && it.ignoreRules }
    }

    /** How long until the smart rules might let the queue run again (0 = now or network-driven). */
    fun rulesRecheckInMs(): Long {
        val ctx = appContext ?: return 0
        val rules = DownloaderPrefs.rules(ctx)
        val now = conditions(ctx)
        return ScheduleRules.recheckInMs(ScheduleRules.evaluate(rules, now), rules, now.minuteOfDay)
    }

    /** Bytes downloaded today (for the daily cap line in settings). */
    fun bytesToday(): Long = ledger.bytesOn(dayKey()) + unsavedBytes.get()

    fun enqueue(
        context: Context,
        item: DownloadItem,
    ): String {
        init(context)
        // Same source already queued or running: hand back the existing id instead of downloading twice.
        item.sourceId?.let { source ->
            _items.value.firstOrNull { it.sourceId == source && it.isActive }?.let { return it.id }
        }
        val name = SmartNaming.clean(SmartNaming.withExtension(item.fileName.ifBlank { LinkRules.fileName(item.url, null) }, item.mimeType))
        val queued =
            item.copy(
                id = item.id.ifBlank { UUID.randomUUID().toString() },
                fileName = name,
                title = if (item.autoName) name else item.title.ifBlank { name },
                mimeType = item.mimeType.takeUnless { it.isBlank() || it == OCTET } ?: SmartNaming.mimeFor(name),
                mirrors = item.mirrors.filter { it != item.url && (it.startsWith("https://") || it.startsWith("http://")) }.distinct(),
                checksum = ExpectedChecksum.parse(item.checksum)?.encode(),
                state = State.QUEUED,
                createdAt = System.currentTimeMillis(),
            )
        _items.update { listOf(queued) + it }
        emit(queued)
        persist(force = true)
        pump()
        DownloadWork.schedule(context)
        return queued.id
    }

    /** What a new link would duplicate (queued, same link, same file or same content), or null. */
    fun findDuplicate(
        url: String,
        fileName: String? = null,
        sourceId: String? = null,
    ): DuplicateFinder.Match? = DuplicateFinder.find(_items.value.map { it.toRecord() }, url, sourceId, fileName)

    fun pause(id: String) {
        pausing += id
        jobs.remove(id)?.cancel()
        mutate(id) { if (it.isActive) it.copy(state = State.QUEUED, paused = true, speedBps = 0, etaSeconds = -1) else it }
        persist(force = true)
        pump()
    }

    fun resume(id: String) {
        mutate(id) { if (it.isActive) it.copy(paused = false, notBefore = 0) else it }
        persist(force = true)
        pump()
        appContext?.let(DownloadWork::schedule)
    }

    fun pauseAll() = _items.value.filter { it.isActive && !it.paused }.forEach { pause(it.id) }

    fun resumeAll() = _items.value.filter { it.isActive && it.paused }.forEach { resume(it.id) }

    /** Retries a failed download, keeping every byte already fetched. */
    fun retry(id: String) {
        mutate(id) { if (it.state == State.FAILED) it.copy(state = State.QUEUED, error = null, paused = false) else it }
        persist(force = true)
        pump()
        appContext?.let(DownloadWork::schedule)
    }

    /** Starts it now: first in the queue, ignoring Wi-Fi-only, "tonight" and the smart rules. */
    fun startNow(id: String) {
        val top = (_items.value.maxOfOrNull { it.priority } ?: 0) + 1
        mutate(id) { it.copy(priority = top, notBefore = 0, wifiOnly = false, paused = false, ignoreRules = true) }
        persist(force = true)
        pump()
    }

    fun movePriority(
        id: String,
        delta: Int,
    ) {
        mutate(id) { it.copy(priority = it.priority + delta) }
        persist(force = true)
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        mutate(id) { if (it.isActive) it.copy(state = State.CANCELLED, speedBps = 0, paused = false) else it }
        appContext?.let { partFile(it, id).delete() }
        persist(force = true)
        pump()
    }

    fun remove(id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (item.isActive) cancel(id)
        _items.update { list -> list.filterNot { it.id == id } }
        persist(force = true)
    }

    fun clearFinished() {
        _items.update { list -> list.filter { it.isActive } }
        persist(force = true)
    }

    fun setSpeedLimit(
        context: Context,
        bps: Long,
    ) {
        DownloaderPrefs.setSpeedLimit(context, bps)
        limiter.limitBps = bps
    }

    fun setWifiOnly(
        context: Context,
        on: Boolean,
    ) {
        DownloaderPrefs.setWifiOnly(context, on)
        rulesChanged(context)
    }

    /** Settings changed a rule: hold or release work now instead of at the next tick. */
    fun rulesChanged(context: Context) {
        init(context)
        enforceRules()
        pump()
        DownloadWork.schedule(context)
    }

    /** Speed samples of the last minute, one per second, for the live graph. */
    fun speedHistory(id: String): List<Long> = meters[id]?.history().orEmpty()

    fun tonight(): Long = QueuePolicy.tonight(Calendar.getInstance())

    /** When a waiting download should be done, from this network's speed at this hour; -1 unknown. */
    fun learnedEtaSeconds(item: DownloadItem): Long {
        if (item.total <= 0) return -1
        return hourly.eta(item.total - item.downloaded, Calendar.getInstance().get(Calendar.HOUR_OF_DAY), 0)
    }

    /**
     * Unpacks a finished .zip into Downloads/Mawja/<its folder>/<archive name>/. Entries that would
     * escape that folder ("zip slip") are skipped; very large or crowded archives stop with an error.
     * Returns how many files were written.
     */
    suspend fun unzip(
        context: Context,
        id: String,
    ): Int =
        withContext(Dispatchers.IO) {
            val item = _items.value.firstOrNull { it.id == id && it.state == State.COMPLETED } ?: error("Not a finished download")
            val uri = Uri.parse(item.contentUri ?: error("No file"))
            val base = SmartNaming.clean(item.fileName.substringBeforeLast('.'), fallback = "archive")
            val root = listOf(item.subfolder, base).filter { it.isNotBlank() }.joinToString("/")
            var count = 0
            var bytes = 0L
            val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open the file")
            ZipInputStream(input.buffered()).use { zip ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    ensureActive()
                    val entry = zip.nextEntry ?: break
                    val path = if (entry.isDirectory) null else ZipPaths.safe(entry.name)
                    if (path != null) {
                        if (++count > MAX_UNZIP_ENTRIES) throw IOException("Too many files in the archive")
                        val name = path.substringAfterLast('/')
                        val dir = path.substringBeforeLast('/', "")
                        val target =
                            Target.create(
                                context,
                                DownloadItem(
                                    id = "unzip",
                                    title = name,
                                    url = "",
                                    fileName = name,
                                    mimeType = SmartNaming.mimeFor(name),
                                    subfolder = listOf(root, dir).filter { it.isNotBlank() }.joinToString("/"),
                                    headers = emptyMap(),
                                    sourceId = null,
                                ),
                            )
                        try {
                            target.stream.use { out ->
                                while (true) {
                                    val read = zip.read(buffer)
                                    if (read < 0) break
                                    bytes += read
                                    if (bytes > MAX_UNZIP_BYTES) throw IOException("The archive unpacks to too much data")
                                    out.write(buffer, 0, read)
                                }
                            }
                            target.publish(context)
                        } catch (t: Throwable) {
                            target.discard(context)
                            throw t
                        }
                    }
                }
            }
            count
        }

    /** Starts whatever the queue allows now. */
    @Synchronized
    fun pump() {
        val ctx = appContext ?: return
        val slots = ConnectionPolicy.parallel(DownloaderPrefs.parallel(ctx), DownloaderPrefs.royal(ctx)) - jobs.size
        val now = conditions(ctx)
        val verdict = ScheduleRules.evaluate(DownloaderPrefs.rules(ctx), now)
        _waitReason.value = verdict
        if (slots <= 0) return
        val candidates =
            _items.value
                .filter { it.state == State.QUEUED && !jobs.containsKey(it.id) && (verdict == ScheduleRules.Verdict.OK || it.ignoreRules) }
                .map { QueuePolicy.Candidate(it.id, it.priority, it.createdAt, it.wifiOnly && !it.ignoreRules, it.notBefore, it.paused) }
        val picked = QueuePolicy.pick(candidates, slots, System.currentTimeMillis(), now.unmetered, globalWifiOnly = false)
        picked.forEach { id ->
            pausing -= id
            jobs[id] =
                scope.launch {
                    try {
                        run(ctx, id)
                    } finally {
                        jobs.remove(id)
                        meters.remove(id)
                        pump()
                    }
                }
        }
    }

    /** Puts running downloads back in the queue when a rule stops them (Wi-Fi lost, cap reached …). */
    private fun enforceRules() {
        val ctx = appContext ?: return
        val now = conditions(ctx)
        val rules = DownloaderPrefs.rules(ctx)
        _items.value
            .filter { it.state == State.RUNNING && !it.ignoreRules && jobs.containsKey(it.id) }
            .filter { ScheduleRules.evaluate(rules, now, it.wifiOnly) != ScheduleRules.Verdict.OK }
            .forEach { jobs.remove(it.id)?.cancel() }
    }

    private fun conditions(context: Context): ScheduleRules.Conditions {
        val battery = context.getSystemService(BatteryManager::class.java)
        val percent = runCatching { battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1 }.getOrDefault(-1)
        val charging = runCatching { battery?.isCharging ?: false }.getOrDefault(false)
        val cal = Calendar.getInstance()
        return ScheduleRules.Conditions(
            unmetered = isUnmetered(context),
            charging = charging,
            batteryPercent = if (percent in 0..100) percent else -1,
            minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE),
            bytesToday = bytesToday(),
        )
    }

    private fun dayKey(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 10_000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }

    private fun isUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private suspend fun run(
        context: Context,
        id: String,
    ) {
        var item = _items.value.firstOrNull { it.id == id } ?: return
        if (item.state != State.QUEUED) return
        mutate(id) { it.copy(state = State.RUNNING, error = null) }
        val meter = SpeedMeter().also { meters[id] = it }
        val part = partFile(context, id)
        try {
            var restarted = false
            while (true) {
                try {
                    // No size yet, or the part file is gone (cleared storage): saved progress cannot be
                    // trusted, so probe again and plan from byte 0 instead of resuming into a blank file.
                    if (item.total <= 0 || !part.exists()) {
                        part.delete()
                        item = prepare(context, item.copy(segments = emptyList(), downloaded = 0))
                    }
                    part.parentFile?.mkdirs()
                    if (item.segments.isNotEmpty()) {
                        fetchSegmented(context, id, item, part, meter)
                    } else {
                        fetchSingle(id, item, part, meter)
                    }
                    break
                } catch (e: RemoteFileChanged) {
                    // The server copy changed under us: the old bytes are worthless, start over once.
                    if (restarted) throw e
                    restarted = true
                    part.delete()
                    item = item.copy(total = -1, segments = emptyList(), downloaded = 0, etag = null, lastModified = null)
                    mutate(id) { it.copy(total = -1, segments = emptyList(), downloaded = 0, etag = null, lastModified = null) }
                }
            }
            finish(context, id, part)
        } catch (t: Throwable) {
            val wasPaused = id in pausing
            val current = _items.value.firstOrNull { it.id == id }
            when {
                wasPaused || current?.paused == true -> Unit
                current?.state == State.CANCELLED -> part.delete()
                t is CancellationException -> mutate(id) { it.copy(state = State.QUEUED, speedBps = 0, etaSeconds = -1) }
                else -> mutate(id) { it.copy(state = State.FAILED, speedBps = 0, etaSeconds = -1, error = describe(t)) }
            }
            persist(force = true)
            if (t is CancellationException) throw t
        }
    }

    private fun describe(t: Throwable): String =
        when {
            t is HttpFailure && t.outcome == RetryPolicy.Outcome.EXPIRED -> "Link expired or access denied (HTTP ${t.code})"
            t is RemoteFileChanged -> "The file on the server keeps changing"
            (t.message ?: "").let { it.contains("ENOSPC") || it.contains("No space left") } -> "Not enough storage"
            else -> t.message ?: t.javaClass.simpleName
        }

    private data class Probe(
        val code: Int,
        val total: Long,
        val ranges: Boolean,
        val etag: String?,
        val lastModified: String?,
        val disposition: String?,
        val contentType: String?,
        val latencyMs: Long,
    )

    /** Asks for the first byte: a 206 with a Content-Range means the server can serve parts. */
    private fun probe(
        item: DownloadItem,
        url: String,
    ): Probe {
        val started = System.nanoTime()
        val request = requestFor(item, url).header("Range", "bytes=0-0").build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw HttpFailure(r.code, RetryPolicy.parseRetryAfter(r.header("Retry-After")))
            val latency = (System.nanoTime() - started) / 1_000_000
            val etag = r.header("ETag")?.takeUnless { it.startsWith("W/") }
            val modified = r.header("Last-Modified")
            val disposition = r.header("Content-Disposition")
            val type = r.header("Content-Type")
            if (r.code == 206) {
                val total = RangeMath.parseContentRange(r.header("Content-Range"))?.total ?: -1L
                return Probe(r.code, total, total > 0, etag, modified, disposition, type, latency)
            }
            return Probe(r.code, r.body.contentLength(), false, etag, modified, disposition, type, latency)
        }
    }

    /** Probes with retries (and a resolver refresh for an expired link), then plans names, servers and parts. */
    private suspend fun prepare(
        context: Context,
        start: DownloadItem,
    ): DownloadItem {
        var item = start
        var attempt = 0
        var refreshed = false
        var answer: Probe? = null
        while (answer == null) {
            currentCoroutineContext().ensureActive()
            try {
                answer = probe(item, item.url)
            } catch (e: HttpFailure) {
                when (e.outcome) {
                    RetryPolicy.Outcome.FATAL -> throw e
                    RetryPolicy.Outcome.EXPIRED -> {
                        // One fresh link from a resolver; a second refusal means access is really denied.
                        if (refreshed) throw e
                        val fresh = LinkResolvers.refresh(item)
                        if (fresh == null || fresh == item.url) throw e
                        refreshed = true
                        item = item.copy(url = fresh)
                        continue
                    }
                    RetryPolicy.Outcome.RETRY -> Unit
                }
                if (++attempt > PROBE_RETRIES) throw e
                delay(RetryPolicy.delayMs(attempt - 1, e.retryAfterMs))
            } catch (e: IOException) {
                if (++attempt > PROBE_RETRIES) throw e
                delay(RetryPolicy.delayMs(attempt - 1))
            }
        }
        val first = answer ?: error("no probe answer")
        // Mirrors: keep the ones that serve the same bytes, fastest first.
        val sources =
            if (first.ranges && item.mirrors.isNotEmpty()) {
                val timed = mutableListOf(item.url to first.latencyMs)
                item.mirrors.forEach { mirror ->
                    runCatching { probe(item, mirror) }.getOrNull()?.let { m ->
                        if (m.ranges && m.total == first.total) timed += mirror to m.latencyMs
                    }
                }
                timed.sortedBy { it.second }.map { it.first }
            } else {
                emptyList()
            }
        var name = item.fileName
        var title = item.title
        var mime = item.mimeType
        val serverType = first.contentType?.substringBefore(';')?.trim()?.takeIf { it.contains('/') && it != OCTET }
        if (item.autoName) {
            val fromServer = first.disposition?.let { LinkRules.fileName(item.url, it) }
            if (fromServer != null && fromServer != "download") {
                name = SmartNaming.clean(SmartNaming.withExtension(fromServer, serverType))
                if (title == item.fileName) title = name
            }
        }
        if ((mime.isBlank() || mime == OCTET) && serverType != null) mime = serverType
        name = SmartNaming.clean(SmartNaming.withExtension(name, mime))
        val connections = ConnectionPolicy.connections(item.connections.takeIf { it > 0 } ?: DownloaderPrefs.connections(context), DownloaderPrefs.royal(context))
        val segments = if (first.ranges && first.total > 0) RangeMath.plan(first.total, connections) else emptyList()
        val planned =
            item.copy(
                fileName = name,
                title = title,
                mimeType = mime,
                total = first.total,
                segments = segments,
                sources = sources,
                etag = first.etag,
                lastModified = first.lastModified,
            )
        mutate(item.id) {
            it.copy(
                url = planned.url,
                fileName = name,
                title = title,
                mimeType = mime,
                total = first.total,
                downloaded = 0,
                segments = segments,
                sources = sources,
                etag = first.etag,
                lastModified = first.lastModified,
            )
        }
        return planned
    }

    private fun requestFor(
        item: DownloadItem,
        url: String = item.url,
    ): Request.Builder =
        Request
            .Builder()
            .url(url)
            .apply { item.headers.forEach { (k, v) -> if (k.lowercase() !in MANAGED_HEADERS) header(k, v) } }

    /** If-Range value: a strong ETag, else Last-Modified; null when the server gave neither. */
    private fun validator(item: DownloadItem): String? = item.etag ?: item.lastModified

    private suspend fun fetchSegmented(
        context: Context,
        id: String,
        item: DownloadItem,
        part: File,
        meter: SpeedMeter,
    ) {
        // inFlight == BUFFER_SIZE: a worker reads at most one buffer before it writes and advances,
        // so a steal never cuts below bytes already read (see fetchPart).
        val board = SegmentBoard(item.segments, minSteal = STEAL_MIN_BYTES, inFlight = BUFFER_SIZE.toLong())
        val urls = AtomicReference(item.sources.ifEmpty { listOf(item.url) + item.mirrors }.distinct())
        val workers =
            ConnectionPolicy.connections(item.connections.takeIf { it > 0 } ?: DownloaderPrefs.connections(context), DownloaderPrefs.royal(context))
        RandomAccessFile(part, "rw").use { raf ->
            if (raf.length() != item.total) raf.setLength(item.total)
            val channel = raf.channel
            coroutineScope {
                val reporter = launch { report(id, item.total, meter) { board.snapshot() } }
                (0 until workers)
                    .map { w -> async { worker(w, item, urls, board, channel, meter) } }
                    .awaitAll()
                reporter.cancel()
            }
            channel.force(false)
        }
        if (!board.isComplete()) throw IOException("Download stopped before the end")
        mutate(id) { it.copy(segments = board.snapshot(), downloaded = item.total) }
    }

    /** One connection: works through unclaimed parts, then steals half of the slowest one. */
    private suspend fun worker(
        worker: Int,
        item: DownloadItem,
        urls: AtomicReference<List<String>>,
        board: SegmentBoard,
        channel: FileChannel,
        meter: SpeedMeter,
    ) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val index = board.claim() ?: board.steal() ?: return
            try {
                fetchPart(worker, item, urls, board, index, channel, meter)
            } finally {
                board.release(index)
            }
        }
    }

    /** One part with its own retries; a retry continues from the last byte written, on the next server. */
    private suspend fun fetchPart(
        worker: Int,
        item: DownloadItem,
        urls: AtomicReference<List<String>>,
        board: SegmentBoard,
        index: Int,
        channel: FileChannel,
        meter: SpeedMeter,
    ) {
        var attempt = 0
        // Never larger than the board's inFlight guard (BUFFER_SIZE), or a steal could cut into a read.
        val buffer = ByteArray(BUFFER_SIZE)
        while (board.remaining(index) > 0) {
            currentCoroutineContext().ensureActive()
            val seg = board.part(index)
            val from = seg.next
            val servers = urls.get()
            val url = servers[(worker + attempt) % servers.size]
            try {
                val request =
                    requestFor(item, url)
                        .header("Range", RangeMath.header(seg))
                        .apply { validator(item)?.let { header("If-Range", it) } }
                        .build()
                client.newCall(request).execute().use { r ->
                    // A full 200 answer to a ranged request with If-Range means the file changed.
                    if (r.code == 200) throw RemoteFileChanged()
                    if (r.code != 206) throw HttpFailure(r.code, RetryPolicy.parseRetryAfter(r.header("Retry-After")))
                    val range = RangeMath.parseContentRange(r.header("Content-Range"))
                    if (!RangeMath.answers(range, from, item.total)) {
                        if (range != null && range.total > 0 && range.total != item.total) throw RemoteFileChanged()
                        throw IOException("Server sent the wrong part")
                    }
                    r.body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val left = board.remaining(index)
                            if (left <= 0) break
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                            if (read < 0) break
                            limiter.reserve(read).takeIf { it > 0 }?.let { delay(it) }
                            val bytes = ByteBuffer.wrap(buffer, 0, read)
                            var at = board.part(index).next
                            while (bytes.hasRemaining()) at += channel.write(bytes, at)
                            val counted = board.advance(index, read.toLong())
                            meter.add(counted)
                            countBytes(counted)
                            attempt = 0
                        }
                    }
                }
            } catch (e: RemoteFileChanged) {
                throw e
            } catch (e: HttpFailure) {
                when (e.outcome) {
                    RetryPolicy.Outcome.FATAL -> throw e
                    RetryPolicy.Outcome.EXPIRED -> {
                        if (servers.size > 1) {
                            // Drop the refusing server; the others serve the same bytes.
                            urls.updateAndGet { list -> list.filter { it != url }.ifEmpty { list } }
                        } else {
                            // The only link expired: ask its resolver for a fresh one and continue from the same byte.
                            // compareAndSet: when another part already swapped the link, keep that one.
                            val fresh = LinkResolvers.refresh(item.copy(url = url)) ?: throw e
                            if (urls.compareAndSet(servers, listOf(fresh))) mutate(item.id) { it.copy(url = fresh) }
                        }
                    }
                    RetryPolicy.Outcome.RETRY -> Unit
                }
                if (++attempt > RetryPolicy.MAX_ATTEMPTS) throw e
                delay(RetryPolicy.delayMs(attempt - 1, e.retryAfterMs))
            } catch (e: IOException) {
                if (++attempt > RetryPolicy.MAX_ATTEMPTS) throw e
                delay(RetryPolicy.delayMs(attempt - 1))
            }
        }
    }

    /** Servers without range support: one stream; a retry starts over. */
    private suspend fun fetchSingle(
        id: String,
        item: DownloadItem,
        part: File,
        meter: SpeedMeter,
    ) {
        var attempt = 0
        val servers = (listOf(item.url) + item.mirrors).distinct()
        while (true) {
            currentCoroutineContext().ensureActive()
            var written = 0L
            try {
                coroutineScope {
                    val reporter = launch { report(id, item.total, meter, single = { written }) { emptyList() } }
                    client.newCall(requestFor(item, servers[attempt % servers.size]).build()).execute().use { r ->
                        if (!r.isSuccessful) throw HttpFailure(r.code, RetryPolicy.parseRetryAfter(r.header("Retry-After")))
                        part.outputStream().use { out ->
                            r.body.byteStream().use { input ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    limiter.reserve(read).takeIf { it > 0 }?.let { delay(it) }
                                    out.write(buffer, 0, read)
                                    written += read
                                    meter.add(read.toLong())
                                    countBytes(read.toLong())
                                }
                            }
                        }
                    }
                    reporter.cancel()
                }
                if (item.total > 0 && written != item.total) throw IOException("Short download: $written of ${item.total}")
                mutate(id) { it.copy(downloaded = written, total = if (item.total > 0) item.total else written) }
                return
            } catch (e: HttpFailure) {
                if (e.outcome != RetryPolicy.Outcome.RETRY && servers.size == 1) throw e
                if (++attempt > RetryPolicy.MAX_ATTEMPTS) throw e
                delay(RetryPolicy.delayMs(attempt - 1, e.retryAfterMs))
            } catch (e: IOException) {
                if (++attempt > RetryPolicy.MAX_ATTEMPTS) throw e
                delay(RetryPolicy.delayMs(attempt - 1))
            }
        }
    }

    private fun countBytes(bytes: Long) {
        if (bytes > 0) unsavedBytes.addAndGet(bytes)
    }

    private suspend fun report(
        id: String,
        total: Long,
        meter: SpeedMeter,
        single: (() -> Long)? = null,
        segments: () -> List<Segment>,
    ) {
        while (currentCoroutineContext().isActive) {
            delay(TICK_MS)
            val segs = segments()
            val got = single?.invoke() ?: DownloadMath.downloaded(segs)
            val bps = meter.bytesPerSecond()
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val now = System.currentTimeMillis()
            if (bps > 0 && now - lastHourlySample >= HOURLY_SAMPLE_MS) {
                lastHourlySample = now
                hourly.record(hour, bps)
            }
            mutate(id) {
                it.copy(
                    downloaded = got,
                    segments = if (single == null) segs else it.segments,
                    speedBps = bps,
                    etaSeconds = if (total > 0) hourly.eta(total - got, hour, bps) else -1,
                )
            }
            persist(force = false)
        }
    }

    /**
     * Checks the part file (expected checksum, what its bytes really are), then copies it into
     * Downloads/Mawja/<category> with a SHA-256 and a size check, and publishes it.
     */
    private fun finish(
        context: Context,
        id: String,
        part: File,
    ) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        val expected = ExpectedChecksum.parse(item.checksum)
        val kind = sniff(part)
        val safety = FileSafety.verdict(item.fileName, kind)
        val folder = item.subfolder.ifBlank { if (DownloaderPrefs.autoSort(context)) item.category.folder else "" }
        val sha = MessageDigest.getInstance("SHA-256")
        val other = expected?.takeIf { it.algorithm != ExpectedChecksum.Algorithm.SHA256 }?.let { MessageDigest.getInstance(it.algorithm.javaName) }
        var copied = 0L
        val target = Target.create(context, item.copy(subfolder = folder))
        try {
            target.stream.use { out ->
                part.inputStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        sha.update(buffer, 0, read)
                        other?.update(buffer, 0, read)
                        copied += read
                    }
                }
            }
            val shaHex = hex(sha.digest())
            val checksumOk = expected?.matches(if (other != null) hex(other.digest()) else shaHex)
            if (checksumOk == false) {
                // Corrupt or a different file: never publish it; a retry downloads from scratch.
                target.discard(context)
                part.delete()
                mutate(id) {
                    it.copy(
                        state = State.FAILED,
                        error = "Checksum mismatch: the file is damaged or not the expected one",
                        checksumOk = false,
                        sha256 = shaHex,
                        segments = emptyList(),
                        downloaded = 0,
                        total = -1,
                        speedBps = 0,
                        etaSeconds = -1,
                    )
                }
                persist(force = true)
                return
            }
            target.publish(context)
            part.delete()
            val expectedSize = item.total
            mutate(id) {
                it.copy(
                    state = State.COMPLETED,
                    contentUri = target.uri.toString(),
                    fileName = target.displayName,
                    subfolder = folder,
                    downloaded = copied,
                    total = if (expectedSize > 0) expectedSize else copied,
                    speedBps = 0,
                    etaSeconds = 0,
                    segments = emptyList(),
                    sha256 = shaHex,
                    checksumOk = checksumOk,
                    verified = expectedSize <= 0 || copied == expectedSize,
                    safety = safety.name,
                    finishedAt = System.currentTimeMillis(),
                )
            }
            persist(force = true)
        } catch (t: Throwable) {
            target.discard(context)
            throw t
        }
    }

    /** What the part file really holds (first bytes, plus a manifest check for zips). */
    private fun sniff(part: File): FileSafety.Kind {
        val header = ByteArray(FileSafety.HEADER_BYTES)
        val n = runCatching { part.inputStream().use { it.read(header) } }.getOrDefault(-1)
        if (n <= 0) return FileSafety.Kind.UNKNOWN
        val head = header.copyOf(n)
        val first = FileSafety.sniff(head)
        if (first != FileSafety.Kind.ZIP) return first
        val manifest = runCatching { ZipFile(part).use { it.getEntry("AndroidManifest.xml") != null } }.getOrDefault(false)
        return FileSafety.sniff(head, hasManifest = manifest)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun partFile(
        context: Context,
        id: String,
    ): File = File(File(context.filesDir, "parts"), "$id.part")

    private fun persist(force: Boolean) {
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersist < PERSIST_EVERY_MS) return
        lastPersist = now
        runCatching { DownloadStore.save(ctx, _items.value) }
        val delta = unsavedBytes.getAndSet(0)
        if (delta > 0 || ledger.day != dayKey()) {
            ledger = ledger.add(dayKey(), delta)
            runCatching { DownloaderPrefs.setLedger(ctx, ledger) }
        }
        if (force) runCatching { DownloaderPrefs.setHourlySpeed(ctx, hourly.encode()) }
    }

    private fun mutate(
        id: String,
        change: (DownloadItem) -> DownloadItem,
    ) {
        var changed: DownloadItem? = null
        _items.update { list ->
            list.map { if (it.id == id) change(it).also { new -> changed = new } else it }
        }
        changed?.let(::emit)
    }

    private fun emit(item: DownloadItem) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { runCatching { it.onChanged(item) } }
    }

    private fun DownloadItem.toRecord() =
        DuplicateFinder.Record(id, url, sourceId, fileName, total, sha256, state == State.COMPLETED, isActive, finishedAt)

    private const val OCTET = "application/octet-stream"
    private const val PROBE_RETRIES = 4
    private const val MAX_UNZIP_ENTRIES = 10_000
    private const val MAX_UNZIP_BYTES = 8L shl 30

    /** Parts smaller than twice this are not split again (a new connection would cost more than it saves). */
    private const val STEAL_MIN_BYTES = 512L * 1024

    /** Headers OkHttp or the engine set themselves. */
    private val MANAGED_HEADERS = setOf("host", "content-length", "transfer-encoding", "connection", "range", "if-range")

    /** Where a finished download's bytes go, hidden from galleries until complete. */
    private class Target(
        val uri: Uri,
        val stream: OutputStream,
        val displayName: String,
        private val pendingMediaStore: Boolean,
        private val file: File?,
    ) {
        fun publish(context: Context) {
            if (pendingMediaStore && Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        }

        fun discard(context: Context) {
            runCatching { stream.close() }
            if (pendingMediaStore) runCatching { context.contentResolver.delete(uri, null, null) }
            file?.delete()
        }

        companion object {
            fun create(
                context: Context,
                item: DownloadItem,
            ): Target {
                val folder = listOf("Mawja", item.subfolder).filter { it.isNotBlank() }.joinToString("/")
                val safeName = SmartNaming.clean(item.fileName, fallback = item.id)
                if (Build.VERSION.SDK_INT >= 29) {
                    val values =
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folder")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    val uri =
                        context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: error("MediaStore refused the file")
                    val stream = context.contentResolver.openOutputStream(uri) ?: error("Cannot open output")
                    // MediaStore renames on a clash ("name (1).ext"); show the name it really kept.
                    val kept =
                        runCatching {
                            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                                if (c.moveToFirst()) c.getString(0) else null
                            }
                        }.getOrNull() ?: safeName
                    return Target(uri, stream, kept, pendingMediaStore = true, file = null)
                }
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), folder).apply { mkdirs() }
                // Older Android writes straight to a path: never overwrite an existing file.
                val name = SmartNaming.unique(safeName) { File(dir, it).exists() }
                val file = File(dir, name)
                val uri = Uri.fromFile(file)
                return Target(uri, file.outputStream(), name, pendingMediaStore = false, file = file)
            }
        }
    }
}
