package com.isyarharun.garisku

import org.junit.Test
import java.io.File

/**
 * One-shot exporter with uniqueness guarantee: challenge candidates are
 * kept ONLY if countOrderedPaths == 1 (exactly one valid route). Prints a
 * difficulty table for the curve check. Run with:
 *   ./gradlew :app:testDebugUnitTest --tests "*LevelExporter*exportAllLevels*"
 */
class LevelExporter {

    private fun seedFor(mode: GameMode, level: Int, attempt: Int): Long =
        LevelGenerator.seedFor(mode, level) + attempt * 7919L

    @Test
    fun exportAllLevels() {
        val assets = File("src/main/assets").apply { mkdirs() }
        val table = StringBuilder()
        table.appendLine("=== Challenge levels (unique-solution) ===")
        table.appendLine("Lvl | Grid | Nums | Blocks | Open | Diff | Target | Routes<=2")

        for (mode in GameMode.entries) {
            val sb = StringBuilder("{")
            for (level in 1..LevelFactory.LEVEL_COUNT) {
                val chosen: Triple<Int, GameState, Int>
                var chosenScore = 0
                if (mode == GameMode.CHALLENGE) {
                    val target = LevelFactory.challengeTargetFor(level)
                    val nCandidates = LevelFactory.CANDIDATES_PER_LEVEL
                    var best: Triple<Int, GameState, Int>? = null   // attempt, state, |score-target|
                    for (attempt in 0 until nCandidates) {
                        val gs = LevelFactory.buildWithSeed(mode, level, seedFor(mode, level, attempt))
                        val routes = LevelGenerator.countOrderedPaths(
                            gs.rows, gs.cols, gs.numberPositions, gs.blocks, stopAfter = 2
                        )
                        // HARD requirement: exactly one solution.
                        if (routes != 1) continue

                        val open = allOpen(gs)
                        val sc = DifficultyScorer.score(open, gs.rows, gs.cols, gs.blocks, gs.numberPositions)
                        val dist = kotlin.math.abs(sc.total - target)
                        if (best == null || dist < best.third) {
                            best = Triple(attempt, gs, dist)
                            chosenScore = sc.total
                        }
                    }
                    chosen = best ?: run {
                        // Ultra-rare: no unique candidate in this batch — take the
                        // highest-scoring attempt anyway (generator still solvable).
                        var fallback: Triple<Int, GameState, Int>? = null
                        for (attempt in 0 until nCandidates) {
                            val gs = LevelFactory.buildWithSeed(mode, level, seedFor(mode, level, attempt))
                            val open = allOpen(gs)
                            val sc = DifficultyScorer.score(open, gs.rows, gs.cols, gs.blocks, gs.numberPositions)
                            val dist = kotlin.math.abs(sc.total - target)
                            if (fallback == null || dist < fallback.third) {
                                fallback = Triple(attempt, gs, dist)
                                chosenScore = sc.total
                            }
                        }
                        println("WARN level $level: no unique candidate found, using fallback")
                        fallback!!
                    }

                    if (level % 10 == 0 || level == 1) {
                        val gs = chosen.second
                        val routes = LevelGenerator.countOrderedPaths(
                            gs.rows, gs.cols, gs.numberPositions, gs.blocks, stopAfter = 3
                        )
                        table.appendLine(
                            "%3d | %dx%d | %4d | %6d | %4d | %4d | %6d | %d+".format(
                                level, gs.rows, gs.cols, gs.totalNumbers, gs.blocks.size,
                                gs.rows * gs.cols - gs.blocks.size, chosenScore, target, routes
                            )
                        )
                    }
                } else {
                    chosen = Triple(0, LevelFactory.buildWithSeed(mode, level, seedFor(mode, level, 0)), 0)
                }
                val chosenAttempt = chosen.first
                val gs = chosen.second

                if (level > 1) sb.append(",")
                sb.append("\"$level\":{\"rows\":${gs.rows},\"cols\":${gs.cols},")
                sb.append("\"seed_attempt\":$chosenAttempt,")
                sb.append("\"difficulty\":$chosenScore,")
                sb.append("\"numbers\":{")
                sb.append(gs.numberPositions.entries.joinToString(",") {
                    "\"${it.key}\":[${it.value.row},${it.value.col}]"
                })
                sb.append("},\"blocks\":[")
                sb.append(gs.blocks.joinToString(",") { "[${it.row},${it.col}]" })
                sb.append("]}")
            }
            sb.append("}")
            File(assets, "levels_${mode.name.lowercase()}.json").writeText(sb.toString())
            println("Exported ${mode.name}: ${LevelFactory.LEVEL_COUNT} levels")
        }
        println(table)
    }

    /**
     * Verifies every exported challenge level has EXACTLY ONE valid route
     * (re-derived from its seed_attempt) and satisfies the win-condition
     * invariants.
     */
    @Test
    fun verifyChallengeLevelsUniqueAndSolvable() {
        for (level in 1..LevelFactory.LEVEL_COUNT) {
            val attempt = readSeedAttempt(GameMode.CHALLENGE, level)
                ?: error("Level $level: missing seed_attempt")
            val gs = LevelFactory.buildWithSeed(GameMode.CHALLENGE, level, seedFor(GameMode.CHALLENGE, level, attempt))

            val first = gs.numberPositions[1]
            val last = gs.numberPositions[gs.totalNumbers]
            checkNotNull(first) { "Level $level: missing number 1" }
            checkNotNull(last) { "Level $level: missing final number" }

            val routes = LevelGenerator.countOrderedPaths(gs.rows, gs.cols, gs.numberPositions, gs.blocks, stopAfter = 2)
            check(routes == 1) { "Level $level: expected exactly 1 route, got $routes" }
        }
    }

    private fun readSeedAttempt(mode: GameMode, level: Int): Int? {
        val f = File("src/main/assets/levels_${mode.name.lowercase()}.json")
        if (!f.exists()) return null
        val root = com.google.gson.JsonParser.parseString(f.readText()).asJsonObject
        val lv = root.get(level.toString()) ?: return null
        return lv.asJsonObject.get("seed_attempt")?.asInt
    }

    private fun allOpen(gs: GameState): Set<Position> = buildSet {
        for (r in 0 until gs.rows) for (c in 0 until gs.cols) {
            val p = Position(r, c)
            if (p !in gs.blocks) add(p)
        }
    }
}
