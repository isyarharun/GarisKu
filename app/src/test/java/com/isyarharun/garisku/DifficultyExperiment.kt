package com.isyarharun.garisku

import org.junit.Test
import kotlin.math.ceil

/**
 * OFFLINE EXPERIMENT HARNESS for the PRODUCTION generator (JVM only, never ships).
 * Guarded by env var GARISKU_EXP=1 so CI/regression runs skip it.
 *
 * Retrofitted 2026-09 after the pipeline moved to LevelGenerator.generateMazeWallLevel
 * (edge-wall chains + uniqueness convergence). The old phases (0/A/B/C) measured the
 * superseded generateBlockingLevel/generateBrickLevel families and curveZ/X/Y/F — those
 * generators are gone, so the harness now validates the LIVE pipeline:
 *
 *  Phase A — convergence health: can the uniqueness hard-gate (countOrderedPaths == 1)
 *           actually be satisfied per level within the candidate budget? Reports
 *           how many of the budgeted seeds converge to a UNIQUE solvable level.
 *  Phase B — difficulty distribution: for the UNIQUE candidates per level, what range of
 *           DifficultyScorer v2 scores is achievable? Used to sanity-check that
 *           challengeTargetFor anchors fall inside the achievable band.
 *
 * Reports: app/build/reports/exp_prod_*.txt
 */
class DifficultyExperiment {

    private fun out(name: String, content: String) {
        val f = java.io.File("build/reports/$name")
        f.parentFile?.mkdirs()
        f.writeText(content)
        println(">>> wrote $name")
        println(content)
    }

    /** Build one candidate via the production path; returns null if no maze path. */
    private fun buildCandidate(level: Int, seed: Long): GameState? =
        LevelFactory.buildWithSeedOrNull(GameMode.CHALLENGE, level, seed)

    private fun routeCount(gs: GameState): Int =
        LevelGenerator.countOrderedPaths(gs.rows, gs.cols, gs.numberPositions, gs.blocks, stopAfter = 2, edgeWalls = gs.edgeWalls)

    // ── Phase A: convergence health of the uniqueness gate ────────────────────

    @Test
    fun phaseA_convergence() {
        if (System.getenv("GARISKU_EXP") != "1") return
        val probeLevels = listOf(1, 10, 15, 25, 26, 35, 50)
        val sb = StringBuilder("=== Phase A: production uniqueness-gate convergence health ===\n")
        sb.append("Lvl | tier | grid | cntRng | cand | unique | nonUniq | reject | min-walls | avg-walls\n")
        for (level in probeLevels) {
            val (rows, cols) = LevelFactory.gridSizeFor(level)
            val countRange = "8-10"
            val n = LevelFactory.candidatesFor(level)
            var unique = 0; var nonUniq = 0; var rej = 0
            var wallsSum = 0L; var minWalls = Int.MAX_VALUE; var acc = 0
            for (i in 0 until n) {
                val seed = LevelGenerator.seedFor(GameMode.CHALLENGE, level) + i * 7919L
                val gs = buildCandidate(level, seed)
                if (gs == null) { rej++; continue }
                val routes = routeCount(gs)
                when {
                    routes == 1 -> { unique++; wallsSum += gs.edgeWalls.size; minWalls = minOf(minWalls, gs.edgeWalls.size); acc++ }
                    routes >= 2 -> { nonUniq++; wallsSum += gs.edgeWalls.size; minWalls = minOf(minWalls, gs.edgeWalls.size); acc++ }
                    else -> rej++
                }
            }
            sb.append(
                ("L%2d | %-9s | %dx%d | %5s | %3d | %4d | %4d | %4d | %6s | %6.1f\n").format(
                    level, LevelFactory.tierFor(level).name, rows, cols, countRange, n,
                    unique, nonUniq, rej,
                    if (minWalls == Int.MAX_VALUE) "-" else minWalls.toString(),
                    if (acc > 0) wallsSum.toFloat() / acc else 0f
                )
            )
        }
        out("exp_prod_convergence.txt", sb.toString())
    }

