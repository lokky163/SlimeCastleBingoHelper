package com.slimecastle.bingohelper.strategy

/**
 * Direct port of the strategy from `index.ts`.
 *
 * The board is a 5x5 grid. Every cell belongs to a row and a column, and the two diagonals
 * additionally collect their cells. All 12 lines are stored as columns -> rows -> diagonals,
 * which is the order the original algorithm relies on when it generates patterns.
 */
const val FIELD_SIZE = 5
const val FIELD_CELLS = FIELD_SIZE * FIELD_SIZE

class Line {
    val cells = ArrayList<Cell>(FIELD_SIZE)

    val isComplete: Boolean
        get() {
            for (cell in cells) {
                if (!cell.used) return false
            }
            return true
        }
}

class Cell(val id: Int, val row: Line, val column: Line) {
    var used = false

    val lines = ArrayList<Line>(4)
    val diagonals = ArrayList<Line>(2)

    init {
        lines.add(row)
        lines.add(column)
    }

    /** Completion counts of all lines crossing this cell, sorted from most to least complete. */
    fun lineCompletionDesc(): IntArray {
        val counts = IntArray(lines.size)
        for (i in lines.indices) {
            var count = 0
            for (cell in lines[i].cells) {
                if (cell.used) count++
            }
            counts[i] = count
        }
        counts.sortDescending()
        return counts
    }
}

class Field {
    val rows = ArrayList<Line>(FIELD_SIZE)
    val columns = ArrayList<Line>(FIELD_SIZE)
    val diagonals = arrayListOf(Line(), Line())
    val cells = ArrayList<Cell>(FIELD_CELLS)
    val lines: List<Line>

    init {
        for (i in 0 until FIELD_SIZE) {
            val row = Line()
            rows.add(row)

            for (j in 0 until FIELD_SIZE) {
                if (i == 0) {
                    columns.add(Line())
                }

                val column = columns[j]
                val cell = Cell(i * FIELD_SIZE + j + 1, row, column)
                row.cells.add(cell)
                column.cells.add(cell)
                cells.add(cell)

                if (i == j) {
                    diagonals[0].cells.add(cell)
                    cell.lines.add(diagonals[0])
                    cell.diagonals.add(diagonals[0])
                }

                if (i + j + 1 == FIELD_SIZE) {
                    diagonals[1].cells.add(cell)
                    cell.lines.add(diagonals[1])
                    cell.diagonals.add(diagonals[1])
                }
            }
        }

        lines = columns + rows + diagonals
    }

    val linesCompleted: Int
        get() = lines.count { it.isComplete }

    val usedCellCount: Int
        get() = cells.count { it.used }

    fun reset() {
        for (cell in cells) {
            cell.used = false
        }
    }

    fun clone(): Field {
        val clone = Field()
        for (i in cells.indices) {
            clone.cells[i].used = cells[i].used
        }
        return clone
    }

    /** True when the pattern can still be completed within [maxMoves] openings. */
    fun finishable(target: BooleanArray, maxMoves: Int): Boolean {
        var movesNeeded = 0
        for (i in cells.indices) {
            if (target[i] && !cells[i].used) {
                movesNeeded++
                if (movesNeeded > maxMoves) return false
            }
        }
        return true
    }

    fun print(): String = buildString {
        for (row in rows) {
            for (cell in row.cells) {
                append(if (cell.used) "x " else "o ")
            }
            append('\n')
        }
    }
}

object BingoStrategy {

    private class PatternLib(
        val lines: Int,
        val patterns: List<BooleanArray>,
        val remainingMovesOffset: Int,
    )

    /** Patterns stored as 25 used-flags, grouped by the number of cells they contain. */
    private val fourLinePatterns = createPatterns(4)
    private val threeLinePatterns = createPatterns(3)
    private val twoLinePatterns = createPatterns(2)

    private val allFourLinePatterns =
        fourLinePatterns[14].orEmpty() + fourLinePatterns[15].orEmpty() + fourLinePatterns[16].orEmpty()
    private val allThreeLinePatterns =
        threeLinePatterns[12].orEmpty() + threeLinePatterns[13].orEmpty() + threeLinePatterns[15].orEmpty()
    private val allTwoLinePatterns =
        twoLinePatterns.toSortedMap().values.flatten()

    private val patternLibs = listOf(
        PatternLib(4, allFourLinePatterns, 2),
        PatternLib(4, allFourLinePatterns, 1),
        PatternLib(4, allFourLinePatterns, 0),
        PatternLib(3, allThreeLinePatterns, 1),
        PatternLib(3, allThreeLinePatterns, 0),
        PatternLib(2, allTwoLinePatterns, 1),
        PatternLib(2, allTwoLinePatterns, 0),
    )

    @Volatile
    var lastUsedPatterns: String = "—"
        private set

    @Volatile
    var lastUsedHeatmap: IntArray? = null
        private set

