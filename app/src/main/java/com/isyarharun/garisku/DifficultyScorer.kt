package com.isyarharun.garisku

/**
 * Scores how HARD a challenge level is, from the structure of its open-cell
 * graph. Used by the exporter for best-of-N candidate selection.
 *
 * Score 0..100. Higher = harder.
 *
 * Core insight for cover-all-cells puzzles with DENSE numbers: every
 * not-yet-reachable number is an OBSTACLE. Dense checkpoints + wide open
 * board = the route must thread narrow gaps in exactly the right order —
 * the player must simulate the whole path mentally.
 *
 *  - Checkpoint density (share of open cells carrying a number): dominant.
 *    Dense numbers interlock as moving blockers.                      40%
 *  - Leaf cells (deg-1): each leaf MUST be a path endpoint; extra leaves
 *    create ordering puzzles.                                         25%
 *  - Gates / junctions: decision points where wrong turns dead-end.   20%
 *  - Tightness: smaller open ratio scores slightly higher.            15%
 */
object DifficultyScorer {

    data class Score(
        val total: Int,
        val leafRatio: Float,
        val gateCount: Int,
        val checkpointDensity: Float,
        val openRatio: Float
    )

    fun score(
        openCells: Set<Position>,
        rows: Int,
        cols: Int,
        blocks: Set<Position>,
        numbers: Map<Int, Position>
    ): Score {
        if (openCells.isEmpty()) return Score(0, 0f, 0, 0f, 0f)
        val n = openCells.size

        // ── Checkpoint density (dominant signal) ────────────────
        val density = (numbers.size.toFloat() / n).coerceIn(0f, 1f)
        val densityN = ((density - 0.25f) / 0.40f).coerceIn(0f, 1f)  // 25% → 0, 65% → 1

        // ── Degree stats: leaves & gates ────────────────────────
        var leaves = 0
        var gates = 0
        for (p in openCells) {
            var deg = 0
            for (nb in neighbours(p, rows, cols)) if (nb in openCells) deg++
            when {
                deg == 1 -> leaves++
                deg >= 3 -> gates++
            }
        }
        val leafRatio = leaves.toFloat() / n
        val leafN = (leafRatio / 0.20f).coerceIn(0f, 1f)              // 20% leaves → max
        val gateN = (gates.toFloat() / n * 3f).coerceIn(0f, 1f)

        // ── Tightness ───────────────────────────────────────────
        val openRatio = n.toFloat() / (rows * cols)
        val tightN = ((0.90f - openRatio) / 0.30f).coerceIn(0f, 1f)

        val totalScore = 40f * densityN + 25f * leafN + 20f * gateN + 15f * tightN
        return Score(
            totalScore.toInt().coerceIn(0, 100),
            leafRatio, gates, density, openRatio
        )
    }

    /** Pick the candidate whose score is closest to [target]. */
    fun <T> pickBest(candidates: List<Pair<T, Score>>, target: Int): T? {
        if (candidates.isEmpty()) return null
        return candidates.minByOrNull { (_, s) -> kotlin.math.abs(s.total - target) }?.first
    }

    private fun neighbours(p: Position, rows: Int, cols: Int): List<Position> = listOf(
        Position(p.row - 1, p.col), Position(p.row + 1, p.col),
        Position(p.row, p.col - 1), Position(p.row, p.col + 1)
    ).filter { it.row in 0 until rows && it.col in 0 until cols }
}
