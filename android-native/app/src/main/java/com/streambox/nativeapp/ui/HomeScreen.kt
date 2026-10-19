package com.streambox.nativeapp.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.streambox.nativeapp.AppEnv
import com.streambox.nativeapp.api.Api
import com.streambox.nativeapp.api.Title
import com.streambox.nativeapp.data.Progress
import com.streambox.nativeapp.data.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Session memory of catalog rows: Back-from-player and tab switches paint
    instantly while a background revalidate keeps them fresh (build 0.13 -
    refetching five sequential catalogs made the TV feel endless). */
private object BrowseCache {
    val rows = HashMap<String, List<Pair<String, List<Title>>>>()
    val searches = HashMap<String, List<Title>>()
}

/** The canonical row plan per section - shared by the loader and the
    incremental painter so rows never reorder as they land. A genre pick
    swaps the section's rows for that genre's real discover rows (BROWSE BY
    GENRE, 0.39). */
private fun rowPlan(
    section: Section,
    genre: String? = null,
    gName: String = "",
): List<Pair<String, () -> List<Title>>> = when (section) {
    Section.HOME -> listOf(
        "Trending Now" to { Api.trending() },
        "Popular Movies" to { Api.popularMovies() },
        "Popular Shows" to { Api.popularTv() },
        "Top Rated Movies" to { Api.topRatedMovies() },
        "Top Rated Shows" to { Api.topRatedTv() },
    )
    Section.MOVIES -> {
        val g = genre?.toIntOrNull()
        if (g != null) listOf(
            "Popular " + gName + " Movies" to { Api.genreMovies(g, false) },
            "Top Rated " + gName + " Movies" to { Api.genreMovies(g, true) },
        ) else listOf(
            "Trending Movies" to { Api.trending().filter { it.type == "movie" } },
            "Popular Movies" to { Api.popularMovies() },
            "Top Rated Movies" to { Api.topRatedMovies() },
        )
    }
    Section.TV -> {
        val g = genre?.toIntOrNull()
        if (g != null) listOf(
            "Popular " + gName + " Shows" to { Api.genreTv(g, false, false).filter { !it.isAnime } },
            "Top Rated " + gName + " Shows" to { Api.genreTv(g, true, false).filter { !it.isAnime } },
        ) else listOf(
            "New Episodes" to { Api.newEpisodes(false).filter { !it.isAnime } },
            "Trending Shows" to { Api.trending().filter { it.type != "movie" && !it.isAnime } },
            "Popular Shows" to { Api.popularTv().filter { !it.isAnime } },
            "Top Rated Shows" to { Api.topRatedTv().filter { !it.isAnime } },
        )
    }
    // Anime genre picks ride ANILIST (Romance, Slice of Life, Mecha, ...).
    Section.ANIME -> if (genre != null) listOf(
        "Popular " + gName + " Anime" to { Api.animeGenreBrowse(genre, false) },
        "Top Rated " + gName + " Anime" to { Api.animeGenreBrowse(genre, true) },
    ) else listOf(
        "New Episodes" to { Api.newEpisodes(true) },
        "Popular Anime" to { Api.anime() },
        "Top Rated Anime" to { Api.animeTop() },
    )
    Section.MYLIST -> listOf()
}

/** Every row loads in parallel AND paints the moment it lands (speed pass
    0.32): the hero appears after the first request instead of after all
    five, in canonical order so nothing jumps. One dead endpoint can never
    sink the page - it just comes back empty. */
private suspend fun fetchRows(
    section: Section,
    genre: String?,
    gName: String,
    onProgress: (List<Pair<String, List<Title>>>) -> Unit,
): List<Pair<String, List<Title>>> =
    withContext(Dispatchers.IO) {
        coroutineScope {
            val plan = rowPlan(section, genre, gName)
            val order = plan.map { it.first }
            val done = java.util.Collections.synchronizedMap(HashMap<String, List<Title>>())
            plan.map { (label, f) ->
                async {
                    val titles = runCatching { f() }.getOrDefault(listOf())
                    done[label] = titles
                    onProgress(order.mapNotNull { l -> done[l]?.let { l to it } })
                }
            }.awaitAll()
            order.mapNotNull { l -> done[l]?.let { l to it } }
        }
    }

