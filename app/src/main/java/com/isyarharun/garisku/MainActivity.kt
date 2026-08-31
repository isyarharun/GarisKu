package com.isyarharun.garisku

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

private enum class Screen { MENU, LEVEL_SELECT, GAME }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LevelProgress.init(this)
        LevelRepository.init(this)
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = BackgroundDark
            ) {
                GarisKuApp()
            }
        }
    }
}

@Composable
private fun GarisKuApp() {
    var screen by remember { mutableStateOf(Screen.MENU) }
    var selectedMode by remember { mutableStateOf(GameMode.SIMPLE) }
    var selectedLevel by remember { mutableStateOf(1) }

    // Back gesture from the game returns to the level grid.
    BackHandler(enabled = screen == Screen.GAME) {
        screen = Screen.LEVEL_SELECT
    }

    when (screen) {
        Screen.MENU -> MainMenuScreen(
            onPlay = { mode, level ->
                selectedMode = mode
                selectedLevel = level
                screen = Screen.GAME
            }
        )
        Screen.LEVEL_SELECT -> LevelSelectScreen(
            mode = selectedMode,
            onBack = { screen = Screen.MENU },
            onPlay = { level ->
                selectedLevel = level
                screen = Screen.GAME
            }
        )
        Screen.GAME -> GarisKuGame(
            mode = selectedMode,
            levelNumber = selectedLevel,
            onExit = { screen = Screen.LEVEL_SELECT },
            onNextLevel = { selectedLevel++ }
        )
    }
}