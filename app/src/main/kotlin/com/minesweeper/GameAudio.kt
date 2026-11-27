package com.minesweeper

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.VibrationEffect
import android.os.VibratorManager

/** Small SoundPool wrapper for short, low-latency arcade effects. */
class GameAudio(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(5)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val loadedSamples = mutableSetOf<Int>()
    private val explosionId: Int
    private val revealId: Int
    private val clickId: Int
    private val menuId: Int

    @Volatile private var enabled = true
    @Volatile private var volume = 0.72f

    init {
        // SoundPool loads asynchronously; don't drop a sample into play() before
        // Android has finished decoding it (especially on the first menu tap).
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) synchronized(loadedSamples) { loadedSamples += sampleId }
        }
        explosionId = pool.load(context, R.raw.mine_explosion, 1)
        revealId = pool.load(context, R.raw.cell_reveal, 1)
        clickId = pool.load(context, R.raw.cell_click, 1)
        menuId = pool.load(context, R.raw.menu_click, 1)
    }

    fun configure(isEnabled: Boolean, level: Float) {
        enabled = isEnabled
        volume = level.coerceIn(0f, 1f)
    }

    fun explosion() = play(explosionId)
    fun reveal() = play(revealId)
    fun tap() = play(clickId)
    fun menu() = play(menuId)

    private fun play(soundId: Int) {
        if (!enabled || soundId == 0 || volume <= 0f) return
        val ready = synchronized(loadedSamples) { soundId in loadedSamples }
        if (ready) pool.play(soundId, volume, volume, 1, 0, 1f)
    }

    fun release() = pool.release()
}

/** A short physical confirmation only for a directly triggered mine. */
fun triggerMineVibration(context: Context) {
    val manager = context.getSystemService(VibratorManager::class.java) ?: return
    val vibrator = manager.defaultVibrator
    if (vibrator.hasVibrator()) {
        vibrator.vibrate(VibrationEffect.createOneShot(190L, 180))
    }
}
