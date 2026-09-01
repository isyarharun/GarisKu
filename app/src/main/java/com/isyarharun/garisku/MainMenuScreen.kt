package com.isyarharun.garisku

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Number of selectable levels per mode. */
const val LEVELS_PER_MODE = 50

/**
 * Main menu: lets the player pick a mode, then navigates to the level grid.
 */
@Composable
fun MainMenuScreen(
    onPlay: (mode: GameMode, level: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    ModeSelectScreen(
        onSelect = { mode -> onPlay(mode, LevelProgress.getMaxUnlocked(mode)) },
        modifier = modifier
    )
}

@Composable
private fun ModeSelectScreen(
    onSelect: (GameMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Music toggle — top-right of menu
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            MusicToggleButton()
        }

        Text("GarisKu", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Pilih mode permainan",
            color = OnSurfaceVariantDark, fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(40.dp))

        ModeCard(
            title = "Simple",
            subtitle = "Hubungkan nomor berurutan",
            emoji = "🔢",
            onClick = { onSelect(GameMode.SIMPLE) }
        )
        Spacer(modifier = Modifier.height(16.dp))
        ModeCard(
            title = "Challenge",
            subtitle = "Lewati semua sel, hindari bata 🧱",
            emoji = "🧩",
            onClick = { onSelect(GameMode.CHALLENGE) }
        )

        Spacer(modifier = Modifier.height(32.dp))
        Text(
            "Music: Wallpaper — Kevin MacLeod (incompetech.com), CC-BY 4.0",
            color = OnSurfaceVariantDark.copy(alpha = 0.6f), fontSize = 10.sp
        )
    }
}

@Composable
private fun MusicToggleButton() {
    var enabled by remember { mutableStateOf(MusicManager.isEnabled()) }
    Button(
        onClick = { enabled = MusicManager.toggle() },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (enabled) GreenPrimaryDark else SurfaceVariantDark
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            if (enabled) "🔊" else "🔇",
            fontSize = 16.sp
        )
    }
}

@Composable
private fun ModeCard(
    title: String,
    subtitle: String,
    emoji: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceDark)
            .border(1.dp, GreenPrimary.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(emoji, fontSize = 36.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(subtitle, color = OnSurfaceVariantDark, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

/**
 * Level selection grid for a mode. Locked levels are greyed out;
 * completed levels show a check.
 */
@Composable
fun LevelSelectScreen(
    mode: GameMode,
    onBack: () -> Unit,
    onPlay: (level: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val maxUnlocked = LevelProgress.getMaxUnlocked(mode)
    val levels = remember { (1..LEVELS_PER_MODE).toList() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Header with back button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariantDark)
            ) { Text("←", color = Color.White, fontSize = 18.sp) }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    if (mode == GameMode.SIMPLE) "Simple" else "Challenge",
                    color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    "Level terbuka: $maxUnlocked",
                    color = OnSurfaceVariantDark, fontSize = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(levels) { level ->
                LevelCell(
                    level = level,
                    unlocked = LevelProgress.isUnlocked(mode, level),
                    completed = LevelProgress.isCompleted(mode, level),
                    onClick = { onPlay(level) }
                )
            }
        }
    }
}

@Composable
private fun LevelCell(
    level: Int,
    unlocked: Boolean,
    completed: Boolean,
    onClick: () -> Unit
) {
    val bg = when {
        !unlocked -> SurfaceVariantDark.copy(alpha = 0.4f)
        completed -> GreenPrimaryDark
        else -> SurfaceDark
    }
    val textColor = when {
        !unlocked -> OnSurfaceVariantDark.copy(alpha = 0.4f)
        completed -> Color.White
        else -> Color.White
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .then(
                if (unlocked) Modifier.clickable(onClick = onClick)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (completed) "✓" else level.toString(),
            color = textColor,
            fontSize = if (completed) 20.sp else 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}