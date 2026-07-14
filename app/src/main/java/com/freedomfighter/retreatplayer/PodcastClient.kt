package com.freedomfighter.retreatplayer

import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Episode(
    val title: String,
    val url: String,
    val sizeBytes: Long,
    /** From itunes:duration when the feed provides it ("00:39:55", "39:55" or seconds). */
    val durationMs: Long,
)

/**
 * Fetches a podcast RSS feed and lists its audio episodes: item title,
 * enclosure url/length, itunes:duration. Built against the feeds we generate in
 * notable-dhamma-teachers (podcastify.py) and dharmaseed-style feeds, whose
 * enclosure URLs may carry query strings and redirect — OkHttp follows those.
 * Blocking; run off the main thread.
 */
object PodcastClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    fun fetch(feedUrl: String): List<Episode> {
        val request = Request.Builder().url(feedUrl).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Feed answered HTTP ${resp.code}")
            return parse(resp.body?.string() ?: throw IOException("Empty feed"))
        }
    }

    fun download(url: String, dest: java.io.File, onProgress: (Float) -> Unit = {}) =
        WebDavClient.download(url, "", "", dest, onProgress)

    private fun parse(xml: String): List<Episode> {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(xml.reader())

        val episodes = mutableListOf<Episode>()
        var inItem = false
        var title: String? = null
        var url: String? = null
        var size = 0L
        var durationMs = 0L

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val local = parser.name?.lowercase()
            when (event) {
                XmlPullParser.START_TAG -> when {
                    local == "item" -> { inItem = true; title = null; url = null; size = 0L; durationMs = 0L }
                    inItem && local == "title" && title == null ->
                        title = runCatching { parser.nextText().trim() }.getOrNull()
                    inItem && local == "enclosure" -> {
                        val type = parser.getAttributeValue(null, "type") ?: ""
                        val u = parser.getAttributeValue(null, "url")
                        if (u != null && (type.startsWith("audio") || type.isEmpty())) {
                            url = u
                            size = parser.getAttributeValue(null, "length")?.toLongOrNull() ?: 0L
                        }
                    }
                    inItem && local == "duration" ->
                        durationMs = parseDuration(runCatching { parser.nextText().trim() }.getOrDefault(""))
                }
                XmlPullParser.END_TAG -> if (local == "item") {
                    inItem = false
                    val t = title
                    val u = url
                    if (t != null && u != null) episodes.add(Episode(t, u, size, durationMs))
                }
            }
            event = parser.next()
        }
        return episodes
    }

    /** "00:39:55" → ms; "39:55" → ms; "2395" (seconds) → ms; junk → 0. */
    private fun parseDuration(text: String): Long {
        if (text.isBlank()) return 0L
        val parts = text.split(":").map { it.trim().toLongOrNull() ?: return 0L }
        return when (parts.size) {
            1 -> parts[0] * 1000
            2 -> (parts[0] * 60 + parts[1]) * 1000
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
            else -> 0L
        }
    }
}
