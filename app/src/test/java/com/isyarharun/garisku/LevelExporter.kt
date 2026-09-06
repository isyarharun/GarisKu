package com.isyarharun.garisku

import org.junit.Test
import java.io.File

/**
 * One-shot exporter with edge-walls + uniqueness guarantee.
 * Challenge candidates come from LevelFactory.generateMazeWallLevel (wall
 * chains on non-solution edges + convergence to a single valid route); only
 * levels with exactly one valid route are exported. Prints the difficulty
 * acceptance table.
 * Run with:
 *   ./gradlew :app:testDebugUnitTest --tests "*LevelExporter*exportAllLevels*"
 */
class LevelExporter {

    private fun seedFor(mode: GameMode, level: Int, attempt: Int): Long =
        LevelGenerator.seedFor(mode, level) + attempt * 7919L

    private fun allOpen(gs: GameState): Set<Position> = buildSet {
        for (r in 0 until gs.rows) for (c in 0 until gs.cols) {
            val p = Position(r, c)
            if (p !in gs.blocks) add(p)
        }
    }

    /** Mean/max gap between consecutive numbers along a valid route. */
    private fun gapStats(route: List<Position>, numbers: Map<Int, Position>): Pair<Float, Int> {
        if (numbers.size < 2 || route.isEmpty()) return 0f to 0
        val idx = HashMap<Position, Int>()
        route.forEachIndexed { i, p -> idx[p] = i }
        val order = numbers.entries.sortedBy { it.key }
        var sum = 0; var maxG = 0
        for (w in 0 until order.size - 1) {
            val a = idx[order[w].value] ?: return 0f to 0
            val b = idx[order[w + 1].value] ?: return 0f to 0
            val g = kotlin.math.abs(b - a)
            sum += g; if (g > maxG) maxG = g
        }
        return sum.toFloat() / (order.size - 1) to maxG
    }

    @Test
    fun exportAllLevels() {
        val assets = File("src/main/assets").apply { mkdirs() }
        val table = StringBuilder()
        table.appendLine("=== Challenge 50 levels — unique-solution, re-anchored curve (48→76) ===")
        table.appendLine("Lvl | Grid | Nums | Dens | Gap | Bricks | Uniq | Br | Frc | nearF | nearP | DevM | Score | Tgt | Rej")

        for (mode in GameMode.entries) {
            val sb = StringBuilder("{")
            // Rolling window of recent CHALLENGE scores so the boss level (L50)
            // can be compared against the stable boss-zone average, not a single
            // possibly-anomalous predecessor (L49 sometimes spikes to ~87 while
            // L45-48 sit at 69-79).
            val recentScores = ArrayDeque<Int>()
            for (level in 1..LevelFactory.LEVEL_COUNT) {
                val chosen: Triple<Int, GameState, Int>   // attempt, state, dist
                var chosenScore = 0
                if (mode == GameMode.CHALLENGE) {
                    val target = LevelFactory.challengeTargetFor(level)
                    var best: Triple<Int, GameState, Int>? = null
                    var rejections = 0
                    val nCandidates = LevelFactory.candidatesFor(level)
                    for (attempt in 0 until nCandidates) {
                        val gs = LevelFactory.buildWithSeedOrNull(mode, level, seedFor(mode, level, attempt))
                        if (gs == null) { rejections++; continue }   // no path through maze
                        val routes = LevelGenerator.countOrderedPaths(
                            gs.rows, gs.cols, gs.numberPositions, gs.blocks, stopAfter = 2, edgeWalls = gs.edgeWalls
                        )
                        if (routes != 1) { rejections++; println("L$level att=$attempt: routes=$routes (not-unique)"); continue }
                        val sc = DifficultyScorer.score(gs.rows, gs.cols, gs.blocks, gs.edgeWalls, gs.numberPositions)
                        // This is a UNIQUE solvable candidate — pick the one closest to target.
                        val dist = kotlin.math.abs(sc.total - target)
                        if (best == null || dist < best.third) {
                            best = Triple(attempt, gs, dist)
                            chosenScore = sc.total
                        }
                    }
                    chosen = best ?: error("Level $level: no UNIQUE solvable candidate in $nCandidates attempts")
                    // Record for the boss-zone rolling window (keep last 5).
                    recentScores.addLast(chosenScore)
                    while (recentScores.size > 5) recentScores.removeFirst()

                    val gs = chosen.second
                    val open = allOpen(gs)
                    val density = gs.totalNumbers.toFloat() / open.size
                    val ana = RouteAnalyzer.analyze(gs.rows, gs.cols, gs.numberPositions, gs.blocks, gs.edgeWalls)
                    val (gm, _) = gapStats(ana.solutionRoute, gs.numberPositions)
                    table.appendLine(
                        ("%3d | %dx%d | %4d | %.2f | %4.2f | %6d | %4d | %.2f | %5.1f | %5d | %5d | %4.1f | %5d | %3d | %3d").format(
                            level, gs.rows, gs.cols, gs.totalNumbers, density,
                            gm, gs.blocks.size, 1,
                            ana.avgBranching, ana.forcedMoveRatio,
                            ana.nearSolutionFull, ana.nearSolutionPrefix,
                            ana.meanDeviationDepth, chosenScore, target, rejections
                        )
                    )
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
                sb.append("],\"walls\":[")
                sb.append(gs.edgeWalls.joinToString(",") {
                    "[${it.a.row},${it.a.col},${it.b.row},${it.b.col}]"
                })
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
     * and satisfies the win-condition invariants.
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

            val routes = LevelGenerator.countOrderedPaths(
                gs.rows, gs.cols, gs.numberPositions, gs.blocks, stopAfter = 2, edgeWalls = gs.edgeWalls
            )
            check(routes == 1) { "Level $level: expected EXACTLY ONE valid route (unique-solution), got $routes" }
        }
    }

    private fun readSeedAttempt(mode: GameMode, level: Int): Int? {
        val f = File("src/main/assets/levels_${mode.name.lowercase()}.json")
        if (!f.exists()) return null
        val root = com.google.gson.JsonParser.parseString(f.readText()).asJsonObject
        val lv = root.get(level.toString()) ?: return null
        return lv.asJsonObject.get("seed_attempt")?.asInt
    }
}
