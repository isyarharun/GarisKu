package com.isyarharun.garisku

import org.junit.Test

class GridAndCountInvariantTest {
    @Test
    fun gridBandsAndChallengeCountRange() {
        check(LevelFactory.gridSizeFor(1) == (8 to 8))
        check(LevelFactory.gridSizeFor(25) == (8 to 8))
        check(LevelFactory.gridSizeFor(26) == (10 to 10))
        check(LevelFactory.gridSizeFor(50) == (10 to 10))

        val seeds = (0L until 40L).map { 7919L * it + 17L }
        val counts8 = seeds.map { LevelFactory.challengeNumberCount(8, 8, it) }
        val counts10 = seeds.map { LevelFactory.challengeNumberCount(10, 10, it) }
        check(counts8.all { it in 10..11 })
        check(counts10.all { it in 7..8 })
        check(counts8.any { it == 10 } && counts8.any { it == 11 })
        check(counts10.any { it == 7 } && counts10.any { it == 8 })
        check(LevelFactory.challengeNumberCount(8, 8, 123L) ==
            LevelFactory.challengeNumberCount(8, 8, 123L))
    }

    @Test
    fun knownSolutionValidationAndAlternativeDetection() {
        val path = listOf(
            Position(0, 0), Position(0, 1), Position(0, 2),
            Position(1, 2), Position(1, 1), Position(1, 0),
            Position(2, 0), Position(2, 1), Position(2, 2)
        )
        val numbers = mapOf(1 to path.first(), 2 to path[4], 3 to path.last())
        val allEdges = buildList {
            for (r in 0 until 3) for (c in 0 until 3) {
                val p = Position(r, c)
                if (c < 2) add(WallEdge(p, Position(r, c + 1)))
                if (r < 2) add(WallEdge(p, Position(r + 1, c)))
            }
        }
        val pathEdges = path.zipWithNext().map { WallEdge(it.first, it.second) }.toSet()
        val corridorWalls = allEdges.filter { it !in pathEdges }.toSet()

        check(LevelGenerator.validateKnownSolution(3, 3, numbers, emptySet(), emptySet(), path))
        check(!LevelGenerator.validateKnownSolution(3, 3, numbers, emptySet(), emptySet(), path.dropLast(1)))

        val unique = LevelGenerator.findAlternativeOrderedPath(
            3, 3, numbers, emptySet(), corridorWalls, path, budget = 100_000
        )
        check(!unique.alternativeFound)
        check(!unique.budgetExhausted)

        val alternatives = LevelGenerator.findAlternativeOrderedPath(
            3, 3, numbers, emptySet(), emptySet(), path, budget = 100_000
        )
        check(alternatives.alternativeFound)
    }

    @Test
    fun challengeBuildUsesRequestedGridAndCountRange() {
        val l5 = LevelFactory.buildWithSeed(GameMode.CHALLENGE, 5, LevelGenerator.seedFor(GameMode.CHALLENGE, 5))
        val l30 = LevelFactory.buildWithSeed(GameMode.CHALLENGE, 30, LevelGenerator.seedFor(GameMode.CHALLENGE, 30))
        check(l5.rows == 8 && l5.cols == 8 && l5.totalNumbers in 10..11)
        check(l30.rows == 10 && l30.cols == 10 && l30.totalNumbers in 7..8)
    }
}
