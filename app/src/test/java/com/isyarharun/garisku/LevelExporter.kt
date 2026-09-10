package com.isyarharun.garisku

import org.junit.Test
import java.io.File

/**
 * One-shot exporter with edge-walls + soft-uniqueness selection.
 * Challenge candidates come from LevelGenerator.generateMazeWallLevel (sparse
 * wall chains on non-solution edges + soft convergence); selection keeps the
 * candidate with the FEWEST valid routes (1 = unique) closest to the difficulty
 * target. Prints the difficulty acceptance table.
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
        table.appendLine("=== Challenge 50 levels — fewest-routes/switches selection; 8x8 (L1-25, 10-11 nums) / 10x10 (L26-50, 7-8 nums) ===")
        table.appendLine("Lvl | Grid | Nums | Walls | Dens | Gap | Rt | Sw | Bricks | Br | Frc | nearF | nearP | DevM | Score | Tgt | Rej   (Rt 0 = search budget exhausted / very open)")

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
                    var best: Triple<Int, GameState, Int>? = null   // attempt, state, |score-target|
                    var bestRoutes = Int.MAX_VALUE
                    var bestSwitches = Int.MAX_VALUE
                    var chosenScore = 0
                    var rejections = 0
                    val nCandidates = LevelFactory.candidatesFor(level)
                    for (attempt in 0 until nCandidates) {
                        val gs = LevelFactory.buildWithSeedOrNull(mode, level, seedFor(mode, level, attempt))
                        if (gs == null) { rejections++; continue }
                        val known = gs.solutionPath ?: run { rejections++; continue }
                        if (!LevelGenerator.validateKnownSolution(
                                gs.rows, gs.cols, gs.numberPositions, gs.blocks, gs.edgeWalls, known
                            )) {
                            rejections++; println("L$level att=$attempt: known solution invalid"); continue
                        }
                        val routes = LevelGenerator.countOrderedPaths(
                            gs.rows, gs.cols, gs.numberPositions, gs.blocks,
                            stopAfter = LevelFactory.ROUTE_COUNT_CAP, edgeWalls = gs.edgeWalls,
                            budget = 400_000
                        )
                        val routesBucket = if (routes == 0) LevelFactory.ROUTE_COUNT_CAP + 1 else routes
                        val switches = LevelGenerator.countSwitches(
                            gs.rows, gs.cols, gs.numberPositions, gs.edgeWalls, known,
                            cap = LevelFactory.SWITCH_CAP
                        )
                        val sc = DifficultyScorer.score(gs.rows, gs.cols, gs.blocks, gs.edgeWalls, gs.numberPositions, known)
                        val dist = kotlin.math.abs(sc.total - target)
                        val better = routesBucket < bestRoutes ||
                            (routesBucket == bestRoutes && switches < bestSwitches) ||
                            (routesBucket == bestRoutes && switches == bestSwitches && (best == null || dist < best.third))
                        if (better) {
                            best = Triple(attempt, gs, dist)
                            bestRoutes = routesBucket
                            bestSwitches = switches
                            chosenScore = sc.total
                        }
                    }
                    chosen = Triple(
                        best?.first ?: -1,
                        best?.second ?: error("Level $level: no solvable challenge candidate in $nCandidates attempts"),
                        best?.third ?: 0
                    )
                    // Record for the boss-zone rolling window (keep last 5).
                    recentScores.addLast(chosenScore)
                    while (recentScores.size > 5) recentScores.removeFirst()

                    val gs = chosen.second
                    // Honest re-count for the table (bigger budget).
                    val routes = LevelGenerator.countOrderedPaths(
                        gs.rows, gs.cols, gs.numberPositions, gs.blocks,
                        stopAfter = LevelFactory.ROUTE_COUNT_CAP, edgeWalls = gs.edgeWalls
                    )
                    val switches = LevelGenerator.countSwitches(
                        gs.rows, gs.cols, gs.numberPositions, gs.edgeWalls, gs.solutionPath!!,
                        cap = LevelFactory.SWITCH_CAP
                    )
                    val open = allOpen(gs)
                    val density = gs.totalNumbers.toFloat() / open.size
                    val ana = RouteAnalyzer.analyze(gs.rows, gs.cols, gs.numberPositions, gs.blocks, gs.edgeWalls, gs.solutionPath)
                    val (gm, _) = gapStats(ana.solutionRoute, gs.numberPositions)
                    table.appendLine(
                        ("%3d | %dx%d | %4d | %5d | %.2f | %4.2f | %3d | %2d | %6d | %.2f | %5.1f | %5d | %5d | %4.1f | %5d | %3d | %3d").format(
                            level, gs.rows, gs.cols, gs.totalNumbers, gs.edgeWalls.size, density,
                            gm, routes, switches, gs.blocks.size,
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
     * Verifies every exported challenge level is solvable and LOCALLY UNIQUE
     * (no short alternative route = no switches) and satisfies the
     * win-condition invariants. Route counting is informational only: very
     * open boards can exhaust any sane search budget even though they are
     * solvable and switch-free (soft uniqueness — see generateMazeWallLevel).
     */
    @Test
    fun verifyChallengeLevelsSolvable() {
        for (level in 1..LevelFactory.LEVEL_COUNT) {
            val attempt = readSeedAttempt(GameMode.CHALLENGE, level)
                ?: error("Level $level: missing seed_attempt")
            val rebuilt = LevelFactory.buildWithSeed(
                GameMode.CHALLENGE, level, seedFor(GameMode.CHALLENGE, level, attempt)
            )
            val known = rebuilt.solutionPath ?: error("Level $level: missing generated solution path")
            check(LevelGenerator.validateKnownSolution(
                rebuilt.rows, rebuilt.cols, rebuilt.numberPositions, rebuilt.blocks, rebuilt.edgeWalls, known
            )) { "Level $level: generated solution failed direct validation" }
            val switches = LevelGenerator.countSwitches(
                rebuilt.rows, rebuilt.cols, rebuilt.numberPositions, rebuilt.edgeWalls, known,
                cap = LevelFactory.SWITCH_CAP
            )
            check(switches == 0) { "Level $level: $switches local switches remain (expected locally unique)" }
            check(if (level <= 25) rebuilt.rows == 8 && rebuilt.cols == 8 else rebuilt.rows == 10 && rebuilt.cols == 10) {
                "Level $level: unexpected grid ${rebuilt.rows}x${rebuilt.cols}"
            }
            val expectedNumbers = if (level <= 25) 10..11 else 7..8
            check(rebuilt.numberPositions.size in expectedNumbers) {
                "Level $level: expected $expectedNumbers numbers, got ${rebuilt.numberPositions.size}"
            }
            checkNotNull(rebuilt.numberPositions[1]) { "Level $level: missing number 1" }
            checkNotNull(rebuilt.numberPositions[rebuilt.totalNumbers]) { "Level $level: missing final number" }
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
