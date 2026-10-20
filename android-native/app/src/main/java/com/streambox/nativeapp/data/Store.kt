package com.streambox.nativeapp.data

import android.content.Context
import com.streambox.nativeapp.api.Title
import org.json.JSONArray
import org.json.JSONObject

data class Progress(
    val type: String,
    val id: Int,
    val name: String,
    val poster: String?,
    val season: Int,
    val episode: Int,
    val cur: Float,
    val dur: Float,
    val at: Long,
    val mal: Int?,
    val ani: Int?,
    val lang: String = "",
)

/** Per-title subtitle placement + sync memory (device-local). */
data class SubsPref(val pos: String = "bottom", val offset: Float = 0f)

/** Per-episode subtitle pick. URL-backed picks only - a manually picked
    local file can't be re-read after a restart. */
data class SubPick(val url: String, val name: String, val lang: String)

/** Caption style (device-local taste settings, task spec: size/color/
    background opacity for OUR overlay - never the embed's own captions). */
data class SubStyle(val size: Int = 16, val color: Int = 0, val bg: Int = 60)

/** Device-local stores (phone and TV keep their own state, house rule).
    SharedPreferences JSON - same spirit as the site's localStorage keys. */
object Store {
    private const val PREFS = "streambox_native"
    private const val K_PROGRESS = "progress"
    private const val K_LIST = "mylist"
    private const val K_ADULT = "adult_open"
    private const val K_LAST_SEEN = "last_seen_version"
    private const val K_SUBS_PREF = "subs_pref"
    private const val K_SUB_PICK = "sub_pick"
    private const val K_SUB_STYLE = "sub_style"
    private const val K_AUTO_SKIP = "auto_skip"
    private const val K_FEED = "feed_cache"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun titleToJson(t: Title): JSONObject = JSONObject()
        .put("type", t.type).put("id", t.id).put("name", t.name)
        .put("poster", t.poster).put("backdrop", t.backdrop)
        .put("year", t.year).put("overview", t.overview).put("vote", t.vote)
        .put("lang", t.lang)

    private fun titleFrom(o: JSONObject): Title = Title(
        id = o.optInt("id"),
        type = o.optString("type"),
        name = o.optString("name"),
        poster = if (o.isNull("poster")) null else o.optString("poster"),
        backdrop = if (o.isNull("backdrop")) null else o.optString("backdrop"),
        year = o.optInt("year"),
        overview = o.optString("overview"),
        vote = o.optDouble("vote"),
        lang = o.optString("lang", ""),
    )

    private fun progressToJson(x: Progress): JSONObject = JSONObject()
        .put("type", x.type).put("id", x.id).put("name", x.name)
        .put("poster", x.poster).put("season", x.season).put("episode", x.episode)
        .put("cur", x.cur.toDouble()).put("dur", x.dur.toDouble()).put("at", x.at)
        .put("mal", x.mal ?: JSONObject.NULL).put("ani", x.ani ?: JSONObject.NULL)
        .put("lang", x.lang)

    private fun progressFrom(o: JSONObject): Progress = Progress(
        type = o.optString("type"),
        id = o.optInt("id"),
        name = o.optString("name"),
        poster = if (o.isNull("poster")) null else o.optString("poster"),
        season = o.optInt("season"),
        episode = o.optInt("episode"),
        cur = o.optDouble("cur", 0.0).toFloat(),
        dur = o.optDouble("dur", 0.0).toFloat(),
        at = o.optLong("at"),
        mal = if (o.isNull("mal")) null else o.optInt("mal"),
        ani = if (o.isNull("ani")) null else o.optInt("ani"),
        lang = o.optString("lang", ""),
    )

