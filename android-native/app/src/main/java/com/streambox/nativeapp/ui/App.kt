package com.streambox.nativeapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.focusable
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.streambox.nativeapp.AppEnv
import com.streambox.nativeapp.BuildConfig
import com.streambox.nativeapp.api.Title
import com.streambox.nativeapp.data.Store
import kotlinx.coroutines.delay

/* Design tokens live in Theme.kt (Obsidian Glow Cinematic). */
enum class Section(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    MOVIES("Movies", Icons.Filled.Movie),
    TV("TV", Icons.Filled.LiveTv),
    ANIME("Anime", Icons.Filled.AutoAwesome),
    // Korean dramas get their own tab beside Anime (0.57): the old
    // TV Shows K-Drama strip moved here.
    KDRAMA("KDrama", Icons.Filled.Star),
    MYLIST("My List", Icons.Filled.Favorite),
    // Browse everything (0.55): pick movie / TV / anime, then genre + year
    // chips over the FULL paginated catalog.
    BROWSE("Browse", Icons.Filled.Explore),
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

/** No-op indication: the default ripple/hover state layer draws its bounded
    region as a SQUARE plate behind round buttons on TV (0.34 bug report).
    Every target here carries its own ring/glow as feedback, so content
    draws untouched and nothing else paints. */
private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: androidx.compose.foundation.interaction.InteractionSource): DelegatableNode =
        NoIndicationNode()

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 0
}

private class NoIndicationNode : Modifier.Node(), DrawModifierNode {
    override fun ContentDrawScope.draw() {
        drawContent()
    }
}