@Composable
fun BrowseScreen(
    section: Section,
    onSection: (Section) -> Unit,
    onOpen: (Title) -> Unit,
    onPlay: (Title) -> Unit,
    onResume: (Progress) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val big = AppEnv.isTv

    var searchMode by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf(listOf<Pair<String, List<Title>>>()) }
    var results by remember { mutableStateOf(listOf<Title>()) }
    var cont by remember { mutableStateOf(listOf<Progress>()) }
    var myList by remember { mutableStateOf(listOf<Title>()) }
    var adultOpen by remember { mutableStateOf(Store.adultOpen(ctx)) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadedKey by remember { mutableStateOf("") }
    // BROWSE BY GENRE (0.39): real TMDB genre names for the chip row; the
    // pick swaps the section's rows for that genre's discover rows. Anime
    // uses the ANILIST taxonomy instead (0.40 - Romance, Slice of Life,
    // Mecha, ... that TMDB's TV genres do not have).
    var genreSel by remember { mutableStateOf<String?>(null) }
    var genreList by remember { mutableStateOf(listOf<Pair<String, String>>()) }

    LaunchedEffect(section) {
        genreSel = null
        genreList = withContext(Dispatchers.IO) {
            runCatching {
                when (section) {
                    Section.ANIME -> Api.animeGenres()
                    Section.MOVIES -> Api.genres("movie").map { it.first.toString() to it.second }
                    Section.TV -> Api.genres("tv").map { it.first.toString() to it.second }
                    else -> listOf()
                }
            }.getOrDefault(listOf())
        }
    }

    LaunchedEffect(section, submitted, searchMode, genreSel) {
        cont = Store.progressList(ctx)
        myList = Store.listItems(ctx)
        val isSearch = searchMode && submitted.isNotBlank()
        val gName = genreList.firstOrNull { it.first == genreSel }?.second ?: ""
        val key = if (isSearch) "s:" + submitted.trim().lowercase() + ":" + section
        else "r:" + section + ":" + (genreSel ?: "")
        // Stale-while-revalidate: cached content paints immediately and is
        // refreshed in the background - Back-from-player is instant now.
        val hitRows = BrowseCache.rows[key]
        val hitSearch = BrowseCache.searches[key]
        when {
            hitRows != null -> { rows = hitRows; results = listOf() }
            hitSearch != null -> { results = hitSearch; rows = listOf() }
            key != loadedKey -> { rows = listOf(); results = listOf() }
        }
        loading = rows.isEmpty() && results.isEmpty()
        error = null
        try {
            if (isSearch) {
                val fresh = withContext(Dispatchers.IO) {
                    runCatching {
                        Api.search(submitted, if (section == Section.ANIME) "anime" else null)
                    }.getOrDefault(listOf())
                }
                results = fresh
                rows = listOf()
                BrowseCache.searches[key] = fresh
            } else {
                // Rows paint as they land - hero first, rest stream in.
                // Over a cached paint we revalidate silently (no flicker
                // back to partial rows) and swap once complete.
                rows = fetchRows(section, genreSel, gName) { partial -> if (hitRows == null) rows = partial }
                results = listOf()
                BrowseCache.rows[key] = rows
            }
            loadedKey = key
        } catch (e: CancellationException) {
            // Leaving/recomposing cancels the load - that is normal navigation,
            // NEVER an error (the old catch surfaced "coroutine scope left the
            // composition" to the user).
            throw e
        } catch (e: Exception) {
            if (rows.isEmpty() && results.isEmpty()) {
                error = "Couldn't load the catalog - check your connection and try again."
            }
        }
        loading = false
    }

    Column(modifier = modifier.fillMaxSize()) {
        val body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
            if (searchMode) {
                SearchField(
                    input = input,
                    onInput = { input = it },
                    onSubmit = { submitted = input },
                )
            }
            // BROWSE BY GENRE (design pack language): the chip row rides
            // over the movies / shows / anime feeds only.
            if (!searchMode && genreList.isNotEmpty()) {
                GenreBar(genreList, genreSel, big) { genreSel = it }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = Accent) }
                    error != null -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { Text(error ?: "", color = Dim, fontFamily = BodyFont) }
                    searchMode && submitted.isNotBlank() -> SearchGrid(results, adultOpen, onOpen)
                    section == Section.MYLIST -> MyListView(
                        titles = myList,
                        cont = cont,
                        adultOpen = adultOpen,
                        big = big,
                        onOpen = onOpen,
                        onPlay = onPlay,
                        onRemove = { t ->
                            Store.toggleList(ctx, t)
                            myList = Store.listItems(ctx)
                        },
                    )
                    else -> Feed(rows, cont, adultOpen, big, onOpen, onPlay, onResume,
                        onRemoveCont = { p ->
                            Store.removeProgress(ctx, p.type, p.id)
                            cont = Store.progressList(ctx)
                        })
                }
            }
        }

        if (big) {
            // TV shell (design pack): icon rail owns the left edge, the
            // status strip carries device + clock, content flows beside it.
            Row(modifier = Modifier.fillMaxSize()) {
                TvRail(
                    section = section,
                    onSection = onSection,
                    onSearchToggle = { searchMode = !searchMode; submitted = ""; input = "" },
                    adultOpen = adultOpen,
                    onAdultToggle = {
                        adultOpen = !adultOpen
                        Store.setAdultOpen(ctx, adultOpen)
                    },
                )
                Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                    TvStatusBar()
                    body()
                    TvLegend()
                }
            }
        } else {
            TopBar(
                section = section,
                onSection = onSection,
                searchMode = searchMode,
                onSearchToggle = { searchMode = !searchMode; submitted = ""; input = "" },
                adultOpen = adultOpen,
                onAdultToggle = {
                    adultOpen = !adultOpen
                    Store.setAdultOpen(ctx, adultOpen)
                },
            )
            body()
        }
    }
}

