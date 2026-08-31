package com.isyarharun.garisku

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ── Color Palette (KaloriKu theme) ────────────────────────
private val GridBg = BackgroundDark          // #121212
private val CellEmpty = SurfaceVariantDark   // #2C2C2C
private val CellNumbered = GreenPrimaryDarkTheme // #7ED957 (hijau terang)
private val CellVisited = OrangeSecondaryDark    // #FFB74D (oranye hangat)
private val LineColor = GreenPrimary         // #65C32F (hijau garis)
private val TargetRing = OrangeSecondary     // #F57C00 (oranye pulsing)
private val WinOverlay = GreenSuccess.copy(alpha = 0.55f) // #4CAF50
private val BrickColor = Color(0xFF37474F)   // bata abu-abu biru (blocked cell)

private fun buildLevel(mode: GameMode, levelNumber: Int): GameState {
    // Levels are pre-generated data (assets) → instant load, no runtime DFS.
    val elapsed = LevelProgress.getElapsedSeconds(mode, levelNumber)
    val data = LevelRepository.getLevel(mode, levelNumber)
    if (data != null) {
        return GameState(
            data.rows, data.cols, data.numberPositions,
            data.numberPositions.size, mode, data.blocks,
            initialElapsedSeconds = elapsed
        )
    }
    // Fallback safety.
    val gs = LevelFactory.build(mode, levelNumber)
    return GameState(
        gs.rows, gs.cols, gs.numberPositions, gs.totalNumbers, mode, gs.blocks,
        initialElapsedSeconds = elapsed
    )
}

private fun formatTime(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
}

