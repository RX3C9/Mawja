/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.downloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.mawja.downloader.api.MawjaDownloaderContract.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
) {
    val progress: Float get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    val isActive: Boolean get() = state == State.QUEUED || state == State.RUNNING
}

/** Event sink so the binder service can forward changes to its clients. */
interface DownloadListener {
    fun onChanged(item: DownloadItem)
}

/**
 * Process-wide download engine: at most [MAX_PARALLEL] transfers, progress throttled,
 * files written to the public Downloads/Mawja folder through MediaStore on Android 10+.
 */
object DownloadEngine {
    private const val MAX_PARALLEL = 3
    private const val PROGRESS_STEP_BYTES = 256 * 1024L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(MAX_PARALLEL)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val listeners = mutableSetOf<DownloadListener>()

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun addListener(listener: DownloadListener) = synchronized(listeners) { listeners += listener }

    fun removeListener(listener: DownloadListener) = synchronized(listeners) { listeners -= listener }

    fun activeCount(): Int = _items.value.count { it.isActive }

    fun enqueue(
        context: Context,
        item: DownloadItem,
    ): String {
        // Same source already queued or running: hand back the existing id instead of downloading twice.
        item.sourceId?.let { source ->
            _items.value.firstOrNull { it.sourceId == source && it.isActive }?.let { return it.id }
        }
        val queued = item.copy(id = item.id.ifBlank { UUID.randomUUID().toString() }, state = State.QUEUED)
        _items.update { listOf(queued) + it }
        emit(queued)
        val appContext = context.applicationContext
        jobs[queued.id] =
            scope.launch {
                gate.withPermit { run(appContext, queued.id) }
                jobs.remove(queued.id)
            }
        return queued.id
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        mutate(id) { if (it.isActive) it.copy(state = State.CANCELLED) else it }
    }

    fun clearFinished() = _items.update { list -> list.filter { it.isActive } }

    private suspend fun run(
        context: Context,
        id: String,
    ) {
        val start = _items.value.firstOrNull { it.id == id } ?: return
        if (start.state == State.CANCELLED) return
        mutate(id) { it.copy(state = State.RUNNING) }
        var target: Target? = null
        try {
            val request =
                Request
                    .Builder()
                    .url(start.url)
                    .apply { start.headers.forEach { (k, v) -> header(k, v) } }
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body
                val total = body.contentLength()
                target = Target.create(context, start)
                target!!.stream.use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReported = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            if (done - lastReported >= PROGRESS_STEP_BYTES) {
                                lastReported = done
                                mutate(id) { it.copy(downloaded = done, total = total) }
                            }
                        }
                        mutate(id) { it.copy(downloaded = done, total = if (total > 0) total else done) }
                    }
                }
                target!!.publish(context)
                mutate(id) { it.copy(state = State.COMPLETED, contentUri = target!!.uri.toString()) }
            }
        } catch (t: Throwable) {
            target?.discard(context)
            val cancelled = _items.value.firstOrNull { it.id == id }?.state == State.CANCELLED ||
                t is kotlinx.coroutines.CancellationException
            mutate(id) {
                if (cancelled) it.copy(state = State.CANCELLED) else it.copy(state = State.FAILED, error = t.message ?: t.javaClass.simpleName)
            }
            if (t is kotlinx.coroutines.CancellationException) throw t
        }
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

    /** Where a download's bytes go, hidden from galleries until it finishes. */
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
