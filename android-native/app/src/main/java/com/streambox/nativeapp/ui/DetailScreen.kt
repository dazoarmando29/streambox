package com.streambox.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streambox.nativeapp.api.Api
import com.streambox.nativeapp.api.AnimeIds
import com.streambox.nativeapp.api.Title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DetailScreen(
    title: Title,
    onBack: () -> Unit,
    onPlay: (season: Int, episode: Int, mal: Int?, ani: Int?) -> Unit,
) {
    var season by remember { mutableIntStateOf(1) }
    var episode by remember { mutableIntStateOf(1) }
    var ids by remember { mutableStateOf<AnimeIds?>(null) }
    var mapping by remember { mutableStateOf("none") } // none | ok | missing | failed

    LaunchedEffect(title) {
        if (title.type == "anime") {
            mapping = "loading"
            val mapped = withContext(Dispatchers.IO) { Api.animeMap(title.name, title.year) }
            ids = mapped
            mapping = if (mapped != null && (mapped.mal != null || mapped.ani != null)) "ok" else "missing"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Button(onClick = onBack, modifier = Modifier.focusRing()) { Text("Back") }
        Spacer(modifier = Modifier.height(12.dp))
        Row {
            AsyncImage(
                model = title.poster,
                contentDescription = title.name,
                modifier = Modifier
                    .width(140.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1B2B45)),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(title.name, fontSize = 22.sp, fontWeight = FontWeight.Black)
                if (title.year > 0 || title.vote > 0) {
                    Text(
                        listOf(
                            if (title.year > 0) title.year.toString() else "",
                            if (title.vote > 0) String.format("%.1f", title.vote) else "",
                        ).filter { it.isNotEmpty() }.joinToString("  -  "),
                        color = Color(0xFF9DB2CC),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (title.overview.isNotBlank()) {
            Text(title.overview, color = Color(0xFFC6D3E5))
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (title.type != "movie") {
            Stepper("Season", season) { season = it.coerceAtLeast(1) }
            Stepper("Episode", episode) { episode = it.coerceAtLeast(1) }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (title.type == "anime" && mapping != "ok") {
            Text(
                when (mapping) {
                    "loading" -> "Matching anime id..."
                    "missing" -> "No anime mapping found - cannot start AniPM for this title."
                    else -> "Mapping lookup failed - check connection and retry from home."
                },
                color = Color(0xFFF6C177),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Button(
            onClick = { onPlay(season, episode, ids?.mal, ids?.ani) },
            enabled = title.type != "anime" || mapping == "ok",
            modifier = Modifier.focusRing(),
        ) {
            Text(if (title.type == "movie") "Play" else "Play S" + season + " E" + episode)
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontWeight = FontWeight.Bold, modifier = Modifier.width(90.dp))
        Button(
            onClick = { onChange(value - 1) },
            modifier = Modifier.focusRing(),
        ) { Text("-") }
        Text(
            value.toString(),
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Button(
            onClick = { onChange(value + 1) },
            modifier = Modifier.focusRing(),
        ) { Text("+") }
    }
}
