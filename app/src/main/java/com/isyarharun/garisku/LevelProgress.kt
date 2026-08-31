package com.isyarharun.garisku

import android.content.Context
import android.content.SharedPreferences

/**
 * Saves/loads per-mode progress so the player can pick any unlocked level
 * from the menu. Tracks the highest unlocked level and the set of completed
 * levels for each mode.
 */
object LevelProgress {
    private const val PREFS_NAME = "garisku_progress"
    private const val KEY_SIMPLE_UNLOCKED = "max_unlocked_simple"
    private const val KEY_CHALLENGE_UNLOCKED = "max_unlocked_challenge"
    private const val KEY_SIMPLE_COMPLETED = "completed_simple"
    private const val KEY_CHALLENGE_COMPLETED = "completed_challenge"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun unlockedKey(mode: GameMode): String = when (mode) {
        GameMode.SIMPLE -> KEY_SIMPLE_UNLOCKED
        GameMode.CHALLENGE -> KEY_CHALLENGE_UNLOCKED
    }

    private fun completedKey(mode: GameMode): String = when (mode) {
        GameMode.SIMPLE -> KEY_SIMPLE_COMPLETED
        GameMode.CHALLENGE -> KEY_CHALLENGE_COMPLETED
    }

    private fun elapsedKey(mode: GameMode, level: Int): String =
        "elapsed_${mode.name.lowercase()}_$level"

    /** Highest unlocked level for a mode (defaults to 1). */
    fun getMaxUnlocked(mode: GameMode): Int =
        prefs?.getInt(unlockedKey(mode), 1) ?: 1

    /** True if the given level is unlocked for the mode. */
    fun isUnlocked(mode: GameMode, level: Int): Boolean =
        level <= getMaxUnlocked(mode)

    /** True if the given level has been completed for the mode. */
    fun isCompleted(mode: GameMode, level: Int): Boolean {
        val set = prefs?.getStringSet(completedKey(mode), emptySet()) ?: emptySet()
        return level.toString() in set
    }

    /** Marks a level as completed. */
    fun markCompleted(mode: GameMode, level: Int) {
        val key = completedKey(mode)
        val set = (prefs?.getStringSet(key, emptySet()) ?: emptySet()).toMutableSet()
        set.add(level.toString())
        prefs?.edit()?.putStringSet(key, set)?.apply()
    }

    /** Unlocks the next level if the given level is the current frontier. */
    fun unlockNext(mode: GameMode, level: Int) {
        val current = getMaxUnlocked(mode)
        if (level >= current) {
            prefs?.edit()?.putInt(unlockedKey(mode), level + 1)?.apply()
        }
    }

    /** Cumulative elapsed seconds spent on this level (across retries/sessions). */
    fun getElapsedSeconds(mode: GameMode, level: Int): Int =
        prefs?.getInt(elapsedKey(mode, level), 0) ?: 0

    fun saveElapsedSeconds(mode: GameMode, level: Int, seconds: Int) {
        prefs?.edit()?.putInt(elapsedKey(mode, level), seconds)?.apply()
    }
}