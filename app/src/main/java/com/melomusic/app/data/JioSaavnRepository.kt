package com.melomusic.app.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class JioSaavnRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // 1. RELIABLE SEARCH WITH HIGH-RES 500x500 COVER ART
    suspend fun searchSongs(query: String): List<Song> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Song>()
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.jiosaavn.com/api.php?__call=search.getResults&q=$encodedQuery&p=1&n=30&_format=json&_marker=0&api_version=4&ctx=web6dot0"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val jsonString = response.body?.string() ?: return@withContext emptyList()
            val root = JsonParser.parseString(jsonString)

            val resultsArray: JsonArray? = if (root.isJsonObject) {
                val obj = root.asJsonObject
                when {
                    obj.has("results") && obj.get("results").isJsonArray -> obj.getAsJsonArray("results")
                    obj.has("results") && obj.get("results").isJsonObject -> obj.getAsJsonObject("results").getAsJsonArray("songs")
                    obj.has("data") && obj.get("data").isJsonArray -> obj.getAsJsonArray("data")
                    else -> null
                }
            } else if (root.isJsonArray) {
                root.asJsonArray
            } else null

            if (resultsArray != null) {
                for (element in resultsArray) {
                    val obj = element.asJsonObject
                    val id = obj.get("id")?.asString ?: continue
                    val moreInfo = if (obj.has("more_info") && obj.get("more_info").isJsonObject) obj.getAsJsonObject("more_info") else null

                    val rawTitle = obj.get("song")?.asString
                        ?: obj.get("title")?.asString
                        ?: moreInfo?.get("song")?.asString
                        ?: "Unknown Track"
                    val title = cleanHtmlEntities(rawTitle)

                    val rawArtist = moreInfo?.get("primary_artists")?.asString
                        ?: moreInfo?.get("singers")?.asString
                        ?: obj.get("primary_artists")?.asString
                        ?: obj.get("singers")?.asString
                        ?: obj.get("subtitle")?.asString
                        ?: "Unknown Artist"
                    val artist = cleanHtmlEntities(rawArtist)

                    val rawAlbum = moreInfo?.get("album")?.asString
                        ?: obj.get("album")?.asString
                        ?: "Single"
                    val album = cleanHtmlEntities(rawAlbum)

                    val rawImage = obj.get("image")?.asString ?: moreInfo?.get("image")?.asString ?: ""
                    val highResImage = rawImage
                        .replace("50x50", "500x500")
                        .replace("150x150", "500x500")
                        .replace("http://", "https://")

                    val duration = moreInfo?.get("duration")?.asString ?: obj.get("duration")?.asString ?: "210"
                    val encryptedMediaUrl = moreInfo?.get("encrypted_media_url")?.asString
                        ?: obj.get("encrypted_media_url")?.asString
                        ?: ""

                    list.add(Song(id, title, artist, album, highResImage, duration, encryptedMediaUrl))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    // 2. SMART RADIO RECOMMENDATIONS
    suspend fun getRadioRecommendations(seedSong: Song): List<Song> = withContext(Dispatchers.IO) {
        val recommendations = mutableListOf<Song>()
        try {
            val recoUrl = "https://www.jiosaavn.com/api.php?__call=reco.getreco&pid=${seedSong.id}&_format=json&_marker=0&api_version=4&ctx=web6dot0"
            val request = Request.Builder().url(recoUrl).header("User-Agent", "Mozilla/5.0").build()
            val response = client.newCall(request).execute()
            val jsonString = response.body?.string() ?: ""

            if (jsonString.startsWith("[")) {
                val array = JsonParser.parseString(jsonString).asJsonArray
                for (elem in array) {
                    val obj = elem.asJsonObject
                    val id = obj.get("id")?.asString ?: continue
                    if (id == seedSong.id) continue

                    val moreInfo = if (obj.has("more_info") && obj.get("more_info").isJsonObject) obj.getAsJsonObject("more_info") else null
                    val title = cleanHtmlEntities(obj.get("song")?.asString ?: obj.get("title")?.asString ?: "Track")
                    val artist = cleanHtmlEntities(moreInfo?.get("primary_artists")?.asString ?: obj.get("primary_artists")?.asString ?: seedSong.artist)
                    val album = cleanHtmlEntities(moreInfo?.get("album")?.asString ?: "Single")
                    val highResImage = (obj.get("image")?.asString ?: "")
                        .replace("50x50", "500x500")
                        .replace("150x150", "500x500")
                        .replace("http://", "https://")
                    val duration = moreInfo?.get("duration")?.asString ?: "210"
                    val encrypted = moreInfo?.get("encrypted_media_url")?.asString ?: obj.get("encrypted_media_url")?.asString ?: ""

                    recommendations.add(Song(id, title, artist, album, highResImage, duration, encrypted))
                }
            }
        } catch (_: Exception) { }

        if (recommendations.size < 6) {
            val artistQuery = seedSong.artist.split(",").firstOrNull()?.trim() ?: seedSong.artist
            val fallbackTracks = searchSongs("$artistQuery Hits")
            for (track in fallbackTracks) {
                if (track.id != seedSong.id && recommendations.none { it.id == track.id }) {
                    recommendations.add(track)
                }
            }
        }
        recommendations
    }

    // 3. RESOLVE STREAM URL
    suspend fun getStreamUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (!song.localPath.isNullOrBlank()) return@withContext song.localPath
        getRemoteDecryptedUrl(song)
    }

    fun getRemoteDecryptedUrl(song: Song): String? {
        if (song.encryptedMediaUrl.isBlank()) return null
        return try {
            val key = "38346591".toByteArray(Charsets.UTF_8)
            val secretKey = SecretKeySpec(key, "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decoded = android.util.Base64.decode(song.encryptedMediaUrl, android.util.Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(decoded)
            val rawUrl = String(decryptedBytes, Charsets.UTF_8).replace("http://", "https://")
            rawUrl.replace("_96.mp4", "_160.mp4")
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // 4. SYNCED LYRICS
    suspend fun getSyncedLyrics(title: String, artist: String): List<LyricLine> = withContext(Dispatchers.IO) {
        val lines = mutableListOf<LyricLine>()
        try {
            val cleanTitle = title.replace(Regex("\\(.*\\)|\\[.*\\]"), "").trim()
            val cleanArtist = artist.split(",").firstOrNull()?.trim() ?: artist
            val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
            val encodedArtist = URLEncoder.encode(cleanArtist, "UTF-8")
            val url = "https://lrclib.net/api/get?track_name=$encodedTitle&artist_name=$encodedArtist"
            val request = Request.Builder().url(url).header("User-Agent", "MeloMusic-App").build()
            val response = client.newCall(request).execute()
            val jsonString = response.body?.string() ?: return@withContext emptyList()
            val json = JsonParser.parseString(jsonString).asJsonObject
            val syncedLyrics = json.get("syncedLyrics")?.asString ?: return@withContext emptyList()

            syncedLyrics.lines().forEach { line ->
                val match = Regex("\\[(\\d{2}):(\\d{2}\\.\\d{2})\\](.*)").find(line)
                if (match != null) {
                    val mins = match.groupValues[1].toFloatOrNull() ?: 0f
                    val secs = match.groupValues[2].toFloatOrNull() ?: 0f
                    val text = match.groupValues[3].trim()
                    if (text.isNotEmpty()) {
                        lines.add(LyricLine(mins * 60f + secs, text))
                    }
                }
            }
        } catch (_: Exception) { }
        lines
    }

    private fun cleanHtmlEntities(input: String): String {
        return input.replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}