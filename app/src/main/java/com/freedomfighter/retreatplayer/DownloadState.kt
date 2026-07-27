package com.freedomfighter.retreatplayer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Process-wide snapshot of what [DownloadService] is doing, observed by the import
 * dialog so a download's progress / ✓ / error survives closing and reopening it —
 * the download now outlives the dialog that started it.
 *
 * Keys are the per-item ids the dialog already has: a kDrive file id, or a podcast
 * episode URL.
 */
object DownloadState {
    /** Items queued or downloading. */
    var inFlight by mutableStateOf<Set<String>>(emptySet())

    /** Key → fraction downloaded, 0f..1f. */
    var progress by mutableStateOf<Map<String, Float>>(emptyMap())

    /** Titles that finished this session, so a row shows ✓. */
    var justAdded by mutableStateOf<Set<String>>(emptySet())

    /** Key → last error message, cleared when re-queued or on success. */
    var errors by mutableStateOf<Map<String, String>>(emptyMap())

    /** Bumped on every library change so the homepage reloads from storage. */
    var libraryVersion by mutableStateOf(0)
}
