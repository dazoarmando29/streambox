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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.streambox.nativeapp.AppEnv
import com.streambox.nativeapp.api.Api
import com.streambox.nativeapp.api.AnimeIds
import com.streambox.nativeapp.api.Title
import com.streambox.nativeapp.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DetailScreen(
    title: Title,
    onBack: () -> Unit,
    onPlay: (season: Int, episode: Int, mal: Int?, ani: Int?) -> Unit,
) {
    val ctx = LocalContext.current
    val big = AppEnv.isTv

    var season by remember { mutableIntStateOf(1) }
    var episode by remember { mutableIntStateOf(1) }
    var ids by remember { mutableStateOf<AnimeIds?>(null) }
    var mapping by remember { mutableStateOf("none") } // none | ok | missing
    var inList by remember { mutableStateOf(Store.inList(ctx, title.type, title.id)) }

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
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(if (big) 320.dp else 240.dp)) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(title.backdrop ?: title.poster).crossfade(true).build(),
                contentDescription = title.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(listOf(Color(0x660B1426), Color(0xCC0B1426), Ink)),
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    title.name,
                    fontSize = if (big) 32.sp else 26.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = Accent, modifier = Modifier.size(14.dp))
                    Text(
                        "  " + String.format("%.1f", title.vote) + "  -  " +
                            (if (title.year > 0) title.year.toString() + "  -  " else "") + title.type.uppercase(),
                        color = Soft,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        Column(modifier = Modifier.padding(16.dp)) {
            if (title.overview.isNotBlank()) {
                Text(title.overview, color = Soft, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(14.dp))
            }

            if (title.type != "movie") {
                Stepper("Season", season) { season = it.coerceAtLeast(1) }
                Stepper("Episode", episode) { episode = it.coerceAtLeast(1) }
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (title.type == "anime" && mapping != "ok") {
                Text(
                    when (mapping) {
                        "loading" -> "Matching anime id..."
                        else -> "No anime mapping found - AniPM is out; play goes through VidRift."
                    },
                    color = Color(0xFFF6C177),
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    if (title.type == "movie") "Watch Now" else "Play S" + season + " E" + episode,
                    big,
                    accent = true,
                    icon = Icons.Filled.PlayArrow,
                ) {
                    // Missing mapping no longer blocks play: the player
                    // routes anime-without-ids to VidRift (website rule).
                    if (title.type != "anime" || mapping != "loading") {
                        onPlay(season, episode, ids?.mal, ids?.ani)
                    }
                }
                PillButton(
                    if (inList) "In My List" else "My List",
                    big,
                    outline = !inList,
                    icon = if (inList) Icons.Filled.Check else Icons.Filled.Add,
                ) {
                    Store.toggleList(ctx, title)
                    inList = Store.inList(ctx, title.type, title.id)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            PillButton("Back", big) { onBack() }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.width(90.dp))
        PillButton("-", big = false) { onChange(value - 1) }
        Text(
            value.toString(),
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        PillButton("+", big = false) { onChange(value + 1) }
    }
}
