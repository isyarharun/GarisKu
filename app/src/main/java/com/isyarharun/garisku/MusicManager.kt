package com.isyarharun.garisku

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.MediaPlayer

/**
 * Background music player for the main menu.
 * - Looping OGG resource (res/raw/bg_music.ogg)
 * - ON/OFF toggle persisted in SharedPreferences
 * - pause()/resume() for app lifecycle (background/foreground)
 *
 * Music: "Wallpaper" by Kevin MacLeod (incompetech.com), CC-BY 4.0.
 */
object MusicManager {

    private var player: MediaPlayer? = null
    private var prefs: SharedPreferences? = null
    private var prepared = false

    private const val KEY_ENABLED = "music_enabled"

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences("garisku_progress", Context.MODE_PRIVATE)
        player = MediaPlayer.create(context, R.raw.bg_music)?.apply {
            isLooping = true
            setVolume(0.6f, 0.6f)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
        }
        prepared = player != null
    }

    /** Whether the user wants music (persisted). */
    fun isEnabled(): Boolean = prefs?.getBoolean(KEY_ENABLED, true) ?: true

    /** Start music if enabled. Safe to call repeatedly. */
    fun start() {
        val p = player ?: return
        if (!prepared) return
        if (isEnabled() && !p.isPlaying) {
            p.start()
        }
    }

    /** Stop music but keep the toggle state. */
    fun pause() {
        val p = player ?: return
        if (p.isPlaying) p.pause()
    }

    /** Toggle music on/off. Saves preference. Returns the new enabled state. */
    fun toggle(): Boolean {
        val newState = !isEnabled()
        prefs?.edit()?.putBoolean(KEY_ENABLED, newState)?.apply()
        if (newState) start() else pause()
        return newState
    }

    /** Release resources — call from Activity.onDestroy(). */
    fun release() {
        player?.release()
        player = null
        prepared = false
    }
}