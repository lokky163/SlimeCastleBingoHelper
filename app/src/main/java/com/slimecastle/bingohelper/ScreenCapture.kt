package com.slimecastle.bingohelper

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.SystemClock
import android.util.Log

/**
 * Keeps one virtual display alive for the whole session and hands out its latest frame.
 *
 * MediaProjection only renders when the screen changes, so every incoming frame is cached and
 * reused. Frames are converted at most every [frameIntervalMs]; when somebody is waiting,
 * the next frame is converted immediately.
 *
 * The callback receives a bitmap owned by this class: analyse it right away, do not recycle
 * or keep it. Callbacks run on [handler], the same thread that converts frames.
 */
class ScreenCapture(
    projection: MediaProjection,
    private val handler: Handler,
    private val width: Int,
    private val height: Int,
    densityDpi: Int,
) {

    private companion object {
        const val TAG = "BingoHelper"
        /** A static screen produces no new frames; fall back to the cached one after this. */
        const val WAIT_TIMEOUT_MS = 400L
    }

    private val reader: ImageReader? = try {
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
    } catch (t: Throwable) {
        Log.e(TAG, "Cannot create ImageReader", t)
        null
    }

    private var display: VirtualDisplay? = null
    private var frame: Bitmap? = null
    private var hasFrame = false
    private var lastConvert = 0L

    @Volatile
    private var frameIntervalMs = 250L

    private var waiting: ((Bitmap?) -> Unit)? = null
    private var timeoutTask: Runnable? = null

    val isReady: Boolean get() = reader != null && display != null

    fun setFrameInterval(intervalMs: Long) {
        frameIntervalMs = intervalMs
    }

    init {
        val imageReader = reader
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener({ onImageAvailable() }, handler)
            display = try {
                projection.createVirtualDisplay(
                    "bingo-screen",
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.surface,
                    null,
                    handler,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Cannot create virtual display", t)
                null
            }
        }
    }

    /** Delivers the cached frame if there is one, otherwise waits for the next frame. */
    fun capture(onResult: (Bitmap?) -> Unit) = request(fresh = false, WAIT_TIMEOUT_MS, onResult)

    /** Always waits for a frame rendered after this call (falls back to the cache). */
    fun captureFresh(timeoutMs: Long = WAIT_TIMEOUT_MS, onResult: (Bitmap?) -> Unit) =
        request(fresh = true, timeoutMs, onResult)

    private fun request(fresh: Boolean, timeoutMs: Long, onResult: (Bitmap?) -> Unit) {
        handler.post {
            if (waiting != null) {
                onResult(null)
                return@post
            }
            if (!fresh && hasFrame) {
                onResult(frame)
                return@post
            }
            if (reader == null || display == null) {
                onResult(if (hasFrame) frame else null)
                return@post
            }

            waiting = onResult
            val task = Runnable {
                timeoutTask = null
                val callback = waiting ?: return@Runnable
                waiting = null
                callback(if (hasFrame) frame else null)
            }
            timeoutTask = task
            handler.postDelayed(task, timeoutMs)
        }
    }

    private fun onImageAvailable() {
        val imageReader = reader ?: return
        val image = try {
            imageReader.acquireLatestImage()
        } catch (_: Throwable) {
            null
        } ?: return

        var converted = false
        try {
            val now = SystemClock.uptimeMillis()
            if (waiting != null || now - lastConvert >= frameIntervalMs) {
                convert(image)
                converted = true
                lastConvert = now
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot convert frame", t)
        } finally {
            try {
                image.close()
            } catch (_: Throwable) {
            }
        }

        val callback = waiting ?: return
        if (!converted && !hasFrame) return

        waiting = null
        timeoutTask?.let { handler.removeCallbacks(it) }
        timeoutTask = null
        callback(frame)
    }

    private fun convert(image: Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride

        var bitmap = frame
        if (bitmap == null || bitmap.width != paddedWidth || bitmap.height != height) {
            bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            frame = bitmap
        }
        bitmap.copyPixelsFromBuffer(buffer)
        hasFrame = true
    }

    fun release() {
        handler.post {
            waiting = null
            timeoutTask?.let { handler.removeCallbacks(it) }
            timeoutTask = null

            try {
                reader?.setOnImageAvailableListener(null, null)
            } catch (_: Throwable) {
            }
            try {
                display?.release()
            } catch (_: Throwable) {
            }
            try {
                reader?.close()
            } catch (_: Throwable) {
            }

            frame = null
            hasFrame = false
        }
    }
}
