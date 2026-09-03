package com.isyarharun.garisku

import kotlin.math.ceil

/**
 * Measures how hard the DECISIONS in a finished puzzle are — from the
 * player's point of view: only numbers + bricks are known, no hidden solution.
 *
 * Win condition mirrored from GameState.checkCompletion: numbers consumed in
 * order, every open cell covered, path ENDS on the last number's cell.
 *
 * All outputs are deterministic: fixed neighbour order, fixed budgets, capped
 * counters. The exploration DFS samples the near-solution space under a step
 * budget, so counts are deterministic lower-bound estimates (never hang).
 *
 * Intended for UNIQUE puzzles (countOrderedPaths == 1): the near-solution
 * counter excludes the unique true solution state.
 */
object RouteAnalyzer {

    data class Analysis(
        val solutionLength: Int,        // open cells on the route
        val numberCount: Int,
        /** Mean legal options per step along a valid solution. */
        val avgBranching: Float,
        /** Max legal options at any solution step. */
        val maxBranching: Int,
        /** Share of solution steps with exactly ONE legal option (no decision). */
        val forcedMoveRatio: Float,
        /** Wrong turns: mean steps survived before dead-ending. */
        val meanDeviationDepth: Float,
        /** Wrong turns: deepest trap (capped at PROBE_STEP_CAP). */
        val maxDeviationDepth: Int,
        /** Number of deviation probes actually run. */
        val deviationProbes: Int,
        /** Routes that consume ALL numbers in order but fail the win condition. */
        val nearSolutionFull: Int,
        /** Dead-ends reached with >= 60% of numbers already consumed. */
        val nearSolutionPrefix: Int,
        /** True when the exploration budget ran out (counts are samples). */
        val exploreBudgetExhausted: Boolean,
        /** One valid route (deterministic). Empty when no route exists. */
        val solutionRoute: List<Position> = emptyList()
    )

    private const val PROBE_STEP_CAP = 48
    private const val MAX_PROBES = 64
    private const val PROBE_BUDGET = 15_000
    private const val EXPLORE_BUDGET = 1_200_000
    private const val NEAR_CAP = 256

    fun analyze(
        rows: Int, cols: Int,
        numbers: Map<Int, Position>,
        blocks: Set<Position>
    ): Analysis {
        val total = rows * cols - blocks.size
        val maxNumber = numbers.keys.maxOrNull() ?: return empty()
        val start = numbers[1] ?: return empty()
        val end = numbers[maxNumber] ?: return empty()
        if (start in blocks || end in blocks || total <= 0) return empty()
        val numberAt = HashMap<Position, Int>()
        for ((num, p) in numbers) numberAt[p] = num

        // ── 1. Reconstruct ONE valid solution (deterministic DFS order) ──
        val solution = findRoute(rows, cols, numberAt, blocks, total, maxNumber, start, end)
            ?: return empty(numbers.size)

        // ── 2. Solution walk: branching + forced moves + wrong turns ──
        val posIdx = HashMap<Position, Int>()
        solution.forEachIndexed { i, p -> posIdx[p] = i }
        val expectedAt = IntArray(solution.size)
        var nextExpected = 2
        var optSum = 0
        var steps = 0
        var forced = 0
        var maxB = 0
        val wrongTurns = ArrayList<Pair<Int, Position>>()   // (stepIndex, deviationCell)
        for (j in 0 until solution.size - 1) {
            expectedAt[j] = nextExpected
            val cur = solution[j]
            var options = 0
            for (nb in neighbours(cur, rows, cols)) {
                if (nb in blocks) continue
                val vi = posIdx[nb]
                if (vi != null && vi <= j) continue               // already visited
                val num = numberAt[nb]
                if (num != null && num != nextExpected) continue  // wrong number = wall
                if (nb != solution[j + 1]) wrongTurns.add(j to nb)
                options++
            }
            if (numberAt[solution[j + 1]] == nextExpected) nextExpected++
            optSum += options; steps++
            if (options == 1) forced++
            if (options > maxB) maxB = options
        }

        // ── 3. Deviation probes: how long does a wrong turn survive? ──
        val sampled = if (wrongTurns.size <= MAX_PROBES) wrongTurns else {
            val out = ArrayList<Pair<Int, Position>>(MAX_PROBES)
            for (k in 0 until MAX_PROBES) out.add(wrongTurns[k * wrongTurns.size / MAX_PROBES])
            out
        }
        var depthSum = 0
        var depthMax = 0
        for ((j, cell) in sampled) {
            val baseVisited = HashSet<Position>().apply { for (i in 0..j) add(solution[i]) }
            val d = probeDepth(rows, cols, numberAt, blocks, maxNumber, cell, baseVisited, expectedAt[j])
            depthSum += d
            if (d > depthMax) depthMax = d
        }
        val meanDev = if (sampled.isEmpty()) 0f else depthSum.toFloat() / sampled.size

        // ── 4. Near-solution exploration (budgeted, deterministic) ──
        var nearFull = 0
        var nearPrefix = 0
        var exhausted = false
        var budget = EXPLORE_BUDGET
        val prefixThreshold = ceil(maxNumber * 0.6).toInt()
        val visited = hashSetOf(start)

        fun explore(cur: Position, nextNumber: Int, covered: Int) {
            if (exhausted) return
            if (budget <= 0) { exhausted = true; return }
            budget--
            if (nextNumber > maxNumber) {
                // All numbers consumed in order but the win condition is not met.
                // (covered == total && cur == end would be the true solution.)
                if (!(covered == total && cur == end) && nearFull < NEAR_CAP) nearFull++
                return
            }
            val nbs = neighbours(cur, rows, cols)
            var moves = 0
            for (nb in nbs) {
                if (nb in blocks || nb in visited) continue
                val num = numberAt[nb]
                if (num != null && num != nextNumber) continue
                moves++
            }
            if (moves == 0) {
                if (nextNumber - 1 >= prefixThreshold && nearPrefix < NEAR_CAP) nearPrefix++
                return
            }
            for (nb in nbs) {
                if (exhausted) return
                if (nb in blocks || nb in visited) continue
                val num = numberAt[nb]
                if (num != null && num != nextNumber) continue
                visited.add(nb)
                explore(nb, if (num != null) nextNumber + 1 else nextNumber, covered + 1)
                visited.remove(nb)
            }
        }
        explore(start, 2, 1)

        return Analysis(
            solutionLength = solution.size,
            numberCount = numbers.size,
            avgBranching = if (steps == 0) 0f else optSum.toFloat() / steps,
            maxBranching = maxB,
            forcedMoveRatio = if (steps == 0) 0f else forced.toFloat() / steps,
            meanDeviationDepth = meanDev,
            maxDeviationDepth = depthMax,
            deviationProbes = sampled.size,
            nearSolutionFull = nearFull,
            nearSolutionPrefix = nearPrefix,
            exploreBudgetExhausted = exhausted,
            solutionRoute = solution
        )
    }

