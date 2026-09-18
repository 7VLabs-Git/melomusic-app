package com.melomusic.app.playback

import android.content.ComponentName
import android.content.Context
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

/**
 * THE MISSING PIECE.
 *
 * A MediaSessionService does not play anything on its own — it is a host. Something has
 * to connect to its session as a MediaController and issue setMediaItem/prepare/play.
 * Nothing in the uploaded project ever did that, so even a perfectly valid stream URL
 * would have produced silence.
 *
 * Create one of these in your Application or a ViewModel, call connect() once, and
 * drive everything through it.
 */
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
            startPositionTicker()
            onReady()
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
    }

    /**
     * Resolve the stream, hand it to the session, play.
     * `resolveStreamUrl` is suspending, so this must not be called from a
     * non-cancellable context.
     */
    fun play(song: Song, resolveStreamUrl: suspend (Song) -> String?) {
        scope.launch {
            _state.value = _state.value.copy(
                song = song,
                isBuffering = true,
                error = null,
                positionMs = 0L
            )

            val url = runCatching { resolveStreamUrl(song) }.getOrNull()

            if (url.isNullOrBlank()) {
                _state.value = _state.value.copy(
                    isBuffering = false,
                    error = "No playable stream for \"${song.title}\"."
                )
                return@launch
            }

            val item = MediaItem.Builder()
                .setUri(url)
                .setMediaId(song.id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .setArtworkUri(android.net.Uri.parse(song.thumbnailUrl))
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()
                )
                .build()

            controller?.apply {
                setMediaItem(item)
                prepare()      // <- omitted in most broken players
                playWhenReady = true
                play()
            }
        }
    }

    fun togglePlayPause() {
        val c = controller ?: return
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
                durationMs = controller?.duration?.coerceAtLeast(0L) ?: 0L
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            // 403 here almost always means the stream URL was minted for a different
            // client than the one fetching it, or it has already expired.
            _state.value = _state.value.copy(
                isBuffering = false,
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
                delay(250)
            }
        }
    }
}