/** Top chrome. Design split: TV gets the WEBSITE's top tab bar (logo +
    Home/Movies/TV Shows/Anime/My List + search + lock, D-pad friendly);
    phone keeps a compact logo bar with the floating bottom nav below. */
@Composable
private fun TopBar(
    section: Section,
    onSection: (Section) -> Unit,
    searchMode: Boolean,
    onSearchToggle: () -> Unit,
    adultOpen: Boolean,
    onAdultToggle: () -> Unit,
) {
    val big = AppEnv.isTv
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (big) Color(0xFF0E1524) else Ink)
            .height(if (big) 68.dp else 56.dp)
            .padding(horizontal = if (big) 20.dp else 14.dp),
    ) {
        LogoMark(big)
        if (big) {
            Spacer(modifier = Modifier.width(28.dp))
            Section.values().forEach { s ->
                NavTab(
                    label = if (s == Section.TV) "TV Shows" else s.label,
                    on = s == section,
                ) { onSection(s) }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(if (big) 46.dp else 42.dp)
                .focusRing(RoundedCornerShape(50))
                .background(Pill, RoundedCornerShape(50))
                .clickable { onSearchToggle() }
                .padding(horizontal = 14.dp),
        ) {
            Icon(Icons.Filled.Search, contentDescription = "Search", tint = Color.White, modifier = Modifier.size(18.dp))
            if (!searchMode) {
                Spacer(modifier = Modifier.width(6.dp))
                Text("Search", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(if (big) 46.dp else 42.dp)
                .focusRingCircle()
                .background(Pill, CircleShape)
                .clickable { onAdultToggle() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = "Adults-only gate",
                tint = if (adultOpen) Accent else Amber,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun LogoMark(big: Boolean) {
    Box(
        modifier = Modifier
            .size(if (big) 42.dp else 36.dp)
            .background(Accent, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Ink, modifier = Modifier.size(22.dp))
    }
    Spacer(modifier = Modifier.width(10.dp))
    Text("Stream", color = Color.White, fontWeight = FontWeight.Black, fontSize = if (big) 24.sp else 22.sp)
    Text("Box", color = Accent, fontWeight = FontWeight.Black, fontSize = if (big) 24.sp else 22.sp)
}

/** Website-style tab: active gets the rounded highlight AND the accent
    underline; the whole tab is a 52dp+ D-pad target. */
@Composable
private fun NavTab(label: String, on: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .height(52.dp)
            .focusRing(RoundedCornerShape(12.dp), pop = false)
            .background(
                if (on) Color(0x2200F0B5) else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            label,
            color = if (on) Color.White else Dim,
            fontWeight = if (on) FontWeight.Black else FontWeight.Bold,
            fontSize = 15.sp,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .width(22.dp)
                .height(3.dp)
                .background(if (on) Accent else Color.Transparent, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun SearchField(input: String, onInput: (String) -> Unit, onSubmit: () -> Unit) {
    OutlinedTextField(
        value = input,
        onValueChange = onInput,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        placeholder = { Text("Search titles", color = Dim) },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent,
            unfocusedBorderColor = Color(0xFF243247),
            focusedContainerColor = Card,
            unfocusedContainerColor = Card,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
    )
}

@Composable
private fun Feed(
    rows: List<Pair<String, List<Title>>>,
    cont: List<Progress>,
    adultOpen: Boolean,
    big: Boolean,
    onOpen: (Title) -> Unit,
    onPlay: (Title) -> Unit,
    onResume: (Progress) -> Unit,
    onRemoveCont: (Progress) -> Unit,
) {
    val hero = rows.firstOrNull()?.second?.firstOrNull { !it.adult || adultOpen }
        ?: rows.firstOrNull()?.second?.firstOrNull()
    // Titles whose real TMDB episode air dates land in the last 7 days ride
    // a NEW EPISODES badge in the TV/Anime areas (the New Episodes row is
    // the same feed - no extra request, nothing invented).
    val newIds: Set<Int> = remember(rows) {
        rows.firstOrNull { it.first == "New Episodes" }?.second?.map { it.id }?.toSet() ?: setOf()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (hero != null) {
            item { Hero(hero, rows.firstOrNull()?.first, big, onPlay, onOpen) }
        }
        if (cont.isNotEmpty()) {
            item {
                ContinueRow(cont, big, onResume, onRemoveCont)
            }
        }
        items(rows, key = { it.first }) { (label, titles) ->
            val shown = titles.filter { !it.adult || adultOpen }
            if (shown.isNotEmpty()) {
                PosterRow(label, shown, newIds, onOpen)
            }
        }
    }
}

@Composable
private fun Hero(t: Title, rowLabel: String?, big: Boolean, onPlay: (Title) -> Unit, onOpen: (Title) -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(if (big) 420.dp else 260.dp)) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(t.backdrop ?: t.poster).crossfade(100).build(),
            contentDescription = t.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(Color(0x330B111E), Color(0xCC0B111E), Ink)),
                )
        )
        // Website hero: a left-side wash so the text block always reads.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(listOf(Color(0xB30B111E), Color(0x000B111E))),
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    horizontal = if (big) 28.dp else 16.dp,
                    vertical = if (big) 18.dp else 12.dp,
                ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Accent, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    "#1 " + (rowLabel?.uppercase() ?: "FEATURED") + " \u2022 " + t.type.uppercase(),
                    color = Ink,
                    fontWeight = FontWeight.Black,
                    fontSize = if (big) 13.sp else 11.sp,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                t.name,
                fontSize = if (big) 44.sp else 28.sp,
                fontWeight = FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = Accent, modifier = Modifier.size(14.dp))
                Text(
                    "  " + String.format("%.1f", t.vote) + "  -  " +
                        (if (t.year > 0) t.year.toString() + "  -  " else "") + t.type.uppercase(),
                    color = Soft,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (big) 15.sp else 13.sp,
                )
            }
            if (t.overview.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    t.overview,
                    color = Soft,
                    fontFamily = BodyFont,
                    fontSize = if (big) 15.sp else 13.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = if (big) 720.dp else 520.dp),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            val heroCtx = LocalContext.current
            var heroList by remember(t.id) { mutableStateOf(Store.inList(heroCtx, t.type, t.id)) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Watch", big, accent = true, icon = Icons.Filled.PlayArrow) { onPlay(t) }
                PillButton("Details", big, icon = Icons.Filled.Info) { onOpen(t) }
                if (big) {
                    // TV hero (design pack): Watch Now | Details | My List.
                    PillButton(
                        if (heroList) "In My List" else "My List",
                        big,
                        icon = if (heroList) Icons.Filled.Check else Icons.Filled.Add,
                    ) {
                        Store.toggleList(heroCtx, t)
                        heroList = Store.inList(heroCtx, t.type, t.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueRow(cont: List<Progress>, big: Boolean, onResume: (Progress) -> Unit, onRemove: (Progress) -> Unit) {
    val frs = remember { HashMap<String, FocusRequester>() }
    var prevCount by remember { mutableIntStateOf(cont.size) }
    // A removed card takes its focus with it - land on a remaining card so
    // the TV remote is never dead after clearing one (sheet-lesson rule).
    LaunchedEffect(cont.size) {
        if (big && cont.size < prevCount && cont.isNotEmpty()) {
            for (p in cont) {
                val fr = frs[p.type + "-" + p.id] ?: continue
                if (runCatching { fr.requestFocus(); true }.getOrDefault(false)) break
            }
        }
        prevCount = cont.size
    }
    Column {
        SectionHeader(
            "Continue Watching",
            right = if (cont.size > 1) cont.size.toString() + " IN PROGRESS" else null,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(cont, key = { it.type + "-" + it.id }) { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Design pack Continue card: wide 16:9 art with the
                    // teal progress bar riding the bottom edge, title + meta
                    // below. The clear X stays a SIBLING outside the card
                    // (remote-safe split) and the ring hugs the card - no
                    // wrapped borders (0.34 bug) - (0.36 design pass).
                    Column(
                        modifier = Modifier
                            .width(if (big) 252.dp else 190.dp)
                            .focusRequester(frs.getOrPut(p.type + "-" + p.id) { FocusRequester() })
                            .focusRing(RoundedCornerShape(14.dp))
                            .clip(RoundedCornerShape(14.dp))
                            .background(Card)
                            .clickable { onResume(p) },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .background(Pill),
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current).data(p.poster).crossfade(100).build(),
                                contentDescription = p.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(listOf(Color(0x000B111E), Color(0x990B111E))),
                                    ),
                            )
                            if (p.dur > 0 && p.cur > 5) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .fillMaxWidth(fraction = (p.cur / p.dur).coerceIn(0.05f, 1f))
                                        .height(4.dp)
                                        .background(Accent),
                                )
                            }
                        }
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text(
                                p.name,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (big) 13.sp else 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                p.type.uppercase() + "  \u2022  S" + p.season + " E" + p.episode,
                                color = Dim,
                                fontSize = 10.sp,
                                maxLines = 1,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                if (p.dur > 0 && p.cur > 5) {
                                    val leftM = maxOf(1, ((p.dur - p.cur) / 60f).toInt())
                                    (p.cur / p.dur * 100).toInt().toString() + "% watched  \u2022  " + leftM + "m left"
                                } else "Not started",
                                color = Accent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(if (big) 44.dp else 34.dp)
                            .focusRingCircle(pop = false)
                            .background(Pill, CircleShape)
                            .clickable { onRemove(p) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove",
                            tint = Color.White,
                            modifier = Modifier.size(if (big) 18.dp else 14.dp),
                        )
                    }
                }
            }
        }
    }
}

/** BROWSE BY GENRE strip (design pack chips): tracked label + pill row,
    All plus the section's real TMDB genres. A pick drives discover rows. */
@Composable
private fun GenreBar(
    genres: List<Pair<String, String>>,
    sel: String?,
    big: Boolean,
    onPick: (String?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "BROWSE BY GENRE",
            color = Dim,
            fontWeight = FontWeight.Bold,
            fontSize = if (big) 10.sp else 9.sp,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
        )
        Spacer(modifier = Modifier.height(6.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { GenreChip("All", sel == null, big) { onPick(null) } }
            items(genres.size) { i ->
                GenreChip(genres[i].second, sel == genres[i].first, big) {
                    onPick(genres[i].first)
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun GenreChip(label: String, on: Boolean, big: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .focusRing(RoundedCornerShape(50), pop = false)
            .background(if (on) Accent else Pill, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = if (big) 16.dp else 12.dp, vertical = if (big) 12.dp else 8.dp),
    ) {
        Text(
            label,
            color = if (on) Ink else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = if (big) 13.sp else 11.sp,
            maxLines = 1,
        )
    }
}

@Composable
fun SectionHeader(label: String, right: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(if (AppEnv.isTv) 22.dp else 18.dp)
                .background(Accent, RoundedCornerShape(2.dp)),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            label,
            fontSize = if (AppEnv.isTv) 21.sp else 17.sp,
            fontWeight = FontWeight.Black,
            color = Color.White,
        )
        if (right != null) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                right,
                color = Dim,
                fontWeight = FontWeight.Bold,
                fontSize = if (AppEnv.isTv) 12.sp else 10.sp,
                letterSpacing = 1.sp,
            )
        }
    }
}

@Composable
private fun PosterRow(label: String, titles: List<Title>, newIds: Set<Int>, onOpen: (Title) -> Unit) {
    Column {
        SectionHeader(label)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(titles, key = { it.type + "-" + it.id }) { t ->
                PosterCard(t, onOpen, newEp = t.type != "movie" && t.id in newIds)
            }
        }
    }
}

@Composable
fun PosterCard(t: Title, onOpen: (Title) -> Unit, newEp: Boolean = false) {
    Column(
        modifier = Modifier.width(if (AppEnv.isTv) 170.dp else 128.dp),
    ) {
        // The ARTWORK is the button: ring, pop and click all hug the poster.
        // The caption below is decoration, never part of the target (the old
        // whole-column box is what made picking feel like a square box).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .focusRing(RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(Card)
                .clickable { onOpen(t) },
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(t.poster).crossfade(100).build(),
                contentDescription = t.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Rating cap (design pack posters): glass chip, real vote only.
            if (t.vote > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color(0xD90B111E), RoundedCornerShape(50))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(10.dp),
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        String.format("%.1f", t.vote),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                    )
                }
            }
            // NEW EPISODES: real TMDB episode air dates inside the last 7
            // days (TV/Anime areas) - so a dropped episode is visible while
            // browsing any row, not only the New Episodes row.
            if (newEp) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Accent, RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        "NEW EPISODES",
                        color = Ink,
                        fontWeight = FontWeight.Black,
                        fontSize = 9.sp,
                        letterSpacing = 0.5.sp,
                    )
                }
            }
        }
        Text(
            t.name,
            fontSize = if (AppEnv.isTv) 13.sp else 12.sp,
            color = Dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun SearchGrid(titles: List<Title>, adultOpen: Boolean, onOpen: (Title) -> Unit) {
    val shown = titles.filter { !it.adult || adultOpen }
    if (shown.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing found", color = Dim)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (AppEnv.isTv) 170.dp else 128.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(shown, key = { it.type + "-" + it.id }) { t -> PosterCard(t, onOpen) }
    }
}

/** My List (design pack): filter chips + list rows on phone; on TV the
    mock's media-card grid with Resume and remove as sibling targets. The
    row/card body opens details, the teal circle plays, the X removes -
    three separate focus targets, remote-safe (0.21 lesson). */
@Composable
private fun MyListView(
    titles: List<Title>,
    cont: List<Progress>,
    adultOpen: Boolean,
    big: Boolean,
    onOpen: (Title) -> Unit,
    onPlay: (Title) -> Unit,
    onRemove: (Title) -> Unit,
) {
    var filter by remember { mutableStateOf("all") }
    // Sort (design pack My List): Recently Added keeps the save order.
    var sort by remember { mutableStateOf("recent") }
    val shown = titles.filter { !it.adult || adultOpen }
    val counts = mapOf(
        "all" to shown.size,
        "movie" to shown.count { it.type == "movie" },
        "tv" to shown.count { it.type == "tv" },
        "anime" to shown.count { it.type == "anime" },
    )
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        ) {
            Text(
                "MY LIST",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = if (big) 22.sp else 18.sp,
                letterSpacing = 1.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .focusRing(RoundedCornerShape(50), pop = false)
                    .background(Pill, RoundedCornerShape(50))
                    .clickable {
                        sort = when (sort) {
                            "recent" -> "az"
                            "az" -> "rating"
                            else -> "recent"
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    "SORT: " + when (sort) {
                        "az" -> "A-Z"
                        "rating" -> "Rating"
                        else -> "Recently Added"
                    },
                    color = Soft,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (big) 11.sp else 10.sp,
                    letterSpacing = 1.sp,
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                shown.size.toString() + " SAVED",
                color = Accent,
                fontWeight = FontWeight.Bold,
                fontSize = if (big) 12.sp else 10.sp,
                letterSpacing = 1.5.sp,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            listOf("all" to "All", "movie" to "Movies", "tv" to "TV Shows", "anime" to "Anime").forEach { (key, label) ->
                val on = filter == key
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .focusRing(RoundedCornerShape(50), pop = false)
                        .background(if (on) Accent else Pill, RoundedCornerShape(50))
                        .clickable { filter = key }
                        .padding(horizontal = if (big) 16.dp else 12.dp, vertical = if (big) 10.dp else 8.dp),
                ) {
                    Text(
                        label,
                        color = if (on) Ink else Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (big) 14.sp else 12.sp,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        (counts[key] ?: 0).toString(),
                        color = if (on) Ink.copy(alpha = 0.7f) else Dim,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (big) 12.sp else 10.sp,
                    )
                }
            }
        }
        val filtered = when (filter) {
            "movie" -> shown.filter { it.type == "movie" }
            "tv" -> shown.filter { it.type == "tv" }
            "anime" -> shown.filter { it.type == "anime" }
            else -> shown
        }.let { base ->
            when (sort) {
                "az" -> base.sortedBy { it.name.lowercase() }
                "rating" -> base.sortedByDescending { it.vote }
                else -> base
            }
        }
        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (shown.isEmpty()) "My List is empty - add titles from any Details screen"
                    else "Nothing in this filter yet",
                    color = Dim,
                    fontFamily = BodyFont,
                )
            }
            return@Column
        }
        if (big) {
            // TV (design pack My List): media-card grid - thumb + chips +
            // progress, then Resume and remove as sibling targets below.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(320.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(filtered, key = { it.type + "-" + it.id }) { t ->
                    val p = cont.firstOrNull { it.type == t.type && it.id == t.id }
                    MyListCard(t, p, onOpen, onPlay, onRemove)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(filtered, key = { it.type + "-" + it.id }) { t ->
                    MyListRow(t, big, onOpen, onPlay, onRemove)
                }
            }
        }
    }
}