@Composable
fun StreamBoxApp() {
    var section by remember { mutableStateOf(Section.HOME) }
    var screen by remember { mutableStateOf<Screen>(Screen.Browse(Section.HOME)) }
    // Search state lives HERE (0.50): BrowseScreen's own remember died with
    // its composition when a detail opened, so Back from a search result
    // dumped you on the home feed instead of back into your search. A tab
    // switch and the player's Netflix-style exit to home clear it on
    // purpose - only Detail-back keeps it.
    var searchMode by remember { mutableStateOf(false) }
    var searchInput by remember { mutableStateOf("") }
    var searchSubmitted by remember { mutableStateOf("") }
    fun clearSearch() {
        searchMode = false
        searchInput = ""
        searchSubmitted = ""
    }

    fun play(t: Title, season: Int, episode: Int, mal: Int?, ani: Int?, lang: String? = null) {
        screen = Screen.Player(t, season, episode, mal, ani, lang)
    }

    // Boot splash + What's New (0.51): a cinematic start over the warm-up,
    // then a ONE-TIME card after every fresh update (first opening only -
    // lastSeenVersion remembers what you have already seen).
    val appCtx = LocalContext.current
    var splashDone by remember { mutableStateOf(false) }
    var whatsNew by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    LaunchedEffect(Unit) {
        // 0.70 perf: the cinematic beat is 700ms now - the old 1200ms was
        // pure waiting on a weak TV box (the splash overlay animates the
        // whole time). The catalog still warms behind it from t=0.
        delay(700)
        splashDone = true
        val v = BuildConfig.VERSION_NAME
        val prev = Store.lastSeenVersion(appCtx)
        if (prev != v) {
            whatsNew = v to whatsNewBullets(prev, v)
            Store.setLastSeenVersion(appCtx, v)
        }
    }
    // 0.72 nav perf: warm every tab's feed behind the home screen so the
    // nav bar never opens a section cold (that first-visit fetch behind a
    // spinner is the laggish feel on slow TV Wi-Fi). Pauses while the
    // player owns the screen - playback keeps the whole line.
    LaunchedEffect(Unit) {
        delay(2500)
        warmFeedCaches(appCtx) { screen is Screen.Player }
    }

    // Space Grotesk is the house voice (titles, labels, numerics): every
    // Text inherits it; long-form copy opts into BodyFont at the call site.
    MaterialTheme(colorScheme = StreamColors) {
        CompositionLocalProvider(
            LocalTextStyle provides TextStyle(fontFamily = DisplayFont),
            // The default ripple/hover indication draws its bounded state
            // layer as a SQUARE plate behind round buttons on TV (0.34 bug
            // report). Every target here carries its own ring/glow, so the
            // default indication is switched off app-wide.
            LocalIndication provides NoIndication,
        ) {
        // The splash is an OVERLAY over the live tree (0.52 speed pass):
        // the catalog starts fetching at t=0 behind it, so the boot fade
        // reveals a warm home instead of an empty one.
        Box(modifier = Modifier.fillMaxSize()) {
            Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
                when (val s = screen) {
                    is Screen.Browse -> Column(modifier = Modifier.fillMaxSize()) {
                        BrowseScreen(
                            section = section,
                            onSection = {
                                section = it
                                // A fresh tab never inherits the search bar.
                                clearSearch()
                                screen = Screen.Browse(it)
                            },
                            onOpen = { screen = Screen.Detail(it) },
                            onPlay = { t ->
                                if (t.type == "movie") play(t, 1, 1, null, null)
                                else screen = Screen.Detail(t)
                            },
                            onResume = { p ->
                                play(
                                    Title(p.id, p.type, p.name, p.poster, null, 0, "", 0.0, lang = p.lang),
                                    p.season, p.episode, p.mal, p.ani,
                                )
                            },
                            searchMode = searchMode,
                            searchInput = searchInput,
                            searchSubmitted = searchSubmitted,
                            onSearchToggle = {
                                searchMode = !searchMode
                                searchInput = ""
                                searchSubmitted = ""
                            },
                            onSearchInput = { searchInput = it },
                            onSearchSubmit = { searchSubmitted = it },
                            modifier = Modifier.weight(1f),
                        )
                        // TV uses the website-style top tab bar instead -
                        // the bottom nav is a phone pattern (design split).
                        if (!AppEnv.isTv) {
                            BottomNav(current = section) {
                                section = it
                                screen = Screen.Browse(it)
                            }
                        }
                    }

                    // key() per title / play session (0.48): un-keyed
                    // remember kept the PREVIOUS title's state - a new key
                    // = fresh composition.
                    is Screen.Detail -> key(s.title.id, s.title.type) {
                        DetailScreen(
                            title = s.title,
                            onBack = { screen = Screen.Browse(section) },
                            onPlay = { season, episode, mal, ani, lang -> play(s.title, season, episode, mal, ani, lang) },
                            onOpen = { screen = Screen.Detail(it) },
                        )
                    }

                    is Screen.Player -> key(s.title.id, s.title.type, s.season, s.episode, s.lang ?: "") {
                        PlayerScreen(
                            title = s.title,
                            season = s.season,
                            episode = s.episode,
                            mal = s.mal,
                            ani = s.ani,
                            lang = s.lang,
                            // Back from the player goes home (Netflix rule)
                            // - never back into the launching search.
                            onHome = {
                                clearSearch()
                                screen = Screen.Browse(section)
                            },
                            // Thank You screen (0.73): More Like This opens
                            // the title's detail page.
                            onOpen = {
                                clearSearch()
                                screen = Screen.Detail(it)
                            },
                        )
                    }
                }
            }
            AnimatedVisibility(visible = !splashDone, exit = fadeOut(tween(420))) {
                SplashScreen()
            }
            whatsNew?.let { (ver, bullets) ->
                WhatsNewDialog(ver, bullets) { whatsNew = null }
            }
        }
        }
    }
}

/** What's New copy, newest first - add a key per release. The card lists
    every entry between the version you last saw and the current one. */
