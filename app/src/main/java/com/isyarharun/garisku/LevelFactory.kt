package com.isyarharun.garisku

import kotlin.random.Random

/**
 * Difficulty design for 100 levels per mode.
 *
 * SIMPLE — waypoint growth over the shared grid bands.
 * CHALLENGE — unique-solution wall mazes. A Hamiltonian solution path is kept
 * intact, wall chains block alternatives, and export verifies exactly one route.
 * Grid: L1-25 = 8x8, L26-50 = 10x10.
 * CHALLENGE checkpoint count: deterministic 8..10 per level.
 */
object LevelFactory {

    const val LEVEL_COUNT = 50

    /** Candidates per level in the exporter's calibration loop. */
    const val CANDIDATES_PER_LEVEL = 32

    /** Challenge checkpoint count: deterministic random 8, 9, or 10. */
    fun challengeNumberCount(rows: Int, cols: Int, seed: Long): Int {
        require(rows * cols == 64 || rows * cols == 100)
        return Random(seed).nextInt(8, 11)
    }

    /** Difficulty tiers following the existing challenge curve.
     *  Number count is now an independent deterministic 8..10 choice; tier
     *  configuration controls wall chains and segment gaps. */
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

    /** Grid size follows level progression: L1-25 are 8x8, L26-50 are 10x10. */
    fun gridSizeFor(level: Int): Pair<Int, Int> = when {
        level <= 25 -> 8 to 8
        else -> 10 to 10
    }

    /** SIMPLE: waypoint count. Dense early, sparse later. */
    fun simpleNumbersFor(level: Int, rows: Int, cols: Int): Int {
        val cells = rows * cols
        val t = (level - 1) / 49f
        val density = 0.42f - 0.25f * t                 // 42% → 17% of cells
        return (cells * density).toInt().coerceIn(4, cells / 2)
    }

    /**
     * CHALLENGE tier configuration. Number count is selected separately by
     * challengeNumberCount; these parameters control wall chains and blind gaps.
     */
    data class ChallengeParams(
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
            // Wall chains remain tier-driven. Route-count performance for the
            // new 8x8/10x10 grids is measured before asset export.
            ChallengeTier.EASY -> ChallengeParams(2, 4, if (big) 6 else 6, 2, false)
            ChallengeTier.EASY_MED -> ChallengeParams(2, 5, if (big) 8 else 7, 3, false)
            ChallengeTier.MEDIUM -> ChallengeParams(2, 6, if (big) 9 else 8, 3, true)
            ChallengeTier.MED_HARD -> ChallengeParams(2, 8, if (big) 11 else 9, 4, true)
            ChallengeTier.HARD -> ChallengeParams(2, 10, if (big) 12 else 10, 5, true)
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
                val numberCount = challengeNumberCount(gridRows, gridCols, seed)
                try {
                    val wall = LevelGenerator.generateMazeWallLevel(
                        gridRows, gridCols, numberCount, cfg.wallPieces,
                        cfg.maxChainLen, cfg.allowBranch, seed,
                        cfg.minSeg, cfg.maxSeg
                    )
                    val numbers = wall.numberPositions.size
                    GameState(gridRows, gridCols, wall.numberPositions, numbers, mode, edgeWalls = wall.edgeWalls, solutionPath = wall.path)
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
                val numberCount = challengeNumberCount(gridRows, gridCols, seed)
                val wall = LevelGenerator.generateMazeWallLevel(
                    gridRows, gridCols, numberCount, cfg.wallPieces,
                    cfg.maxChainLen, cfg.allowBranch, seed,
                    cfg.minSeg, cfg.maxSeg
                )
                val numbers = wall.numberPositions.size
                GameState(gridRows, gridCols, wall.numberPositions, numbers, mode, edgeWalls = wall.edgeWalls, solutionPath = wall.path)
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
            val known = gs.solutionPath ?: continue
            val valid = LevelGenerator.validateKnownSolution(
                gs.rows, gs.cols, gs.numberPositions, gs.blocks, gs.edgeWalls, known
            )
            if (!valid) continue
            val alternative = LevelGenerator.findAlternativeOrderedPath(
                gs.rows, gs.cols, gs.numberPositions, gs.blocks, gs.edgeWalls, known
            )
            if (alternative.alternativeFound || alternative.budgetExhausted) continue
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
