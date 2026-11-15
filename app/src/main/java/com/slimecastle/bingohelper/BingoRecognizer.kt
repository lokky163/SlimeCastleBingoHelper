package com.slimecastle.bingohelper

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Finds the 5x5 bingo board on a screenshot of the game.
 *
 * The game UI is anchored to the screen, so the board always occupies roughly the same
 * area of the screen. The exact tile boundaries are taken from the image itself:
 * unopened tiles are bright, warm wooden squares while the gaps between them are dark blue.
 *
 * The result is a list of 25 rectangles in screenshot pixel coordinates, ordered row by row.
 */
object BingoRecognizer {

    class RecognitionException(message: String) : Exception(message)

    data class Result(
        val cells: List<RectF>,
        val board: RectF,
        val cellWidth: Float,
        val cellHeight: Float,
    )

    private const val GRID_SIZE = 5

    /** Fraction of the screen that is searched for the board. */
    private const val ROI_LEFT = 0.09f
    private const val ROI_TOP = 0.34f
    private const val ROI_RIGHT = 0.91f
    private const val ROI_BOTTOM = 0.76f

    fun recognize(screenshot: Bitmap): Result {
        require(screenshot.width > 0 && screenshot.height > 0) { "Empty screenshot" }

        val x0 = (screenshot.width * ROI_LEFT).roundToInt().coerceIn(0, screenshot.width - 1)
        val x1 = (screenshot.width * ROI_RIGHT).roundToInt().coerceIn(x0 + 1, screenshot.width)
        val y0 = (screenshot.height * ROI_TOP).roundToInt().coerceIn(0, screenshot.height - 1)
        val y1 = (screenshot.height * ROI_BOTTOM).roundToInt().coerceIn(y0 + 1, screenshot.height)

        val roiWidth = x1 - x0
        val roiHeight = y1 - y0
        val pixels = IntArray(roiWidth * roiHeight)
        screenshot.getPixels(pixels, 0, roiWidth, x0, y0, roiWidth, roiHeight)

        val columnProfile = IntArray(roiWidth)
        val rowProfile = IntArray(roiHeight)

        for (y in 0 until roiHeight) {
            val rowOffset = y * roiWidth
            for (x in 0 until roiWidth) {
                if (isWoodenTile(pixels[rowOffset + x])) {
                    columnProfile[x]++
                    rowProfile[y]++
                }
            }
        }

        val columns = findBands(
            profile = columnProfile,
            minLength = (roiWidth * 0.04f).roundToInt(),
            gapTolerance = (roiWidth * 0.008f).roundToInt().coerceAtLeast(2),
        )
        val rows = findBands(
            profile = rowProfile,
            minLength = (roiHeight * 0.04f).roundToInt(),
            gapTolerance = (roiHeight * 0.008f).roundToInt().coerceAtLeast(2),
        )

        validateBands(columns, "столбцы")
        validateBands(rows, "строки")

        val cells = ArrayList<RectF>(GRID_SIZE * GRID_SIZE)
        for (row in rows) {
            for (column in columns) {
                cells += RectF(
                    (x0 + column.first).toFloat(),
                    (y0 + row.first).toFloat(),
                    (x0 + column.last + 1).toFloat(),
                    (y0 + row.last + 1).toFloat(),
                )
            }
        }

        val board = RectF(
            (x0 + columns.first().first).toFloat(),
            (y0 + rows.first().first).toFloat(),
            (x0 + columns.last().last + 1).toFloat(),
            (y0 + rows.last().last + 1).toFloat(),
        )

        val cellWidth = columns.sumOf { it.last - it.first + 1 }.toFloat() / GRID_SIZE
        val cellHeight = rows.sumOf { it.last - it.first + 1 }.toFloat() / GRID_SIZE

        return Result(cells, board, cellWidth, cellHeight)
    }

    /**
     * Returns connected regions of the profile that contain at least [minLength] pixels and
     * are separated by more than [gapTolerance] empty pixels. Small gaps (anti-aliasing) are
     * merged so that a single tile produces exactly one band.
     */
    private fun findBands(profile: IntArray, minLength: Int, gapTolerance: Int): List<IntRange> {
        val max = profile.maxOrNull() ?: 0
        if (max <= 0) return emptyList()

        val threshold = max * 0.5f
        val runs = ArrayList<IntRange>()
        var start = -1

        for (i in profile.indices) {
            val above = profile[i] > threshold
            if (above) {
                if (start < 0) start = i
            } else if (start >= 0) {
                runs += start until i
                start = -1
            }
        }
        if (start >= 0) runs += start until profile.size

        val merged = ArrayList<IntRange>()
        for (run in runs) {
            val last = merged.lastOrNull()
            if (last != null && run.first - last.last <= gapTolerance) {
                merged[merged.size - 1] = last.first..run.last
            } else {
                merged += run
            }
        }

        return merged.filter { it.last - it.first + 1 >= minLength }
    }

    private fun validateBands(bands: List<IntRange>, name: String) {
        if (bands.size != GRID_SIZE) {
            throw RecognitionException("сетка не найдена: $name = ${bands.size} (ожидалось $GRID_SIZE)")
        }

        val sizes = bands.map { it.last - it.first + 1 }
        val minSize = sizes.minOrNull() ?: 0
        val maxSize = sizes.maxOrNull() ?: 0
        if (maxSize.toFloat() > minSize * 1.35f) {
            throw RecognitionException("неравномерные размеры: $name ($minSize..$maxSize px)")
        }

        val centers = bands.map { (it.first + it.last) / 2f }
        val pitches = centers.zipWithNext { a, b -> b - a }
        val averagePitch = pitches.average().toFloat()
        if (averagePitch <= 0f || pitches.any { abs(it - averagePitch) > averagePitch * 0.15f }) {
            throw RecognitionException("неравномерный шаг сетки")
        }
    }

    /** A warm, bright pixel - the colour of an unopened wooden tile. */
    private fun isWoodenTile(color: Int): Boolean {
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF

        return r > 165 && g > 115 && b < 200 && r > g + 15 && g > b + 25
    }
}
