package com.streambox.nativeapp.api

import com.streambox.nativeapp.BuildConfig
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

data class Cue(val s: Float, val e: Float, val text: String)

/** Active cue at time t (0.72 perf): binary search over the SORTED cue
    list - the old firstOrNull scan walked every cue on every timeupdate
    and the caption lookup was measurable jank on weak TV boxes. */
fun cueAt(cues: List<Cue>, t: Float): Cue? {
    if (cues.isEmpty()) return null
    var lo = 0
    var hi = cues.size - 1
    var idx = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (cues[mid].s <= t) {
            idx = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    if (idx < 0) return null
    // Walk back over any overlapping tails (rare) - a handful at most.
    var i = idx
    var steps = 0
    while (i >= 0 && steps < 8) {
        val c = cues[i]
        if (t >= c.s && t <= c.e) return c
        if (c.e < t && idx - i > 2) return null
        i--
        steps++
    }
    return null
}

data class SubFile(
    val name: String,
    val lang: String,
    val cues: List<Cue>,
    // Placement + sync are SETTINGS (website behavior): they live in the
    // player state (remembered per title), not on the file.
)

data class WyzItem(
    val id: String,
    val release: String,
    val display: String,
    val origin: String,
    val downloads: Int,
    val cc: Boolean,
    val url: String,
)

/** SRT/VTT parsing + Wyzie search - same behavior as the website's Subs
    panel (promo-banner cues stripped, tags stripped, open-VTT tolerated). */
object Subs {
    private val PROMO = Regex("wyzie\\.io|unlock every source|ad-free subs", RegexOption.IGNORE_CASE)
    private val TS = Regex("(\\d{1,2}:)?\\d{1,2}:\\d{2}[.,]\\d{1,3}\\s*-->\\s*(\\d{1,2}:)?\\d{1,2}:\\d{2}[.,]\\d{1,3}")

    fun parse(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        var body = text.removePrefix("\uFEFF").replace(Regex("\r\n?"), "\n")
        val lines = body.split("\n")
        var i = 0
        var start: Float? = null
        var end: Float? = null
        val buf = ArrayList<String>()

        fun push() {
            if (start != null && end != null && buf.isNotEmpty()) {
                val t = buf.joinToString("\n").replace(Regex("<[^>]*>"), "").trim()
                if (t.isNotEmpty() && end!! > start!! && !PROMO.containsMatchIn(t)) {
                    out.add(Cue(start!!, end!!, t))
                }
            }
            start = null
            end = null
            buf.clear()
        }

        if (lines.isNotEmpty() && lines[0].trim().startsWith("WEBVTT")) i = 1
        while (i < lines.size) {
            val ln = lines[i]
            when {
                ln.isBlank() -> push()
                Regex("^(NOTE|STYLE|REGION)\\b", RegexOption.IGNORE_CASE).containsMatchIn(ln) -> {
                    while (i + 1 < lines.size && lines[i + 1].isNotBlank()) i++
                }
                TS.containsMatchIn(ln) -> {
                    push()
                    val parts = ln.split("-->")
                    start = toSecs(parts[0])
                    end = toSecs(parts.getOrElse(1) { "" })
                }
                start == null && ln.trim().toIntOrNull() != null -> {}
                start != null -> buf.add(ln.trim())
            }
            i++
        }
        push()
        return out
    }

    private fun toSecs(part: String): Float {
        val clean = part.trim().replace(",", ".").substringBefore(' ').trim()
        val bits = clean.split(":")
        if (bits.isEmpty()) return 0f
        val sec = bits.last().toFloatOrNull() ?: 0f
        val min = bits.getOrElse(bits.size - 2) { "0" }.toIntOrNull() ?: 0
        val hr = bits.getOrElse(bits.size - 3) { "0" }.toIntOrNull() ?: 0
        return (hr * 3600 + min * 60 + sec)
    }

    fun guessLang(name: String): String {
        val n = name.lowercase()
        val map = listOf(
            "english" to "English", ".en." to "English", ".eng." to "English",
            "spanish" to "Spanish", "french" to "French", "arabic" to "Arabic",
            "hindi" to "Hindi", "tagalog" to "Tagalog", "filipino" to "Filipino",
            "german" to "German", "portuguese" to "Portuguese", "italian" to "Italian",
            "dutch" to "Dutch", "russian" to "Russian", "turkish" to "Turkish",
            "indonesian" to "Indonesian", "vietnamese" to "Vietnamese", "polish" to "Polish",
            "japanese" to "Japanese", "korean" to "Korean", "chinese" to "Chinese", "urdu" to "Urdu",
        )
        for ((k, v) in map) if (n.contains(k)) return v
        return "Custom"
    }
}

/** Wyzie search via the streambox-api Worker (key stays as a Worker secret). */
object Wyz {
    fun search(tmdbId: Int, language: String, season: Int?, episode: Int?): List<WyzItem> {
        val q = StringBuilder(BuildConfig.API_BASE + "/api/wyz/search?id=" + tmdbId + "&language=" + language)
        if (season != null) q.append("&season=").append(season)
        if (episode != null) q.append("&episode=").append(episode)
        val conn = URL(q.toString()).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
        conn.setRequestProperty("accept", "application/json")
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            // Wyzie answers 400 "No subtitles found" for empty searches -
            // that is a normal empty result, not a failure.
            if (code == 400) return listOf()
            if (code !in 200..299) throw IllegalStateException("http_" + code)
            val arr = JSONArray(text)
            val out = ArrayList<WyzItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("url", "")
                if (u.isEmpty()) continue
                out.add(
                    WyzItem(
                        id = o.optString("id", i.toString()),
                        release = o.optString("release", "").ifEmpty { o.optString("fileName", "#" + i) },
                        display = o.optString("display", ""),
                        origin = o.optString("origin", ""),
                        downloads = o.optInt("downloadCount", 0),
                        cc = o.optBoolean("isHearingImpaired", false),
                        url = u,
                    )
                )
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    fun fetchFile(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("http_" + code)
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

/** Backup subtitle sources (the website's chain behind Wyzie's 1,000/day):
    #1 TSDB - public, keyless, TMDB ids, movies AND episodes, strict episode
    match. #2 BetaSeries - TV only, keyed (Worker secret), runs behind TSDB. */
object Backups {
    private fun getJson(url: String): org.json.JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
        conn.setRequestProperty("accept", "application/json")
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("http_" + code)
            return org.json.JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    fun tsdbFind(tmdbId: Int, isTv: Boolean, lang: String, season: Int?, episode: Int?): List<WyzItem> {
        val q = StringBuilder("sort=newest&limit=25&lang=").append(lang)
        if (isTv && season != null && episode != null) {
            q.append("&season=").append(season).append("&episode=").append(episode)
        }
        val u = "https://api.thesubtitledb.org/app/titles/" +
            (if (isTv) "tv" else "movie") + "/" + tmdbId + "/subtitles?" + q
        val j = getJson(u)
        var list = (j.optJSONArray("subtitles") ?: JSONArray())
            .let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }
                    .filter { o -> !o.optString("id").isEmpty() && !o.optString("download_url").isEmpty() }
            }
        if (isTv && season != null && episode != null) {
            // strict episode match - season packs would mistime (site rule)
            list = list.filter { o ->
                o.optInt("season", -1) == season && o.optInt("episode", -1) == episode
            }
            if (list.isEmpty()) return listOf()
        }
        return list
            .sortedWith(
                compareByDescending<org.json.JSONObject> { !it.optBoolean("hearing_impaired", false) }
                    .thenByDescending { it.optInt("downloads", 0) }
            )
            .map { o ->
                WyzItem(
                    id = "tsdb:" + o.optString("id"),
                    release = o.optString("release_name", "").ifEmpty { "TSDB #" + o.optString("id") },
                    display = o.optString("language", "").ifEmpty { lang.uppercase() },
                    origin = "TSDB",
                    downloads = o.optInt("downloads", 0),
                    cc = o.optBoolean("hearing_impaired", false),
                    url = o.optString("download_url"),
                )
            }
    }

    fun bsFind(tmdbId: Int, lang: String, season: Int, episode: Int): List<WyzItem> {
        val u = BuildConfig.API_BASE + "/api/bs-subs?tmdb=" + tmdbId +
            "&season=" + season + "&episode=" + episode + "&lang=" + lang
        val j = getJson(u)
        if (j.has("error")) return listOf()
        return (j.optJSONArray("data") ?: JSONArray())
            .let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }
            }
            .map { o ->
                WyzItem(
                    id = "bs:" + o.optString("file"),
                    release = o.optString("file", "BetaSeries"),
                    display = o.optString("language", lang.uppercase()),
                    origin = o.optString("source", "BetaSeries"),
                    downloads = 0,
                    cc = false,
                    url = o.optString("url"),
                )
            }
    }
}
