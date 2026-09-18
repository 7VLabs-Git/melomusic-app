package com.melomusic.app.data

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object MusicSource {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private const val SAAVN_SEARCH_URL = "https://saavn.dev/api/search/songs"

    /**
     * Resolves a direct, high-bitrate JioSaavn CDN stream URL from YouTube metadata.
     */
    suspend fun resolveJioSaavnStream(title: String, artist: String): String = withContext(Dispatchers.IO) {
        val cleanTitle = sanitizeTitle(title)
        val cleanArtist = artist.split(",", "&", "/").firstOrNull()?.trim() ?: ""
        val query = "$cleanTitle $cleanArtist".trim()

        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$SAAVN_SEARCH_URL?query=$encodedQuery&page=1&limit=5"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext ""

            val rawJson = response.body?.string() ?: return@withContext ""
            val root = JsonParser.parseString(rawJson).asJsonObject
            val results = root.getAsJsonObject("data")?.getAsJsonArray("results") ?: return@withContext ""

            if (results.size() == 0) {
                Log.w("MeloMusic", "No JioSaavn audio match found for: $query")
                return@withContext ""
            }

            // Pick the best match
            val topSong = results.get(0).asJsonObject
            val downloadUrls = topSong.getAsJsonArray("downloadUrl") ?: return@withContext ""

            // Prioritize highest available quality: 320kbps -> 160kbps -> fallback
            var selectedUrl = ""
            for (item in downloadUrls) {
                val quality = item.asJsonObject.get("quality")?.asString ?: ""
                val streamUrl = item.asJsonObject.get("url")?.asString ?: ""

                if (quality == "320kbps") {
                    selectedUrl = streamUrl
                    break
                } else if (quality == "160kbps" && selectedUrl.isEmpty()) {
                    selectedUrl = streamUrl
                }
            }

            if (selectedUrl.isEmpty() && downloadUrls.size() > 0) {
                selectedUrl = downloadUrls.get(downloadUrls.size() - 1).asJsonObject.get("url")?.asString ?: ""
            }

            Log.d("MeloMusic", "Resolved JioSaavn direct stream: $selectedUrl")
            selectedUrl
        } catch (e: Exception) {
            Log.e("MeloMusic", "Failed to resolve JioSaavn audio for $query", e)
            ""
        }
    }

    /**
     * Cleans metadata to improve cross-engine match rates.
     */
    private fun sanitizeTitle(title: String): String {
        return title
            .replace(Regex("(?i)\\b(official\\s*video|official\\s*audio|lyric\\s*video|audio|mv|remix)\\b"), "")
            .replace(Regex("[\\[(].*?[])]"), "")
            .replace(Regex("[|/].*$"), "")
            .trim()
    }
}