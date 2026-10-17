package com.streambox.nativeapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
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

/** Phone navigation: a floating rounded pill bar with an accent chip on the
    active tab (modern mobile pattern; TV gets the website's top tab bar). */
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
                .background(Color(0xFF16243C), RoundedCornerShape(26.dp))
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
                            if (on) Color(0x2E14B8A6) else Color.Transparent,
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

/** D-pad comfort: a teal ring + a gentle pop on whatever the remote is on.
    Both ANIMATE (the old 0.dp border was a Skia hairline AND the scale
    snapped instantly - both felt broken on TV). The ring is stroked along
    the node's own shape so it hugs pills, circles and posters exactly. */
fun Modifier.focusRing(
    shape: Shape = RoundedCornerShape(16.dp),
    pop: Boolean = true,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "focusRing",
    )
    val scale by animateFloatAsState(
        targetValue = if (focused && pop) 1.06f else 1f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "focusPop",
    )
    onFocusChanged { focused = it.isFocused }
        .scale(scale)
        .drawWithContent {
            drawContent()
            if (ring > 0f) {
                val stroke = Stroke(width = 3.dp.toPx())
                val c = Accent.copy(alpha = ring)
                when (val o = shape.createOutline(size, layoutDirection, this)) {
                    is Outline.Rectangle -> drawRect(color = c, style = stroke)
                    is Outline.Rounded -> drawRoundRect(
                        color = c,
                        topLeft = Offset(o.roundRect.left, o.roundRect.top),
                        size = Size(o.roundRect.width, o.roundRect.height),
                        cornerRadius = o.roundRect.topLeftCornerRadius,
                        style = stroke,
                    )
                    is Outline.Generic -> drawPath(o.path, color = c, style = stroke)
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
    val bg = when {
        accent -> Accent
        outline -> Color(0xFF24344E)
        else -> Pill
    }
    val fg = if (accent) Ink else Color.White
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
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
        Text(label, color = fg, fontWeight = FontWeight.Black, fontSize = if (big) 17.sp else 14.sp)
    }
}
