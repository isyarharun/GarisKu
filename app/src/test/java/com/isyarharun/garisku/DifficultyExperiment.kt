package com.isyarharun.garisku

import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

/**
 * OFFLINE EXPERIMENT HARNESS (JVM only, never ships).
 * Guarded by env var GARISKU_EXP=1 so CI/regression runs skip it.
 *
 * Phase 0 — coupling: requested gap vs achieved density (placeNumbers behavior).
 * Phase A — one-axis sensitivity: density, gap variance, brick budget, grid size.
 * Phase B — full-curve comparison on probe levels (old curve vs breakpoint curves).
 *
 * Reports: app/build/reports/exp_*.txt
 */
class DifficultyExperiment {

    // ── helpers ─────────────────────────────────────────────────────────

    private fun out(name: String, content: String) {
        val f = java.io.File("build/reports/exp_$name.txt")
        f.parentFile?.mkdirs()
        f.writeText(content)
        println(">>> wrote exp_$name.txt")
        println(content)
    }

    private fun openSet(rows: Int, cols: Int, blocks: Set<Position>): Set<Position> = buildSet {
        for (r in 0 until rows) for (c in 0 until cols) {
            val p = Position(r, c)
            if (p !in blocks) add(p)
        }
    }

    /** true when placeNumbers fell back to sparse (requested segments don't fit). */
    private fun fellBack(rows: Int, cols: Int, numberCount: Int, minSeg: Int): Boolean {
        val total = rows * cols
        val steps = total - 1
        return steps < minSeg * (numberCount - 1)
    }

    /** mean gap between consecutive numbers along a route. */
    private fun gapsOf(solution: List<Position>, numbers: Map<Int, Position>): Pair<Float, Int> {
        if (numbers.size < 2) return 0f to 0
        val idx = HashMap<Position, Int>()
        solution.forEachIndexed { i, p -> idx[p] = i }
        val order = numbers.entries.sortedBy { it.key }
        var sum = 0
        var maxG = 0
        for (w in 0 until order.size - 1) {
            val a = idx[order[w].value] ?: return 0f to 0
            val b = idx[order[w + 1].value] ?: return 0f to 0
            val g = abs(b - a)
            sum += g; if (g > maxG) maxG = g
        }
        return sum.toFloat() / (order.size - 1) to maxG
    }

    private fun analyze(
        rows: Int, cols: Int, density: Float, maxBricks: Int,
        seed: Long, minSeg: Int = 2, maxSeg: Int = 3
    ): Triple<BrickLevel?, Int, RouteAnalyzer.Analysis?> {
        // generateBlockingLevel requires rows,cols >= 5
        val rr = maxOf(rows, 5); val cc = maxOf(cols, 5)
        return try {
            val brick = LevelGenerator.generateBlockingLevel(rr, cc, density, maxBricks, seed, minSeg, maxSeg)
            val a = RouteAnalyzer.analyze(rr, cc, brick.numberPositions, brick.blocks)
            Triple(brick, 0, a)
        } catch (e: Exception) {
            System.err.println("REJECT seed=$seed cfg=(${rows}x${cols},d=$density,g=$minSeg-$maxSeg): ${e.message}")
            Triple(null, 1, null)   // rejected: not unique within budget
        }
    }

    private fun header(t: String) = t +
        "cfg | rej | nums | dens | gMean | gMax | bricks | branch | frc | nearF | nearP | devMean | devMax | expExc\n"

    private fun row(
        cfg: String, rows: Int, cols: Int, res: Triple<BrickLevel?, Int, RouteAnalyzer.Analysis?>
    ): String {
        val (brick, rej, a) = res
        if (brick == null || a == null) return "%-22s | %3d | -\n".format(cfg, rej)
        val open = openSet(rows, cols, brick.blocks)
        val dens = brick.numberPositions.size.toFloat() / open.size
        val (gm, gx) = gapsOf(brick.path, brick.numberPositions)
        return ("%s | %3d | %4d | %.2f | %5.2f | %4d | %6d | %6.2f | %.2f | %5d | %5d | %7.2f | %6d | %b\n").format(
            cfg, rej, brick.numberPositions.size, dens, gm, gx, brick.blocks.size,
            a.avgBranching, a.forcedMoveRatio, a.nearSolutionFull, a.nearSolutionPrefix,
            a.meanDeviationDepth, a.maxDeviationDepth, a.exploreBudgetExhausted
        )
    }

