package com.streambox.nativeapp.ui

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    LaunchedEffect(section, submitted, searchMode) {
        cont = Store.progressList(ctx)
        myList = Store.listItems(ctx)
        loading = true
        error = null
        try {
            withContext(Dispatchers.IO) {
                if (searchMode && submitted.isNotBlank()) {
                    results = Api.search(submitted, if (section == Section.ANIME) "anime" else null)
                    rows = listOf()
                } else {
                    results = listOf()
                    rows = when (section) {
                        Section.HOME -> listOf(
                            "Trending Now" to Api.trending(),
                            "Popular Movies" to Api.popularMovies(),
                            "Popular Shows" to Api.popularTv(),
                            "Top Rated Movies" to Api.topRatedMovies(),
                            "Top Rated Shows" to Api.topRatedTv(),
                        )
                        Section.MOVIES -> listOf(
                            "Trending Movies" to Api.trending().filter { it.type == "movie" },
                            "Popular Movies" to Api.popularMovies(),
                            "Top Rated Movies" to Api.topRatedMovies(),
                        )
                        Section.TV -> listOf(
                            "Trending Shows" to Api.trending().filter { it.type != "movie" },
                            "Popular Shows" to Api.popularTv(),
                            "Top Rated Shows" to Api.topRatedTv(),
                        )
                        Section.ANIME -> listOf(
                            "Popular Anime" to Api.anime(),
                            "Top Rated Anime" to Api.animeTop(),
                        )
                        Section.MYLIST -> listOf()
                    }
                }
            }
        } catch (e: Exception) {
            error = e.message ?: "load failed"
            rows = listOf()
            results = listOf()
        }
        loading = false
    }

    Column(modifier = modifier.fillMaxSize()) {
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

        if (searchMode) {
            SearchField(
                input = input,
                onInput = { input = it },
                onSubmit = { submitted = input },
            )
        }

        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = Accent) }
            error != null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { Text("Could not load: " + error, color = Dim) }
            searchMode && submitted.isNotBlank() -> SearchGrid(results, adultOpen, onOpen)
            section == Section.MYLIST -> MyListGrid(myList, adultOpen, onOpen)
            else -> Feed(rows, cont, adultOpen, big, onOpen, onPlay, onResume,
                onRemoveCont = { p ->
                    Store.removeProgress(ctx, p.type, p.id)
                    cont = Store.progressList(ctx)
                })
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
            .background(if (big) Color(0xFF0E1B2E) else Ink)
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
                .focusRing(CircleShape)
                .background(Pill, CircleShape)
                .clickable { onAdultToggle() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = "Adults-only gate",
                tint = if (adultOpen) Accent else Color(0xFFF6C177),
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
            .focusRing(RoundedCornerShape(12.dp))
            .background(
                if (on) Color(0x2214B8A6) else Color.Transparent,
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
            unfocusedBorderColor = Color(0xFF24374F),
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
                PosterRow(label, shown, onOpen)
            }
        }
    }
}

@Composable
private fun Hero(t: Title, rowLabel: String?, big: Boolean, onPlay: (Title) -> Unit, onOpen: (Title) -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(if (big) 420.dp else 260.dp)) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(t.backdrop ?: t.poster).crossfade(true).build(),
            contentDescription = t.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(Color(0x330B1426), Color(0xCC0B1426), Ink)),
                )
        )
        // Website hero: a left-side wash so the text block always reads.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(listOf(Color(0xB30B1426), Color(0x000B1426))),
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
                    fontSize = if (big) 15.sp else 13.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = if (big) 720.dp else 520.dp),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Watch", big, accent = true, icon = Icons.Filled.PlayArrow) { onPlay(t) }
                PillButton("Details", big, icon = Icons.Filled.Info) { onOpen(t) }
            }
        }
    }
}

@Composable
private fun ContinueRow(cont: List<Progress>, big: Boolean, onResume: (Progress) -> Unit, onRemove: (Progress) -> Unit) {
    Column {
        SectionHeader("Continue Watching")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(cont, key = { it.type + "-" + it.id }) { p ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .width(if (big) 320.dp else 250.dp)
                        .focusRing(RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .background(Card)
                        .clickable { onResume(p) }
                        .padding(8.dp),
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(p.poster).crossfade(true).build(),
                        contentDescription = p.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(64.dp)
                            .height(90.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Pill),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            p.name,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            p.type.uppercase() + "  S" + p.season + " E" + p.episode,
                            color = Dim,
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                        Text(
                            if (p.dur > 0 && p.cur > 5) (p.cur / p.dur * 100).toInt().toString() + "% watched"
                            else "Not started",
                            color = Accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .focusRing(CircleShape)
                            .background(Pill, CircleShape)
                            .clickable { onRemove(p) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
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
    }
}

@Composable
private fun PosterRow(label: String, titles: List<Title>, onOpen: (Title) -> Unit) {
    Column {
        SectionHeader(label)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(titles, key = { it.type + "-" + it.id }) { t ->
                PosterCard(t, onOpen)
            }
        }
    }
}

@Composable
private fun PosterCard(t: Title, onOpen: (Title) -> Unit) {
    Column(
        modifier = Modifier
            .width(if (AppEnv.isTv) 170.dp else 128.dp)
            .focusRing(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable { onOpen(t) },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(t.poster).crossfade(true).build(),
            contentDescription = t.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .background(Card),
        )
        Text(
            t.name,
            fontSize = 12.sp,
            color = Dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
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

@Composable
private fun MyListGrid(titles: List<Title>, adultOpen: Boolean, onOpen: (Title) -> Unit) {
    val shown = titles.filter { !it.adult || adultOpen }
    if (shown.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("My List is empty - add titles from any Details screen", color = Dim)
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