private val WHATS_NEW: Map<String, List<String>> = linkedMapOf(
    "0.75" to listOf(
        "Slow Wi-Fi handled automatically - the player detects a thin connection, plays a lighter quality (and the smoother MP4 files when available) and never stops and starts; it says so with one honest note and remembers nothing after you leave",
        "Crisp picture is always on - a real sharpening + vividness pass on every server for a cleaner, sharper look",
        "Next episodes warm up in the background so auto-next starts instantly",
    ),
    "0.74" to listOf(
        "Fixed the freeze where the video stuttered, went silent and stopped moving - the player now detects the stall and rebuilds itself automatically (your volume is kept)",
        "Long sessions stay smoother on weak TV boxes - background overhead is trimmed and memory pressure is handled before playback feels it",
    ),
    "0.73" to listOf(
        "Skip Intro / Skip Recap / Skip Outro land on the REAL timestamps now, and the timeline highlights exactly where they are",
        "Skip Outro works - it skips the ending song automatically (and the Next Episode chain waits until the episode truly ends)",
        "Rewinding no longer freezes or mutes the video - seeks heal themselves and your volume is protected",
        "New Browse genre: Another World (isekai / reincarnated / another-world stories)",
        "Episode rows show SEASON FINALE and the next season, and a Thank You screen with More Like This closes the season",
        "Continue Watching drops finished episodes and comes back with the NEW episode the moment it airs",
    ),
    "0.72" to listOf(
        "Smoother playback - the player bar no longer repaints over the video every beat",
        "Snappier navigation - tabs are warmed up in the background so none of them opens cold",
    ),
    "0.71" to listOf(
        "Tagalog is the K-Drama priority again - the app hunts every VidBolt server for the dub and switches to it automatically",
        "When no Tagalog dub exists right now, the app says so and keeps Korean (Filipino subtitles stay available)",
    ),
    "0.70" to listOf(
        "The Tagalog dub is back on every episode - audio tracks can no longer vanish",
        "Snappier on weak TV boxes and faster video starts on slow Wi-Fi",
    ),
    "0.69" to listOf(
        "The real audio tracks ALWAYS appear now - the Tagalog dub can no longer vanish",
    ),
    "0.68" to listOf(
        "Continue Watching remembers your K-Dramas - resuming Lie to Me plays the Tagalog dub again",
    ),
    "0.67" to listOf(
        "Auto-play Next now works on EVERY TV show, anime and K-Drama - episodes chain and never sit stuck at the end",
        "Auto-play Next is ON by default (still switchable in Episodes)",
    ),
    "0.66" to listOf(
        "Auto-play Next is ours now - the next episode always opens through our own route, even on VidBolt Up Next",
    ),
    "0.65" to listOf(
        "Next Episode always plays the NEXT episode - on every anime, TV show and K-Drama, on every server",
        "Provider Up Next events can no longer jump your episode to a different video",
    ),
    "0.64" to listOf(
        "K-Dramas play the Tagalog dub by default (Korean when there is none) with VidBolt's own English subtitles",
        "Browse is redesigned - every poster and name readable on one clean scroll",
        "Left now reaches the menu from anywhere in the rows and grids",
    ),
    "0.63" to listOf(
        "Episode labels truly line up now - one right edge for every row",
    ),
    "0.62" to listOf(
        "Episode labels line up in one straight column - no more scattered 24m",
        "Coming Soon keeps its stacked date + PH countdown",
    ),
    "0.61" to listOf(
        "Episode status is one clean line on the right - pinned, never moving",
    ),
    "0.60" to listOf(
        "Anime seasons play RIGHT - season 2 starts at season 2 (season-aware entry routing)",
        "Next Episode never steps past a season's end or into an unaired episode",
    ),
    "0.59" to listOf(
        "Episode status sits in one fixed spot on every row - no more drifting labels",
    ),
    "0.58" to listOf(
        "The zigzag is really gone this time - smooth rows everywhere",
        "Browse is back on the rail (every tab fits now) and KDrama shows a single All chip",
    ),
    "0.57" to listOf(
        "New KDrama tab beside Anime - Korean dramas out of the TV Shows strip",
        "Coming Soon episodes show their PH air date with a live countdown",
        "Consistent episode status zone + smooth rows everywhere (the zigzag is gone)",
    ),
    "0.56" to listOf(
        "Browse tab grows K-Dramas - Korean dramas by genre and year, straight to VidBolt",
    ),
    "0.55" to listOf(
        "New Browse tab: pick Movies / TV Shows / Anime, then genre and year chips over the full catalog",
        "Anime never reads as TV anywhere again - labels follow the content",
    ),
    "0.54" to listOf(
        "VidBolt subtitles actually show when you pick them",
        "Skip pills: plain Skip Intro / Next Episode names, no false Next Ep over the intro",
        "Server auto-switch leads with VidBolt - no more VidRift hops",
    ),
    "0.53" to listOf(
        "Smooth TV row navigation - the zigzag and shake are gone",
    ),
    "0.52" to listOf(
        "Everything is faster: warm boot, instant browses, quicker players and server hops",
        "Motion clarity: judder-free playback matched to your display's refresh",
        "Hero meta chips in the design-pack style",
    ),
    "0.51" to listOf(
        "Updates install right over older versions - no uninstall needed",
        "Roll back to any older APK whenever you want",
        "New cinematic boot splash",
        "This What's New card opens once per fresh update",
    ),
    "0.50" to listOf(
        "Continue Watching follows your tab (Home shows everything)",
        "Back from a title's details returns to your search",
        "VidBolt quality picker in Audio & Subtitles",
    ),
)

