package com.isyarharun.garisku

import kotlin.math.ln

/**
 * Scores how HARD the DECISIONS in a challenge level are, from the player's
 * point of view — not how the board looks.
 *
 * v1 scored board STRUCTURE (checkpoint density 40% / leaves 25% / gates 20% /
 * tightness 15%). The 2026-09 experiments proved those signals broken for this
 * generator: gates saturate on wide-open boards, leaves are ~always zero, and
 * density is NOT monotonic with felt difficulty (high density = guided = easy;
 * low density = ambiguous = hard). See DifficultyExperiment + RouteAnalyzer.
 *
 * v2 measures decision difficulty on the unique-solution puzzle:
 *  - nearSolutionFull  — routes consuming ALL numbers in order yet failing the
 *    win condition ("looked finished, wasn't"). Log-scaled, capped at 256.
 *  - nearSolutionPrefix — dead-ends reached with ≥60% of numbers consumed.
 *  - (1 − forcedMoveRatio) — share of solution steps where the player faces a
 *    REAL choice instead of a single forced continuation.
 *  - meanDeviationDepth — how long a wrong turn survives before dead-ending
 *    (backtracking / decision depth).
 *  - avgBranching — legal options per step along the solution.
 *  - gateCount — articulation points (Zip-like choke points) created by edge
 *    walls; more gates = more route ordering must be threaded correctly.
 *
 * Number density, wall count and segment gaps are generator KNOBS, deliberately
 * NOT raw score inputs (they feed difficulty indirectly through the metrics).
 */
object DifficultyScorer {

    data class Score(
        val total: Int,
        val nearSolutionFull: Int,
        val nearSolutionPrefix: Int,
        val forcedMoveRatio: Float,
        val meanDeviationDepth: Float,
        val avgBranching: Float,
        val gateCount: Int
    )

    fun score(
        rows: Int,
        cols: Int,
        blocks: Set<Position>,
        edgeWalls: Set<WallEdge>,
        numbers: Map<Int, Position>
    ): Score {
        val a = RouteAnalyzer.analyze(rows, cols, numbers, blocks, edgeWalls)
        val nearF = ln(1f + minOf(a.nearSolutionFull, 256).toFloat()) / ln(257f)
        val nearP = ln(1f + minOf(a.nearSolutionPrefix, 256).toFloat()) / ln(257f)
        val dec = 1f - a.forcedMoveRatio
        val dev = minOf(a.meanDeviationDepth, 20f) / 20f
        val br = ((a.avgBranching - 1.2f) / 0.5f).coerceIn(0f, 1f)
        // Gates are strong but saturate fast; cap at 8 and weight modestly.
        val gate = (minOf(a.gateCount, 8).toFloat() / 8f)
        // Near-solution traps are the strongest "needs a hint" signal, but they
        // thin out at very low density. Deviation depth, branching and gates keep
        // rising as density drops + walls accumulate, so balancing them in keeps
        // the high-level curve from dipping.
        val totalScore =
            32f * nearF + 24f * nearP +
                14f * dec + 16f * dev + 8f * br + 6f * gate
        return Score(
            totalScore.toInt().coerceIn(0, 100),
            a.nearSolutionFull, a.nearSolutionPrefix,
            a.forcedMoveRatio, a.meanDeviationDepth, a.avgBranching,
            a.gateCount
        )
    }

    /**
     * Pick the candidate whose total is closest to [target] (primary criterion —
     * required), but on a near-tie (within 2 points) prefer the one that is
     * GENUINELY harder to play: higher deviation depth, higher branching, more
     * near-solution traps and more gates. This avoids shipping a candidate that
     * merely scores high on paper yet plays as a forced/guided corridor.
     */
    fun <T> pickBest(candidates: List<Pair<T, Score>>, target: Int): T? {
        if (candidates.isEmpty()) return null
        val best = candidates.minWithOrNull { a, b ->
            val da = kotlin.math.abs(a.second.total - target)
            val db = kotlin.math.abs(b.second.total - target)
            when {
                da != db -> da.compareTo(db)
                else -> playHardness(a.second).compareTo(playHardness(b.second))
            }
        } ?: return null
        return best.first
    }

    private fun playHardness(s: Score): Float =
        1f * s.meanDeviationDepth +
            1.5f * s.avgBranching +
            (minOf(s.nearSolutionFull, 50).toFloat() / 10f) +
            (minOf(s.nearSolutionPrefix, 50).toFloat() / 10f) +
            (0.5f * s.gateCount)
}