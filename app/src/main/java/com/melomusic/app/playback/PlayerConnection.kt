// File: app/src/main/java/com/melomusic/app/playback/PlayerConnection.kt
package com.melomusic.app.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.melomusic.app.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

@androidx.media3.common.util.UnstableApi
class PlayerConnection(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var controller: MediaController? = null
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    data class PlaybackState(
        val song: Song? = null,
        val isPlaying: Boolean = false,
        val isBuffering: Boolean = false,
        val isEnded: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val error: String? = null
    )

    fun connect(onReady: () -> Unit = {}) {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(playerListener)

            // CRITICAL FIX: Rehydrate state if MusicService is already holding a song when Activity reopens
            c.currentMediaItem?.let { item ->
                val meta = item.mediaMetadata
                val restoredSong = Song(
                    id = item.mediaId.ifBlank { "restored" },
                    title = meta.title?.toString() ?: "Unknown Track",
                    artist = meta.artist?.toString() ?: "Unknown Artist",
                    album = meta.albumTitle?.toString() ?: "",
                    thumbnailUrl = meta.artworkUri?.toString() ?: "",
                    durationText = ((c.duration.coerceAtLeast(210000L)) / 1000L).toString(),
                    encryptedMediaUrl = ""
                )
                _state.value = _state.value.copy(
                    song = _state.value.song ?: restoredSong,
                    isPlaying = c.isPlaying,
                    isBuffering = c.playbackState == Player.STATE_BUFFERING,
                    positionMs = c.currentPosition.coerceAtLeast(0L),
                    durationMs = c.duration.coerceAtLeast(0L)
                )
            }

            startPositionTicker()
            onReady()
        }, MoreExecutors.directExecutor())
    }

    /**
     * Restores the last active song into the UI state (for MiniPlayer visibility) without auto-playing.
     */
    fun restoreLastSongIfIdle(savedSong: Song) {
        if (_state.value.song == null) {
            _state.value = _state.value.copy(song = savedSong, isPlaying = false)
        }
    }

    fun release() {
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
    }

    fun play(song: Song, resolveStreamUrl: suspend (Song) -> String?) {
        scope.launch {
            _state.value = _state.value.copy(
                song = song,
                isBuffering = true,
                isEnded = false,
                error = null,
                positionMs = 0L
            )
            val rawUrl = runCatching { resolveStreamUrl(song) }.getOrNull()
            if (rawUrl.isNullOrBlank()) {
                _state.value = _state.value.copy(
                    isBuffering = false,
                    error = "Unable to resolve stream for \"${song.title}\"."
                )
                return@launch
            }

            val playableUri = when {
                rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> Uri.parse(rawUrl)
                rawUrl.startsWith("file://") -> Uri.parse(rawUrl)
                rawUrl.startsWith("content://") -> Uri.parse(rawUrl)
                else -> Uri.fromFile(File(rawUrl))
            }

            val item = MediaItem.Builder()
                .setUri(playableUri)
                .setMediaId(song.id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .setArtworkUri(Uri.parse(song.thumbnailUrl))
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()
                )
                .build()

            controller?.apply {
                setMediaItem(item)
                prepare()
                playWhenReady = true
                play()
            }
        }
    }

    fun togglePlayPause(resolveIfIdle: (() -> Unit)? = null) {
        val c = controller ?: return
        if (c.mediaItemCount == 0 && _state.value.song != null) {
            resolveIfIdle?.invoke()
            return
        }
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        _state.value = _state.value.copy(positionMs = ms)
    }

    fun stop() {
        controller?.stop()
        controller?.clearMediaItems()
        _state.value = PlaybackState()
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.value = _state.value.copy(
                isBuffering = playbackState == Player.STATE_BUFFERING,
                isEnded = playbackState == Player.STATE_ENDED,
                durationMs = controller?.duration?.coerceAtLeast(0L) ?: 0L
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(
                isBuffering = false,
                isPlaying = false,
                error = "${error.errorCodeName}: ${error.message}"
            )
        }
    }

    private fun startPositionTicker() {
        scope.launch(Dispatchers.Main) {
            while (true) {
                controller?.let { c ->
                    if (c.isPlaying) {
                        _state.value = _state.value.copy(
                            positionMs = c.currentPosition,
                            durationMs = c.duration.coerceAtLeast(0L)
                        )
                    }
                }
                delay(80)
            }
        }
    }
}