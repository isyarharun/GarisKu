package com.isyarharun.garisku

import kotlin.random.Random

/**
 * Difficulty design for 100 levels per mode.
 *
 * SIMPLE — grid & waypoint growth:
 *   L1-20:  4x4 → 5x5, many waypoints (guided)
 *   L21-50: 5x5 → 7x7, fewer waypoints (planning starts)
 *   L51-100: 7x7 → 8x8, sparse waypoints (long segments)
 *
 * CHALLENGE — UNIQUE-SOLUTION puzzles: dense numbers that block each other,
 * carved by wall segments, with the route count verified = 1 at export time.
 * The player must plan the entire path from the start — a single wrong
 * step dead-ends, because there is exactly one valid route.
 *   Grid:        5x5 → 8x8 (caps at L75)
 *   Number density of open cells: 35% → 55% (numbers as obstacles)
 *   Open ratio:  88% → 70% (walls)
 *   Target difficulty rises monotonically 30 → 95.
 */
object LevelFactory {

    const val LEVEL_COUNT = 50

    /** Candidates per level in the exporter's calibration loop. */
    const val CANDIDATES_PER_LEVEL = 32

    /** Difficulty tiers following the Zip pattern (from the reference screenshots):
     *  Easy = many numbers (guided), Hard = few numbers (ambiguous). Density is
     *  relative to grid, so Easy-6x6 and Easy-8x8 feel equally "guided".
     */
    enum class ChallengeTier { EASY, EASY_MED, MEDIUM, MED_HARD, HARD }

    fun tierFor(level: Int): ChallengeTier = when {
        level <= 10 -> ChallengeTier.EASY
        level <= 15 -> ChallengeTier.EASY_MED
        level <= 25 -> ChallengeTier.MEDIUM
        level <= 35 -> ChallengeTier.MED_HARD
        else -> ChallengeTier.HARD
    }

    /** Candidate budget per tier. Walls-first generation is expensive, so keep
     *  budgets low to keep export time sane; best-of-N still finds a good pick. */
    fun candidatesFor(level: Int): Int = when (tierFor(level)) {
        ChallengeTier.HARD, ChallengeTier.MED_HARD -> 24
        ChallengeTier.MEDIUM -> 20
        else -> 16
    }

    /** Grid size follows level progression, staying in 6x6..8x8 (Zip reference).
     *  Early levels are 6x6, later levels 8x8 — but difficulty tier is
     *  independent, so a late Hard level can still be 8x8 and vice versa. */
    fun gridSizeFor(level: Int): Pair<Int, Int> = when {
        level <= 25 -> 6 to 6
        else -> 8 to 8
    }

    /** SIMPLE: waypoint count. Dense early, sparse later. */
    fun simpleNumbersFor(level: Int, rows: Int, cols: Int): Int {
        val cells = rows * cols
        val t = (level - 1) / 49f
        val density = 0.42f - 0.25f * t                 // 42% → 17% of cells
        return (cells * density).toInt().coerceIn(4, cells / 2)
    }

    /**
     * CHALLENGE per-band config. Empirically calibrated (2026-09 experiments,
     * see RouteAnalyzer + DifficultyExperiment):
     *  - LOWER number density = HIGHER decision difficulty (near-solutions,
     *    deviation depth, branching all rise as density drops 0.55→0.38).
     *    High density (0.55+) reads as GUIDED = easy → reserved for L1–5.
     *  - Wider segment-gap VARIANCE (2..5) creates alternating clusters and
     *    free stretches = more ambiguity than uniform gaps.
     *  - Bricks stay capped at 3: they exist to converge uniqueness, NOT as
     *    difficulty (A3: metrics identical for budgets 1–3).
     *  - L10 breakpoint: density 0.42→0.38 + variance 2..5 = HARDCORE jump.
     */
    data class ChallengeParams(
        val numberDensity: Float,   // share of cells carrying a number (from Zip refs)
        val minSeg: Int,
        val maxSeg: Int,            // higher = longer blind stretches
        val wallPieces: Int,        // number of wall chains
        val maxChainLen: Int,       // max edges per chain (L/T shape driver)
        val allowBranch: Boolean    // T-shape allowed
    )

