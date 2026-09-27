package com.minesweeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A plain map standing in for SharedPreferences, so this runs on the JVM
 *  with no Robolectric. */
private class FakeStatsStore : StatsStore {
    private val values = mutableMapOf<String, Int>()
    override fun getInt(key: String, default: Int) = values[key] ?: default
    override fun putInt(key: String, value: Int) { values[key] = value }
}

class StatsRecorderTest {

    @Test
    fun `a win records a finished game, a win, and a first best`() {
        val recorder = StatsRecorder(FakeStatsStore())
        val result = recorder.record("Easy", won = true, elapsedSeconds = 90)

        assertEquals(1, result.finished)
        assertEquals(1, result.won)
        assertEquals(90, result.bestSeconds)
        assertTrue(result.isNewBest)
    }

    @Test
    fun `a loss counts as finished but not won, and leaves the best alone`() {
        val recorder = StatsRecorder(FakeStatsStore())
        recorder.record("Easy", won = true, elapsedSeconds = 90)
        val result = recorder.record("Easy", won = false, elapsedSeconds = 5)

        assertEquals(2, result.finished)
        assertEquals(1, result.won)
        assertEquals(90, result.bestSeconds)
        assertFalse(result.isNewBest)
    }

    @Test
    fun `a faster win lowers the best, a slower one does not`() {
        val recorder = StatsRecorder(FakeStatsStore())
        recorder.record("Hard", won = true, elapsedSeconds = 60)

        val slower = recorder.record("Hard", won = true, elapsedSeconds = 70)
        assertEquals(60, slower.bestSeconds)
        assertFalse(slower.isNewBest)

        val faster = recorder.record("Hard", won = true, elapsedSeconds = 45)
        assertEquals(45, faster.bestSeconds)
        assertTrue(faster.isNewBest)
    }

    @Test
    fun `presets are tracked separately`() {
        val store = FakeStatsStore()
        val recorder = StatsRecorder(store)
        recorder.record("Easy", won = true, elapsedSeconds = 30)
        val medium = recorder.record("Medium", won = true, elapsedSeconds = 200)

        // A fresh label starts from zero, not wherever "Easy" left off.
        assertEquals(1, medium.finished)
        assertEquals(200, medium.bestSeconds)
    }
}
