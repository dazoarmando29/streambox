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
)

data class AnimeIds(val mal: Int?, val ani: Int?)

/** One episode of a TV/anime season (real TMDB fields only - a missing
    still or runtime is normal, especially on long-running anime). */
data class Ep(val n: Int, val name: String, val still: String?, val runtime: Int, val year: Int, val overview: String)

/** One cast member (detail rail): name + role + portrait when TMDB has it. */
data class CastMem(val name: String, val role: String, val photo: String?)

/** Client for the streambox-api Worker: TMDB catalog (key stays server-side)
    and TMDB->MAL/AniList mapping (Jikan/AniList woes solved server-side). */
object Api {
    private const val BASE = BuildConfig.API_BASE
    private const val IMG = "https://image.tmdb.org/t/p/w342"
    private const val IMG_W780 = "https://image.tmdb.org/t/p/w780"
    private const val IMG_W300 = "https://image.tmdb.org/t/p/w300"
    private const val IMG_W185 = "https://image.tmdb.org/t/p/w185"

    private fun get(path: String): JSONObject {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
        conn.setRequestProperty("accept", "application/json")
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("http_" + code)
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun img(o: JSONObject, key: String, base: String): String? {
        val p = o.optString(key, "")
        return if (p.isNullOrEmpty() || p == "null") null else base + p
    }

    private fun yearOf(o: JSONObject): Int {
        val d = o.optString("release_date", "").ifEmpty { o.optString("first_air_date", "") }
        return d.take(4).toIntOrNull() ?: 0
    }

    private fun titleOf(o: JSONObject): String =
        o.optString("title", "").ifEmpty { o.optString("name", "") }

    private fun titlesFrom(arr: JSONArray, typeOverride: String?): List<Title> {
        val out = ArrayList<Title>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("id", 0)
            if (id == 0) continue
            val mediaType = o.optString("media_type", "")
            val type = typeOverride
                ?: if (o.has("title") && !o.has("name")) "movie"
                else if (mediaType == "movie") "movie" else "tv"
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

    fun search(query: String, typeOverride: String?): List<Title> =
        titlesFrom(
            get("/api/tmdb/3/search/multi?query=" + java.net.URLEncoder.encode(query, "UTF-8"))
                .getJSONArray("results"),
            typeOverride
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
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optInt("episode_number", 0)
            if (n <= 0) continue
            out.add(
                Ep(
                    n = n,
                    name = o.optString("name", ""),
                    still = img(o, "still_path", IMG_W300),
                    runtime = o.optInt("runtime", 0),
                    year = o.optString("air_date", "").take(4).toIntOrNull() ?: 0,
                    overview = o.optString("overview", ""),
                )
            )
        }
        return out
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

    /** Similar titles (detail rail) - anime keeps its anime routing. */
    fun similar(type: String, id: Int): List<Title> {
        val kind = if (type == "movie") "movie" else "tv"
        val arr = get("/api/tmdb/3/" + kind + "/" + id + "/similar").optJSONArray("results")
            ?: return listOf()
        return titlesFrom(arr, if (type == "movie") "movie" else if (type == "anime") "anime" else null)
            .filter { it.poster != null }
    }

    fun animeMap(name: String, year: Int): AnimeIds? {
        return try {
            val o = get(
                "/api/anime-map?title=" + java.net.URLEncoder.encode(name, "UTF-8") +
                    "&year=" + year
            )
            if (o.has("error")) null
            else AnimeIds(
                mal = if (o.isNull("mal")) null else o.optInt("mal"),
                ani = if (o.isNull("ani")) null else o.optInt("ani"),
            )
        } catch (e: Exception) {
            null
        }
    }
}
