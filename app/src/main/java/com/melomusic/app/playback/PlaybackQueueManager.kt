// File: app/src/main/java/com/melomusic/app/playback/PlaybackQueueManager.kt
package com.melomusic.app.playback

import com.melomusic.app.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PlaybackQueueManager {
    const val MODE_REPEAT_ALL = 1
    const val MODE_REPEAT_ONE = 2
    const val MODE_SHUFFLE = 0

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _playMode = MutableStateFlow(MODE_REPEAT_ALL)
    val playMode: StateFlow<Int> = _playMode.asStateFlow()

    // Resolved stream URLs cache: songId -> direct CDN URL or file:// URI
    private val streamUrlCache = mutableMapOf<String, String>()

    fun setQueue(newQueue: List<Song>, startIndex: Int = 0) {
        _queue.value = newQueue
        _currentIndex.value = startIndex.coerceIn(0, (newQueue.size - 1).coerceAtLeast(0))
    }

    fun getCurrentSong(): Song? = _queue.value.getOrNull(_currentIndex.value)

    fun getSongAt(index: Int): Song? = _queue.value.getOrNull(index)

    fun cacheStreamUrl(songId: String, url: String) {
        synchronized(streamUrlCache) {
            streamUrlCache[songId] = url
        }
    }

    fun getCachedStreamUrl(songId: String): String? {
        return synchronized(streamUrlCache) {
            streamUrlCache[songId]
        }
    }

    fun setPlayMode(mode: Int) {
        _playMode.value = mode
    }

    fun togglePlayMode(): Int {
        _playMode.value = when (_playMode.value) {
            MODE_REPEAT_ALL -> MODE_REPEAT_ONE
            MODE_REPEAT_ONE -> MODE_SHUFFLE
            else -> MODE_REPEAT_ALL
        }
        return _playMode.value
    }

    fun getNextIndex(): Int? {
        val q = _queue.value
        if (q.isEmpty()) return null
        if (_playMode.value == MODE_REPEAT_ONE) return _currentIndex.value

        if (_playMode.value == MODE_SHUFFLE && q.size > 1) {
            var rand = q.indices.random()
            while (rand == _currentIndex.value) {
                rand = q.indices.random()
            }
            return rand
        }

        val next = _currentIndex.value + 1
        return if (next < q.size) next else if (_playMode.value == MODE_REPEAT_ALL) 0 else null
    }

    fun getPreviousIndex(): Int? {
        val q = _queue.value
        if (q.isEmpty()) return null
        val prev = _currentIndex.value - 1
        return if (prev >= 0) prev else if (_playMode.value == MODE_REPEAT_ALL) q.size - 1 else null
    }

    fun moveToNext(): Song? {
        val nextIdx = getNextIndex() ?: return null
        _currentIndex.value = nextIdx
        return getCurrentSong()
    }

    fun moveToPrevious(): Song? {
        val prevIdx = getPreviousIndex() ?: return null
        _currentIndex.value = prevIdx
        return getCurrentSong()
    }

    fun appendSongs(songs: List<Song>) {
        val currentList = _queue.value.toMutableList()
        val existingIds = currentList.map { it.id }.toSet()
        val unique = songs.filter { it.id !in existingIds }
        if (unique.isNotEmpty()) {
            currentList.addAll(unique)
            _queue.value = currentList
        }
    }

    fun removeAt(index: Int) {
        val currentList = _queue.value.toMutableList()
        if (index in currentList.indices) {
            currentList.removeAt(index)
            if (index < _currentIndex.value) {
                _currentIndex.value = (_currentIndex.value - 1).coerceAtLeast(0)
            }
            _queue.value = currentList
        }
    }
}