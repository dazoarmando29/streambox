package com.streambox.nativeapp.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.streambox.nativeapp.api.Title

sealed class Screen {
    data object Home : Screen()
    data class Detail(val title: Title) : Screen()
    data class Player(val title: Title, val season: Int, val episode: Int, val mal: Int?, val ani: Int?) : Screen()
}

private val StreamColors = darkColorScheme(
    primary = Color(0xFF14B8A6),
    onPrimary = Color(0xFF0B1426),
    background = Color(0xFF0B1426),
    onBackground = Color(0xFFE8EEF7),
    surface = Color(0xFF132138),
    onSurface = Color(0xFFE8EEF7),
)

@Composable
fun StreamBoxApp() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    MaterialTheme(colorScheme = StreamColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val s = screen) {
                is Screen.Home -> HomeScreen(onOpen = { screen = Screen.Detail(it) })
                is Screen.Detail -> DetailScreen(
                    title = s.title,
                    onBack = { screen = Screen.Home },
                    onPlay = { season, episode, mal, ani ->
                        screen = Screen.Player(s.title, season, episode, mal, ani)
                    },
                )
                is Screen.Player -> PlayerScreen(
                    title = s.title,
                    season = s.season,
                    episode = s.episode,
                    mal = s.mal,
                    ani = s.ani,
                    onHome = { screen = Screen.Home },
                )
            }
        }
    }
}

/** D-pad comfort: a bright teal ring on whatever the remote is on.
    Every clickable/stepper stacks this so TV focus is never invisible. */
fun Modifier.focusRing(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.isFocused }
        .border(
            width = if (focused) 3.dp else 0.dp,
            color = Color(0xFF14B8A6),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        )
        .scale(if (focused) 1.04f else 1f)
}
