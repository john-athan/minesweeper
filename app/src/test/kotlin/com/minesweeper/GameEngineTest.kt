package com.minesweeper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine, on the JVM, with no device.
 *
 * The reveal cascade runs on the scope the engine is handed, and so does the
 * one-second game timer. The timer never finishes, so the engine gets a scope
 * of its own driven by the test scheduler: time can be advanced deliberately,
 * and `runTest` is not left waiting for a loop that runs forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameEngineTest {

    private fun TestScope.engine(): Pair<GameEngine, CoroutineScope> {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        return GameEngine(scope) to scope
    }

    private fun GameEngine.minesOnBoard() = cells.count { it.isMine }

    // ── The ceiling ───────────────────────────────────────────────────────────

    @Test
    fun `the ceiling leaves room for the safe area`() {
        // A safe tap carves out 3x3, so a 5x5 grid holds 25 - 9 = 16.
        assertEquals(16, Difficulty.maxMines(5, 5, 1))
        // No safe tap, so every cell but one is available.
        assertEquals(25, Difficulty.maxMines(5, 5, -1))
        // Radius 0 protects only the tapped cell.
        assertEquals(24, Difficulty.maxMines(5, 5, 0))
        // A grid smaller than the safe area clips it rather than going negative.
        assertEquals(1, Difficulty.maxMines(2, 2, 1))
    }

    @Test
    fun `every preset asks for a number of mines that fits`() {
        for (d in Difficulty.presets) {
            assertTrue("${d.label} asks for ${d.mines}, at most ${d.maxMines} fit",
                       d.mines <= d.maxMines)
        }
    }

    @Test
    fun `custom clamps the mine count to what the grid can hold`() {
        // The slider used to top out at rows*cols-1, which ignored the safe area:
        // 24 was offered, 16 could be placed, and the win condition believed 24.
        assertEquals(16, Difficulty.custom(5, 24, fog = false, safeStart = true).mines)
        // Without a safe tap the whole grid is available again.
        assertEquals(24, Difficulty.custom(5, 24, fog = false, safeStart = false).mines)
        // A count that already fits is left alone.
        assertEquals(10, Difficulty.custom(9, 10, fog = false, safeStart = true).mines)
    }

    // ── Placement ─────────────────────────────────────────────────────────────

    @Test
    fun `the board carries exactly the number of mines it asked for`() = runTest {
        val configs = Difficulty.presets +
            Difficulty.custom(5, 16, fog = false, safeStart = true) +
            Difficulty.custom(5, 25, fog = false, safeStart = false) +
            Difficulty.custom(20, 399, fog = false, safeStart = true)

        for (d in configs) {
            val (g, scope) = engine()
            g.newGame(d)
            // Mines are placed synchronously inside reveal, before the cascade
            // is launched, so the count is readable straight away.
            g.reveal(d.rows / 2, d.cols / 2)
            assertEquals("${d.label} ${d.rows}x${d.cols}", d.mines, g.minesOnBoard())
            scope.cancel()
        }
    }

    @Test
    fun `a safe first tap is never a mine`() = runTest {
        repeat(50) {
            val (g, scope) = engine()
            g.newGame(Difficulty.custom(5, 16, fog = false, safeStart = true))
            g.reveal(2, 2)
            assertTrue("the first tap landed on a mine", !g.cell(2, 2).isMine)
            assertNotEquals(GameStatus.LOST, g.status)
            scope.cancel()
        }
    }

    // ── Winning and losing ────────────────────────────────────────────────────

    @Test
    fun `a full board is won only once every safe cell is revealed`() = runTest {
        // 5x5 at the ceiling with a safe tap in the middle is fully determined:
        // the 3x3 around (2,2) is the only mine-free region, so the cascade has
        // exactly nine cells to reveal and stops at the ring of mines.
        val (g, scope) = engine()
        g.newGame(Difficulty.custom(5, 16, fog = false, safeStart = true))
        g.reveal(2, 2)

        testScheduler.runCurrent()
        assertEquals(1, g.cells.count { it.isRevealed })
        // The old win condition read 25 - 24 = 1 here and declared victory on
        // the first revealed cell.
        assertEquals(GameStatus.PLAYING, g.status)

        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()
        assertEquals(9, g.cells.count { it.isRevealed && !it.isMine })
        assertEquals(GameStatus.WON, g.status)
        scope.cancel()
    }

    @Test
    fun `the win condition reads the board, not the requested mine count`() = runTest {
        // custom() cannot produce this any more, but the constructor is public and
        // a preset typo would look exactly like it: 24 asked for, 16 placeable.
        // Reading difficulty.mines here made safe = 1 and won on the first cell.
        val overloaded = Difficulty(
            rows = 5, cols = 5, mines = 24, label = "Overloaded",
            safeRadius = 1, chordEnabled = true,
            countdownSeconds = null, fogSeconds = null)

        val (g, scope) = engine()
        g.newGame(overloaded)
        g.reveal(2, 2)
        assertEquals(16, g.minesOnBoard())

        testScheduler.runCurrent()
        assertEquals(GameStatus.PLAYING, g.status)

        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()
        assertEquals(9, g.cells.count { it.isRevealed && !it.isMine })
        assertEquals(GameStatus.WON, g.status)
        scope.cancel()
    }

    @Test
    fun `revealing a mine loses immediately`() = runTest {
        val (g, scope) = engine()
        g.newGame(Difficulty.custom(5, 16, fog = false, safeStart = true))
        g.reveal(2, 2)
        val mine = g.cells.first { it.isMine }

        g.reveal(mine.row, mine.col)
        assertEquals(GameStatus.LOST, g.status)
        assertTrue(g.cell(mine.row, mine.col).isExploded)
        scope.cancel()
    }

    @Test
    fun `a flagged cell cannot be revealed and moves the counter`() = runTest {
        val (g, scope) = engine()
        g.newGame(Difficulty.EASY)
        assertEquals(Difficulty.EASY.mines, g.minesLeft)

        g.toggleFlag(0, 0)
        assertEquals(Difficulty.EASY.mines - 1, g.minesLeft)
        g.reveal(0, 0)
        assertEquals(GameStatus.IDLE, g.status)

        g.toggleFlag(0, 0)
        assertEquals(Difficulty.EASY.mines, g.minesLeft)
        scope.cancel()
    }
}
