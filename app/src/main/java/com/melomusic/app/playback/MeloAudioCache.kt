// File: app/src/main/java/com/melomusic/app/playback/MeloAudioCache.kt
package com.melomusic.app.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@UnstableApi
object MeloAudioCache {
    @Volatile
    private var cacheInstance: SimpleCache? = null
    private const val MAX_CACHE_BYTES = 512L * 1024L * 1024L // 512 MB automatic stream cache

    fun getInstance(context: Context): SimpleCache {
        return cacheInstance ?: synchronized(this) {
            cacheInstance ?: run {
                val cacheDir = File(context.cacheDir, "melo_stream_cache").apply { mkdirs() }
                val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES)
                val dbProvider = StandaloneDatabaseProvider(context.applicationContext)
                SimpleCache(cacheDir, evictor, dbProvider).also { cacheInstance = it }
            }
        }
    }

    fun createDataSourceFactory(context: Context): DataSource.Factory {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)

        // Supports both file:// (downloaded songs) and https:// (streamed songs)
        val upstreamFactory = DefaultDataSource.Factory(context.applicationContext, httpFactory)

        return CacheDataSource.Factory()
            .setCache(getInstance(context.applicationContext))
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun release() {
        synchronized(this) {
            runCatching { cacheInstance?.release() }
            cacheInstance = null
        }
    }
}