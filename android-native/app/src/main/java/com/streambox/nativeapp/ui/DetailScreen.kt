package com.streambox.nativeapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.streambox.nativeapp.AppEnv
import com.streambox.nativeapp.api.Api
import com.streambox.nativeapp.api.AnimeIds
import com.streambox.nativeapp.api.CastMem
import com.streambox.nativeapp.api.Ep
import com.streambox.nativeapp.api.Title
import com.streambox.nativeapp.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Title page in the Obsidian Glow language (design pack: hope_movie /
    bleach_anime / visionquest_tv details): cinematic hero with layered
    washes and a teal under-glow, badge pills over the key art, display
    title + meta strip, synopsis with Read more, one huge glowing Play CTA,
    a micro action bar, and - for series/anime - the S/E HUD island plus a
    lazy episode rail fed by real TMDB season data. Behavior is unchanged:
    same play route, same mapping guard, same remote Back. */
@Composable
fun DetailScreen(
    title: Title,
    onBack: () -> Unit,
    onPlay: (season: Int, episode: Int, mal: Int?, ani: Int?, lang: String?) -> Unit,
    onOpen: (Title) -> Unit,
) {
    val ctx = LocalContext.current
    val big = AppEnv.isTv

    var season by remember { mutableIntStateOf(1) }
    var episode by remember { mutableIntStateOf(1) }
    var ids by remember { mutableStateOf<AnimeIds?>(null) }
    var mapping by remember { mutableStateOf("none") } // none | ok | missing
    var inList by remember { mutableStateOf(Store.inList(ctx, title.type, title.id)) }
    var expanded by remember { mutableStateOf(false) }
    var episodes by remember { mutableStateOf(listOf<Ep>()) }
    var epsBusy by remember { mutableStateOf(false) }
    var cast by remember { mutableStateOf(listOf<CastMem>()) }
    var similar by remember { mutableStateOf(listOf<Title>()) }
    // AniPM start language (design pack SUB|DUB chips): the house default
    // stands - Dragon Ball starts on the Faulconer English dub, everything
    // else on sub - and the chips only override per play.
    var subDub by remember {
        mutableStateOf(if (title.name.contains("dragon ball", true)) "dub" else "sub")
    }
    // Continue-watching marker for the episode rail (device-local memory).
    val saved = remember(title.id, title.type) {
        Store.progressList(ctx).firstOrNull { it.type == title.type && it.id == title.id }
    }

    LaunchedEffect(title) {
        if (title.type == "anime") {
            mapping = "loading"
            val mapped = withContext(Dispatchers.IO) { Api.animeMap(title.name, title.year) }
            ids = mapped
            mapping = if (mapped != null && (mapped.mal != null || mapped.ani != null)) "ok" else "missing"
        }
    }

    LaunchedEffect(title.id, title.type, season) {
        if (title.type != "movie") {
            epsBusy = true
            episodes = withContext(Dispatchers.IO) {
                runCatching { Api.season(title.id, season) }.getOrDefault(listOf())
            }
            epsBusy = false
        }
    }

    LaunchedEffect(title.id, title.type) {
        val (c, s) = withContext(Dispatchers.IO) {
            runCatching { Api.credits(title.type, title.id) }.getOrDefault(listOf()) to
                runCatching { Api.similar(title.type, title.id) }.getOrDefault(listOf())
        }
        cast = c
        similar = s
    }

    // Remote/gesture Back works everywhere - no hunting for a button on TV.
    BackHandler { onBack() }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // ---- Cinematic hero ----
        item {
            Box(modifier = Modifier.fillMaxWidth().height(if (big) 560.dp else 430.dp)) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(title.backdrop ?: title.poster).crossfade(true).build(),
                    contentDescription = title.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Layered washes: key art melts into the obsidian canvas on
                // every edge (design pack hero recipe).
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color(0x330B111E), Color(0xB30B111E), Ink))),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.horizontalGradient(listOf(Color(0xB30B111E), Color(0x000B111E)))),
                )
                // Floating glass header: back + list (mock top bar).
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(horizontal = if (big) 28.dp else 16.dp, vertical = if (big) 18.dp else 12.dp),
                ) {
                    GlassCircle(if (big) 52.dp else 44.dp, Icons.AutoMirrored.Filled.ArrowBack, "Back") { onBack() }
                    Spacer(modifier = Modifier.weight(1f))
                    GlassCircle(
                        if (big) 52.dp else 44.dp,
                        if (inList) Icons.Filled.Check else Icons.Filled.Add,
                        "My List",
                    ) {
                        Store.toggleList(ctx, title)
                        inList = Store.inList(ctx, title.type, title.id)
                    }
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(
                            horizontal = if (big) 28.dp else 16.dp,
                            vertical = if (big) 22.dp else 14.dp,
                        ),
                ) {
                    // Badge pills: real data only (house rule - never claim
                    // 4K/ratings we were not given).
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(Color(0x2600F0B5), RoundedCornerShape(50))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(Accent, CircleShape),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                title.type.uppercase() + (if (title.year > 0) "  \u2022  " + title.year else ""),
                                color = Accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (big) 12.sp else 10.sp,
                                letterSpacing = 1.sp,
                            )
                        }
                        if (title.vote > 0) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .background(Glass, RoundedCornerShape(50))
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = Accent,
                                    modifier = Modifier.size(if (big) 14.dp else 12.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    String.format("%.1f", title.vote),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = if (big) 12.sp else 10.sp,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        title.name,
                        color = Color.White,
                        fontSize = if (big) 48.sp else 30.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = if (big) 900.dp else 560.dp),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        buildString {
                            if (title.vote > 0) {
                                append("\u2605 ")
                                append(String.format("%.1f", title.vote))
                            }
                            if (title.year > 0) {
                                if (isNotEmpty()) append("   \u2022   ")
                                append(title.year)
                            }
                            if (title.type != "movie") {
                                if (isNotEmpty()) append("   \u2022   ")
                                append("S").append(season).append(" E").append(episode)
                            } else {
                                if (isNotEmpty()) append("   \u2022   ")
                                append("MOVIE")
                            }
                        },
                        color = Dim,
                        fontWeight = FontWeight.Medium,
                        fontSize = if (big) 15.sp else 12.sp,
                    )
                    if (title.overview.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            title.overview,
                            color = Soft,
                            fontFamily = BodyFont,
                            fontSize = if (big) 16.sp else 13.sp,
                            maxLines = if (expanded) 12 else 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = if (big) 860.dp else 560.dp),
                        )
                        Row(
                            modifier = Modifier.clickable { expanded = !expanded }.padding(top = 2.dp),
                        ) {
                            Text(
                                if (expanded) "Show less" else "Read more",
                                color = Accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (big) 14.sp else 12.sp,
                            )
                        }
                    }
                }
            }
        }

        // ---- Primary action block ----
        item {
            Column(modifier = Modifier.padding(horizontal = if (big) 28.dp else 16.dp, vertical = if (big) 16.dp else 12.dp)) {
                // SUB | DUB chips (design pack anime details): the AniPM
                // start language for this play.
                if (title.type == "anime" && mapping == "ok") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "AUDIO",
                            color = Dim,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (big) 11.sp else 10.sp,
                            letterSpacing = 1.5.sp,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        listOf("sub" to "Sub", "dub" to "Dub").forEach { (key, label) ->
                            val on = subDub == key
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .focusRing(RoundedCornerShape(50), pop = false)
                                    .background(if (on) Accent else Pill, RoundedCornerShape(50))
                                    .clickable { subDub = key }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    label,
                                    color = if (on) Ink else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = if (big) 13.sp else 12.sp,
                                )
                            }
                        }
                        if (title.name.contains("dragon ball", true)) {
                            Text(
                                "Faulconer dub pinned",
                                color = Dim,
                                fontFamily = BodyFont,
                                fontSize = 10.sp,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
                PillButton(
                    if (title.type == "movie") "Play Movie" else "Play S" + season + " E" + episode,
                    big,
                    accent = true,
                    icon = Icons.Filled.PlayArrow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Missing mapping no longer blocks play: the player
                    // routes anime-without-ids to VidRift (website rule).
                    if (title.type != "anime" || mapping != "loading") {
                        onPlay(season, episode, ids?.mal, ids?.ani, if (title.type == "anime") subDub else null)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(
                        if (inList) "In My List" else "My List",
                        big,
                        icon = if (inList) Icons.Filled.Check else Icons.Filled.Add,
                        modifier = Modifier.weight(1f),
                    ) {
                        Store.toggleList(ctx, title)
                        inList = Store.inList(ctx, title.type, title.id)
                    }
                    PillButton(
                        "Back",
                        big,
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        modifier = Modifier.weight(1f),
                    ) { onBack() }
                }
            }
        }

        // ---- Anime mapping note ----
        if (title.type == "anime" && mapping != "ok") {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(horizontal = if (big) 28.dp else 16.dp)
                        .background(Color(0x33F5A623), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        when (mapping) {
                            "loading" -> "Matching anime id..."
                            else -> "No anime mapping - play goes through VidRift."
                        },
                        color = Amber,
                        fontFamily = BodyFont,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // ---- Season/episode HUD island + episode rail ----
        if (title.type != "movie") {
            item {
                Column(modifier = Modifier.padding(horizontal = if (big) 28.dp else 16.dp)) {
                    Spacer(modifier = Modifier.height(if (big) 16.dp else 12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (big) 28.dp else 16.dp),
                        modifier = Modifier
                            .background(Card, RoundedCornerShape(18.dp))
                            .padding(if (big) 16.dp else 12.dp),
                    ) {
                        StepChip("S", season, big) { season = it.coerceAtLeast(1) }
                        StepChip("E", episode, big) { episode = it.coerceAtLeast(1) }
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(width = 3.dp, height = 18.dp)
                                .background(Accent, RoundedCornerShape(50)),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "Episodes",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (big) 22.sp else 17.sp,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            "Season " + season,
                            color = Dim,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (big) 13.sp else 11.sp,
                            letterSpacing = 1.sp,
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    if (epsBusy && episodes.isEmpty()) {
                        CircularProgressIndicator(
                            color = Accent,
                            modifier = Modifier.padding(16.dp).size(if (big) 40.dp else 28.dp),
                        )
                    }
                }
            }
            items(episodes) { ep ->
                EpisodeRow(
                    ep = ep,
                    big = big,
                    current = saved != null && saved.season == season && saved.episode == ep.n,
                    savedCur = if (saved != null && saved.season == season && saved.episode == ep.n) saved.cur else 0f,
                    savedDur = if (saved != null && saved.season == season && saved.episode == ep.n) saved.dur else 0f,
                ) {
                    episode = ep.n
                    if (title.type != "anime" || mapping != "loading") {
                        onPlay(season, ep.n, ids?.mal, ids?.ani, if (title.type == "anime") subDub else null)
                    }
                }
            }
        }

        // ---- Cast & similar rails (design pack sections) ----
        if (cast.isNotEmpty()) {
            item { CastRail(cast, big) }
        }
        if (similar.isNotEmpty()) {
            item { SimilarRail(similar, big, onOpen) }
        }
        item { Spacer(modifier = Modifier.height(if (big) 28.dp else 24.dp)) }
    }
}

/** Circular portrait rail (design pack "Starring Cast & Crew"). */
@Composable
private fun CastRail(cast: List<CastMem>, big: Boolean) {
    Column {
        SectionHeader("Cast & Crew")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(cast.size) { i ->
                val m = cast[i]
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.widthIn(max = if (big) 110.dp else 88.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (big) 84.dp else 64.dp)
                            .background(Pill, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (m.photo != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current).data(m.photo).crossfade(true).build(),
                                contentDescription = m.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                            )
                        } else {
                            Text(
                                m.name.take(1).uppercase(),
                                color = Accent,
                                fontWeight = FontWeight.Black,
                                fontSize = if (big) 26.sp else 20.sp,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        m.name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (big) 12.sp else 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (m.role.isNotBlank()) {
                        Text(
                            m.role,
                            color = Dim,
                            fontFamily = BodyFont,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
    }
}

/** "More Like This" poster rail (design pack section). */
@Composable
private fun SimilarRail(similar: List<Title>, big: Boolean, onOpen: (Title) -> Unit) {
    Column {
        SectionHeader("More Like This")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(similar.size) { i -> PosterCard(similar[i], onOpen) }
        }
        Spacer(modifier = Modifier.height(14.dp))
    }
}

/** One episode row: 16:9 still (or E-number plate when TMDB has none),
    title + real meta, synopsis, and a Continue marker on the saved ep. */
@Composable
private fun EpisodeRow(
    ep: Ep,
    big: Boolean,
    current: Boolean,
    savedCur: Float,
    savedDur: Float,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (big) 28.dp else 16.dp, vertical = 5.dp)
            .focusRing(RoundedCornerShape(16.dp))
            .background(if (current) Color(0x1400F0B5) else Card, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = if (big) 180.dp else 122.dp, height = if (big) 102.dp else 70.dp)
                .background(Pill, RoundedCornerShape(12.dp)),
        ) {
            if (ep.still != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(ep.still).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    "E" + ep.n,
                    color = Dim,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (big) 20.sp else 15.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color(0x33000000)),
            ) {
                if (current && savedDur > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = (savedCur / savedDur).coerceIn(0.05f, 1f))
                            .height(3.dp)
                            .background(Accent),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "E" + ep.n,
                    color = Accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (big) 13.sp else 11.sp,
                    letterSpacing = 1.sp,
                )
                if (ep.name.isNotBlank() && ep.name != "Episode " + ep.n) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        ep.name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (big) 16.sp else 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (current) {
                    Text(
                        "CONTINUE",
                        color = Accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (big) 11.sp else 9.sp,
                        letterSpacing = 1.sp,
                    )
                } else if (ep.runtime > 0) {
                    Text(
                        ep.runtime.toString() + "m",
                        color = Dim,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (big) 12.sp else 10.sp,
                    )
                }
            }
            if (ep.overview.isNotBlank()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    ep.overview,
                    color = Dim,
                    fontFamily = BodyFont,
                    fontSize = if (big) 13.sp else 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Glass circle for the floating hero header (design pack top bar). */
@Composable
private fun GlassCircle(size: Dp, icon: androidx.compose.ui.graphics.vector.ImageVector, cd: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .focusRing(CircleShape)
            .background(Glass, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = cd, tint = Color.White, modifier = Modifier.size(size * 0.42f))
    }
}

/** Season/episode stepper as one sleek pill row (design pass 0.15). */
@Composable
private fun StepChip(label: String, value: Int, big: Boolean, onChange: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Pill, RoundedCornerShape(50))
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(if (big) 44.dp else 38.dp)
                .focusRing(CircleShape)
                .background(Card, CircleShape)
                .clickable { onChange(value - 1) },
            contentAlignment = Alignment.Center,
        ) {
            Text("-", color = Color.White, fontWeight = FontWeight.Black, fontSize = if (big) 20.sp else 17.sp)
        }
        Text(
            label + "  " + value,
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = if (big) 17.sp else 14.sp,
            modifier = Modifier.padding(horizontal = if (big) 20.dp else 14.dp),
        )
        Box(
            modifier = Modifier
                .size(if (big) 44.dp else 38.dp)
                .focusRing(CircleShape)
                .background(Card, CircleShape)
                .clickable { onChange(value + 1) },
            contentAlignment = Alignment.Center,
        ) {
            Text("+", color = Color.White, fontWeight = FontWeight.Black, fontSize = if (big) 20.sp else 17.sp)
        }
    }
}
