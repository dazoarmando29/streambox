package com.streambox.nativeapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streambox.nativeapp.AppEnv
import com.streambox.nativeapp.api.Title

/* Design tokens live in Theme.kt (Obsidian Glow Cinematic). */
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
    data class Player(
        val title: Title,
        val season: Int,
        val episode: Int,
        val mal: Int?,
        val ani: Int?,
        val lang: String? = null,
    ) : Screen()
}

private val StreamColors = darkColorScheme(
    primary = Accent,
    onPrimary = Ink,
    background = Ink,
    onBackground = Soft,
    surface = Card,
    onSurface = Soft,
)

@Composable
fun StreamBoxApp() {
    var section by remember { mutableStateOf(Section.HOME) }
    var screen by remember { mutableStateOf<Screen>(Screen.Browse(Section.HOME)) }

    fun play(t: Title, season: Int, episode: Int, mal: Int?, ani: Int?, lang: String? = null) {
        screen = Screen.Player(t, season, episode, mal, ani, lang)
    }

    // Space Grotesk is the house voice (titles, labels, numerics): every
    // Text inherits it; long-form copy opts into BodyFont at the call site.
    MaterialTheme(colorScheme = StreamColors) {
        CompositionLocalProvider(LocalTextStyle provides TextStyle(fontFamily = DisplayFont)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
            when (val s = screen) {
                is Screen.Browse -> Column(modifier = Modifier.fillMaxSize()) {
                    BrowseScreen(
                        section = section,
                        onSection = {
                            section = it
                            screen = Screen.Browse(it)
                        },
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
                    // TV uses the website-style top tab bar instead - the
                    // bottom nav is a phone pattern (design split rule).
                    if (!AppEnv.isTv) {
                        BottomNav(current = section) {
                            section = it
                            screen = Screen.Browse(it)
                        }
                    }
                }

                is Screen.Detail -> DetailScreen(
                    title = s.title,
                    onBack = { screen = Screen.Browse(section) },
                    onPlay = { season, episode, mal, ani, lang -> play(s.title, season, episode, mal, ani, lang) },
                    onOpen = { screen = Screen.Detail(it) },
                )

                is Screen.Player -> PlayerScreen(
                    title = s.title,
                    season = s.season,
                    episode = s.episode,
                    mal = s.mal,
                    ani = s.ani,
                    lang = s.lang,
                    onHome = { screen = Screen.Browse(section) },
                )
            }
        }
        }
    }
}

/** Phone navigation: a frosted glass dock with a glowing teal pill on the
    active tab (Obsidian Glow); TV gets the website's top tab bar. */
@Composable
private fun BottomNav(current: Section, onPick: (Section) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ink)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Card, RoundedCornerShape(26.dp))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Section.values().forEach { s ->
                val on = s == current
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .focusRing(RoundedCornerShape(18.dp), pop = false)
                        .background(
                            if (on) Color(0x3300F0B5) else Color.Transparent,
                            RoundedCornerShape(18.dp),
                        )
                        .clickable { onPick(s) }
                        .padding(vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = s.icon,
                        contentDescription = s.label,
                        tint = if (on) Accent else Dim,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        s.label,
                        fontSize = 10.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        color = if (on) Accent else Dim,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Pill button in the website's style. */
@Composable
fun PillButton(
    label: String,
    big: Boolean,
    accent: Boolean = false,
    outline: Boolean = false,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val h = if (big) 54.dp else 46.dp
    // Solid pills everywhere (website style): "outline" keeps its name but
    // renders as the soft gray fill - drawn borders are gone for good.
    // The accent pill blooms (Obsidian Glow primary CTA).
    val bg = when {
        accent -> Accent
        outline -> Color(0xFF243247)
        else -> Pill
    }
    val fg = if (accent) Ink else Color.White
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .then(if (accent) Modifier.glow(RoundedCornerShape(50)) else Modifier)
            .height(h)
            .focusRing(RoundedCornerShape(50))
            .background(bg, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = if (big) 22.dp else 18.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(if (big) 22.dp else 18.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(label, color = fg, fontWeight = FontWeight.Bold, fontSize = if (big) 17.sp else 14.sp)
    }
}