/** TV My List media card (design pack): 16:9 art with chips + watch
    progress, title/meta/status, then sibling Resume + remove targets. */
@Composable
private fun MyListCard(
    t: Title,
    p: Progress?,
    onOpen: (Title) -> Unit,
    onPlay: (Title) -> Unit,
    onRemove: (Title) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Card),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .focusRing(RoundedCornerShape(18.dp))
                .clickable { onOpen(t) },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(Pill),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(t.backdrop ?: t.poster).crossfade(100).build(),
                    contentDescription = t.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color(0xE60B111E), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        when (t.type) {
                            "movie" -> "Movie"
                            "anime" -> "Anime"
                            else -> "TV"
                        },
                        color = Accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                    )
                }
                if (t.vote > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color(0xD90B111E), RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = Accent, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            String.format("%.1f", t.vote),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                        )
                    }
                }
                if (p != null && p.dur > 0 && p.cur > 5) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(fraction = (p.cur / p.dur).coerceIn(0.05f, 1f))
                            .height(4.dp)
                            .background(Accent),
                    )
                }
            }
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    t.name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    when {
                        p == null -> "Unwatched"
                        p.dur > 0 && p.cur > 5 -> (p.cur / p.dur * 100).toInt().toString() + "% watched"
                        else -> "S" + p.season + ":E" + p.episode + " ready"
                    },
                    color = if (p != null) Accent else Dim,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .focusRing(RoundedCornerShape(50))
                    .background(Accent, RoundedCornerShape(50))
                    .clickable { onPlay(t) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Play",
                    tint = Ink,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (p != null && p.cur > 5) "Resume" else "Play",
                    color = Ink,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .focusRingCircle()
                    .background(Pill, CircleShape)
                    .clickable { onRemove(t) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun MyListRow(
    t: Title,
    big: Boolean,
    onOpen: (Title) -> Unit,
    onPlay: (Title) -> Unit,
    onRemove: (Title) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Card),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .focusRing(RoundedCornerShape(16.dp))
                .clickable { onOpen(t) }
                .padding(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(if (big) 78.dp else 64.dp)
                    .height(if (big) 110.dp else 92.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Pill),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(t.poster).crossfade(100).build(),
                    contentDescription = t.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .background(Color(0xE60B111E), RoundedCornerShape(50))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        when (t.type) {
                            "movie" -> "Movie"
                            "anime" -> "Anime"
                            else -> "TV"
                        },
                        color = Accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    t.name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (big) 17.sp else 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    listOf(
                        if (t.year > 0) t.year.toString() else "",
                        when (t.type) {
                            "movie" -> "Movie"
                            "anime" -> "Anime"
                            else -> "TV Series"
                        },
                    ).filter { it.isNotEmpty() }.joinToString("  \u2022  "),
                    color = Dim,
                    fontFamily = BodyFont,
                    fontSize = if (big) 12.sp else 11.sp,
                    maxLines = 1,
                )
                if (t.vote > 0) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = Accent,
                            modifier = Modifier.size(if (big) 13.dp else 11.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            String.format("%.1f", t.vote),
                            color = Soft,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (big) 12.sp else 11.sp,
                        )
                    }
                }
            }
        }
        // Play + remove are SIBLING targets beside the row body.
        Box(
            modifier = Modifier
                .size(if (big) 52.dp else 44.dp)
                .focusRingCircle()
                .glow(CircleShape, elevation = 10.dp)
                .background(Accent, CircleShape)
                .clickable { onPlay(t) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = Ink,
                modifier = Modifier.size(if (big) 26.dp else 22.dp),
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .padding(end = if (big) 14.dp else 10.dp)
                .size(if (big) 48.dp else 40.dp)
                .focusRingCircle()
                .background(Pill, CircleShape)
                .clickable { onRemove(t) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove",
                tint = Color.White,
                modifier = Modifier.size(if (big) 20.dp else 16.dp),
            )
        }
    }
}

