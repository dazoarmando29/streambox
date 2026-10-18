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
import com.streambox.nativeapp.api.SeasonInfo
import com.streambox.nativeapp.api.TDetail
import com.streambox.nativeapp.api.Title
import com.streambox.nativeapp.api.VideoInfo
import com.streambox.nativeapp.data.Progress
import com.streambox.nativeapp.data.Store
import kotlinx.coroutines.Dispatchers
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
    var similar by remember { mutableStateOf(listOf<Title>()) }
    var det by remember { mutableStateOf(TDetail(listOf(), 0, listOf(), "", "", "", listOf())) }
    var saved by remember {
        mutableStateOf(Store.progressList(ctx).firstOrNull { it.type == title.type && it.id == title.id })
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
            episodes = withContext(Dispatchers.IO) {
                runCatching { Api.season(title.id, season) }.getOrDefault(listOf())
            }
            epsBusy = false
        }
    }

    LaunchedEffect(title.id, title.type) {
        val triple = withContext(Dispatchers.IO) {
            Triple(
                runCatching { Api.details(title.type, title.id) }
                    .getOrDefault(TDetail(listOf(), 0, listOf(), "", "", "", listOf())),
                runCatching { Api.credits(title.type, title.id) }.getOrDefault(listOf()),
                runCatching { Api.similar(title.type, title.id) }.getOrDefault(listOf()),
            )
        }
        det = triple.first
        cast = triple.second
        similar = triple.third
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
                    model = ImageRequest.Builder(ctx).data(title.backdrop ?: title.poster).crossfade(true).build(),
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
                            if (title.type == "movie") {
                                if (det.runtime > 0) {
                                    if (isNotEmpty()) append("   \u2022   ")
                                    append(fmtMin(det.runtime))
                                }
                            } else {
                                if (isNotEmpty()) append("   \u2022   ")
                                append("S").append(season).append(" E").append(episode)
                                if (det.seasons.isNotEmpty()) {
                                    append("   \u2022   ")
                                    append(det.seasons.size).append(" Seasons")
                                }
                            }
                        },
                        color = Dim,
                        fontWeight = FontWeight.Medium,
                        fontSize = if (big) 15.sp else 12.sp,
                    )
                    if (det.genres.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(det.genres.size) { i ->
                                Box(
                                    modifier = Modifier
                                        .background(Glass, RoundedCornerShape(50))
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        det.genres[i],
                                        color = Soft,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = if (big) 11.sp else 10.sp,
                                    )
                                }
                            }
                        }
                    }
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
                        Row(modifier = Modifier.clickable { expanded = !expanded }.padding(top = 2.dp)) {
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

        // ---- Resume / Play module ----
        item {
            Column(modifier = Modifier.padding(horizontal = if (big) 28.dp else 16.dp, vertical = if (big) 16.dp else 12.dp)) {
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
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
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
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (title.type != "anime" || mapping != "loading") {
                        if (rp != null) {
                            onPlay(rp.season, rp.episode, ids?.mal, ids?.ani, langFor)
                        } else {
                            onPlay(season, episode, ids?.mal, ids?.ani, langFor)
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
                        Spacer(modifier = Modifier.weight(1f))
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
                    if (trailer != null) {
                        PillButton(
                            "Trailer",
                            big,
                            icon = Icons.Filled.PlayArrow,
                            modifier = Modifier.weight(1f),
                        ) { openTrailer(ctx, trailer.key) }
                    } else {
                        PillButton(
                            "Back",
                            big,
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            modifier = Modifier.weight(1f),
                        ) { onBack() }
                    }
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

        // ---- Season Directory + episode rail ----
        if (title.type != "movie") {
            item {
                Column(modifier = Modifier.padding(horizontal = if (big) 28.dp else 16.dp)) {
                    Spacer(modifier = Modifier.height(if (big) 12.dp else 8.dp))
                    if (det.seasons.isNotEmpty()) {
                        Text(
                            "SEASON DIRECTORY",
                            color = Dim,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 1.5.sp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(det.seasons.size) { i ->
                                val s = det.seasons[i]
                                val on = s.n == season
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .focusRing(RoundedCornerShape(50), pop = false)
                                        .background(if (on) Accent else Pill, RoundedCornerShape(50))
                                        .clickable {
                                            season = s.n
                                            episode = 1
                                        }
                                        .padding(horizontal = 16.dp, vertical = 9.dp),
                                ) {
                                    Text(
                                        "Season " + s.n,
                                        color = if (on) Ink else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = if (big) 13.sp else 12.sp,
                                    )
                                    if (s.count > 0) {
                                        Text(
                                            s.count.toString() + " eps",
                                            color = if (on) Ink.copy(alpha = 0.7f) else Dim,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp,
                                        )
                                    }
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
                ) {
                    episode = ep.n
                    if (title.type != "anime" || mapping != "loading") {
                        onPlay(season, ep.n, ids?.mal, ids?.ani, langFor)
                    }
                }
            }
        }

        // ---- Top Cast ----
        if (cast.isNotEmpty()) {
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
                                .background(Cyan, RoundedCornerShape(50)),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "Top Cast",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (big) 22.sp else 17.sp,
                        )
                    }
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
                                            model = ImageRequest.Builder(ctx).data(m.photo).crossfade(true).build(),
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
                                            .crossfade(true)
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
        }
        item { Spacer(modifier = Modifier.height(if (big) 28.dp else 24.dp)) }
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
                if (current && savedDur > 0 && savedCur > 5) {
                    Text(
                        fmtMin(((savedDur - savedCur) / 60).toInt().coerceAtLeast(1)) + " left",
                        color = Accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (big) 12.sp else 10.sp,
                    )
                } else if (ep.runtime > 0) {
                    Text(
                        fmtMin(ep.runtime),
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
