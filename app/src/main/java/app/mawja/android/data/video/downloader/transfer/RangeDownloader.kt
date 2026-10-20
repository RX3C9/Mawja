package app.mawja.android.data.video.downloader.transfer

import android.content.Context
import android.util.Log
import app.mawja.android.BuildConfig
import app.mawja.android.data.video.downloader.remote.DownloaderConnection
import app.mawja.android.data.video.downloader.remote.RemoteRangeResult
import app.mawja.android.data.video.downloader.remote.RemoteRangeTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import app.mawja.android.data.video.downloader.YouTubeStreamUrls
import app.mawja.android.network.ProxyAwareClient
import app.mawja.android.player.datasource.GoogleVideoRequestPolicy
import app.mawja.android.player.error.StreamDenialClassifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Range workers for the video (or only) stream; a download always gets at least one. */
internal fun videoWorkerCount(threads: Int): Int = threads.coerceAtLeast(1)

/** Range workers for the separate audio stream: half the connections, at least two when there are two to give. */
internal fun audioWorkerCount(threads: Int): Int {
    val connections = threads.coerceAtLeast(1)
    return (connections / 2).coerceIn(minOf(2, connections), connections)
}

/**
 * Fetches every stream of a [TransferJob] in fixed-size byte ranges, several at a time, straight
 * into pre-sized part files.
 *
 * Each block is tracked on the job, so a stop keeps what was written and a later run with the
 * restored block map fetches only the rest. The first block that fails for good fails the whole
 * job at once: carrying on would download the rest of a file that is then thrown away.
 *
 * Mawja: when Mawja Downloader is installed (API 2+), every request is run there through
 * [RemoteRangeTransport] and the downloader writes the bytes into these part files; the local
 * OkHttp path stays for builds that do not require the downloader and for tests.
 */
