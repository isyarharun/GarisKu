package com.isyarharun.garisku

import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Brute-force search for an EXTREMELY hard level 50:
 * 7x7 grid, dense numbers (65% of open cells), tight walls (open ~62%),
 * MUST have exactly one solution, scored as hard as possible.
 * Overwrites level 50 in levels_challenge.json when found.
 */
class Level50Hardcore {

    @Test
    fun forgeLevel50() {
        val rows = 7
        val cols = 7
        var best: GameState? = null
        var bestScore = -1
        val startSeed = LevelGenerator.seedFor(GameMode.CHALLENGE, 50)

        // Deep search: many candidates with EXTREME density + tight walls.
        // Open target 0.62 but generator struggles on 7x7 with 24 numbers;
        // try several wall densities and number counts per seed.
        for (i in 0 until 300) {
            val seed = startSeed + i * 104729L
            // Alternate between strategies for diversity.
            val numTarget = listOf(20, 22, 24, 26)[i % 4]
            val openRatio = listOf(0.68f, 0.62f, 0.58f)[i % 3]
            val maze = LevelGenerator.generateMazeLevel(rows, cols, numTarget, openRatio, seed)
            val numbers = maze.numberPositions.size
            if (numbers < 16) continue

            val routes = LevelGenerator.countOrderedPaths(rows, cols, maze.numberPositions, maze.blocks, stopAfter = 2)
            if (routes != 1) continue

            val sc = DifficultyScorer.score(rows, cols, maze.blocks, maze.numberPositions)
            if (sc.total > bestScore) {
                bestScore = sc.total
                best = GameState(rows, cols, maze.numberPositions, numbers, GameMode.CHALLENGE, maze.blocks)
            }
        }

        val winner = checkNotNull(best) { "No unique-solution candidate found in search" }
        println("Level 50 forged: score=$bestScore nums=${winner.totalNumbers} blocks=${winner.blocks.size}")

        // Patch JSON via proper parsing (file is pretty-printed with nested objects).
        val f = File("src/main/assets/levels_challenge.json")
        val root = com.google.gson.JsonParser.parseString(f.readText()).asJsonObject
        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
        val newLv = com.google.gson.JsonObject().apply {
            addProperty("rows", rows)
            addProperty("cols", cols)
            addProperty("seed_attempt", -1)  // -1 = custom forged level
            addProperty("difficulty", bestScore)
            val nums = com.google.gson.JsonObject()
            for ((num, p) in winner.numberPositions.entries.sortedBy { it.key }) {
                nums.add(num.toString(), com.google.gson.JsonArray().apply {
                    add(p.row); add(p.col)
                })
            }
            add("numbers", nums)
            add("blocks", com.google.gson.JsonArray().apply {
                for (b in winner.blocks.sortedWith(compareBy({ it.row }, { it.col }))) {
                    add(com.google.gson.JsonArray().apply { add(b.row); add(b.col) })
                }
            })
        }
        root.add("50", newLv)
        f.writeText(gson.toJson(root))
        println("JSON patched. Level 50 is now the hardcore version.")
    }
}
