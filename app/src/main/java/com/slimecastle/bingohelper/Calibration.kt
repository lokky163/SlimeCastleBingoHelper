package com.slimecastle.bingohelper

import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the helper knows about the game UI, in screenshot pixels.
 *
 * The coordinates are calibrated once (the game layout never moves) and reused until the
 * screen resolution changes or the user resets the calibration.
 */
data class Calibration(
    val screenWidth: Int,
    val screenHeight: Int,
    val board: RectF?,
    val cells: List<RectF>,
    /** The two restart buttons, sorted left (x1) to right (x10). */
    val resetButtons: List<RectF>,
) {

    val hasBoard: Boolean get() = board != null && cells.size == FIELD_CELL_COUNT
    val hasReset: Boolean get() = resetButtons.size == 2
    val ready: Boolean get() = hasBoard && hasReset

    fun matchesScreen(width: Int, height: Int): Boolean =
        screenWidth == width && screenHeight == height

    fun missingLabels(): List<String> = buildList {
        if (!hasBoard) add("поле")
        if (!hasReset) add("кнопки перезапуска")
    }

    fun toJson(): String = JSONObject().apply {
        put("w", screenWidth)
        put("h", screenHeight)
        put("board", board?.toArray() ?: JSONObject.NULL)
        put("cells", JSONArray().apply { cells.forEach { put(it.toArray()) } })
        put("reset", JSONArray().apply { resetButtons.forEach { put(it.toArray()) } })
    }.toString()

    companion object {
        const val FIELD_CELL_COUNT = 25

        fun empty(width: Int, height: Int) = Calibration(
            screenWidth = width,
            screenHeight = height,
            board = null,
            cells = emptyList(),
            resetButtons = emptyList(),
        )

        fun fromJson(json: JSONObject): Calibration? {
            return try {
                val cells = json.getJSONArray("cells").toRects()
                // Older versions also stored reward rectangles. Ignore them so the existing
                // board and restart calibration survives an app update.
                val width = json.getInt("w")
                val height = json.getInt("h")
                if (width <= 0 || height <= 0 || cells.size !in listOf(0, FIELD_CELL_COUNT)) {
                    return null
                }
                val board = json.opt("board").let {
                    if (it is JSONArray) it.toRect() else null
                }
                val reset = json.getJSONArray("reset").toRects()
                if (reset.size !in listOf(0, 2)) return null
                val allRects = cells + reset + listOfNotNull(board)
                if (allRects.any { rect ->
                        rect.left < 0f || rect.top < 0f || rect.right > width ||
                            rect.bottom > height || rect.width() <= 0f || rect.height() <= 0f
                    }
                ) return null
                Calibration(
                    screenWidth = width,
                    screenHeight = height,
                    board = board,
                    cells = cells,
                    resetButtons = reset,
                )
            } catch (_: Throwable) {
                null
            }
        }

        private fun JSONArray.toRects(): List<RectF> {
            val result = ArrayList<RectF>(length())
            for (i in 0 until length()) {
                result += getJSONArray(i).toRect()
            }
            return result
        }

        private fun JSONArray.toRect(): RectF = RectF(
            getDouble(0).toFloat(),
            getDouble(1).toFloat(),
            getDouble(2).toFloat(),
            getDouble(3).toFloat(),
        )

        private fun RectF.toArray(): JSONArray = JSONArray()
            .put(left.toDouble())
            .put(top.toDouble())
            .put(right.toDouble())
            .put(bottom.toDouble())
    }
}