    /**
     * Zip-reference config, tier-based. Walls-first architecture: densities now
     * match the screenshots exactly (Easy ~27%, Medium ~20-22%, Hard ~15-17%)
     * because solvability comes from finding a Hamiltonian path through the
     * maze, NOT from density-driven convergence. Difficulty rides on wall
     * chains (straight → L → T), their count, and the segment gaps.
     */
    fun challengeConfigFor(level: Int, rows: Int, cols: Int): ChallengeParams {
        val big = rows * cols >= 64
        return when (tierFor(level)) {
            // UNIQUENESS is now guaranteed by LevelGenerator.generateMazeWallLevel
            // (it walls away alternative routes until countOrderedPaths == 1).
            // Density is deliberately kept ABOVE the 8×8 convergence floor (~0.24)
            // so the route counter reliably finds every alternative — below it the
            // sparse cover-all search gives up under budget and leaves non-unique
            // levels (see puzzle-difficulty-tuning: "raise the 8×8 floor"). Base
            // wall pieces are bumped so early levels stay computationally cheap to
            // converge (fewer base alternatives = fewer convergence iterations).
            ChallengeTier.EASY -> ChallengeParams(if (big) 0.28f else 0.26f, 2, 4, if (big) 6 else 6, 2, false)
            ChallengeTier.EASY_MED -> ChallengeParams(if (big) 0.26f else 0.25f, 2, 5, if (big) 8 else 7, 3, false)
            ChallengeTier.MEDIUM -> ChallengeParams(if (big) 0.24f else 0.23f, 2, 6, if (big) 9 else 8, 3, true)
            ChallengeTier.MED_HARD -> ChallengeParams(if (big) 0.26f else 0.23f, 2, 8, if (big) 11 else 9, 4, true)
            ChallengeTier.HARD -> ChallengeParams(if (big) 0.24f else 0.22f, 2, 10, if (big) 12 else 10, 5, true)
        }
    }

    /**
     * Difficulty target curve, re-anchored to the ACHIEVABLE band of the
     * production generator (measured 2026-09, see DifficultyExperiment Phase B on
     * the live maze-wall pipeline — not the retired generateBlockingLevel). The
     * old anchors (15→94 with a HARDCORE jump at L10) were calibrated against the
     * brick generator's range and are now impossible or trivially-easy:
     * 6×6 can only reach ~40–57 (p50), 8×8 ~55–77 (p50, ceiling ~82).
     *
     * New anchors sit between the median and p90 of each band so best-of-N lands a
     * genuinely hard candidate without starving the pool:
     *   6×6 (L1–25):       48 → 56      — smooth early ramp, no fake L10 spike
     *   L26 grid bump:     jumps to 64  (6×6 → 8×8 is where difficulty really rises)
     *   8×8 (L26–50):      64 → 76      — the true hardcore tail
     *
     * L1 Easy · L9 Medium · L15 Medium+ · L25 Medium++ · L26 jump · L50 Hardcore.
     */
    fun challengeTargetFor(level: Int): Int {
        val anchors = listOf(
            1 to 48, 3 to 50, 5 to 52, 7 to 54, 9 to 55,
            15 to 56, 20 to 56, 25 to 56,          // 6×6 band ceiling ~56
            26 to 64,                               // grid bump (6×6 → 8×8)
            30 to 67, 35 to 69, 40 to 72, 45 to 74, 50 to 76
        )
        if (level <= anchors.first().first) return anchors.first().second
        for (i in 0 until anchors.size - 1) {
            val (l0, v0) = anchors[i]
            val (l1, v1) = anchors[i + 1]
            if (level in l0..l1) {
                val f = (level - l0).toFloat() / (l1 - l0)
                return (v0 + f * (v1 - v0)).toInt()
            }
        }
        return anchors.last().second
    }

