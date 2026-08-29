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

private fun buildLevel(mode: GameMode, levelNumber: Int): GameState {
    val random = kotlin.random.Random.Default
    // Difficulty scaling: grid grows every 5 levels, capped at 10x10.
    val gridSize = (4 + (levelNumber - 1) / 5).coerceIn(4, 10)
    val cells = gridSize * gridSize
    return when (mode) {
        GameMode.SIMPLE -> {
            // Numbers keep growing with level (+1 every 2 levels), capped at half the cells.
            val numbers = (gridSize + kotlin.random.Random.nextInt(0, 2) + (levelNumber - 1) / 2)
                .coerceIn(4, cells / 2)
            val positions = LevelGenerator.generateSimplePath(gridSize, gridSize, numbers, random)
            GameState(rows = gridSize, cols = gridSize, numberPositions = positions, totalNumbers = numbers, mode = mode)
        }
        GameMode.CHALLENGE -> {
            // Numbers grow (+1 every 3 levels), capped at a third of the cells
            // so segments never get trivially short.
            val numbers = (gridSize + gridSize / 2 + (levelNumber - 1) / 3)
                .coerceIn(minOf(6, cells / 3), cells / 3)
            val positions = LevelGenerator.placeNumbers(
                LevelGenerator.generateHamiltonianPath(gridSize, gridSize, random), numbers, random
            )
            GameState(rows = gridSize, cols = gridSize, numberPositions = positions, totalNumbers = numbers, mode = mode)
        }
    }
}

private fun formatTime(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
}

@Composable
fun GarisKuGame(modifier: Modifier = Modifier) {
    var mode by remember { mutableStateOf(GameMode.SIMPLE) }
    var levelNumber by remember { mutableStateOf(1) }

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

    // Timer tick
    LaunchedEffect(gameState.timerStarted) {
        while (gameState.timerStarted && !gameState.isComplete) {
            delay(1000)
            gameState.tickSecond()
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

        // ── Mode selector ────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ModeButton("Simple", mode == GameMode.SIMPLE) {
                mode = GameMode.SIMPLE; levelNumber = 1
            }
            ModeButton("Challenge", mode == GameMode.CHALLENGE) {
                mode = GameMode.CHALLENGE; levelNumber = 1
            }
        }

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
                "Penuhi semua sel + hubungkan berurutan"
            else "Hubungkan nomor berurutan",
            color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── Timer ────────────────────────────────────────
        if (gameState.isComplete) {
            Text("✨ Selesai! ✨", color = TargetRing, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
        }
        Text("⏱ ${formatTime(gameState.elapsedSeconds)}${
            if (mode == GameMode.CHALLENGE) "  |  ${gameState.path.size}/${gameState.totalCells} sel" else ""
        }",
            color = Color.White.copy(alpha = if (gameState.timerStarted) 1f else 0.5f),
            fontSize = if (gameState.isComplete) 24.sp else 16.sp)

        Spacer(modifier = Modifier.height(16.dp))

        // ── Grid ─────────────────────────────────────────
        GameGrid(gameState = gameState, errorAnim = errorAnim,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f))

        Spacer(modifier = Modifier.height(20.dp))

        // ── Reset / Level Baru ──────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { gameState.reset() },
                colors = ButtonDefaults.buttonColors(containerColor = CellEmpty)
            ) { Text("🔄 Ulang", color = Color.White, fontSize = 15.sp) }
            Button(
                onClick = { levelNumber++ },
                colors = ButtonDefaults.buttonColors(containerColor = CellNumbered)
            ) { Text("🎲 Level Baru", color = Color.White, fontSize = 15.sp) }
        }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) TargetRing else CellEmpty,
            contentColor = Color.White
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
                        do { } while (awaitPointerEvent().changes.any { it.pressed.not() })
                        return@awaitEachGesture
                    }

                    isDragging = true
                    dragPos = down.position
                    lastDragCell = Position(row, col)

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
                                val gPath = gameState.path
                                // ── UNDO: drag backward to previous cell ──
                                if (gPath.size >= 2 && newCell == gPath[gPath.size - 2]) {
                                    gameState.undo()
                                } else {
                                    gameState.tryConnect(frow, fcol)
                                }
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
                val cx = c * cellSizePx
                val cy = r * cellSizePx

                val cellColor = when {
                    cell.isVisited -> CellVisited
                    cell.number != null -> CellNumbered
                    else -> CellEmpty
                }

                val shakeX = if (gameState.hasError && cell.number == gameState.nextNumber)
                    errorAnim * 8f else 0f

                drawRoundRect(cellColor, Offset(cx + pad + shakeX, cy + pad),
                    Size(cellSizePx - pad * 2, cellSizePx - pad * 2), CornerRadius(12f))

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