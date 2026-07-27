package com.freedomfighter.retreatplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Downloads recordings in the background so a transfer survives the screen going
 * black. The old path ran the download inside the import dialog's coroutine scope
 * with no wake lock; when the screen turned off the CPU idled (and on battery,
 * Doze cut the network outright), the socket read timed out, and the partial file
 * was thrown away.
 *
 * As a foreground service of type dataSync it is exempt from Doze's network
 * restrictions and won't be killed; a PARTIAL_WAKE_LOCK keeps the CPU running for
 * the whole transfer; and the queue lives in the service, not the dialog, so
 * closing the dialog (or the app) no longer aborts anything. Downloads run one at
 * a time with real progress, and each recording lands in the library as it
 * finishes, mirrored to the UI through [DownloadState].
 *
 * Only network picks (kDrive, podcast) come here; local file copies stay inline in
 * the dialog because they are quick and rely on the just-granted read permission.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val queue = ConcurrentLinkedQueue<Req>()
    private var worker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var total = 0
    @Volatile private var done = 0
    @Volatile private var current = ""
    @Volatile private var currentPct = 0

    private data class Req(
        val key: String,
        val title: String,
        val filename: String,
        val sizeBytes: Long,
        val durationMs: Long,
        val driveId: String?,
        val linkUuid: String?,
    ) {
        val isKdrive get() = driveId != null && linkUuid != null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val key = it.getStringExtra(EXTRA_KEY)
            val title = it.getStringExtra(EXTRA_TITLE)
            val filename = it.getStringExtra(EXTRA_FILENAME)
            if (key != null && title != null && filename != null) {
                queue.add(
                    Req(
                        key = key,
                        title = title,
                        filename = filename,
                        sizeBytes = it.getLongExtra(EXTRA_SIZE, 0L),
                        durationMs = it.getLongExtra(EXTRA_DURATION, 0L),
                        driveId = it.getStringExtra(EXTRA_DRIVE_ID),
                        linkUuid = it.getStringExtra(EXTRA_LINK_UUID),
                    ),
                )
                total++
                scope.launch(Dispatchers.Main) {
                    DownloadState.inFlight = DownloadState.inFlight + key
                    DownloadState.errors = DownloadState.errors - key
                    DownloadState.progress = DownloadState.progress + (key to 0f)
                }
            }
        }
        startDownloadForeground()
        ensureWorker()
        return START_NOT_STICKY // in-memory queue: a null-intent relaunch has nothing to do
    }

    @Synchronized
    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch { runQueue() }
    }

    private suspend fun runQueue() {
        acquireWakeLock()
        try {
            while (true) {
                val req = queue.poll() ?: break
                current = req.title
                currentPct = 0
                pushNotification()
                val result = runCatching {
                    val dest = File(RecordingStore.recordingsDir(this@DownloadService), req.filename)
                    val onProgress: (Float) -> Unit = { f -> onProgress(req.key, f) }
                    if (req.isKdrive) {
                        KDriveClient.downloadFile(
                            KDriveConfig(req.driveId!!, req.linkUuid!!, 0), req.key, req.sizeBytes, dest, onProgress,
                        )
                    } else {
                        Http.download(req.key, dest, req.sizeBytes, onProgress)
                    }
                    dest
                }
                done++
                withContext(Dispatchers.Main) { record(req, result) }
            }
        } finally {
            releaseWakeLock()
            finishIfIdle()
        }
    }

    /** Reflect transfer progress to the UI and (throttled) the notification. */
    private fun onProgress(key: String, fraction: Float) {
        val pct = (fraction * 100).toInt().coerceIn(0, 100)
        scope.launch(Dispatchers.Main) {
            DownloadState.progress = DownloadState.progress + (key to fraction)
        }
        if (pct != currentPct) {
            currentPct = pct
            pushNotification()
        }
    }

    /** Register the finished file (or its error). Runs on the main thread so the
     *  read-modify-write of the recording list stays consistent with the UI's edits. */
    private fun record(req: Req, result: Result<File>) {
        DownloadState.inFlight = DownloadState.inFlight - req.key
        DownloadState.progress = DownloadState.progress - req.key
        result.onSuccess { dest ->
            val duration = if (req.durationMs > 0) req.durationMs else probeDuration(dest)
            val rec = Recording(RecordingStore.nextId(this), dest.name, req.title, Category.NEW, duration)
            RecordingStore.save(this, RecordingStore.load(this) + rec)
            DownloadState.justAdded = DownloadState.justAdded + req.title
            DownloadState.libraryVersion++
        }.onFailure {
            DownloadState.errors = DownloadState.errors + (req.key to (it.message ?: "Download failed"))
        }
    }

    @Synchronized
    private fun finishIfIdle() {
        if (queue.isEmpty()) {
            total = 0
            done = 0
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            worker = scope.launch { runQueue() }
        }
    }

    private fun startDownloadForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RetreatPlayer:download").apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L) // safety cap; released as soon as the queue drains
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun pushNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): android.app.Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Downloads recordings in the background." },
            )
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = current.ifBlank { "Downloading" }
        val text = if (total > 1) "Downloading ($currentPct%)  ·  ${(done + 1).coerceAtMost(total)}/$total" else "Downloading ($currentPct%)"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, currentPct, currentPct == 0)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "retreat_download"
        private const val NOTIF_ID = 9
        private const val EXTRA_KEY = "key"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_FILENAME = "filename"
        private const val EXTRA_SIZE = "size"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_DRIVE_ID = "drive_id"
        private const val EXTRA_LINK_UUID = "link_uuid"

        /** Queue a network download. For a podcast, [key] is the enclosure URL that
         *  gets fetched; for kDrive, [key] is the file id and [kdrive] carries the
         *  share so the service can rebuild the download URL without holding config. */
        fun enqueue(
            ctx: Context,
            key: String,
            title: String,
            filename: String,
            sizeBytes: Long,
            durationMs: Long,
            kdrive: KDriveConfig?,
        ) {
            val i = Intent(ctx, DownloadService::class.java).apply {
                putExtra(EXTRA_KEY, key)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_FILENAME, filename)
                putExtra(EXTRA_SIZE, sizeBytes)
                putExtra(EXTRA_DURATION, durationMs)
                kdrive?.let {
                    putExtra(EXTRA_DRIVE_ID, it.driveId)
                    putExtra(EXTRA_LINK_UUID, it.linkUuid)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }
    }
}
