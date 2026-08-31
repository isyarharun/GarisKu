package com.isyarharun.garisku

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Singleton wrapper around [SoundPool] for low-latency game SFX.
 *
 * Sounds:
 *  - TICK  : played on every successful cell connection while dragging
 *  - UNDO  : played when retracing one step backward
 *  - ERROR : played when tapping/dragging onto an invalid cell
 *  - WIN   : played once when the level is completed
 *
 * Init from MainActivity.onCreate(), release in onDestroy().
 */
object SoundManager {

    private enum class SoundType { TICK, UNDO, ERROR, WIN }

    private var pool: SoundPool? = null
    private val soundIds = mutableMapOf<SoundType, Int>()
    private val loaded = mutableSetOf<SoundType>()

    // Debounce timestamps (nanos) to avoid machine-gun playback on fast drags.
    private var lastTickNanos = 0L
    private var lastErrorNanos = 0L

    private const val TICK_DEBOUNCE_MS = 50L
    private const val ERROR_DEBOUNCE_MS = 200L

    fun init(context: Context) {
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(attrs)
            .build()
            .also { sp ->
                sp.setOnLoadCompleteListener { _, sampleId, status ->
                    if (status == 0) {
                        loaded += soundIds.entries.firstOrNull { it.value == sampleId }?.key ?: return@setOnLoadCompleteListener
                    }
                }
            }

        // Placeholder WAV tones — replace with real .ogg SFX later (same resource names).
        soundIds[SoundType.TICK] = pool!!.load(context, R.raw.sfx_tick, 1)
        soundIds[SoundType.UNDO] = pool!!.load(context, R.raw.sfx_undo, 1)
        soundIds[SoundType.ERROR] = pool!!.load(context, R.raw.sfx_error, 1)
        soundIds[SoundType.WIN] = pool!!.load(context, R.raw.sfx_win, 1)
    }

    fun release() {
        pool?.release()
        pool = null
        soundIds.clear()
        loaded.clear()
    }

    private fun play(type: SoundType, volume: Float = 1f) {
        val id = soundIds[type] ?: return
        if (type !in loaded) return // not decoded yet — skip silently
        pool?.play(id, volume, volume, 1, 0, 1f)
    }

    /** Short blip per connected cell. Debounced to avoid spam on fast drags. */
    fun playTick() {
        val now = System.nanoTime()
        if (now - lastTickNanos < TICK_DEBOUNCE_MS * 1_000_000) return
        lastTickNanos = now
        play(SoundType.TICK)
    }

    /** Soft pop when retracing one step backward. */
    fun playUndo() = play(SoundType.UNDO)

    /** Low thud on invalid cell. Debounced so repeated taps don't stack. */
    fun playError() {
        val now = System.nanoTime()
        if (now - lastErrorNanos < ERROR_DEBOUNCE_MS * 1_000_000) return
        lastErrorNanos = now
        play(SoundType.ERROR)
    }

    /** Triumphant chime on level completion. */
    fun playWin() = play(SoundType.WIN)
}