    // ── Phase B: achieved difficulty band vs target anchors ──────────────────

    @Test
    fun phaseGridCountSample() {
        if (System.getenv("GARISKU_EXP") != "1") return
        val out = java.io.File("build/reports/exp_grid_count_sample.txt")
        out.parentFile?.mkdirs()
        out.writeText("level|grid|count|walls|buildMs|routeCount|routeMs|score\n")
        for (level in listOf(1, 10, 25, 26, 35, 50)) {
            val seed = LevelGenerator.seedFor(GameMode.CHALLENGE, level)
            val t0 = System.currentTimeMillis()
            val gs = LevelFactory.buildWithSeed(GameMode.CHALLENGE, level, seed)
            val buildMs = System.currentTimeMillis() - t0
            val t1 = System.currentTimeMillis()
            val known = gs.solutionPath ?: error("missing solution path")
            val directValid = LevelGenerator.validateKnownSolution(
                gs.rows, gs.cols, gs.numberPositions, gs.blocks, gs.edgeWalls, known
            )
            val alternative = LevelGenerator.findAlternativeOrderedPath(
                gs.rows, gs.cols, gs.numberPositions, gs.blocks, gs.edgeWalls, known
            )
            val routeMs = System.currentTimeMillis() - t1
            val score = DifficultyScorer.score(gs.rows, gs.cols, gs.blocks, gs.edgeWalls, gs.numberPositions).total
            out.appendText("$level|${gs.rows}x${gs.cols}|${gs.totalNumbers}|${gs.edgeWalls.size}|$buildMs|valid=$directValid|alt=${alternative.alternativeFound}|budget=${alternative.budgetExhausted}|$routeMs|$score\n")
            println("grid-sample: level=$level grid=${gs.rows}x${gs.cols} count=${gs.totalNumbers} valid=$directValid alternative=${alternative.alternativeFound} budget=${alternative.budgetExhausted} routeMs=$routeMs score=$score")
        }
    }

    @Test
    fun phaseB_difficultyBand() {
        if (System.getenv("GARISKU_EXP") != "1") return
        val sb = StringBuilder("=== Phase B: production DifficultyScorer v2 band vs target (unique candidates only) ===\n")
        sb.append("Lvl | tier | grid | target | unique | p10 | p50 | p90 | max | fit\n")
        for (level in 1..LevelFactory.LEVEL_COUNT) {
            val (rows, cols) = LevelFactory.gridSizeFor(level)
            val target = LevelFactory.challengeTargetFor(level)
            val n = LevelFactory.candidatesFor(level)
            val scores = mutableListOf<Int>()
            for (i in 0 until n) {
                val seed = LevelGenerator.seedFor(GameMode.CHALLENGE, level) + i * 7919L
                val gs = buildCandidate(level, seed) ?: continue
                if (routeCount(gs) != 1) continue   // only UNIQUE candidates count
                scores.add(DifficultyScorer.score(gs.rows, gs.cols, gs.blocks, gs.edgeWalls, gs.numberPositions).total)
            }
            if (scores.isEmpty()) {
                sb.append("L%3d | %-9s | %dx%d | %3d |    0 | NO UNIQUE CANDIDATE\n".format(level, LevelFactory.tierFor(level).name, rows, cols, target))
                continue
            }
            scores.sort()
            fun pct(p: Float): Int { val idx = (p * (scores.size - 1)).toInt().coerceIn(0, scores.size - 1); return scores[idx] }
            val inBand = target in pct(0.10f)..pct(0.90f)
            val fit = if (target >= pct(0.50f) && target <= pct(0.90f)) "good" else if (inBand) "in-band" else "OFF"
            sb.append(
                ("L%3d | %-9s | %dx%d | %3d | %5d | %4d | %4d | %4d | %4d | %s\n").format(
                    level, LevelFactory.tierFor(level).name, rows, cols, target, scores.size,
                    pct(0.10f), pct(0.50f), pct(0.90f), scores.last(), fit
                )
            )
        }
        out("exp_prod_band.txt", sb.toString())
    }
}