    /** Bounded DFS over one wrong-turn subtree; returns max steps survived. */
    private fun probeDepth(
        rows: Int, cols: Int,
        numberAt: Map<Position, Int>,
        blocks: Set<Position>,
        maxNumber: Int,
        startCell: Position,
        baseVisited: Set<Position>,
        startNext: Int
    ): Int {
        var best = 0
        var budget = PROBE_BUDGET
        val visited = HashSet(baseVisited)
        visited.add(startCell)
        val num0 = numberAt[startCell]

        fun dfs(cur: Position, depth: Int, nextNumber: Int) {
            if (budget <= 0 || depth >= PROBE_STEP_CAP) return
            budget--
            if (depth > best) best = depth
            if (nextNumber > maxNumber) return   // numbers exhausted = terminal
            for (nb in neighbours(cur, rows, cols)) {
                if (budget <= 0) return
                if (nb in blocks || nb in visited) continue
                val num = numberAt[nb]
                if (num != null && num != nextNumber) continue
                visited.add(nb)
                dfs(nb, depth + 1, if (num != null) nextNumber + 1 else nextNumber)
                visited.remove(nb)
            }
        }
        dfs(startCell, 1, if (num0 != null) startNext + 1 else startNext)
        return best
    }

    /** First valid route via deterministic DFS (fixed neighbour order). */
    private fun findRoute(
        rows: Int, cols: Int,
        numberAt: Map<Position, Int>,
        blocks: Set<Position>,
        total: Int, maxNumber: Int,
        start: Position, end: Position
    ): List<Position>? {
        var route: List<Position>? = null
        var budget = 300_000
        val stack = ArrayDeque<Position>().apply { addLast(start) }
        val visited = hashSetOf(start)

        fun dfs(cur: Position, nextNumber: Int, covered: Int) {
            if (route != null || budget <= 0) return
            budget--
            if (covered == total) {
                if (nextNumber > maxNumber && cur == end) route = stack.toList()
                return
            }
            for (nb in neighbours(cur, rows, cols)) {
                if (route != null || budget <= 0) return
                if (nb in blocks || nb in visited) continue
                val num = numberAt[nb]
                if (num != null && num != nextNumber) continue
                stack.addLast(nb); visited.add(nb)
                dfs(nb, if (num != null) nextNumber + 1 else nextNumber, covered + 1)
                stack.removeLast(); visited.remove(nb)
            }
        }
        dfs(start, 2, 1)
        return route
    }

    private fun empty(numberCount: Int = 0) = Analysis(
        0, numberCount, 0f, 0, 0f, 0f, 0, 0, 0, 0, false
    )

    private fun neighbours(p: Position, rows: Int, cols: Int): List<Position> = listOf(
        Position(p.row - 1, p.col), Position(p.row + 1, p.col),
        Position(p.row, p.col - 1), Position(p.row, p.col + 1)
    ).filter { it.row in 0 until rows && it.col in 0 until cols }
}
