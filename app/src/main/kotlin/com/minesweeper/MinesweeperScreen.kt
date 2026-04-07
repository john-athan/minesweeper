@file:OptIn(ExperimentalFoundationApi::class)

package com.minesweeper

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.hapticfeedback.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import com.minesweeper.ui.theme.*
import kotlin.math.roundToInt

// ── Number colours ────────────────────────────────────────────────────────────
private val NUM_COLORS = arrayOf(
    Color.Transparent,
    Num1, Num2, Num3, Num4, Num5, Num6, Num7, Num8,
)

// ── Root ──────────────────────────────────────────────────────────────────────

@Composable
fun MinesweeperApp(game: GameEngine) {
    val haptic = LocalHapticFeedback.current

    // Screen shake on loss
    val shakeX = remember { Animatable(0f) }
    LaunchedEffect(game.status) {
        if (game.status == GameStatus.LOST) {
            repeat(5) {
                shakeX.animateTo(12f, tween(40))
                shakeX.animateTo(-12f, tween(40))
            }
            shakeX.animateTo(0f, tween(60))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF0D1B2A), SpaceBlack),
                    radius = 1600f,
                )
            )
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(shakeX.value.roundToInt(), 0) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GameHeader(game = game, onReset = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                game.newGame()
            })

            Spacer(Modifier.height(4.dp))
            DifficultyBar(selected = game.difficulty, onSelect = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                game.newGame(it)
            })
            Spacer(Modifier.height(4.dp))

            // Fixed-height reserved slot for the status banner so the grid
            // doesn't resize when the banner appears/disappears
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                contentAlignment = Alignment.Center,
            ) {
                StatusBanner(game.status)
            }

            Spacer(Modifier.height(4.dp))

            // Grid — expands to fill whatever remains
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                val cols = game.difficulty.cols
                val rows = game.difficulty.rows
                val gap  = 1.dp

                // Fill screen: compute largest cell that fits in both axes
                val cellSize = remember(cols, rows, maxWidth, maxHeight) {
                    val cw = (maxWidth  - gap * (cols - 1)) / cols
                    val ch = (maxHeight - gap * (rows - 1)) / rows
                    minOf(cw, ch).coerceAtLeast(8.dp)
                }

                LazyVerticalGrid(
                    columns               = GridCells.Fixed(cols),
                    modifier              = Modifier.wrapContentSize(),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement   = Arrangement.spacedBy(gap),
                    contentPadding        = PaddingValues(0.dp),
                    userScrollEnabled     = false,
                ) {
                    items(
                        count = game.cells.size,
                        key   = { i -> game.cells[i].run { row * 1000 + col } },
                    ) { i ->
                        val cell = game.cells[i]
                        CellView(
                            cell        = cell,
                            cellSize    = cellSize,
                            onTap       = {
                                if (game.status == GameStatus.WON || game.status == GameStatus.LOST) return@CellView
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                when {
                                    cell.isFogged   -> Unit  // can't interact with fogged cells
                                    cell.isRevealed -> game.chord(cell.row, cell.col)
                                    else            -> game.reveal(cell.row, cell.col)
                                }
                            },
                            onLongPress = {
                                if (game.status == GameStatus.WON || game.status == GameStatus.LOST) return@CellView
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                game.toggleFlag(cell.row, cell.col)
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
private fun GameHeader(game: GameEngine, onReset: () -> Unit) {
    val face = when (game.status) {
        GameStatus.WON     -> "😎"
        GameStatus.LOST    -> "😵"
        GameStatus.PLAYING -> "🙂"
        GameStatus.IDLE    -> "😊"
    }

    // Blinking countdown when time is low
    val infiniteTransition = rememberInfiniteTransition(label = "timeLow")
    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue  = 0.5f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse),
        label         = "blinkAlpha",
    )
    val timerColor = when {
        game.isTimeLow                         -> Color(0xFFFF3B30).copy(alpha = blinkAlpha)
        game.difficulty.countdownSeconds != null -> Color(0xFFFF9500)
        else                                   -> Color(0xFFFF3B30)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment         = Alignment.CenterVertically,
        horizontalArrangement     = Arrangement.SpaceBetween,
    ) {
        LedDisplay(
            text  = game.minesLeft.coerceIn(-99, 999).toString().padStart(3),
            color = Color(0xFFFF3B30),
        )

        Surface(
            onClick        = onReset,
            shape          = RoundedCornerShape(14.dp),
            color          = Color(0xFF1B2B3C),
            tonalElevation = 4.dp,
            modifier       = Modifier.size(50.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(face, fontSize = 24.sp)
            }
        }

        LedDisplay(text = game.formattedTime, color = timerColor)
    }
}

@Composable
private fun LedDisplay(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF040710))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text("888", fontFamily = FontFamily.Monospace, fontSize = 20.sp,
            color = Color(0xFF1A1A1A), fontWeight = FontWeight.Bold)
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 20.sp,
            color = color, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

// ── Difficulty bar ────────────────────────────────────────────────────────────

@Composable
private fun DifficultyBar(selected: Difficulty, onSelect: (Difficulty) -> Unit) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Difficulty.entries.forEach { diff ->
            val active = selected == diff
            val bg by animateColorAsState(
                if (active) CyberCyan else Color(0xFF1B2B3C), tween(250), label = "bg"
            )
            val fg by animateColorAsState(
                if (active) SpaceBlack else MutedBlue, tween(250), label = "fg"
            )
            val modifiers = buildList {
                if (diff.countdownSeconds != null) add("⏱")
                if (diff.fogSeconds != null)       add("👁")
                if (diff.safeRadius < 0)           add("⚡")
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg)
                    .pointerInput(diff) { detectTapGestures(onTap = { onSelect(diff) }) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text       = diff.label,
                        color      = fg,
                        fontSize   = 13.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    )
                    if (modifiers.isEmpty()) {
                        Text(
                            text     = "${diff.mines}💣",
                            color    = fg.copy(alpha = 0.65f),
                            fontSize = 10.sp,
                        )
                    } else {
                        Text(
                            text     = modifiers.joinToString(""),
                            color    = fg.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

// ── Status banner ─────────────────────────────────────────────────────────────

@Composable
private fun StatusBanner(status: GameStatus) {
    val visible = status == GameStatus.WON || status == GameStatus.LOST
    val bg      = if (status == GameStatus.WON) Color(0xFF0E3A20) else Color(0xFF3D0808)
    val text    = if (status == GameStatus.WON) "🎉  You Win!  🎉" else "💥  Game Over  💥"

    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue  = 0.75f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label         = "bannerAlpha",
    )

    AnimatedVisibility(
        visible = visible,
        enter   = slideInVertically { -it } + fadeIn(),
        exit    = slideOutVertically { -it } + fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text       = text,
                style      = MaterialTheme.typography.titleMedium,
                color      = SoftWhite.copy(alpha = pulse),
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

// ── Cell view ─────────────────────────────────────────────────────────────────

@Composable
private fun CellView(
    cell:        Cell,
    cellSize:    Dp,
    onTap:       () -> Unit,
    onLongPress: () -> Unit,
) {
    // Pop-in when revealed
    val popScale = remember(cell.row, cell.col) { Animatable(if (cell.isRevealed) 1f else 0f) }
    LaunchedEffect(cell.isRevealed) {
        if (cell.isRevealed && popScale.value < 0.5f) {
            popScale.snapTo(0.72f)
            popScale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
        } else if (!cell.isRevealed) {
            popScale.snapTo(0f)
        }
    }

    // Re-fog fade: when isFogged becomes true, animate back to "closed" look
    val fogScale = remember(cell.row, cell.col) { Animatable(0f) }
    LaunchedEffect(cell.isFogged) {
        if (cell.isFogged) {
            fogScale.animateTo(1f, tween(400))
        } else {
            fogScale.snapTo(0f)
        }
    }

    // Flag bounce
    val flagScale = remember(cell.row, cell.col) { Animatable(if (cell.isFlagged) 1f else 0f) }
    LaunchedEffect(cell.isFlagged) {
        if (cell.isFlagged) {
            flagScale.snapTo(0f)
            flagScale.animateTo(1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow))
        } else {
            flagScale.animateTo(0f, tween(130))
        }
    }

    // Explosion pulse
    val explodeInf = rememberInfiniteTransition(label = "expl")
    val explodePulse by explodeInf.animateFloat(
        initialValue  = 1f,
        targetValue   = if (cell.isExploded) 1.18f else 1f,
        animationSpec = infiniteRepeatable(tween(280), RepeatMode.Reverse),
        label         = "explPulse",
    )

    val currentOnTap       by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    // Visually treat fogged cells like unrevealed ones
    val appearsRevealed = cell.isRevealed && !cell.isFogged
    val revealScale     = if (appearsRevealed) popScale.value.coerceAtLeast(0.72f) else 1f
    val totalScale      = revealScale * (if (cell.isExploded) explodePulse else 1f)

    Box(
        modifier = Modifier
            .size(cellSize)
            .scale(totalScale)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap       = { currentOnTap() },
                    onLongPress = { currentOnLongPress() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // ── Canvas background ─────────────────────────────────────────────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cr = CornerRadius(4.dp.toPx())
            val w  = size.width
            val h  = size.height

            when {
                // ── Fogged (revealed but memory hidden) ───────────────────────
                cell.isFogged -> {
                    drawCellShadow(w, h, cr)
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            colors = listOf(Color(0xFF2A2248), Color(0xFF1A1538)),
                            start  = Offset(0f, 0f), end = Offset(w, h),
                        ),
                        size         = Size(w - 1.5f, h - 1.5f),
                        cornerRadius = cr,
                    )
                    drawHighlightEdges(w, h)
                    // Subtle fog overlay that fades in
                    drawRoundRect(
                        color        = Color(0x30A890FF).copy(alpha = 0.3f * fogScale.value),
                        size         = Size(w - 1.5f, h - 1.5f),
                        cornerRadius = cr,
                    )
                }

                // ── Unrevealed – flagged ──────────────────────────────────────
                !cell.isRevealed && cell.isFlagged -> {
                    drawCellShadow(w, h, cr)
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            colors = listOf(Color(0xFF1E3D28), Color(0xFF162A1C)),
                            start  = Offset(0f, 0f), end = Offset(w, h),
                        ),
                        size         = Size(w - 1.5f, h - 1.5f),
                        cornerRadius = cr,
                    )
                    drawHighlightEdges(w, h)
                }

                // ── Unrevealed – normal ───────────────────────────────────────
                !cell.isRevealed -> {
                    drawCellShadow(w, h, cr)
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            colors = listOf(Color(0xFF253648), Color(0xFF1B2B3C)),
                            start  = Offset(0f, 0f), end = Offset(w, h),
                        ),
                        size         = Size(w - 1.5f, h - 1.5f),
                        cornerRadius = cr,
                    )
                    drawHighlightEdges(w, h)
                }

                // ── Exploded mine ─────────────────────────────────────────────
                cell.isExploded -> {
                    drawRoundRect(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFFFF6600), Color(0xFFCC1100), Color(0xFF4D0000)),
                            center = Offset(w * .45f, h * .4f),
                            radius = maxOf(w, h) * 0.85f,
                        ),
                        cornerRadius = cr,
                    )
                    drawRoundRect(
                        color        = Color(0x60FF3300),
                        style        = Stroke(width = 3.dp.toPx()),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                }

                // ── Revealed mine ─────────────────────────────────────────────
                cell.isMine -> {
                    drawRoundRect(color = Color(0xFF250606), cornerRadius = cr)
                    drawRoundRect(color = Color(0x25FF1A1A), style = Stroke(1.5f), cornerRadius = cr)
                }

                // ── Revealed safe ─────────────────────────────────────────────
                else -> {
                    drawRoundRect(color = DeepNavy, cornerRadius = cr)
                    drawRoundRect(color = Color(0x18FFFFFF), style = Stroke(1f), cornerRadius = cr)
                }
            }
        }

        // ── Content ───────────────────────────────────────────────────────────
        val fs = (cellSize.value * 0.48f).sp

        when {
            cell.isFogged ->
                Text("·", fontSize = fs * 0.6f, color = Color(0x558090FF))

            cell.isFlagged && !cell.isRevealed ->
                Text("⚑", fontSize = fs, color = Color(0xFFFF6B35),
                    modifier = Modifier.scale(flagScale.value.coerceAtLeast(0f)))

            cell.isRevealed && cell.isExploded ->
                Text("💥", fontSize = fs)

            cell.isRevealed && cell.isMine ->
                Text("💣", fontSize = fs * 0.85f)

            cell.isRevealed && cell.neighborMines > 0 ->
                Text(
                    text          = cell.neighborMines.toString(),
                    fontSize      = fs,
                    fontWeight    = FontWeight.ExtraBold,
                    color         = NUM_COLORS[cell.neighborMines],
                    fontFamily    = FontFamily.Monospace,
                    letterSpacing = 0.sp,
                )
        }
    }
}

// ── Canvas helpers ────────────────────────────────────────────────────────────

private fun DrawScope.drawCellShadow(w: Float, h: Float, cr: CornerRadius) {
    drawRoundRect(color = Color(0x70000000), topLeft = Offset(1.8f, 1.8f),
        size = Size(w, h), cornerRadius = cr)
}

private fun DrawScope.drawHighlightEdges(w: Float, h: Float) {
    drawLine(Color(0x55FFFFFF), Offset(5f, 2.5f), Offset(w - 6f, 2.5f), strokeWidth = 1.5f)
    drawLine(Color(0x33FFFFFF), Offset(2.5f, 5f), Offset(2.5f, h - 6f), strokeWidth = 1.5f)
    drawLine(Color(0x60000000), Offset(5f, h - 2f), Offset(w - 6f, h - 2f), strokeWidth = 1.5f)
    drawLine(Color(0x40000000), Offset(w - 2f, 5f), Offset(w - 2f, h - 6f), strokeWidth = 1.5f)
}
