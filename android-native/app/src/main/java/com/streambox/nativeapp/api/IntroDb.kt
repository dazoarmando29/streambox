package com.streambox.nativeapp.api

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** TheIntroDB (keyless, TMDB-keyed) - same intro/recap/credits source the
    website uses. TV shows + anime only; movies never skip (house rule). */
object IntroDb {
    data class TRange(val start: Float, val end: Float?)
    data class Skips(val intro: List<TRange>, val recap: List<TRange>, val credits: List<TRange>)

    fun fetch(tmdbId: Int, season: Int, episode: Int): Skips {
        val u = "https://api.theintrodb.org/v3/media?tmdb_id=" + tmdbId +
            "&season=" + season + "&episode=" + episode
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("accept", "application/json")
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) return Skips(listOf(), listOf(), listOf())
            val o = JSONObject(text)
            val intro = ranges(o, "intro", false)
            val recap = ranges(o, "recap", false)
            val credits = ranges(o, "credits", true)
            return Skips(intro, recap, credits)
        } catch (e: Exception) {
            return Skips(listOf(), listOf(), listOf())
        } finally {
            conn.disconnect()
        }
    }

    private fun ranges(o: JSONObject, key: String, allowOpenEnd: Boolean): List<TRange> {
        val out = ArrayList<TRange>()
        val arr = o.optJSONArray(key) ?: return out
        for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            val start = x.optDouble("start_ms", 0.0).toFloat() / 1000f
            val end = if (x.isNull("end_ms")) null else x.optDouble("end_ms").toFloat() / 1000f
            val ok = if (allowOpenEnd) {
                start >= 0 && (end == null || end > start)
            } else {
                end != null && end > start && end - start > 3
            }
            if (ok) out.add(TRange(start, end))
        }
        return out
    }
}
