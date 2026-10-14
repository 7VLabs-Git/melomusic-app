package com.melomusic.app.data.recommendation

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

data class YTRecommendedTrack(
    val title: String,
    val artist: String,
    val videoId: String
)

data class YTRadioResult(
    val tracks: List<YTRecommendedTrack>,
    val continuationToken: String?
)

class YouTubeMusicRadioEngine(private val httpClient: OkHttpClient) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun getClientContext(): JsonObject {
        val client = JsonObject().apply {
            addProperty("clientName", "WEB_REMIX")
            addProperty("clientVersion", "1.20241009.01.00")
            addProperty("hl", "en")
            addProperty("gl", "IN")
        }
        return JsonObject().apply { add("client", client) }
    }

    /**
     * Resolves the YouTube Music video ID for a song given title and artist.
     */
    suspend fun resolveVideoId(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        try {
            val cleanTitle = title.replace(Regex("""\s*[\(\[\{].*?[\)\]\}]"""), "").trim()
            val cleanArtist = artist.split(",", "&", "/").firstOrNull()?.trim() ?: ""
            val query = "$cleanTitle $cleanArtist".trim()

            val payload = JsonObject().apply {
                add("context", getClientContext())
                addProperty("query", query)
                addProperty("params", "Eg-KAQwIARAAGAAgACgAMABqChAEEAMQCRAFEAo%3D") // Music songs filter
            }

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/search")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer", "https://music.youtube.com/")
                .header("Origin", "https://music.youtube.com")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            val root = JsonParser.parseString(body).asJsonObject

            // Scan JSON for the first valid videoId
            val contents = root.getAsJsonObject("contents")
                ?.getAsJsonObject("tabbedSearchResultsRenderer")
                ?.getAsJsonArray("tabs")?.get(0)?.asJsonObject
                ?.getAsJsonObject("tabRenderer")
                ?.getAsJsonObject("content")
                ?.getAsJsonObject("sectionListRenderer")
                ?.getAsJsonArray("contents") ?: return@withContext null

            for (section in contents) {
                val items = section.asJsonObject
                    .getAsJsonObject("musicShelfRenderer")
                    ?.getAsJsonArray("contents") ?: continue

                for (item in items) {
                    val flexColumns = item.asJsonObject
                        .getAsJsonObject("musicResponsiveListItemRenderer")
                        ?.getAsJsonArray("flexColumns") ?: continue

                    val playNav = item.asJsonObject
                        .getAsJsonObject("musicResponsiveListItemRenderer")
                        ?.getAsJsonObject("overlay")
                        ?.getAsJsonObject("musicItemThumbnailOverlayRenderer")
                        ?.getAsJsonObject("content")
                        ?.getAsJsonObject("musicPlayButtonRenderer")
                        ?.getAsJsonObject("playNavigationEndpoint")

                    val videoId = playNav?.getAsJsonObject("watchEndpoint")?.get("videoId")?.asString
                    if (!videoId.isNullOrBlank()) return@withContext videoId
                }
            }
        } catch (_: Exception) {}
        null
    }

    /**
     * Calls YouTube Music Automix ("RDAMVM" + videoId) to fetch real YT Music queue tracks.
     */
    suspend fun fetchAutomixRadio(videoId: String): YTRadioResult = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<YTRecommendedTrack>()
        var nextContinuation: String? = null

        try {
            val automixPlaylistId = "RDAMVM$videoId"
            val payload = JsonObject().apply {
                add("context", getClientContext())
                addProperty("videoId", videoId)
                addProperty("playlistId", automixPlaylistId)
                addProperty("isAudioOnly", true)
            }

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/next")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer", "https://music.youtube.com/")
                .header("Origin", "https://music.youtube.com")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext YTRadioResult(emptyList(), null)
            val root = JsonParser.parseString(body).asJsonObject

            val playlistPanel = root.getAsJsonObject("contents")
                ?.getAsJsonObject("singleColumnMusicWatchNextResultsRenderer")
                ?.getAsJsonObject("tabbedRenderer")
                ?.getAsJsonObject("watchNextTabbedResultsRenderer")
                ?.getAsJsonArray("tabs")?.get(0)?.asJsonObject
                ?.getAsJsonObject("tabRenderer")
                ?.getAsJsonObject("content")
                ?.getAsJsonObject("musicQueueRenderer")
                ?.getAsJsonObject("content")
                ?.getAsJsonObject("playlistPanelRenderer") ?: return@withContext YTRadioResult(emptyList(), null)

            val contents = playlistPanel.getAsJsonArray("contents") ?: return@withContext YTRadioResult(emptyList(), null)

            for (element in contents) {
                val renderer = element.asJsonObject.getAsJsonObject("playlistPanelVideoRenderer") ?: continue
                val vId = renderer.get("videoId")?.asString ?: ""

                // Skip the seed song itself
                if (vId == videoId) continue

                val title = renderer.getAsJsonObject("title")
                    ?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.get("text")?.asString ?: ""

                val artistRuns = renderer.getAsJsonObject("longBylineText")
                    ?.getAsJsonArray("runs")
                val artist = artistRuns?.get(0)?.asJsonObject?.get("text")?.asString ?: ""

                if (title.isNotBlank() && artist.isNotBlank()) {
                    tracks.add(YTRecommendedTrack(title, artist, vId))
                }
            }

            // Extract continuation token for infinite loading
            val continuations = playlistPanel.getAsJsonArray("continuations")
            if (continuations != null && continuations.size() > 0) {
                nextContinuation = continuations.get(0).asJsonObject
                    .getAsJsonObject("nextRadioContinuationData")
                    ?.get("continuation")?.asString
            }
        } catch (_: Exception) {}

        YTRadioResult(tracks, nextContinuation)
    }

    /**
     * Loads the next batch using a continuation token for endless playback.
     */
    suspend fun fetchContinuation(token: String): YTRadioResult = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<YTRecommendedTrack>()
        var nextContinuation: String? = null

        try {
            val payload = JsonObject().apply {
                add("context", getClientContext())
                addProperty("continuation", token)
            }

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/next")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer", "https://music.youtube.com/")
                .header("Origin", "https://music.youtube.com")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext YTRadioResult(emptyList(), null)
            val root = JsonParser.parseString(body).asJsonObject

            val continuationContents = root.getAsJsonObject("continuationContents")
                ?.getAsJsonObject("playlistPanelContinuation") ?: return@withContext YTRadioResult(emptyList(), null)

            val contents = continuationContents.getAsJsonArray("contents") ?: return@withContext YTRadioResult(emptyList(), null)

            for (element in contents) {
                val renderer = element.asJsonObject.getAsJsonObject("playlistPanelVideoRenderer") ?: continue
                val vId = renderer.get("videoId")?.asString ?: ""
                val title = renderer.getAsJsonObject("title")
                    ?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.get("text")?.asString ?: ""
                val artistRuns = renderer.getAsJsonObject("longBylineText")
                    ?.getAsJsonArray("runs")
                val artist = artistRuns?.get(0)?.asJsonObject?.get("text")?.asString ?: ""

                if (title.isNotBlank() && artist.isNotBlank()) {
                    tracks.add(YTRecommendedTrack(title, artist, vId))
                }
            }

            val continuations = continuationContents.getAsJsonArray("continuations")
            if (continuations != null && continuations.size() > 0) {
                nextContinuation = continuations.get(0).asJsonObject
                    .getAsJsonObject("nextRadioContinuationData")
                    ?.get("continuation")?.asString
            }
        } catch (_: Exception) {}

        YTRadioResult(tracks, nextContinuation)
    }
}