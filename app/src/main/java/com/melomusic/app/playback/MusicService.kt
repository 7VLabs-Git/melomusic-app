// File: app/src/main/java/com/melomusic/app/playback/MusicService.kt
package com.melomusic.app.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
        var onPlaybackErrorRecovery: ((PlaybackException) -> Unit)? = null

        @Volatile
        var activeAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
        var onAudioSessionChanged: ((Int) -> Unit)? = null
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
            // Hold CPU awake for up to 30s while next track URL resolves over network
            serviceWakeLock?.acquire(30_000L)
        } catch (_: Exception) {}
    }

    private fun releaseTransitionWakeLock() {
        try {
            if (serviceWakeLock?.isHeld == true) {
                serviceWakeLock?.release()
            }
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
                15_000, // Min buffer
                50_000, // Max buffer
                1_500,  // Buffer for playback start
                3_000   // Buffer after rebuffer
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Wire in the 512MB LRU disk cache so streamed songs are cached automatically
        val cachedMediaSourceFactory = DefaultMediaSourceFactory(
            MeloAudioCache.createDataSourceFactory(this)
        )

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(cachedMediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(loadControl)
            .build()

        activeAudioSessionId = exoPlayer.audioSessionId

        exoPlayer.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                activeAudioSessionId = audioSessionId
                if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                    onAudioSessionChanged?.invoke(audioSessionId)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        acquireTransitionWakeLock()
                        onTrackEndedAutoAdvance?.invoke()
                    }
                    Player.STATE_READY -> {
                        // Once ExoPlayer is buffered and ready, C.WAKE_MODE_NETWORK takes over
                        if (exoPlayer.playWhenReady) {
                            releaseTransitionWakeLock()
                        }
                    }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                acquireTransitionWakeLock()
                onPlaybackErrorRecovery?.invoke(error)
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
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
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
        releaseTransitionWakeLock()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}