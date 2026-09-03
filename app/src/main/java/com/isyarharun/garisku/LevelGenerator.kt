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

    /**
     * Deterministic seed for a given mode + level, so the same level always
     * produces the same puzzle. Combines the mode ordinal and level number
     * into a stable Long.
     */
    fun seedFor(mode: GameMode, level: Int): Long {
        val modeSalt = when (mode) {
            GameMode.SIMPLE -> 0x517CC1B727220A95L
            GameMode.CHALLENGE -> 0x6A09E667F3BCC909L
        }
        var h = modeSalt xor level.toLong()
        h *= 0x100000001B3L
        h = h xor (h ushr 33)
        h *= 0x100000001B3L
        h = h xor (h ushr 33)
        return h
    }

    /** Random Hamiltonian path over all cells — Warnsdorff DFS. */
    fun generateHamiltonianPath(rows: Int, cols: Int, seed: Long): List<Position> {
        val random = Random(seed)
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
    fun placeNumbers(path: List<Position>, count: Int, seed: Long, minSeg: Int = 2, maxSeg: Int = 4): Map<Int, Position> {
        val random = Random(seed)
        require(count >= 2)
        require(minSeg >= 1 && maxSeg >= minSeg)
        val nSegments = count - 1
        val totalSteps = path.size - 1
        val base = minSeg * nSegments
        if (totalSteps < base) return placeSparse(path, count)

        val extra = totalSteps - base
        val segLengths = IntArray(nSegments) { minSeg }
        var remaining = extra
        var i = 0
        val cap = maxSeg - minSeg
        while (remaining > 0) {
            while (i < nSegments && remaining > 0 && segLengths[i] - minSeg < cap) { segLengths[i]++; remaining-- }
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
    fun generateSimplePath(rows: Int, cols: Int, numberCount: Int, seed: Long): Map<Int, Position> {
        val random = Random(seed)
        require(rows >= 2 && cols >= 2)
        val full = generateHamiltonianPath(rows, cols, seed)
        val need = (numberCount * 3).coerceIn(4, full.size)
        val maxStart = full.size - need
        val startIdx = if (maxStart <= 0) 0 else random.nextInt(maxStart + 1)
        val window = full.subList(startIdx, startIdx + need)
        return placeNumbers(window, numberCount, seed)
    }

    /**
     * Challenge maze level — WALL-based bricks for real difficulty.
     *
     * Instead of scattered single cells (weak obstacles), this carves the grid
     * with wall segments: each wall is a straight run of 2..maxWall cells
     * (horizontal or vertical). Walls are placed one by one; after each wall
     * we require (a) connectivity of open cells and (b) parity, and finally
     * verify a Hamiltonian path exists over the open cells. On success the
     * solution path is returned — numbers are placed on it so the level is
     * guaranteed solvable.
     *
     * [targetOpenRatio] shapes the maze: 0.85 = light, 0.55 = brutal.
     */
    fun generateMazeLevel(
        rows: Int, cols: Int, numberCount: Int,
        targetOpenRatio: Float, seed: Long
    ): BrickLevel {
        require(rows >= 5 && cols >= 5)
        val random = Random(seed)
        val total = rows * cols
        val targetBlocks = (total * (1f - targetOpenRatio)).toInt()
            .coerceAtLeast(2)
            .coerceAtMost(total - numberCount * 2)

        val blocked = mutableSetOf<Position>()
        // Keep a 1-cell clear margin around the border to avoid degenerate forks.
        val interior = buildList {
            for (r in 1 until rows - 1) for (c in 1 until cols - 1) add(Position(r, c))
        }

        fun openCells(): List<Position> = buildList {
            for (r in 0 until rows) for (c in 0 until cols) {
                val p = Position(r, c)
                if (p !in blocked) add(p)
            }
        }

        fun connectedAndParityOk(): List<Position>? {
            val open = openCells()
            if (open.isEmpty()) return null
            val openSet = open.toSet()

            // Parity: path alternates colors → |black - white| <= 1.
            var black = 0; var white = 0
            for (p in open) {
                if ((p.row + p.col) % 2 == 0) black++ else white++
            }
            if (kotlin.math.abs(black - white) > 1) return null

            // Connectivity from the first open cell.
            val visited = hashSetOf<Position>()
            val queue = ArrayDeque<Position>().apply { add(open.first()); visited.add(open.first()) }
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                for (nb in neighbours(cur, rows, cols)) {
                    if (nb in openSet && nb !in visited) { visited.add(nb); queue.addLast(nb) }
                }
            }
            return if (visited.size == open.size) open else null
        }

        var guard = 0
        // Hard cap: at most 3 blocks total (2-3 cell wall pieces).
        val hardCap = minOf(targetBlocks, 3)
        while (blocked.size < hardCap && guard < 200) {
            guard++
            val seedCell = interior.randomOrNull(random) ?: break
            if (seedCell in blocked) continue
            val horizontal = random.nextBoolean()
            val len = 2 + random.nextInt(2)  // 2..3 cells per wall
            val cells = mutableListOf<Position>()
            for (i in 0 until len) {
                val r = if (horizontal) seedCell.row else seedCell.row + i
                val c = if (horizontal) seedCell.col + i else seedCell.col
                if (r !in 1 until rows - 1 || c !in 1 until cols - 1) break
                cells.add(Position(r, c))
            }
            if (cells.any { it in blocked }) continue
            if (blocked.size + cells.size > hardCap) {
                // Trim the wall to fit the cap.
                while (cells.size + blocked.size > hardCap && cells.isNotEmpty()) cells.removeAt(cells.size - 1)
                if (cells.isEmpty()) continue
            }

            blocked.addAll(cells)
            val open = connectedAndParityOk()
            if (open != null && open.size >= numberCount * 2) {
                // Wall accepted.
            } else {
                blocked.removeAll(cells)
            }
        }

        // Final verification: a real Hamiltonian path must exist.
        val open = openCells()
        val solution = hamiltonianOnOpen(open, rows, cols, random)
        if (solution != null && blocked.isNotEmpty()) {
            // Dense checkpoints with small gaps → numbers interlock as obstacles.
            val minSeg = 2
            val maxSeg = 4
            val numbers = placeNumbers(solution, numberCount, seed, minSeg, maxSeg)
            return BrickLevel(solution, blocked.toSet(), numbers)
        }

        // Fallback: single spread bricks (old behavior) — still solvable.
        return generateBrickLevel(rows, cols, numberCount, maxOf(1, targetBlocks), seed)
    }

    /**
     * Counts valid ordered routes (numbers in sequence, covering every open
     * cell) with early-exit at [stopAfter]. Budgeted so it never hangs.
     * Used to verify a level has EXACTLY ONE solution.
     */
    fun countOrderedPaths(
        rows: Int, cols: Int,
        numbers: Map<Int, Position>,
        blocks: Set<Position>,
        stopAfter: Int = 2
    ): Int {
        val total = rows * cols - blocks.size
        if (total <= 0) return 0
        val maxNumber = numbers.keys.maxOrNull() ?: return 0
        val start = numbers[1] ?: return 0
        if (start in blocks) return 0

        val numberAt = HashMap<Position, Int>()
        for ((num, p) in numbers) numberAt[p] = num

        var count = 0
        var budget = 120_000
        val visited = hashSetOf(start)
        val end = numbers[maxNumber]
        if (end in blocks) return 0

        fun dfs(cur: Position, nextNumber: Int, covered: Int) {
            if (count >= stopAfter || budget <= 0) return
            budget--

            if (covered == total) {
                // Rule: all numbers consumed IN ORDER and the path ENDS on the
                // last number's cell (mirrors the game's win condition).
                if (nextNumber > maxNumber && cur == end) count++
                return
            }

            for (nb in neighbours(cur, rows, cols)) {
                if (nb in blocks || nb in visited) continue
                val isNumber = numberAt[nb]
                if (isNumber != null) {
                    if (isNumber != nextNumber) continue   // wrong number = blocked
                    visited.add(nb)
                    dfs(nb, nextNumber + 1, covered + 1)
                    visited.remove(nb)
                } else {
                    visited.add(nb)
                    dfs(nb, nextNumber, covered + 1)
                    visited.remove(nb)
                }
                if (count >= stopAfter || budget <= 0) return
            }
        }

        dfs(start, 2, 1)
        return count
    }

    /**
     * Finds the SECOND valid ordered route (if any) and returns its full cell
     * list. Returns null when the level already has exactly one solution (or
     * budget ran out before a second route was found).
     *
     * Used by [generateBlockingLevel] to learn WHICH cells make alternative
     * routes possible — those cells become brick candidates.
     */
    fun findSecondRoute(
        rows: Int, cols: Int,
        numbers: Map<Int, Position>,
        blocks: Set<Position>
    ): List<Position>? {
        val total = rows * cols - blocks.size
        if (total <= 0) return null
        val maxNumber = numbers.keys.maxOrNull() ?: return null
        val start = numbers[1] ?: return null
        if (start in blocks) return null

        val numberAt = HashMap<Position, Int>()
        for ((num, p) in numbers) numberAt[p] = num

        var found = 0
        var budget = 150_000
        var secondRoute: List<Position>? = null
        val current = ArrayDeque<Position>()
        current.addLast(start)
        val visited = hashSetOf(start)
        val end = numbers[maxNumber]
        if (end in blocks) return null

        fun dfs(cur: Position, nextNumber: Int, covered: Int) {
            if (secondRoute != null || budget <= 0) return
            budget--

            if (covered == total) {
                if (nextNumber > maxNumber && cur == end) {
                    found++
                    if (found == 2) {
                        secondRoute = current.toList()   // capture the alternative
                    }
                }
                return
            }

            for (nb in neighbours(cur, rows, cols)) {
                if (nb in blocks || nb in visited) continue
                val isNumber = numberAt[nb]
                current.addLast(nb); visited.add(nb)
                if (isNumber != null) {
                    if (isNumber == nextNumber) {
                        dfs(nb, nextNumber + 1, covered + 1)
                    }
                } else {
                    dfs(nb, nextNumber, covered + 1)
                }
                current.removeLast(); visited.remove(nb)
                if (secondRoute != null || budget <= 0) return
            }
        }

        dfs(start, 2, 1)
        return secondRoute
    }

    /**
     * BLOCKING level — bricks are placed ON PURPOSE to kill alternative routes.
     *
     * Process (inverted from previous generators):
     *  1. Full open board, generate a Hamiltonian solution path.
     *  2. Place DENSE numbers on the solution path.
     *  3. Count routes; find a second (alternative) route via DFS.
     *  4. Cells that the alternative route uses but the solution does NOT
     *     become brick candidates. Brick the most impactful one(s).
     *  5. Repeat until exactly ONE route remains, or the brick budget
     *     (maxBricks) is spent, or we run out of iterations → reject.
     *
     * The result: bricks exist precisely where they silence alternative
     * routes — they CLOSE the board down to a single forced journey.
     *
     * @param numberDensity share of open cells carrying a number (0.45..0.62)
     * @param maxBricks     hard cap on placed bricks (e.g. 3)
     * @param minSeg        minimum segment length between consecutive numbers
     * @param maxSeg        maximum segment length between consecutive numbers
     */
    fun generateBlockingLevel(
        rows: Int, cols: Int,
        numberDensity: Float,
        maxBricks: Int, seed: Long,
        minSeg: Int = 2, maxSeg: Int = 3
    ): BrickLevel {
        require(rows >= 5 && cols >= 5)
        val random = Random(seed)
        val total = rows * cols

        // 1. Solution path on a fully open board.
        val solution = generateHamiltonianPath(rows, cols, seed)

        // 2. Dense numbers on the solution. Segment gap is a tunable knob
        //    (minSeg/maxSeg); baseline remains 2..3.
        val numberCount = (total * numberDensity).toInt().coerceIn(4, total - 2)
        var numbers = placeNumbers(solution, numberCount, seed, minSeg, maxSeg)

        val blocked = mutableSetOf<Position>()

        // 3-6. Iteratively brick away alternative routes.
        // IMPORTANT: never brick cells ON the solution path — the solution is
        // the only route guaranteed to end on the last number.
        val solutionSet = solution.toSet()
        var iterations = 0
        while (iterations < 12) {
            iterations++
            val routes = countOrderedPaths(rows, cols, numbers, blocked, stopAfter = 2)
            if (routes == 1) {
                // Unique! The solution path is still fully open and ends on
                // the last number — the level is solvable by design.
                return BrickLevel(solution, blocked.toSet(), numbers)
            }
            if (blocked.size >= maxBricks) break

            // Find one alternative route and diff it against the solution.
            val alt = findSecondRoute(rows, cols, numbers, blocked) ?: break
            val candidates = alt.filter { it !in solutionSet && it !in blocked }
            if (candidates.isEmpty()) {
                // Alternative reuses only solution cells (different ORDER).
                // A brick can't fix ordering. Reshape the number placement
                // deterministically (same solution path, salted seed) and
                // retry the blocking loop from a clean board.
                numbers = placeNumbers(solution, numberCount, seed + iterations * 104729L, minSeg, maxSeg)
                blocked.clear()
            } else {
                // Brick the deviation cell closest to the board center
                // (heuristic: central bricks kill more alternatives).
                val cr = rows / 2f
                val cc = cols / 2f
                blocked.add(candidates.minByOrNull { p ->
                    (p.row - cr) * (p.row - cr) + (p.col - cc) * (p.col - cc)
                }!!)
            }
        }

        // Not converged within budget — verify final state; if unique, accept.
        val finalRoutes = countOrderedPaths(rows, cols, numbers, blocked, stopAfter = 2)
        if (finalRoutes == 1) {
            return BrickLevel(solution, blocked.toSet(), numbers)
        }

        // Reject: caller (exporter) will try another seed.
        error("generateBlockingLevel: failed to converge to a unique solution")
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
        maxBrickCount: Int = 5, seed: Long
    ): BrickLevel {
        val random = Random(seed)
        require(rows >= 4 && cols >= 4)
        val mirrored = generateHamiltonianPath(rows, cols, seed)
        // Always at least 1 brick so every challenge level has an obstacle.
        val brickCount = 1 + random.nextInt(maxBrickCount)
        if (brickCount == 0) {
            val numbers = placeNumbers(mirrored, numberCount, seed)
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

            val numbers = placeNumbers(solution, numberCount, seed)
            return BrickLevel(solution, blocks, numbers)
        }

        // Fallback: tail bricks (guaranteed solvable, may cluster).
        val fbp = mirrored.dropLast(brickCount)
        val fbb = mirrored.takeLast(brickCount).toSet()
        val fbn = placeNumbers(fbp, numberCount, seed)
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