    fun progressList(ctx: Context): List<Progress> {
        return try {
            val arr = JSONArray(prefs(ctx).getString(K_PROGRESS, "[]") ?: "[]")
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { progressFrom(it) }
            }.sortedByDescending { it.at }
        } catch (e: Exception) {
            listOf()
        }
    }

    fun saveProgress(ctx: Context, p: Progress) {
        try {
            val keep = progressList(ctx).filterNot { it.type == p.type && it.id == p.id }
            val arr = JSONArray()
            (listOf(p) + keep).take(12).forEach { arr.put(progressToJson(it)) }
            prefs(ctx).edit().putString(K_PROGRESS, arr.toString()).apply()
        } catch (e: Exception) {
        }
    }

    fun removeProgress(ctx: Context, type: String, id: Int) {
        try {
            val arr = JSONArray()
            progressList(ctx).filterNot { it.type == type && it.id == id }
                .forEach { arr.put(progressToJson(it)) }
            prefs(ctx).edit().putString(K_PROGRESS, arr.toString()).apply()
        } catch (e: Exception) {
        }
    }

    fun listItems(ctx: Context): List<Title> {
        return try {
            val arr = JSONArray(prefs(ctx).getString(K_LIST, "[]") ?: "[]")
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { titleFrom(it) }
            }
        } catch (e: Exception) {
            listOf()
        }
    }

    fun inList(ctx: Context, type: String, id: Int): Boolean =
        listItems(ctx).any { it.type == type && it.id == id }

    fun toggleList(ctx: Context, t: Title): Boolean {
        return try {
            val cur = listItems(ctx)
            val on = cur.any { it.type == t.type && it.id == t.id }
            val next = if (on) cur.filterNot { it.type == t.type && it.id == t.id } else listOf(t) + cur
            val arr = JSONArray()
            next.take(100).forEach { arr.put(titleToJson(it)) }
            prefs(ctx).edit().putString(K_LIST, arr.toString()).apply()
            !on
        } catch (e: Exception) {
            false
        }
    }

    fun adultOpen(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ADULT, false)

    fun setAdultOpen(ctx: Context, open: Boolean) {
        prefs(ctx).edit().putBoolean(K_ADULT, open).apply()
    }

    /** Last app version whose What's New card was seen (device-local). A
        mismatch with BuildConfig.VERSION_NAME opens the card ONCE per
        fresh update (0.51). */
    fun lastSeenVersion(ctx: Context): String =
        prefs(ctx).getString(K_LAST_SEEN, "") ?: ""

    fun setLastSeenVersion(ctx: Context, v: String) {
        prefs(ctx).edit().putString(K_LAST_SEEN, v).apply()
    }

    /** Disk feed cache (0.52 speed pass): a cold app start paints the last
        rows of a section instantly and revalidates in the background. 30
        minute TTL, last six sections - small prefs, big first paint. */
    private const val FEED_TTL_MS = 30L * 60L * 1000L

    fun loadFeedCache(ctx: Context, key: String): List<Pair<String, List<Title>>>? {
        val root = JSONObject(prefs(ctx).getString(K_FEED, "{}") ?: "{}")
        val o = root.optJSONObject(key) ?: return null
        if (System.currentTimeMillis() - o.optLong("at", 0) > FEED_TTL_MS) return null
        val arr = o.optJSONArray("rows") ?: return null
        val out = ArrayList<Pair<String, List<Title>>>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val tArr = r.optJSONArray("titles") ?: JSONArray()
            val titles = ArrayList<Title>()
            for (j in 0 until tArr.length()) {
                val t = tArr.optJSONObject(j) ?: continue
                titles.add(titleFrom(t))
            }
            out.add(r.optString("label") to titles)
        }
        return out
    }

    fun saveFeedCache(ctx: Context, key: String, rows: List<Pair<String, List<Title>>>) {
        val root = JSONObject(prefs(ctx).getString(K_FEED, "{}") ?: "{}")
        val arr = JSONArray()
        rows.forEach { (label, titles) ->
            val tArr = JSONArray()
            titles.forEach { tArr.put(titleToJson(it)) }
            arr.put(JSONObject().put("label", label).put("titles", tArr))
        }
        root.put(key, JSONObject().put("at", System.currentTimeMillis()).put("rows", arr))
        // Keep the prefs small: the six most recent keys only.
        while (root.length() > 6) {
            var oldest: String? = null
            var oldestAt = Long.MAX_VALUE
            for (k in root.keys()) {
                val at = root.optJSONObject(k)?.optLong("at", 0) ?: 0
                if (at < oldestAt) {
                    oldestAt = at
                    oldest = k
                }
            }
            if (oldest == null) break
            root.remove(oldest)
        }
        prefs(ctx).edit().putString(K_FEED, root.toString()).apply()
    }

    fun autoSkip(ctx: Context): Boolean = prefs(ctx).getBoolean(K_AUTO_SKIP, false)

    fun setAutoSkip(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(K_AUTO_SKIP, on).apply()
    }

    fun subStyle(ctx: Context): SubStyle {
        return try {
            val o = JSONObject(prefs(ctx).getString(K_SUB_STYLE, "{}") ?: "{}")
            SubStyle(
                size = o.optInt("size", 16).coerceIn(12, 26),
                color = o.optInt("color", 0).coerceIn(0, 2),
                bg = o.optInt("bg", 60).coerceIn(0, 90),
            )
        } catch (e: Exception) {
            SubStyle()
        }
    }

    fun saveSubStyle(ctx: Context, s: SubStyle) {
        try {
            val o = JSONObject()
                .put("size", s.size.coerceIn(12, 26))
                .put("color", s.color.coerceIn(0, 2))
                .put("bg", s.bg.coerceIn(0, 90))
            prefs(ctx).edit().putString(K_SUB_STYLE, o.toString()).apply()
        } catch (e: Exception) {
        }
    }

    private fun subKey(type: String, id: Int) = type + id

    private fun epKey(type: String, id: Int, season: Int, episode: Int) =
        type + id + ":" + season + ":" + episode

    fun subsPref(ctx: Context, type: String, id: Int): SubsPref {
        return try {
            val o = JSONObject(prefs(ctx).getString(K_SUBS_PREF, "{}") ?: "{}")
                .optJSONObject(subKey(type, id))
            SubsPref(
                pos = o?.optString("pos")?.ifEmpty { "bottom" } ?: "bottom",
                offset = (o?.optDouble("offset") ?: 0.0).toFloat(),
            )
        } catch (e: Exception) {
            SubsPref()
        }
    }

    fun saveSubsPref(ctx: Context, type: String, id: Int, pos: String, offset: Float) {
        try {
            val root = JSONObject(prefs(ctx).getString(K_SUBS_PREF, "{}") ?: "{}")
            val next = JSONObject()
            val key = subKey(type, id)
            next.put(key, JSONObject().put("pos", pos).put("offset", offset.toDouble()))
            val keys = root.keys()
            var n = 1
            while (keys.hasNext() && n < 40) {
                val k = keys.next()
                if (k != key) {
                    next.put(k, root.getJSONObject(k))
                    n++
                }
            }
            prefs(ctx).edit().putString(K_SUBS_PREF, next.toString()).apply()
        } catch (e: Exception) {
        }
    }

    fun subPick(ctx: Context, type: String, id: Int, season: Int, episode: Int): SubPick? {
        return try {
            val o = JSONObject(prefs(ctx).getString(K_SUB_PICK, "{}") ?: "{}")
                .optJSONObject(epKey(type, id, season, episode)) ?: return null
            val url = o.optString("url")
            if (url.isEmpty()) null else SubPick(url, o.optString("name"), o.optString("lang"))
        } catch (e: Exception) {
            null
        }
    }

    /** pick == null forgets the episode's subtitle (user hit Clear). */
    fun saveSubPick(ctx: Context, type: String, id: Int, season: Int, episode: Int, pick: SubPick?) {
        try {
            val root = JSONObject(prefs(ctx).getString(K_SUB_PICK, "{}") ?: "{}")
            val next = JSONObject()
            val key = epKey(type, id, season, episode)
            if (pick != null) {
                next.put(
                    key,
                    JSONObject().put("url", pick.url).put("name", pick.name).put("lang", pick.lang),
                )
            }
            val keys = root.keys()
            var n = if (pick != null) 1 else 0
            while (keys.hasNext() && n < 60) {
                val k = keys.next()
                if (k != key) {
                    next.put(k, root.getJSONObject(k))
                    n++
                }
            }
            prefs(ctx).edit().putString(K_SUB_PICK, next.toString()).apply()
        } catch (e: Exception) {
        }
    }
}
