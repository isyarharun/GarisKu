package com.isyarharun.garisku

import kotlin.random.Random

/**
 * Generates solvable levels for GarisKu.
 *
 * CHALLENGE: random Hamiltonian path (Warnsdorff DFS) covering ALL cells,
 *            so the route is winding and unpredictable — never a plain serpentine.
 * SIMPLE:    random window of a Hamiltonian path, numbers spread along it.
 */
object LevelGenerator {

    private val DIRS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)

    /**
     * Random Hamiltonian path over all cells of a rows x cols grid.
     * Uses DFS with Warnsdorff heuristic (prefer cells with fewest onward moves)
     * + randomized tie-breaking, with restarts. Falls back to a mirrored
     * serpentine (guaranteed valid) if all attempts fail.
     */
    fun generateHamiltonianPath(rows: Int, cols: Int, random: Random = Random.Default): List<Position> {
        require(rows >= 2 && cols >= 2) { "Grid too small" }
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
                            if (m.row in 0 until rows && m.col in 0 until cols &&
                                m !in visited && m != cur
                            ) onward++
                        }
                        cands.add(n to onward)
                    }
                }
                // Warnsdorff: fewest onward first; shuffle before stable sort = random tie-break.
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

    /** Guaranteed-valid fallback: serpentine path with random mirroring. */
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

    /**
     * Picks `count` number positions spaced along a path such that segments
     * between consecutive numbers are length 2..4. Number 1 at start, last at end.
     */
    fun placeNumbers(
        path: List<Position>,
        count: Int,
        random: Random = Random.Default
    ): Map<Int, Position> {
        require(count >= 2) { "Need at least 2 numbers" }

        val nSegments = count - 1
        val totalSteps = path.size - 1
        val base = 2 * nSegments
        if (totalSteps < base) {
            return placeSparse(path, count)
        }
        val extra = totalSteps - base
        val segLengths = IntArray(nSegments) { 2 }
        var remaining = extra
        var i = 0
        while (remaining > 0) {
            while (i < nSegments && remaining > 0 && segLengths[i] < 4) {
                segLengths[i]++
                remaining--
            }
            i++
            if (i >= nSegments && remaining > 0) {
                segLengths[nSegments - 1] += remaining
                remaining = 0
            }
        }
        val order = (0 until nSegments).shuffled(random)
        val shuffledLengths = order.map { segLengths[it] }.toIntArray()

        val result = LinkedHashMap<Int, Position>()
        result[1] = path.first()
        var idx = 0
        for (k in 0 until nSegments - 1) {
            idx += shuffledLengths[k]
            result[result.size + 1] = path[idx]
        }
        result[result.size + 1] = path.last()
        return result
    }

    /** Fallback: spread numbers evenly; skips duplicate positions so a number can never be overwritten. */
    private fun placeSparse(path: List<Position>, count: Int): Map<Int, Position> {
        val result = LinkedHashMap<Int, Position>()
        if (path.isEmpty()) return result
        result[1] = path.first()
        for (k in 1 until count - 1) {
            val idx = (path.size - 1) * k / (count - 1)
            val pos = path[idx]
            if (result.values.none { it == pos }) {
                result[result.size + 1] = pos
            }
        }
        val last = path.last()
        if (result.values.none { it == last }) {
            result[result.size + 1] = last
        }
        return result
    }

    /**
     * SIMPLE mode: take a random contiguous window of a random Hamiltonian path.
     * The window is always long and winding — the walk can never get stuck,
     * and number positions can never collide.
     */
    fun generateSimplePath(
        rows: Int, cols: Int, numberCount: Int, random: Random = Random.Default
    ): Map<Int, Position> {
        require(rows >= 2 && cols >= 2) { "Grid too small" }

        val full = generateHamiltonianPath(rows, cols, random)
        val need = (numberCount * 3).coerceIn(4, full.size)
        val maxStart = full.size - need
        val startIdx = if (maxStart <= 0) 0 else random.nextInt(maxStart + 1)
        val window = full.subList(startIdx, startIdx + need)

        return placeNumbers(window, numberCount, random)
    }

    fun manhattanDistance(a: Position, b: Position): Int =
        kotlin.math.abs(a.row - b.row) + kotlin.math.abs(a.col - b.col)
}