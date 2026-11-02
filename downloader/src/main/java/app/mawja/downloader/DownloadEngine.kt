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
import java.util.concurrent.atomic.AtomicLongArray

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
) {
    val progress: Float get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    val isActive: Boolean get() = state == State.QUEUED || state == State.RUNNING
}

/** Event sink so the binder service can forward changes to its clients. */
interface DownloadListener {
    fun onChanged(item: DownloadItem)
}

/**
 * Process-wide download engine (IDM-style): every file is split into up to 16 ranges fetched in
 * parallel into a private part file, with pause and resume (also after the app or phone was
 * closed; [DownloadWork] picks unfinished work up again), smart retries, a shared speed limit, a
 * priority queue with Wi-Fi-only and "tonight" rules, and a size check plus SHA-256 at the end.
 * Finished files land in the public Downloads/Mawja folder through MediaStore on Android 10+.
 */
object DownloadEngine {
    private const val MAX_PARALLEL = 3
    private const val TICK_MS = 500L
    private const val PERSIST_EVERY_MS = 3_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val pausing = ConcurrentHashMap.newKeySet<String>()
    private val meters = ConcurrentHashMap<String, SpeedMeter>()
    private val listeners = mutableSetOf<DownloadListener>()
    private val limiter = SpeedLimiter()

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastPersist = 0L

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
            val saved = DownloadStore.load(ctx).map { if (it.state == State.RUNNING) it.copy(state = State.QUEUED, speedBps = 0) else it }
            _items.value = saved
            scope.launch {
                while (isActive) {
                    pump()
                    delay(15_000)
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
        return waiting.isNotEmpty() && (waiting.all { it.wifiOnly } || (ctx != null && DownloaderPrefs.wifiOnly(ctx)))
    }

    fun enqueue(
        context: Context,
        item: DownloadItem,
    ): String {
        init(context)
        // Same source already queued or running: hand back the existing id instead of downloading twice.
        item.sourceId?.let { source ->
            _items.value.firstOrNull { it.sourceId == source && it.isActive }?.let { return it.id }
        }
        val queued =
            item.copy(
                id = item.id.ifBlank { UUID.randomUUID().toString() },
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

    /** Starts it now: first in the queue, ignoring Wi-Fi-only and "tonight". */
    fun startNow(id: String) {
        val top = (_items.value.maxOfOrNull { it.priority } ?: 0) + 1
        mutate(id) { it.copy(priority = top, notBefore = 0, wifiOnly = false, paused = false) }
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
        pump()
    }

    /** Speed samples of the last minute, one per second, for the live graph. */
    fun speedHistory(id: String): List<Long> = meters[id]?.history().orEmpty()

    fun tonight(): Long = QueuePolicy.tonight(Calendar.getInstance())

    /** Starts whatever the queue allows now. */
    @Synchronized
    fun pump() {
        val ctx = appContext ?: return
        val slots = MAX_PARALLEL - jobs.size
        if (slots <= 0) return
        val candidates =
            _items.value
                .filter { it.state == State.QUEUED && !jobs.containsKey(it.id) }
                .map { QueuePolicy.Candidate(it.id, it.priority, it.createdAt, it.wifiOnly, it.notBefore, it.paused) }
        val picked =
            QueuePolicy.pick(candidates, slots, System.currentTimeMillis(), isUnmetered(ctx), DownloaderPrefs.wifiOnly(ctx))
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
            if (item.total <= 0 || (item.segments.isEmpty() && !part.exists())) {
                val probe = probe(item)
                val segments = if (probe.ranges && probe.total > 0) DownloadMath.split(probe.total) else emptyList()
                item = item.copy(total = probe.total, segments = segments)
                mutate(id) { it.copy(total = probe.total, segments = segments) }
                part.delete()
            }
            part.parentFile?.mkdirs()
            if (item.segments.isNotEmpty()) {
                fetchSegmented(id, item, part, meter)
            } else {
                fetchSingle(id, item, part, meter)
            }
            finish(context, id, part)
        } catch (t: Throwable) {
            val wasPaused = id in pausing
            val current = _items.value.firstOrNull { it.id == id }
            when {
                wasPaused || current?.paused == true -> Unit
                current?.state == State.CANCELLED -> part.delete()
                t is CancellationException -> mutate(id) { it.copy(state = State.QUEUED, speedBps = 0) }
                else -> mutate(id) { it.copy(state = State.FAILED, speedBps = 0, etaSeconds = -1, error = t.message ?: t.javaClass.simpleName) }
            }
            persist(force = true)
            if (t is CancellationException) throw t
        }
    }

    private data class Probe(
        val total: Long,
        val ranges: Boolean,
    )

    /** Asks for the first byte: a 206 with a Content-Range means the server can serve parts. */
    private fun probe(item: DownloadItem): Probe {
        val request = requestFor(item).header("Range", "bytes=0-0").build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            if (r.code == 206) {
                val total = r.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1L
                return Probe(total, total > 0)
            }
            return Probe(r.body.contentLength(), false)
        }
    }

    private fun requestFor(item: DownloadItem): Request.Builder =
        Request
            .Builder()
            .url(item.url)
            .apply { item.headers.forEach { (k, v) -> header(k, v) } }

    private suspend fun fetchSegmented(
        id: String,
        item: DownloadItem,
        part: File,
        meter: SpeedMeter,
    ) {
        val segments = item.segments
        val done = AtomicLongArray(segments.size).apply { segments.forEachIndexed { i, s -> set(i, s.done.coerceAtMost(s.length)) } }
        RandomAccessFile(part, "rw").use { raf ->
            if (raf.length() != item.total) raf.setLength(item.total)
            val channel = raf.channel
            coroutineScope {
                val reporter = launch { report(id, item.total, meter) { segments.mapIndexed { i, s -> s.copy(done = done.get(i)) } } }
                segments.indices
                    .filter { done.get(it) < segments[it].length }
                    .map { i -> async { fetchRange(item, segments[i], done, i, channel, meter) } }
                    .awaitAll()
                reporter.cancel()
            }
            channel.force(false)
        }
        mutate(id) { it.copy(segments = segments.mapIndexed { i, s -> s.copy(done = done.get(i)) }, downloaded = item.total) }
    }

    /** One range with its own retries; a retry continues from the last byte written. */
    private suspend fun fetchRange(
        item: DownloadItem,
        seg: Segment,
        done: AtomicLongArray,
        index: Int,
        channel: FileChannel,
        meter: SpeedMeter,
    ) {
        var attempt = 0
        while (done.get(index) < seg.length) {
            currentCoroutineContext().ensureActive()
            try {
                val from = seg.start + done.get(index)
                val request = requestFor(item).header("Range", "bytes=$from-${seg.end}").build()
                client.newCall(request).execute().use { r ->
                    if (r.code != 206) throw IOException("HTTP ${r.code} for a part")
                    r.body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (done.get(index) < seg.length) {
                            currentCoroutineContext().ensureActive()
                            val want = minOf(buffer.size.toLong(), seg.length - done.get(index)).toInt()
                            val read = input.read(buffer, 0, want)
                            if (read < 0) break
                            limiter.reserve(read).takeIf { it > 0 }?.let { delay(it) }
                            channel.write(ByteBuffer.wrap(buffer, 0, read), seg.start + done.get(index))
                            done.addAndGet(index, read.toLong())
                            meter.add(read.toLong())
                            attempt = 0
                        }
                    }
                }
            } catch (e: IOException) {
                if (++attempt > DownloadMath.MAX_RETRIES) throw e
                delay(DownloadMath.retryDelayMs(attempt - 1))
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
        while (true) {
            currentCoroutineContext().ensureActive()
            var written = 0L
            try {
                coroutineScope {
                    val reporter = launch { report(id, item.total, meter, single = { written }) { emptyList() } }
                    client.newCall(requestFor(item).build()).execute().use { r ->
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                        part.outputStream().use { out ->
                            r.body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    limiter.reserve(read).takeIf { it > 0 }?.let { delay(it) }
                                    out.write(buffer, 0, read)
                                    written += read
                                    meter.add(read.toLong())
                                }
                            }
                        }
                    }
                    reporter.cancel()
                }
                if (item.total > 0 && written != item.total) throw IOException("Short download: $written of ${item.total}")
                mutate(id) { it.copy(downloaded = written, total = if (item.total > 0) item.total else written) }
                return
            } catch (e: IOException) {
                if (++attempt > DownloadMath.MAX_RETRIES) throw e
                delay(DownloadMath.retryDelayMs(attempt - 1))
            }
        }
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
            mutate(id) {
                it.copy(
                    downloaded = got,
                    segments = if (single == null) segs else it.segments,
                    speedBps = bps,
                    etaSeconds = if (total > 0) DownloadMath.etaSeconds(total - got, bps) else -1,
                )
            }
            persist(force = false)
        }
    }

    /** Copies the part file into Downloads/Mawja with a SHA-256, checks the size, then publishes. */
    private fun finish(
        context: Context,
        id: String,
        part: File,
    ) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        val target = Target.create(context, item)
        try {
            target.stream.use { out ->
                part.inputStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read
                    }
                }
            }
            target.publish(context)
        } catch (t: Throwable) {
            target.discard(context)
            throw t
        }
        part.delete()
        val expected = item.total
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        mutate(id) {
            it.copy(
                state = State.COMPLETED,
                contentUri = target.uri.toString(),
                downloaded = copied,
                total = if (expected > 0) expected else copied,
                speedBps = 0,
                etaSeconds = 0,
                segments = emptyList(),
                sha256 = sha,
                verified = expected <= 0 || copied == expected,
                finishedAt = System.currentTimeMillis(),
            )
        }
        persist(force = true)
    }

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

    /** Where a finished download's bytes go, hidden from galleries until complete. */
    private class Target(
        val uri: Uri,
        val stream: OutputStream,
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
                val safeName = item.fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { item.id }
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
                    return Target(uri, stream, pendingMediaStore = true, file = null)
                }
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), folder).apply { mkdirs() }
                val file = File(dir, safeName)
                val uri = Uri.fromFile(file)
                return Target(uri, file.outputStream(), pendingMediaStore = false, file = file)
            }
        }
    }
}
