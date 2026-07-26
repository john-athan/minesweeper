package com.minesweeper

import androidx.compose.runtime.*
import kotlinx.coroutines.*

// ── Difficulty ────────────────────────────────────────────────────────────────

data class Difficulty(
    val rows: Int,
    val cols: Int,
    val mines: Int,
    val label: String,
    /** -1 = no safety, 0 = only clicked cell, 1 = 3×3 area */
    val safeRadius: Int,
    val chordEnabled: Boolean,
    /** null = count up; non-null = count down, lose at 0 */
    val countdownSeconds: Int?,
    /** null = no fog; non-null = cells re-hide after this many seconds */
    val fogSeconds: Int?,
    /** true for user-defined games built via [custom] */
    val isCustom: Boolean = false,
) {
    companion object {
        val EASY = Difficulty(
            9, 9, 10, "Easy",
            safeRadius = 1, chordEnabled = true, countdownSeconds = null, fogSeconds = null)
        val MEDIUM = Difficulty(
            12, 12, 30, "Medium",
            safeRadius = 0, chordEnabled = true, countdownSeconds = 300, fogSeconds = null)
        val HARD = Difficulty(
            14, 14, 50, "Hard",
            safeRadius = -1, chordEnabled = false, countdownSeconds = 180, fogSeconds = 6)

        /** Built-in presets, shown as fixed tabs in the difficulty bar. */
        val presets = listOf(EASY, MEDIUM, HARD)

        /**
         * Build a user-defined game (issue #5). Grid is [size]×[size];
         * mines clamped to leave at least one safe cell. Chord stays on and
         * there's no countdown — custom is about layout, not time pressure.
         */
        fun custom(
            size: Int,
            mines: Int,
            fog: Boolean,
            safeStart: Boolean,
        ) = Difficulty(
            rows             = size,
            cols             = size,
            mines            = mines.coerceIn(1, size * size - 1),
            label            = "Custom",
            safeRadius       = if (safeStart) 1 else -1,
            chordEnabled     = true,
            countdownSeconds = null,
            fogSeconds       = if (fog) 6 else null,
            isCustom         = true,
        )
    }
}

// ── Cell ──────────────────────────────────────────────────────────────────────

enum class GameStatus { IDLE, PLAYING, WON, LOST }

data class Cell(
    val row:           Int,
    val col:           Int,
    val isMine:        Boolean = false,
    val isRevealed:    Boolean = false,
    val isFlagged:     Boolean = false,
    val neighborMines: Int     = 0,
    val isExploded:    Boolean = false,
    /** Fog of war: cell is revealed logically but visually hidden again */
    val isFogged:      Boolean = false,
)

// ── Engine ────────────────────────────────────────────────────────────────────

class GameEngine(private val scope: CoroutineScope) {

    var difficulty     by mutableStateOf(Difficulty.EASY)
        private set
    var status         by mutableStateOf(GameStatus.IDLE)
        private set
    var minesLeft      by mutableStateOf(Difficulty.EASY.mines)
        private set
    var elapsedSeconds by mutableStateOf(0)
        private set
    var cells          by mutableStateOf(buildGrid(Difficulty.EASY))
        private set

    /** Per-cell fog expiry timestamps, parallel to cells list. Not exposed to UI. */
    private var fogExpiry = LongArray(Difficulty.EASY.rows * Difficulty.EASY.cols) { Long.MAX_VALUE }

    private var generation = 0
    private var timerJob: Job? = null
    private var fogJob:   Job? = null

    // ── Public API ────────────────────────────────────────────────────────────

    fun newGame(diff: Difficulty = difficulty) {
        timerJob?.cancel()
        fogJob?.cancel()
        generation++
        difficulty     = diff
        status         = GameStatus.IDLE
        cells          = buildGrid(diff)
        minesLeft      = diff.mines
        elapsedSeconds = 0
    }