@Singleton
class RangeDownloader internal constructor(
    private val client: () -> OkHttpClient,
    // Blocking socket reads run here, never on the shared IO pool, so a large download cannot
    // starve the rest of the app of IO threads.
    private val transferDispatcher: CoroutineDispatcher,
    private val remote: () -> RemoteRangeTransport? = { null },
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        ProxyAwareClient {
            connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .retryOnConnectionFailure(true)
        }::get,
        Dispatchers.IO.limitedParallelism(MAX_PARALLEL_READS),
        {
            if (BuildConfig.REQUIRE_DOWNLOADER) {
                DownloaderConnection.transferService(context)?.let(::RemoteRangeTransport)
            } else {
                null
            }
        },
    )

    /**
     * Runs [job] to its end. Cancelling the caller stops the job at once: the blocking reads
     * are released by cancelling their requests, which a coroutine cancellation alone cannot do.
     */
    suspend fun run(job: TransferJob): TransferResult =
        withContext(transferDispatcher) {
            coroutineScope {
                val releaseOnCancel =
                    launch {
                        try {
                            awaitCancellation()
                        } finally {
                            job.stop()
                        }
                    }
                try {
                    transfer(client(), job)
                } finally {
                    releaseOnCancel.cancel()
                }
            }
        }

    private suspend fun transfer(
        client: OkHttpClient,
        job: TransferJob,
    ): TransferResult {
        val transport = runCatching { remote() }.getOrNull()
        if (transport != null) Log.d(TAG, "${job.videoId}: bytes go through Mawja Downloader")
        try {
            for (stream in job.streams) {
                if (stream.totalBytes <= 0L) {
                    when (val length = contentLength(client, transport, stream.url, job.fallbackUserAgent)) {
                        is ContentLength.Known -> stream.totalBytes = length.bytes
                        ContentLength.Denied -> job.fail(TransferResult.Denied(stream.url))
                        ContentLength.Unknown -> job.fail(TransferResult.Failed(TransferFailure.LENGTH_UNKNOWN))
                    }
                    if (!job.isRunning) return job.outcome()
                }
                prepareFile(stream.file, stream.totalBytes)
                stream.nextBlock.set(0)
                stream.downloaded.set(restoredBytes(stream))
            }

            coroutineScope {
                job.streams
                    .flatMap { stream ->
                        val workers =
                            if (stream.role == StreamRole.AUDIO && job.streams.size > 1) {
                                audioWorkerCount(job.threads)
                            } else {
                                videoWorkerCount(job.threads)
                            }
                        List(workers) { async { workerLoop(client, transport, job, stream) } }
                    }.awaitAll()
            }
            if (job.isRunning) {
                if (job.streams.all { it.completedBlocks.size >= blockCount(it.totalBytes) }) {
                    job.complete()
                } else {
                    job.fail(TransferResult.Failed(TransferFailure.BLOCK_FAILED, "blocks missing after transfer"))
                }
            }
        } catch (e: IOException) {
            val failure = if (isDiskFull(e)) TransferFailure.DISK_FULL else TransferFailure.BLOCK_FAILED
            job.fail(TransferResult.Failed(failure, e.message))
        }
        return job.outcome()
    }

    private fun TransferJob.outcome(): TransferResult = result ?: TransferResult.Stopped

    private suspend fun workerLoop(
        client: OkHttpClient,
        transport: RemoteRangeTransport?,
        job: TransferJob,
        stream: TransferStream,
    ) {
        val blockCount = blockCount(stream.totalBytes)
        while (job.isRunning) {
            val block = stream.nextBlock.getAndIncrement()
            if (block >= blockCount) return
            if (block in stream.completedBlocks) continue
            val start = block * BLOCK_SIZE
            val end = minOf(start + BLOCK_SIZE, stream.totalBytes) - 1
            if (!fetchBlockWithRetry(client, transport, job, stream, block, start, end)) return
            stream.completedBlocks.add(block)
        }
    }

    private suspend fun fetchBlockWithRetry(
        client: OkHttpClient,
        transport: RemoteRangeTransport?,
        job: TransferJob,
        stream: TransferStream,
        block: Int,
        start: Long,
        end: Long,
    ): Boolean {
        var lastError: String? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            if (!job.isRunning) return false
            val outcome =
                if (transport != null) {
                    fetchBlockRemote(transport, job, stream, block, start, end)
                } else {
                    fetchBlock(client, job, stream, block, start, end)
                }
            when (outcome) {
                BlockOutcome.Done -> {
                    return true
                }

                BlockOutcome.Stopped -> {
                    return false
                }

                is BlockOutcome.Denied -> {
                    job.fail(TransferResult.Denied(outcome.url))
                    return false
                }

                is BlockOutcome.Retry -> {
                    lastError = outcome.reason
                    Log.w(TAG, "${job.videoId} ${stream.role} block $block attempt ${attempt + 1}: ${outcome.reason}")
                    if (outcome.diskFull) {
                        job.fail(TransferResult.Failed(TransferFailure.DISK_FULL, outcome.reason))
                        return false
                    }
                    if (attempt + 1 < MAX_ATTEMPTS) delay(retryDelayMs(attempt, outcome.retryAfterMs))
                }
            }
        }
        job.fail(TransferResult.Failed(TransferFailure.BLOCK_FAILED, lastError))
        return false
    }

    private fun fetchBlock(
        client: OkHttpClient,
        job: TransferJob,
        stream: TransferStream,
        block: Int,
        start: Long,
        end: Long,
    ): BlockOutcome {
        val already = stream.partialBlockBytes[block] ?: 0L
        val from = start + already
        if (from > end) {
            stream.partialBlockBytes.remove(block)
            return BlockOutcome.Done
        }
        val call = client.newCall(blockRequest(stream.url, from, end, job.fallbackUserAgent))
        val handle = job.track(call)
        var written = 0L
        try {
            val response =
                try {
                    call.execute()
                } catch (e: IOException) {
                    return if (job.isRunning) BlockOutcome.Retry(e.message ?: e.javaClass.simpleName) else BlockOutcome.Stopped
                }
            response.use {
                if (!response.isSuccessful) return failedResponse(response, stream.url)
                // Only googlevideo's query range may answer 200; anywhere else a 200 means the
                // server ignored the Range header and is sending the file from its first byte.
                if (response.code != 206 && !YouTubeStreamUrls.isYouTubeStreamUrl(stream.url)) {
                    return BlockOutcome.Retry("range ignored")
                }
                val body = response.body
                val expected = end - from + 1
                RandomAccessFile(stream.file, "rw").use { file ->
                    file.seek(from)
                    val buffer = ByteArray(BUFFER_SIZE)
                    body.byteStream().use { input ->
                        while (written < expected) {
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), expected - written).toInt())
                            if (read < 0) break
                            if (!job.isRunning) {
                                stream.partialBlockBytes[block] = already + written
                                return BlockOutcome.Stopped
                            }
                            file.write(buffer, 0, read)
                            written += read
                            stream.downloaded.addAndGet(read.toLong())
                        }
                    }
                }
                if (written < expected) {
                    stream.partialBlockBytes[block] = already + written
                    return BlockOutcome.Retry("short block: $written of $expected bytes")
                }
                stream.partialBlockBytes.remove(block)
                return BlockOutcome.Done
            }
        } catch (e: IOException) {
            stream.partialBlockBytes[block] = already + written
            if (!job.isRunning) return BlockOutcome.Stopped
            return BlockOutcome.Retry(e.message ?: e.javaClass.simpleName, diskFull = isDiskFull(e))
        } finally {
            job.untrack(handle)
        }
    }

    /** [fetchBlock] run inside Mawja Downloader, which writes the body into the part file. */
    private fun fetchBlockRemote(
        transport: RemoteRangeTransport,
        job: TransferJob,
        stream: TransferStream,
        block: Int,
        start: Long,
        end: Long,
    ): BlockOutcome {
        val already = stream.partialBlockBytes[block] ?: 0L
        val from = start + already
        if (from > end) {
            stream.partialBlockBytes.remove(block)
            return BlockOutcome.Done
        }
        if (!job.isRunning) return BlockOutcome.Stopped
        val expected = end - from + 1
        val result =
            try {
                transport.fetch(
                    request = blockRequest(stream.url, from, end, job.fallbackUserAgent),
                    file = stream.file,
                    offset = from,
                    maxBytes = expected,
                    // Only googlevideo's query range may answer 200; see fetchBlock.
                    requirePartial = !YouTubeStreamUrls.isYouTubeStreamUrl(stream.url),
                    onBytes = { stream.downloaded.addAndGet(it) },
                    registerCancel = job::trackCancel,
                    unregisterCancel = job::untrack,
                )
            } catch (e: IOException) {
                return if (job.isRunning) BlockOutcome.Retry(e.message ?: e.javaClass.simpleName) else BlockOutcome.Stopped
            }
        val written = result.written
        if (written > 0) stream.partialBlockBytes[block] = already + written
        if (!job.isRunning || result.cancelled) return BlockOutcome.Stopped
        if (result.code == 0 || result.error != null && result.isSuccessful) {
            return BlockOutcome.Retry(result.error ?: "no response", diskFull = result.diskFull)
        }
        if (!result.isSuccessful) return failedResponse(result.code, result.retryAfter, stream.url)
        if (result.code != 206 && !YouTubeStreamUrls.isYouTubeStreamUrl(stream.url)) return BlockOutcome.Retry("range ignored")
        if (written < expected) return BlockOutcome.Retry("short block: $written of $expected bytes")
        stream.partialBlockBytes.remove(block)
        return BlockOutcome.Done
    }

    private fun failedResponse(
        response: Response,
        url: String,
    ): BlockOutcome = failedResponse(response.code, response.header("Retry-After"), url)

    private fun failedResponse(
        code: Int,
        retryAfter: String?,
        url: String,
    ): BlockOutcome =
        when (code) {
            403 -> BlockOutcome.Denied(url)
            429, 500, 502, 503, 504 -> BlockOutcome.Retry("HTTP $code", retryAfterMs = retryAfterMs(retryAfter))
            else -> BlockOutcome.Retry("HTTP $code")
        }

    private fun blockRequest(
        url: String,
        from: Long,
        to: Long,
        fallbackUserAgent: String,
    ): Request {
        val builder = Request.Builder()
        if (YouTubeStreamUrls.isYouTubeStreamUrl(url)) {
            val client = StreamDenialClassifier.clientOf(url)
            builder
                .url(YouTubeStreamUrls.buildYouTubeBlockUrl(url, from, to))
                .header("User-Agent", GoogleVideoRequestPolicy.userAgent(client, fallbackUserAgent))
            GoogleVideoRequestPolicy.headers(client).forEach { (name, value) -> builder.header(name, value) }
        } else {
            builder
                .url(url)
                .header("Range", "bytes=$from-$to")
                .header("User-Agent", fallbackUserAgent)
        }
        return builder.build()
    }

    private sealed interface ContentLength {
        data class Known(
            val bytes: Long,
        ) : ContentLength

        data object Denied : ContentLength

        data object Unknown : ContentLength
    }

    /**
     * The stream's size: from the URL's `clen` when it carries one, otherwise a one-byte probe.
     * The probe's status is checked, so a refused URL reports the refusal rather than a size.
     */
    private fun contentLength(
        client: OkHttpClient,
        transport: RemoteRangeTransport?,
        url: String,
        fallbackUserAgent: String,
    ): ContentLength {
        if (YouTubeStreamUrls.isYouTubeStreamUrl(url)) {
            YouTubeStreamUrls.extractClenFromUrl(url).takeIf { it > 0 }?.let { return ContentLength.Known(it) }
        }
        if (transport != null) return remoteContentLength(transport, url, fallbackUserAgent)
        return try {
            client.newCall(blockRequest(url, 0L, 0L, fallbackUserAgent)).execute().use { response ->
                when {
                    response.code == 403 -> {
                        ContentLength.Denied
                    }

                    !response.isSuccessful -> {
                        ContentLength.Unknown
                    }

                    else -> {
                        response
                            .header("Content-Range")
                            ?.substringAfter('/')
                            ?.toLongOrNull()
                            ?.takeIf { it > 0 }
                            ?.let { ContentLength.Known(it) } ?: ContentLength.Unknown
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Length probe failed: ${e.message}")
            ContentLength.Unknown
        }
    }

    private fun remoteContentLength(
        transport: RemoteRangeTransport,
        url: String,
        fallbackUserAgent: String,
    ): ContentLength {
        val result: RemoteRangeResult =
            try {
                transport.fetch(
                    request = blockRequest(url, 0L, 0L, fallbackUserAgent),
                    file = null,
                    offset = 0L,
                    maxBytes = 0L,
                    requirePartial = false,
                    onBytes = {},
                    registerCancel = { cancel -> cancel },
                    unregisterCancel = {},
                )
            } catch (e: IOException) {
                Log.w(TAG, "Length probe through Mawja Downloader failed: ${e.message}")
                return ContentLength.Unknown
            }
        return when {
            result.code == 403 -> ContentLength.Denied
            !result.isSuccessful -> ContentLength.Unknown
            else ->
                result.contentRange
                    ?.substringAfter('/')
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
                    ?.let { ContentLength.Known(it) } ?: ContentLength.Unknown
        }
    }

    private fun prepareFile(
        file: File,
        size: Long,
    ) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf -> if (raf.length() != size) raf.setLength(size) }
    }

    private fun restoredBytes(stream: TransferStream): Long =
        stream.completedBlocks.sumOf { block ->
            val start = block * BLOCK_SIZE
            minOf(start + BLOCK_SIZE, stream.totalBytes) - start
        } + stream.partialBlockBytes.values.sum()

    private sealed interface BlockOutcome {
        data object Done : BlockOutcome

        data object Stopped : BlockOutcome

        data class Denied(
            val url: String,
        ) : BlockOutcome

        data class Retry(
            val reason: String,
            val retryAfterMs: Long? = null,
            val diskFull: Boolean = false,
        ) : BlockOutcome
    }

    internal companion object {
        private const val TAG = "RangeDownloader"
        const val BLOCK_SIZE = 2L * 1024 * 1024
        private const val BUFFER_SIZE = 256 * 1024
        private const val MAX_ATTEMPTS = 5
        private const val INITIAL_RETRY_DELAY_MS = 2_000L
        private const val MAX_RETRY_AFTER_MS = 60_000L
        private const val MAX_PARALLEL_READS = 16

        fun blockCount(totalBytes: Long): Int = ((totalBytes + BLOCK_SIZE - 1) / BLOCK_SIZE).toInt()

        fun retryDelayMs(
            attempt: Int,
            retryAfterMs: Long?,
        ): Long = retryAfterMs?.coerceIn(0L, MAX_RETRY_AFTER_MS) ?: (INITIAL_RETRY_DELAY_MS shl attempt)

        fun isDiskFull(error: IOException): Boolean =
            error.message?.contains("ENOSPC") == true || error.message?.contains("No space left") == true

        private fun retryAfterMs(retryAfter: String?): Long? =
            retryAfter
                ?.trim()
                ?.toLongOrNull()
                ?.times(1000L)
    }
}
