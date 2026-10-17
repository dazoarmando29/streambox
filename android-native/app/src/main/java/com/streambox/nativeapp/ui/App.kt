package com.streambox.nativeapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streambox.nativeapp.AppEnv
import com.streambox.nativeapp.api.Title

/* Design tokens - same palette as the website. */
val Accent = Color(0xFF14B8A6)
val Ink = Color(0xFF0B1426)
val Card = Color(0xFF132138)
val Pill = Color(0xFF1D2C44)
val Dim = Color(0xFF8FA3BF)
val Soft = Color(0xFFC6D3E5)

enum class Section(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    MOVIES("Movies", Icons.Filled.Movie),
    TV("TV", Icons.Filled.LiveTv),
    ANIME("Anime", Icons.Filled.AutoAwesome),
    MYLIST("My List", Icons.Filled.Favorite),
}

sealed class Screen {
    data class Browse(val section: Section) : Screen()
    data class Detail(val title: Title) : Screen()
    data class Player(val title: Title, val season: Int, val episode: Int, val mal: Int?, val ani: Int?) : Screen()
}

private val StreamColors = darkColorScheme(
    primary = Accent,
    onPrimary = Ink,
    background = Ink,
    onBackground = Color(0xFFE8EEF7),
    surface = Card,
    onSurface = Color(0xFFE8EEF7),
)

@Composable
fun StreamBoxApp() {
    var section by remember { mutableStateOf(Section.HOME) }
    var screen by remember { mutableStateOf<Screen>(Screen.Browse(Section.HOME)) }

    fun play(t: Title, season: Int, episode: Int, mal: Int?, ani: Int?) {
        screen = Screen.Player(t, season, episode, mal, ani)
    }

    MaterialTheme(colorScheme = StreamColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
            when (val s = screen) {
                is Screen.Browse -> Column(modifier = Modifier.fillMaxSize()) {
                    BrowseScreen(
                        section = section,
                        onOpen = { screen = Screen.Detail(it) },
                        onPlay = { t ->
                            if (t.type == "movie") play(t, 1, 1, null, null)
                            else screen = Screen.Detail(t)
                        },
                        onResume = { p ->
                            play(
                                Title(p.id, p.type, p.name, p.poster, null, 0, "", 0.0),
                                p.season, p.episode, p.mal, p.ani,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                    BottomNav(current = section) {
                        section = it
                        screen = Screen.Browse(it)
                    }
                }

                is Screen.Detail -> DetailScreen(
                    title = s.title,
                    onBack = { screen = Screen.Browse(section) },
                    onPlay = { season, episode, mal, ani -> play(s.title, season, episode, mal, ani) },
                )

                is Screen.Player -> PlayerScreen(
                    title = s.title,
                    season = s.season,
                    episode = s.episode,
                    mal = s.mal,
                    ani = s.ani,
                    onHome = { screen = Screen.Browse(section) },
                )
            }
        }
    }
}

@Composable
private fun BottomNav(current: Section, onPick: (Section) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0E1B2E))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Section.values().forEach { s ->
            val on = s == current
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(64.dp)
                    .focusRing()
                    .clickable { onPick(s) }
                    .padding(vertical = 6.dp),
            ) {
                Icon(
                    imageVector = s.icon,
                    contentDescription = s.label,
                    tint = if (on) Accent else Dim,
                    modifier = Modifier.size(if (AppEnv.isTv) 26.dp else 22.dp),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    s.label,
                    fontSize = if (AppEnv.isTv) 12.sp else 10.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) Accent else Dim,
                    maxLines = 1,
                )
            }
        }
    }
}

/** D-pad comfort: a bright teal ring on whatever the remote is on. */
fun Modifier.focusRing(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.isFocused }
        .border(
            width = if (focused) 3.dp else 0.dp,
            color = Accent,
            shape = RoundedCornerShape(14.dp),
        )
        .scale(if (focused) 1.05f else 1f)
}

/** Pill button in the website's style. */
@Composable
fun PillButton(
    label: String,
    big: Boolean,
    accent: Boolean = false,
    outline: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val h = if (big) 54.dp else 46.dp
    val bg = when {
        accent -> Accent
        outline -> Color.Transparent
        else -> Pill
    }
    val fg = if (accent) Ink else Color.White
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .height(h)
            .focusRing()
            .background(bg, RoundedCornerShape(50))
            .then(
                if (outline) Modifier.border(2.dp, Color(0xFF2A3B55), RoundedCornerShape(50))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = if (big) 22.dp else 18.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(if (big) 22.dp else 18.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(label, color = fg, fontWeight = FontWeight.Black, fontSize = if (big) 17.sp else 14.sp)
    }
}
