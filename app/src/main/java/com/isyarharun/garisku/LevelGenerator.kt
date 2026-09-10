package com.isyarharun.garisku

import kotlin.random.Random

/**
 * Generates solvable levels for GarisKu.
 *
 * CHALLENGE: Zip-style sparse wall chains on non-solution edges. The known
 * Hamiltonian solution stays intact; a soft convergence loop seals alternative
 * routes while any are cheaply found. Uniqueness is PREFERRED, not required —
 * selection upstream keeps the candidate with the FEWEST valid routes.
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
        edgeWalls: Set<WallEdge> = emptySet(),
        budget: Int = 2_000_000
    ): Int {
        val total = rows * cols - blocks.size
        if (total <= 0) return 0
        val maxNumber = numbers.keys.maxOrNull() ?: return 0
        val start = numbers[1] ?: return 0
        if (start in blocks) return 0

        val numberAt = HashMap<Position, Int>()
        for ((num, p) in numbers) numberAt[p] = num

        var count = 0
        var budgetLeft = budget
        val visited = hashSetOf(start)
        val end = numbers[maxNumber]
        if (end in blocks) return 0

        fun dfs(cur: Position, nextNumber: Int, covered: Int) {
            if (count >= stopAfter || budgetLeft <= 0) return
            budgetLeft--

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
        val probes: Int,
        /** The found alternative route (null when unique or budget ran out). */
        val path: List<Position>? = null
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

    /** Searches directly for any valid route that deviates from [solution].
     *  The found route is returned in [AlternativePathResult.path] so callers
     *  can seal the edge where it first leaves the solution. When [random] is
     *  given, neighbour tie-breaks are shuffled (randomized probing for wide
     *  open boards); the result stays deterministic for a fixed seed. */
    fun findAlternativeOrderedPath(
        rows: Int, cols: Int,
        numbers: Map<Int, Position>,
        blocks: Set<Position>,
        edgeWalls: Set<WallEdge>,
        solution: List<Position>,
        budget: Int = 2_000_000,
        random: Random? = null
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
        val current = ArrayDeque<Position>().apply { addLast(start) }
        var budgetLeft = budget
        var exhausted = false
        var found = false
        var foundPath: List<Position>? = null

        fun dfs(cur: Position, nextNumber: Int, covered: Int, deviated: Boolean) {
            if (found || exhausted) return
            if (budgetLeft <= 0) { exhausted = true; return }
            budgetLeft--
            if (covered == total) {
                if (deviated && nextNumber > maxNumber && cur == end) {
                    found = true
                    foundPath = current.toList()
                }
                return
            }
            val expected = if (!deviated) {
                solutionIndex[cur]?.let { i -> solution.getOrNull(i + 1) }
            } else null
            val candidates = neighbours(cur, rows, cols)
                .filter { it !in blocks && it !in visited && !edgeWallBlocks(cur, it, edgeWalls) }
                .let { if (random != null) it.shuffled(random) else it }
                .sortedBy { n ->
                    neighbours(n, rows, cols).count {
                        it !in blocks && it !in visited && !edgeWallBlocks(n, it, edgeWalls)
                    }
                }
            for (nb in candidates) {
                val num = numberAt[nb]
                if (num != null && num != nextNumber) continue
                visited.add(nb)
                current.addLast(nb)
                dfs(nb, if (num != null) nextNumber + 1 else nextNumber, covered + 1, deviated || nb != expected)
                current.removeLast()
                visited.remove(nb)
                if (found || exhausted) return
            }
        }
        dfs(start, 2, 1, false)
        return AlternativePathResult(found, exhausted, 1, foundPath)
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
    private fun consecutiveEdges(path: List<Position>): Set<WallEdge> = buildSet {        for (i in 0 until path.size - 1) add(WallEdge(path[i], path[i + 1]))
    }

    /**
     * MAZE-WALL level (Zip-faithful, SPARSE wall chains).
     *
     * Architecture (path-first + chain placement + soft convergence):
     *   1. Build a full-coverage Hamiltonian solution path over every cell.
     *   2. Place connected wall CHAINS (straight / L / T — the Zip reference
     *      style, floating freely) ONLY on edges the solution does NOT use, so
     *      the known solution stays valid by construction.
     *   3. Place numbers on the solution path (tier-driven segment gaps).
     *   4. Soft convergence: while an alternative route is cheaply found, seal
     *      the edge where it FIRST deviates from the solution (always a
     *      non-solution edge, so each step makes progress and can never break
     *      the solution). Stops on uniqueness OR when the extra-edge cap is
     *      reached — a few remaining routes are acceptable.
     *
     * Hard uniqueness needs nearly every non-solution edge sealed (dense maze).
     * Soft selection instead keeps the layout sparse like the reference
     * screenshots; the win condition (cover all cells + ordered numbers + end
     * on the last number) makes ANY remaining valid route a win, and the
     * exporter/factory pick the candidate with the FEWEST routes (1 wins
     * automatically).
     *
     * @param numberCount exact number of checkpoints placed on the solution path
     * @param wallPieces    how many wall chains to place (also the cap for extra
     *                      convergence edges)
     * @param maxChainLen   max edges per chain (1=straight stub, 3+ makes L/T)
     * @param allowBranch   when true, a chain may fork into a T
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

        // ── 1. Solution path first (fast Hamiltonian, no walls yet) ──
        val solution = generateHamiltonianPath(rows, cols, seed)
        val solutionEdges = consecutiveEdges(solution)

        // ── 2. Numbers on the intact solution path. ──
        val numbers = placeNumbers(solution, numberCount, seed, minSeg, maxSeg)

        // ── 3. Sparse wall CHAINS on non-solution edges. ──
        val wallable = buildList {
            for (r in 0 until rows) for (c in 0 until cols) {
                val p = Position(r, c)
                if (c + 1 < cols && solutionEdges.none { it.connects(p, Position(r, c + 1)) })
                    add(WallEdge(p, Position(r, c + 1)))
                if (r + 1 < rows && solutionEdges.none { it.connects(p, Position(r + 1, c)) })
                    add(WallEdge(p, Position(r + 1, c)))
            }
        }.shuffled(random)
        val walls = LinkedHashSet<WallEdge>()
        val wallableSet = wallable.toHashSet()
        var chains = 0
        for (edge in wallable) {
            if (chains >= wallPieces) break
            if (edge in walls) continue
            walls.addAll(growChain(edge, maxChainLen, allowBranch, random, wallableSet, walls))
            chains++
        }

        // ── 4. Soft convergence (sparse-safe, two detectors): ──
        // a) LOCAL switches — same-cell-set reroutes of a short solution segment;
        //    cheap, deterministic, always sealable by one edge.
        // b) BLIND probes — randomized full-route searches that catch wide-open
        //    boards where whole-route alternatives exist but no local switch.
        // Both seal only non-solution edges, so the solution stays intact and
        // every added wall removes at least one real alternative.
        var extra = 0
        while (extra < wallPieces) {
            val switch = findSwitchEdge(rows, cols, numbers, walls, solution)
            if (switch != null) {
                walls.add(switch)
                extra++
                continue
            }
            var sealed = false
            repeat(3) {
                if (sealed) return@repeat
                val probe = findAlternativeOrderedPath(
                    rows, cols, numbers, emptySet(), walls, solution,
                    budget = 150_000, random = random
                )
                val path = probe.path ?: return@repeat
                val edge = firstDeviationEdge(path, solution) ?: return@repeat
                if (walls.add(edge)) { sealed = true; extra++ }
            }
            if (!sealed) break
        }

        return BrickLevel(solution, emptySet(), numbers, walls)
    }

    /** First edge where [alternative] leaves the solution route — always a
     *  non-solution edge, hence wallable without breaking the solution. */
    private fun firstDeviationEdge(alternative: List<Position>, solution: List<Position>): WallEdge? {
        val n = minOf(alternative.size, solution.size)
        for (i in 1 until n) {
            if (alternative[i] != solution[i]) return WallEdge(alternative[i - 1], alternative[i])
        }
        return null
    }

    /**
     * Finds a LOCAL switch of the solution: an alternative route for a short
     * segment that visits the SAME cell set (e.g. the other winding of a 2x2
     * block). Splicing it into the full solution yields a genuine alternative
     * route, so sealing the returned (non-solution, non-walled) edge removes at
     * least that alternative. Deterministic scan; small-cell DFS under a node
     * budget, so it stays cheap on sparse boards where a full route search is
     * hopeless.
     *
     * @return an edge that seals the first switch found, or null when the
     *         solution is locally unique / the budget ran out.
     */
    fun findSwitchEdge(
        rows: Int, cols: Int,
        numbers: Map<Int, Position>,
        walls: Set<WallEdge>,
        solution: List<Position>,
        maxSpan: Int = 10,
        nodeBudget: Int = 100_000
    ): WallEdge? {
        val solutionEdges = consecutiveEdges(solution)
        val numberAt = numbers.entries.associate { it.value to it.key }
        var budget = nodeBudget

        for (i in 0 until solution.size - 1) {
            val maxJ = minOf(solution.size - 1, i + maxSpan)
            for (j in i + 2..maxJ) {          // a switch needs >= 3 cells
                val segment = solution.subList(i, j + 1)
                val segCells = segment.toSet()
                if (segCells.size != segment.size) continue   // self-crossing slice
                val segNums = segment.mapNotNull { numberAt[it] }.sorted()
                val segEnd = solution[j]
                val alt = ArrayList<Position>().apply { add(segment.first()) }
                val visited = hashSetOf(segment.first())
                var found: List<Position>? = null
                var nextNumIdx = 0

                fun dfs(cur: Position) {
                    if (found != null || budget <= 0) return
                    budget--
                    if (cur == segEnd) {
                        if (alt.size == segment.size && alt != segment) found = alt.toList()
                        return
                    }
                    for ((dr, dc) in DIRS) {
                        if (found != null || budget <= 0) return
                        val nb = Position(cur.row + dr, cur.col + dc)
                        if (nb !in segCells || nb in visited) continue
                        if (edgeWallBlocks(cur, nb, walls)) continue
                        val num = numberAt[nb]
                        if (num != null && (nextNumIdx >= segNums.size || segNums[nextNumIdx] != num)) continue
                        visited.add(nb); alt.add(nb)
                        val saved = nextNumIdx
                        if (num != null) nextNumIdx++
                        dfs(nb)
                        nextNumIdx = saved
                        alt.removeAt(alt.size - 1); visited.remove(nb)
                    }
                }
                dfs(segment.first())
                if (found != null) {
                    for (k in 0 until found.size - 1) {
                        val e = WallEdge(found[k], found[k + 1])
                        if (solutionEdges.none { it.connects(e.a, e.b) } && !edgeWallBlocks(e.a, e.b, walls)) return e
                    }
                }
                if (budget <= 0) return null
            }
        }
        return null
    }

    /**
     * Greedy switch census on a COPY of the wall set: repeatedly find and seal
     * local switches. 0 = locally unique (no short alternative route exists);
     * the count is a cheap, search-free proxy for the number of valid routes —
     * used to rank candidates (FEWEST wins, 1-route uniqueness not required).
     */
    fun countSwitches(
        rows: Int, cols: Int,
        numbers: Map<Int, Position>,
        walls: Set<WallEdge>,
        solution: List<Position>,
        cap: Int = 12
    ): Int {
        val temp = LinkedHashSet(walls)
        var count = 0
        while (count < cap) {
            val e = findSwitchEdge(rows, cols, numbers, temp, solution) ?: break
            temp.add(e)
            count++
        }
        return count
    }

    /**
     * Grows a wall chain from [start] across edges that are wallable and not
     * yet walled. Random adjacency produces the reference shapes: collinear
     * steps become straight bars, perpendicular steps become L, and the
     * optional branch turns a chain into a T. Chains float freely (no border
     * anchor needed).
     */
    private fun growChain(
        start: WallEdge,
        maxChainLen: Int,
        allowBranch: Boolean,
        random: Random,
        wallable: Set<WallEdge>,
        walls: Set<WallEdge>
    ): List<WallEdge> {
        val used = hashSetOf(start)
        val chain = ArrayList<WallEdge>().apply { add(start) }

        fun freeNeighbors(edge: WallEdge): List<WallEdge> =
            wallable.filter { it !in used && it !in walls && it.touches(edge) }

        val targetLen = 1 + random.nextInt(maxChainLen)
        var tip = start
        while (chain.size < targetLen) {
            val candidates = freeNeighbors(tip)
            if (candidates.isEmpty()) break
            tip = candidates[random.nextInt(candidates.size)]
            used.add(tip); chain.add(tip)
        }
        if (allowBranch && chain.size >= 2 && random.nextInt(4) == 0) {
            var tip2 = chain[random.nextInt(chain.size)]
            repeat(1 + random.nextInt(2)) {
                val candidates = freeNeighbors(tip2)
                if (candidates.isEmpty()) return@repeat
                tip2 = candidates[random.nextInt(candidates.size)]
                used.add(tip2); chain.add(tip2)
            }
        }
        return chain
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