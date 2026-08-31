package com.isyarharun.garisku

import org.junit.Test
import java.io.File

/**
 * One-shot exporter: generates all levels and writes them as JSON
 * into app/src/main/assets/. Run with:
 *   gradlew.bat :app:testDebugUnitTest --tests "*LevelExporter*"
 */
class LevelExporter {

    @Test
    fun exportAllLevels() {
        val assets = File("src/main/assets").apply { mkdirs() }
        for (mode in GameMode.entries) {
            val sb = StringBuilder("{")
            for (level in 1..LevelFactory.LEVEL_COUNT) {
                val gs = LevelFactory.build(mode, level)
                if (level > 1) sb.append(",")
                sb.append("\"$level\":{\"rows\":${gs.rows},\"cols\":${gs.cols},")
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
        }
    }

    /**
     * Verifies the win-condition invariant for every challenge level:
     * the generator's solution must (a) start on number 1, (b) END on the
     * final number, and (c) cover every open cell — otherwise the rule
     * "fill all cells + end on the last number" would be unsatisfiable.
     */
    @Test
    fun verifyChallengeLevelsEndOnLastNumber() {
        for (level in 1..LevelFactory.LEVEL_COUNT) {
            // Re-derive with the same seed/params the factory uses.
            val seed = LevelGenerator.seedFor(GameMode.CHALLENGE, level)
            val (gridRows, gridCols) = LevelFactory.gridSizeFor(level)
            val (numbers, bricks, _) = LevelFactory.challengeParamsFor(level, gridRows, gridCols)
            val brick = LevelGenerator.generateBrickLevel(gridRows, gridCols, numbers, bricks, seed)

            val first = brick.numberPositions[1]
            val last = brick.numberPositions[numbers]
            checkNotNull(first) { "Level $level: missing number 1" }
            checkNotNull(last) { "Level $level: missing final number" }

            check(brick.path.first() == first) {
                "Level $level: solution does not start on number 1"
            }
            check(brick.path.last() == last) {
                "Level $level: solution does not END on the final number"
            }
            check(brick.path.size == gridRows * gridCols - brick.blocks.size) {
                "Level $level: solution does not cover every open cell"
            }
            check(brick.blocks.isNotEmpty()) {
                "Level $level: challenge level must always have at least 1 brick"
            }
            check(!brick.blocks.contains(last)) {
                "Level $level: final number is on a brick!"
            }
        }
    }
}