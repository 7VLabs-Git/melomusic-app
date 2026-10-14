// File: app/src/main/java/com/melomusic/app/data/JioSaavnRepository.kt
package com.melomusic.app.data

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melomusic.app.data.local.MeloDatabase
import com.melomusic.app.data.local.SongEntity
import com.melomusic.app.data.network.NetworkQualityHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

// -------------------------------------------------------------
// YOUTUBE MUSIC INNERTUBE ENGINE
// -------------------------------------------------------------
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

    suspend fun resolveVideoId(title: String, artist: String, moodHint: String = ""): String? = withContext(Dispatchers.IO) {
        try {
            val cleanTitle = title.replace(Regex("""\s*[(\[{].*?[)\]}]"""), "").trim()
            val cleanArtist = artist.split(",", "&", "/").firstOrNull()?.trim() ?: ""
            val query = "$cleanTitle $cleanArtist $moodHint".trim()

            val payload = JsonObject().apply {
                add("context", getClientContext())
                addProperty("query", query)
                addProperty("params", "Eg-KAQwIARAAGAAgACgAMABqChAEEAMQCRAFEAo%3D")
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

            val contents = root.getAsJsonObject("contents")
                ?.getAsJsonObject("tabbedSearchResultsRenderer")
                ?.getAsJsonArray("tabs")?.get(0)?.asJsonObject
                ?.getAsJsonObject("tabRenderer")
                ?.getAsJsonObject("content")
                ?.getAsJsonObject("sectionListRenderer")
                ?.getAsJsonArray("contents") ?: return@withContext null

            for (i in 0 until contents.size()) {
                val section = contents.get(i).asJsonObject
                val items = section.getAsJsonObject("musicShelfRenderer")?.getAsJsonArray("contents") ?: continue

                for (j in 0 until items.size()) {
                    val item = items.get(j).asJsonObject
                    val playNav = item.getAsJsonObject("musicResponsiveListItemRenderer")
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

    suspend fun fetchAutomixRadio(videoId: String): YTRadioResult = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<YTRecommendedTrack>()
        var nextContinuation: String? = null

        try {
            val payload = JsonObject().apply {
                add("context", getClientContext())
                addProperty("videoId", videoId)
                addProperty("playlistId", "RDAMVM$videoId")
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

            for (i in 0 until contents.size()) {
                val renderer = contents.get(i).asJsonObject.getAsJsonObject("playlistPanelVideoRenderer") ?: continue
                val vId = renderer.get("videoId")?.asString ?: ""
                if (vId == videoId) continue

                val title = renderer.getAsJsonObject("title")
                    ?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.get("text")?.asString ?: ""
                val artist = renderer.getAsJsonObject("longBylineText")
                    ?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.get("text")?.asString ?: ""

                if (title.isNotBlank() && artist.isNotBlank()) {
                    tracks.add(YTRecommendedTrack(title, artist, vId))
                }
            }

            val continuations = playlistPanel.getAsJsonArray("continuations")
            if (continuations != null && continuations.size() > 0) {
                nextContinuation = continuations.get(0).asJsonObject
                    .getAsJsonObject("nextRadioContinuationData")
                    ?.get("continuation")?.asString
            }
        } catch (_: Exception) {}

        YTRadioResult(tracks, nextContinuation)
    }

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

            for (i in 0 until contents.size()) {
                val renderer = contents.get(i).asJsonObject.getAsJsonObject("playlistPanelVideoRenderer") ?: continue
                val vId = renderer.get("videoId")?.asString ?: ""
                val title = renderer.getAsJsonObject("title")
                    ?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.get("text")?.asString ?: ""
                val artist = renderer.getAsJsonObject("longBylineText")
                    ?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.get("text")?.asString ?: ""

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

// -------------------------------------------------------------
// MAIN JIOSAAVN REPOSITORY IMPLEMENTATION
// -------------------------------------------------------------
class JioSaavnRepository(private val context: Context? = null) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val ytRadioEngine = YouTubeMusicRadioEngine(client)
    private val db: MeloDatabase? = context?.let { MeloDatabase.getInstance(it) }

    private val lyricsMemoryCache = mutableMapOf<String, List<LyricLine>>()

    var currentContinuationToken: String? = null
        private set

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
                for (i in 0 until resultsArray.size()) {
                    val song = parseSongObject(resultsArray.get(i).asJsonObject)
                    if (song != null) {
                        list.add(song)
                        cacheSongAttributes(song)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    suspend fun getRadioRecommendations(seedSong: Song, moodHint: String = ""): List<Song> = withContext(Dispatchers.IO) {
        val pool = mutableListOf<Song>()
        val seenTrackIds = mutableSetOf(seedSong.id)

        val seedFingerprint = normalizeTitle(seedSong.title)
        val seenFingerprints = mutableSetOf<String>()
        seenFingerprints.add(seedFingerprint)

        // 1. YouTube Music Automix ("RDAMVM" algorithm)
        try {
            val ytVideoId = ytRadioEngine.resolveVideoId(seedSong.title, seedSong.artist, moodHint)
            if (!ytVideoId.isNullOrBlank()) {
                val radioResult = ytRadioEngine.fetchAutomixRadio(ytVideoId)
                currentContinuationToken = radioResult.continuationToken

                for (ytTrack in radioResult.tracks) {
                    if (pool.size >= 25) break
                    val searchMatches = searchSongs("${ytTrack.title} ${ytTrack.artist}")
                    val bestMatch = searchMatches.firstOrNull { candidate ->
                        shouldAcceptCandidate(candidate, seedFingerprint, seenTrackIds, seenFingerprints)
                    }
                    if (bestMatch != null) {
                        pool.add(bestMatch)
                        cacheSongAttributes(bestMatch)
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. JioSaavn WebRadio Entity Station fallback
        if (pool.size < 12) {
            val stationTracks = fetchStationRadio(seedSong.id)
            for (track in stationTracks) {
                if (shouldAcceptCandidate(track, seedFingerprint, seenTrackIds, seenFingerprints)) {
                    pool.add(track)
                    cacheSongAttributes(track)
                }
            }
        }

        // 3. Fallback to reco.getreco endpoint
        if (pool.size < 15) {
            try {
                val recoUrl = "https://www.jiosaavn.com/api.php?__call=reco.getreco&pid=${seedSong.id}&_format=json&_marker=0&api_version=4&ctx=web6dot0"
                val request = Request.Builder().url(recoUrl).header("User-Agent", "Mozilla/5.0").build()
                val response = client.newCall(request).execute()
                val jsonString = response.body?.string() ?: ""
                if (jsonString.startsWith("[")) {
                    val array = JsonParser.parseString(jsonString).asJsonArray
                    for (i in 0 until array.size()) {
                        val song = parseSongObject(array.get(i).asJsonObject)
                        if (song != null && shouldAcceptCandidate(song, seedFingerprint, seenTrackIds, seenFingerprints)) {
                            pool.add(song)
                            cacheSongAttributes(song)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 4. Local Room acoustic profile matching
        if (db != null && pool.size < 15) {
            val seedEntity = db.songDao().getSongById(songId = seedSong.id)
            val seedBpm = seedEntity?.bpm ?: estimateBpm(seedSong.title, seedSong.album, durationStr = seedSong.durationText)
            val seedEnergy = seedEntity?.energyScore ?: estimateEnergy(seedSong.title)
            val seedYearInt: Int = seedEntity?.year?.toIntOrNull()
                ?: (seedSong.year as String?)?.toIntOrNull()
                ?: estimateYear(seedSong.title, seedSong.artist, seedSong.album)
            val seedLangStr: String = (seedEntity?.language ?: (seedSong.language as String?).orEmpty()).ifBlank { "hindi" }

            val localAcoustic = db.songDao().getSongsByAcousticProfile(
                minBpm = (seedBpm - 20).coerceAtLeast(60),
                maxBpm = (seedBpm + 20).coerceAtMost(180),
                targetEnergy = seedEnergy,
                language = seedLangStr,
                seedYear = seedYearInt,
                limit = 15
            )
            for (neighbor in localAcoustic) {
                val song = neighbor.toSong()
                if (shouldAcceptCandidate(song, seedFingerprint, seenTrackIds, seenFingerprints)) {
                    pool.add(song)
                }
            }
        }

        // 5. Sliding-window artist dispersion (Max 2 songs per artist, 4-track spacing)
        val finalizedQueue = mutableListOf<Song>()
        val artistAppearanceCount = mutableMapOf<String, Int>()
        val recentArtistsWindow = ArrayDeque<String>(4)

        for (candidate in pool) {
            val allArtists = extractAllArtistTokens(candidate.artist)
            val primaryArtist = allArtists.firstOrNull() ?: "unknown"

            if (allArtists.any { artistAppearanceCount.getOrDefault(it, 0) >= 2 }) continue
            if (recentArtistsWindow.contains(primaryArtist)) continue

            allArtists.forEach { artist ->
                artistAppearanceCount[artist] = artistAppearanceCount.getOrDefault(artist, 0) + 1
            }

            recentArtistsWindow.addLast(primaryArtist)
            if (recentArtistsWindow.size > 4) recentArtistsWindow.removeFirst()

            finalizedQueue.add(candidate)
            if (finalizedQueue.size >= 25) break
        }

        finalizedQueue
    }

    suspend fun getNextEndlessBatch(): List<Song> = withContext(Dispatchers.IO) {
        val token = currentContinuationToken ?: return@withContext emptyList()
        val result = ytRadioEngine.fetchContinuation(token)
        currentContinuationToken = result.continuationToken

        val resolved = mutableListOf<Song>()
        for (track in result.tracks) {
            val search = searchSongs("${track.title} ${track.artist}")
            val match = search.firstOrNull()
            if (match != null) {
                resolved.add(match)
                cacheSongAttributes(match)
            }
        }
        resolved
    }

    private fun shouldAcceptCandidate(
        candidate: Song,
        seedFingerprint: String,
        seenTrackIds: MutableSet<String>,
        seenFingerprints: MutableSet<String>
    ): Boolean {
        if (!seenTrackIds.add(candidate.id)) return false

        val candidateFingerprint = normalizeTitle(candidate.title)
        if (candidateFingerprint.isBlank()) return false

        if (candidateFingerprint == seedFingerprint || seenFingerprints.contains(candidateFingerprint)) {
            return false
        }

        if (areTitlesSubstantiallyIdentical(candidateFingerprint, seedFingerprint)) {
            return false
        }

        if (seenFingerprints.any { areTitlesSubstantiallyIdentical(candidateFingerprint, it) }) {
            return false
        }

        seenFingerprints.add(candidateFingerprint)
        return true
    }

    private fun normalizeTitle(rawTitle: String): String {
        return rawTitle.lowercase()
            .replace(Regex("""\s*[(\[{].*?[)\]}]"""), "")
            .replace(Regex("""(?i)\b(from\s+".*?"|from\s+'.*?'|from\s+.*|feat.*|ft.*|solo|duet|remix|version|remastered|classic|original|soundtrack|audio|video)\b"""), "")
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun areTitlesSubstantiallyIdentical(t1: String, t2: String): Boolean {
        if (t1 == t2) return true
        val words1 = t1.split(" ").filter { it.length > 2 }.toSet()
        val words2 = t2.split(" ").filter { it.length > 2 }.toSet()
        if (words1.isEmpty() || words2.isEmpty()) return false

        val overlap = words1.intersect(words2).size
        val minCount = minOf(words1.size, words2.size)
        return (overlap.toFloat() / minCount.toFloat()) >= 0.60f
    }

    private fun extractAllArtistTokens(artistString: String): List<String> {
        return artistString.lowercase()
            .split(",", "&", "/", "feat.", "ft.", "and", ";")
            .map { it.trim() }
            .filter { it.length > 2 }
    }

    private fun estimateYear(title: String, artist: String, album: String): Int {
        val text = "$title $artist $album".lowercase()
        return when {
            text.contains("mukesh") || text.contains("kishore") || text.contains("rafi") || text.contains("lata") -> 1978
            text.contains("kumar sanu") || text.contains("udit narayan") || text.contains("alka yagnik") -> 1998
            text.contains("honey singh") || text.contains("badshah") || text.contains("paradox") || text.contains("mc stan") -> 2023
            else -> 2020
        }
    }

    private fun fetchStationRadio(songId: String): List<Song> {
        val tracks = mutableListOf<Song>()
        try {
            val createUrl = "https://www.jiosaavn.com/api.php?__call=webradio.createEntityStation&entity_id=[%22$songId%22]&entity_type=queue&_format=json&_marker=0&api_version=4&ctx=web6dot0"
            val req1 = Request.Builder().url(createUrl).header("User-Agent", "Mozilla/5.0").build()
            val res1 = client.newCall(req1).execute()
            val json1 = res1.body?.string() ?: ""
            val stationId = JsonParser.parseString(json1).asJsonObject.get("stationid")?.asString ?: return emptyList()

            val getSongsUrl = "https://www.jiosaavn.com/api.php?__call=webradio.getSong&stationid=$stationId&k=20&next=1&_format=json&_marker=0&api_version=4&ctx=web6dot0"
            val req2 = Request.Builder().url(getSongsUrl).header("User-Agent", "Mozilla/5.0").build()
            val res2 = client.newCall(req2).execute()
            val json2 = res2.body?.string() ?: ""
            val root = JsonParser.parseString(json2).asJsonObject

            for (key in root.keySet()) {
                val element = root.get(key)
                if (element != null && element.isJsonObject) {
                    val obj = element.asJsonObject
                    if (obj.has("song") || obj.has("title")) {
                        val parsed = parseSongObject(obj)
                        if (parsed != null) tracks.add(parsed)
                    }
                }
            }
        } catch (_: Exception) {}
        return tracks
    }

    suspend fun getStreamUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (!song.localPath.isNullOrBlank()) return@withContext song.localPath

        val userPrefQuality = context?.getSharedPreferences("melo_vault", Context.MODE_PRIVATE)
            ?.getString("pref_streaming_quality", null)

        val preferredBitrate = when {
            userPrefQuality?.startsWith("320") == true -> NetworkQualityHelper.AudioBitratePreference.HIGH_320
            userPrefQuality?.startsWith("160") == true -> NetworkQualityHelper.AudioBitratePreference.STANDARD_160
            else -> context?.let { NetworkQualityHelper.getPreferredBitrate(it) }
                ?: NetworkQualityHelper.AudioBitratePreference.HIGH_320
        }

        val isDataSaver96 = userPrefQuality?.startsWith("96") == true

        val cached = db?.songDao()?.getSongById(song.id)
        if (cached != null && !isDataSaver96) {
            if (preferredBitrate == NetworkQualityHelper.AudioBitratePreference.HIGH_320 && !cached.streamUrl320.isNullOrBlank()) {
                return@withContext cached.streamUrl320
            }
            if (!cached.streamUrl160.isNullOrBlank()) {
                return@withContext cached.streamUrl160
            }
        }

        val decryptedUrl = getRemoteDecryptedUrl(song)
        if (decryptedUrl != null) {
            val url320 = decryptedUrl.replace("_96.mp4", "_320.mp4").replace("_160.mp4", "_320.mp4")
            val url160 = decryptedUrl.replace("_96.mp4", "_160.mp4").replace("_320.mp4", "_160.mp4")
            val url96 = decryptedUrl.replace("_320.mp4", "_96.mp4").replace("_160.mp4", "_96.mp4")

            val safeYear = (song.year as String?).orEmpty().ifBlank {
                estimateYear(song.title, song.artist, song.album).toString()
            }
            val safeLang = (song.language as String?).orEmpty().ifBlank { "hindi" }

            db?.songDao()?.insertOrUpdateSong(
                SongEntity(
                    id = song.id,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    thumbnailUrl = song.thumbnailUrl,
                    durationText = song.durationText,
                    encryptedMediaUrl = song.encryptedMediaUrl.orEmpty(),
                    localPath = song.localPath,
                    streamUrl320 = url320,
                    streamUrl160 = url160,
                    bpm = estimateBpm(song.title, song.album, song.durationText),
                    energyScore = estimateEnergy(song.title),
                    year = safeYear,
                    language = safeLang
                )
            )

            return@withContext when {
                isDataSaver96 -> url96
                preferredBitrate == NetworkQualityHelper.AudioBitratePreference.HIGH_320 -> url320
                else -> url160
            }
        }
        null
    }

    @Suppress("GetInstance")
    fun getRemoteDecryptedUrl(song: Song): String? {
        val enc = song.encryptedMediaUrl.orEmpty().trim()
        if (enc.isBlank()) return null
        return try {
            val key = "38346591".toByteArray(Charsets.UTF_8)
            val secretKey = SecretKeySpec(key, "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decoded = android.util.Base64.decode(enc, android.util.Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(decoded)
            String(decryptedBytes, Charsets.UTF_8).replace("http://", "https://")
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun cacheSongAttributes(song: Song) {
        if (db == null) return
        val existing = db.songDao().getSongById(song.id)
        if (existing == null) {
            val safeYear = (song.year as String?).orEmpty().ifBlank {
                estimateYear(song.title, song.artist, song.album).toString()
            }
            val safeLang = (song.language as String?).orEmpty().ifBlank { "hindi" }

            db.songDao().insertOrUpdateSong(
                SongEntity(
                    id = song.id,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    thumbnailUrl = song.thumbnailUrl,
                    durationText = song.durationText,
                    encryptedMediaUrl = song.encryptedMediaUrl.orEmpty(),
                    bpm = estimateBpm(song.title, song.album, song.durationText),
                    energyScore = estimateEnergy(song.title),
                    year = safeYear,
                    language = safeLang
                )
            )
        }
    }

    private fun estimateBpm(title: String, album: String, durationStr: String): Int {
        val lower = "$title $album".lowercase()
        return when {
            lower.contains("remix") || lower.contains("club") || lower.contains("edm") || lower.contains("glory") -> 128
            lower.contains("lofi") || lower.contains("chill") || lower.contains("unplugged") -> 85
            lower.contains("sad") || lower.contains("acoustic") || lower.contains("slow") -> 75
            lower.contains("party") || lower.contains("dance") || lower.contains("bhangra") -> 132
            else -> {
                val dur = durationStr.toIntOrNull() ?: 210
                if (dur > 300) 88 else 115
            }
        }
    }

    private fun estimateEnergy(title: String): Float {
        val lower = title.lowercase()
        return when {
            lower.contains("remix") || lower.contains("dance") || lower.contains("bass") || lower.contains("glory") -> 0.88f
            lower.contains("unplugged") || lower.contains("acoustic") || lower.contains("piano") -> 0.28f
            lower.contains("lofi") || lower.contains("sleep") || lower.contains("relax") -> 0.18f
            else -> 0.60f
        }
    }

    private fun parseSongObject(obj: JsonObject): Song? {
        val id = obj.get("id")?.asString ?: return null
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
        val useHighRes = context?.getSharedPreferences("melo_vault", Context.MODE_PRIVATE)
            ?.getBoolean("pref_high_res_art", true) ?: true

        val targetRes = if (useHighRes) "500x500" else "150x150"
        val highResImage = rawImage
            .replace("50x50", targetRes)
            .replace("150x150", targetRes)
            .replace("http://", "https://")

        val duration = moreInfo?.get("duration")?.asString ?: obj.get("duration")?.asString ?: "210"
        val encryptedMediaUrl = moreInfo?.get("encrypted_media_url")?.asString
            ?: obj.get("encrypted_media_url")?.asString
            ?: ""

        val year = obj.get("year")?.asString ?: moreInfo?.get("year")?.asString ?: ""
        val language = obj.get("language")?.asString ?: moreInfo?.get("language")?.asString ?: "hindi"

        return Song(
            id = id,
            title = title,
            artist = artist,
            album = album,
            thumbnailUrl = highResImage,
            durationText = duration,
            encryptedMediaUrl = encryptedMediaUrl,
            localPath = null,
            year = year,
            language = language
        )
    }

    // =========================================================================
    // PRECISION SYNCED LYRICS ENGINE
    // =========================================================================

    suspend fun getSyncedLyrics(title: String, artist: String): List<LyricLine> = withContext(Dispatchers.IO) {
        val dummySong = Song(
            id = "${title}_${artist}".hashCode().toString(),
            title = title,
            artist = artist,
            album = "",
            thumbnailUrl = "",
            durationText = "210",
            encryptedMediaUrl = ""
        )
        getSyncedLyrics(dummySong)
    }

    suspend fun getSyncedLyrics(song: Song): List<LyricLine> = withContext(Dispatchers.IO) {
        lyricsMemoryCache[song.id]?.let { return@withContext it }

        val cleanTitle = cleanTitleForLyrics(song.title)
        val cleanArtist = cleanArtistForLyrics(song.artist)
        val durationSec = parseDurationSeconds(song.durationText)

        // 1. Try exact match with duration
        var result = fetchLrclibExact(cleanTitle, cleanArtist, durationSec)

        // 2. Try fuzzy search prioritizing synced lyrics with matching duration
        if (result.isEmpty()) {
            result = fetchLrclibFuzzy(cleanTitle, cleanArtist, durationSec)
        }

        // 3. Try title-only search if composite artist credits caused a miss
        if (result.isEmpty()) {
            result = fetchLrclibFuzzy(cleanTitle, "", durationSec)
        }

        // 4. Fallback to JioSaavn native lyrics (marked unsynced with time = -1f)
        if (result.isEmpty() && song.id.isNotBlank()) {
            result = fetchJioSaavnNativeLyrics(song.id)
        }

        if (result.isNotEmpty()) {
            lyricsMemoryCache[song.id] = result
        }

        result
    }

    private fun cleanTitleForLyrics(rawTitle: String): String {
        return rawTitle
            .replace(Regex("""\s*[(\[{].*?[)\]}]"""), "")
            .replace(Regex("""(?i)\b(from\s+".*?"|from\s+'.*?'|hindi|punjabi|tamil|telugu|malayalam|remix|lofi|slowed|reverb|version|video|audio|official|full\s+song)\b"""), "")
            .replace(Regex("""[-|].*$"""), "")
            .replace(Regex("""[^a-zA-Z0-9\s']"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun cleanArtistForLyrics(rawArtist: String): String {
        return rawArtist
            .split(",", "&", "/", "feat.", "ft.", "and", ";")
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() } ?: rawArtist
    }

    private fun parseDurationSeconds(durationText: String): Int {
        return if (durationText.contains(":")) {
            val parts = durationText.split(":")
            val mins = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
            val secs = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            mins * 60 + secs
        } else {
            durationText.trim().toIntOrNull() ?: 0
        }
    }

    private fun fetchLrclibExact(title: String, artist: String, durationSec: Int): List<LyricLine> {
        return try {
            val encTitle = URLEncoder.encode(title, "UTF-8")
            val encArtist = URLEncoder.encode(artist, "UTF-8")
            val durationParam = if (durationSec > 30) "&duration=$durationSec" else ""
            val url = "https://lrclib.net/api/get?track_name=$encTitle&artist_name=$encArtist$durationParam"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MeloMusic-App/2.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val jsonString = response.body?.string() ?: return emptyList()
            val json = JsonParser.parseString(jsonString).asJsonObject
            if (durationSec > 30 && json.has("duration") && !json.get("duration").isJsonNull) {
                val apiDur = json.get("duration").asInt
                if (abs(apiDur - durationSec) > 8) return emptyList()
            }
            parseLrclibObject(json)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchLrclibFuzzy(title: String, artist: String, durationSec: Int): List<LyricLine> {
        return try {
            val query = "$title $artist".trim()
            val encQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://lrclib.net/api/search?q=$encQuery"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MeloMusic-App/2.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val jsonString = response.body?.string() ?: return emptyList()
            val array = JsonParser.parseString(jsonString).asJsonArray
            if (array.size() == 0) return emptyList()

            var bestSyncedItem: JsonObject? = null
            var bestSyncedDelta = Int.MAX_VALUE
            var fallbackPlainItem: JsonObject? = null

            for (i in 0 until array.size()) {
                val item = array.get(i).asJsonObject
                val hasSynced = item.has("syncedLyrics") && !item.get("syncedLyrics").isJsonNull && item.get("syncedLyrics").asString.isNotBlank()
                val hasPlain = item.has("plainLyrics") && !item.get("plainLyrics").isJsonNull && item.get("plainLyrics").asString.isNotBlank()
                val itemDuration = if (item.has("duration") && !item.get("duration").isJsonNull) item.get("duration").asInt else durationSec
                val delta = if (durationSec > 30) abs(itemDuration - durationSec) else 0

                if (hasSynced && delta < bestSyncedDelta) {
                    bestSyncedDelta = delta
                    bestSyncedItem = item
                }
                if (fallbackPlainItem == null && hasPlain && delta <= 12) {
                    fallbackPlainItem = item
                }
            }

            if (bestSyncedItem != null && bestSyncedDelta <= 8) {
                return parseLrclibObject(bestSyncedItem)
            }
            if (fallbackPlainItem != null) {
                return parseLrclibObject(fallbackPlainItem)
            }
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseLrclibObject(json: JsonObject): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        val synced = if (json.has("syncedLyrics") && !json.get("syncedLyrics").isJsonNull) json.get("syncedLyrics").asString else null
        val plain = if (json.has("plainLyrics") && !json.get("plainLyrics").isJsonNull) json.get("plainLyrics").asString else null

        if (!synced.isNullOrBlank()) {
            val timestampRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
            for (rawLine in synced.lines()) {
                val matches = timestampRegex.findAll(rawLine).toList()
                if (matches.isEmpty()) continue

                val lyricText = rawLine.substring(matches.last().range.last + 1).trim()
                if (lyricText.isEmpty()) continue

                for (match in matches) {
                    val mins = match.groupValues[1].toFloatOrNull() ?: 0f
                    val secs = match.groupValues[2].toFloatOrNull() ?: 0f
                    val fracStr = match.groupValues[3]
                    val fracSec = when (fracStr.length) {
                        1 -> (fracStr.toFloatOrNull() ?: 0f) / 10f
                        2 -> (fracStr.toFloatOrNull() ?: 0f) / 100f
                        3 -> (fracStr.toFloatOrNull() ?: 0f) / 1000f
                        else -> 0f
                    }
                    val totalSeconds = (mins * 60f) + secs + fracSec
                    lines.add(LyricLine(totalSeconds, lyricText))
                }
            }
            return lines.sortedBy { it.time }
        } else if (!plain.isNullOrBlank()) {
            plain.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { txt ->
                    lines.add(LyricLine(-1f, txt))
                }
        }
        return lines
    }

    private fun fetchJioSaavnNativeLyrics(songId: String): List<LyricLine> {
        return try {
            val url = "https://www.jiosaavn.com/api.php?__call=lyrics.getLyrics&lyrics_id=$songId&ctx=web6dot0&api_version=4&_format=json"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val jsonString = response.body?.string() ?: return emptyList()
            val root = JsonParser.parseString(jsonString).asJsonObject
            val rawLyrics = root.get("lyrics")?.asString ?: return emptyList()

            val cleanLyrics = rawLyrics
                .replace("<br />", "\n")
                .replace("<br/>", "\n")
                .replace("<br>", "\n")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#039;", "'")
                .trim()

            cleanLyrics.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { LyricLine(-1f, it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun cleanHtmlEntities(input: String): String {
        return input.replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}