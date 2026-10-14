package me.masonasons.fastplay.playback

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.media.session.MediaButtonReceiver

/**
 * Keeps FastPlay playing with the screen off or another app in front: a foreground
 * service whose notification is the media notification (PlaybackController
 * builds it). It is in the foreground while something plays; paused, the
 * notification stays, to play again from, and the service lets the system have
 * the process back if it needs it.
 */
class PlaybackService : Service() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        goForeground(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A media button with FastPlay not in front comes here
        MediaButtonReceiver.handleIntent(PlaybackController.session, intent)
        goForeground(PlaybackController.session.controller.playbackState?.state ==
            android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING)
        return START_NOT_STICKY
    }

    private fun goForeground(playing: Boolean) {
        val notification = PlaybackController.buildNotification(playing = playing)
        ServiceCompat.startForeground(
            this, PlaybackController.NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
    }

    /** Playing or paused: the notification shows which. */
    fun refresh(playing: Boolean) {
        if (playing) {
            goForeground(true)
        } else {
            // Out of the foreground, the notification left to play again from
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            getSystemService(NotificationManager::class.java)
                .notify(PlaybackController.NOTIFICATION_ID, PlaybackController.buildNotification(playing = false))
        }
    }

    /** Nothing loaded any more. */
    fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        PlaybackController.serviceEnded()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        var instance: PlaybackService? = null
            private set
    }
}
