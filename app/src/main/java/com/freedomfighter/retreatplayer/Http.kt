package com.freedomfighter.retreatplayer

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal blocking HTTP GET helpers with manual redirect following — the same
 * code proven in Retreat Timer's kDrive client. Keeps the app dependency-free.
 * All calls must run off the main thread.
 */
object Http {

    fun getString(url: String): String =
        open(url).second.use { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * Stream [url] into [dest]; deletes the partial file and rethrows on failure.
     * [expectedSize] drives the progress callback when the server sends no
     * Content-Length (kDrive share downloads sometimes chunk).
     */
    fun download(url: String, dest: File, expectedSize: Long = 0L, onProgress: (Float) -> Unit = {}) {
        val (length, stream) = open(url)
        val total = if (length > 0) length else expectedSize
        try {
            stream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
    }

    private fun open(urlStr: String, redirects: Int = 0): Pair<Long, InputStream> {
        if (redirects > 5) throw IOException("Too many redirects")
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = false
        }
        val code = conn.responseCode
        if (code in 300..399) {
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            if (location.isNullOrEmpty()) throw IOException("Redirect with no Location")
            return open(URL(URL(urlStr), location).toString(), redirects + 1)
        }
        if (code !in 200..299) {
            val err = runCatching { conn.errorStream?.readBytes()?.toString(Charsets.UTF_8) }.getOrNull()
            conn.disconnect()
            throw IOException("HTTP $code${if (err != null) ": ${err.take(200)}" else ""}")
        }
        return conn.contentLengthLong to conn.inputStream
    }
}