/** TV shell left rail (design pack android_tv_home): logo + search, the
    five sections as glowing icon buttons, the parental lock at the bottom.
    While the remote is inside the rail it opens a little and shows the
    labels (Home / Movies / TV Shows / Anime / My List), then folds back to
    icon-only - a short hold after the last focus loss keeps it from
    flickering between buttons (0.34 request). */
@Composable
private fun TvRail(
    section: Section,
    onSection: (Section) -> Unit,
    onSearchToggle: () -> Unit,
    adultOpen: Boolean,
    onAdultToggle: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var foldJob by remember { mutableStateOf<Job?>(null) }
    val railW by animateFloatAsState(
        targetValue = if (expanded) 200f else 84f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "railWidth",
    )
    Column(
        horizontalAlignment = Alignment.Start,
        modifier = Modifier
            .width(railW.dp)
            .fillMaxSize()
            .background(Color(0xFF0E1524))
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(Accent, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "StreamBox", tint = Ink, modifier = Modifier.size(26.dp))
        }
        Spacer(modifier = Modifier.height(18.dp))
        val onRailFocus: (Boolean) -> Unit = { focused ->
            foldJob?.cancel()
            if (focused) {
                expanded = true
            } else {
                foldJob = scope.launch {
                    delay(250)
                    expanded = false
                }
            }
        }
        RailBtn(Icons.Filled.Search, "Search", on = false, expanded = expanded, onFocus = onRailFocus, onClick = onSearchToggle)
        Spacer(modifier = Modifier.height(10.dp))
        Section.values().forEach { s ->
            RailBtn(
                s.icon,
                when (s) {
                    Section.TV -> "TV Shows"
                    else -> s.label
                },
                on = s == section,
                expanded = expanded,
                onFocus = onRailFocus,
            ) { onSection(s) }
            Spacer(modifier = Modifier.height(10.dp))
        }
        Spacer(modifier = Modifier.weight(1f))
        RailBtn(
            Icons.Filled.Lock,
            "Adults-Only",
            on = adultOpen,
            expanded = expanded,
            tint = Amber,
            onFocus = onRailFocus,
            onClick = onAdultToggle,
        )
    }
}

