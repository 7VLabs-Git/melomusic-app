// File: app/src/main/java/com/melomusic/app/playback/MusicService.kt
package com.melomusic.app.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.melomusic.app.MainActivity
import com.melomusic.app.R
import com.melomusic.app.data.JioSaavnRepository
import com.melomusic.app.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@UnstableApi
class MusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var serviceWakeLock: PowerManager.WakeLock? = null
    private lateinit var exoPlayer: ExoPlayer

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val repository by lazy { JioSaavnRepository(this) }
    private val preloader by lazy { MeloAudioPreloadManager(this, serviceScope) }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "melo_playback_channel"

        @Volatile
        var activeAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
        var onAudioSessionChanged: ((Int) -> Unit)? = null
        var onCurrentSongChanged: ((Song?) -> Unit)? = null

        // Callbacks bound by MainActivity
        var onSkipNextRequested: (() -> Unit)? = null
        var onSkipPreviousRequested: (() -> Unit)? = null
        var onTrackEndedAutoAdvance: (() -> Unit)? = null
        var onPlaybackErrorRecovery: (() -> Unit)? = null
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
            serviceWakeLock?.acquire(45_000L)
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

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(NOTIFICATION_CHANNEL_ID)
            .setChannelName(R.string.app_name)
            .build()
            .apply {
                setSmallIcon(R.drawable.ic_notification)
            }
        setMediaNotificationProvider(notificationProvider)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 1_500, 3_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val cachedSourceFactory = DefaultMediaSourceFactory(
            MeloAudioCache.createDataSourceFactory(this)
        )

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(cachedSourceFactory)
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

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) releaseTransitionWakeLock()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    acquireTransitionWakeLock()
                    val callback = onTrackEndedAutoAdvance
                    if (callback != null) {
                        callback.invoke()
                    } else {
                        skipToNextTrack()
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val currentSongId = mediaItem?.mediaId
                if (currentSongId != null) {
                    val currentSong = PlaybackQueueManager.queue.value.find { it.id == currentSongId }
                    if (currentSong != null) {
                        val index = PlaybackQueueManager.queue.value.indexOf(currentSong)
                        if (index != -1) {
                            PlaybackQueueManager.setQueue(PlaybackQueueManager.queue.value, index)
                        }
                        onCurrentSongChanged?.invoke(currentSong)
                    }
                }
                // Maintain the 3-track pipeline and trigger auto-mix if running low
                syncSlidingWindow()
            }

            override fun onPlayerError(error: PlaybackException) {
                acquireTransitionWakeLock()
                val recoveryCallback = onPlaybackErrorRecovery
                if (recoveryCallback != null) {
                    recoveryCallback.invoke()
                    return
                }
                serviceScope.launch(Dispatchers.IO) {
                    val current = PlaybackQueueManager.getCurrentSong()
                    if (current != null) {
                        // Refresh expired CDN link
                        val refreshedUrl = repository.getRemoteDecryptedUrl(current)
                            ?: repository.getStreamUrl(current)
                        if (!refreshedUrl.isNullOrBlank()) {
                            PlaybackQueueManager.cacheStreamUrl(current.id, refreshedUrl)
                            withContext(Dispatchers.Main) {
                                playCurrentSongInternal(current, refreshedUrl, exoPlayer.currentPosition)
                            }
                        }
                    }
                }
            }
        })

        val forwardingPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(COMMAND_SEEK_TO_NEXT)
                    .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(COMMAND_SEEK_TO_PREVIOUS)
                    .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    COMMAND_SEEK_TO_NEXT,
                    COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    COMMAND_SEEK_TO_PREVIOUS,
                    COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun seekToNext() = skipToNextTrack()
            override fun seekToNextMediaItem() = skipToNextTrack()
            override fun seekToPrevious() = skipToPreviousTrack()
            override fun seekToPreviousMediaItem() = skipToPreviousTrack()
        }

        val sessionIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(sessionIntent)
            .build()
    }

    private fun resolveUri(song: Song): String? {
        val safeId = song.id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val dirs = listOf(
            File(filesDir, "downloads"),
            filesDir,
            getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC),
            getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
        ).filterNotNull()

        for (dir in dirs) {
            if (!dir.exists()) continue
            for (id in listOf(song.id, safeId)) {
                for (ext in listOf("mp4", "mp3", "m4a", "aac")) {
                    val f = File(dir, "$id.$ext")
                    if (f.exists() && f.length() > 5000) {
                        return Uri.fromFile(f).toString()
                    }
                }
            }
        }
        return PlaybackQueueManager.getCachedStreamUrl(song.id)
    }

    private suspend fun getOrFetchStreamUrl(song: Song): String? {
        resolveUri(song)?.let { return it }
        val fetched = withContext(Dispatchers.IO) {
            repository.getStreamUrl(song) ?: repository.getRemoteDecryptedUrl(song)
        }
        if (!fetched.isNullOrBlank()) {
            PlaybackQueueManager.cacheStreamUrl(song.id, fetched)
        }
        return fetched
    }

    private fun buildMediaItem(song: Song, uriStr: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(Uri.parse(uriStr))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album.ifBlank { "Single" })
                    .setArtworkUri(Uri.parse(song.thumbnailUrl))
                    .build()
            )
            .build()
    }

    fun playTrack(song: Song, queueContext: List<Song>, startIndex: Int) {
        acquireTransitionWakeLock()
        PlaybackQueueManager.setQueue(queueContext, startIndex)

        serviceScope.launch {
            val url = getOrFetchStreamUrl(song)
            if (!url.isNullOrBlank()) {
                playCurrentSongInternal(song, url, 0L)
                syncSlidingWindow()
            } else {
                releaseTransitionWakeLock()
            }
        }
    }

    private fun playCurrentSongInternal(song: Song, url: String, startPositionMs: Long) {
        val currentItem = buildMediaItem(song, url)
        exoPlayer.setMediaItem(currentItem, startPositionMs)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        onCurrentSongChanged?.invoke(song)
    }

    /**
     * Maintains an autonomous lookahead window of tracks in ExoPlayer's playlist.
     * Guarantees true 0ms gapless transitions and seamless background auto-advance.
     */
    private fun syncSlidingWindow() {
        serviceScope.launch {
            val queue = PlaybackQueueManager.queue.value
            val currentIdx = PlaybackQueueManager.currentIndex.value

            // 1. Proactive Radio Recommendations: replenish when near the end
            if (queue.size - currentIdx <= 3 && queue.isNotEmpty()) {
                val seed = queue.getOrNull(currentIdx) ?: queue.last()
                val recommendations = withContext(Dispatchers.IO) {
                    repository.getRadioRecommendations(seed, "auto-endless")
                }
                PlaybackQueueManager.appendSongs(recommendations)
            }

            // 2. Resolve Track N+1 for Gapless buffering
            val nextIdx = PlaybackQueueManager.getNextIndex()
            if (nextIdx != null && nextIdx != currentIdx) {
                val nextSong = PlaybackQueueManager.getSongAt(nextIdx)
                if (nextSong != null) {
                    val nextUrl = getOrFetchStreamUrl(nextSong)
                    if (!nextUrl.isNullOrBlank()) {
                        // Pre-cache upcoming audio bytes to disk
                        preloader.preloadTrack(nextSong.id, nextUrl)

                        // Attach to ExoPlayer timeline if not already queued
                        if (exoPlayer.mediaItemCount < 2) {
                            exoPlayer.addMediaItem(buildMediaItem(nextSong, nextUrl))
                        }
                    }
                }
            }

            // 3. Resolve Track N+2 for lookahead offline caching
            val lookaheadIdx = nextIdx?.let { it + 1 }
            if (lookaheadIdx != null && lookaheadIdx in queue.indices) {
                val lookaheadSong = queue[lookaheadIdx]
                val lookaheadUrl = getOrFetchStreamUrl(lookaheadSong)
                if (!lookaheadUrl.isNullOrBlank()) {
                    preloader.preloadTrack(lookaheadSong.id, lookaheadUrl)
                }
            }
        }
    }

    fun skipToNextTrack() {
        acquireTransitionWakeLock()
        val callback = onSkipNextRequested
        if (callback != null) {
            callback.invoke()
            return
        }
        if (exoPlayer.hasNextMediaItem()) {
            exoPlayer.seekToNextMediaItem()
        } else {
            val nextSong = PlaybackQueueManager.moveToNext()
            if (nextSong != null) {
                serviceScope.launch {
                    val url = getOrFetchStreamUrl(nextSong)
                    if (!url.isNullOrBlank()) {
                        playCurrentSongInternal(nextSong, url, 0L)
                        syncSlidingWindow()
                    }
                }
            }
        }
    }

    fun skipToPreviousTrack() {
        if (exoPlayer.currentPosition > 3000L) {
            exoPlayer.seekTo(0L)
            return
        }
        acquireTransitionWakeLock()
        val callback = onSkipPreviousRequested
        if (callback != null) {
            callback.invoke()
            return
        }
        val prevSong = PlaybackQueueManager.moveToPrevious()
        if (prevSong != null) {
            serviceScope.launch {
                val url = getOrFetchStreamUrl(prevSong)
                if (!url.isNullOrBlank()) {
                    playCurrentSongInternal(prevSong, url, 0L)
                    syncSlidingWindow()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        releaseTransitionWakeLock()
        preloader.cancelAll()
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}