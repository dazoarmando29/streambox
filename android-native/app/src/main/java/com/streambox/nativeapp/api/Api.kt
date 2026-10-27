package com.streambox.nativeapp.api

import com.streambox.nativeapp.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Title(
    val id: Int,
    val type: String, // "movie" | "tv" | "anime"
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val year: Int,
    val overview: String,
    val vote: Double,
    val adult: Boolean = false,
    val genreIds: List<Int> = listOf(),
    val lang: String = "",
) {
    /** Anime detection for row routing: Japanese animation. TMDB list and
        discover payloads carry genre_ids + original_language. */
    val isAnime: Boolean
        get() = 16 in genreIds && lang == "ja"
}

data class AnimeIds(val mal: Int?, val ani: Int?, val year: Int = 0)

/** One episode of a TV/anime season (real TMDB fields only - a missing
    still or runtime is normal, especially on long-running anime). aired
    compares the real air date against today: future episodes render as
    Coming Soon instead of a dead play target. */
data class Ep(
    val n: Int,
    val name: String,
    val still: String?,
    val runtime: Int,
    val year: Int,
    val overview: String,
    val aired: Boolean = true,
    val vote: Double = 0.0,
    val air: String = "",
)

/** One cast member (detail rail): name + role + portrait when TMDB has it. */
data class CastMem(val name: String, val role: String, val photo: String?)

/** One season in the Season Directory (real TMDB counts). air is the
    season's first air date - a future date means the whole season is a
    Coming Soon schedule entry, never a play target. */
data class SeasonInfo(val n: Int, val count: Int, val name: String, val air: String = "")

/** One crew member (Creator & Showrunner card): name + real TMDB job. */
data class CrewMem(val name: String, val job: String)

/** One TMDB video (trailer / extra clip, YouTube keys only). */
data class VideoInfo(val key: String, val name: String, val kind: String)

/** Everything the Series Episodes design needs from TMDB details. aired is
    false only when the real release/air date is in the future (Avengers:
    Doomsday case) - then the UI says Coming Soon instead of Play. */
data class TDetail(
    val genres: List<String>,
    val runtime: Int,
    val seasons: List<SeasonInfo>,
    val studio: String,
    val langs: String,
    val cert: String,
    val videos: List<VideoInfo>,
    val aired: Boolean = true,
    /** TMDB original_language (0.68): heals titles whose stored lang is
        blank (legacy Continue Watching records) so Korean dramas still
        route to VidBolt and the Tagalog dub. */
    val originalLanguage: String = "",
    /** Title identity (0.77): the drop-gate routes anime seasons through
        the same entry rule as the player and needs the name + year. */
    val name: String = "",
    val year: Int = 0,
)

/** Client for the streambox-api Worker: TMDB catalog (key stays server-side)
    and TMDB->MAL/AniList mapping (Jikan/AniList woes solved server-side). */
object Api {
    private const val BASE = BuildConfig.API_BASE
    private const val IMG = "https://image.tmdb.org/t/p/w342"
    private const val IMG_W780 = "https://image.tmdb.org/t/p/w780"
    private const val IMG_W300 = "https://image.tmdb.org/t/p/w300"
    private const val IMG_W185 = "https://image.tmdb.org/t/p/w185"

    /** Short-lived response cache (0.52 speed pass): re-opening a detail or
        re-browsing a section inside two minutes never pays the network
        again. Catalog data is stable enough for a 120s window. */
    private data class CachedResp(val at: Long, val json: JSONObject)

    private val respCache = java.util.Collections.synchronizedMap(HashMap<String, CachedResp>())
    private const val RESP_TTL_MS = 120_000L

