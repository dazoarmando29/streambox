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
)

/** Device-local stores (phone and TV keep their own state, house rule).
    SharedPreferences JSON - same spirit as the site's localStorage keys. */
object Store {
    private const val PREFS = "streambox_native"
    private const val K_PROGRESS = "progress"
    private const val K_LIST = "mylist"
    private const val K_ADULT = "adult_open"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun titleToJson(t: Title): JSONObject = JSONObject()
        .put("type", t.type).put("id", t.id).put("name", t.name)
        .put("poster", t.poster).put("backdrop", t.backdrop)
        .put("year", t.year).put("overview", t.overview).put("vote", t.vote)

    private fun titleFrom(o: JSONObject): Title = Title(
        id = o.optInt("id"),
        type = o.optString("type"),
        name = o.optString("name"),
        poster = if (o.isNull("poster")) null else o.optString("poster"),
        backdrop = if (o.isNull("backdrop")) null else o.optString("backdrop"),
        year = o.optInt("year"),
        overview = o.optString("overview"),
        vote = o.optDouble("vote"),
    )

    private fun progressToJson(x: Progress): JSONObject = JSONObject()
        .put("type", x.type).put("id", x.id).put("name", x.name)
        .put("poster", x.poster).put("season", x.season).put("episode", x.episode)
        .put("cur", x.cur.toDouble()).put("dur", x.dur.toDouble()).put("at", x.at)
        .put("mal", x.mal ?: JSONObject.NULL).put("ani", x.ani ?: JSONObject.NULL)

    private fun progressFrom(o: JSONObject): Progress = Progress(
        type = o.optString("type"),
        id = o.optInt("id"),
        name = o.optString("name"),
        poster = if (o.isNull("poster")) null else o.optString("poster"),
        season = o.optInt("season"),
        episode = o.optInt("episode"),
        cur = o.optDouble("cur").toFloat(),
        dur = o.optDouble("dur").toFloat(),
        at = o.optLong("at"),
        mal = if (o.isNull("mal")) null else o.optInt("mal"),
        ani = if (o.isNull("ani")) null else o.optInt("ani"),
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
}