    fun reveal(row: Int, col: Int) {
        if (status == GameStatus.WON || status == GameStatus.LOST) return
        val c = cell(row, col)
        if (c.isRevealed || c.isFlagged) return

        if (status == GameStatus.IDLE) {
            placeMines(row, col)
            status = GameStatus.PLAYING
            startTimer()
            startFogJob()
        }

        if (cell(row, col).isMine) {
            explodeMine(row, col)
            return
        }

        val gen = generation
        scope.launch { bfsReveal(row, col, gen) }
    }

    fun toggleFlag(row: Int, col: Int) {
        if (status == GameStatus.WON || status == GameStatus.LOST) return
        val c = cell(row, col)
        if (c.isRevealed) return
        val wasFlagged = c.isFlagged
        updateCell(row, col) { it.copy(isFlagged = !it.isFlagged) }
        minesLeft += if (wasFlagged) 1 else -1
    }

    fun chord(row: Int, col: Int) {
        if (!difficulty.chordEnabled) return
        val c = cell(row, col)
        if (!c.isRevealed || c.neighborMines == 0) return
        val flagCount = neighbors(row, col).count { (r, cl) -> cell(r, cl).isFlagged }
        if (flagCount == c.neighborMines) {
            neighbors(row, col).forEach { (r, cl) ->
                val nc = cell(r, cl)
                if (!nc.isRevealed && !nc.isFlagged) reveal(r, cl)
            }
        }
    }

    val displaySeconds: Int
        get() = if (difficulty.countdownSeconds != null)
            (difficulty.countdownSeconds!! - elapsedSeconds).coerceAtLeast(0)
        else
            elapsedSeconds.coerceAtMost(5999)

    val formattedTime: String
        get() = "%02d:%02d".format(displaySeconds / 60, displaySeconds % 60)

    val isTimeLow: Boolean
        get() = difficulty.countdownSeconds != null &&
                displaySeconds in 0..30 &&
                status == GameStatus.PLAYING

    // ── Internals ─────────────────────────────────────────────────────────────

    fun idx(row: Int, col: Int) = row * difficulty.cols + col
    fun cell(row: Int, col: Int) = cells[idx(row, col)]

    private fun buildGrid(d: Difficulty): List<Cell> {
        fogExpiry = LongArray(d.rows * d.cols) { Long.MAX_VALUE }
        return List(d.rows * d.cols) { i -> Cell(i / d.cols, i % d.cols) }
    }

    private fun updateCell(row: Int, col: Int, fn: (Cell) -> Cell) {
        cells = cells.toMutableList().also { it[idx(row, col)] = fn(it[idx(row, col)]) }
    }

    private fun placeMines(safeRow: Int, safeCol: Int) {
        val safe = buildSet<Pair<Int, Int>> {
            val r = difficulty.safeRadius
            if (r >= 0) {
                for (dr in -r..r) for (dc in -r..r) {
                    val nr = safeRow + dr; val nc = safeCol + dc
                    if (nr in 0 until difficulty.rows && nc in 0 until difficulty.cols) add(nr to nc)
                }
            }
            // safeRadius < 0 → empty safe set (first click can be a mine)
        }
        val minePos = (0 until difficulty.rows).flatMap { r ->
            (0 until difficulty.cols).map { c -> r to c }
        }.filter { it !in safe }.shuffled().take(difficulty.mines).toSet()

        val withMines = cells.map { c -> c.copy(isMine = (c.row to c.col) in minePos) }
        cells = withMines.map { c ->
            c.copy(neighborMines = neighbors(c.row, c.col).count { (r, cl) -> withMines[idx(r, cl)].isMine })
        }
    }

