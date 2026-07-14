package com.freedomfighter.retreatplayer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Process-wide snapshot of what [PlayerService] is currently playing. The service
 * (same process) writes it on the main thread; Compose observes it to draw the
 * player. Null [recordingId] means nothing is loaded.
 */
object PlaybackState {
    var recordingId by mutableStateOf<Long?>(null)
    var title by mutableStateOf<String?>(null)
    var isPlaying by mutableStateOf(false)
    var positionMs by mutableStateOf(0)
    var durationMs by mutableStateOf(0)

    val remainingMs: Int get() = (durationMs - positionMs).coerceAtLeast(0)

    fun clear() {
        recordingId = null
        title = null
        isPlaying = false
        positionMs = 0
        durationMs = 0
    }
}

/** Format milliseconds as M:SS, or H:MM:SS once past an hour. */
fun formatClock(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Compact duration for tiles: "58 min", "1 h 02". */
fun formatDuration(ms: Long): String {
    val totalMin = ms / 60_000
    return when {
        ms <= 0 -> ""
        totalMin < 1 -> "< 1 min"
        totalMin < 60 -> "$totalMin min"
        else -> "%d h %02d".format(totalMin / 60, totalMin % 60)
    }
}
