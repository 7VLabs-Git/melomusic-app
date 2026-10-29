// File: app/src/main/java/com/melomusic/app/playback/MeloAudioPreloadManager.kt
package com.melomusic.app.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

@UnstableApi
class MeloAudioPreloadManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val activePreloads = ConcurrentHashMap<String, Job>()
    private val cacheDataSourceFactory by lazy {
        MeloAudioCache.createDataSourceFactory(context) as CacheDataSource.Factory
    }

    fun preloadTrack(songId: String, streamUrl: String, bytesToPreload: Long = 2 * 1024 * 1024L) {
        // Skip local files and already running jobs
        if (streamUrl.startsWith("file://") || activePreloads.containsKey(songId)) return

        val job = scope.launch(Dispatchers.IO) {
            try {
                val dataSpec = DataSpec.Builder()
                    .setUri(Uri.parse(streamUrl))
                    .setLength(bytesToPreload) // Pre-buffer the first 2MB for instant playback
                    .build()

                val cacheWriter = CacheWriter(
                    cacheDataSourceFactory.createDataSource(),
                    dataSpec,
                    null,
                    null
                )
                cacheWriter.cache()
            } catch (_: Exception) {
                // Network dropped or cancelled; partial cache remains valid
            } finally {
                activePreloads.remove(songId)
            }
        }
        activePreloads[songId] = job
    }

    fun cancelAll() {
        activePreloads.values.forEach { it.cancel() }
        activePreloads.clear()
    }
}