@Composable
fun GarisKuGame(
    mode: GameMode,
    levelNumber: Int,
    onExit: () -> Unit,
    onNextLevel: () -> Unit,
    modifier: Modifier = Modifier
) {
    // (Re)create the game state when mode or level changes → fresh generated level.
    val gameState = remember(mode, levelNumber) {
        buildLevel(mode, levelNumber)
    }

    // Error auto-clear
    if (gameState.hasError) {
        LaunchedEffect(Unit) { delay(400); gameState.clearError() }
    }

    val errorAnim by animateFloatAsState(
        targetValue = if (gameState.hasError) 1f else 0f,
        animationSpec = tween(200), label = "errorShake"
    )

    // Auto-save progress when a level is completed (next level unlocked).
    LaunchedEffect(gameState.isComplete) {
        if (gameState.isComplete) {
            SoundManager.playWin()
            LevelProgress.saveElapsedSeconds(mode, levelNumber, gameState.elapsedSeconds)
            LevelProgress.markCompleted(mode, levelNumber)
            LevelProgress.unlockNext(mode, levelNumber)
        }
    }

    // Auto-advance: shortly after completion, jump straight to the next level.
    LaunchedEffect(gameState.isComplete) {
        if (gameState.isComplete && levelNumber < LEVELS_PER_MODE) {
            delay(1500)
            onNextLevel()
        }
    }

    // Timer tick — cumulative across retries: stops on completion,
    // resumes automatically once the level is reset and retried.
    LaunchedEffect(gameState.timerStarted, gameState.isComplete) {
        while (gameState.timerStarted && !gameState.isComplete) {
            delay(1000)
            gameState.tickSecond()
            LevelProgress.saveElapsedSeconds(mode, levelNumber, gameState.elapsedSeconds)
        }
    }

    // Save the cumulative timer whenever the game screen is left (grid/menu),
    // so re-selecting the same level resumes where it left off.
    DisposableEffect(mode, levelNumber) {
        onDispose {
            LevelProgress.saveElapsedSeconds(mode, levelNumber, gameState.elapsedSeconds)
        }
    }

    Column(
        modifier = modifier.fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // ── Title ────────────────────────────────────────
        Text("GarisKu", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)

        Spacer(modifier = Modifier.height(8.dp))

        // ── Level ─────────────────────────────────────────
        Text(
            text = "Level ${levelNumber}  |  ${gameState.rows}×${gameState.rows}  ${gameState.totalNumbers} nomor",
            color = CellNumbered, fontSize = 16.sp, fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── Hint ─────────────────────────────────────────
        Text(
            text = if (mode == GameMode.CHALLENGE)
                "Lewati semua sel terbuka, hindari bata 🧱"
            else "Hubungkan nomor berurutan",
            color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── Timer ────────────────────────────────────────
        if (gameState.isComplete) {
            Text("✨ Selesai! ✨", color = TargetRing, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
        } else if (gameState.isWrongRoute) {
            Text("❌ Rute salah — penuhi kembali!", color = Color(0xFFFF4444), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Text("⏱ ${formatTime(gameState.elapsedSeconds)}${
            if (mode == GameMode.CHALLENGE) "  |  ${gameState.path.size}/${gameState.openCellCount} sel" else ""
        }",
            color = Color.White.copy(alpha = if (gameState.timerStarted) 1f else 0.5f),
            fontSize = if (gameState.isComplete) 24.sp else 16.sp)

        Spacer(modifier = Modifier.height(16.dp))

        // ── Grid ─────────────────────────────────────────
        GameGrid(gameState = gameState, errorAnim = errorAnim,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f))

        Spacer(modifier = Modifier.height(20.dp))

        // ── Reset / Level ───────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { gameState.reset() },
                colors = ButtonDefaults.buttonColors(containerColor = CellEmpty)
            ) { Text("🔄 Ulang", color = Color.White, fontSize = 15.sp) }
            Button(
                onClick = onExit,
                colors = ButtonDefaults.buttonColors(containerColor = CellNumbered)
            ) { Text("🔢 Level", color = Color.White, fontSize = 15.sp) }
        }
    }
}

@Composable
private fun GameGrid(
    gameState: GameState, errorAnim: Float, modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    var cellSizePx by remember { mutableStateOf(0f) }

    var dragPos by remember { mutableStateOf<Offset?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    var lastDragCell by remember { mutableStateOf<Position?>(null) }

    // ── Animations ──────────────────────────────────────
    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        0.5f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulseAlpha"
    )
    val pulseScale by pulseTransition.animateFloat(
        0.95f, 1.05f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulseScale"
    )

    val victoryProgress = remember { Animatable(0f) }
    LaunchedEffect(gameState.isComplete) {
        if (gameState.isComplete) {
            victoryProgress.snapTo(0f)
            victoryProgress.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
        }
    }

    val drawProgress = remember { Animatable(0f) }
    var prevPathSize by remember { mutableStateOf(0) }
    LaunchedEffect(gameState.path.size) {
        if (gameState.path.size > prevPathSize) {
            drawProgress.snapTo(0f)
            drawProgress.animateTo(1f, tween(150))
        }
        prevPathSize = gameState.path.size
    }

    Canvas(
        modifier = modifier
            .pointerInput(gameState) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (cellSizePx <= 0f) return@awaitEachGesture

                    val col = (down.position.x / cellSizePx).toInt()
                        .coerceIn(0, gameState.cols - 1)
                    val row = (down.position.y / cellSizePx).toInt()
                        .coerceIn(0, gameState.rows - 1)

                    if (!gameState.tryConnect(row, col)) {
                        SoundManager.playError()
                        do { } while (awaitPointerEvent().changes.any { it.pressed.not() })
                        return@awaitEachGesture
                    }
                    SoundManager.playTick()

                    isDragging = true
                    dragPos = down.position
                    lastDragCell = Position(row, col)

                    // Applies connect/undo logic for a single cell.
                    // Backward: undo ONLY one step, and only when the finger
                    // touches the path cell right before the tip — so retracing
                    // always follows the exact drawn path, never jumps.
                    fun processCell(cell: Position) {
                        val gPath = gameState.path
                        if (gPath.isEmpty()) return
                        val tip = gPath.last()

                        if (cell == tip) return

                        if (gPath.size >= 2 && cell == gPath[gPath.size - 2]) {
                            // Finger moved back onto the previous path cell → undo one step.
                            gameState.undo()
                            SoundManager.playUndo()
                        } else if (cell !in gPath) {
                            // Forward: only connect if adjacent to the CURRENT tip.
                            if (cell.isAdjacentTo(tip)) {
                                if (gameState.tryConnect(cell.row, cell.col)) {
                                    SoundManager.playTick()
                                } else {
                                    SoundManager.playError()
                                }
                            }
                        }
                        // Any other visited cell: ignore — no jumping.
                    }

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break

                        if (event.type == PointerEventType.Move) {
                            dragPos = change.position

                            val fcol = (change.position.x / cellSizePx).toInt()
                                .coerceIn(0, gameState.cols - 1)
                            val frow = (change.position.y / cellSizePx).toInt()
                                .coerceIn(0, gameState.rows - 1)
                            val newCell = Position(frow, fcol)

                            if (newCell != lastDragCell) {
                                processCell(newCell)
                                lastDragCell = newCell
                            }

                            change.consume()
                        }

                        if (!change.pressed) break
                    } while (true)

                    isDragging = false
                    dragPos = null
                    lastDragCell = null
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val rows = gameState.rows
        val cols = gameState.cols
        cellSizePx = w / cols
        val pad = 4f

        // ── 1. Grid BG ────────────────────────────────────
        drawRoundRect(GridBg, Offset.Zero, Size(w, h), CornerRadius(24f))

        // ── 2. Cells ──────────────────────────────────────
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cell = gameState.grid[r][c]
                val pos = Position(r, c)
                val cx = c * cellSizePx
                val cy = r * cellSizePx

                val cellColor = when {
                    gameState.isBlocked(pos) -> BrickColor
                    cell.isVisited -> CellVisited
                    cell.number != null -> CellNumbered
                    else -> CellEmpty
                }

                val shakeX = if (gameState.hasError && cell.number == gameState.nextNumber)
                    errorAnim * 8f else 0f

                drawRoundRect(cellColor, Offset(cx + pad + shakeX, cy + pad),
                    Size(cellSizePx - pad * 2, cellSizePx - pad * 2), CornerRadius(12f))

                // Brick texture: small inner mark so walls read clearly.
                if (gameState.isBlocked(pos)) {
                    drawLine(
                        color = Color.White.copy(alpha = 0.15f),
                        start = Offset(cx + cellSizePx * 0.3f, cy + pad + 2f),
                        end = Offset(cx + cellSizePx * 0.7f, cy + cellSizePx - pad - 2f),
                        strokeWidth = 3f
                    )
                    // Skip number & ring for bricks.
                    continue
                }

                // Pul ring target
                if (!cell.isVisited && cell.number == gameState.nextNumber) {
                    val ringPad = pad + (cellSizePx - pad * 2) * (1f - pulseScale) / 2f
                    val ringSize = (cellSizePx - pad * 2) * pulseScale
                    drawRoundRect(
                        TargetRing.copy(alpha = pulseAlpha),
                        Offset(cx + ringPad + shakeX, cy + ringPad),
                        Size(ringSize, ringSize), CornerRadius(12f),
                        style = Stroke(width = 3f)
                    )
                }
            }
        }

        // ── 3. Lines ──────────────────────────────────────
        val path = gameState.path
        // Line width scales down relative to cell size on big grids.
        val lineWidth = (cellSizePx * 0.16f).coerceIn(5f, 12f)
        if (path.size >= 2) {
            for (i in 0 until path.size - 1) {
                val from = path[i]
                val to = path[i + 1]
                val alpha = if (i == path.size - 2)
                    0.3f + 0.7f * drawProgress.value else 1f

                drawLine(LineColor.copy(alpha = alpha),
                    Offset(from.col * cellSizePx + cellSizePx / 2,
                        from.row * cellSizePx + cellSizePx / 2),
                    Offset(to.col * cellSizePx + cellSizePx / 2,
                        to.row * cellSizePx + cellSizePx / 2),
                    lineWidth, StrokeCap.Round)
            }

            for (pos in path) {
                if (gameState.isNumbered(pos)) {
                    drawCircle(LineColor, (cellSizePx * 0.08f).coerceIn(3f, 6f),
                        Offset(pos.col * cellSizePx + cellSizePx / 2,
                            pos.row * cellSizePx + cellSizePx / 2))
                }
            }
        }

        // ── 4. Drag trail ──────────────────────────────────
        if (isDragging && dragPos != null && path.isNotEmpty()) {
            val last = path.last()
            val lx = last.col * cellSizePx + cellSizePx / 2
            val ly = last.row * cellSizePx + cellSizePx / 2

            drawLine(LineColor.copy(alpha = 0.4f),
                Offset(lx, ly), dragPos!!, 12f, StrokeCap.Round)
            drawCircle(LineColor.copy(alpha = 0.25f), 18f, dragPos!!)
        }

        // ── 5. Numbers ────────────────────────────────────
        // Font scales down on big grids so numbers stay readable without overflow.
        val numberFont = when {
            cols >= 10 -> 13.sp
            cols >= 9 -> 15.sp
            cols >= 8 -> 17.sp
            cols >= 6 -> 20.sp
            else -> 24.sp
        }
        val textStyle = TextStyle(color = Color.White, fontSize = numberFont, fontWeight = FontWeight.Bold)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cell = gameState.grid[r][c]
                val number = cell.number ?: continue

                val result = textMeasurer.measure(number.toString(), textStyle)
                val cx = c * cellSizePx + cellSizePx / 2
                val cy = r * cellSizePx + cellSizePx / 2

                drawText(textLayoutResult = result,
                    topLeft = Offset(cx - result.size.width / 2f,
                        cy - result.size.height / 2f),
                    color = if (cell.isVisited) Color.White.copy(alpha = 0.5f) else Color.White)
            }
        }

        // ── 6. Victory ─────────────────────────────────────
        if (gameState.isComplete) {
            val bgAlpha = victoryProgress.value * 0.55f
            drawRoundRect(LineColor.copy(alpha = bgAlpha),
                Offset.Zero, Size(w, h), CornerRadius(24f))

            val emojiScale = victoryProgress.value
            val emojiSize = 64.sp * emojiScale
            if (emojiSize.value > 8f) {
                val doneResult = textMeasurer.measure(
                    "🎉", TextStyle(fontSize = emojiSize, fontWeight = FontWeight.Bold))
                drawText(textLayoutResult = doneResult,
                    topLeft = Offset(w / 2 - doneResult.size.width / 2f,
                        h / 2 - doneResult.size.height / 2f))
            }

            for (i in 0..5) {
                val angle = (i / 6f) * kotlin.math.PI.toFloat() * 2f + victoryProgress.value * 2f
                val dist = cellSizePx * 1.5f * victoryProgress.value
                val sx = w / 2 + kotlin.math.cos(angle) * dist
                val sy = h / 2 + kotlin.math.sin(angle) * dist
                drawCircle(
                    TargetRing.copy(alpha = victoryProgress.value *
                        (0.5f + 0.5f * kotlin.math.sin(victoryProgress.value * 10f + i.toFloat()))),
                    4f + 2f * victoryProgress.value, Offset(sx, sy))
            }
        }
    }
}