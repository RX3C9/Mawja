package app.mawja.android.player.screenshot

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import app.mawja.android.player.EnhancedPlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * The player's video surface, published by VideoPlayerSurface so a screenshot can read the decoded
 * frame straight off it. Weak so a torn-down player never leaks through here.
 */
object VideoSurfaceRegistry {
    @Volatile
    private var ref: WeakReference<SurfaceView>? = null

    fun publish(view: SurfaceView) {
        ref = WeakReference(view)
    }

    fun clear(view: SurfaceView) {
        if (ref?.get() === view) ref = null
    }

    fun current(): SurfaceView? = ref?.get()
}

/**
 * Clean, full-resolution screenshot (#91): the frame is copied from the video surface itself with
 * [PixelCopy], so no controls, subtitles or overlays are in it, at the decoded video's size, and saved
 * as PNG to Pictures/Mawja.
 */
object VideoScreenshot {
    sealed interface Result {
        data class Saved(val displayPath: String) : Result

        data object NoFrame : Result

        data object Failed : Result
    }

    suspend fun capture(
        context: Context,
        title: String?,
    ): Result {
        val surfaceView = VideoSurfaceRegistry.current() ?: return Result.NoFrame
        val holderSurface = surfaceView.holder?.surface
        if (holderSurface == null || !holderSurface.isValid) return Result.NoFrame
        val manager = EnhancedPlayerManager.getInstance()
        val videoSize = manager.getPlayer()?.videoSize
        val size =
            ScreenshotPlan.captureSize(
                videoWidth = videoSize?.width ?: 0,
                videoHeight = videoSize?.height ?: 0,
                pixelWidthHeightRatio = videoSize?.pixelWidthHeightRatio ?: 1f,
                surfaceWidth = surfaceView.width,
                surfaceHeight = surfaceView.height,
            ) ?: return Result.NoFrame
        val bitmap =
            runCatching { Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888) }
                .getOrNull() ?: return Result.Failed
        val copied = copyFrame(surfaceView, bitmap)
        if (!copied) {
            bitmap.recycle()
            return Result.Failed
        }
        val name =
            ScreenshotPlan.fileName(
                title = title,
                positionMs = manager.getCurrentPosition(),
                nowMillis = System.currentTimeMillis(),
            )
        return withContext(Dispatchers.IO) {
            try {
                save(context, bitmap, name)
            } catch (_: Throwable) {
                Result.Failed
            } finally {
                bitmap.recycle()
            }
        }
    }

    private suspend fun copyFrame(
        view: SurfaceView,
        dst: Bitmap,
    ): Boolean {
        val thread = HandlerThread("mawja-screenshot").apply { start() }
        return try {
            suspendCancellableCoroutine { cont ->
                try {
                    PixelCopy.request(
                        view,
                        dst,
                        { result -> if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS) },
                        Handler(thread.looper),
                    )
                } catch (_: Throwable) {
                    if (cont.isActive) cont.resume(false)
                }
            }
        } finally {
            thread.quitSafely()
        }
    }

    private fun save(
        context: Context,
        bitmap: Bitmap,
        name: String,
    ): Result {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values =
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, ScreenshotPlan.RELATIVE_DIR)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            val uri =
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return Result.Failed
            val ok =
                resolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) } == true
            if (!ok) {
                resolver.delete(uri, null, null)
                return Result.Failed
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return Result.Saved("${ScreenshotPlan.RELATIVE_DIR}/$name")
        }
        // API 26–28: the shared Pictures folder needs the storage permission; without it the picture
        // goes to the app's own Pictures folder instead.
        val canWriteShared =
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        @Suppress("DEPRECATION")
        val root =
            if (canWriteShared) {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            } else {
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            } ?: return Result.Failed
        val dir = File(root, ScreenshotPlan.DIR_NAME).apply { mkdirs() }
        val file = File(dir, name)
        val ok = FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        if (!ok) return Result.Failed
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
        return Result.Saved(file.absolutePath)
    }
}
