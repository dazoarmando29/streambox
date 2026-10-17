package com.streambox.nativeapp.api

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Verified anime skip-timestamp providers (live-probed 2026-10-17):
    - AniSkip api.aniskip.com v2: MAL id + episode + episodeLength (0 = any),
      required types[] = op|ed|mixed-op|mixed-ed|recap, intervals in SECONDS,
      open (no auth). 404 = no data for that episode.
    - SkipDB api.skipdb.tv: IMDb id + season/episode + stream duration,
      types intro|recap|outro|preview, timestamps in MILLISECONDS with
      duration matching (match/adjusted/confidence). Reads open, 120/min.
    Both are TIMESTAMP METADATA ONLY - no streams, no rights involved.
    Empty results just mean "no data": callers fall back to TheIntroDB. */
object SkipTimes {

    /** kind: intro | recap | credits | preview (credits = outro = "Next Ep"). */
    data class Hit(val kind: String, val start: Float, val end: Float)

    private fun getJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("accept", "application/json")
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) return JSONObject()
            return JSONObject(text)
        } catch (e: Exception) {
            return JSONObject()
        } finally {
            conn.disconnect()
        }
    }

    /** AniSkip: op/mixed-op -> intro, ed/mixed-ed -> credits, recap -> recap. */
    fun aniSkip(mal: Int, episode: Int, durSec: Float): List<Hit> {
        return try {
            if (mal <= 0 || episode <= 0) return listOf()
            val epLen = if (durSec > 30) durSec.toInt() else 0
            val o = getJson(
                "https://api.aniskip.com/v2/skip-times/" + mal + "/" + episode +
                    "?episodeLength=" + epLen +
                    "&types%5B%5D=op&types%5B%5D=ed&types%5B%5D=recap" +
                    "&types%5B%5D=mixed-op&types%5B%5D=mixed-ed",
            )
            val arr = o.optJSONArray("results") ?: return listOf()
            val out = ArrayList<Hit>()
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val iv = r.optJSONObject("interval") ?: continue
                val s = iv.optDouble("startTime", -1.0).toFloat()
                val e = iv.optDouble("endTime", -1.0).toFloat()
                if (s < 0f || e <= s) continue
                when (r.optString("skipType")) {
                    "op", "mixed-op" -> out.add(Hit("intro", s, e))
                    "ed", "mixed-ed" -> out.add(Hit("credits", s, e))
                    "recap" -> out.add(Hit("recap", s, e))
                }
            }
            out
        } catch (e: Exception) {
            listOf()
        }
    }

    /** SkipDB: intro/recap/outro/preview -> our kinds (outro = credits). */
    fun skipDb(imdb: String, season: Int, episode: Int, durSec: Float): List<Hit> {
        return try {
            if (imdb.isEmpty() || episode <= 0) return listOf()
            var u = "https://api.skipdb.tv/api/segments?imdb_id=" + imdb +
                "&season=" + season + "&episode=" + episode
            if (durSec > 30) u += "&duration=" + durSec.toInt()
            val seg = getJson(u).optJSONObject("segments") ?: return listOf()
            val out = ArrayList<Hit>()
            fun add(type: String, kind: String) {
                val s = seg.optJSONObject(type) ?: return
                val st = s.optLong("start_ms", -1L).toFloat() / 1000f
                val en = s.optLong("end_ms", -1L).toFloat() / 1000f
                if (st >= 0f && en > st) out.add(Hit(kind, st, en))
            }
            add("intro", "intro")
            add("recap", "recap")
            add("outro", "credits")
            add("preview", "preview")
            out
        } catch (e: Exception) {
            listOf()
        }
    }
}
