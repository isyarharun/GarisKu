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

data class Cell(
    val number: Int? = null,
    val isVisited: Boolean = false
)

/**
 * Game state with full trail dragging, undo, and a simple timer.
 *
 * Modes:
 * - SIMPLE: win when all numbers connected in order.
 * - CHALLENGE: win when all numbers connected in order AND every cell is filled.
 */
class GameState(
    val rows: Int,
    val cols: Int,
    val numberPositions: Map<Int, Position>,
    val totalNumbers: Int,
    val mode: GameMode = GameMode.SIMPLE
) {
    var grid by mutableStateOf(createEmptyGrid())
        private set

    var path by mutableStateOf<List<Position>>(emptyList())
        private set

    var isComplete by mutableStateOf(false)
        private set

    var hasError by mutableStateOf(false)
        private set

    /** Timer: elapsed seconds since first connection. */
    var elapsedSeconds by mutableStateOf(0)
        private set

    var timerStarted by mutableStateOf(false)
        private set

    val connectedNumbers: Int get() = path.count { grid[it.row][it.col].number != null }

    val nextNumber: Int get() = connectedNumbers + 1

    /** How many total cells must be filled to win in challenge mode. */
    val totalCells: Int get() = rows * cols

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
        path = emptyList()
        isComplete = false
        hasError = false
        elapsedSeconds = 0
        timerStarted = false
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
        if (isComplete) isComplete = false
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

    private fun checkCompletion() {
        if (connectedNumbers != totalNumbers) return

        if (mode == GameMode.CHALLENGE) {
            // Challenge: also require every cell filled.
            if (path.size == totalCells) {
                isComplete = true
            }
        } else {
            isComplete = true
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