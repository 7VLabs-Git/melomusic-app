// File: app/src/main/java/com/melomusic/app/playback/MusicService.kt
package com.melomusic.app.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.melomusic.app.MainActivity

@UnstableApi
class MusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var serviceWakeLock: PowerManager.WakeLock? = null

    companion object {
        var onSkipNextRequested: (() -> Unit)? = null
        var onSkipPreviousRequested: (() -> Unit)? = null
        var onTrackEndedAutoAdvance: (() -> Unit)? = null
    }

    private fun acquireTransitionWakeLock() {
        try {
            if (serviceWakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                serviceWakeLock = pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "MeloMusic::ServiceTransitionWakeLock"
                )?.apply { setReferenceCounted(false) }
            }
            serviceWakeLock?.acquire(30_000L)
        } catch (_: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,
                50_000,
                1_500,
                3_000
            )
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(loadControl)
            .build()

        // Native ExoPlayer listener inside the Foreground Service:
        // Guarantees auto-advance fires immediately even when the screen is locked and Activity is stopped!
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    acquireTransitionWakeLock()
                    onTrackEndedAutoAdvance?.invoke()
                }
            }
        })

        val forwardingPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands()
                    .buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                if (command == Player.COMMAND_SEEK_TO_NEXT ||
                    command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                ) {
                    return true
                }
                return super.isCommandAvailable(command)
            }

            override fun seekToNext() {
                acquireTransitionWakeLock()
                onSkipNextRequested?.invoke()
            }

            override fun seekToNextMediaItem() {
                acquireTransitionWakeLock()
                onSkipNextRequested?.invoke()
            }

            override fun seekToPrevious() {
                acquireTransitionWakeLock()
                onSkipPreviousRequested?.invoke()
            }

            override fun seekToPreviousMediaItem() {
                acquireTransitionWakeLock()
                onSkipPreviousRequested?.invoke()
            }
        }

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        try {
            if (serviceWakeLock?.isHeld == true) {
                serviceWakeLock?.release()
            }
        } catch (_: Exception) {}
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}