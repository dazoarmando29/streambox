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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streambox.nativeapp.api.Api
import com.streambox.nativeapp.api.Title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Tab(val label: String) {
    MOVIES("Movies"), TV("TV"), ANIME("Anime")
}

@Composable
fun HomeScreen(onOpen: (Title) -> Unit) {
    var tab by remember { mutableStateOf(Tab.MOVIES) }
    var input by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var titles by remember { mutableStateOf(listOf<Title>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(tab, submitted) {
        loading = true
        error = null
        titles = try {
            withContext(Dispatchers.IO) {
                if (submitted.isNotBlank()) {
                    Api.search(submitted, if (tab == Tab.ANIME) "anime" else null)
                } else when (tab) {
                    Tab.MOVIES -> Api.popularMovies()
                    Tab.TV -> Api.popularTv()
                    Tab.ANIME -> Api.anime()
                }
            }
        } catch (e: Exception) {
            error = e.message ?: "load failed"
            listOf()
        }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("StreamBox", fontSize = 22.sp, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.width(12.dp))
            Tab.values().forEach { t ->
                Button(
                    onClick = { tab = t; submitted = "" },
                    modifier = Modifier.focusRing().padding(horizontal = 4.dp),
                ) { Text(t.label) }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search titles") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitted = input }),
        )
        Spacer(modifier = Modifier.height(8.dp))

        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            error != null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { Text("Could not load: " + error) }
            titles.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { Text("Nothing found") }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                contentPadding = PaddingValues(4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(titles, key = { it.type + "-" + it.id }) { t ->
                    PosterCard(t) { onOpen(t) }
                }
            }
        }
    }
}

@Composable
private fun PosterCard(t: Title, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .focusRing()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = t.poster,
            contentDescription = t.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .background(Color(0xFF1B2B45)),
        )
        Text(
            t.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}
