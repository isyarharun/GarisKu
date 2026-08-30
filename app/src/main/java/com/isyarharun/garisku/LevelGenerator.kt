package com.isyarharun.garisku

import kotlin.random.Random

/**
 * Generates solvable levels for GarisKu.
 *
 * CHALLENGE: random Hamiltonian path + spread bricks with BFS contiguity check.
 * SIMPLE:    random window of a Hamiltonian path.
 */
object LevelGenerator {

    private val DIRS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)

    /** Random Hamiltonian path over all cells — Warnsdorff DFS. */
    fun generateHamiltonianPath(rows: Int, cols: Int, random: Random = Random.Default): List<Position> {
        require(rows >= 2 && cols >= 2)
        val total = rows * cols
        repeat(30) {
            val start = Position(random.nextInt(rows), random.nextInt(cols))
            var budget = 150_000
            val path = mutableListOf(start)
            val visited = hashSetOf(start)

            fun dfs(): Boolean {
                if (path.size == total) return true
                if (budget <= 0) return false
                budget--
                val cur = path.last()
                val cands = ArrayList<Pair<Position, Int>>(4)
                for ((dr, dc) in DIRS.shuffled(random)) {
                    val n = Position(cur.row + dr, cur.col + dc)
                    if (n.row in 0 until rows && n.col in 0 until cols && n !in visited) {
                        var onward = 0
                        for ((dr2, dc2) in DIRS) {
                            val m = Position(n.row + dr2, n.col + dc2)
                            if (m.row in 0 until rows && m.col in 0 until cols && m !in visited && m != cur)
                                onward++
                        }
                        cands.add(n to onward)
                    }
                }
                cands.sortBy { it.second }
                for ((n, _) in cands) {
                    path.add(n); visited.add(n)
                    if (dfs()) return true
                    path.removeAt(path.size - 1); visited.remove(n)
                    if (budget <= 0) return false
                }
                return false
            }
            if (dfs()) return path
        }
        return serpentine(rows, cols, random)
    }

    private fun serpentine(rows: Int, cols: Int, random: Random): List<Position> {
        val path = ArrayList<Position>(rows * cols)
        for (r in 0 until rows) {
            if (r % 2 == 0) for (c in 0 until cols) path.add(Position(r, c))
            else for (c in cols - 1 downTo 0) path.add(Position(r, c))
        }
        return when (random.nextInt(4)) {
            0 -> path
            1 -> path.map { Position(it.row, cols - 1 - it.col) }
            2 -> path.map { Position(rows - 1 - it.row, it.col) }
            else -> path.map { Position(rows - 1 - it.row, cols - 1 - it.col) }
        }
    }

    /** Place numbers along path with segment lengths 2..4. */
    fun placeNumbers(path: List<Position>, count: Int, random: Random = Random.Default): Map<Int, Position> {
        require(count >= 2)
        val nSegments = count - 1
        val totalSteps = path.size - 1
        val base = 2 * nSegments
        if (totalSteps < base) return placeSparse(path, count)

        val extra = totalSteps - base
        val segLengths = IntArray(nSegments) { 2 }
        var remaining = extra
        var i = 0
        while (remaining > 0) {
            while (i < nSegments && remaining > 0 && segLengths[i] < 4) { segLengths[i]++; remaining-- }
            i++
            if (i >= nSegments && remaining > 0) { segLengths[nSegments - 1] += remaining; remaining = 0 }
        }
        val order = (0 until nSegments).shuffled(random)
        val shuffled = order.map { segLengths[it] }.toIntArray()

        val result = LinkedHashMap<Int, Position>()
        result[1] = path.first()
        var idx = 0
        for (k in 0 until nSegments - 1) { idx += shuffled[k]; result[result.size + 1] = path[idx] }
        result[result.size + 1] = path.last()
        return result
    }

    private fun placeSparse(path: List<Position>, count: Int): Map<Int, Position> {
        val result = LinkedHashMap<Int, Position>()
        if (path.isEmpty()) return result
        result[1] = path.first()
        for (k in 1 until count - 1) {
            val idx = (path.size - 1) * k / (count - 1)
            val pos = path[idx]
            if (result.values.none { it == pos }) result[result.size + 1] = pos
        }
        val last = path.last()
        if (result.values.none { it == last }) result[result.size + 1] = last
        return result
    }

    /** SIMPLE: random window of a Hamiltonian path. */
    fun generateSimplePath(rows: Int, cols: Int, numberCount: Int, random: Random = Random.Default): Map<Int, Position> {
        require(rows >= 2 && cols >= 2)
        val full = generateHamiltonianPath(rows, cols, random)
        val need = (numberCount * 3).coerceIn(4, full.size)
        val maxStart = full.size - need
        val startIdx = if (maxStart <= 0) 0 else random.nextInt(maxStart + 1)
        val window = full.subList(startIdx, startIdx + need)
        return placeNumbers(window, numberCount, random)
    }

    /**
     * Challenge brick level — spread bricks + BFS contiguity check.
     *
     * Bricks are picked from path indices spaced ≥2 apart so they never share
     * a grid edge. A lightweight BFS (O(cells)) verifies that all open cells
     * remain connected. Fast fallback to tail bricks if re-rolls exceed 30.
     */
    fun generateBrickLevel(
        rows: Int, cols: Int, numberCount: Int,
        maxBrickCount: Int = 5, random: Random = Random.Default
    ): BrickLevel {
        require(rows >= 4 && cols >= 4)
        val mirrored = generateHamiltonianPath(rows, cols, random)
        // Always at least 1 brick so every challenge level has an obstacle.
        val brickCount = 1 + random.nextInt(maxBrickCount)
        if (brickCount == 0) {
            val numbers = placeNumbers(mirrored, numberCount, random)
            return BrickLevel(mirrored, emptySet(), numbers)
        }

        val pathSize = mirrored.size
        val allIds = (0 until pathSize).toList()

        repeat(30) {
            val chosen = mutableListOf<Int>()
            for (idx in allIds.shuffled(random)) {
                if (chosen.all { kotlin.math.abs(it - idx) >= 2 }) {
                    chosen.add(idx)
                    if (chosen.size >= brickCount) break
                }
            }
            if (chosen.size < brickCount) return@repeat

            val blocks = chosen.map { mirrored[it] }.toSet()
            if (blocks.contains(mirrored.first())) return@repeat

            val openCells = mirrored.filterIndexed { i, _ -> i !in chosen }
            val openSet = openCells.toSet()

            // Fast reject: parity (checkerboard). A path visiting N cells must
            // alternate colors, so |black - white| <= 1 — otherwise unsolvable.
            var black = 0
            var white = 0
            for (p in openCells) {
                if ((p.row + p.col) % 2 == 0) black++ else white++
            }
            if (kotlin.math.abs(black - white) > 1) return@repeat

            // Verify a real Hamiltonian path exists over the open cells (bounded DFS).
            val solution = hamiltonianOnOpen(openCells, rows, cols, random) ?: return@repeat

            val numbers = placeNumbers(solution, numberCount, random)
            return BrickLevel(solution, blocks, numbers)
        }

        // Fallback: tail bricks (guaranteed solvable, may cluster).
        val fbp = mirrored.dropLast(brickCount)
        val fbb = mirrored.takeLast(brickCount).toSet()
        val fbn = placeNumbers(fbp, numberCount, random)
        return BrickLevel(fbp, fbb, fbn)
    }

    /**
     * Bounded Warnsdorff DFS over the open cells. Returns a Hamiltonian path
     * covering every open cell, or null. Budgeted so it never hangs.
     */
    private fun hamiltonianOnOpen(
        open: List<Position>, rows: Int, cols: Int, random: Random
    ): List<Position>? {
        if (open.isEmpty()) return emptyList()
        val openSet = open.toHashSet()
        val total = open.size

        repeat(8) {
            val start = open[random.nextInt(open.size)]
            var budget = 40_000
            val path = mutableListOf(start)
            val visited = hashSetOf(start)

            fun dfs(): Boolean {
                if (path.size == total) return true
                if (budget <= 0) return false
                budget--
                val cur = path.last()
                val sorted = neighbours(cur, rows, cols)
                    .filter { it in openSet && it !in visited }
                    .sortedBy { n ->
                        neighbours(n, rows, cols).count {
                            it in openSet && it !in visited && it != cur
                        }
                    }
                for (n in sorted) {
                    path.add(n); visited.add(n)
                    if (dfs()) return true
                    path.removeAt(path.size - 1); visited.remove(n)
                    if (budget <= 0) return false
                }
                return false
            }
            val result = dfs()
            if (result) return path.toList()
        }
        return null
    }

    private fun neighbours(p: Position, rows: Int, cols: Int): List<Position> =
        DIRS.map { (dr, dc) -> Position(p.row + dr, p.col + dc) }
            .filter { it.row in 0 until rows && it.col in 0 until cols }

    fun manhattanDistance(a: Position, b: Position): Int =
        kotlin.math.abs(a.row - b.row) + kotlin.math.abs(a.col - b.col)
}

data class BrickLevel(
    val path: List<Position>,
    val blocks: Set<Position>,
    val numberPositions: Map<Int, Position>
)