    // ── Phase 0: gap-density coupling ───────────────────────────────────

    @Test
    fun phase0_coupling() {
        if (System.getenv("GARISKU_EXP") != "1") return
        val sb = StringBuilder("=== Phase 0: requested gap vs achieved (7x7, bricks=3) ===\n")
        sb.append("minSeg | maxSeg | minDensity feasible | requestedN(45%) fellBack | requestedN(34%) fellBack\n")
        val rows = 7; val cols = 7; val total = rows * cols
        for ((mn, mx) in listOf(2 to 3, 3 to 4, 4 to 5, 5 to 6)) {
            val n45 = (total * 0.45f).toInt()
            val n34 = (total * 0.34f).toInt()
            val fb45 = fellBack(rows, cols, n45, mn)
            val fb34 = fellBack(rows, cols, n34, mn)
            val minDens = mn.toFloat() * (n34 - 1) / (total - 1)
            sb.append("$mn | $mx | need density <= %.3f | $fb45 | $fb34\n".format(minDens))
        }
        // Direct placeNumbers probe on a serpentine path.
        sb.append("\nDirect placeNumbers probe (7x7 serpentine):\n")
        val path = buildList {
            for (r in 0 until rows) if (r % 2 == 0) for (c in 0 until cols) add(Position(r, c))
            else for (c in cols - 1 downTo 0) add(Position(r, c))
        }
        for ((mn, mx) in listOf(2 to 3, 3 to 4, 4 to 5, 5 to 6)) {
            for (n in listOf(24, 20, 16, 12, 9)) {
                val nums = LevelGenerator.placeNumbers(path, n, 42L, mn, mx)
                // effective mean gap = (pathLen-1)/(nums-1) even when fallback
                val eff = (path.size - 1).toFloat() / (nums.size - 1)
                sb.append("gap($mn,$mx) n=$n -> placed=${nums.size} (fallback=${
                    fellBack(rows, cols, n, mn)}) effMeanGap=%.2f\n".format(eff))
            }
        }
        out("phase0", sb.toString())
    }

    // ── Phase A: one-axis sensitivity at a fixed level-ish config ───────

    @Test
    fun phaseA_sensitivity() {
        if (System.getenv("GARISKU_EXP") != "1") return
        val probeSeeds = 0 until 8

        // A1: density sweep (7x7, gap 2..4, bricks 3)
        val sb1 = StringBuilder("=== A1 density sweep (7x7, gap 2-4, bricks 3, 8 seeds) ===\n")
        sb1.append(header("cfg"))
        for (d in listOf(0.48f, 0.43f, 0.40f, 0.37f, 0.34f)) {
            var rej = 0
            val rows = 7; val cols = 7
            val agg = FloatArray(6); var n = 0
            for (k in probeSeeds) {
                val r = analyze(rows, cols, d, 3, 1000L + k * 7919L, 2, 4)
                if (r.second == 1) rej++
                val a = r.third ?: continue
                agg[0] += a.avgBranching; agg[1] += a.forcedMoveRatio
                agg[2] += a.nearSolutionFull; agg[3] += a.nearSolutionPrefix
                agg[4] += a.meanDeviationDepth; agg[5] += a.maxDeviationDepth
                n++
            }
            if (n > 0) {
                sb1.append(
                    "d=%.2f      | avg: branch=%.2f frc=%.2f nearF=%.1f nearP=%.1f devM=%.2f devX=%.1f rej=%d/%d\n"
                        .format(d, agg[0] / n, agg[1] / n, agg[2] / n, agg[3] / n, agg[4] / n, agg[5] / n, rej, probeSeeds.count())
                )
            } else sb1.append("d=%.2f      | all rejected (rej=%d)\n".format(d, rej))
        }
        out("a1_density", sb1.toString())

        // A2: gap variance sweep (7x7, density 0.40, bricks 3)
        val sb2 = StringBuilder("=== A2 gap sweep (7x7, density 0.40, bricks 3, 8 seeds) ===\n")
        sb2.append(header("cfg"))
        for ((mn, mx) in listOf(2 to 3, 2 to 4, 2 to 5, 1 to 5)) {
            var rej = 0
            val rows = 7; val cols = 7
            val agg = FloatArray(6); var n = 0
            for (k in probeSeeds) {
                val r = analyze(rows, cols, 0.40f, 3, 2000L + k * 7919L, mn, mx)
                if (r.second == 1) rej++
                val a = r.third ?: continue
                agg[0] += a.avgBranching; agg[1] += a.forcedMoveRatio
                agg[2] += a.nearSolutionFull; agg[3] += a.nearSolutionPrefix
                agg[4] += a.meanDeviationDepth; agg[5] += a.maxDeviationDepth
                n++
            }
            if (n > 0) {
                sb2.append(
                    "g($mn,$mx)    | avg: branch=%.2f frc=%.2f nearF=%.1f nearP=%.1f devM=%.2f devX=%.1f rej=%d/%d\n"
                        .format(agg[0] / n, agg[1] / n, agg[2] / n, agg[3] / n, agg[4] / n, agg[5] / n, rej, probeSeeds.count())
                )
            } else sb2.append("g($mn,$mx)    | all rejected\n")
        }
        out("a2_gap", sb2.toString())

        // A3: brick budget sweep (7x7, density 0.40, gap 2..4)
        val sb3 = StringBuilder("=== A3 bricks sweep (7x7, density 0.40, gap 2-4, 8 seeds) ===\n")
        sb3.append(header("cfg"))
        for (b in 0..3) {
            var rej = 0
            val rows = 7; val cols = 7
            val agg = FloatArray(6); var n = 0
            for (k in probeSeeds) {
                val r = analyze(rows, cols, 0.40f, b, 3000L + k * 7919L, 2, 4)
                if (r.second == 1) rej++
                val a = r.third ?: continue
                agg[0] += a.avgBranching; agg[1] += a.forcedMoveRatio
                agg[2] += a.nearSolutionFull; agg[3] += a.nearSolutionPrefix
                agg[4] += a.meanDeviationDepth; agg[5] += a.maxDeviationDepth
                n++
            }
            if (n > 0) {
                sb3.append(
                    "avg: branch=%.2f frc=%.2f nearF=%.1f nearP=%.1f devM=%.2f devX=%.1f rej=%d/%d\n"
                        .format(agg[0] / n, agg[1] / n, agg[2] / n, agg[3] / n, agg[4] / n, agg[5] / n, rej, probeSeeds.count())
                )
            } else sb3.append("bricks=$b   | all rejected\n")
        }
        out("a3_bricks", sb3.toString())

        // A4: grid sweep (gap 2..4, bricks 3)
        val sb4 = StringBuilder("=== A4 grid sweep (gap 2-4, bricks 3, 8 seeds) ===\n")
        sb4.append(header("cfg"))
        for ((g, d) in listOf(6 to 0.44f, 7 to 0.40f, 8 to 0.36f)) {
            var rej = 0
            val agg = FloatArray(6); var n = 0
            for (k in probeSeeds) {
                val r = analyze(g, g, d, 3, 4000L + k * 7919L, 2, 4)
                if (r.second == 1) rej++
                val a = r.third ?: continue
                agg[0] += a.avgBranching; agg[1] += a.forcedMoveRatio
                agg[2] += a.nearSolutionFull; agg[3] += a.nearSolutionPrefix
                agg[4] += a.meanDeviationDepth; agg[5] += a.maxDeviationDepth
                n++
            }
            if (n > 0) {
                sb4.append(
                    "%dx%d d=%.2f | avg: branch=%.2f frc=%.2f nearF=%.1f nearP=%.1f devM=%.2f devX=%.1f rej=%d/%d\n"
                        .format(g, g, d, agg[0] / n, agg[1] / n, agg[2] / n, agg[3] / n, agg[4] / n, agg[5] / n, rej, probeSeeds.count())
                )
            } else sb4.append("%dx%d d=%.2f | all rejected\n".format(g, g, d))
        }
        out("a4_grid", sb4.toString())
    }

    // ── Phase B: full curves on probe levels ────────────────────────────

    private val probeLevels = listOf(1, 5, 7, 9, 10, 15, 20, 30, 40, 50)

    private data class Curve(val name: String, val cfg: (Int) -> Quad)
    private data class Quad(val rows: Int, val cols: Int, val density: Float, val gap: Pair<Int, Int>)

    /** Old curve, verbatim from current LevelFactory. */
    private val curveZ = Curve("Z_old") { level ->
        val t = (level - 1) / 49f
        val size = (6 + (t * 2).toInt()).coerceIn(6, 8)
        Quad(size, size, 0.45f + 0.17f * t, 2 to 3)
    }

    /** Breakpoint curve: config jumps at L10; density down, variance up (A1/A2/A4-informed). */
    private val curveX = Curve("X_break10") { level ->
        when {
            level <= 5 -> Quad(6, 6, 0.47f, 2 to 3)
            level <= 9 -> Quad(7, 7, 0.42f, 2 to 4)
            level <= 15 -> Quad(7, 7, 0.38f, 2 to 5)
            level <= 35 -> Quad(8, 8, 0.36f, 2 to 5)
            else -> Quad(8, 8, 0.34f, 2 to 6)
        }
    }

    /** Aggressive-low curve: even lower density (rejection risk measured here). */
    private val curveY = Curve("Y_aggrLow") { level ->
        when {
            level <= 5 -> Quad(6, 6, 0.44f, 2 to 3)
            level <= 9 -> Quad(7, 7, 0.40f, 2 to 5)
            level <= 15 -> Quad(7, 7, 0.34f, 2 to 5)
            level <= 35 -> Quad(8, 8, 0.32f, 2 to 6)
            else -> Quad(8, 8, 0.30f, 2 to 6)
        }
    }

    @Test
    fun phaseB_curves() {
        if (System.getenv("GARISKU_EXP") != "1") return
        val sb = StringBuilder()
        for (curve in listOf(curveZ, curveX, curveY)) {
            sb.append("=== Curve ${curve.name} ===\n")
            sb.append(header("Lvl"))
            for (level in probeLevels) {
                val q = curve.cfg(level)
                val target = when (curve.name) {
                    "Z_old" -> (55 + (level - 1) / 49f * 43).toInt()
                    else -> curveTargetX(level)
                }
                var rej = 0
                var best: RouteAnalyzer.Analysis? = null
                var bestBrick: BrickLevel? = null
                var bestScore = -1f
                for (i in 0 until 16) {
                    val seed = LevelGenerator.seedFor(GameMode.CHALLENGE, level) + i * 7919L
                    val r = analyze(q.rows, q.cols, q.density, 3, seed, q.gap.first, q.gap.second)
                    if (r.second == 1) { rej++; continue }
                    val a = r.third ?: continue
                    val brick = r.first ?: continue
                    val s = probeScore(a)
                    if (s > bestScore) { bestScore = s; best = a; bestBrick = brick }
                }
                if (best == null || bestBrick == null) {
                    sb.append("%3d | ALL REJECTED rej=%d/16\n".format(level, rej))
                    continue
                }
                val open = openSet(q.rows, q.cols, bestBrick.blocks)
                val dens = bestBrick.numberPositions.size.toFloat() / open.size
                val (gm, gx) = gapsOf(bestBrick.path, bestBrick.numberPositions)
                sb.append(
                    ("%3d | rej=%2d | %4d | %.2f | %5.2f | %4d | %6d | %6.2f | %.2f | %5d | %5d | %7.2f | %6d | tgt=%3d\n")
                        .format(
                            level, rej, bestBrick.numberPositions.size, dens, gm, gx,
                            bestBrick.blocks.size, best.avgBranching, best.forcedMoveRatio,
                            best.nearSolutionFull, best.nearSolutionPrefix,
                            best.meanDeviationDepth, best.maxDeviationDepth, target
                        )
                )
            }
            sb.append("\n")
        }
        out("phaseB", sb.toString())
    }

    /** Piecewise target for X/Y curves (breakpoint at 10). */
    private fun curveTargetX(level: Int): Int = when {
        level <= 5 -> 18 + (level - 1) * 2          // 18..26 easy
        level == 6 -> 30
        level == 7 -> 38
        level == 8 -> 46
        level == 9 -> 55
        level == 10 -> 70                            // the jump
        level <= 20 -> 70 + (level - 10) * 2         // 70..88 hardcore
        level <= 35 -> 88 + (level - 20)             // 88..103 → clamp
        else -> 103 + (level - 35)                   // extreme
    }.coerceAtMost(120)

    /** Probe scorer for curve comparison (pre-calibration). */
    private fun probeScore(a: RouteAnalyzer.Analysis): Float {
        val nearFull = if (a.nearSolutionFull > 0) ln(a.nearSolutionFull.toDouble()).toFloat() else 0f
        val nearPre = if (a.nearSolutionPrefix > 0) ln(a.nearSolutionPrefix.toDouble()).toFloat() else 0f
        return nearFull * 8f + nearPre * 8f + (1f - a.forcedMoveRatio) * 30f +
            a.avgBranching * 5f + a.meanDeviationDepth * 6f
    }

    // ── Phase C: FINAL curve validation + scorer-v2 distribution ────────

    /**
     * Scorer v2 (probe): decision-difficulty only. Density/bricks are
     * generator knobs, NOT score inputs. Normalized 0..100.
     */
    private fun scorerV2(a: RouteAnalyzer.Analysis): Float {
        val nearF = ln(1f + minOf(a.nearSolutionFull, 256).toFloat()) / ln(257f)
        val nearP = ln(1f + minOf(a.nearSolutionPrefix, 256).toFloat()) / ln(257f)
        val dec = 1f - a.forcedMoveRatio
        val dev = minOf(a.meanDeviationDepth, 20f) / 20f
        val br = ((a.avgBranching - 1.2f) / 0.5f).coerceIn(0f, 1f)
        return 30f * nearF + 20f * nearP + 20f * dec + 20f * dev + 10f * br
    }

    /** FINAL candidate curve — late bands raised for healthy rejection. */
    private val curveF = Curve("F_final") { level ->
        when {
            level <= 3 -> Quad(6, 6, 0.55f, 2 to 3)     // learning: guided
            level <= 5 -> Quad(6, 6, 0.50f, 2 to 3)     // easy+
            level <= 7 -> Quad(7, 7, 0.46f, 2 to 4)     // medium
            level <= 9 -> Quad(7, 7, 0.42f, 2 to 4)     // hard
            level <= 15 -> Quad(7, 7, 0.38f, 2 to 5)    // HARDCORE breakpoint
            level <= 35 -> Quad(8, 8, 0.40f, 2 to 5)    // very hard
            else -> Quad(8, 8, 0.38f, 2 to 5)           // extreme
        }
    }

    @Test
    fun phaseC_finalCurve() {
        if (System.getenv("GARISKU_EXP") != "1") return
        val levels = listOf(1, 3, 5, 7, 9, 10, 12, 15, 20, 30, 40, 50)
        val sb = StringBuilder("=== Phase C: final curve F — scorer v2 distribution (16 cand) ===\n")
        sb.append("Lvl | rej | p10 | p50 | p90 | max | (metrics of max-scoring candidate)\n")
        for (level in levels) {
            val q = curveF.cfg(level)
            val scores = mutableListOf<Float>()
            var rej = 0
            var best: RouteAnalyzer.Analysis? = null
            var bestBrick: BrickLevel? = null
            var bestS = -1f
            for (i in 0 until 16) {
                val seed = LevelGenerator.seedFor(GameMode.CHALLENGE, level) + i * 7919L
                val r = analyze(q.rows, q.cols, q.density, 3, seed, q.gap.first, q.gap.second)
                if (r.second == 1) { rej++; continue }
                val a = r.third ?: continue
                val brick = r.first ?: continue
                val s = scorerV2(a)
                scores.add(s)
                if (s > bestS) { bestS = s; best = a; bestBrick = brick }
            }
            if (scores.isEmpty() || best == null || bestBrick == null) {
                sb.append("%3d | rej=%2d | ALL REJECTED\n".format(level, rej))
                continue
            }
            scores.sort()
            fun pct(p: Float): Float {
                val idx = (p * (scores.size - 1)).toInt().coerceIn(0, scores.size - 1)
                return scores[idx]
            }
            val open = openSet(q.rows, q.cols, bestBrick.blocks)
            val dens = bestBrick.numberPositions.size.toFloat() / open.size
            sb.append(
                ("%3d | %2d/%2d | %5.1f | %5.1f | %5.1f | %5.1f | nums=%2d dens=%.2f frc=%.2f nearF=%3d nearP=%3d devM=%5.1f devX=%2d br=%.2f\n")
                    .format(
                        level, rej, 16, pct(0.10f), pct(0.50f), pct(0.90f), scores.last(),
                        bestBrick.numberPositions.size, dens, best.forcedMoveRatio,
                        best.nearSolutionFull, best.nearSolutionPrefix,
                        best.meanDeviationDepth, best.maxDeviationDepth, best.avgBranching
                    )
            )
        }
        out("phaseC", sb.toString())
    }
}