@Composable
private fun RailBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    on: Boolean,
    expanded: Boolean,
    tint: Color = if (on) Ink else Dim,
    onFocus: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .onFocusChanged { onFocus(it.isFocused) }
                .focusRingCircle(pop = false)
                .background(if (on) Accent else Color.Transparent, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        }
        if (expanded) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                label,
                color = if (on) Accent else Soft,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
            )
        }
    }
}

/** TV status strip (design pack: device line + clock pill). */
@Composable
private fun TvStatusBar() {
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val fmt = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.US)
        while (true) {
            clock = fmt.format(java.util.Date())
            kotlinx.coroutines.delay(30000)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 24.dp),
    ) {
        Box(modifier = Modifier.size(9.dp).background(Accent, CircleShape))
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            "ANDROID TV",
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 13.sp,
            letterSpacing = 2.sp,
        )
        Spacer(modifier = Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(Pill, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text("CLOCK", color = Dim, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.5.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(clock, color = Color.White, fontWeight = FontWeight.Black, fontSize = 13.sp)
        }
    }
}

/** TV browse legend (design pack 10-foot HUD key caps). */
@Composable
private fun TvLegend() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        KeyChip("OK")
        Text("Select", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.width(12.dp))
        KeyChip("\u25B2  \u25BC")
        Text("Navigate", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.width(12.dp))
        KeyChip("BACK")
        Text("Back", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            "STREAMBOX 10-FOOT UI",
            color = Color(0xFF3B4A63),
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 1.5.sp,
        )
    }
}
