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

    /** Candidate budget per level. Hard bands get more attempts because lower
     *  density (more ambiguity) has a higher rejection rate. Boss gets the most. */
    fun candidatesFor(level: Int): Int = when {
        level >= 40 -> 64   // NIGHTMARE / BOSS
        level >= 26 -> 48   // EXTREME
        else -> CANDIDATES_PER_LEVEL
    }

    /** Grid size curve — jumps early so difficulty doesn't wait for L25+:
     *  6x6 learning band, 7x7 medium→hardcore, 8x8 from L16 (very hard → extreme). */
    fun gridSizeFor(level: Int): Pair<Int, Int> = when {
        level <= 5 -> 6 to 6
        level <= 15 -> 7 to 7
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
        val numberDensity: Float,   // lower = more decision ambiguity
        val minSeg: Int,
        val maxSeg: Int,            // higher = longer blind stretches
        val maxWalls: Int           // Zip-style edge walls (kill alternative routes)
    )

    /**
     * Zip-style WALL config. Edge walls reliably converge (they kill alternative
     * routes directly, unlike inert cell-bricks), so density can drop as low as
     * 0.28 for the boss without flooding rejects — each dropped number + each
     * added wall increases decision ambiguity and gate threading.
     */
    fun challengeConfigFor(level: Int): ChallengeParams = when {
        level <= 3 -> ChallengeParams(0.55f, 2, 3, 3)    // learning: guided
        level <= 5 -> ChallengeParams(0.48f, 2, 4, 3)    // easy+
        level <= 7 -> ChallengeParams(0.44f, 2, 5, 4)    // medium
        level <= 9 -> ChallengeParams(0.41f, 2, 6, 5)    // hard
        level <= 15 -> ChallengeParams(0.38f, 2, 7, 7)   // HARDCORE (L10 breakpoint)
        level <= 25 -> ChallengeParams(0.36f, 2, 8, 8)   // very hard
        level <= 39 -> ChallengeParams(0.34f, 2, 9, 10)  // extreme
        level <= 49 -> ChallengeParams(0.31f, 2, 10, 12) // nightmare
        else -> ChallengeParams(0.28f, 2, 12, 16)        // BOSS (L50): sparse + most walls
    }

    /**
     * Difficulty target curve with a HARDCORE breakpoint at L10 (no longer a
     * linear 55→98). Anchors calibrated against the achievable scorer-v2
     * distributions (16 candidates/level, 2026-09 phase-C data):
     * targets sit between the median and p90 of each band so best-of-16
     * selection reliably lands a hard candidate without starving the band.
     *
     * L1 Easy · L5 Easy+ · L7 Medium · L9 Hard · L10 HARDCORE (jump +14) ·
     * L15 Hardcore+ · L25 Very Hard · L35 Very Hard+ · L50 Extreme.
     */
    fun challengeTargetFor(level: Int): Int {
        val anchors = listOf(
            1 to 15, 3 to 18, 5 to 26, 7 to 36, 9 to 50,
            10 to 64, 15 to 64,               // HARDCORE jump, plateau
            20 to 70, 30 to 78, 40 to 84, 45 to 89, 50 to 94
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
                val cfg = challengeConfigFor(level)
                try {
                    val wall = LevelGenerator.generateWallLevel(
                        gridRows, gridCols, cfg.numberDensity, cfg.maxWalls, seed,
                        cfg.minSeg, cfg.maxSeg
                    )
                    val numbers = wall.numberPositions.size
                    GameState(gridRows, gridCols, wall.numberPositions, numbers, mode, edgeWalls = wall.edgeWalls)
                } catch (e: IllegalStateException) {
                    null   // candidate rejected — not unique within wall budget
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
                val cfg = challengeConfigFor(level)
                val wall = LevelGenerator.generateWallLevel(
                    gridRows, gridCols, cfg.numberDensity, cfg.maxWalls, seed,
                    cfg.minSeg, cfg.maxSeg
                )
                val numbers = wall.numberPositions.size
                GameState(gridRows, gridCols, wall.numberPositions, numbers, mode, edgeWalls = wall.edgeWalls)
            }
        }
    }

    /**
     * CHALLENGE best-of-N: generate candidates with different seeds, score
     * each with DifficultyScorer, and keep the one closest to the level's
     * target difficulty. Runs at EXPORT time on a PC — not on the phone.
     * Uniqueness filtering happens in the exporter.
     */
    fun build(mode: GameMode, level: Int): GameState {
        if (mode == GameMode.SIMPLE) return buildWithSeed(mode, level, seed = LevelGenerator.seedFor(mode, level))

        val target = challengeTargetFor(level)
        val n = candidatesFor(level)                       // honours boss/extra candidates
        val candidates = mutableListOf<Pair<Long, DifficultyScorer.Score>>()
        val states = mutableListOf<Pair<Long, GameState>>()

        for (i in 0 until n) {
            val seed = LevelGenerator.seedFor(mode, level) + i * 7919L
            val gs = buildWithSeedOrNull(mode, level, seed) ?: continue   // skip invalid
            val score = DifficultyScorer.score(gs.rows, gs.cols, gs.blocks, gs.edgeWalls, gs.numberPositions)
            candidates.add(seed to score)
            states.add(seed to gs)
        }
        if (candidates.isEmpty()) {
            error("Level $level: no unique challenge candidate in $n attempts")
        }

        val bestSeed = DifficultyScorer.pickBest(candidates, target) ?: candidates[0].first
        return states.first { it.first == bestSeed }.second
    }
}
