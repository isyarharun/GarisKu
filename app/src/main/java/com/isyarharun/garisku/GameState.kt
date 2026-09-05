package com.isyarharun.garisku

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/** Game mode. */
enum class GameMode { SIMPLE, CHALLENGE }

data class Position(val row: Int, val col: Int) {
    fun isAdjacentTo(other: Position): Boolean {
        val dr = abs(row - other.row)
        val dc = abs(col - other.col)
        return (dr == 1 && dc == 0) || (dr == 0 && dc == 1)
    }
}

/**
 * An edge between two orthogonally-adjacent cells that cannot be crossed
 * (Zip-style wall). Blocks movement across that shared side while both cells
 * remain open.
 */
data class WallEdge(val a: Position, val b: Position) {
    init { require(a.isAdjacentTo(b)) }
    fun connects(x: Position, y: Position): Boolean =
        (a == x && b == y) || (a == y && b == x)
}

data class Cell(
    val number: Int? = null,
    val isVisited: Boolean = false
)

/**
 * Game state with full trail dragging, undo, and a simple timer.
 *
 * Modes:
 * - SIMPLE: win when all numbers connected in order.
 * - CHALLENGE: grid has brick/blocked cells (impassable). Player must drag a
 *              path that visits ALL open (non-brick) cells, connecting the
 *              numbers in order, winding around the blocks.
 */
class GameState(
    val rows: Int,
    val cols: Int,
    val numberPositions: Map<Int, Position>,
    val totalNumbers: Int,
    val mode: GameMode = GameMode.SIMPLE,
    /** Brick cells that block the path (challenge mode). */
    val blocks: Set<Position> = emptySet(),
    /** Zip-style edge walls that block crossing between two adjacent cells. */
    val edgeWalls: Set<WallEdge> = emptySet(),
    /** Cumulative elapsed seconds carried over from previous attempts. */
    initialElapsedSeconds: Int = 0
) {
    var grid by mutableStateOf(createEmptyGrid())
        private set

    var path by mutableStateOf<List<Position>>(emptyList())
        private set

    var isComplete by mutableStateOf(false)
        private set

    var hasError by mutableStateOf(false)
        private set

    /** Timer: cumulative elapsed seconds across retries/sessions for this level. */
    var elapsedSeconds by mutableStateOf(initialElapsedSeconds)
        private set

    var timerStarted by mutableStateOf(false)
        private set

    val connectedNumbers: Int get() = path.count { grid[it.row][it.col].number != null }

    val nextNumber: Int get() = connectedNumbers + 1

    /** Number of open (non-brick) cells the player must cover. */
    val openCellCount: Int get() = rows * cols - blocks.size

    val isBlocked: (Position) -> Boolean = { blocks.contains(it) }

    fun isNumbered(pos: Position): Boolean = grid[pos.row][pos.col].number != null

    private fun createEmptyGrid(): List<List<Cell>> {
        val g = MutableList(rows) { MutableList(cols) { Cell() } }
        for ((number, pos) in numberPositions) {
            g[pos.row][pos.col] = Cell(number = number)
        }
        return g
    }

    fun tryConnect(row: Int, col: Int): Boolean {
        if (isComplete) return false
        if (row !in 0 until rows || col !in 0 until cols) return false

        val pos = Position(row, col)
        if (pos in blocks) return false
        if (pos in path) return false

        val cell = grid[row][col]
        val number = cell.number

        if (path.isEmpty()) timerStarted = true

        if (path.isEmpty()) {
            if (number == 1) {
                path = listOf(pos)
                markVisited(row, col)
                checkCompletion()
                return true
            }
            return false
        }

        val lastPos = path.last()
        if (!pos.isAdjacentTo(lastPos)) return false
        // Edge wall (Zip-style) blocks crossing between these two cells.
        if (edgeWalls.any { it.connects(lastPos, pos) }) return false

        if (number != null && number != nextNumber) {
            triggerError()
            return false
        }

        path = path + pos
        markVisited(row, col)
        checkCompletion()
        return true
    }

    fun reset() {
        // Cumulative timer: elapsedSeconds & timerStarted are intentionally
        // NOT reset — "Ulang" restarts the puzzle, not the total clock.
        path = emptyList()
        isComplete = false
        hasError = false
        grid = createEmptyGrid()
    }

    fun tickSecond() {
        if (timerStarted && !isComplete) elapsedSeconds++
    }

    private fun triggerError() { hasError = true }

    fun clearError() { hasError = false }

    // ── Undo ─────────────────────────────────────────────────

    fun undo(): Boolean {
        if (path.isEmpty()) return false
        // Level sudah selesai → kemenangan tidak boleh dibatalkan oleh undo.
        if (isComplete) return false
        hasError = false

        val last = path.last()
        grid = grid.mapIndexed { r, rowList ->
            rowList.mapIndexed { c, cell ->
                if (r == last.row && c == last.col) cell.copy(isVisited = false) else cell
            }
        }
        path = path.dropLast(1)
        return true
    }

    /** True when the player covered every open cell but the route is wrong (challenge). */
    val isWrongRoute: Boolean
        get() = mode == GameMode.CHALLENGE &&
            connectedNumbers == totalNumbers &&
            path.size == openCellCount &&
            !isComplete

    private fun checkCompletion() {
        if (mode == GameMode.CHALLENGE) {
            // Challenge: cover every open cell AND finish ON the last number —
            // the final tile of the path must be number totalNumbers.
            val last = path.lastOrNull()
            val endsOnLastNumber =
                last != null && grid[last.row][last.col].number == totalNumbers
            if (connectedNumbers == totalNumbers &&
                path.size == openCellCount &&
                endsOnLastNumber
            ) {
                isComplete = true
            }
        } else {
            if (connectedNumbers == totalNumbers) {
                isComplete = true
            }
        }
    }

    private fun markVisited(row: Int, col: Int) {
        grid = grid.mapIndexed { r, rowList ->
            rowList.mapIndexed { c, cell ->
                if (r == row && c == col) cell.copy(isVisited = true) else cell
            }
        }
    }
}