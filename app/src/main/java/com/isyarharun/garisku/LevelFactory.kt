package com.isyarharun.garisku

import kotlin.random.Random

/**
 * Difficulty design for 50 levels per mode.
 *
 * SIMPLE — grid & waypoint growth:
 *   L1-10:  4x4 → 5x5, many waypoints (guided)
 *   L11-25: 5x5 → 7x7, fewer waypoints (planning starts)
 *   L26-50: 7x7 → 10x10, sparse waypoints (long segments)
 *
 * CHALLENGE — three escalating axes:
 *   Grid:   4x4 → 10x10 (caps at L37)
 *   Bricks: 1 → 8 (more obstacles = tighter corridors)
 *   Waypoints: grow with grid, then SPARSIFY after cap
 *   (fewer waypoints on a big grid = long forced segments = hardest)
 */
object LevelFactory {

    const val LEVEL_COUNT = 50

    /**
     * Grid size curve — square grids like the reference puzzle (7x7).
     * Grows 5x5 → 8x8, capping at L37.
     */
    fun gridSizeFor(level: Int): Pair<Int, Int> {
        val t = (level - 1) / 49f
        val size = (5 + (t * 3).toInt()).coerceIn(5, 8)   // 5 → 8
        return size to size
    }

    /** SIMPLE: waypoint count. Dense early, sparse late. */
    fun simpleNumbersFor(level: Int, rows: Int, cols: Int): Int {
        val cells = rows * cols
        val t = (level - 1) / 49f                       // 0..1 progress
        val density = 0.45f - 0.25f * t                 // 45% → 20% of cells
        return (cells * density).toInt().coerceIn(4, cells / 2)
    }

    /**
     * CHALLENGE — reference-puzzle style (7x7, 11 numbers ≈ 22%):
     * sparse waypoints (~26% of cells, shrinking to ~15%) on square grids.
     * Bricks are ALWAYS present (min 1) — every level has an obstacle.
     * Fewer waypoints = longer segments = harder route planning.
     */
    fun challengeParamsFor(level: Int, rows: Int, cols: Int): Triple<Int, Int, Int> {
        val cells = rows * cols
        val t = (level - 1) / 49f
        // Waypoints: 30% → 15% of cells (reference puzzle ≈ 22%).
        val density = 0.30f - 0.15f * t
        val numbers = (cells * density).toInt().coerceIn(5, cells / 3)
        // Bricks: always ≥1; grows 1-2 early → up to 6 late.
        val bricks = (1 + t * 5).toInt().coerceIn(1, minOf(6, cells / 8))
        return Triple(numbers, bricks, cells)
    }

    /** Build a level from the difficulty formulas (used by the exporter). */
    fun build(mode: GameMode, level: Int): GameState {
        val seed = LevelGenerator.seedFor(mode, level)
        val random = Random(seed)
        val (gridRows, gridCols) = gridSizeFor(level)
        return when (mode) {
            GameMode.SIMPLE -> {
                val numbers = simpleNumbersFor(level, gridRows, gridCols)
                val positions = LevelGenerator.generateSimplePath(gridRows, gridCols, numbers, seed)
                GameState(gridRows, gridCols, positions, numbers, mode)
            }
            GameMode.CHALLENGE -> {
                val (numbers, bricks, cells) = challengeParamsFor(level, gridRows, gridCols)
                val brick = LevelGenerator.generateBrickLevel(gridRows, gridCols, numbers, bricks, seed)
                GameState(gridRows, gridCols, brick.numberPositions, numbers, mode, brick.blocks)
            }
        }
    }
}