    /**
     * Build a level deterministically from its seed (single candidate).
     * Returns null when the blocking generator fails to converge to a
     * unique solution with the given seed (exporter tries other seeds).
     */
    fun buildWithSeedOrNull(mode: GameMode, level: Int, seed: Long): GameState? {
        val (gridRows, gridCols) = gridSizeFor(level)
        return when (mode) {
            GameMode.SIMPLE -> {
                val numbers = simpleNumbersFor(level, gridRows, gridCols)
                val positions = LevelGenerator.generateSimplePath(gridRows, gridCols, numbers, seed)
                GameState(gridRows, gridCols, positions, numbers, mode)
            }
            GameMode.CHALLENGE -> {
                val cfg = challengeConfigFor(level, gridRows, gridCols)
                try {
                    val wall = LevelGenerator.generateMazeWallLevel(
                        gridRows, gridCols, cfg.numberDensity, cfg.wallPieces,
                        cfg.maxChainLen, cfg.allowBranch, seed,
                        cfg.minSeg, cfg.maxSeg
                    )
                    val numbers = wall.numberPositions.size
                    GameState(gridRows, gridCols, wall.numberPositions, numbers, mode, edgeWalls = wall.edgeWalls)
                } catch (e: IllegalStateException) {
                    null   // candidate rejected — no Hamiltonian path through maze
                }
            }
        }
    }

    /**
     * Build a level deterministically from its seed (single candidate).
     * Used by the exporter's calibration loop and verification test.
     */
    fun buildWithSeed(mode: GameMode, level: Int, seed: Long): GameState {
        val (gridRows, gridCols) = gridSizeFor(level)
        return when (mode) {
            GameMode.SIMPLE -> {
                val numbers = simpleNumbersFor(level, gridRows, gridCols)
                val positions = LevelGenerator.generateSimplePath(gridRows, gridCols, numbers, seed)
                GameState(gridRows, gridCols, positions, numbers, mode)
            }
            GameMode.CHALLENGE -> {
                val cfg = challengeConfigFor(level, gridRows, gridCols)
                val wall = LevelGenerator.generateMazeWallLevel(
                    gridRows, gridCols, cfg.numberDensity, cfg.wallPieces,
                    cfg.maxChainLen, cfg.allowBranch, seed,
                    cfg.minSeg, cfg.maxSeg
                )
                val numbers = wall.numberPositions.size
                GameState(gridRows, gridCols, wall.numberPositions, numbers, mode, edgeWalls = wall.edgeWalls)
            }
        }
    }

    /**
         * CHALLENGE best-of-N: generate candidates with different seeds, score
         * each with DifficultyScorer, and keep the UNIQUE one (countOrderedPaths ==
         * 1) closest to the level's target difficulty. Runs at EXPORT time on a PC —
         * not on the phone. Uniqueness is a hard gate here (the generator drives
         * toward uniqueness; this selection seals it by only accepting routes == 1).
         */
        fun build(mode: GameMode, level: Int): GameState {
            if (mode == GameMode.SIMPLE) return buildWithSeed(mode, level, seed = LevelGenerator.seedFor(mode, level))

            val target = challengeTargetFor(level)
        val n = candidatesFor(level)                       // honours tier budgets
        data class Cand(val seed: Long, val gs: GameState, val score: DifficultyScorer.Score)
        val candidates = mutableListOf<Cand>()

        for (i in 0 until n) {
            val seed = LevelGenerator.seedFor(mode, level) + i * 7919L
            val gs = buildWithSeedOrNull(mode, level, seed) ?: continue   // no path through maze
            val routes = LevelGenerator.countOrderedPaths(
                gs.rows, gs.cols, gs.numberPositions, gs.blocks, stopAfter = 2, edgeWalls = gs.edgeWalls
            )
            if (routes != 1) continue   // UNIQUENESS HARD GATE: only unique candidates ship
            val score = DifficultyScorer.score(gs.rows, gs.cols, gs.blocks, gs.edgeWalls, gs.numberPositions)
            candidates.add(Cand(seed, gs, score))
        }
        if (candidates.isEmpty()) {
            error("Level $level: no UNIQUE challenge candidate found in $n attempts")
        }

        // Difficulty closest to target (unique-candidates only).
        val best = candidates
            .map { it.seed to it.score }
            .let { DifficultyScorer.pickBest(it, target) }
            ?: candidates.first().seed
        return candidates.first { it.seed == best }.gs
    }
}
