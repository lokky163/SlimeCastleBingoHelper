package com.slimecastle.bingohelper

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * Reads the current game screen through the already calibrated coordinates.
 *
 * Reads the calibrated wooden cells and checks the saved restart buttons. There is no
 * reward recognition: any screen without a readable board or restart buttons is dismissed.
 */
class GameVision(private val calibration: Calibration) {

    class Frame(
        val screenLuminance: Float,
        val menuOpen: Boolean,
        /** The two restart buttons of the end screen are visible. */
        val resetVisible: Boolean,
        val closed: BooleanArray,
        val closedCount: Int,
        val boardLuminance: Float,
    ) {
        /** A readable board with at least five confidently closed wooden cells. */
        val boardReadable: Boolean get() =
            !menuOpen && !resetVisible && closedCount >= MIN_CLOSED_BOARD_CELLS

    }

    private val reader = CellReader()

    fun analyze(bitmap: Bitmap, detectResetButtons: Boolean = true): Frame {
        val screen = screenLuminance(bitmap)
        val menuOpen = screen < MENU_LUMINANCE_THRESHOLD
        val closed = reader.read(bitmap, calibration.cells)
        val closedCount = closed.count { it }
        // A playable board wins over restart buttons even if its late-round colors happen
        // to match their saved rectangles. Only check restart when fewer than five cells
        // are confidently closed; otherwise an unrecognised screen is dismissed.
        val checkReset = detectResetButtons && !menuOpen && closedCount < MIN_CLOSED_BOARD_CELLS
        val boardLuminance = if (checkReset) {
            calibration.board?.let { meanLuminance(bitmap, it) } ?: 0f
        } else 0f
        val resetVisible = checkReset &&
            boardLuminance <= END_BOARD_LUMINANCE && matchesSavedReset(bitmap)

        return Frame(
            screenLuminance = screen,
            menuOpen = menuOpen,
            resetVisible = resetVisible,
            closed = closed,
            closedCount = closedCount,
            boardLuminance = boardLuminance,
        )
    }

    // ------------------------------------------------------------------ saved coordinates

    private fun matchesSavedReset(bitmap: Bitmap): Boolean {
        val rects = calibration.resetButtons
        if (rects.size != 2) return false
        // During an ordinary round both saved button positions overlap unopened golden
        // tiles. Their gold fraction is *also* about 0.7; the button face, however, is
        // noticeably darker (169 vs 188 on the reference screens).
        return rects.all {
            hasGoldenButtonShape(bitmap, it) &&
                meanLuminance(bitmap, it) < RESET_BUTTON_MAX_LUMINANCE
        }
    }

    /**
     * A restart button is a continuous golden bar. On a late-round board the saved button
     * rectangle straddles opened and closed cells: its total golden fraction can exceed 0.25,
     * but one or more fifths of its width have almost no golden pixels.
     */
    private fun hasGoldenButtonShape(bitmap: Bitmap, rect: RectF): Boolean {
        val x0 = rect.left.toInt().coerceIn(0, bitmap.width - 1)
        val y0 = rect.top.toInt().coerceIn(0, bitmap.height - 1)
        val x1 = rect.right.toInt().coerceIn(x0 + 1, bitmap.width)
        val y1 = rect.bottom.toInt().coerceIn(y0 + 1, bitmap.height)

        val width = x1 - x0
        val row = IntArray(width)
        val golden = IntArray(5)
        val total = IntArray(5)

        var y = y0
        while (y < y1) {
            bitmap.getPixels(row, 0, width, x0, y, width, 1)
            var x = 0
            while (x < width) {
                val band = (x * 5 / width).coerceAtMost(4)
                if (GoldenComponents.isGolden(row[x])) golden[band]++
                total[band]++
                x += 3
            }
            y += 3
        }

        val samples = total.sum()
        return samples > 0 && golden.sum().toFloat() / samples > RESET_GOLD_FRACTION &&
            total.indices.all { band ->
                total[band] > 0 && golden[band].toFloat() / total[band] >
                    RESET_MIN_BAND_GOLD_FRACTION
            }
    }

    // ------------------------------------------------------------------ calibration helpers

    class CellReader {
        private var pixels = IntArray(0)

        fun read(bitmap: Bitmap, cells: List<RectF>): BooleanArray {
            val closed = BooleanArray(cells.size)
            if (cells.isEmpty()) return closed

            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            for (rect in cells) {
                left = min(left, rect.left)
                top = min(top, rect.top)
                right = max(right, rect.right)
                bottom = max(bottom, rect.bottom)
            }

            val bx = left.toInt().coerceIn(0, bitmap.width - 1)
            val by = top.toInt().coerceIn(0, bitmap.height - 1)
            val bw = (right.toInt() - bx).coerceIn(1, bitmap.width - bx)
            val bh = (bottom.toInt() - by).coerceIn(1, bitmap.height - by)

            if (pixels.size < bw * bh) pixels = IntArray(bw * bh)
            bitmap.getPixels(pixels, 0, bw, bx, by, bw, bh)

            for (index in cells.indices) {
                val rect = cells[index]
                val insetX = (rect.width() * 0.10f).coerceAtLeast(2f)
                val insetY = (rect.height() * 0.10f).coerceAtLeast(2f)

                val sx = (rect.left + insetX).toInt() - bx
                val sy = (rect.top + insetY).toInt() - by
                val ex = (rect.right - insetX).toInt() - bx
                val ey = (rect.bottom - insetY).toInt() - by

                var warm = 0
                var total = 0
                var y = sy
                while (y < ey) {
                    val offset = y * bw
                    var x = sx
                    while (x < ex) {
                        if (isWoodenTile(pixels[offset + x])) warm++
                        total++
                        x += 2
                    }
                    y += 2
                }

                closed[index] = total > 0 && warm.toFloat() / total > CLOSED_WARM_FRACTION
            }

            return closed
        }
    }

