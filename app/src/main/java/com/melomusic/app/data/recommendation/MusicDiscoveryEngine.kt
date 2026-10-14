// File: app/src/main/java/com/melomusic/app/data/recommendation/MusicDiscoveryEngine.kt
package com.melomusic.app.data.recommendation

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class MusicDiscoveryEngine(private val httpClient: OkHttpClient) {

    // Public read-only client token for Last.fm open music graph
    private val lastFmKey = "b25b959554ed76058ac220b7b2e0a026"

    suspend fun getSimilarArtists(artistName: String): List<String> = withContext(Dispatchers.IO) {
        val cleanArtist = artistName.split(",", "&", "feat.", "ft.", "/").firstOrNull()?.trim() ?: artistName
        if (cleanArtist.isBlank()) return@withContext emptyList()

        val results = mutableListOf<String>()
        try {
            val encoded = URLEncoder.encode(cleanArtist, "UTF-8")
            val url = "https://ws.audioscrobbler.com/2.0/?method=artist.getsimilar&artist=$encoded&api_key=$lastFmKey&format=json&limit=12&autocorrect=1"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MeloMusic-Engine/2.0")
                .build()

            val response = httpClient.newCall(request).execute()
            val raw = response.body?.string() ?: ""
            val root = JsonParser.parseString(raw).asJsonObject
            val artistsArray = root.getAsJsonObject("similarartists")?.getAsJsonArray("artist")
            if (artistsArray != null) {
                for (item in artistsArray) {
                    val name = item.asJsonObject.get("name")?.asString
                    if (!name.isNullOrBlank()) results.add(name)
                }
            }
        } catch (_: Exception) {}

        results
    }

    suspend fun getSimilarTracks(title: String, artistName: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val cleanTitle = title.replace(Regex("""\s*[\(\[\{].*?[\)\]\}]"""), "").trim()
        val cleanArtist = artistName.split(",", "&", "feat.", "ft.", "/").firstOrNull()?.trim() ?: artistName

        val results = mutableListOf<Pair<String, String>>()
        try {
            val encTrack = URLEncoder.encode(cleanTitle, "UTF-8")
            val encArtist = URLEncoder.encode(cleanArtist, "UTF-8")
            val url = "https://ws.audioscrobbler.com/2.0/?method=track.getsimilar&track=$encTrack&artist=$encArtist&api_key=$lastFmKey&format=json&limit=15&autocorrect=1"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MeloMusic-Engine/2.0")
                .build()

            val response = httpClient.newCall(request).execute()
            val raw = response.body?.string() ?: ""
            val root = JsonParser.parseString(raw).asJsonObject
            val trackArray = root.getAsJsonObject("similartracks")?.getAsJsonArray("track")
            if (trackArray != null) {
                for (item in trackArray) {
                    val tObj = item.asJsonObject
                    val tName = tObj.get("name")?.asString ?: ""
                    val tArtist = tObj.getAsJsonObject("artist")?.get("name")?.asString ?: ""
                    if (tName.isNotBlank() && tArtist.isNotBlank()) {
                        results.add(Pair(tName, tArtist))
                    }
                }
            }
        } catch (_: Exception) {}

        results
    }
}