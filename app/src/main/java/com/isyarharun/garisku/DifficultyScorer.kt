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
 *
 * Bricks and number density are generator KNOBS, deliberately NOT score inputs.
 */
object DifficultyScorer {

    data class Score(
        val total: Int,
        val nearSolutionFull: Int,
        val nearSolutionPrefix: Int,
        val forcedMoveRatio: Float,
        val meanDeviationDepth: Float,
        val avgBranching: Float
    )

    fun score(
        rows: Int,
        cols: Int,
        blocks: Set<Position>,
        numbers: Map<Int, Position>
    ): Score {
        val a = RouteAnalyzer.analyze(rows, cols, numbers, blocks)
        val nearF = ln(1f + minOf(a.nearSolutionFull, 256).toFloat()) / ln(257f)
        val nearP = ln(1f + minOf(a.nearSolutionPrefix, 256).toFloat()) / ln(257f)
        val dec = 1f - a.forcedMoveRatio
        val dev = minOf(a.meanDeviationDepth, 20f) / 20f
        val br = ((a.avgBranching - 1.2f) / 0.5f).coerceIn(0f, 1f)
        val totalScore = 30f * nearF + 20f * nearP + 20f * dec + 20f * dev + 10f * br
        return Score(
            totalScore.toInt().coerceIn(0, 100),
            a.nearSolutionFull, a.nearSolutionPrefix,
            a.forcedMoveRatio, a.meanDeviationDepth, a.avgBranching
        )
    }

    /** Pick the candidate whose score is closest to [target]. */
    fun <T> pickBest(candidates: List<Pair<T, Score>>, target: Int): T? {
        if (candidates.isEmpty()) return null
        return candidates.minByOrNull { (_, s) -> kotlin.math.abs(s.total - target) }?.first
    }
}
