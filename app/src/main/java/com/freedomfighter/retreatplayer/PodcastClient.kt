package com.freedomfighter.retreatplayer

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

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
 * enclosure URLs may carry query strings and redirect — [Http] follows those.
 * Blocking; run off the main thread.
 */
object PodcastClient {

    fun fetch(feedUrl: String): List<Episode> = parse(Http.getString(feedUrl))

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
