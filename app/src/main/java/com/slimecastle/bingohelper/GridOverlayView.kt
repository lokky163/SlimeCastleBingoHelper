package com.slimecastle.bingohelper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Full-screen, non-touchable overlay for semi-transparent calibration previews.
 * The opaque best-move outline is shown in separate, narrow windows so it doesn't
 * block touches on the game through the rest of the screen.
 */
class GridOverlayView(context: Context) : View(context) {

    class Extra(val rect: RectF, val fill: Int, val stroke: Int)

    var cells: List<RectF> = emptyList()
    var extras: List<Extra> = emptyList()

    private val density = resources.displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xCCFFFFFF.toInt()
        strokeWidth = density * 1.5f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawCalibration(canvas)
    }

    private fun drawCalibration(canvas: Canvas) {
        fillPaint.color = 0x55808080
        for (cell in cells) {
            canvas.drawRect(cell, fillPaint)
            canvas.drawRect(cell, strokePaint)
        }

        for (extra in extras) {
            fillPaint.color = extra.fill
            canvas.drawRect(extra.rect, fillPaint)
            strokePaint.color = extra.stroke
            canvas.drawRect(extra.rect, strokePaint)
        }
        strokePaint.color = 0xCCFFFFFF.toInt()
    }

}
