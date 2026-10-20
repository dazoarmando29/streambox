package com.streambox.nativeapp.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.streambox.nativeapp.api.CrewMem
import com.streambox.nativeapp.api.Ep
import com.streambox.nativeapp.api.SeasonInfo
import com.streambox.nativeapp.api.TDetail
import com.streambox.nativeapp.api.Title
import com.streambox.nativeapp.api.VideoInfo
import com.streambox.nativeapp.data.Progress
import com.streambox.nativeapp.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Series Episodes design (the pack's third screen set) for movie,
    anime and TV: cinematic hero with glass header + Trailer chip, badge
    pills and genre chips from real TMDB details, the Resume/MARK AS WATCHED
    module when you are mid-episode, the Season Directory chip row, the
    episode rail with progress, Top Cast, Bonus Features & Clips, the
    Specifications & Meta card and More Like This. Missing data hides its
    chip - nothing is ever invented. */
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
    var crewList by remember { mutableStateOf(listOf<CrewMem>()) }
    var similar by remember { mutableStateOf(listOf<Title>()) }
    var det by remember { mutableStateOf(TDetail(listOf(), 0, listOf(), "", "", "", listOf())) }
    var saved by remember {
        mutableStateOf(Store.progressList(ctx).firstOrNull { it.type == title.type && it.id == title.id })
    }
    // Coming Soon clock (0.57): the PH air countdown ticks with the minute.
    var tickMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60000)
            tickMs = System.currentTimeMillis()
        }
    }
    // AniPM start language (design pack SUB|DUB chips): the house default
    // stands - Dragon Ball starts on the Faulconer English dub, everything
    // else on sub - and the chips only override per play.
    var subDub by remember {
        mutableStateOf(if (title.name.contains("dragon ball", true)) "dub" else "sub")
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
            // Clear first (0.48): a stale rail from the previous season made
            // 'episode 1' click ANOTHER season's E1 while the fetch ran.
            episodes = listOf()
            episodes = withContext(Dispatchers.IO) {
                runCatching { Api.season(title.id, season) }.getOrDefault(listOf())
            }
            epsBusy = false
        }
    }

    // Season sanity (0.48): whatever the entry path, the selected season
    // must exist on THIS title - a stale pick snaps to the first real
    // season (specials/season 0 never win) and the rail restarts at E1.
    LaunchedEffect(det) {
        if (det.seasons.isNotEmpty() && det.seasons.none { it.n == season }) {
            season = det.seasons.firstOrNull { it.n >= 1 }?.n ?: 1
            episode = 1
        }
    }

    LaunchedEffect(title.id, title.type) {
        // Speed pass 0.32: details/credits/similar run in PARALLEL and each
        // paints the moment it lands - the page never waits for the slowest.
        coroutineScope {
            val d = async(Dispatchers.IO) {
                runCatching { Api.details(title.type, title.id) }
                    .getOrDefault(TDetail(listOf(), 0, listOf(), "", "", "", listOf()))
            }
            val c = async(Dispatchers.IO) {
                runCatching { Api.credits(title.type, title.id) }.getOrDefault(listOf())
            }
            val w = async(Dispatchers.IO) {
                runCatching { Api.crew(title.type, title.id) }.getOrDefault(listOf())
            }
            val s = async(Dispatchers.IO) {
                runCatching { Api.similar(title.type, title.id) }.getOrDefault(listOf())
            }
            det = d.await()
            cast = c.await()
            crewList = w.await()
            similar = s.await()
        }
    }

    // Second wave: certification + trailer/extras ride in behind the core
    // (they only feed chips and rails, never blocking the page).
    LaunchedEffect(title.id, title.type) {
        val pair = withContext(Dispatchers.IO) {
            runCatching { Api.cert(title.type, title.id) }.getOrDefault("") to
                runCatching { Api.videos(title.type, title.id) }.getOrDefault(listOf())
        }
        det = det.copy(cert = pair.first, videos = pair.second)
    }

    // Remote/gesture Back works everywhere - no hunting for a button on TV.
    BackHandler { onBack() }

    // Resume module (design pack): mid-episode progress turns the CTA into
    // "Resume Sx Ex" with the time left and a MARK AS WATCHED escape.
    val p = saved
    val watched = p != null && p.dur > 0 && p.cur >= p.dur * 0.95f
    val resumable = p != null && p.cur > 5f && !watched
    // Non-null only while resumable - one binding for every Resume module.
    val rp = if (resumable) p else null
    val trailer = det.videos.firstOrNull { it.kind.equals("Trailer", true) } ?: det.videos.firstOrNull()
    val langFor = if (title.type == "anime") subDub else null

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // ---- Cinematic hero + glass header ----
        item {
            Box(modifier = Modifier.fillMaxWidth().height(if (big) 560.dp else 440.dp)) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(title.backdrop ?: title.poster).crossfade(180).build(),
                    contentDescription = title.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(horizontal = if (big) 28.dp else 16.dp, vertical = if (big) 18.dp else 12.dp),
                ) {
                    GlassCircle(if (big) 52.dp else 44.dp, Icons.AutoMirrored.Filled.ArrowBack, "Back") { onBack() }
                    Spacer(modifier = Modifier.weight(1f))
                    if (trailer != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .focusRing(RoundedCornerShape(50), pop = false)
                                .background(Glass, RoundedCornerShape(50))
                                .clickable { openTrailer(ctx, trailer.key) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "Trailer",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Trailer", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(Color(0x2600F0B5), RoundedCornerShape(50))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Box(modifier = Modifier.size(6.dp).background(Accent, CircleShape))
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
                        if (det.cert.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .background(Glass, RoundedCornerShape(50))
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    det.cert,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = if (big) 11.sp else 10.sp,
                                    letterSpacing = 1.sp,
                                )
                            }
                        }
                        if (!det.aired) {
                            Box(
                                modifier = Modifier
                                    .background(Color(0x33F5A623), RoundedCornerShape(50))
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    "COMING SOON",
                                    color = Amber,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = if (big) 11.sp else 10.sp,
                                    letterSpacing = 1.sp,
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
                                append(" /10")
                            }
                            if (title.year > 0) {
                                if (isNotEmpty()) append("   \u2022   ")
                                append(title.year)
                            }
                            if (title.type == "movie" && det.runtime > 0) {
                                if (isNotEmpty()) append("   \u2022   ")
                                append(fmtMin(det.runtime))
                            }
                            // Genres ride the meta line (design pack:
                            // "9.0 /10 - Sci-Fi - Cyber Thriller - Action").
                            for (g in det.genres) {
                                if (isNotEmpty()) append("   \u2022   ")
                                append(g)
                            }
                        },
                        color = Dim,
                        fontWeight = FontWeight.Medium,
                        fontSize = if (big) 15.sp else 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = if (big) 1100.dp else 560.dp),
                    )
                    if (title.overview.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            title.overview,
                            color = Soft,
                            fontFamily = BodyFont,
                            fontSize = if (big) 16.sp else 13.sp,
                            maxLines = if (expanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = if (big) 860.dp else 560.dp),
                        )
                        Row(modifier = Modifier.clickable { expanded = !expanded }.padding(top = 2.dp)) {
                            Text(
                                if (expanded) "Show less" else "Read more",
                                color = Accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (big) 14.sp else 12.sp,
                            )
                        }
                    }
                    // CTA row (design pack series-details): PLAY + My List +
                    // Trailer side by side - never stacked full-width.
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (det.aired) {
                            PillButton(
                                when {
                                    rp != null && title.type != "movie" ->
                                        "Resume S" + rp.season + " E" + rp.episode
                                    rp != null -> "Resume Movie"
                                    title.type == "movie" -> "Play Movie"
                                    else -> "Play S" + season + " E" + episode
                                },
                                big,
                                accent = true,
                                icon = Icons.Filled.PlayArrow,
                                modifier = Modifier.weight(1.4f),
                            ) {
                                if (title.type != "anime" || mapping != "loading") {
                                    if (rp != null) {
                                        onPlay(rp.season, rp.episode, ids?.mal, ids?.ani, langFor)
                                    } else {
                                        onPlay(season, episode, ids?.mal, ids?.ani, langFor)
                                    }
                                }
                            }
                        } else {
                            // Not out yet (Avengers: Doomsday case): a Coming
                            // Soon plate where Play would be - My List still
                            // works right beside it.
                            Box(
                                modifier = Modifier
                                    .weight(1.4f)
                                    .height(if (big) 54.dp else 46.dp)
                                    .background(Pill, RoundedCornerShape(50)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "COMING SOON",
                                    color = Amber,
                                    fontWeight = FontWeight.Black,
                                    fontSize = if (big) 16.sp else 14.sp,
                                    letterSpacing = 2.sp,
                                )
                            }
                        }
                        PillButton(
                            if (inList) "In My List" else "My List",
                            big,
                            icon = if (inList) Icons.Filled.Check else Icons.Filled.Add,
                            modifier = Modifier.weight(1f),
                        ) {
                            Store.toggleList(ctx, title)
                            inList = Store.inList(ctx, title.type, title.id)
                        }
                        if (trailer != null) {
                            PillButton("Trailer", big, icon = Icons.Filled.PlayArrow, modifier = Modifier.weight(1f)) {
                                openTrailer(ctx, trailer.key)
                            }
                        } else {
                            PillButton("Back", big, icon = Icons.AutoMirrored.Filled.ArrowBack, modifier = Modifier.weight(1f)) {
                                onBack()
                            }
                        }
                    }
                    if (rp != null && rp.dur > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                fmtMin(((rp.dur - rp.cur) / 60).toInt().coerceAtLeast(1)) + " left",
                                color = Accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .focusRing(RoundedCornerShape(50), pop = false)
                                    .background(Pill, RoundedCornerShape(50))
                                    .clickable {
                                        Store.saveProgress(
                                            ctx,
                                            Progress(
                                                rp.type, rp.id, rp.name, rp.poster, rp.season, rp.episode,
                                                rp.dur, rp.dur, System.currentTimeMillis(), rp.mal, rp.ani,
                                            ),
                                        )
                                        saved = Store.progressList(ctx)
                                            .firstOrNull { it.type == title.type && it.id == title.id }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                            ) {
                                Text(
                                    "MARK AS WATCHED",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    letterSpacing = 1.sp,
                                )
                            }
                        }
                    }
                    if (!det.aired) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Not out yet - save it to My List and check back.",
                            color = Dim,
                            fontFamily = BodyFont,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }

        // (Play/Resume + My List + Trailer live in the hero CTA row now -
        // design pack series-details. SUB|DUB moved to the info card.)

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

        // ---- Season Directory + episode rail ----
        if (title.type != "movie") {
            item {
                Column(modifier = Modifier.padding(horizontal = if (big) 28.dp else 16.dp)) {
                    Spacer(modifier = Modifier.height(if (big) 12.dp else 8.dp))
                    if (det.seasons.isNotEmpty()) {
                        Text(
                            "SEASONS & EPISODES",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = if (big) 18.sp else 15.sp,
                            letterSpacing = 0.5.sp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // Design pack season pills: "Season 1  10 Episodes"
                        // inline; a season whose air date is still in the
                        // future is a COMING SOON schedule entry.
                        val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                            .format(java.util.Date())
                        LazyRow(
                            modifier = Modifier.rowNavLock(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(det.seasons.size) { i ->
                                val s = det.seasons[i]
                                val on = s.n == season
                                val soon = s.air.isNotBlank() && s.air > todayStr
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .focusRing(RoundedCornerShape(50), pop = false)
                                        .background(if (on) Pill else Color(0x1AFFFFFF), RoundedCornerShape(50))
                                        .then(if (!soon) Modifier.clickable {
                                            season = s.n
                                            episode = 1
                                        } else Modifier)
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                ) {
                                    if (on) {
                                        Box(modifier = Modifier.size(7.dp).background(Accent, CircleShape))
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(
                                        "Season " + s.n,
                                        color = if (on) Accent else Color.White,
                                        fontWeight = FontWeight.Black,
                                        fontSize = if (big) 13.sp else 12.sp,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        when {
                                            soon -> "COMING SOON"
                                            s.count > 0 -> s.count.toString() + " Episodes"
                                            else -> ""
                                        },
                                        color = if (soon) Amber else Dim,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = if (big) 11.sp else 10.sp,
                                        letterSpacing = if (soon) 1.sp else 0.sp,
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
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
                val cur = p != null && p.season == season && p.episode == ep.n
                EpisodeRow(
                    ep = ep,
                    big = big,
                    current = cur,
                    savedCur = if (cur) p!!.cur else 0f,
                    savedDur = if (cur) p!!.dur else 0f,
                    tickMs = tickMs,
                ) {
                    episode = ep.n
                    if (title.type != "anime" || mapping != "loading") {
                        onPlay(season, ep.n, ids?.mal, ids?.ani, langFor)
                    }
                }
            }
        }

        // ---- Info card (design pack series-details footer): Creator &
        // Showrunner, Starring Cast, Audio Streams and Subtitles & CC ----
        item {
            Column(modifier = Modifier.padding(horizontal = if (big) 28.dp else 16.dp)) {
                Spacer(modifier = Modifier.height(if (big) 6.dp else 4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(if (big) 24.dp else 10.dp),
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Card, RoundedCornerShape(16.dp))
                        .padding(if (big) 22.dp else 14.dp),
                ) {
                    InfoCol("CREATOR & SHOWRUNNER", Modifier.weight(1f)) {
                        val lead = crewList.firstOrNull()
                        Text(
                            lead?.name ?: "Not reported",
                            color = if (lead != null) Color.White else Dim,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (big) 15.sp else 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        crewList.drop(1).forEach { c ->
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                c.job + ": " + c.name,
                                color = Dim,
                                fontFamily = BodyFont,
                                fontSize = if (big) 12.sp else 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    InfoCol("STARRING CAST", Modifier.weight(1f)) {
                        if (cast.isEmpty()) {
                            Text(
                                "Not reported",
                                color = Dim,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (big) 15.sp else 13.sp,
                            )
                        } else {
                            Text(
                                cast[0].name,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (big) 15.sp else 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                cast.drop(1).take(3).joinToString(", ") { it.name },
                                color = Dim,
                                fontFamily = BodyFont,
                                fontSize = if (big) 12.sp else 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    InfoCol("AUDIO STREAMS", Modifier.weight(1f)) {
                        Text(
                            det.langs.ifEmpty { "Not reported" },
                            color = Soft,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (big) 13.sp else 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (title.type == "anime" && mapping == "ok") {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("sub" to "Sub", "dub" to "Dub").forEach { (key, label) ->
                                    val on = subDub == key
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .focusRing(RoundedCornerShape(50), pop = false)
                                            .background(if (on) Accent else Pill, RoundedCornerShape(50))
                                            .clickable { subDub = key }
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            label,
                                            color = if (on) Ink else Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = if (big) 12.sp else 11.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    InfoCol("SUBTITLES & CC", Modifier.weight(1f)) {
                        Text(
                            "English, Spanish, French, German, Italian, Portuguese + more",
                            color = Soft,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (big) 13.sp else 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            "Searchable via Wyzie / TSDB / BetaSeries + manual .srt / .vtt",
                            color = Dim,
                            fontFamily = BodyFont,
                            fontSize = if (big) 12.sp else 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // ---- Bonus Features & Clips ----
        if (det.videos.isNotEmpty()) {
            item {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(18.dp)
                                .background(Accent, RoundedCornerShape(50)),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "Bonus Features & Clips",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (big) 22.sp else 17.sp,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            det.videos.size.toString() + " EXTRAS",
                            color = Dim,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp,
                        )
                    }
                    LazyRow(
                        modifier = Modifier.rowNavLock(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(det.videos.size) { i ->
                            val v = det.videos[i]
                            Column(
                                modifier = Modifier
                                    .width(if (big) 260.dp else 220.dp)
                                    .focusRing(RoundedCornerShape(14.dp))
                                    .clickable { openTrailer(ctx, v.key) },
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(if (big) 146.dp else 122.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Pill),
                                ) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(ctx)
                                            .data("https://img.youtube.com/vi/" + v.key + "/hqdefault.jpg")
                                            .crossfade(180)
                                            .build(),
                                        contentDescription = v.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .size(40.dp)
                                            .background(Color(0xCC0B111E), CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Filled.PlayArrow,
                                            contentDescription = null,
                                            tint = Accent,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    v.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = if (big) 13.sp else 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (v.kind.isNotBlank()) {
                                    Text(
                                        v.kind,
                                        color = Dim,
                                        fontFamily = BodyFont,
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }
        }

        // ---- Specifications & Meta ----
        if (det.studio.isNotBlank() || det.langs.isNotBlank() || det.cert.isNotBlank()) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Card, RoundedCornerShape(16.dp))
                            .padding(14.dp),
                    ) {
                        Text(
                            "SPECIFICATIONS & META",
                            color = Dim,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 1.5.sp,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (det.studio.isNotBlank()) {
                                SpecCell("STUDIO", det.studio, Modifier.weight(1f))
                            }
                            if (det.langs.isNotBlank()) {
                                SpecCell("LANGUAGES", det.langs, Modifier.weight(1f))
                            }
                        }
                        if (det.cert.isNotBlank()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            SpecCell(
                                "CONTENT ADVISORY",
                                det.cert,
                                Modifier.fillMaxWidth(),
                                warn = true,
                            )
                        }
                        if (det.runtime > 0 && title.type == "movie") {
                            Spacer(modifier = Modifier.height(10.dp))
                            SpecCell("RUNTIME", fmtMin(det.runtime), Modifier.fillMaxWidth())
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }
        }

        // ---- More Like This ----
        if (similar.isNotEmpty()) {
            item {
                // 0.58: no reveal requests - the whole-card target carries
                // names into view by itself (row reveals zigzagged).
                Column(modifier = Modifier.rowNavLock()) {
                    SectionHeader("More Like This")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(similar.size) { i ->
                            PosterCard(similar[i], onOpen)
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }
        }
        item { Spacer(modifier = Modifier.height(if (big) 28.dp else 24.dp)) }
    }
}

/** One labeled column of the detail info card (design pack footer). */
@Composable
private fun InfoCol(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier) {
        Text(
            label,
            color = Dim,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 1.5.sp,
        )
        Spacer(modifier = Modifier.height(6.dp))
        content()
    }
}

/** One cell of the Specifications & Meta card (design pack). */
@Composable
private fun SpecCell(label: String, value: String, modifier: Modifier = Modifier, warn: Boolean = false) {
    Column(modifier = modifier) {
        Text(
            label,
            color = Dim,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 1.5.sp,
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            value,
            color = if (warn) Amber else Color.White,
            fontWeight = FontWeight.Bold,
            fontFamily = BodyFont,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Minutes -> "56m" / "1h 57m" (design pack durations). */
private fun fmtMin(min: Int): String =
    if (min >= 60) (min / 60).toString() + "h " + (min % 60) + "m" else min.toString() + "m"

/** TMDB trailer/extras keys are YouTube ids - open the official player. */
private fun openTrailer(ctx: android.content.Context, key: String) {
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + key))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Coming Soon clock (0.57): air dates shown and counted down in Philippine
    time (Asia/Manila) - the family clock. TMDB air dates are date-only, so
    an episode flips available at 00:00 PH of its air date. */
private fun phTz(): java.util.TimeZone = java.util.TimeZone.getTimeZone("Asia/Manila")

private fun phAirLabel(air: String): String {
    return try {
        val parse = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val out = java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.US)
        out.timeZone = phTz()
        out.format(parse.parse(air) ?: return air)
    } catch (e: Exception) {
        air
    }
}

private fun phCountdown(air: String, nowMs: Long): String {
    return try {
        val p = air.split("-")
        val cal = java.util.Calendar.getInstance(phTz())
        cal.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt(), 0, 0, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val diff = cal.timeInMillis - nowMs
        if (diff <= 0) return "available now"
        val days = diff / 86400000L
        val hours = (diff % 86400000L) / 3600000L
        val mins = (diff % 3600000L) / 60000L
        when {
            days > 0 -> "in " + days + "d " + hours + "h"
            hours > 0 -> "in " + hours + "h " + mins + "m"
            else -> "in " + maxOf(1L, mins) + "m"
        }
    } catch (e: Exception) {
        ""
    }
}

/** One episode row: 16:9 still (or E-number plate when TMDB has none),
    play overlay, title + real meta, synopsis, and a Continue marker on the
    saved ep (design pack rows). */
@Composable
private fun EpisodeRow(
    ep: Ep,
    big: Boolean,
    current: Boolean,
    savedCur: Float,
    savedDur: Float,
    tickMs: Long,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (big) 28.dp else 16.dp, vertical = 5.dp)
            .rowNavLock()
            .focusRing(RoundedCornerShape(16.dp))
            .background(if (current) Color(0x1400F0B5) else Card, RoundedCornerShape(16.dp))
            // Unaired rows stay visible but are NOT play targets (no dead
            // buttons - the D-pad skips them like Netflix's schedule rows).
            .then(if (ep.aired) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = if (big) 180.dp else 122.dp, height = if (big) 102.dp else 70.dp)
                .background(Pill, RoundedCornerShape(12.dp)),
        ) {
            if (ep.still != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(ep.still).crossfade(180).build(),
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
            if (ep.aired) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(if (big) 34.dp else 28.dp)
                        .background(Color(0xB30B111E), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(if (big) 18.dp else 15.dp),
                    )
                }
            }
            if (ep.runtime > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color(0xE60B111E), RoundedCornerShape(50))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        fmtMin(ep.runtime),
                        color = Soft,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (big) 10.sp else 9.sp,
                    )
                }
            }
            if (current && savedDur > 0 && savedCur > 5) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color(0xE60B111E), RoundedCornerShape(50))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        "Watched " + (savedCur / savedDur * 100).toInt() + "%",
                        color = Accent,
                        fontWeight = FontWeight.Black,
                        fontSize = if (big) 10.sp else 9.sp,
                    )
                }
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
                // Design pack numbering: "1. The Awakening".
                Text(
                    ep.n.toString() + ". ",
                    color = Accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (big) 16.sp else 13.sp,
                )
                Text(
                    if (ep.name.isNotBlank() && ep.name != "Episode " + ep.n) ep.name else "Episode " + ep.n,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (big) 16.sp else 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.weight(1f))
                // Status zone (0.57): ONE consistent right-aligned block on
                // every row - status label on top (NEW / COMING SOON) and the
                // time line under it (remaining when in progress, runtime
                // otherwise; Coming Soon carries the PH air date + countdown).
                // "NEW" stays reserved for the episode of THIS WEEK (0.38).
                val weekAgo = remember {
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .format(java.util.Date(System.currentTimeMillis() - 7L * 24 * 3600 * 1000))
                }
                val isNew = ep.aired && ep.air.isNotBlank() && ep.air >= weekAgo
                val inProgress = ep.aired && current && savedDur > 0 && savedCur > 5
                Column(horizontalAlignment = Alignment.End) {
                    if (!ep.aired) {
                        Text(
                            "COMING SOON",
                            color = Amber,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (big) 11.sp else 9.sp,
                            letterSpacing = 1.sp,
                        )
                    } else if (isNew) {
                        Text(
                            "NEW",
                            color = Accent,
                            fontWeight = FontWeight.Black,
                            fontSize = if (big) 11.sp else 9.sp,
                            letterSpacing = 1.sp,
                        )
                    }
                    val timeLine = when {
                        !ep.aired ->
                            if (ep.air.isNotBlank()) {
                                phAirLabel(ep.air) + " (PH)  \u00b7  " + phCountdown(ep.air, tickMs)
                            } else ""
                        inProgress -> fmtMin(((savedDur - savedCur) / 60).toInt().coerceAtLeast(1)) + " left"
                        ep.runtime > 0 -> fmtMin(ep.runtime)
                        else -> ""
                    }
                    if (timeLine.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            timeLine,
                            color = if (!ep.aired) Soft else if (inProgress) Accent else Dim,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (big) 12.sp else 10.sp,
                        )
                    }
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
            .focusRingCircle()
            .background(Glass, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = cd, tint = Color.White, modifier = Modifier.size(size * 0.42f))
    }
}