private fun whatsNewBullets(prev: String, current: String): List<String> {
    fun num(v: String) = v.substringAfterLast('.').toIntOrNull() ?: 0
    val p = num(prev)
    val cur = num(current)
    val out = ArrayList<String>()
    for ((ver, items) in WHATS_NEW) {
        val n = num(ver)
        if (n in (p + 1)..cur) out.addAll(items)
    }
    if (out.isEmpty()) out.addAll(WHATS_NEW[current] ?: emptyList())
    return out.take(8)
}

/** Cinematic boot (Obsidian Glow): ink canvas, soft glow blobs, the logo
    plate breathing over its bloom, wordmark and a sweep bar. */
@Composable
private fun SplashScreen() {
    val big = AppEnv.isTv
    val loop = rememberInfiniteTransition(label = "boot")
    val pulse by loop.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bootPulse",
    )
    val fade by animateFloatAsState(targetValue = 1f, animationSpec = tween(650), label = "bootFade")
    val sweep = (pulse - 0.92f) / 0.16f
    // Boot guard: the splash OWNS the remote while it is up - presses can
    // never land on the content warming up underneath it.
    val splashFR = remember { FocusRequester() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .focusRequester(splashFR)
            .focusable()
            .onKeyEvent { true },
    ) {
        LaunchedEffect(Unit) { runCatching { splashFR.requestFocus() } }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF0D1422), Color(0xFF0B111E), Ink))),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (big) 60.dp else 30.dp)
                .size(if (big) 420.dp else 280.dp)
                .background(Brush.radialGradient(listOf(Accent.copy(alpha = 0.16f), Color.Transparent)), CircleShape),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center).alpha(fade),
        ) {
            Box(
                modifier = Modifier
                    .size(if (big) 100.dp else 82.dp)
                    .scale(pulse)
                    .glow(RoundedCornerShape(28.dp))
                    .background(Accent, RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Ink,
                    modifier = Modifier.size(if (big) 56.dp else 46.dp),
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Row {
                Text("Stream", color = Color.White, fontWeight = FontWeight.Black, fontSize = if (big) 42.sp else 36.sp)
                Text("Box", color = Accent, fontWeight = FontWeight.Black, fontSize = if (big) 42.sp else 36.sp)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "MOVIES  \u00b7  TV  \u00b7  ANIME",
                color = Dim,
                fontWeight = FontWeight.Bold,
                fontSize = if (big) 13.sp else 11.sp,
                letterSpacing = 3.sp,
            )
            Spacer(modifier = Modifier.height(30.dp))
            Box(
                modifier = Modifier
                    .width(170.dp)
                    .height(3.dp)
                    .background(Color(0x22FFFFFF), RoundedCornerShape(2.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = (0.18f + 0.82f * sweep).coerceIn(0.18f, 1f))
                        .height(3.dp)
                        .background(Accent, RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

/** One-time What's New card after a fresh update (design pack glass). */
@Composable
private fun WhatsNewDialog(version: String, bullets: List<String>, onDone: () -> Unit) {
    val big = AppEnv.isTv
    Dialog(onDismissRequest = onDone) {
        Column(
            modifier = Modifier
                .widthIn(max = if (big) 640.dp else 420.dp)
                .background(Card, RoundedCornerShape(24.dp))
                .padding(if (big) 28.dp else 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .glow(RoundedCornerShape(12.dp))
                        .background(Accent, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Ink, modifier = Modifier.size(22.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("What's New", color = Color.White, fontWeight = FontWeight.Black, fontSize = if (big) 26.sp else 21.sp)
                    Text(
                        "VERSION " + version,
                        color = Dim,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (big) 12.sp else 10.sp,
                        letterSpacing = 2.sp,
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            bullets.forEach { b ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(modifier = Modifier.padding(top = 6.dp).size(7.dp).background(Accent, CircleShape))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(b, color = Soft, fontFamily = BodyFont, fontSize = if (big) 15.sp else 13.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Spacer(modifier = Modifier.height(10.dp))
            PillButton(
                "Start Watching", big, accent = true, icon = Icons.Filled.PlayArrow,
                modifier = Modifier.fillMaxWidth(),
            ) { onDone() }
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

/** Pill button in the website's style. onFocusChange lets a container bring
    its WHOLE block into view when a pill takes focus (0.49: bringIntoView
    otherwise scrolls only the focused pill and the hero title stayed cut). */
@Composable
fun PillButton(
    label: String,
    big: Boolean,
    accent: Boolean = false,
    outline: Boolean = false,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit = {},
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
            .onFocusChanged { onFocusChange(it.isFocused) }
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
