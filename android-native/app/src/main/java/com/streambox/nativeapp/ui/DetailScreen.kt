package com.streambox.nativeapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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

/** Title page in the website's design language: full-bleed backdrop, the
    whole story block over a gradient at the bottom-left, actions on the
    hero, and the season/episode picker as one sleek pill (build 0.15 - the
    old stacked circle steppers and floating text looked broken on TV). */
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

    // Remote/gesture Back works everywhere - no hunting for a button on TV.
    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(if (big) 520.dp else 340.dp)) {
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
                        Brush.verticalGradient(listOf(Color(0x330B111E), Color(0xB30B111E), Ink)),
                    )
            )
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
                        vertical = if (big) 22.dp else 14.dp,
                    ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Accent, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        title.type.uppercase() + (if (title.year > 0) "  \u2022  " + title.year else ""),
                        color = Ink,
                        fontWeight = FontWeight.Black,
                        fontSize = if (big) 13.sp else 11.sp,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    title.name,
                    fontSize = if (big) 48.sp else 30.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
                    Text(
                        "  " + String.format("%.1f", title.vote),
                        color = Soft,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (big) 16.sp else 14.sp,
                    )
                }
                if (title.overview.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        title.overview,
                        color = Soft,
                        fontFamily = BodyFont,
                        fontSize = if (big) 16.sp else 13.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = if (big) 860.dp else 560.dp),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        icon = if (inList) Icons.Filled.Check else Icons.Filled.Add,
                    ) {
                        Store.toggleList(ctx, title)
                        inList = Store.inList(ctx, title.type, title.id)
                    }
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = if (big) 28.dp else 16.dp, vertical = 16.dp)) {
            if (title.type == "anime" && mapping != "ok") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color(0x33F5A623), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        when (mapping) {
                            "loading" -> "Matching anime id..."
                            else -> "No anime mapping - play goes through VidRift."
                        },
                        color = Amber,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (title.type != "movie") {
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
                Spacer(modifier = Modifier.height(12.dp))
            }

            PillButton("Back", big) { onBack() }
        }
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
