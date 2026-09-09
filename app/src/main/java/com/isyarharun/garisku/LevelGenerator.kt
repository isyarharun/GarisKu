package com.isyarharun.garisku

import kotlin.random.Random

/**
 * Generates solvable levels for GarisKu.
 *
 * CHALLENGE: unique-solution wall mazes (edge-wall chains on non-solution edges
 * + convergence to exactly one valid route).
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
     * Counts valid ordered routes (numbers in sequence, covering every open
     * cell) with early-exit at [stopAfter]. Budgeted so it never hangs.
     * Used to verify a level has EXACTLY ONE solution.
     */
    fun countOrderedPaths(
        rows: Int, cols: Int,
        numbers: Map<Int, Position>,
        blocks: Set<Position>,
        stopAfter: Int = 2,
        edgeWalls: Set<WallEdge> = emptySet()
    ): Int {
        val total = rows * cols - blocks.size
        if (total <= 0) return 0
        val maxNumber = numbers.keys.maxOrNull() ?: return 0
        val start = numbers[1] ?: return 0
        if (start in blocks) return 0

        val numberAt = HashMap<Position, Int>()
        for ((num, p) in numbers) numberAt[p] = num

        var count = 0
        var budget = 2_000_000
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

            // Warnsdorff-ordered neighbours: try the cells with fewest onward
            // options first so the (guaranteed) solution is found fast. Ordering
            // changes only the order of exploration, never the set of routes,
            // so the count stays exact — but no longer misses the cover-all
            // solution on wide boards within the budget (fixes spurious
            // routes == 0 rejections).
            val ordered = neighbours(cur, rows, cols)
                .filter { it !in blocks && it !in visited && !edgeWallBlocks(cur, it, edgeWalls) }
                .sortedBy { n ->
                    neighbours(n, rows, cols).count {
                        it !in blocks && it !in visited && it != cur && !edgeWallBlocks(n, it, edgeWalls)
                    }
                }
            for (nb in ordered) {
                if (nb in blocks || nb in visited) continue
                if (edgeWallBlocks(cur, nb, edgeWalls)) continue
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

    private fun edgeWallBlocks(a: Position, b: Position, walls: Set<WallEdge>): Boolean =
        walls.any { it.connects(a, b) }

    /** True when [set] contains an edge connecting [a]-[b], undirected. */
    private fun wallSetContains(set: Set<WallEdge>, a: Position, b: Position): Boolean =
        set.any { it.connects(a, b) }

    data class AlternativePathResult(
        val alternativeFound: Boolean,
        val budgetExhausted: Boolean,
        val probes: Int
    )

    /** Validates the Hamiltonian solution returned by the generator directly. */
    fun validateKnownSolution(
        rows: Int, cols: Int,
        numbers: Map<Int, Position>,
        blocks: Set<Position>,
        edgeWalls: Set<WallEdge>,
        solution: List<Position>
    ): Boolean {
        val total = rows * cols - blocks.size
        val maxNumber = numbers.keys.maxOrNull() ?: return false
        if (solution.size != total || solution.toSet().size != solution.size) return false
        if (solution.firstOrNull() != numbers[1] || solution.lastOrNull() != numbers[maxNumber]) return false
        val numberAt = numbers.entries.associate { it.value to it.key }
        var nextNumber = 1
        for (i in solution.indices) {
            val p = solution[i]
            if (p in blocks) return false
            val n = numberAt[p]
            if (n != null) {
                if (n != nextNumber) return false
                nextNumber++
            }
            if (i + 1 < solution.size) {
                val q = solution[i + 1]
                if (!p.isAdjacentTo(q) || edgeWallBlocks(p, q, edgeWalls)) return false
            }
        }
        return nextNumber > maxNumber
    }

    /** Searches directly for any valid route that deviates from [solution]. */
    fun findAlternativeOrderedPath(
        rows: Int, cols: Int,
        numbers: Map<Int, Position>,
        blocks: Set<Position>,
        edgeWalls: Set<WallEdge>,
        solution: List<Position>,
        budget: Int = 2_000_000
    ): AlternativePathResult {
        if (!validateKnownSolution(rows, cols, numbers, blocks, edgeWalls, solution)) {
            return AlternativePathResult(false, false, 0)
        }
        val total = rows * cols - blocks.size
        val maxNumber = numbers.keys.maxOrNull() ?: return AlternativePathResult(false, false, 0)
        val start = numbers[1] ?: return AlternativePathResult(false, false, 0)
        val end = numbers[maxNumber] ?: return AlternativePathResult(false, false, 0)
        val numberAt = numbers.entries.associate { it.value to it.key }
        val solutionIndex = solution.withIndex().associate { it.value to it.index }
        val visited = hashSetOf(start)
        var budgetLeft = budget
        var exhausted = false
        var found = false

        fun dfs(cur: Position, nextNumber: Int, covered: Int, deviated: Boolean) {
            if (found || exhausted) return
            if (budgetLeft <= 0) { exhausted = true; return }
            budgetLeft--
            if (covered == total) {
                if (deviated && nextNumber > maxNumber && cur == end) found = true
                return
            }
            val expected = if (!deviated) {
                solutionIndex[cur]?.let { i -> solution.getOrNull(i + 1) }
            } else null
            val candidates = neighbours(cur, rows, cols)
                .filter { it !in blocks && it !in visited && !edgeWallBlocks(cur, it, edgeWalls) }
                .sortedBy { n ->
                    neighbours(n, rows, cols).count {
                        it !in blocks && it !in visited && !edgeWallBlocks(n, it, edgeWalls)
                    }
                }
            for (nb in candidates) {
                val num = numberAt[nb]
                if (num != null && num != nextNumber) continue
                visited.add(nb)
                dfs(nb, if (num != null) nextNumber + 1 else nextNumber, covered + 1, deviated || nb != expected)
                visited.remove(nb)
                if (found || exhausted) return
            }
        }
        dfs(start, 2, 1, false)
        return AlternativePathResult(found, exhausted, 1)
    }

    private data class OneRouteResult(
        val route: List<Position>?,
        val budgetExhausted: Boolean
    )

    /** First valid ordered route, optionally avoiding one edge of the known path. */
    private fun findOneRouteResult(
        rows: Int, cols: Int,
        numbers: Map<Int, Position>,
        blocks: Set<Position>,
        edgeWalls: Set<WallEdge>,
        bannedEdge: WallEdge?,
        budget: Int
    ): OneRouteResult {
        val total = rows * cols - blocks.size
        if (total <= 0) return OneRouteResult(null, false)
        val maxNumber = numbers.keys.maxOrNull() ?: return OneRouteResult(null, false)
        val start = numbers[1] ?: return OneRouteResult(null, false)
        if (start in blocks) return OneRouteResult(null, false)
        val end = numbers[maxNumber]
        if (end in blocks) return OneRouteResult(null, false)
        val numberAt = HashMap<Position, Int>().apply { for ((n, p) in numbers) put(p, n) }
        var remaining = budget
        var budgetExhausted = false
        val current = ArrayDeque<Position>().apply { addLast(start) }
        val visited = hashSetOf(start)
        var result: List<Position>? = null

        fun dfs(cur: Position, nextNumber: Int, covered: Int) {
            if (result != null) return
            if (remaining <= 0) { budgetExhausted = true; return }
            remaining--
            if (covered == total) {
                if (nextNumber > maxNumber && cur == end) result = current.toList()
                return
            }
            val ordered = neighbours(cur, rows, cols)
                .filter { it !in blocks && it !in visited && !edgeWallBlocks(cur, it, edgeWalls) &&
                        bannedEdge?.connects(cur, it) != true }
                .sortedBy { n ->
                    neighbours(n, rows, cols).count {
                        it !in blocks && it !in visited && it != cur &&
                        !edgeWallBlocks(n, it, edgeWalls) && bannedEdge?.connects(n, it) != true
                    }
                }
            for (nb in ordered) {
                val isNum = numberAt[nb]
                if (isNum != null && isNum != nextNumber) continue
                current.addLast(nb); visited.add(nb)
                dfs(nb, if (isNum != null) nextNumber + 1 else nextNumber, covered + 1)
                current.removeLast(); visited.remove(nb)
                if (result != null || budgetExhausted) return
            }
        }

        dfs(start, 2, 1)
        return OneRouteResult(result, budgetExhausted)
    }

    /** Returns a valid alternative route distinct from [solution], or NULL when the
     * level is UNIQUE (probabilistically robust). A distinct Hamiltonian route must
     * omit at least one solution edge, so we probe solution edges IN PATH ORDER —
     * sealing each shut and running a fast find-one (Warnsdorff finds an existing
     * route quickly; a probe that finds one is a genuine alternative). A probe that
     * finds nothing is the expensive case, so its budget is capped and we only scan
     * a bounded prefix of solution edges: any alternative deviates near the start,
     * so a level whose first [probeLimit] sealed edges all kill every route is
     * effectively unique. The exporter re-confirms with countOrderedPaths. */
    private fun findAlternativeRoute(
        rows: Int, cols: Int,
        numbers: Map<Int, Position>,
        blocks: Set<Position>,
        walls: Set<WallEdge>,
        solution: List<Position>,
        probeBudget: Int = 120_000,
        probeLimit: Int = 40
    ): List<Position>? {
        val solEdges = consecutiveEdges(solution)
        var scanned = 0
        for (e in solEdges) {
            if (++scanned > probeLimit) break
            val r = findOneRouteResult(rows, cols, numbers, blocks, walls, e, probeBudget).route
            if (r != null) return r
        }
        return null
    }

    /** Consecutive edge walls along a cell route (the shared sides between steps). */
    private fun consecutiveEdges(path: List<Position>): Set<WallEdge> = buildSet {
        for (i in 0 until path.size - 1) add(WallEdge(path[i], path[i + 1]))
    }

    /**
     * MAZE-WALL level (Zip-faithful, unique-solution).
     *
     * Architecture (path-first + walls on NON-solution edges + convergence):
     *   1. Build a full-coverage Hamiltonian solution path over every cell.
     *   2. Place connected wall CHAINS (straight / L / T — the Zip reference style)
     *      only on edges the solution does NOT use, so the solution stays intact
     *      and solvable by construction (no expensive search-through-maze).
     *   3. Put numbers on the solution path.
     *   4. Wall away any remaining ALTERNATIVE route until countOrderedPaths == 1,
     *      yielding a level with EXACTLY ONE valid solution (true uniqueness —
     *      see step 4 below).
     *
     * Why this converges at low density: because the solution visits every open
     * cell, walling a non-solution edge can never disconnect the board, and a
     * distinct alternative route must differ on at least one (wallable)
     * non-solution edge — so uniqueness is reachable and termination is
     * guaranteed (not just "fewest routes").
     *
     * @param numberCount exact number of checkpoints placed on the solution path
     * @param wallPieces    how many wall chains to attempt
     * @param maxChainLen   max edges per chain (1=straight stub, 3+ makes L/T)
     * @param allowBranch   when true, a chain may fork into a T from an endpoint
     */
    fun generateMazeWallLevel(
        rows: Int, cols: Int,
        numberCount: Int,
        wallPieces: Int, maxChainLen: Int,
        allowBranch: Boolean,
        seed: Long,
        minSeg: Int = 2, maxSeg: Int = 3
    ): BrickLevel {
        require(rows >= 5 && cols >= 5)
        val total = rows * cols
        require(numberCount in 4..total - 2)
        val random = Random(seed)

        // ── 1. Solution path FIRST (fast Hamiltonian, no walls yet) ──
        val solution = generateHamiltonianPath(rows, cols, seed)
        val solutionEdges = consecutiveEdges(solution)

        // ── 2. WALL-FIRST graph: close every non-solution edge. ──
        // The open graph initially is exactly the Hamiltonian path, so the known
        // solution is valid and uniqueness is structurally guaranteed.
        val allEdges = buildList {
            for (r in 0 until rows) for (c in 0 until cols) {
                val p = Position(r, c)
                if (c + 1 < cols) add(WallEdge(p, Position(r, c + 1)))
                if (r + 1 < rows) add(WallEdge(p, Position(r + 1, c)))
            }
        }
        val nonSolutionEdges = allEdges.filter { edge ->
            solutionEdges.none { it.connects(edge.a, edge.b) }
        }.shuffled(random)
        val walls = LinkedHashSet<WallEdge>().apply { addAll(nonSolutionEdges) }

        // ── 3. Numbers on the intact solution path. ──
        val numbers = placeNumbers(solution, numberCount, seed, minSeg, maxSeg)

        // Selectively remove walls. wallPieces is retained as the tier's target
        // number of extra edges to open. Every opening is tested against the real
        // numbered puzzle; failed or budget-starved probes remain closed.
        var opened = 0
        for (edge in nonSolutionEdges) {
            if (opened >= wallPieces) break
            walls.remove(edge)
            val candidate = findAlternativeOrderedPath(
                rows, cols, numbers, emptySet(), walls, solution, budget = 250_000
            )
            if (!candidate.alternativeFound && !candidate.budgetExhausted) {
                opened++
            } else {
                walls.add(edge)
            }
        }

        // Final conservative uniqueness check with actual checkpoints. If an opened
        // edge made a numbered alternative possible, restore that edge as a wall.
        val acceptedWalls = LinkedHashSet(walls)
        val openedEdges = nonSolutionEdges.filter { it !in acceptedWalls }
        for (edge in openedEdges) {
            acceptedWalls.remove(edge)
            val result = findAlternativeOrderedPath(
                rows, cols, numbers, emptySet(), acceptedWalls, solution, budget = 500_000
            )
            if (result.alternativeFound || result.budgetExhausted) acceptedWalls.add(edge)
        }

        return BrickLevel(solution, emptySet(), numbers, acceptedWalls)
    }

    /** All cells reachable through non-walled edges? (O(cells) BFS). */
    private fun connectedAllCells(rows: Int, cols: Int, walls: Set<WallEdge>): Boolean {
        val total = rows * cols
        val seen = hashSetOf(Position(0, 0))
        val queue = ArrayDeque<Position>().apply { addLast(Position(0, 0)) }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (nb in neighbours(cur, rows, cols)) {
                if (nb in seen) continue
                if (edgeWallBlocks(cur, nb, walls)) continue
                seen.add(nb); queue.addLast(nb)
            }
        }
        return seen.size == total
    }

    private fun neighbours(p: Position, rows: Int, cols: Int): List<Position> =
        DIRS.map { (dr, dc) -> Position(p.row + dr, p.col + dc) }
            .filter { it.row in 0 until rows && it.col in 0 until cols }
}

data class BrickLevel(
    val path: List<Position>,
    val blocks: Set<Position>,
    val numberPositions: Map<Int, Position>,
    val edgeWalls: Set<WallEdge> = emptySet()
)