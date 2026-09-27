package com.minesweeper

import android.content.Context
import android.content.SharedPreferences

// ── Storage seam ──────────────────────────────────────────────────────────────

/**
 * A tiny seam over SharedPreferences so [StatsRecorder] runs on the plain JVM
 * in tests, with no Robolectric needed just to fake an Android Context.
 */
interface StatsStore {
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
}

private const val PREFS_NAME = "minesweeper_prefs"

/** Key for the flag mode toggle (issue: flag mode), shares this same file. */
const val KEY_FLAG_MODE = "flag_mode"

/** The app's one SharedPreferences file, opened wherever it's needed. */
fun appPrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

class SharedPrefsStatsStore(private val prefs: SharedPreferences) : StatsStore {
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int)   = prefs.edit().putInt(key, value).apply()
}

// ── Recorder ──────────────────────────────────────────────────────────────────

/** A preset's stats right after [StatsRecorder.record] folded a game into them. */
data class RecordedGame(
    val finished:    Int,
    val won:         Int,
    val bestSeconds: Int?,
    val isNewBest:   Boolean,
)

/**
 * Best times and win counts, per preset difficulty. Custom boards vary board
 * to board, so callers only ever pass a preset's label (issue: stats).
 */
class StatsRecorder(private val store: StatsStore) {

    fun record(label: String, won: Boolean, elapsedSeconds: Int): RecordedGame {
        val finishedKey = "$label:finished"
        val wonKey      = "$label:won"
        val bestKey     = "$label:best"

        val finished = store.getInt(finishedKey, 0) + 1
        store.putInt(finishedKey, finished)

        var wonCount  = store.getInt(wonKey, 0)
        var best      = store.getInt(bestKey, NO_BEST).takeIf { it != NO_BEST }
        var isNewBest = false

        if (won) {
            wonCount += 1
            store.putInt(wonKey, wonCount)
            if (best == null || elapsedSeconds < best) {
                best = elapsedSeconds
                store.putInt(bestKey, best)
                isNewBest = true
            }
        }

        return RecordedGame(finished, wonCount, best, isNewBest)
    }

    companion object {
        // Sentinel rather than a nullable getInt: SharedPreferences (and the
        // fake store in tests) only ever stores a plain Int.
        private const val NO_BEST = -1
    }
}
