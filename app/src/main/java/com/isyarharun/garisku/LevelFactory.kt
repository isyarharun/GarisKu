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
    const val CANDIDATES_PER_LEVEL = 16

    /** Grid size curve — square grids, 6x6 → 8x8, capping at L35. */
    fun gridSizeFor(level: Int): Pair<Int, Int> {
        val t = (level - 1) / 49f
        val size = (6 + (t * 2).toInt()).coerceIn(6, 8)
        return size to size
    }

    /** SIMPLE: waypoint count. Dense early, sparse late. */
    fun simpleNumbersFor(level: Int, rows: Int, cols: Int): Int {
        val cells = rows * cols
        val t = (level - 1) / 49f
        val density = 0.42f - 0.25f * t                 // 42% → 17% of cells
        return (cells * density).toInt().coerceIn(4, cells / 2)
    }

    /**
     * CHALLENGE params — few walls, dense numbers as the obstacles.
     * @return Triple(numberDensity, targetOpenRatio, cells)
     */
    fun challengeParamsFor(level: Int, rows: Int, cols: Int): Triple<Float, Float, Int> {
        val t = (level - 1) / 49f
        // Checkpoint density of OPEN cells: 45% → 62%.
        val numberDensity = 0.45f + 0.17f * t
        // Open ratio: 88% → 82% (board stays wide; only 2-3 bricks).
        val openRatio = (0.88f - 0.06f * t).coerceIn(0.82f, 0.88f)
        return Triple(numberDensity, openRatio, rows * cols)
    }

    /** Monotonic difficulty target: 55 (already hard) → 98 (brutal). */
    fun challengeTargetFor(level: Int): Int =
        (55 + (level - 1) / 49f * 43).toInt().coerceIn(55, 98)

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
                val (density, openRatio, _) = challengeParamsFor(level, gridRows, gridCols)
                val maze = LevelGenerator.generateUniqueLevel(gridRows, gridCols, density, openRatio, seed)
                val numbers = maze.numberPositions.size
                GameState(gridRows, gridCols, maze.numberPositions, numbers, mode, maze.blocks)
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
        val n = CANDIDATES_PER_LEVEL
        val candidates = mutableListOf<Pair<Long, DifficultyScorer.Score>>()
        val states = mutableListOf<Pair<Long, GameState>>()

        for (i in 0 until n) {
            val seed = LevelGenerator.seedFor(mode, level) + i * 7919L
            val gs = buildWithSeed(mode, level, seed)
            val open = allPositions(gs.rows, gs.cols).filter { it !in gs.blocks }.toSet()
            val score = DifficultyScorer.score(open, gs.rows, gs.cols, gs.blocks, gs.numberPositions)
            candidates.add(seed to score)
            states.add(seed to gs)
        }

        val bestSeed = DifficultyScorer.pickBest(candidates, target) ?: candidates[0].first
        return states.first { it.first == bestSeed }.second
    }

    private fun allPositions(rows: Int, cols: Int): List<Position> = buildList {
        for (r in 0 until rows) for (c in 0 until cols) add(Position(r, c))
    }
}