    private fun explodeMine(row: Int, col: Int) {
        updateCell(row, col) { it.copy(isRevealed = true, isExploded = true) }
        // Lose immediately — the mine cascade below is presentation only. Keeping
        // status PLAYING for the length of the animation let the player keep
        // tapping (and even win) mid-explosion (issue #6).
        status = GameStatus.LOST
        timerJob?.cancel()
        fogJob?.cancel()
        val gen = generation
        scope.launch {
            delay(250)
            if (generation != gen) return@launch
            val hiddenMines = cells.filter { it.isMine && !it.isExploded }
            hiddenMines.forEachIndexed { i, mc ->
                if (generation != gen) return@launch
                delay(40L * i)
                if (generation != gen) return@launch
                updateCell(mc.row, mc.col) { it.copy(isRevealed = true) }
            }
        }
    }

    private suspend fun bfsReveal(startRow: Int, startCol: Int, gen: Int) {
        val visited = mutableSetOf<Pair<Int, Int>>()
        var wave = listOf(startRow to startCol)

        while (wave.isNotEmpty()) {
            if (generation != gen) return
            // A wave already in flight when the game ended must not keep
            // revealing cells (or trip checkWin) after the fact (issue #6).
            if (status != GameStatus.PLAYING) return

            val toReveal  = mutableSetOf<Pair<Int, Int>>()
            val nextWave  = mutableListOf<Pair<Int, Int>>()
            val fogOffset = difficulty.fogSeconds?.let { it * 1000L } ?: Long.MAX_VALUE

            for ((r, c) in wave) {
                if (r to c in visited) continue
                visited += r to c
                val curr = cell(r, c)
                if (curr.isFlagged || curr.isRevealed || curr.isMine) continue
                toReveal += r to c
                if (curr.neighborMines == 0) {
                    neighbors(r, c).filter { it !in visited }.forEach { nextWave += it }
                }
            }

            if (toReveal.isNotEmpty()) {
                // Set fog expiry timestamps for this wave
                val now = System.currentTimeMillis()
                toReveal.forEach { (r, c) ->
                    fogExpiry[idx(r, c)] = if (fogOffset == Long.MAX_VALUE) Long.MAX_VALUE else now + fogOffset
                }
                cells = cells.map { c ->
                    if ((c.row to c.col) in toReveal) c.copy(isRevealed = true) else c
                }
                if (checkWin()) return
            }

            wave = nextWave
            if (wave.isNotEmpty()) delay(45)
        }
    }

    private fun checkWin(): Boolean {
        if (status != GameStatus.PLAYING) return false
        val safe = difficulty.rows * difficulty.cols - difficulty.mines
        if (cells.count { it.isRevealed && !it.isMine } == safe) {
            status = GameStatus.WON
            timerJob?.cancel()
            fogJob?.cancel()
            minesLeft = 0
            cells = cells.map { if (it.isMine) it.copy(isFlagged = true) else it }
            return true
        }
        return false
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (true) {
                delay(1000)
                elapsedSeconds++
                val cd = difficulty.countdownSeconds
                if (cd != null && elapsedSeconds >= cd) {
                    // Time's up — reveal all mines and lose
                    cells = cells.map { if (it.isMine) it.copy(isRevealed = true) else it }
                    status = GameStatus.LOST
                    fogJob?.cancel()
                    break
                }
            }
        }
    }

    private fun startFogJob() {
        fogJob?.cancel()
        if (difficulty.fogSeconds == null) return
        val gen = generation
        fogJob = scope.launch {
            while (generation == gen && status == GameStatus.PLAYING) {
                delay(250)
                if (generation != gen) return@launch
                val now = System.currentTimeMillis()
                var changed = false
                val newCells = cells.map { c ->
                    if (!c.isFogged && !c.isFlagged && c.isRevealed && fogExpiry[idx(c.row, c.col)] <= now) {
                        changed = true
                        c.copy(isFogged = true)
                    } else c
                }
                if (changed) cells = newCells
            }
        }
    }

    fun neighbors(row: Int, col: Int): List<Pair<Int, Int>> = buildList {
        for (dr in -1..1) for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            val r = row + dr; val c = col + dc
            if (r in 0 until difficulty.rows && c in 0 until difficulty.cols) add(r to c)
        }
    }
}
