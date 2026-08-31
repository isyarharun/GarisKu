package com.isyarharun.garisku

import android.content.Context
import android.content.SharedPreferences

/**
 * Saves/loads the current level number per mode so the player
 * resumes where they left off after closing the app.
 */
object LevelProgress {
    private const val PREFS_NAME = "garisku_progress"
    private const val KEY_SIMPLE = "current_level_simple"
    private const val KEY_CHALLENGE = "current_level_challenge"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getCurrentLevel(mode: GameMode): Int {
        val key = when (mode) {
            GameMode.SIMPLE -> KEY_SIMPLE
            GameMode.CHALLENGE -> KEY_CHALLENGE
        }
        return prefs?.getInt(key, 1) ?: 1
    }

    fun saveCurrentLevel(mode: GameMode, level: Int) {
        val key = when (mode) {
            GameMode.SIMPLE -> KEY_SIMPLE
            GameMode.CHALLENGE -> KEY_CHALLENGE
        }
        prefs?.edit()?.putInt(key, level)?.apply()
    }
}