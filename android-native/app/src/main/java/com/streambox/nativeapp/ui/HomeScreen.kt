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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streambox.nativeapp.AppEnv
import com.streambox.nativeapp.api.Api
import com.streambox.nativeapp.api.Title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Tab(val label: String) {
    MOVIES("Movies"), TV("TV"), ANIME("Anime")
}

private val DimText = Color(0xFF8FA3BF)

@Composable
fun HomeScreen(onOpen: (Title) -> Unit) {
    var tab by remember { mutableStateOf(Tab.MOVIES) }
    var input by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf(listOf<Pair<String, List<Title>>>()) }
    var results by remember { mutableStateOf(listOf<Title>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(tab, submitted) {
        loading = true
        error = null
        try {
            withContext(Dispatchers.IO) {
                if (submitted.isNotBlank()) {
                    results = Api.search(submitted, if (tab == Tab.ANIME) "anime" else null)
                    rows = listOf()
                } else {
                    results = listOf()
                    rows = when (tab) {
                        Tab.MOVIES -> listOf(
                            "Trending Now" to Api.trending().filter { it.type == "movie" },
                            "Popular Movies" to Api.popularMovies(),
                            "Top Rated Movies" to Api.topRatedMovies(),
                        )
                        Tab.TV -> listOf(
                            "Trending Now" to Api.trending().filter { it.type != "movie" },
                            "Popular Shows" to Api.popularTv(),
                            "Top Rated Shows" to Api.topRatedTv(),
                        )
                        Tab.ANIME -> listOf(
                            "Popular Anime" to Api.anime(),
                            "Top Rated Anime" to Api.animeTop(),
                        )
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

    Column(modifier = Modifier.fillMaxSize()) {
        TopStrip(
            tab = tab,
            onTab = { tab = it; submitted = ""; input = "" },
            input = input,
            onInput = { input = it },
            onSubmit = { submitted = input },
        )

        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = Color(0xFF14B8A6)) }
            error != null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { Text("Could not load: " + error, color = DimText) }
            submitted.isNotBlank() -> SearchResults(results, onOpen)
            else -> Binge(rows, onOpen)
        }
    }
}

@Composable
private fun TopStrip(
    tab: Tab,
    onTab: (Tab) -> Unit,
    input: String,
    onInput: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("StreamBox", fontSize = 22.sp, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.width(18.dp))
            Tab.values().forEach { t ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .focusRing()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .clickable { onTab(t) },
                ) {
                    Text(
                        t.label,
                        fontSize = 15.sp,
                        fontWeight = if (t == tab) FontWeight.Black else FontWeight.Medium,
                        color = if (t == tab) Color.White else DimText,
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Box(
                        modifier = Modifier
                            .width(22.dp)
                            .height(3.dp)
                            .background(
                                if (t == tab) Color(0xFF14B8A6) else Color.Transparent,
                                RoundedCornerShape(2.dp),
                            )
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = input,
            onValueChange = onInput,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search titles", color = DimText) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF14B8A6),
                unfocusedBorderColor = Color(0xFF24374F),
                focusedContainerColor = Color(0xFF132138),
                unfocusedContainerColor = Color(0xFF132138),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        )
    }
}

/** Netflix bones: billboard hero on top, then horizontal poster rows. */
@Composable
private fun Binge(rows: List<Pair<String, List<Title>>>, onOpen: (Title) -> Unit) {
    val hero = rows.firstOrNull()?.second?.firstOrNull { it.backdrop != null }
        ?: rows.firstOrNull()?.second?.firstOrNull()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (hero != null) {
            item { Hero(hero, onOpen) }
        }
        items(rows, key = { it.first }) { (label, titles) ->
            if (titles.isNotEmpty()) {
                Row(label, titles, onOpen)
            }
        }
    }
}

@Composable
private fun Hero(t: Title, onOpen: (Title) -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(if (AppEnv.isTv) 300.dp else 220.dp)) {
        AsyncImage(
            model = t.backdrop ?: t.poster,
            contentDescription = t.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x000B1426), Color(0x990B1426), Color(0xFF0B1426)),
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
        ) {
            Text(
                t.name,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = { onOpen(t) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF14B8A6),
                    contentColor = Color(0xFF0B1426),
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.focusRing().height(48.dp),
            ) {
                Text("Play", fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun Row(label: String, titles: List<Title>, onOpen: (Title) -> Unit) {
    Column {
        Text(
            label,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
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
            .width(if (AppEnv.isTv) 150.dp else 118.dp)
            .focusRing()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onOpen(t) },
    ) {
        AsyncImage(
            model = t.poster,
            contentDescription = t.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .background(Color(0xFF132138)),
        )
        Text(
            t.name,
            fontSize = 12.sp,
            color = DimText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SearchResults(titles: List<Title>, onOpen: (Title) -> Unit) {
    if (titles.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing found", color = DimText)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (AppEnv.isTv) 150.dp else 118.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(titles, key = { it.type + "-" + it.id }) { t ->
            PosterCard(t, onOpen)
        }
    }
}
