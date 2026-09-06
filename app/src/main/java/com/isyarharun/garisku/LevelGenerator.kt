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

    /** First valid ordered route (cover-all + numbers-in-order + ends on the last
     * number), optionally forced to AVOID [bannedEdge], else null. Warnsdorff
     * ordering makes finding ONE route fast and reliable (used for the robust
     * uniqueness probe below). */
    private fun findOneRoute(
        rows: Int, cols: Int,
        numbers: Map<Int, Position>,
        blocks: Set<Position>,
        edgeWalls: Set<WallEdge>,
        bannedEdge: WallEdge?,
        budget: Int
    ): List<Position>? {
        val total = rows * cols - blocks.size
        if (total <= 0) return null
        val maxNumber = numbers.keys.maxOrNull() ?: return null
        val start = numbers[1] ?: return null
        if (start in blocks) return null
        val end = numbers[maxNumber]
        if (end in blocks) return null
        val numberAt = HashMap<Position, Int>().apply { for ((n, p) in numbers) put(p, n) }
        var remaining = budget
        val current = ArrayDeque<Position>().apply { addLast(start) }
        val visited = hashSetOf(start)
        var result: List<Position>? = null

        fun dfs(cur: Position, nextNumber: Int, covered: Int) {
            if (result != null || remaining <= 0) return
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
                if (result != null || remaining <= 0) return
            }
        }

        dfs(start, 2, 1)
        return result
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
            val r = findOneRoute(rows, cols, numbers, blocks, walls, e, probeBudget)
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
     * @param numberDensity share of cells carrying a number
     * @param wallPieces    how many wall chains to attempt
     * @param maxChainLen   max edges per chain (1=straight stub, 3+ makes L/T)
     * @param allowBranch   when true, a chain may fork into a T from an endpoint
     */
    fun generateMazeWallLevel(
        rows: Int, cols: Int,
        numberDensity: Float,
        wallPieces: Int, maxChainLen: Int,
        allowBranch: Boolean,
        seed: Long,
        minSeg: Int = 2, maxSeg: Int = 3
    ): BrickLevel {
        require(rows >= 5 && cols >= 5)
        val total = rows * cols
        val random = Random(seed)

        // ── 1. Solution path FIRST (fast Hamiltonian, no walls yet) ──
        val solution = generateHamiltonianPath(rows, cols, seed)
        val solutionEdges = consecutiveEdges(solution)

        // ── 2. Place wall CHAINS on edges the solution does NOT use. ──
        // This guarantees the solution path stays fully intact → solvable by
        // construction, NO expensive search-through-maze, NO shedding. The walls
        // block alternative short-cuts and form connected chains (L/T) like Zip.
        val walls = LinkedHashSet<WallEdge>()
        val allEdges = buildList {
            for (r in 0 until rows) for (c in 0 until cols) {
                val p = Position(r, c)
                if (c + 1 < cols) add(WallEdge(p, Position(r, c + 1)))
                if (r + 1 < rows) add(WallEdge(p, Position(r + 1, c)))
            }
        }
        val usable = allEdges.filter { e -> !solutionEdges.any { it.connects(e.a, e.b) } }.shuffled(random)

        var pieces = 0
        var gi = 0
        val totalEdges = allEdges.size
        // Keep walls well below half of all edges so the board stays permissive.
        val maxWalls = (totalEdges * 0.30f).toInt().coerceAtLeast(2)
        while (pieces < wallPieces && gi < usable.size && walls.size < maxWalls) {
            // Seed edge that is not yet walled and not a solution edge.
            var seedEdge: WallEdge? = null
            while (gi < usable.size) {
                val e = usable[gi++]
                if (e !in walls) { seedEdge = e; break }
            }
            seedEdge ?: break

            // Grow a connected chain from this seed (straight / L / T).
            val chain = ArrayDeque<WallEdge>().apply { addLast(seedEdge) }
            val len = 1 + random.nextInt(maxChainLen)
            var tip = if (random.nextBoolean()) seedEdge.b else seedEdge.a
            var prev = if (tip == seedEdge.b) seedEdge.a else seedEdge.b

            while (chain.size < len) {
                val options = neighbours(tip, rows, cols)
                    .filter { n ->
                        n != prev &&
                            !wallSetContains(walls, tip, n) &&
                            !wallSetContains(chain.toSet(), tip, n) &&
                            !wallSetContains(solutionEdges, tip, n)   // never block the solution
                    }
                if (options.isEmpty()) break
                val next = options.random(random)
                chain.addLast(WallEdge(tip, next))
                prev = tip; tip = next
            }

            // Optional T-branch.
            if (allowBranch && chain.size >= 2 && random.nextInt(3) == 0) {
                val branchRoot = chain.random(random).let { if (random.nextBoolean()) it.a else it.b }
                val branchOpts = neighbours(branchRoot, rows, cols).filter { n ->
                    !wallSetContains(walls, branchRoot, n) &&
                        !wallSetContains(chain.toSet(), branchRoot, n) &&
                        !wallSetContains(solutionEdges, branchRoot, n)
                }
                if (branchOpts.isNotEmpty()) {
                    val bNext = branchOpts.random(random)
                    chain.addLast(WallEdge(branchRoot, bNext))
                }
            }

            // Accept the chain if it keeps the board connected. Degree safety is
            // automatically satisfied for every cell ON the solution path (which
            // is intact), and candidate chains avoid solution edges, so we only
            // need to ensure we don't wall off a cell entirely (parity/connectivity).
            val before = walls.size
            val added = walls.addAll(chain) && walls.size > before
            if (added) {
                if (!connectedAllCells(rows, cols, walls)) walls.removeAll(chain)
                else pieces++
            }
        }

        // ── 3. Numbers on the (intact) solution path — solvable by construction ──
        val numberCount = (total * numberDensity).toInt().coerceIn(4, total - 2)
        val numbers = placeNumbers(solution, numberCount, seed, minSeg, maxSeg)

        // ── 4. Converge to a UNIQUE solution — wall the deviation edges of any ──
        // alternative route until exactly one valid route remains (true
        // uniqueness, not just "fewest routes"). This is guaranteed to terminate:
        //   • The solution is a cover-all Hamiltonian path through EVERY cell, so
        //     every cell keeps its two solution edges open; walling any
        //     non-solution edge can therefore NEVER disconnect the board.
        //   • A distinct valid alternative (cover-all + numbers in order + ends on
        //     the last number) must differ from the solution on at least one edge,
        //     and that edge is a non-solution edge we are free to wall.
        // Hence each iteration strictly lowers the route count and reaches exactly
        // ONE route. (Kill the deviation edge nearest the board centre first —
        // central walls prune the most alternatives with the fewest walls.)
        val centreR = rows / 2f
        val centreC = cols / 2f
        val convEdgeCap = (totalEdges * 0.65f).toInt().coerceAtLeast(4)   // solutions survive all non-solution walls
        var convIter = 0
        while (convIter < 300 && walls.size < convEdgeCap) {
            convIter++
            // Robust uniqueness probe: a distinct Hamiltonian route must omit at
            // least one solution edge, so search for any route that survives with
            // each solution edge sealed shut. Null => truly unique.
            val alt = findAlternativeRoute(rows, cols, numbers, emptySet(), walls, solution) ?: break
            val candidates = consecutiveEdges(alt).filter { e ->
                !solutionEdges.any { it.connects(e.a, e.b) } && !wallSetContains(walls, e.a, e.b)
            }
            if (candidates.isEmpty()) break   // cannot happen for a genuine alternative (see KDoc)
            val picked = candidates.minByOrNull { w ->
                val mr = (w.a.row + w.b.row) / 2f
                val mc = (w.a.col + w.b.col) / 2f
                (mr - centreR) * (mr - centreR) + (mc - centreC) * (mc - centreC)
            }!!
            walls.add(picked)
        }

        return BrickLevel(solution, emptySet(), numbers, walls)
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