package com.isyarharun.garisku

import android.content.Context
import org.json.JSONObject

data class LevelData(
    val rows: Int,
    val cols: Int,
    val numberPositions: Map<Int, Position>,
    val blocks: Set<Position>
)

/**
 * Loads pre-generated levels from assets/levels_{mode}.json.
 * Parsed once at init, kept in memory — opening a level is instant.
 */
object LevelRepository {
    private val cache = mutableMapOf<GameMode, Map<Int, LevelData>>()

    fun init(context: Context) {
        for (mode in GameMode.entries) {
            val name = "levels_${mode.name.lowercase()}.json"
            val json = context.assets.open(name).bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val levels = mutableMapOf<Int, LevelData>()
            for (key in root.keys()) {
                val o = root.getJSONObject(key)
                val numbers = mutableMapOf<Int, Position>()
                val n = o.getJSONObject("numbers")
                for (k in n.keys()) {
                    val p = n.getJSONArray(k)
                    numbers[k.toInt()] = Position(p.getInt(0), p.getInt(1))
                }
                val blocks = mutableSetOf<Position>()
                val b = o.getJSONArray("blocks")
                for (i in 0 until b.length()) {
                    val p = b.getJSONArray(i)
                    blocks.add(Position(p.getInt(0), p.getInt(1)))
                }
                levels[key.toInt()] = LevelData(o.getInt("rows"), o.getInt("cols"), numbers, blocks)
            }
            cache[mode] = levels
        }
    }

    fun getLevel(mode: GameMode, level: Int): LevelData? = cache[mode]?.get(level)
}