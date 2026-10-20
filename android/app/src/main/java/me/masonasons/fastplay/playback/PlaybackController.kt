package me.masonasons.fastplay.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import me.masonasons.fastplay.MainActivity
import me.masonasons.fastplay.R
import me.masonasons.fastplay.engine.Engine

/**
 * What the iPhone's audio session and Now Playing do, on Android: the media
 * session (the lock screen, the notification, headphone and Bluetooth buttons,
 * TalkBack's two finger double tap), audio focus (a call or another player pauses
 * FastPlay, and it plays on after a short interruption), pausing when headphones
 * are unplugged, and the foreground service that keeps playback going with the
 * screen off.
 */
object PlaybackController {
    private const val CHANNEL = "playback"
    const val NOTIFICATION_ID = 1

    private lateinit var context: Context
    lateinit var session: MediaSessionCompat
        private set
    private val main = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    /** Paused by a short loss of focus (a call, a navigation prompt): plays on after. */
    private var pausedForFocus = false
    private var noisyRegistered = false
    private var serviceRunning = false

    // Position and the like twice a second while playing, as the iPhone's tick
    private val tick = object : Runnable {
        override fun run() {
            update()
            Engine.keepPlaceSaved()
            if (Engine.playing) main.postDelayed(this, 500)
        }
    }

    fun init(context: Context) {
        this.context = context.applicationContext
        audio = context.getSystemService(AudioManager::class.java)
        createChannel()
        session = MediaSessionCompat(context, "FastPlay").apply {
            setCallback(Commands)
            setSessionActivity(
                PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        Engine.addStateListener { update() }
        // The output is about to start: ask for the sound first
        Engine.addDeviceStartListener { main.post { requestFocus() } }
        update()
    }

    /** The media buttons, wherever they come from. */
    private object Commands : MediaSessionCompat.Callback() {
        override fun onPlay() = Engine.play()
        override fun onPause() = Engine.pause()
        override fun onStop() = Engine.pause()
        override fun onSkipToNext() = Engine.nextTrack()
        override fun onSkipToPrevious() = Engine.previousTrack()
        override fun onFastForward() = Engine.seekStep(1)
        override fun onRewind() = Engine.seekStep(-1)
        override fun onSeekTo(pos: Long) = Engine.seek(pos / 1000.0)
    }

    // ---- Audio focus ----

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        main.post {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // Another player took over: paused for good
                    hasFocus = false
                    pausedForFocus = false
                    if (Engine.playing) Engine.pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    // A phone call or an alarm: paused until it is given back
                    if (Engine.playing) {
                        pausedForFocus = true
                        Engine.pause()
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    hasFocus = true
                    if (pausedForFocus) {
                        pausedForFocus = false
                        Engine.play()
                    }
                }
                // A short sound over it (a navigation prompt): the system lowers FastPlay itself
                else -> Unit
            }
        }
    }

    private fun requestFocus() {
        if (Engine.mixWithOthers || hasFocus) return
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
            .also { focusRequest = it }
        hasFocus = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    // ---- Headphones unplugged: pause, rather than carry on aloud ----

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && Engine.playing) Engine.pause()
        }
    }

    // ---- What the system shows ----

    /** Brings the session, the notification and the service up to date with the engine. */
    fun update() {
        val loaded = Engine.loaded
        val playing = Engine.playing

        if (playing && !noisyRegistered) {
            ContextCompat.registerReceiver(
                context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyRegistered = true
        } else if (!playing && noisyRegistered) {
            context.unregisterReceiver(noisy)
            noisyRegistered = false
        }

        val state = when {
            !loaded -> PlaybackStateCompat.STATE_NONE
            playing -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val length = Engine.length
        var actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND
        if (Engine.trackCount > 1) {
            actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        }
        if (length > 0) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, (Engine.position * 1000).toLong(), if (playing) 1f else 0f)
                .setActions(actions)
                .build(),
        )
        val title = Engine.title
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, Engine.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, if (length > 0) (length * 1000).toLong() else -1L)
                .build(),
        )
        // With Mix With Others on, the lock screen and headphone controls stay with the other app
        session.isActive = loaded && !Engine.mixWithOthers

        if (loaded) {
            if (playing) {
                main.removeCallbacks(tick)
                main.postDelayed(tick, 500)
            }
            showNotification(title, playing)
        } else {
            hideNotification()
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW).apply {
            description = "What is playing, with play, pause and skip"
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun buildNotification(title: String = Engine.title, playing: Boolean = Engine.playing) =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifEmpty { "FastPlay" })
            .setContentText(Engine.artist)
            .setContentIntent(session.controller.sessionActivity)
            .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_STOP))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(android.R.drawable.ic_media_previous, "Previous track",
                MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_PLAY_PAUSE),
            )
            .addAction(android.R.drawable.ic_media_next, "Next track",
                MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()

    private fun showNotification(title: String, playing: Boolean) {
        if (playing && !serviceRunning) {
            // The service keeps the process playing with the screen off
            serviceRunning = true
            ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
            return
        }
        if (serviceRunning) PlaybackService.instance?.refresh(playing)
        else if (canNotify()) {
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(title, playing))
        }
    }

    private fun hideNotification() {
        if (serviceRunning) {
            serviceRunning = false
            PlaybackService.instance?.finish()
        }
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    internal fun serviceEnded() {
        serviceRunning = false
    }

    private fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
}