    companion object {
        /** Below this average screen luminance a full-screen popup is on screen. */
        const val MENU_LUMINANCE_THRESHOLD = 75f

        /**
         * A frame darker than this is treated as a failed capture (the projection sometimes
         * delivers a black frame right after the display is created).
         */
        const val MIN_VALID_LUMINANCE = 8f

        /** End screen is darker than ordinary play (88 vs 147 on reference frames). */
        const val END_BOARD_LUMINANCE = 135f

        /** Fraction of warm pixels above which a cell is considered closed. */
        const val CLOSED_WARM_FRACTION = 0.8f

        /** Minimum visible wooden tiles required before auto player may tap on the board. */
        const val MIN_CLOSED_BOARD_CELLS = 5

        /** Golden fraction inside a saved restart button. */
        private const val RESET_GOLD_FRACTION = 0.50f
        private const val RESET_MIN_BAND_GOLD_FRACTION = 0.30f
        private const val RESET_BUTTON_MAX_LUMINANCE = 180f

        /** A warm, bright pixel - the colour of an unopened wooden tile. */
        fun isWoodenTile(color: Int): Boolean {
            val r = (color ushr 16) and 0xFF
            val g = (color ushr 8) and 0xFF
            val b = color and 0xFF
            return r > 165 && g > 115 && b < 200 && r > g + 15 && g > b + 25
        }

        /** Coarse mean luminance of the whole screenshot. */
        fun screenLuminance(bitmap: Bitmap): Float {
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) return 0f

            val row = IntArray(width)
            var sum = 0.0
            var count = 0

            var y = 0
            while (y < height) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                var x = 0
                while (x < width) {
                    val color = row[x]
                    sum += 0.299 * ((color ushr 16) and 0xFF) +
                        0.587 * ((color ushr 8) and 0xFF) +
                        0.114 * (color and 0xFF)
                    count++
                    x += 8
                }
                y += 16
            }

            return if (count == 0) 0f else (sum / count).toFloat()
        }

        fun meanLuminance(bitmap: Bitmap, rect: RectF): Float {
            if (bitmap.width < 1 || bitmap.height < 1) return 0f
            val x0 = rect.left.toInt().coerceIn(0, bitmap.width - 1)
            val y0 = rect.top.toInt().coerceIn(0, bitmap.height - 1)
            val x1 = rect.right.toInt().coerceIn(x0 + 1, bitmap.width)
            val y1 = rect.bottom.toInt().coerceIn(y0 + 1, bitmap.height)
            val width = x1 - x0
            val row = IntArray(width)
            var sum = 0.0
            var count = 0
            var y = y0
            while (y < y1) {
                bitmap.getPixels(row, 0, width, x0, y, width, 1)
                var x = 0
                while (x < width) {
                    val color = row[x]
                    sum += 0.299 * ((color ushr 16) and 0xFF) +
                        0.587 * ((color ushr 8) and 0xFF) +
                        0.114 * (color and 0xFF)
                    count++
                    x += 4
                }
                y += 4
            }
            return if (count == 0) 0f else (sum / count).toFloat()
        }

        /** The two wide golden restart buttons, sorted left to right, or `null`. */
        fun detectResetButtonRects(bitmap: Bitmap): List<RectF>? {
            val minWidth = bitmap.width * 0.17f
            val maxWidth = bitmap.width * 0.29f
            val minHeight = bitmap.height * 0.025f
            val maxHeight = bitmap.height * 0.09f
            val minY = bitmap.height * 0.38f
            val maxY = bitmap.height * 0.72f

            val candidates = ArrayList<GoldenComponents.Component>()
            for (component in components(bitmap)) {
                val rect = component.rect
                val w = rect.width()
                val h = rect.height()
                if (w < minWidth || w > maxWidth) continue
                if (h < minHeight || h > maxHeight) continue
                val aspect = w / h
                if (aspect < 1.6f || aspect > 2.6f) continue
                if (component.fill < 0.45f || component.fill > 0.95f) continue
                if (rect.centerY() < minY || rect.centerY() > maxY) continue
                candidates += component
            }

            if (candidates.size != 2) return null
            val widths = candidates.map { it.rect.width() }
            if (widths.maxOrNull()!! - widths.minOrNull()!! > widths.maxOrNull()!! * 0.15f) {
                return null
            }
            return candidates.sortedBy { it.rect.left }.map { it.rect }
        }

        private fun components(bitmap: Bitmap): List<GoldenComponents.Component> =
            GoldenComponents.find(bitmap)

    }
}
