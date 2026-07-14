package com.freedomfighter.retreatplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The two fixed homepage sections, plus NEW where freshly loaded recordings land
 * until the user files them. Bells & chanting sits on top of the page because it
 * is used every day.
 */
enum class Category(val key: String, val label: String) {
    BELLS("bells", "Bells & chanting"),
    TALKS("talks", "Dharma talks"),
    NEW("new", "Just loaded — move into a category");

    companion object {
        fun from(key: String): Category = entries.firstOrNull { it.key == key } ?: NEW
    }
}

/**
 * One recording on the homepage. [fileName] always points inside the app-private
 * recordings folder — every import is copied/downloaded there first, so playback
 * works in airplane mode and can never lose access to its file.
 */
data class Recording(
    val id: Long,
    val fileName: String,
    val title: String,
    val category: Category,
    val durationMs: Long,
) {
    fun file(ctx: Context): File = File(RecordingStore.recordingsDir(ctx), fileName)
}

/**
 * Plain-SharedPreferences persistence, same philosophy as Retreat Timer: tiny and
 * dependency-free so nothing can fail between "loaded" and "plays". The master
 * list order IS the manual sort order; each category shows its members in master
 * order.
 */
object RecordingStore {
    private const val PREFS = "retreat_player"
    private const val KEY_RECORDINGS = "recordings"
    private const val KEY_NEXT_ID = "next_id"
    private const val KEY_WEBDAV_URL = "webdav_url"
    private const val KEY_WEBDAV_USER = "webdav_user"
    private const val KEY_WEBDAV_PASS = "webdav_pass"
    private const val KEY_PODCAST_URL = "podcast_url"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Recording> {
        val raw = prefs(ctx).getString(KEY_RECORDINGS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Recording(
                    id = o.getLong("id"),
                    fileName = o.getString("fileName"),
                    title = o.getString("title"),
                    category = Category.from(o.optString("category", Category.NEW.key)),
                    durationMs = o.optLong("durationMs", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, recordings: List<Recording>) {
        val arr = JSONArray()
        recordings.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("fileName", r.fileName)
                put("title", r.title)
                put("category", r.category.key)
                put("durationMs", r.durationMs)
            })
        }
        prefs(ctx).edit().putString(KEY_RECORDINGS, arr.toString()).apply()
    }

    /** Move a recording one step up or down *within its own category*. */
    fun moved(list: List<Recording>, id: Long, delta: Int): List<Recording> {
        val item = list.firstOrNull { it.id == id } ?: return list
        val siblings = list.filter { it.category == item.category }
        val pos = siblings.indexOfFirst { it.id == id }
        val target = pos + delta
        if (target !in siblings.indices) return list
        val neighbour = siblings[target]
        val i = list.indexOfFirst { it.id == id }
        val j = list.indexOfFirst { it.id == neighbour.id }
        val mutable = list.toMutableList()
        mutable[i] = neighbour
        mutable[j] = item
        return mutable
    }

    /** Re-file into [category], landing at the bottom of that section. */
    fun recategorized(list: List<Recording>, id: Long, category: Category): List<Recording> {
        val item = list.firstOrNull { it.id == id } ?: return list
        return list.filterNot { it.id == id } + item.copy(category = category)
    }

    /** Monotonic id generator so every recording keeps a stable identity. */
    fun nextId(ctx: Context): Long {
        val p = prefs(ctx)
        val id = p.getLong(KEY_NEXT_ID, 1L)
        p.edit().putLong(KEY_NEXT_ID, id + 1).apply()
        return id
    }

    // ---- Remembered import sources: entered once, pre-filled forever after ----

    fun webdavUrl(ctx: Context): String = prefs(ctx).getString(KEY_WEBDAV_URL, "") ?: ""
    fun setWebdavUrl(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_WEBDAV_URL, v).apply()

    fun webdavUser(ctx: Context): String = prefs(ctx).getString(KEY_WEBDAV_USER, "") ?: ""
    fun setWebdavUser(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_WEBDAV_USER, v).apply()

    fun webdavPass(ctx: Context): String = prefs(ctx).getString(KEY_WEBDAV_PASS, "") ?: ""
    fun setWebdavPass(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_WEBDAV_PASS, v).apply()

    fun podcastUrl(ctx: Context): String = prefs(ctx).getString(KEY_PODCAST_URL, "") ?: ""
    fun setPodcastUrl(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_PODCAST_URL, v).apply()

    /** App-private folder holding every loaded recording — available offline. */
    fun recordingsDir(ctx: Context): File = File(ctx.filesDir, "recordings").apply { mkdirs() }
}
