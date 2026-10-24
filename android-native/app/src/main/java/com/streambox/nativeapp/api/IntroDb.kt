package com.streambox.nativeapp.api

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** TheIntroDB (keyless, TMDB-keyed) - same intro/recap/credits source the
    website uses. TV shows + anime only; movies never skip (house rule). */
object IntroDb {
    data class TRange(val start: Float, val end: Float?)
    data class Skips(
        val intro: List<TRange>,
        val recap: List<TRange>,
        val credits: List<TRange>,
        val preview: List<TRange> = listOf(),
    )

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

    /** One CLEAN range set per kind (0.73). The three sources used to
        concatenate blindly and the first in-range hit won - a stale
        TheIntroDB entry (listed first) shadowed the real opening and the
        timestamps missed on other anime. Merge rules:
        - duration-calibrated answers (AniSkip fitted to episodeLength,
          SkipDB duration-matched - `fitted`) outrank raw ones, and every
          candidate is validated against the REAL stream length;
        - a lower-priority range that overlaps a winner is dropped;
        - recap must sit before the opening (small, up front), every ranged
          kind stays inside the episode, credits/preview stay in the tail
          (the 0.54 start=0 guard). */
    fun merge(
        db: Skips,
        ani: List<SkipTimes.Hit>,
        sk: List<SkipTimes.Hit>,
        dur: Float,
        fitted: Boolean,
    ): Skips {
        data class Cand(val src: Int, val r: TRange, val score: Int)

        fun scoreOf(src: Int, r: TRange, kind: String): Int {
            var s = when {
                src < 2 && fitted -> 30
                src < 2 -> 20
                else -> 10
            }
            val len = ((r.end ?: (if (dur > 0) dur else r.start + 90f)) - r.start)
            when (kind) {
                "intro" -> {
                    if (len in 8f..260f) s += 3
                    if (dur <= 0f || r.start <= dur * 0.35f) s += 2
                }
                "recap" -> {
                    if (len in 3f..200f) s += 3
                    if (dur <= 0f || r.start <= dur * 0.35f) s += 2
                }
                else -> {
                    if (r.end != null && len in 20f..360f) s += 3
                    if (r.start > 10f && (dur <= 0f || r.start >= dur * 0.5f)) s += 2
                }
            }
            return s
        }

        fun valid(r: TRange, kind: String): Boolean {
            if (r.start < 0f) return false
            if (r.end != null && r.end!! <= r.start + 2f) return false
            if (dur > 0f) {
                if (r.start >= dur) return false
                if (r.end != null && r.end!! > dur + 25f) return false
                if (kind == "intro" || kind == "recap") {
                    if (r.start >= dur * 0.5f) return false
                    val len = (r.end ?: 0f) - r.start
                    if (len > dur * 0.5f) return false
                } else if (!(r.start > 10f && r.start >= dur * 0.5f)) {
                    return false
                }
            } else if (kind == "intro" || kind == "recap") {
                val len = (r.end ?: 0f) - r.start
                if (r.end != null && (len > 420f || r.start > 900f)) return false
            } else {
                if (!(r.start > 10f)) return false
            }
            return true
        }

        fun overlapFrac(a: TRange, b: TRange): Float {
            val ae = a.end ?: return 0f
            val be = b.end ?: return 0f
            val lo = maxOf(a.start, b.start)
            val hi = minOf(ae, be)
            if (hi <= lo) return 0f
            val la = ae - a.start
            val lb = be - b.start
            val lmin = minOf(la, lb)
            return if (lmin <= 0f) 0f else (hi - lo) / lmin
        }

        fun pick(cands: List<Cand>, max: Int): List<TRange> {
            val out = ArrayList<Cand>()
            for (c in cands.sortedWith(compareByDescending<Cand> { it.score }.thenBy { it.src })) {
                if (out.size >= max) break
                if (out.any { overlapFrac(it.r, c.r) > 0.5f }) continue
                out.add(c)
            }
            return out.map { it.r }.sortedBy { it.start }
        }

        fun build(kind: String, a: List<TRange>, s: List<TRange>, d: List<TRange>, max: Int): List<TRange> {
            val cands = ArrayList<Cand>()
            for (r in a) if (valid(r, kind)) cands.add(Cand(0, r, scoreOf(0, r, kind)))
            for (r in s) if (valid(r, kind)) cands.add(Cand(1, r, scoreOf(1, r, kind)))
            for (r in d) if (valid(r, kind)) cands.add(Cand(2, r, scoreOf(2, r, kind)))
            return pick(cands, max)
        }

        fun hits(list: List<SkipTimes.Hit>, kind: String): List<TRange> =
            list.filter { it.kind == kind }.map { TRange(it.start, it.end) }

        return Skips(
            intro = build("intro", hits(ani, "intro"), hits(sk, "intro"), db.intro, 3),
            recap = build("recap", hits(ani, "recap"), hits(sk, "recap"), db.recap, 3),
            credits = build("credits", hits(ani, "credits"), hits(sk, "credits"), db.credits, 3),
            preview = build("preview", hits(ani, "preview"), hits(sk, "preview"), db.preview, 2),
        )
    }
}
