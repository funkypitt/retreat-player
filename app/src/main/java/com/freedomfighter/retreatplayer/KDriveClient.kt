package com.freedomfighter.retreatplayer

import org.json.JSONObject
import java.io.File
import java.io.IOException

data class KDriveConfig(val driveId: String, val linkUuid: String, val folderId: Int)

data class RemoteFile(val id: String, val name: String, val size: Long, val isDir: Boolean)

/**
 * Reads an Infomaniak kDrive *public share* link and downloads files from it —
 * no account, no password, read-only by construction. Same public API used by
 * Magazine Reader and Retreat Timer.
 *
 * Flow: parse the share URL → `init` (resolve the root folder id) → `list`
 * (paginated) → `download` (stream a file by id). All calls are blocking and
 * must run off the main thread.
 */
object KDriveClient {
    private const val BASE = "https://kdrive.infomaniak.com"

    /** Extract (driveId, linkUuid) from a link like
     *  https://kdrive.infomaniak.com/app/share/123456/abcd-… */
    fun parseShareUrl(url: String): Pair<String, String>? {
        val m = Regex("""/app/share/(\d+)/([a-z0-9-]+)""").find(url) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    fun init(driveId: String, linkUuid: String): KDriveConfig {
        val body = Http.getString("$BASE/2/app/$driveId/share/$linkUuid/init")
        val data = JSONObject(body).optJSONObject("data")
            ?: throw IOException("No data in init response")
        val right = data.optString("right")
        if (right != "public") throw IOException("This share is not public (right=$right)")
        val folderId = data.optInt("file_id", -1)
        if (folderId < 0) throw IOException("No folder id in init response")
        return KDriveConfig(driveId, linkUuid, folderId)
    }

    fun listFiles(config: KDriveConfig): List<RemoteFile> {
        val all = mutableListOf<RemoteFile>()
        var cursor: String? = null
        var hasMore = true
        while (hasMore) {
            val url = buildString {
                append("$BASE/3/app/${config.driveId}/share/${config.linkUuid}")
                append("/files/${config.folderId}/files")
                append("?order_by=last_modified_at&order=desc&limit=50")
                if (cursor != null) append("&cursor=$cursor")
            }
            val root = JSONObject(Http.getString(url))
            val arr = root.optJSONArray("data") ?: throw IOException("No data array in list response")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name", "")
                if (name.isEmpty()) continue
                all.add(
                    RemoteFile(
                        id = o.optInt("id").toString(),
                        name = name,
                        size = o.optLong("size", 0L),
                        isDir = o.optString("type", "file") == "dir",
                    ),
                )
            }
            hasMore = root.optBoolean("has_more", false)
            cursor = if (root.isNull("cursor")) null else root.optString("cursor", null)
            if (cursor == null) hasMore = false
        }
        return all
    }

    fun downloadFile(
        config: KDriveConfig,
        fileId: String,
        expectedSize: Long,
        dest: File,
        onProgress: (Float) -> Unit = {},
    ) {
        val url = "$BASE/2/app/${config.driveId}/share/${config.linkUuid}/files/$fileId/download"
        Http.download(url, dest, expectedSize, onProgress)
    }
}