    /**
     * Returns the cell of the next player move.
     *
     * [remainingMoves] has the same meaning as in `index.ts`: how many cells may still be
     * opened in total (player + computer) until the round ends.
     */
    fun nextMove(field: Field, remainingMoves: Int): Cell {
        var moves = remainingMoves
        var heatmap = getHeatMapOfBestPatterns(field, moves)
        lastUsedHeatmap = heatmap

        if (heatmap == null) {
            return getMostCompleteCell(field.cells)
        }

        var baseCells: List<Cell> = field.cells
        var hotCells: MutableList<Cell> = ArrayList()
        var guard = 0

        while (true) {
            val currentHeatmap = heatmap ?: break

            var maxHeat = Int.MIN_VALUE
            hotCells = ArrayList()

            for (cell in baseCells) {
                if (cell.used) continue

                val heat = currentHeatmap[cell.id - 1]

                if (heat > maxHeat) {
                    maxHeat = heat
                    hotCells.clear()
                    hotCells.add(cell)
                } else if (heat == maxHeat) {
                    hotCells.add(cell)
                }
            }

            if (hotCells.size == 1) break

            moves--
            heatmap = getHeatMapOfBestPatterns(field, moves, false)

            if (heatmap == null || moves == 1) break

            baseCells = hotCells
            hotCells = ArrayList()

            if (++guard > 32) break
        }

        return if (hotCells.isEmpty()) getMostCompleteCell(field.cells) else getMostCompleteCell(hotCells)
    }

    private fun getHeatMapOfBestPatterns(
        field: Field,
        remainingMoves: Int,
        updateLastUsed: Boolean = true,
    ): IntArray? {
        var patterns: List<BooleanArray> = emptyList()

        for (lib in patternLibs) {
            patterns = lib.patterns.filter {
                field.finishable(it, remainingMoves - lib.remainingMovesOffset)
            }

            if (patterns.isNotEmpty()) {
                if (updateLastUsed) {
                    lastUsedPatterns =
                        "${lib.lines}-line patterns finishable with " +
                            "${16 - lib.remainingMovesOffset} moves"
                }
                break
            }
        }

        if (patterns.isEmpty()) {
            if (updateLastUsed) {
                lastUsedPatterns = "None - only 1-line solutions possible."
            }
            return null
        }

        return createHeatmap(patterns)
    }

    private fun createHeatmap(patterns: List<BooleanArray>): IntArray {
        val heatmap = IntArray(FIELD_CELLS)
        for (pattern in patterns) {
            for (i in pattern.indices) {
                if (pattern[i]) heatmap[i]++
            }
        }
        return heatmap
    }

    /** Unused cell with the most complete crossing lines; ties keep the first one found. */
    fun getMostCompleteCell(cells: List<Cell>): Cell {
        var bestCell: Cell? = null
        var bestCounts: IntArray? = null

        for (cell in cells) {
            if (cell.used) continue

            val counts = cell.lineCompletionDesc()
            val best = bestCounts

            if (best == null) {
                bestCell = cell
                bestCounts = counts
                continue
            }

            val size = maxOf(counts.size, best.size)
            for (i in 0 until size) {
                val a = if (i < counts.size) counts[i] else Int.MIN_VALUE
                val b = if (i < best.size) best[i] else Int.MIN_VALUE

                if (a > b) {
                    bestCell = cell
                    bestCounts = counts
                    break
                } else if (a < b) {
                    break
                }
            }
        }

        return bestCell ?: cells.firstOrNull { !it.used } ?: cells.first()
    }

    /**
     * Generates every combination of [targetLines] lines and stores the resulting cell sets,
     * grouped by how many cells they use. This mirrors `createPatterns()` in `index.ts`.
     */
    private fun createPatterns(targetLines: Int, maxMoves: Int = 16): Map<Int, List<BooleanArray>> {
        val field = Field()
        val result = sortedMapOf<Int, MutableList<BooleanArray>>()
        val used = BooleanArray(FIELD_CELLS)
        val indices = ArrayList<Int>()

        fun walk(line: Int, startIndex: Int) {
            var index = startIndex

            while (index < field.lines.size - targetLines + line) {
                indices.add(index)

                if (line == targetLines) {
                    used.fill(false)
                    for (lineIndex in indices) {
                        for (cell in field.lines[lineIndex].cells) {
                            used[cell.id - 1] = true
                        }
                    }

                    var moves = 0
                    for (isUsed in used) {
                        if (isUsed) moves++
                    }

                    if (moves <= maxMoves) {
                        result.getOrPut(moves) { ArrayList() }.add(used.copyOf())
                    }
                } else {
                    walk(line + 1, index + 1)
                }

                indices.removeAt(indices.size - 1)
                index++
            }
        }

        walk(1, 0)
        return result
    }

    /** Number of patterns in every library - useful for debugging the port. */
    fun librarySizes(): List<Int> = patternLibs.map { it.patterns.size }
}
