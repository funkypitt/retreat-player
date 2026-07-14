package com.freedomfighter.retreatplayer

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

data class DavFile(val url: String, val name: String, val size: Long, val isDir: Boolean)

/**
 * Minimal WebDAV client for a shared Infomaniak kDrive folder (or any WebDAV
 * server): PROPFIND with Depth 1 to list, GET to download, Basic auth. Follows
 * the protocol handling proven in funky-openlib's client — tolerate both `d:`
 * prefixed and unprefixed multistatus XML, and skip the folder's own entry.
 * All calls are blocking and must run off the main thread.
 */
object WebDavClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    private fun authHeader(user: String, pass: String): String? =
        if (user.isBlank()) null else Credentials.basic(user, pass)

    fun list(folderUrl: String, user: String, pass: String): List<DavFile> {
        val url = if (folderUrl.endsWith("/")) folderUrl else "$folderUrl/"
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop><d:displayname/><d:getcontentlength/><d:resourcetype/></d:prop>
            </d:propfind>""".trimIndent()
            .toRequestBody("application/xml; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", body)
            .header("Depth", "1")
            .apply { authHeader(user, pass)?.let { header("Authorization", it) } }
            .build()

        http.newCall(request).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403)
                throw IOException("Authentication failed — check the email and app password")
            if (resp.code != 207 && !resp.isSuccessful)
                throw IOException("Server answered HTTP ${resp.code}")
            return parseMultistatus(resp.body?.string() ?: "", url)
        }
    }

    fun download(fileUrl: String, user: String, pass: String, dest: File, onProgress: (Float) -> Unit = {}) {
        val request = Request.Builder()
            .url(fileUrl)
            .apply { authHeader(user, pass)?.let { header("Authorization", it) } }
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed: HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty response")
            val total = body.contentLength()
            try {
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
            } catch (e: Exception) {
                dest.delete()
                throw e
            }
        }
    }

    // ---- PROPFIND 207 multistatus parsing (namespace-prefix tolerant) ----

    private fun parseMultistatus(xml: String, requestUrl: String): List<DavFile> {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(xml.reader())

        val files = mutableListOf<DavFile>()
        val requestPath = URI(requestUrl).path.trimEnd('/')

        var href: String? = null
        var displayName: String? = null
        var size = 0L
        var isDir = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val local = parser.name?.lowercase()
            when (event) {
                XmlPullParser.START_TAG -> when (local) {
                    "response" -> { href = null; displayName = null; size = 0L; isDir = false }
                    "href" -> href = parser.nextText().trim()
                    "displayname" -> displayName = runCatching { parser.nextText().trim() }.getOrNull()
                    "getcontentlength" -> size = runCatching { parser.nextText().trim().toLong() }.getOrDefault(0L)
                    "collection" -> isDir = true
                }
                XmlPullParser.END_TAG -> if (local == "response" && href != null) {
                    val decoded = runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href!!)
                    val path = runCatching { URI(href).path }.getOrDefault(decoded).trimEnd('/')
                    if (path.isNotEmpty() && path != requestPath) {
                        val name = displayName?.takeIf { it.isNotBlank() }
                            ?: decoded.trimEnd('/').substringAfterLast('/')
                        files.add(DavFile(url = resolve(requestUrl, href!!), name = name, size = size, isDir = isDir))
                    }
                }
            }
            event = parser.next()
        }
        return files
    }

    /** hrefs come back either absolute or server-relative; make them absolute. */
    private fun resolve(baseUrl: String, href: String): String =
        if (href.startsWith("http")) href else URI(baseUrl).resolve(href).toString()
}
