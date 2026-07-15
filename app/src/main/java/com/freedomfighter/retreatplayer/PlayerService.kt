package com.freedomfighter.retreatplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that plays exactly one recording, with the same
 * non-interruption guarantee as Retreat Timer's bells: foreground type
 * mediaPlayback (Android will not kill it mid-playback), MediaPlayer wake mode
 * keeping the CPU awake through deep sleep.
 *
 * Only ONE recording may sound at a time. Audio focus is one-directional by
 * design: starting playback CLAIMS exclusive focus, so any well-behaved player
 * on the phone pauses — but focus-loss callbacks are deliberately ignored, so
 * nothing on the phone can pause us.
 *
 * Deliberately NO auto-advance: when the recording ends the service stops.
 * A recording plays only because the user pressed play on it.
 */
class PlayerService : Service() {

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var startupLock: PowerManager.WakeLock? = null
    private var currentTitle: String? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var ticker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finish()
            ACTION_TOGGLE -> togglePause()
            ACTION_BACK10 -> seekBy(-10_000)
            ACTION_FWD10 -> seekBy(+10_000)
            ACTION_RESTART -> seekToStart()
            else -> startPlayback(intent)
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(intent: Intent?) {
        val path = intent?.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Recording"
        val recordingId = intent.getLongExtra(EXTRA_ID, -1L)
        currentTitle = title

        // Replace any in-progress playback.
        ticker?.cancel()
        runCatching { player?.release() }
        player = null

        startPlayerForeground(buildNotification(playing = true, position = 0, duration = 0))
        acquireStartupLock()
        claimAudioFocus()

        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setWakeMode(this@PlayerService, PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(this@PlayerService, Uri.fromFile(java.io.File(path)))
                // One recording, once: completion stops the service, nothing queues.
                setOnCompletionListener { finish() }
                setOnErrorListener { _, _, _ -> finish(); true }
                prepare()
                start()
            }
            PlaybackState.recordingId = recordingId
            PlaybackState.title = title
            PlaybackState.durationMs = player?.duration ?: 0
            PlaybackState.positionMs = 0
            PlaybackState.isPlaying = true
            startTicker()
        }.onFailure { finish() }
    }

    private fun togglePause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            PlaybackState.isPlaying = false
            ticker?.cancel()
        } else {
            p.start()
            PlaybackState.isPlaying = true
            startTicker()
        }
        pushNotification()
    }

    private fun seekBy(deltaMs: Int) {
        val p = player ?: return
        val target = (p.currentPosition + deltaMs).coerceIn(0, p.duration)
        runCatching { p.seekTo(target) }
        PlaybackState.positionMs = target
        pushNotification()
    }

    private fun seekToStart() {
        val p = player ?: return
        runCatching { p.seekTo(0) }
        PlaybackState.positionMs = 0
        pushNotification()
    }

    /** Drive the progress readouts (elapsed and remaining) while playing. */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val p = player ?: break
                PlaybackState.positionMs = runCatching { p.currentPosition }.getOrDefault(0)
                PlaybackState.durationMs = runCatching { p.duration }.getOrDefault(0)
                pushNotification()
                delay(500)
            }
        }
    }

    // ---- notification ----

    private fun pushNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIF_ID,
            buildNotification(PlaybackState.isPlaying, PlaybackState.positionMs, PlaybackState.durationMs),
        )
    }

    private fun buildNotification(playing: Boolean, position: Int, duration: Int): android.app.Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_playback),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.channel_playback_desc) }
            nm.createNotificationChannel(channel)
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val remaining = (duration - position).coerceAtLeast(0)
        val text = if (duration > 0)
            "${formatClock(position)} elapsed  ·  ${formatClock(remaining)} left"
        else "Playing…"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_play_note)
            .setContentIntent(tap)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(android.R.drawable.ic_media_rew, getString(R.string.back10), servicePendingIntent(3, ACTION_BACK10))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                getString(if (playing) R.string.pause else R.string.play),
                servicePendingIntent(2, ACTION_TOGGLE),
            )
            .addAction(android.R.drawable.ic_media_ff, getString(R.string.fwd10), servicePendingIntent(4, ACTION_FWD10))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop), servicePendingIntent(1, ACTION_STOP))

        if (duration > 0) builder.setProgress(duration, position, false)
        return builder.build()
    }

    private fun servicePendingIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this, requestCode, Intent(this, PlayerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun startPlayerForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /** Exclusive focus: other players stop; our listener ignores every change,
     *  so no app can do the same to us. */
    private fun claimAudioFocus() {
        if (focusRequest != null) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { /* non-interruption: never react */ }
            .build()
        focusRequest = req
        am.requestAudioFocus(req)
    }

    private fun releaseAudioFocus() {
        focusRequest?.let {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching { am.abandonAudioFocusRequest(it) }
        }
        focusRequest = null
    }

    private fun acquireStartupLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        startupLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RetreatPlayer:start").apply {
            setReferenceCounted(false)
            acquire(30_000L)
        }
    }

    private fun finish() {
        ticker?.cancel()
        runCatching { player?.release() }
        player = null
        releaseAudioFocus()
        runCatching { if (startupLock?.isHeld == true) startupLock?.release() }
        startupLock = null
        PlaybackState.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        finish()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.freedomfighter.retreatplayer.STOP"
        const val ACTION_TOGGLE = "com.freedomfighter.retreatplayer.TOGGLE"
        const val ACTION_BACK10 = "com.freedomfighter.retreatplayer.BACK10"
        const val ACTION_FWD10 = "com.freedomfighter.retreatplayer.FWD10"
        const val ACTION_RESTART = "com.freedomfighter.retreatplayer.RESTART"
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ID = "id"
        private const val CHANNEL_ID = "retreat_playback"
        private const val NOTIF_ID = 11

        fun play(ctx: Context, recording: Recording) {
            val i = Intent(ctx, PlayerService::class.java).apply {
                putExtra(EXTRA_PATH, recording.file(ctx).absolutePath)
                putExtra(EXTRA_TITLE, recording.title)
                putExtra(EXTRA_ID, recording.id)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun toggle(ctx: Context) = command(ctx, ACTION_TOGGLE)
        fun stop(ctx: Context) = command(ctx, ACTION_STOP)
        fun back10(ctx: Context) = command(ctx, ACTION_BACK10)
        fun fwd10(ctx: Context) = command(ctx, ACTION_FWD10)
        fun restart(ctx: Context) = command(ctx, ACTION_RESTART)

        private fun command(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, PlayerService::class.java).setAction(action))
        }
    }
}