    private fun get(path: String): JSONObject {
        val now = System.currentTimeMillis()
        respCache[path]?.let { if (now - it.at < RESP_TTL_MS) return it.json }
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
        conn.setRequestProperty("accept", "application/json")
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("http_" + code)
            val obj = JSONObject(text)
            respCache[path] = CachedResp(now, obj)
            if (respCache.size > 96) {
                val oldest = respCache.entries.minByOrNull { it.value.at }?.key
                if (oldest != null) respCache.remove(oldest)
            }
            return obj
        } finally {
            conn.disconnect()
        }
    }

    private fun img(o: JSONObject, key: String, base: String): String? {
        val p = o.optString(key, "")
        return if (p.isNullOrEmpty() || p == "null") null else base + p
    }

    /** JSON date string that is BLANK when TMDB says null. org.json's
        optString renders JSON null as the literal "null", which sorts AFTER
        today's date - that stamped old One Piece seasons as COMING SOON
        (0.38 fix). Every date read goes through here. */
    private fun dateOf(o: JSONObject, key: String): String {
        val v = o.optString(key, "")
        return if (v.isEmpty() || v == "null") "" else v
    }

    private fun yearOf(o: JSONObject): Int {
        val d = dateOf(o, "release_date").ifEmpty { dateOf(o, "first_air_date") }
        return d.take(4).toIntOrNull() ?: 0
    }

    private fun titleOf(o: JSONObject): String =
        o.optString("title", "").ifEmpty { o.optString("name", "") }

    /** Today as ISO date - aired/Coming Soon gates compare real TMDB dates. */
    private fun today(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    private fun titlesFrom(arr: JSONArray, typeOverride: String?): List<Title> {
        val out = ArrayList<Title>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("id", 0)
            if (id == 0) continue
            val mediaType = o.optString("media_type", "")
            val gArr = o.optJSONArray("genre_ids")
            val genIds = ArrayList<Int>()
            if (gArr != null) {
                for (g in 0 until gArr.length()) {
                    val gid = gArr.optInt(g, -1)
                    if (gid >= 0) genIds.add(gid)
                }
            }
            val lang = o.optString("original_language", "")
            val isAnime = 16 in genIds && lang == "ja"
            // Content typing (0.48/0.55): Japanese animation is ANIME
            // EVERYWHERE - the label always wins over any row override, so
            // home's Popular Shows can no longer stamp an anime as TV ('it
            // reads as tv') or route it to the TV default player.
            val content = if (o.has("title") && !o.has("name")) "movie"
                else if (mediaType == "movie") "movie"
                else if (isAnime) "anime" else "tv"
            val type = if (content == "anime") "anime" else (typeOverride ?: content)
            out.add(
                Title(
                    id = id,
                    type = type,
                    name = titleOf(o),
                    poster = img(o, "poster_path", IMG),
                    backdrop = img(o, "backdrop_path", IMG_W780),
                    year = yearOf(o),
                    overview = o.optString("overview", ""),
                    vote = o.optDouble("vote_average", 0.0),
                    adult = o.optBoolean("adult", false),
                    genreIds = genIds,
                    lang = lang,
                )
            )
        }
        return out
    }

    fun trending(): List<Title> =
        titlesFrom(get("/api/tmdb/3/trending/all/week").getJSONArray("results"), null)

    fun popularMovies(): List<Title> =
        titlesFrom(get("/api/tmdb/3/movie/popular").getJSONArray("results"), "movie")

    fun topRatedMovies(): List<Title> =
        titlesFrom(get("/api/tmdb/3/movie/top_rated").getJSONArray("results"), "movie")

    fun popularTv(): List<Title> =
        titlesFrom(get("/api/tmdb/3/tv/popular").getJSONArray("results"), "tv")

    fun topRatedTv(): List<Title> =
        titlesFrom(get("/api/tmdb/3/tv/top_rated").getJSONArray("results"), "tv")

    fun anime(): List<Title> =
        titlesFrom(
            get("/api/tmdb/3/discover/tv?with_genres=16&with_original_language=ja&sort_by=popularity.desc")
                .getJSONArray("results"),
            "anime"
        )

    fun animeTop(): List<Title> =
        titlesFrom(
            get("/api/tmdb/3/discover/tv?with_genres=16&with_original_language=ja&sort_by=vote_average.desc&vote_count.gte=80")
                .getJSONArray("results"),
            "anime"
        )

    /** Shows with at least one EPISODE that aired in the last 7 days (real
        TMDB episode air dates - discover's air_date filter looks across all
        episodes, so long-runners like Doraemon qualify the week they drop).
        Drives the New Episodes row + badges in the TV/Anime areas. */
    fun newEpisodes(anime: Boolean): List<Title> {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val cal = java.util.Calendar.getInstance()
        val end = fmt.format(cal.time)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -7)
        val start = fmt.format(cal.time)
        return titlesFrom(
            get(
                "/api/tmdb/3/discover/tv?air_date.gte=" + start + "&air_date.lte=" + end +
                    "&sort_by=popularity.desc" +
                    (if (anime) "&with_genres=16&with_original_language=ja" else "")
            ).getJSONArray("results"),
            if (anime) "anime" else "tv"
        )
    }

    fun search(query: String, typeOverride: String?): List<Title> =
        titlesFrom(
            get("/api/tmdb/3/search/multi?query=" + java.net.URLEncoder.encode(query, "UTF-8"))
                .getJSONArray("results"),
            typeOverride
        )

    private val genreCache = java.util.Collections.synchronizedMap(HashMap<String, List<Pair<Int, String>>>())

    /** AniList genre taxonomy for the Anime area (Romance, Slice of Life,
        Mecha, ... - TMDB's TV genres have no Romance at all). Real AniList
        Genre enum names served by the Worker (0.40). */
    fun animeGenres(): List<Pair<String, String>> {
        return try {
            val arr = get("/api/anime/genres").optJSONArray("data") ?: return listOf()
            val out = ArrayList<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                val name = o.optString("name", "")
                if (id.isNotBlank() && id != "null" && name.isNotBlank() && name != "null") out.add(id to name)
            }
            out
        } catch (e: Exception) {
            listOf()
        }
    }

    /** AniList-genre browse rows: the Worker bridges AniList (Kitsu
        fallback) and resolves each title to its TMDB id, so every row is a
        normal playable Title and nothing is ever invented. */
    fun animeGenreBrowse(genre: String, top: Boolean): List<Title> {
        return try {
            val arr = get(
                "/api/anime/browse?genre=" + java.net.URLEncoder.encode(genre, "UTF-8") +
                    "&top=" + (if (top) "1" else "0")
            ).optJSONArray("data") ?: return listOf()
            val out = ArrayList<Title>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optInt("id", 0)
                if (id == 0) continue
                val poster = o.optString("poster", "")
                val backdrop = o.optString("backdrop", "")
                out.add(
                    Title(
                        id = id,
                        type = "anime",
                        name = o.optString("name", ""),
                        poster = if (poster.isBlank() || poster == "null") null else poster,
                        backdrop = if (backdrop.isBlank() || backdrop == "null") null else backdrop,
                        year = o.optInt("year", 0),
                        overview = o.optString("overview", ""),
                        vote = o.optDouble("vote", 0.0),
                        adult = o.optBoolean("adult", false),
                        genreIds = listOf(16),
                        lang = "ja",
                    )
                )
            }
            out
        } catch (e: Exception) {
            listOf()
        }
    }

    /** Real TMDB genre names (/genre/movie|tv/list) for the BROWSE BY GENRE
        chips - cached for the session. Failures are NEVER cached (0.46: one
        cold timeout froze the strip at All only) and get one retry. */
    /** Browse-tab page (0.55): one page of the FULL catalog for a kind
        (movie / tv / anime) with optional genre + year filters, popularity
        order. Returns the titles plus whether another page exists. The TV
        kind excludes animation (anime lives in its own kind); the anime
        kind is Japanese animation (genre 16 + ja).
        GENRE_ANOTHER_WORLD (0.73) is the one VIRTUAL genre chip - isekai /
        reincarnation / another-world stories ride TMDB KEYWORDS (real ids,
        keyword namespace never collides with TMDB genre ids) instead of
        with_genres. */
    const val GENRE_ANOTHER_WORLD = 237451
    const val ANOTHER_WORLD_NAME = "Another World"
    private const val ISEKAI_KEYWORDS = "237451|291482|5484|213756|227624|33465"

    fun browsePage(kind: String, genre: Int?, year: Int?, page: Int): Pair<List<Title>, Boolean> {
        val q = StringBuilder()
        val kw = if (genre == GENRE_ANOTHER_WORLD) "&with_keywords=" + ISEKAI_KEYWORDS else ""
        val g = if (genre == GENRE_ANOTHER_WORLD) null else genre
        when (kind) {
            "movie" -> {
                q.append("/api/tmdb/3/discover/movie?include_adult=false&sort_by=popularity.desc")
                if (g != null) q.append("&with_genres=").append(g)
                q.append(kw)
                if (year != null) q.append("&primary_release_year=").append(year)
            }
            "anime" -> {
                q.append("/api/tmdb/3/discover/tv?include_adult=false&sort_by=popularity.desc")
                q.append("&with_genres=").append(if (g != null) "16," + g else "16")
                q.append("&with_original_language=ja")
                q.append(kw)
                if (year != null) q.append("&first_air_date_year=").append(year)
            }
            "kdrama" -> {
                // Korean drama (0.56): Korean-language TV - every card
                // keeps lang "ko" so it routes to VidBolt's multi-audio
                // releases.
                q.append("/api/tmdb/3/discover/tv?include_adult=false&sort_by=popularity.desc&without_genres=16")
                q.append("&with_original_language=ko")
                if (g != null) q.append("&with_genres=").append(g)
                q.append(kw)
                if (year != null) q.append("&first_air_date_year=").append(year)
            }
            else -> {
                q.append("/api/tmdb/3/discover/tv?include_adult=false&sort_by=popularity.desc&without_genres=16")
                if (g != null) q.append("&with_genres=").append(g)
                q.append(kw)
                if (year != null) q.append("&first_air_date_year=").append(year)
            }
        }
        q.append("&page=").append(page)
        val o = get(q.toString())
        val titles = titlesFrom(o.optJSONArray("results") ?: JSONArray(), if (kind == "movie") "movie" else null)
        val total = o.optInt("total_pages", 0)
        return titles to (titles.isNotEmpty() && (total == 0 || page < total))
    }

    fun genres(kind: String): List<Pair<Int, String>> {
        val k = if (kind == "movie") "movie" else "tv"
        genreCache[k]?.let { return it }
        repeat(2) {
            val out = ArrayList<Pair<Int, String>>()
            try {
                val arr = get("/api/tmdb/3/genre/" + k + "/list").optJSONArray("genres")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optInt("id", -1)
                        val name = o.optString("name", "")
                        if (id >= 0 && name.isNotBlank() && name != "null") out.add(id to name)
                    }
                }
            } catch (e: Exception) {
            }
            if (out.isNotEmpty()) {
                genreCache[k] = out
                return out
            }
        }
        return listOf()
    }

    /** Genre browse rows (real discover data): popular + top-rated picks
        for one genre. topRated carries the vote_count floor so 10.0-with-
        1-vote noise can never top the row. */
    fun genreMovies(genreId: Int, topRated: Boolean): List<Title> =
        titlesFrom(
            get(
                "/api/tmdb/3/discover/movie?with_genres=" + genreId +
                    (if (topRated) "&sort_by=vote_average.desc&vote_count.gte=80" else "&sort_by=popularity.desc")
            ).getJSONArray("results"),
            "movie"
        )

    /** Genre browse rows for shows; anime narrows to Japanese animation
        (genre 16 AND the genre pick, comma = AND in discover). */
    fun genreTv(genreId: Int, topRated: Boolean, anime: Boolean): List<Title> {
        val g = if (anime) (if (genreId == 16) "16" else "16," + genreId) else genreId.toString()
        return titlesFrom(
            get(
                "/api/tmdb/3/discover/tv?with_genres=" + g +
                    (if (anime) "&with_original_language=ja" else "") +
                    (if (topRated) "&sort_by=vote_average.desc&vote_count.gte=80" else "&sort_by=popularity.desc")
            ).getJSONArray("results"),
            if (anime) "anime" else "tv"
        )
    }

    /** K-Drama rows (0.42): Korean-language TV from discover, optionally
        narrowed to one genre so the listing shows the different KINDS of
        kdramas (mystery, comedy, action, ...). Rows keep lang "ko" so they
        route to VidBolt. */
    fun kdramas(genreId: Int?, top: Boolean): List<Title> =
        titlesFrom(
            get(
                "/api/tmdb/3/discover/tv?with_original_language=ko" +
                    (genreId?.let { "&with_genres=" + it } ?: "") +
                    (if (top) "&sort_by=vote_average.desc&vote_count.gte=80" else "&sort_by=popularity.desc")
            ).getJSONArray("results"),
            "tv"
        )

    /** TMDB -> IMDb id (SkipDB is keyed by IMDb). Anime rows are TMDB tv;
        the other kind is tried once for anime movies / mixed results. */
    fun imdbId(type: String, id: Int): String? {
        fun pick(kind: String): String? {
            val v = get("/api/tmdb/3/" + kind + "/" + id + "/external_ids").optString("imdb_id", "")
            return if (v.isNullOrEmpty() || v == "null") null else v
        }
        return try {
            val first = if (type == "movie") "movie" else "tv"
            val other = if (first == "tv") "movie" else "tv"
            runCatching { pick(first) }.getOrNull() ?: runCatching { pick(other) }.getOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /** Season episodes (detail page grid): TMDB /tv/{id}/season/{n} via the
        Worker's TMDB proxy. Lazy UI handles 1,400+ episode seasons. */
    fun season(id: Int, season: Int): List<Ep> {
        val arr = get("/api/tmdb/3/tv/" + id + "/season/" + season).optJSONArray("episodes")
            ?: return listOf()
        val out = ArrayList<Ep>()
        val today = today()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optInt("episode_number", 0)
            if (n <= 0) continue
            val d = dateOf(o, "air_date")
            out.add(
                Ep(
                    n = n,
                    name = o.optString("name", ""),
                    still = img(o, "still_path", IMG_W300),
                    runtime = o.optInt("runtime", 0),
                    year = d.take(4).toIntOrNull() ?: 0,
                    overview = o.optString("overview", ""),
                    aired = d.isBlank() || d.take(10) <= today,
                    vote = o.optDouble("vote_average", 0.0),
                    air = d,
                )
            )
        }
        return out
    }

    /** AniPM drop truth (0.77): an anime episode is OUT when the provider
        actually has it. The exact embed route the player navigates to
        answers HEAD 200 when the episode exists and 404 before the drop
        (probed live 2026-10-24: DBZ ep 291 = 200, 292 = 404 - Dalvik/Chrome
        UAs both fine). Metadata only - the STATUS CODE of a page we already
        embed, never a stream URL. "Drop" answers are cached 30 min, "not
        yet" answers 90 s so a drop shows up quickly. */
    private val aniPmDrop =
        java.util.Collections.synchronizedMap(HashMap<String, Pair<Boolean, Long>>())

    fun aniPmHas(mal: Int?, aep: Int): Boolean? {
        if (mal == null || mal <= 0 || aep <= 0) return null
        val key = mal.toString() + ":" + aep
        val hit = aniPmDrop[key]
        if (hit != null) {
            val ttl = if (hit.first) 1800000L else 90000L
            if (System.currentTimeMillis() - hit.second < ttl) return hit.first
        }
        return try {
            val conn = URL("https://ani.pm/embed/mal/" + mal + "/" + aep + "/sub")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val code = try {
                conn.responseCode
            } finally {
                conn.disconnect()
            }
            val out = when (code) {
                in 200..299 -> true
                404 -> false
                else -> null
            }
            if (out != null) aniPmDrop[key] = out to System.currentTimeMillis()
            out
        } catch (e: Exception) {
            null
        }
    }

    /** Anime entry routing (the 0.60 rule, shared): split franchises carry
        one ENTRY PER SEASON (by air year) and combined entries number
        absolutely (epBase = episodes before this season). The split
        decision compares the AIR-YEAR entry against the TITLE-YEAR entry
        (0.79) - NEVER against the carried id: Continue Watching stores
        ROUTED ids (0.77), so a resumed season already carries the season's
        own entry and the old did-the-entry-change test flipped its answer,
        kept epBase cumulative, and S2E1 went out as episode 13 of a
        12-episode entry (the provider Episode not available page). */
    data class AnimeEntry(val mal: Int?, val ani: Int?, val epBase: Int)

    fun animeEntry(
        name: String,
        titleYear: Int,
        seasons: List<SeasonInfo>,
        seasonNo: Int,
        titleMal: Int?,
        titleAni: Int?,
    ): AnimeEntry {
        var mal = titleMal
        var ani = titleAni
        var base = seasons.filter { it.n in 1 until seasonNo }.sumOf { it.count }
        val airYear = seasons.firstOrNull { it.n == seasonNo }?.air?.take(4)?.toIntOrNull() ?: 0
        if (seasonNo >= 2 && airYear > 0 && airYear != titleYear) {
            val mapped = runCatching { animeMap(name, airYear) }.getOrNull()
            if (mapped != null && (mapped.mal != null || mapped.ani != null)) {
                mal = mapped.mal ?: mal
                ani = mapped.ani ?: ani
                // Split = the air-year lookup resolved a DIFFERENT entry
                // than the title's own (one entry per season, episodes from
                // 1). Same entry = combined, absolute numbering. The entry's
                // own year is the tie-breaker when the title-year lookup fails.
                val first = runCatching { animeMap(name, titleYear) }.getOrNull()
                val split = if (first != null && (first.mal != null || first.ani != null)) {
                    (mapped.mal ?: mapped.ani) != (first.mal ?: first.ani)
                } else {
                    mapped.year > 0 && titleYear > 0 && mapped.year != titleYear
                }
                if (split) base = 0
            }
        }
        return AnimeEntry(mal, ani, base)
    }

    /** Gate anime episodes on the AniPM drop (0.77) - "the release on AniPM
        is the priority". Boundary episodes (blank dates, the past week, the
        future) start LOCKED and unlock only when the provider answers 200,
        so a row can never look playable and then 404. An early drop on
        AniPM unlocks EARLY (JP-first reality). When the probe is
        unreachable the flip falls back to the day AFTER the air date - the
        old 00:00-of-the-date flip (the PH idea) released up to a day
        before the drop. Non-anime passes through untouched. */
    fun gateAnimeEpisodes(t: Title, seasonNo: Int, eps: List<Ep>): List<Ep> {
        if (!(t.type == "anime" || t.isAnime) || eps.isEmpty()) return eps
        val det = runCatching { details("tv", t.id) }.getOrNull()
        val seasons = det?.seasons ?: listOf()
        val titleMap = runCatching { animeMap(t.name, t.year) }.getOrNull()
        val entry = animeEntry(t.name, t.year, seasons, seasonNo, titleMap?.mal, titleMap?.ani)
        val today = today()
        val weekAgo = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(System.currentTimeMillis() - 7L * 24 * 3600 * 1000))
        var probes = 0
        return eps.map { ep ->
            val boundary = ep.air.isBlank() || ep.air.take(10) >= weekAgo || !ep.aired
            if (!boundary) {
                ep
            } else if (probes >= 12) {
                ep.copy(aired = if (ep.air.isNotBlank()) ep.air.take(10) < today else false)
            } else {
                probes += 1
                when (aniPmHas(entry.mal, (entry.epBase + ep.n).coerceAtLeast(1))) {
                    true -> if (ep.aired) ep else ep.copy(aired = true)
                    false -> if (!ep.aired) ep else ep.copy(aired = false)
                    null -> ep.copy(aired = if (ep.air.isNotBlank()) ep.air.take(10) < today else false)
                }
            }
        }
    }

    /** Core title details (one TMDB call): genres, runtime, the season
        directory, studio/languages and the aired gate. Cert + videos are
        separate calls so the detail screen can fetch everything in
        parallel (speed pass 0.32). Nothing is ever invented. */
    fun details(type: String, id: Int): TDetail {
        val kind = if (type == "movie") "movie" else "tv"
        return try {
            val o = get("/api/tmdb/3/" + kind + "/" + id)
            val genres = ArrayList<String>()
            val gArr = o.optJSONArray("genres")
            if (gArr != null) {
                for (i in 0 until gArr.length()) {
                    val g = gArr.optJSONObject(i)?.optString("name", "") ?: ""
                    if (g.isNotBlank()) genres.add(g)
                }
            }
            val runtime = if (kind == "movie") {
                o.optInt("runtime", 0)
            } else {
                o.optJSONArray("episode_run_time")?.optInt(0, 0) ?: 0
            }
            val seasons = ArrayList<SeasonInfo>()
            val sArr = o.optJSONArray("seasons")
            if (sArr != null) {
                for (i in 0 until sArr.length()) {
                    val s = sArr.optJSONObject(i) ?: continue
                    val n = s.optInt("season_number", 0)
                    if (n <= 0) continue
                    seasons.add(SeasonInfo(n, s.optInt("episode_count", 0), s.optString("name", ""), dateOf(s, "air_date")))
                }
            }
            val comp = o.optJSONArray("production_companies")
            val studio = if (comp != null && comp.length() > 0) {
                comp.optJSONObject(0)?.optString("name", "") ?: ""
            } else ""
            val lArr = o.optJSONArray("spoken_languages")
            val langs = if (lArr == null) "" else {
                val out = ArrayList<String>()
                for (i in 0 until lArr.length()) {
                    val l = lArr.optJSONObject(i) ?: continue
                    val name = l.optString("english_name", "").ifEmpty { l.optString("name", "") }
                    if (name.isNotBlank()) out.add(name)
                }
                out.take(4).joinToString(", ")
            }
            TDetail(
                genres = genres,
                runtime = runtime,
                seasons = seasons,
                studio = studio,
                langs = langs,
                cert = "",
                videos = listOf(),
                aired = dateOf(o, "release_date").ifEmpty { dateOf(o, "first_air_date") }
                    .let { it.isBlank() || it.take(10) <= today() },
                originalLanguage = o.optString("original_language", ""),
                name = titleOf(o),
                year = yearOf(o),
            )
        } catch (e: Exception) {
            TDetail(listOf(), 0, listOf(), "", "", "", listOf())
        }
    }

    /** US content rating / certification (real TMDB only, "" when absent). */
    fun cert(type: String, id: Int): String {
        return try {
            if (type == "movie") {
                val rd = get("/api/tmdb/3/movie/" + id + "/release_dates").optJSONArray("results")
                var c = ""
                if (rd != null) {
                    for (i in 0 until rd.length()) {
                        val r = rd.optJSONObject(i) ?: continue
                        if (r.optString("iso_3166_1") != "US") continue
                        val arr = r.optJSONArray("release_dates") ?: continue
                        for (j in 0 until arr.length()) {
                            val v = arr.optJSONObject(j)?.optString("certification", "") ?: ""
                            if (v.isNotBlank()) { c = v; break }
                        }
                    }
                }
                c
            } else {
                val cr = get("/api/tmdb/3/tv/" + id + "/content_ratings").optJSONArray("results")
                var c = ""
                if (cr != null) {
                    for (i in 0 until cr.length()) {
                        val r = cr.optJSONObject(i) ?: continue
                        if (r.optString("iso_3166_1") == "US") {
                            c = r.optString("rating", "")
                            break
                        }
                    }
                }
                c
            }
        } catch (e: Exception) {
            ""
        }
    }

    /** Trailer + extras (real TMDB videos, YouTube keys only). */
    fun videos(type: String, id: Int): List<VideoInfo> {
        return try {
            val kind = if (type == "movie") "movie" else "tv"
            val vArr = get("/api/tmdb/3/" + kind + "/" + id + "/videos").optJSONArray("results")
                ?: return listOf()
            val out = ArrayList<VideoInfo>()
            for (i in 0 until vArr.length()) {
                val v = vArr.optJSONObject(i) ?: continue
                if (v.optString("site", "") != "YouTube") continue
                val key = v.optString("key", "")
                if (key.isBlank()) continue
                out.add(VideoInfo(key, v.optString("name", ""), v.optString("type", "")))
            }
            out
        } catch (e: Exception) {
            listOf()
        }
    }

    /** Top billed cast (detail rail) - TMDB credits via the Worker proxy. */
    fun credits(type: String, id: Int): List<CastMem> {
        val kind = if (type == "movie") "movie" else "tv"
        val arr = get("/api/tmdb/3/" + kind + "/" + id + "/credits").optJSONArray("cast")
            ?: return listOf()
        val out = ArrayList<CastMem>()
        for (i in 0 until minOf(arr.length(), 12)) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "")
            if (name.isBlank()) continue
            out.add(CastMem(name, o.optString("character", ""), img(o, "profile_path", IMG_W185)))
        }
        return out
    }

    /** Creator & Showrunner card (design pack): the real people behind the
        show - TMDB crew filtered to the credits that name them. */
    fun crew(type: String, id: Int): List<CrewMem> {
        val kind = if (type == "movie") "movie" else "tv"
        val arr = get("/api/tmdb/3/" + kind + "/" + id + "/credits").optJSONArray("crew")
            ?: return listOf()
        val jobs = listOf("Creator", "Showrunner", "Director", "Executive Producer")
        val out = ArrayList<CrewMem>()
        val seen = HashSet<String>()
        for (j in jobs) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("job", "") != j) continue
                val name = o.optString("name", "")
                if (name.isBlank() || !seen.add(name)) continue
                out.add(CrewMem(name, j))
                break
            }
            if (out.size >= 3) break
        }
        return out
    }

    /** Similar titles (detail rail) - anime keeps its anime routing. */
    fun similar(type: String, id: Int): List<Title> {
        val kind = if (type == "movie") "movie" else "tv"
        val arr = get("/api/tmdb/3/" + kind + "/" + id + "/similar").optJSONArray("results")
            ?: return listOf()
        return titlesFrom(arr, if (type == "movie") "movie" else if (type == "anime") "anime" else null)
            .filter { it.poster != null }
    }

    /** First aired episode AFTER (seasonNo, episode) - the Continue Watching
        new-episode revival (0.73). Walks the rest of the season first, then
        the next seasons that have actually premiered. For anime the
        candidate must ALSO be really dropped on AniPM (0.77) - the row must
        never return pointing at an episode that 404s. Null = nothing out
        yet (the row stays hidden). */
    fun nextAiredEpisode(id: Int, seasonNo: Int, episode: Int, titleMal: Int? = null, titleAni: Int? = null): Pair<Int, Int>? {
        return try {
            val det = runCatching { details("tv", id) }.getOrNull() ?: return null
            fun out(s: Int, ep: Ep): Pair<Int, Int>? {
                // Anime candidates must be REALLY dropped on AniPM (0.77) -
                // the row must never return pointing at an episode that404s. With no MAL id there is nothing to probe.
                if (titleMal != null) {
                    val entry = animeEntry(det.name, det.year, det.seasons, s, titleMal, titleAni)
                    if (aniPmHas(entry.mal, (entry.epBase + ep.n).coerceAtLeast(1)) == false) return null
                }
                return s to ep.n
            }
            val eps = runCatching { season(id, seasonNo) }.getOrDefault(listOf())
            for (cand in eps.filter { it.n > episode && it.aired }) {
                out(seasonNo, cand)?.let { return it }
            }
            val nxt = det.seasons.filter { it.n > seasonNo }
                .sortedBy { it.n }
                .firstOrNull { it.count > 0 && (it.air.isBlank() || it.air.take(10) <= today()) }
            if (nxt == null) return null
            val neps = runCatching { season(id, nxt.n) }.getOrDefault(listOf())
            for (cand in neps.filter { it.aired }) {
                out(nxt.n, cand)?.let { return it }
            }
            return null
        } catch (e: Exception) {
            null
        }
    }

    /** Session memo (0.79): every anime route resolves BOTH the air-year
        and the title-year entry - repeats must not repeat the network.
        Failed lookups are never cached (0.46). */
    private val mapMemo =
        java.util.Collections.synchronizedMap(HashMap<String, AnimeIds>())

    fun animeMap(name: String, year: Int): AnimeIds? {
        val key = name.lowercase() + ":" + year
        mapMemo[key]?.let { return it }
        return try {
            val o = get(
                "/api/anime-map?title=" + java.net.URLEncoder.encode(name, "UTF-8") +
                    "&year=" + year
            )
            val out = if (o.has("error")) null
            else AnimeIds(
                mal = if (o.isNull("mal")) null else o.optInt("mal"),
                ani = if (o.isNull("ani")) null else o.optInt("ani"),
                year = if (o.isNull("year")) 0 else o.optInt("year"),
            )
            if (out != null) mapMemo[key] = out
            out
        } catch (e: Exception) {
            null
        }
    }
}
