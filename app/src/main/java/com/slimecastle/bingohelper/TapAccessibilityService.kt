package com.slimecastle.bingohelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Minimal accessibility service that only performs taps for the auto player.
 *
 * Android does not allow an app to inject touches into other apps any other way. The
 * service does not read or collect any accessibility data.
 */
class TapAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BingoHelper"

        @Volatile
        var instance: TapAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Tap service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** A short tap at [x], [y] in screen pixels. [onResult] is called on the main thread. */
    fun tap(x: Float, y: Float, durationMs: Long = 55L, onResult: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()

        dispatch(gesture, x, y, onResult)
    }

    private fun dispatch(
        gesture: GestureDescription,
        x: Float,
        y: Float,
        onResult: (Boolean) -> Unit,
    ) {
        val dispatched = try {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        mainHandler.post { onResult(true) }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "Accessibility gesture cancelled at ($x, $y)")
                        mainHandler.post { onResult(false) }
                    }
                },
                null,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "dispatchGesture failed", t)
            false
        }

        if (!dispatched) {
            Log.w(TAG, "Accessibility gesture was rejected at ($x, $y)")
            mainHandler.post { onResult(false) }
        }
    }
}
