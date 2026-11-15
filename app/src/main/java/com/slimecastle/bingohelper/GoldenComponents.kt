package com.slimecastle.bingohelper

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Connected regions of golden pixels, used only to locate the two restart buttons during
 * manual calibration. Reward squares are intentionally not recognised.
 *
 * The screenshot is scanned every [STEP] pixels, which is plenty for elements that are
 * 100+ pixels wide and keeps the scan fast.
 */
object GoldenComponents {

    const val STEP = 4

    class Component(val rect: RectF, val fill: Float)

    /** A warm, very bright pixel on the golden restart buttons. */
    fun isGolden(color: Int): Boolean {
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        return r > 200 && g > 150 && b < 140 && r > b + 80
    }

    fun find(bitmap: Bitmap): List<Component> {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return emptyList()

        val gridWidth = (width + STEP - 1) / STEP
        val gridHeight = (height + STEP - 1) / STEP
        val mask = BooleanArray(gridWidth * gridHeight)
        val row = IntArray(width)

        var y = 0
        var gy = 0
        while (y < height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            val offset = gy * gridWidth
            var x = 0
            var gx = 0
            while (x < width) {
                if (isGolden(row[x])) mask[offset + gx] = true
                x += STEP
                gx++
            }
            y += STEP
            gy++
        }

        val seen = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val result = ArrayList<Component>()

        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue

            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true

            var count = 0
            var minX = gridWidth
            var maxX = -1
            var minY = gridHeight
            var maxY = -1

            fun push(at: Int) {
                if (mask[at] && !seen[at]) {
                    seen[at] = true
                    queue[tail++] = at
                }
            }

            while (head < tail) {
                val index = queue[head++]
                count++

                val cy = index / gridWidth
                val cx = index - cy * gridWidth

                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy

                if (cx > 0) push(index - 1)
                if (cx < gridWidth - 1) push(index + 1)
                if (cy > 0) push(index - gridWidth)
                if (cy < gridHeight - 1) push(index + gridWidth)
            }

            if (count < 6) continue

            val cellsWide = maxX - minX + 1
            val cellsHigh = maxY - minY + 1

            result += Component(
                RectF(
                    (minX * STEP).toFloat(),
                    (minY * STEP).toFloat(),
                    minOf(width, (maxX + 1) * STEP).toFloat(),
                    minOf(height, (maxY + 1) * STEP).toFloat(),
                ),
                count.toFloat() / (cellsWide * cellsHigh),
            )
        }

        return result
    }
}
