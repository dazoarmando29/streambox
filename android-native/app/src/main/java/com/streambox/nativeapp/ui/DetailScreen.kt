package com.streambox.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streambox.nativeapp.AppEnv
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
    var mapping by remember { mutableStateOf("none") } // none | ok | missing

    LaunchedEffect(title) {
        if (title.type == "anime") {
            mapping = "loading"
            val mapped = withContext(Dispatchers.IO) { Api.animeMap(title.name, title.year) }
            ids = mapped
            mapping = if (mapped != null && (mapped.mal != null || mapped.ani != null)) "ok" else "missing"
        }
    }

    val big = AppEnv.isTv
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(if (big) 300.dp else 220.dp)) {
            AsyncImage(
                model = title.backdrop ?: title.poster,
                contentDescription = title.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0x660B1426), Color(0xE60B1426), Color(0xFF0B1426)),
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            ) {
                Text(
                    title.name,
                    fontSize = if (big) 30.sp else 24.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (title.year > 0 || title.vote > 0) {
                    Text(
                        listOf(
                            if (title.year > 0) title.year.toString() else "",
                            if (title.vote > 0) String.format("%.1f", title.vote) else "",
                        ).filter { it.isNotEmpty() }.joinToString("  -  "),
                        color = Color(0xFFB9C7DA),
                    )
                }
            }
        }

        Column(modifier = Modifier.padding(16.dp)) {
            if (title.overview.isNotBlank()) {
                Text(title.overview, color = Color(0xFFC6D3E5))
                Spacer(modifier = Modifier.height(14.dp))
            }

            if (title.type != "movie") {
                Stepper("Season", season) { season = it.coerceAtLeast(1) }
                Stepper("Episode", episode) { episode = it.coerceAtLeast(1) }
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (title.type == "anime" && mapping != "ok") {
                Text(
                    when (mapping) {
                        "loading" -> "Matching anime id..."
                        else -> "No anime mapping found - cannot start AniPM for this title."
                    },
                    color = Color(0xFFF6C177),
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onPlay(season, episode, ids?.mal, ids?.ani) },
                    enabled = title.type != "anime" || mapping == "ok",
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF14B8A6),
                        contentColor = Color(0xFF0B1426),
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.focusRing().height(if (big) 56.dp else 48.dp),
                ) {
                    Text(
                        if (title.type == "movie") "Play" else "Play S" + season + " E" + episode,
                        fontWeight = FontWeight.Black,
                        fontSize = if (big) 18.sp else 15.sp,
                    )
                }
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1D2C44),
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.focusRing().height(if (big) 56.dp else 48.dp),
                ) {
                    Text("Back", fontWeight = FontWeight.Bold, fontSize = if (big) 18.sp else 15.sp)
                }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontWeight = FontWeight.Bold, modifier = Modifier.width(90.dp))
        Button(
            onClick = { onChange(value - 1) },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D2C44)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.focusRing().height(48.dp),
        ) { Text("-", fontSize = 18.sp) }
        Text(
            value.toString(),
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Button(
            onClick = { onChange(value + 1) },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D2C44)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.focusRing().height(48.dp),
        ) { Text("+", fontSize = 18.sp) }
    }
}
