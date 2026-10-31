package com.trovic.grab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("grab", Context.MODE_PRIVATE)

    fun lastHandled(c: Context): String? = p(c).getString("last_url", null)
    fun markHandled(c: Context, url: String) = p(c).edit().putString("last_url", url).apply()

    fun autoGrab(c: Context) = p(c).getBoolean("auto", true)
    fun setAutoGrab(c: Context, on: Boolean) = p(c).edit().putBoolean("auto", on).apply()

    /** What the in-app Download button and auto-download save: [Mode.VIDEO] or [Mode.AUDIO]. */
    fun mode(c: Context): String = p(c).getString("mode", Mode.VIDEO) ?: Mode.VIDEO
    fun setMode(c: Context, m: String) = p(c).edit().putString("mode", m).apply()

    fun lastEngineUpdate(c: Context) = p(c).getLong("engine_update", 0L)
    fun setLastEngineUpdate(c: Context, t: Long) = p(c).edit().putLong("engine_update", t).apply()
}

object Mode {
    const val VIDEO = "video" // video or picture, as posted
    const val AUDIO = "audio" // MP3 of the video's sound
}

/** Small list of recent downloads, stored as JSON in preferences. */
object History {
    data class Entry(
        val id: String,
        val url: String,
        val state: String, // queued | running | done | failed
        val message: String,
        val time: Long,
        val uri: String?,
        val mime: String?,
        val mode: String = Mode.VIDEO,
    )

    private const val MAX = 40
    private val lock = Any()
    private fun p(c: Context) = c.getSharedPreferences("grab_history", Context.MODE_PRIVATE)

    fun all(c: Context): List<Entry> = synchronized(lock) {
        val arr = JSONArray(p(c).getString("items", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(
                o.getString("id"), o.getString("url"), o.getString("state"),
                o.optString("message"), o.getLong("time"),
                o.optString("uri").ifEmpty { null }, o.optString("mime").ifEmpty { null },
                o.optString("mode").ifEmpty { Mode.VIDEO },
            )
        }
    }

    fun put(c: Context, e: Entry): Unit = synchronized(lock) {
        val list = all(c).filterNot { it.id == e.id }.toMutableList()
        list.add(0, e)
        list.sortByDescending { it.time }
        val arr = JSONArray()
        list.take(MAX).forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("url", it.url); put("state", it.state)
                put("message", it.message); put("time", it.time)
                put("uri", it.uri ?: ""); put("mime", it.mime ?: ""); put("mode", it.mode)
            })
        }
        p(c).edit().putString("items", arr.toString()).apply()
    }

    fun update(c: Context, id: String, change: (Entry) -> Entry): Unit = synchronized(lock) {
        all(c).firstOrNull { it.id == id }?.let { put(c, change(it).copy(id = it.id, time = it.time)) }
    }

    fun clear(c: Context): Unit = synchronized(lock) { p(c).edit().clear().apply() }
}
