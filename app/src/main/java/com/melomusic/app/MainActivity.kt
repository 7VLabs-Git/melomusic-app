// File: app/src/main/java/com/melomusic/app/MainActivity.kt
package com.melomusic.app

import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.cos
import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.melomusic.app.data.JioSaavnRepository
import com.melomusic.app.data.LyricLine
import com.melomusic.app.data.Song
import com.melomusic.app.playback.MusicService
import com.melomusic.app.playback.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.Calendar
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.math.sin

private val VoidBlack = Color(0xFF030304)
private val DeepSurface = Color(0xFF0D0D11)
private val GlassSurface = Color(0x18FFFFFF)
private val TextPure = Color(0xFFFFFFFF)
private val TextMuted = Color(0x9EFFFFFF)
private val TextSubtle = Color(0x60FFFFFF)
private val MeloAccent = Color(0xFFFF5252)

val PRESET_COVERS = listOf(
    "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=600",
    "https://images.unsplash.com/photo-1508700115892-45ecd05ae2ad?w=600",
    "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=600",
    "https://images.unsplash.com/photo-1550684848-fac1c5b4e853?w=600",
    "https://images.unsplash.com/photo-1539185441755-769473a23570?w=600",
    "https://images.unsplash.com/photo-1506703719100-a0f3a48c0f86?w=600",
    "https://images.unsplash.com/photo-1445985543470-41fdd6ce755d?w=600"
)

enum class MeloTab(val label: String, val icon: ImageVector, val activeTint: Color) {
    HOME("Home", Icons.Rounded.Home, Color(0xFFFF5252)),
    EXPLORE("Explore", Icons.Rounded.Explore, Color(0xFF00E5FF)),
    LIBRARY("My World", Icons.Rounded.LibraryMusic, Color(0xFFFFD54F))
}

enum class PlayerTier { MINI, EXPANDED, CINEMATIC }

sealed class NavRoute {
    object Main : NavRoute()
    object CreatePlaylist : NavRoute()
    data class PlaylistDetail(val name: String, val cover: String, val songs: List<Song>) : NavRoute()
    object LikedSongs : NavRoute()
    object RecentlyPlayed : NavRoute()
    object Downloads : NavRoute()
}

data class UserPlaylist(var name: String, var coverUrl: String, val songs: MutableList<Song> = mutableListOf())

const val MODE_SHUFFLE = 0
const val MODE_REPEAT_ALL = 1
const val MODE_REPEAT_ONE = 2
const val CURRENT_APP_VERSION = "v1.0.7"

private const val UPDATE_NOTIFICATION_ID = 5050
private const val UPDATE_CHANNEL_ID = "melo_updates_channel"

@androidx.media3.common.util.UnstableApi
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class
)
class MainActivity : ComponentActivity() {

    private lateinit var player: PlayerConnection
    private val saavnRepository by lazy { JioSaavnRepository(this) }
    private val httpClient = OkHttpClient.Builder().followRedirects(true).build()
    private val gson = Gson()
    private lateinit var prefs: SharedPreferences
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val userPlaylists = mutableStateListOf<UserPlaylist>()
    private val likedSongsList = mutableStateListOf<Song>()
    private val recentlyPlayedList = mutableStateListOf<Song>()
    private val downloadedSongsList = mutableStateListOf<Song>()
    private val playbackHistory = mutableListOf<Song>()

    private val downloadProgressMap = mutableStateMapOf<String, Float>()

    private var currentQueue = mutableStateListOf<Song>()
    private var currentQueueIndex by mutableStateOf(0)
    private var currentPlaybackPositionMs by mutableStateOf(0f)
    private var isUserSeeking by mutableStateOf(false)
    private var playMode by mutableStateOf(MODE_REPEAT_ALL)
    private var userPausedManually = false

    private var userProfileName by mutableStateOf<String?>(null)

    private var updateVersion by mutableStateOf<String?>(null)
    private var updateNotes by mutableStateOf("")
    private var updateUrl by mutableStateOf("")
    private var isCheckingUpdate by mutableStateOf(false)
    private var showUpdateSheetState by mutableStateOf(false)
    private var downloadId by mutableStateOf(-1L)

    private var wakeLock: PowerManager.WakeLock? = null
    private var backgroundPlaybackJob: Job? = null

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == downloadId && context != null) installApk(context)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeloMusic::PlaybackWakeLock")

        prefs = getSharedPreferences("melo_vault", Context.MODE_PRIVATE)
        loadPersistedState()
        userProfileName = prefs.getString("user_profile_name", null)
        createUpdateNotificationChannel()

        ContextCompat.registerReceiver(
            this,
            updateReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )

        // FIX #9: Wire Media3 Lockscreen & Bluetooth Next/Prev directly to our Smart Queue
        MusicService.onSkipNextRequested = { runOnUiThread { playNext() } }
        MusicService.onSkipPreviousRequested = { runOnUiThread { playPrev() } }

        player = PlayerConnection(this, lifecycleScope)
        player.connect {
            // Restore last active song into Mini-Player if service was idle when reopening app
            val lastSongJson = prefs.getString("last_active_song", null)
            val fallbackSong = if (!lastSongJson.isNullOrBlank()) {
                runCatching { gson.fromJson(lastSongJson, Song::class.java) }.getOrNull()
            } else {
                currentQueue.getOrNull(currentQueueIndex) ?: recentlyPlayedList.firstOrNull()
            }
            if (fallbackSong != null) {
                player.restoreLastSongIfIdle(fallbackSong)
            }
        }

        startBackgroundPlaybackTracker()

        // FIX #10: Automatically check for updates in background on every app launch
        checkGithubForUpdates(silentBackgroundCheck = true)

        if (intent?.getBooleanExtra("OPEN_UPDATE_SHEET", false) == true) {
            showUpdateSheetState = true
        }

        setContent { MeloRoot() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("OPEN_UPDATE_SHEET", false)) {
            showUpdateSheetState = true
        }
    }

    override fun onDestroy() {
        backgroundPlaybackJob?.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        unregisterReceiver(updateReceiver)
        MusicService.onSkipNextRequested = null
        MusicService.onSkipPreviousRequested = null
        player.release()
        super.onDestroy()
    }

    private fun startBackgroundPlaybackTracker() {
        backgroundPlaybackJob?.cancel()
        backgroundPlaybackJob = lifecycleScope.launch(Dispatchers.Default) {
            player.state.collect { playbackState ->
                withContext(Dispatchers.Main) {
                    if (!isUserSeeking) {
                        currentPlaybackPositionMs = playbackState.positionMs.toFloat()
                    }

                    if (playbackState.isPlaying) {
                        if (wakeLock?.isHeld == false) {
                            try { wakeLock?.acquire(30 * 60 * 1000L) } catch (_: Exception) {}
                        }
                    } else if (wakeLock?.isHeld == true) {
                        try { wakeLock?.release() } catch (_: Exception) {}
                    }

                    if (playbackState.isEnded && !playbackState.isBuffering && !userPausedManually) {
                        playNext()
                    }
                }
            }
        }
    }

    private fun loadPersistedState() {
        try {
            val likedJson = prefs.getString("liked_songs", "[]") ?: "[]"
            val liked: List<Song> = gson.fromJson(likedJson, object : TypeToken<List<Song>>() {}.type)
            likedSongsList.clear()
            likedSongsList.addAll(liked)

            val recentJson = prefs.getString("recent_songs", "[]") ?: "[]"
            val recent: List<Song> = gson.fromJson(recentJson, object : TypeToken<List<Song>>() {}.type)
            recentlyPlayedList.clear()
            recentlyPlayedList.addAll(recent)

            val dlJson = prefs.getString("downloaded_songs", "[]") ?: "[]"
            val downloaded: List<Song> = gson.fromJson(dlJson, object : TypeToken<List<Song>>() {}.type)
            downloadedSongsList.clear()

            val savedQueueJson = prefs.getString("saved_queue", null)
            if (!savedQueueJson.isNullOrBlank()) {
                val qList: List<Song> = gson.fromJson(savedQueueJson, object : TypeToken<List<Song>>() {}.type)
                if (qList.isNotEmpty()) {
                    currentQueue.clear()
                    currentQueue.addAll(qList)
                    currentQueueIndex = prefs.getInt("saved_queue_index", 0).coerceIn(0, qList.lastIndex)
                }
            }

            downloaded.forEach { s ->
                val resolvedPath = resolvePlayableUrl(s)
                if (resolvedPath != null) {
                    s.localPath = resolvedPath
                    downloadedSongsList.add(s)
                    downloadProgressMap[s.id] = -1f
                }
            }

            val plJson = prefs.getString("user_playlists", null)
            if (plJson != null) {
                val plList: List<UserPlaylist> = gson.fromJson(plJson, object : TypeToken<List<UserPlaylist>>() {}.type)
                userPlaylists.clear()
                userPlaylists.addAll(plList)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun persistLikedSongs() =
        prefs.edit().putString("liked_songs", gson.toJson(likedSongsList.toList())).apply()

    private fun persistRecentSongs() =
        prefs.edit().putString("recent_songs", gson.toJson(recentlyPlayedList.toList())).apply()

    private fun persistDownloadedSongs() =
        prefs.edit().putString("downloaded_songs", gson.toJson(downloadedSongsList.toList())).apply()

    fun persistUserPlaylists() =
        prefs.edit().putString("user_playlists", gson.toJson(userPlaylists.toList())).apply()

    private fun persistQueueState(activeSong: Song? = currentQueue.getOrNull(currentQueueIndex)) {
        val editor = prefs.edit()
            .putString("saved_queue", gson.toJson(currentQueue.take(40)))
            .putInt("saved_queue_index", currentQueueIndex)
        if (activeSong != null) {
            editor.putString("last_active_song", gson.toJson(activeSong))
        }
        editor.apply()
    }

    private fun toggleLikeSong(song: Song) {
        val exists = likedSongsList.any { it.id == song.id }
        if (exists) likedSongsList.removeAll { it.id == song.id } else likedSongsList.add(0, song)
        persistLikedSongs()
    }

    private fun recordRecentlyPlayed(song: Song) {
        recentlyPlayedList.removeAll { it.id == song.id }
        recentlyPlayedList.add(0, song)
        if (recentlyPlayedList.size > 30) recentlyPlayedList.removeAt(recentlyPlayedList.lastIndex)
        persistRecentSongs()
    }

    private fun addSongToPlaylist(playlist: UserPlaylist, song: Song) {
        if (playlist.songs.none { it.id == song.id }) {
            playlist.songs.add(song)
            if (playlist.coverUrl.isBlank()) playlist.coverUrl = song.thumbnailUrl
            persistUserPlaylists()
        }
    }

    private fun resolvePlayableUrl(song: Song): String? {
        val dirs = listOf(
            File(filesDir, "downloads"),
            filesDir,
            getExternalFilesDir(Environment.DIRECTORY_MUSIC),
            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ).filterNotNull()

        val safeId = song.id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val candidateIds = listOf(song.id, safeId).distinct()
        val extensions = listOf("mp4", "mp3", "m4a", "aac")

        for (dir in dirs) {
            if (!dir.exists()) continue
            for (id in candidateIds) {
                for (ext in extensions) {
                    val f = File(dir, "$id.$ext")
                    if (f.exists() && f.length() > 5000) {
                        f.setReadable(true, true)
                        val uriStr = Uri.fromFile(f).toString()
                        song.localPath = uriStr
                        return uriStr
                    }
                }
            }
        }

        if (!song.localPath.isNullOrBlank()) {
            val path = song.localPath!!
            val rawPath = if (path.startsWith("file://")) Uri.parse(path).path else path
            if (rawPath != null) {
                val f = File(rawPath)
                if (f.exists() && f.length() > 5000) {
                    f.setReadable(true, true)
                    val uriStr = Uri.fromFile(f).toString()
                    song.localPath = uriStr
                    return uriStr
                }
            }
        }
        return null
    }

    private fun downloadSongInternally(song: Song) {
        if (downloadProgressMap[song.id] == -1f) return
        downloadProgressMap[song.id] = 0.01f

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val existing = resolvePlayableUrl(song)
                if (existing != null) {
                    withContext(Dispatchers.Main) {
                        downloadProgressMap[song.id] = -1f
                        if (downloadedSongsList.none { it.id == song.id }) downloadedSongsList.add(0, song)
                        persistDownloadedSongs()
                    }
                    return@launch
                }

                var streamUrl = saavnRepository.getRemoteDecryptedUrl(song)
                if (streamUrl.isNullOrBlank()) {
                    streamUrl = saavnRepository.getStreamUrl(song)
                }
                if (streamUrl.isNullOrBlank()) {
                    withContext(Dispatchers.Main) { downloadProgressMap.remove(song.id) }
                    return@launch
                }

                val downloadDir = File(filesDir, "downloads").apply { if (!exists()) mkdirs() }
                val ext = if (streamUrl.contains(".mp3", ignoreCase = true)) "mp3" else "mp4"
                val safeId = song.id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val targetFile = File(downloadDir, "$safeId.$ext")
                val tempFile = File(downloadDir, "$safeId.tmp")

                val req = Request.Builder()
                    .url(streamUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()

                val resp = httpClient.newCall(req).execute()
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                val body = resp.body ?: throw Exception("Empty stream body")
                val totalLength = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var read: Int
                        var currentBytes = 0L

                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            currentBytes += read
                            if (totalLength > 0) {
                                val progress = (currentBytes.toFloat() / totalLength.toFloat()).coerceIn(0.01f, 0.99f)
                                withContext(Dispatchers.Main) {
                                    downloadProgressMap[song.id] = progress
                                }
                            }
                        }
                        output.flush()
                    }
                }

                if (tempFile.length() < 5000) {
                    tempFile.delete()
                    throw Exception("Corrupted download file")
                }

                if (targetFile.exists()) targetFile.delete()
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
                targetFile.setReadable(true, true)

                val localUri = Uri.fromFile(targetFile).toString()
                song.localPath = localUri

                withContext(Dispatchers.Main) {
                    downloadProgressMap[song.id] = -1f
                    if (downloadedSongsList.none { it.id == song.id }) downloadedSongsList.add(0, song)
                    persistDownloadedSongs()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { downloadProgressMap.remove(song.id) }
            }
        }
    }

    private fun deleteDownloadedSong(song: Song) {
        val dirs = listOf(
            File(filesDir, "downloads"),
            filesDir,
            getExternalFilesDir(Environment.DIRECTORY_MUSIC),
            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ).filterNotNull()

        val safeId = song.id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val candidateIds = listOf(song.id, safeId).distinct()
        dirs.forEach { dir ->
            candidateIds.forEach { id ->
                listOf("mp4", "mp3", "m4a", "aac", "tmp").forEach { ext ->
                    val f = File(dir, "$id.$ext")
                    if (f.exists()) f.delete()
                }
            }
        }
        downloadedSongsList.removeAll { it.id == song.id }
        downloadProgressMap.remove(song.id)
        persistDownloadedSongs()
    }

    private var radioRecommendationJob: Job? = null

    private fun playSongWithSmartRadio(seedSong: Song, moodHint: String = "") {
        player.state.value.song?.let { playbackHistory.add(it) }
        recordRecentlyPlayed(seedSong)

        currentQueue.clear()
        currentQueue.add(seedSong)
        currentQueueIndex = 0
        currentPlaybackPositionMs = 0f
        userPausedManually = false

        // 1. Save immediately after seedSong is placed in currentQueue at index 0
        persistQueueState(seedSong)

        player.play(seedSong) { target ->
            resolvePlayableUrl(target) ?: saavnRepository.getStreamUrl(target)
        }

        radioRecommendationJob?.cancel()
        radioRecommendationJob = lifecycleScope.launch {
            val freshRadioTracks = saavnRepository.getRadioRecommendations(seedSong, moodHint)
            withContext(Dispatchers.Main) {
                val existingIds = mutableSetOf(seedSong.id)
                val filtered = freshRadioTracks.filter { existingIds.add(it.id) }
                currentQueue.clear()
                currentQueue.add(seedSong)
                currentQueue.addAll(filtered)
                currentQueueIndex = 0

                // 2. Save again once the async radio tracks are added to currentQueue
                persistQueueState(seedSong)
            }
        }
    }

    // FIX #2: Non-destructive Queue Insertion Helpers
    private fun playImmediatelyPreservingQueue(song: Song) {
        if (currentQueue.isEmpty()) {
            playSongWithSmartRadio(song)
            return
        }
        val existingIdx = currentQueue.indexOfFirst { it.id == song.id }
        if (existingIdx != -1) {
            currentQueueIndex = existingIdx
            startSongPlayback(song, currentQueue)
        } else {
            val insertIdx = (currentQueueIndex + 1).coerceAtMost(currentQueue.size)
            currentQueue.add(insertIdx, song)
            currentQueueIndex = insertIdx
            startSongPlayback(song, currentQueue)
        }
    }

    private fun insertPlayNext(song: Song) {
        if (currentQueue.isEmpty()) {
            playSongWithSmartRadio(song)
            return
        }
        currentQueue.removeAll { it.id == song.id && it.id != currentQueue.getOrNull(currentQueueIndex)?.id }
        val insertIdx = (currentQueueIndex + 1).coerceAtMost(currentQueue.size)
        currentQueue.add(insertIdx, song)
        Toast.makeText(this, "Playing next: ${song.title}", Toast.LENGTH_SHORT).show()
    }

    private fun appendToQueue(song: Song) {
        if (currentQueue.isEmpty()) {
            playSongWithSmartRadio(song)
            return
        }
        if (currentQueue.none { it.id == song.id }) {
            currentQueue.add(song)
        }
        Toast.makeText(this, "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
    }

    // FIX #7: Long-Press Artist Radio Generator
    private fun startArtistRadio(artistName: String) {
        val cleanArtist = artistName.split(",", "&", "/").firstOrNull()?.trim() ?: artistName
        if (cleanArtist.isBlank()) return
        Toast.makeText(this, "Starting $cleanArtist Radio...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val topTracks = saavnRepository.searchSongs("$cleanArtist Hits")
            if (topTracks.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    playSongWithSmartRadio(topTracks.first(), "Artist Radio")
                }
            }
        }
    }

    private var isRefillingQueue = false

    private fun startSongPlayback(song: Song, contextQueue: List<Song>) {
        player.state.value.song?.let { if (it.id != song.id) playbackHistory.add(it) }
        recordRecentlyPlayed(song)
        if (contextQueue !== currentQueue) {
            currentQueue.clear()
            currentQueue.addAll(contextQueue)
        }
        currentQueueIndex = currentQueue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        currentPlaybackPositionMs = 0f
        userPausedManually = false

        // 1. Save immediately after currentQueue and currentQueueIndex are updated
        persistQueueState(song)

        player.play(song) { target ->
            resolvePlayableUrl(target) ?: saavnRepository.getStreamUrl(target)
        }

        if (currentQueue.size - currentQueueIndex <= 3 && !isRefillingQueue) {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val hasNetwork = cm?.activeNetwork != null
            if (hasNetwork) {
                isRefillingQueue = true
                lifecycleScope.launch {
                    val nextBatch = saavnRepository.getNextEndlessBatch()
                    withContext(Dispatchers.Main) {
                        if (nextBatch.isNotEmpty()) {
                            val existingIds = currentQueue.map { it.id }.toSet()
                            currentQueue.addAll(nextBatch.filter { it.id !in existingIds })

                            // 2. Save updated queue after endless batch refill
                            persistQueueState(song)
                        }
                        isRefillingQueue = false
                    }
                }
            }
        }
    }

    private val skippedArtistPenalties = mutableSetOf<String>()

    private fun playNext() {
        if (currentQueue.isEmpty()) return

        val currentTrack = currentQueue.getOrNull(currentQueueIndex)
        if (currentTrack != null && currentPlaybackPositionMs < 15000f) {
            val primary = currentTrack.artist.split(",", "&", "/").firstOrNull()?.trim()?.lowercase()
            if (!primary.isNullOrBlank()) {
                skippedArtistPenalties.add(primary)
            }
        }

        currentPlaybackPositionMs = 0f
        player.state.value.song?.let { playbackHistory.add(it) }

        if (playMode == MODE_REPEAT_ONE) {
            seekSong(0f)
            val currentSong = currentQueue[currentQueueIndex]
            userPausedManually = false
            player.play(currentSong) { target ->
                resolvePlayableUrl(target) ?: saavnRepository.getStreamUrl(target)
            }
            return
        }

        var nextIndex = if (playMode == MODE_SHUFFLE && currentQueue.size > 1) {
            var rand = (currentQueue.indices).random()
            while (rand == currentQueueIndex && currentQueue.size > 1) {
                rand = (currentQueue.indices).random()
            }
            rand
        } else {
            (currentQueueIndex + 1) % currentQueue.size
        }

        var attempts = 0
        while (attempts < 3 && nextIndex < currentQueue.size) {
            val candidate = currentQueue[nextIndex]
            val candidatePrimary = candidate.artist.split(",", "&", "/").firstOrNull()?.trim()?.lowercase()
            if (candidatePrimary != null && skippedArtistPenalties.contains(candidatePrimary)) {
                nextIndex = (nextIndex + 1) % currentQueue.size
                attempts++
            } else {
                break
            }
        }

        currentQueueIndex = nextIndex
        startSongPlayback(currentQueue[currentQueueIndex], currentQueue)
    }

    private fun playPrev() {
        if (currentPlaybackPositionMs > 3000f) {
            seekSong(0f)
            return
        }

        if (playbackHistory.isNotEmpty()) {
            val previousSong = playbackHistory.removeAt(playbackHistory.lastIndex)
            val idx = currentQueue.indexOfFirst { it.id == previousSong.id }
            if (idx >= 0) {
                currentQueueIndex = idx
            }
            currentPlaybackPositionMs = 0f
            userPausedManually = false
            player.play(previousSong) { target ->
                resolvePlayableUrl(target) ?: saavnRepository.getStreamUrl(target)
            }
            return
        }

        if (currentQueue.isNotEmpty()) {
            currentPlaybackPositionMs = 0f
            currentQueueIndex = if (currentQueueIndex - 1 < 0) currentQueue.size - 1 else currentQueueIndex - 1
            startSongPlayback(currentQueue[currentQueueIndex], currentQueue)
        }
    }

    // FIX #1: Seek Debounce Latch so Hardware Ticks Never Fight Finger Drag
    private var seekDebounceJob: Job? = null

    private fun seekSong(targetPositionMs: Float) {
        isUserSeeking = true
        currentPlaybackPositionMs = targetPositionMs
        player.seekTo(targetPositionMs.toLong())

        seekDebounceJob?.cancel()
        seekDebounceJob = lifecycleScope.launch {
            delay(450)
            isUserSeeking = false
        }
    }

    private fun parseDurationMs(durationStr: String): Float {
        return try {
            if (durationStr.contains(":")) {
                val parts = durationStr.split(":")
                val mins = parts[0].trim().toLongOrNull() ?: 3L
                val secs = parts[1].trim().toLongOrNull() ?: 30L
                ((mins * 60 + secs) * 1000L).toFloat().coerceAtLeast(30000f)
            } else {
                val secs = durationStr.trim().toLongOrNull() ?: 210L
                (secs * 1000L).toFloat().coerceAtLeast(30000f)
            }
        } catch (_: Exception) {
            210000f
        }
    }

    // FIX #10: Update Notification Channel & Automatic Startup Update Push Notification
    private fun createUpdateNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                UPDATE_CHANNEL_ID,
                "Melo App Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when a new Melo release is available"
                enableVibration(true)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun sendUpdatePushNotification(versionTag: String, releaseNotes: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_UPDATE_SHEET", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            99,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, UPDATE_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Melo Update $versionTag Available")
            .setContentText(releaseNotes.lines().firstOrNull()?.take(80) ?: "Tap to download and install the latest version.")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        getSystemService(NotificationManager::class.java)?.notify(UPDATE_NOTIFICATION_ID, notification)
    }

    private fun checkGithubForUpdates(silentBackgroundCheck: Boolean = false) {
        if (!silentBackgroundCheck) isCheckingUpdate = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("https://api.github.com/repos/7VLabs-Git/melomusic-app/releases/latest")
                    .build()
                val resp = httpClient.newCall(req).execute()
                val raw = resp.body?.string() ?: ""
                val json = JsonParser.parseString(raw).asJsonObject
                val tag = json.get("tag_name")?.asString
                val body = json.get("body")?.asString ?: "Enhanced sound pipeline and UI improvements."
                val assets = json.getAsJsonArray("assets")
                var apkUrl = ""
                if (assets != null && assets.size() > 0) {
                    apkUrl = assets.get(0).asJsonObject.get("browser_download_url")?.asString ?: ""
                }
                withContext(Dispatchers.Main) {
                    if (tag != null && tag != CURRENT_APP_VERSION) {
                        updateVersion = tag
                        updateNotes = body
                        updateUrl = apkUrl
                        if (silentBackgroundCheck) {
                            sendUpdatePushNotification(tag, body)
                        }
                    } else {
                        updateVersion = null
                    }
                    isCheckingUpdate = false
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { isCheckingUpdate = false }
            }
        }
    }

    private fun downloadAndInstallUpdate() {
        if (updateUrl.isEmpty()) return
        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "MeloMusic_Update.apk")
        if (file.exists()) file.delete()
        val request = DownloadManager.Request(Uri.parse(updateUrl))
            .setTitle("Downloading Melo Update")
            .setDescription("Upgrading your sonic sanctuary...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(file))
        downloadId = (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }

    private fun installApk(context: Context) {
        try {
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "MeloMusic_Update.apk")
            if (file.exists()) {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Composable
    private fun MeloRoot() {
        val context = LocalContext.current as Activity
        val playerState by player.state.collectAsState()

        var currentTab by remember { mutableStateOf(MeloTab.HOME) }
        var playerTier by remember { mutableStateOf(PlayerTier.MINI) }

        var currentRoute by remember { mutableStateOf<NavRoute>(NavRoute.Main) }
        var showAccountSheet by remember { mutableStateOf(false) }
        var showImportSheet by remember { mutableStateOf(false) }

        // FIX #2: Track Action Sheet State for Non-Destructive Queueing
        var selectedSongForOptions by remember { mutableStateOf<Song?>(null) }

        var extractedArtworkColor by remember { mutableStateOf(Color(0xFFFF5252)) }
        var extractedSecondaryColor by remember { mutableStateOf(Color(0xFF00E5FF)) }

        // FIX #8: Smooth Dynamic Palette Background Interpolation (750ms)
        val smoothArtworkColor by animateColorAsState(
            targetValue = extractedArtworkColor,
            animationSpec = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            label = "smooth_primary_palette"
        )
        val smoothSecondaryColor by animateColorAsState(
            targetValue = extractedSecondaryColor,
            animationSpec = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            label = "smooth_secondary_palette"
        )

        var becauseArtistName by remember {
            mutableStateOf(prefs.getString("last_listened_artist", null))
        }
        var becauseRecommendations by remember { mutableStateOf<List<Song>>(emptyList()) }

        val homeScrollState = rememberLazyListState()

        LaunchedEffect(becauseArtistName) {
            val artist = becauseArtistName
            if (!artist.isNullOrBlank() && becauseRecommendations.isEmpty()) {
                val rec = saavnRepository.searchSongs("$artist Hits")
                if (rec.isNotEmpty()) becauseRecommendations = rec
            }
        }

        LaunchedEffect(playerState.song?.id, playerState.song?.thumbnailUrl) {
            val url = playerState.song?.thumbnailUrl
            if (!url.isNullOrBlank()) {
                val (primary, secondary, _) = extractTwoDominantColorsAndBitmap(context, url)
                extractedArtworkColor = primary
                extractedSecondaryColor = secondary
            }
        }

        LaunchedEffect(playerState.isPlaying, playerState.song?.id) {
            if (playerState.song != null && playerState.isPlaying) {
                val artistName = playerState.song?.artist?.split(",")?.firstOrNull()?.trim()
                    ?: playerState.song?.artist ?: ""
                if (artistName.isNotBlank() && artistName != becauseArtistName) {
                    becauseArtistName = artistName
                    prefs.edit().putString("last_listened_artist", artistName).apply()
                    lifecycleScope.launch {
                        val reco = saavnRepository.searchSongs("$artistName Hits")
                        if (reco.isNotEmpty()) becauseRecommendations = reco
                    }
                }
            }
        }

        BackHandler(
            enabled = playerTier != PlayerTier.MINI ||
                    currentRoute != NavRoute.Main ||
                    currentTab != MeloTab.HOME
        ) {
            when {
                playerTier == PlayerTier.CINEMATIC -> {
                    playerTier = PlayerTier.EXPANDED
                    context.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                playerTier == PlayerTier.EXPANDED -> {
                    playerTier = PlayerTier.MINI
                }
                currentRoute != NavRoute.Main -> {
                    currentRoute = NavRoute.Main
                }
                currentTab != MeloTab.HOME -> {
                    currentTab = MeloTab.HOME
                }
            }
        }

        Box(Modifier.fillMaxSize().background(VoidBlack)) {
            if (currentRoute == NavRoute.Main) {
                Box(Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = currentTab,
                        transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(350)) },
                        label = "tab"
                    ) { tab ->
                        when (tab) {
                            MeloTab.HOME -> HomeScene(
                                onPlay = { s, q -> startSongPlayback(s, q) },
                                onStartMoodRadio = { s, mood -> playSongWithSmartRadio(s, mood) },
                                onSongOptions = { selectedSongForOptions = it },
                                repo = saavnRepository,
                                scrollState = homeScrollState,
                                recentSongs = recentlyPlayedList,
                                becauseArtist = becauseArtistName,
                                becauseList = becauseRecommendations,
                                isLoggedIn = userProfileName != null,
                                userName = userProfileName.orEmpty(),
                                onAccountClick = { showAccountSheet = true }
                            )

                            MeloTab.EXPLORE -> ExploreScene(
                                onPlaySongTap = { song ->
                                    // Clicking any searched song immediately starts a fresh radio for that song
                                    playSongWithSmartRadio(song)
                                },
                                onSongOptions = { selectedSongForOptions = it },
                                repo = saavnRepository
                            )

                            MeloTab.LIBRARY -> MyWorldScene(
                                likedCount = likedSongsList.size,
                                recentCount = recentlyPlayedList.size,
                                downloadCount = downloadedSongsList.size,
                                playlists = userPlaylists,
                                onOpenLiked = { currentRoute = NavRoute.LikedSongs },
                                onOpenRecent = { currentRoute = NavRoute.RecentlyPlayed },
                                onOpenDownloads = { currentRoute = NavRoute.Downloads },
                                onCreatePlaylist = { currentRoute = NavRoute.CreatePlaylist },
                                onOpenPlaylist = { currentRoute = it }
                            )
                        }
                    }

                    if (playerTier == PlayerTier.MINI) {
                        MeloBottomNav(
                            selectedTab = currentTab,
                            onSelectTab = {
                                currentTab = it
                                currentRoute = NavRoute.Main
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = 12.dp)
                        )
                    }
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    when (val route = currentRoute) {
                        NavRoute.Main -> {}
                        is NavRoute.CreatePlaylist -> CreatePlaylistScene(
                            onBack = { currentRoute = NavRoute.Main },
                            onCreate = { name, img ->
                                userPlaylists.add(0, UserPlaylist(name, img))
                                persistUserPlaylists()
                                currentRoute = NavRoute.Main
                            }
                        )

                        is NavRoute.PlaylistDetail -> {
                            val targetPlaylist = userPlaylists.find { it.name == route.name }
                                ?: UserPlaylist(route.name, route.cover, route.songs.toMutableList()).also {
                                    userPlaylists.add(0, it)
                                    persistUserPlaylists()
                                }
                            PlaylistDetailScene(
                                playlist = targetPlaylist,
                                onPlay = { s, q -> startSongPlayback(s, q) },
                                onDelete = {
                                    userPlaylists.removeAll { it.name == targetPlaylist.name }
                                    persistUserPlaylists()
                                    currentRoute = NavRoute.Main
                                },
                                onSave = { persistUserPlaylists() },
                                onBack = { currentRoute = NavRoute.Main },
                                repo = saavnRepository
                            )
                        }

                        is NavRoute.LikedSongs -> SongCollectionDetailScene(
                            title = "Liked Songs",
                            accentColor = Color(0xFFFF4081),
                            bgColorTop = Color(0xFF880E4F),
                            songs = likedSongsList,
                            onPlay = { s, q -> startSongPlayback(s, q) },
                            onBack = { currentRoute = NavRoute.Main }
                        )

                        is NavRoute.RecentlyPlayed -> SongCollectionDetailScene(
                            title = "Recently Played",
                            accentColor = Color(0xFF00E5FF),
                            bgColorTop = Color(0xFF01579B),
                            songs = recentlyPlayedList,
                            onPlay = { s, q -> startSongPlayback(s, q) },
                            onBack = { currentRoute = NavRoute.Main }
                        )

                        is NavRoute.Downloads -> DownloadsDetailScene(
                            songs = downloadedSongsList,
                            onPlay = { s, q -> startSongPlayback(s, q) },
                            onDelete = { deleteDownloadedSong(it) },
                            onBack = { currentRoute = NavRoute.Main }
                        )
                    }
                }
            }

            val isKeyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            val miniPlayerBottomPadding =
                if (isKeyboardOpen) 10.dp else if (currentRoute == NavRoute.Main) 86.dp else 16.dp

            // FIX #4: Coordinated Morphing Spring Physics between Mini & Expanded Player
            AnimatedVisibility(
                visible = playerState.song != null && playerTier == PlayerTier.MINI,
                enter = slideInVertically(
                    initialOffsetY = { it / 2 },
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f)
                ) + scaleIn(initialScale = 0.92f) + fadeIn(tween(180)),
                exit = slideOutVertically(
                    targetOffsetY = { -it / 3 },
                    animationSpec = tween(180, easing = FastOutSlowInEasing)
                ) + scaleOut(targetScale = 0.95f) + fadeOut(tween(140)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(bottom = miniPlayerBottomPadding, start = 14.dp, end = 14.dp)
            ) {
                playerState.song?.let { song ->
                    PremiumMiniPlayer(
                        song = song,
                        artworkColor = smoothArtworkColor,
                        isPlaying = playerState.isPlaying,
                        isBuffering = playerState.isBuffering,
                        onExpand = { playerTier = PlayerTier.EXPANDED },
                        onTogglePlay = {
                            userPausedManually = playerState.isPlaying
                            player.togglePlayPause(
                                resolveIfIdle = {
                                    startSongPlayback(
                                        song = song,
                                        contextQueue = if (currentQueue.isNotEmpty()) currentQueue else listOf(song)
                                    )
                                }
                            )
                        },
                        onNext = { playNext() },
                        onPrev = { playPrev() }
                    )
                }
            }

            AnimatedVisibility(
                visible = playerState.song != null && playerTier == PlayerTier.EXPANDED,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = spring(dampingRatio = 0.84f, stiffness = 380f)
                ) + scaleIn(initialScale = 0.94f) + fadeIn(tween(220)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = spring(dampingRatio = 0.86f, stiffness = 400f)
                ) + scaleOut(targetScale = 0.94f) + fadeOut(tween(180))
            ) {
                playerState.song?.let { song ->
                    ImmersivePlayerScene(
                        song = song,
                        artworkColor = smoothArtworkColor,
                        secondaryColor = smoothSecondaryColor,
                        isPlaying = playerState.isPlaying,
                        isBuffering = playerState.isBuffering,
                        currentPositionMs = currentPlaybackPositionMs,
                        isLiked = likedSongsList.any { it.id == song.id },
                        queue = currentQueue,
                        currentQueueIndex = currentQueueIndex,
                        playlists = userPlaylists,
                        playMode = playMode,
                        downloadProgress = downloadProgressMap[song.id]
                            ?: (if (downloadedSongsList.any { it.id == song.id }) -1f else 0f),
                        onDownloadClick = { downloadSongInternally(song) },
                        onLikeToggle = { toggleLikeSong(song) },
                        onAddToPlaylist = { pl -> addSongToPlaylist(pl, song) },
                        onPlayQueueSong = { qSong -> startSongPlayback(qSong, currentQueue) },
                        onRemoveFromQueue = { songToRemove ->
                            val removeIndex = currentQueue.indexOf(songToRemove)
                            if (removeIndex != -1) {
                                currentQueue.removeAt(removeIndex)
                                if (removeIndex < currentQueueIndex) {
                                    currentQueueIndex = (currentQueueIndex - 1).coerceAtLeast(0)
                                }
                            }
                        },
                        onCollapse = { playerTier = PlayerTier.MINI },
                        onTogglePlay = {
                            userPausedManually = playerState.isPlaying
                            player.togglePlayPause(
                                resolveIfIdle = {
                                    startSongPlayback(
                                        song = song,
                                        contextQueue = if (currentQueue.isNotEmpty()) currentQueue else listOf(song)
                                    )
                                }
                            )
                        },
                        onNext = { playNext() },
                        onPrev = { playPrev() },
                        onTogglePlayMode = {
                            playMode = when (playMode) {
                                MODE_REPEAT_ALL -> MODE_REPEAT_ONE
                                MODE_REPEAT_ONE -> MODE_SHUFFLE
                                else -> MODE_REPEAT_ALL
                            }
                        },
                        onSeek = { seekSong(it) },
                        onUserSeekingChange = { isUserSeeking = it },
                        onEnterCinematic = {
                            playerTier = PlayerTier.CINEMATIC
                            context.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        },
                        repository = saavnRepository
                    )
                }
            }

            AnimatedVisibility(
                visible = playerState.song != null && playerTier == PlayerTier.CINEMATIC,
                enter = fadeIn(tween(500)),
                exit = fadeOut(tween(500))
            ) {
                playerState.song?.let { song ->
                    CinematicLandscapeScene(
                        song = song,
                        artworkColor = smoothArtworkColor,
                        secondaryColor = smoothSecondaryColor,
                        isPlaying = playerState.isPlaying,
                        currentPositionMs = currentPlaybackPositionMs,
                        onExit = {
                            playerTier = PlayerTier.EXPANDED
                            context.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        },
                        onTogglePlay = {
                            userPausedManually = playerState.isPlaying
                            player.togglePlayPause()
                        },
                        onSeek = { seekSong(it) },
                        repository = saavnRepository
                    )
                }
            }

            // FIX #2: Contextual Track Action Sheet (Play Now, Play Next, Add to Queue, Start Radio)
            selectedSongForOptions?.let { targetSong ->
                ModalBottomSheet(
                    onDismissRequest = { selectedSongForOptions = null },
                    containerColor = DeepSurface
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 36.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = targetSong.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(54.dp).clip(RoundedCornerShape(12.dp))
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(targetSong.title, color = TextPure, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(targetSong.artist, color = TextMuted, fontSize = 13.sp, maxLines = 1)
                            }
                            DownloadStateIcon(
                                progress = downloadProgressMap[targetSong.id]
                                    ?: (if (downloadedSongsList.any { it.id == targetSong.id }) -1f else 0f),
                                onClick = { downloadSongInternally(targetSong) }
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        HorizontalDivider(color = GlassSurface)
                        Spacer(Modifier.height(8.dp))

                        ListItem(
                            headlineContent = { Text("Play Immediately (Keep Queue)", color = TextPure, fontWeight = FontWeight.Bold) },
                            leadingContent = { Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = MeloAccent) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                                playImmediatelyPreservingQueue(targetSong)
                                selectedSongForOptions = null
                            }
                        )
                        ListItem(
                            headlineContent = { Text("Play Next", color = TextPure, fontWeight = FontWeight.Medium) },
                            leadingContent = { Icon(Icons.Rounded.QueuePlayNext, contentDescription = null, tint = Color(0xFF00E5FF)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                                insertPlayNext(targetSong)
                                selectedSongForOptions = null
                            }
                        )
                        ListItem(
                            headlineContent = { Text("Add to End of Queue", color = TextPure, fontWeight = FontWeight.Medium) },
                            leadingContent = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null, tint = Color(0xFFFFD54F)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                                appendToQueue(targetSong)
                                selectedSongForOptions = null
                            }
                        )
                        ListItem(
                            headlineContent = { Text("Start Fresh Radio from Song", color = TextPure, fontWeight = FontWeight.Medium) },
                            leadingContent = { Icon(Icons.Rounded.Radio, contentDescription = null, tint = Color(0xFF9B51E0)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                                playSongWithSmartRadio(targetSong)
                                selectedSongForOptions = null
                            }
                        )
                        ListItem(
                            headlineContent = { Text("Start ${targetSong.artist.split(",").first()} Radio", color = TextPure, fontWeight = FontWeight.Medium) },
                            leadingContent = { Icon(Icons.Rounded.PersonSearch, contentDescription = null, tint = Color(0xFF00E676)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                                startArtistRadio(targetSong.artist)
                                selectedSongForOptions = null
                            }
                        )
                    }
                }
            }

            if (showAccountSheet) {
                var isEditingName by remember { mutableStateOf(false) }
                var tempNameInput by remember { mutableStateOf(userProfileName ?: "") }

                ModalBottomSheet(
                    onDismissRequest = { showAccountSheet = false },
                    containerColor = DeepSurface
                ) {
                    Column(Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 32.dp)) {
                        Text(
                            "Melo Sanctuary",
                            fontSize = 24.sp,
                            color = TextPure,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Serif
                        )
                        Spacer(Modifier.height(24.dp))

                        if (isEditingName) {
                            Column(Modifier.fillMaxWidth()) {
                                Text("Enter Your Name", color = TextPure, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(8.dp))
                                BasicTextField(
                                    value = tempNameInput,
                                    onValueChange = { tempNameInput = it },
                                    textStyle = TextStyle(color = TextPure, fontSize = 16.sp),
                                    singleLine = true,
                                    cursorBrush = SolidColor(MeloAccent),
                                    modifier = Modifier.fillMaxWidth()
                                        .background(VoidBlack, RoundedCornerShape(12.dp))
                                        .border(1.dp, GlassSurface, RoundedCornerShape(12.dp))
                                        .padding(14.dp)
                                )
                                Spacer(Modifier.height(14.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(
                                        onClick = {
                                            val clean = tempNameInput.trim()
                                            userProfileName = clean.ifBlank { null }
                                            prefs.edit().putString("user_profile_name", userProfileName).apply()
                                            isEditingName = false
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MeloAccent),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Save", color = TextPure) }
                                    OutlinedButton(
                                        onClick = { isEditingName = false },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Cancel", color = TextMuted) }
                                }
                            }
                        } else {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        if (userProfileName != null) "Logged in as $userProfileName" else "Sound Profile",
                                        color = TextPure,
                                        fontWeight = FontWeight.Medium
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        if (userProfileName != null) "Tap to change name or log out" else "Tap to log in & personalize greeting",
                                        color = TextMuted
                                    )
                                },
                                leadingContent = { Icon(Icons.Rounded.Person, tint = MeloAccent, contentDescription = null) },
                                trailingContent = {
                                    if (userProfileName != null) {
                                        TextButton(onClick = {
                                            userProfileName = null
                                            prefs.edit().remove("user_profile_name").apply()
                                        }) { Text("Log Out", color = MeloAccent, fontSize = 12.sp) }
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { isEditingName = true }
                            )
                        }

                        ListItem(
                            headlineContent = { Text("Import Playlist", color = TextPure, fontWeight = FontWeight.Medium) },
                            supportingContent = { Text("Import songs from Spotify link", color = TextMuted) },
                            leadingContent = { Icon(Icons.Rounded.CloudDownload, tint = Color(0xFFFFD54F), contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable {
                                showAccountSheet = false
                                showImportSheet = true
                            }
                        )
                        ListItem(
                            headlineContent = { Text("Check Updates", color = TextPure, fontWeight = FontWeight.Medium) },
                            supportingContent = { Text("Check for the newest release", color = TextMuted) },
                            leadingContent = { Icon(Icons.Rounded.SystemUpdateAlt, tint = Color(0xFF00E5FF), contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable {
                                showAccountSheet = false
                                showUpdateSheetState = true
                                checkGithubForUpdates(silentBackgroundCheck = false)
                            }
                        )
                    }
                }
            }

            if (showImportSheet) {
                var linkOrName by remember { mutableStateOf("") }
                var isImporting by remember { mutableStateOf(false) }
                var importProgress by remember { mutableFloatStateOf(0f) }
                var importStatus by remember { mutableStateOf("") }

                ModalBottomSheet(
                    onDismissRequest = { if (!isImporting) showImportSheet = false },
                    containerColor = DeepSurface
                ) {
                    Column(Modifier.fillMaxWidth().padding(26.dp).padding(bottom = 36.dp)) {
                        Text("Sonic Importer", fontSize = 24.sp, color = TextPure, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                        Spacer(Modifier.height(8.dp))
                        Text("Paste a Spotify playlist link to extract genuine songs.", color = TextMuted, fontSize = 13.sp)
                        Spacer(Modifier.height(24.dp))

                        if (isImporting) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                                LinearProgressIndicator(
                                    progress = { importProgress },
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                    color = Color(0xFFFFD54F),
                                    trackColor = GlassSurface
                                )
                                Spacer(Modifier.height(14.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(importStatus, color = TextPure, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                    Text("${(importProgress * 100).roundToInt()}%", color = Color(0xFFFFD54F), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            BasicTextField(
                                value = linkOrName,
                                onValueChange = { linkOrName = it },
                                textStyle = TextStyle(color = TextPure, fontSize = 15.sp),
                                singleLine = true,
                                cursorBrush = SolidColor(Color(0xFFFFD54F)),
                                modifier = Modifier.fillMaxWidth()
                                    .background(VoidBlack, RoundedCornerShape(14.dp))
                                    .border(1.dp, GlassSurface, RoundedCornerShape(14.dp))
                                    .padding(16.dp),
                                decorationBox = { inner ->
                                    if (linkOrName.isEmpty()) Text("Paste Spotify playlist link...", color = TextSubtle, fontSize = 14.sp) else inner()
                                }
                            )
                        }

                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = {
                                isImporting = true
                                importProgress = 0.05f
                                importStatus = "Connecting to Spotify..."
                                lifecycleScope.launch(Dispatchers.IO) {
                                    var playlistTitle = "Imported Playlist"
                                    var playlistCover = ""
                                    val trackQueryList = mutableListOf<Pair<String, String>>()
                                    val resolvedSongs = mutableListOf<Song>()
                                    var targetUrl = linkOrName.trim()

                                    if (targetUrl.contains("spotify.link")) {
                                        try {
                                            val headReq = Request.Builder().url(targetUrl).header("User-Agent", "Mozilla/5.0").build()
                                            val headResp = httpClient.newCall(headReq).execute()
                                            targetUrl = headResp.request.url.toString()
                                        } catch (_: Exception) {}
                                    }

                                    if (targetUrl.contains("spotify.com")) {
                                        try {
                                            val type = when {
                                                targetUrl.contains("/album/") -> "album"
                                                targetUrl.contains("/track/") -> "track"
                                                else -> "playlist"
                                            }
                                            val idMatch = Regex("$type/([a-zA-Z0-9]+)").find(targetUrl)
                                            val spotifyId = idMatch?.groupValues?.get(1)

                                            try {
                                                val oembedUrl = "https://open.spotify.com/oembed?url=${URLEncoder.encode(targetUrl, "UTF-8")}"
                                                val oembedReq = Request.Builder().url(oembedUrl).header("User-Agent", "Mozilla/5.0").build()
                                                val oembedResp = httpClient.newCall(oembedReq).execute().body?.string() ?: ""
                                                val oembedJson = JsonParser.parseString(oembedResp).asJsonObject
                                                playlistTitle = oembedJson.get("title")?.asString ?: playlistTitle
                                                playlistCover = oembedJson.get("thumbnail_url")?.asString ?: playlistCover
                                            } catch (_: Exception) {}

                                            if (spotifyId != null) {
                                                val embedUrl = "https://open.spotify.com/embed/$type/$spotifyId"
                                                val embedReq = Request.Builder()
                                                    .url(embedUrl)
                                                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                                    .header("Accept-Language", "en-US,en;q=0.9")
                                                    .build()

                                                val respHtml = httpClient.newCall(embedReq).execute().body?.string() ?: ""
                                                val nextDataMatch = Regex("""<script[^>]*id="__NEXT_DATA__"[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE).find(respHtml)
                                                val jsonContent = nextDataMatch?.groupValues?.get(1)

                                                if (!jsonContent.isNullOrBlank()) {
                                                    try {
                                                        val root = JsonParser.parseString(jsonContent).asJsonObject
                                                        val entity = root.getAsJsonObject("props")
                                                            ?.getAsJsonObject("pageProps")
                                                            ?.getAsJsonObject("state")
                                                            ?.getAsJsonObject("data")
                                                            ?.getAsJsonObject("entity")

                                                        if (entity != null) {
                                                            val name = entity.get("name")?.asString ?: entity.get("title")?.asString
                                                            if (!name.isNullOrBlank()) playlistTitle = name

                                                            val trackArray = entity.getAsJsonArray("trackList")
                                                            if (trackArray != null) {
                                                                for (i in 0 until trackArray.size()) {
                                                                    val tObj = trackArray.get(i).asJsonObject
                                                                    val tName = tObj.get("title")?.asString ?: tObj.get("name")?.asString ?: ""
                                                                    val tArtist = tObj.get("subtitle")?.asString ?: ""
                                                                    if (tName.isNotBlank()) {
                                                                        trackQueryList.add(Pair(tName, tArtist))
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    } catch (_: Exception) {}
                                                }

                                                if (trackQueryList.isEmpty()) {
                                                    val pattern = Regex(""""title"\s*:\s*"([^"]+)"\s*,\s*"subtitle"\s*:\s*"([^"]+)"""")
                                                    pattern.findAll(respHtml).forEach { match ->
                                                        val tName = match.groupValues[1].trim()
                                                        val tArtist = match.groupValues[2].trim()
                                                        if (tName.isNotBlank() && trackQueryList.none { it.first == tName }) {
                                                            trackQueryList.add(Pair(tName, tArtist))
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }

                                    if (trackQueryList.isNotEmpty()) {
                                        val total = trackQueryList.size.coerceAtMost(40)
                                        for (i in 0 until total) {
                                            val (trackTitle, trackArtist) = trackQueryList[i]
                                            withContext(Dispatchers.Main) {
                                                importProgress = 0.1f + ((i.toFloat() / total.toFloat()) * 0.85f)
                                                importStatus = "Resolving track ${i + 1}/$total: $trackTitle"
                                            }
                                            val matched = saavnRepository.searchSongs("$trackTitle $trackArtist")
                                            if (matched.isNotEmpty()) {
                                                resolvedSongs.add(matched[0])
                                            }
                                        }
                                    } else {
                                        playlistTitle = if (targetUrl.length > 25) "Imported Playlist" else targetUrl.replaceFirstChar { it.uppercase() }
                                        resolvedSongs.addAll(saavnRepository.searchSongs(playlistTitle))
                                    }

                                    val finalCover = playlistCover.ifBlank {
                                        resolvedSongs.firstOrNull()?.thumbnailUrl ?: PRESET_COVERS[0]
                                    }

                                    withContext(Dispatchers.Main) {
                                        userPlaylists.add(
                                            0,
                                            UserPlaylist(
                                                name = playlistTitle,
                                                coverUrl = finalCover,
                                                songs = resolvedSongs
                                            )
                                        )
                                        persistUserPlaylists()
                                        isImporting = false
                                        showImportSheet = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD54F)),
                            shape = RoundedCornerShape(14.dp),
                            enabled = linkOrName.isNotBlank() && !isImporting
                        ) {
                            Text("IMPORT PLAYLIST", color = VoidBlack, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }

            if (showUpdateSheetState) {
                val updateSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(
                    onDismissRequest = { showUpdateSheetState = false },
                    sheetState = updateSheetState,
                    containerColor = DeepSurface
                ) {
                    Column(
                        Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AnimatedContent(
                            targetState = isCheckingUpdate to (updateVersion != null),
                            label = "update_anim"
                        ) { (checking, hasUpdate) ->
                            if (checking) {
                                Column(
                                    Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CircularProgressIndicator(color = MeloAccent, strokeWidth = 3.dp, modifier = Modifier.size(60.dp))
                                    Spacer(Modifier.height(24.dp))
                                    Text("Contacting Core...", color = TextPure, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                                }
                            } else if (hasUpdate) {
                                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        Modifier.size(72.dp).clip(CircleShape).background(Color(0xFF00E5FF).copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.NewReleases, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(42.dp))
                                    }
                                    Spacer(Modifier.height(18.dp))
                                    Text("Update Available", fontSize = 28.sp, color = TextPure, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                                    Text("Release $updateVersion", fontSize = 16.sp, color = Color(0xFF00E5FF), fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(20.dp))
                                    Box(
                                        Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(VoidBlack).padding(20.dp)
                                    ) {
                                        Text(updateNotes, color = TextMuted, fontSize = 14.sp, lineHeight = 22.sp)
                                    }
                                    Spacer(Modifier.height(24.dp))
                                    Button(
                                        onClick = {
                                            downloadAndInstallUpdate()
                                            showUpdateSheetState = false
                                        },
                                        modifier = Modifier.fillMaxWidth().height(56.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                                        shape = RoundedCornerShape(18.dp)
                                    ) {
                                        Text("DOWNLOAD & AUTO-INSTALL", color = VoidBlack, fontWeight = FontWeight.Black)
                                    }
                                }
                            } else {
                                Column(
                                    Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        Modifier.size(76.dp).clip(CircleShape).background(Color(0xFF00E676).copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF00E676), modifier = Modifier.size(44.dp))
                                    }
                                    Spacer(Modifier.height(18.dp))
                                    Text("Up to Date", fontSize = 28.sp, color = TextPure, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)
                                    Text("Melo $CURRENT_APP_VERSION is currently the newest release", fontSize = 14.sp, color = TextMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // FIX #5: Reusable Circular Download Progress Ring Component
    @Composable
    private fun DownloadStateIcon(
        progress: Float,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val isDownloaded = progress == -1f
        val isDownloading = progress in 0.01f..0.99f

        Box(
            modifier = modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(enabled = !isDownloaded && !isDownloading) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            when {
                isDownloaded -> {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E676)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = "Downloaded",
                            tint = VoidBlack,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                isDownloading -> {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(26.dp),
                            color = Color(0xFF00E5FF),
                            trackColor = Color(0x3300E5FF),
                            strokeWidth = 2.5.dp
                        )
                        Text(
                            text = "${(progress * 100).toInt()}",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E5FF)
                        )
                    }
                }
                else -> {
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = "Download",
                        tint = TextSubtle,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    private suspend fun extractTwoDominantColorsAndBitmap(
        context: Context,
        imageUrl: String
    ): Triple<Color, Color, Bitmap?> {
        return withContext(Dispatchers.IO) {
            try {
                val loader = context.imageLoader
                val request = ImageRequest.Builder(context).data(imageUrl).allowHardware(false).build()
                val result = (loader.execute(request) as? SuccessResult)?.drawable
                val bitmap = (result as? BitmapDrawable)?.bitmap
                if (bitmap != null) {
                    var bestColor1 = android.graphics.Color.DKGRAY
                    var bestColor2 = android.graphics.Color.parseColor("#00E5FF")
                    var highestScore1 = -1f
                    var highestScore2 = -1f
                    val hsv = FloatArray(3)
                    val stepX = (bitmap.width / 24).coerceAtLeast(1)
                    val stepY = (bitmap.height / 24).coerceAtLeast(1)

                    for (x in 0 until bitmap.width step stepX) {
                        for (y in 0 until bitmap.height step stepY) {
                            val pixel = bitmap.getPixel(x, y)
                            if (android.graphics.Color.alpha(pixel) < 128) continue
                            android.graphics.Color.colorToHSV(pixel, hsv)
                            val sat = hsv[1]
                            val value = hsv[2]

                            if (sat > 0.15f && value > 0.15f) {
                                val score = sat * 2.2f + (1.0f - kotlin.math.abs(value - 0.65f))
                                if (score > highestScore1) {
                                    highestScore2 = highestScore1
                                    bestColor2 = bestColor1
                                    highestScore1 = score
                                    bestColor1 = pixel
                                } else if (score > highestScore2 && kotlin.math.abs(pixel - bestColor1) > 50000) {
                                    highestScore2 = score
                                    bestColor2 = pixel
                                }
                            }
                        }
                    }

                    if (highestScore1 == -1f) bestColor1 = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    if (highestScore2 == -1f) bestColor2 = android.graphics.Color.parseColor("#00E5FF")

                    android.graphics.Color.colorToHSV(bestColor1, hsv)
                    if (hsv[2] < 0.60f) hsv[2] = 0.70f
                    if (hsv[1] < 0.45f) hsv[1] = 0.60f
                    val boosted1 = android.graphics.Color.HSVToColor(hsv)

                    android.graphics.Color.colorToHSV(bestColor2, hsv)
                    if (hsv[2] < 0.60f) hsv[2] = 0.70f
                    if (hsv[1] < 0.45f) hsv[1] = 0.60f
                    val boosted2 = android.graphics.Color.HSVToColor(hsv)

                    Triple(Color(boosted1), Color(boosted2), bitmap)
                } else Triple(Color(0xFFFF5252), Color(0xFF00E5FF), null)
            } catch (_: Exception) {
                Triple(Color(0xFFFF5252), Color(0xFF00E5FF), null)
            }
        }
    }

    private suspend fun extractDominantColorAndBitmap(
        context: Context,
        imageUrl: String
    ): Pair<Color, Bitmap?> {
        val (c1, _, bmp) = extractTwoDominantColorsAndBitmap(context, imageUrl)
        return Pair(c1, bmp)
    }

    @Composable
    private fun rememberShimmerBrush(): Brush {
        val transition = rememberInfiniteTransition(label = "shimmer_trans")
        val translateAnim by transition.animateFloat(
            initialValue = -350f,
            targetValue = 1350f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "shimmer_offset"
        )
        return Brush.linearGradient(
            colors = listOf(Color(0xFF14141E), Color(0xFF262638), Color(0xFF14141E)),
            start = Offset(translateAnim, 0f),
            end = Offset(translateAnim + 350f, 250f)
        )
    }

    // ==========================================
    // SCENE 01: HOME
    // ==========================================
    // ==========================================
    // SCENE 01: RICH SPOTIFY-STYLE HOME + GEMINI AURORA
    // ==========================================
    data class CuratedMixCard(
        val tag: String,
        val title: String,
        val subtitle: String,
        val query: String,
        val colors: List<Color>
    )

    @Composable
    private fun GeminiAuroraHeader(modifier: Modifier = Modifier) {
        val infinite = rememberInfiniteTransition(label = "gemini_aurora_v2")

        val orbit1 by infinite.animateFloat(
            initialValue = 0f,
            targetValue = (2f * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 7000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "orbit1"
        )
        val orbit2 by infinite.animateFloat(
            initialValue = 0f,
            targetValue = (2f * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 10500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "orbit2"
        )
        val breathe by infinite.animateFloat(
            initialValue = 0.88f,
            targetValue = 1.22f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathe"
        )

        Canvas(
            modifier = modifier
                .fillMaxWidth()
                .height(520.dp)
        ) {
            val w = size.width
            val h = size.height

            // Deep atmospheric base glow
            drawRect(
                brush = Brush.verticalGradient(
                    0.0f to Color(0xFF0E1329),
                    0.45f to Color(0xFF090A14),
                    1.0f to VoidBlack
                )
            )

            // Aurora Beam 1: Gemini Electric Blue (Top-Left Sweeping Arc)
            val c1 = Offset(
                x = w * (0.18f + 0.28f * cos(orbit1)),
                y = h * (0.16f + 0.12f * sin(orbit1))
            )
            val r1 = w * 0.78f * breathe
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to Color(0xFF3B82F6).copy(alpha = 0.72f),
                    0.45f to Color(0xFF1D4ED8).copy(alpha = 0.32f),
                    1.0f to Color.Transparent,
                    center = c1,
                    radius = r1
                ),
                radius = r1,
                center = c1,
                blendMode = androidx.compose.ui.graphics.BlendMode.Screen
            )

            // Aurora Beam 2: Gemini Cosmic Violet / Orchid (Top-Right Counter-Orbit)
            val c2 = Offset(
                x = w * (0.82f + 0.26f * sin(orbit2)),
                y = h * (0.20f + 0.14f * cos(orbit1))
            )
            val r2 = w * 0.75f
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to Color(0xFFA855F7).copy(alpha = 0.68f),
                    0.50f to Color(0xFF6B21A8).copy(alpha = 0.28f),
                    1.0f to Color.Transparent,
                    center = c2,
                    radius = r2
                ),
                radius = r2,
                center = c2,
                blendMode = androidx.compose.ui.graphics.BlendMode.Screen
            )

            // Aurora Beam 3: Gemini Coral Rose / Magenta (Center-Top Crest)
            val c3 = Offset(
                x = w * (0.52f + 0.30f * sin(orbit1 - orbit2)),
                y = h * (0.28f + 0.10f * sin(orbit2))
            )
            val r3 = w * 0.68f * (2.05f - breathe)
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to Color(0xFFF43F5E).copy(alpha = 0.58f),
                    0.48f to Color(0xFFBE123C).copy(alpha = 0.22f),
                    1.0f to Color.Transparent,
                    center = c3,
                    radius = r3
                ),
                radius = r3,
                center = c3,
                blendMode = androidx.compose.ui.graphics.BlendMode.Screen
            )

            // Aurora Beam 4: Luminous Cyan Core Highlight (Top Edge Shimmer)
            val c4 = Offset(
                x = w * (0.45f + 0.24f * cos(orbit2)),
                y = h * (0.08f + 0.07f * sin(orbit1))
            )
            val r4 = w * 0.56f
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to Color(0xFF00F0FF).copy(alpha = 0.52f),
                    0.40f to Color(0xFF0891B2).copy(alpha = 0.18f),
                    1.0f to Color.Transparent,
                    center = c4,
                    radius = r4
                ),
                radius = r4,
                center = c4,
                blendMode = androidx.compose.ui.graphics.BlendMode.Screen
            )

            // Smooth bottom feather into VoidBlack so content shelves blend seamlessly
            drawRect(
                brush = Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.62f to Color.Transparent,
                    0.86f to VoidBlack.copy(alpha = 0.72f),
                    1.0f to VoidBlack
                )
            )
        }
    }

    @Composable
    private fun HomeScene(
        onPlay: (Song, List<Song>) -> Unit,
        onStartMoodRadio: (Song, String) -> Unit,
        onSongOptions: (Song) -> Unit,
        repo: JioSaavnRepository,
        scrollState: LazyListState,
        recentSongs: List<Song>,
        becauseArtist: String?,
        becauseList: List<Song>,
        isLoggedIn: Boolean,
        userName: String,
        onAccountClick: () -> Unit
    ) {
        var topPicks by remember { mutableStateOf<List<Song>>(emptyList()) }
        var dhhHits by remember { mutableStateOf<List<Song>>(emptyList()) }
        var punjabiWave by remember { mutableStateOf<List<Song>>(emptyList()) }
        var jumpBackIn by remember { mutableStateOf<List<Song>>(emptyList()) }
        var lofiSanctuary by remember { mutableStateOf<List<Song>>(emptyList()) }
        var indieAcoustic by remember { mutableStateOf<List<Song>>(emptyList()) }
        var globalCharts by remember { mutableStateOf<List<Song>>(emptyList()) }
        var retroClassics by remember { mutableStateOf<List<Song>>(emptyList()) }

        var activeFilter by remember { mutableStateOf<String?>(null) }
        var isLoading by remember { mutableStateOf(true) }

        val shimmerBrush = rememberShimmerBrush()
        val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
        val greeting = when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Late night"
        }

        val curatedMixes = remember {
            listOf(
                CuratedMixCard(
                    tag = "DAILY MIX 01",
                    title = "Desi Hip-Hop Cypher",
                    subtitle = "Seedhe Maut, KR\$NA, DIVINE, Raftaar",
                    query = "Seedhe Maut KRSNA Divine",
                    colors = listOf(Color(0xFF4285F4), Color(0xFF1A237E))
                ),
                CuratedMixCard(
                    tag = "DAILY MIX 02",
                    title = "Midnight Lo-Fi & Rain",
                    subtitle = "Slowed & Reverb, Aesthetic Beats",
                    query = "Hindi Lofi Slowed Reverb",
                    colors = listOf(Color(0xFF9B72CB), Color(0xFF311B92))
                ),
                CuratedMixCard(
                    tag = "DAILY MIX 03",
                    title = "Punjabi Turbo Drive",
                    subtitle = "Karan Aujla, Diljit, AP Dhillon, Shubh",
                    query = "Karan Aujla Diljit Shubh Hits",
                    colors = listOf(Color(0xFFD96570), Color(0xFF880E4F))
                ),
                CuratedMixCard(
                    tag = "DAILY MIX 04",
                    title = "Indie & Chai Acoustic",
                    subtitle = "Anuv Jain, Prateek Kuhad, The Local Train",
                    query = "Anuv Jain Prateek Kuhad Local Train",
                    colors = listOf(Color(0xFF00BFA5), Color(0xFF004D40))
                ),
                CuratedMixCard(
                    tag = "DAILY MIX 05",
                    title = "Golden 90s & 2000s",
                    subtitle = "KK, Sonu Nigam, Shaan, Kumar Sanu",
                    query = "KK Sonu Nigam 2000s Bollywood Hits",
                    colors = listOf(Color(0xFFFFB300), Color(0xFFE65100))
                )
            )
        }

        val activeQuery = activeFilter ?: "Top Trending Hindi Hits 2025"

        // Fetch all shelves concurrently in parallel for fast startup
        LaunchedEffect(activeQuery) {
            isLoading = true
            coroutineScope {
                val topDeferred = async { repo.searchSongs(activeQuery) }
                val dhhDeferred = if (dhhHits.isEmpty()) async { repo.searchSongs("Seedhe Maut Divine KRSNA Hits") } else null
                val punjabiDeferred = if (punjabiWave.isEmpty()) async { repo.searchSongs("Karan Aujla Diljit Dosanjh Shubh") } else null
                val jumpDeferred = if (jumpBackIn.isEmpty()) async { repo.searchSongs("Arijit Singh Atif Aslam KK") } else null
                val lofiDeferred = if (lofiSanctuary.isEmpty()) async { repo.searchSongs("Bollywood Lofi Chill Beats") } else null
                val indieDeferred = if (indieAcoustic.isEmpty()) async { repo.searchSongs("Anuv Jain Prateek Kuhad Indie") } else null
                val globalDeferred = if (globalCharts.isEmpty()) async { repo.searchSongs("The Weeknd Post Malone Popular") } else null
                val retroDeferred = if (retroClassics.isEmpty()) async { repo.searchSongs("Kishore Kumar 90s Bollywood Classics") } else null

                topPicks = topDeferred.await()
                dhhDeferred?.await()?.let { dhhHits = it }
                punjabiDeferred?.await()?.let { punjabiWave = it }
                jumpDeferred?.await()?.let { jumpBackIn = it }
                lofiDeferred?.await()?.let { lofiSanctuary = it }
                indieDeferred?.await()?.let { indieAcoustic = it }
                globalDeferred?.await()?.let { globalCharts = it }
                retroDeferred?.await()?.let { retroClassics = it }
            }
            isLoading = false
        }

        Box(Modifier.fillMaxSize()) {
            // Living Gemini-style Aurora Header
            GeminiAuroraHeader()

            LazyColumn(
                state = scrollState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp,
                    bottom = 180.dp
                )
            ) {
                // 1. HEADER & GREETING
                item {
                    Column(Modifier.padding(horizontal = 24.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_melo_logo),
                                contentDescription = "Melo Logo",
                                tint = Color.Unspecified,
                                modifier = Modifier.size(73.dp)
                            )
                            IconButton(
                                onClick = onAccountClick,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape)
                            ) {
                                Icon(
                                    Icons.Rounded.AccountCircle,
                                    contentDescription = "Account",
                                    tint = Color(0xFFFFD54F),
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))

                        if (isLoggedIn && userName.isNotBlank()) {
                            Text(greeting + ",", fontSize = 24.sp, color = TextMuted, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)
                            Text("$userName.", fontSize = 38.sp, color = TextPure, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                        } else {
                            Text(greeting + ".", fontSize = 36.sp, color = TextPure, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }

                // 2. MOOD & ACTIVITY RADIO BUILDERS
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf(
                            Triple("Chill", Icons.Rounded.AcUnit, Color(0xFF00E5FF)),
                            Triple("Focus", Icons.Rounded.LensBlur, Color(0xFF9B51E0)),
                            Triple("Workout", Icons.Rounded.FitnessCenter, Color(0xFFFF5252)),
                            Triple("Party", Icons.Rounded.Celebration, Color(0xFFFFD54F)),
                            Triple("Sad", Icons.Rounded.WaterDrop, Color(0xFF64B5F6))
                        ).forEach { (label, icon, color) ->
                            val queryTarget = when (label) {
                                "Chill" -> "Lofi Chill Hindi"
                                "Focus" -> "Instrumental Focus"
                                "Workout" -> "Workout Gym Motivation"
                                "Party" -> "Bollywood Party Club Hits"
                                else -> "Sad Emotional Hindi"
                            }
                            val isSelected = activeFilter == queryTarget

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) color else Color.White.copy(alpha = 0.10f))
                                        .border(1.dp, if (isSelected) color else Color.White.copy(alpha = 0.16f), CircleShape)
                                        .clickable {
                                            if (isSelected) {
                                                activeFilter = null
                                            } else {
                                                activeFilter = queryTarget
                                                lifecycleScope.launch {
                                                    val moodSeeds = repo.searchSongs(queryTarget)
                                                    if (moodSeeds.isNotEmpty()) {
                                                        withContext(Dispatchers.Main) {
                                                            onStartMoodRadio(moodSeeds.first(), label)
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(icon, contentDescription = null, tint = if (isSelected) VoidBlack else color)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    label,
                                    fontSize = 11.sp,
                                    color = if (isSelected) TextPure else TextMuted,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                }

                // 3. MAIN MULTI-SHELF FEED
                item {
                    AnimatedContent(
                        targetState = isLoading,
                        transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(200)) },
                        label = "home_content"
                    ) { loading ->
                        if (loading) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                                repeat(3) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Box(Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(12.dp)).background(shimmerBrush))
                                        Box(Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(12.dp)).background(shimmerBrush))
                                    }
                                    Spacer(Modifier.height(10.dp))
                                }
                                Spacer(Modifier.height(24.dp))
                                Box(Modifier.width(150.dp).height(22.dp).clip(RoundedCornerShape(6.dp)).background(shimmerBrush))
                                Spacer(Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    repeat(3) {
                                        Box(Modifier.size(135.dp).clip(RoundedCornerShape(18.dp)).background(shimmerBrush))
                                    }
                                }
                            }
                        } else {
                            Column(Modifier.fillMaxWidth()) {
                                // A. SPOTIFY-STYLE 2-COLUMN QUICK-RESUME GRID
                                val quickGridSongs = remember(recentSongs, topPicks, dhhHits) {
                                    (recentSongs + topPicks + dhhHits).distinctBy { it.id }.take(6)
                                }
                                if (quickGridSongs.size >= 4) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        quickGridSongs.chunked(2).forEach { pair ->
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                pair.forEach { qSong ->
                                                    Row(
                                                        Modifier
                                                            .weight(1f)
                                                            .height(56.dp)
                                                            .clip(RoundedCornerShape(12.dp))
                                                            .background(Color(0x24FFFFFF))
                                                            .border(1.dp, Color(0x18FFFFFF), RoundedCornerShape(12.dp))
                                                            .combinedClickable(
                                                                onClick = { onPlay(qSong, quickGridSongs) },
                                                                onLongClick = { onSongOptions(qSong) }
                                                            ),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        AsyncImage(
                                                            model = qSong.thumbnailUrl,
                                                            contentDescription = null,
                                                            contentScale = ContentScale.Crop,
                                                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                                                        )
                                                        Spacer(Modifier.width(10.dp))
                                                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                                            Text(
                                                                qSong.title,
                                                                fontSize = 12.5.sp,
                                                                color = TextPure,
                                                                fontWeight = FontWeight.Bold,
                                                                maxLines = 1
                                                            )
                                                            Text(
                                                                qSong.artist,
                                                                fontSize = 10.5.sp,
                                                                color = TextMuted,
                                                                maxLines = 1
                                                            )
                                                        }
                                                    }
                                                }
                                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(32.dp))
                                }

                                // B. 3D TRENDING HITS CAROUSEL
                                if (topPicks.isNotEmpty()) {
                                    Text(
                                        text = if (activeFilter != null) "Mood Spotlight" else "Trending Now",
                                        fontSize = 20.sp,
                                        color = TextPure,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 24.dp)
                                    )
                                    Spacer(Modifier.height(18.dp))
                                    TrendingHits3DCarousel(
                                        songs = topPicks,
                                        onPlay = { selectedSong -> onPlay(selectedSong, topPicks) },
                                        onLongPress = { selectedSong -> onSongOptions(selectedSong) }
                                    )
                                    Spacer(Modifier.height(32.dp))
                                }

                                // C. CURATED DAILY MIXES (Spotify-style Editorial Cards)
                                Text(
                                    text = "Made For You Mixes",
                                    fontSize = 20.sp,
                                    color = TextPure,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 24.dp)
                                )
                                Spacer(Modifier.height(6.dp))
                                Spacer(Modifier.height(16.dp))
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 24.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    items(curatedMixes) { mix ->
                                        Box(
                                            modifier = Modifier
                                                .width(165.dp)
                                                .height(195.dp)
                                                .clip(RoundedCornerShape(22.dp))
                                                .background(Brush.linearGradient(mix.colors))
                                                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(22.dp))
                                                .clickable {
                                                    lifecycleScope.launch {
                                                        val mixTracks = repo.searchSongs(mix.query)
                                                        if (mixTracks.isNotEmpty()) {
                                                            withContext(Dispatchers.Main) {
                                                                onStartMoodRadio(mixTracks.first(), mix.title)
                                                            }
                                                        }
                                                    }
                                                }
                                                .padding(16.dp)
                                        ) {
                                            Column(
                                                Modifier.fillMaxSize(),
                                                verticalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(
                                                    Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Box(
                                                        Modifier
                                                            .clip(RoundedCornerShape(6.dp))
                                                            .background(Color.Black.copy(alpha = 0.30f))
                                                            .padding(horizontal = 7.dp, vertical = 3.dp)
                                                    ) {
                                                        Text(mix.tag, fontSize = 9.sp, color = TextPure, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                                                    }
                                                    Icon(Icons.Rounded.PlayCircleFilled, contentDescription = null, tint = TextPure, modifier = Modifier.size(26.dp))
                                                }
                                                Column {
                                                    Text(mix.title, fontSize = 18.sp, color = TextPure, fontWeight = FontWeight.Black, lineHeight = 22.sp)
                                                    Spacer(Modifier.height(6.dp))
                                                    Text(mix.subtitle, fontSize = 11.sp, color = Color.White.copy(alpha = 0.80f), maxLines = 2, lineHeight = 14.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(34.dp))

                                // D. BECAUSE YOU LISTENED TO
                                if (becauseList.isNotEmpty()) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp)
                                            .clip(RoundedCornerShape(26.dp))
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(
                                                        Color(0xFFD4AF37).copy(alpha = 0.35f),
                                                        Color(0xFF8C6D23).copy(alpha = 0.15f),
                                                        Color(0xFF030304)
                                                    )
                                                )
                                            )
                                            .border(
                                                BorderStroke(
                                                    1.dp,
                                                    Brush.verticalGradient(listOf(Color(0xFFFFD700).copy(alpha = 0.5f), Color.Transparent))
                                                ),
                                                RoundedCornerShape(26.dp)
                                            )
                                            .padding(vertical = 22.dp)
                                    ) {
                                        Column {
                                            Row(
                                                Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text("BECAUSE YOU LISTENED TO", fontSize = 10.sp, letterSpacing = 2.sp, color = Color(0xFFFFD700), fontWeight = FontWeight.Bold)
                                                    Text(
                                                        text = becauseArtist ?: "Favorite Artist",
                                                        fontSize = 22.sp,
                                                        color = TextPure,
                                                        fontWeight = FontWeight.Bold,
                                                        fontFamily = FontFamily.Serif,
                                                        modifier = Modifier.combinedClickable(
                                                            onClick = {},
                                                            onLongClick = { becauseArtist?.let { startArtistRadio(it) } }
                                                        )
                                                    )
                                                }
                                                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Color(0xFFFFD700), modifier = Modifier.size(24.dp))
                                            }
                                            Spacer(Modifier.height(16.dp))
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = 22.dp),
                                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                                            ) {
                                                items(becauseList.take(8)) { song ->
                                                    Column(
                                                        Modifier
                                                            .width(120.dp)
                                                            .combinedClickable(
                                                                onClick = { onPlay(song, becauseList) },
                                                                onLongClick = { onSongOptions(song) }
                                                            )
                                                    ) {
                                                        AsyncImage(
                                                            model = song.thumbnailUrl,
                                                            contentDescription = null,
                                                            contentScale = ContentScale.Crop,
                                                            modifier = Modifier.size(120.dp).clip(RoundedCornerShape(18.dp))
                                                                .border(1.dp, Color(0xFFFFD700).copy(alpha = 0.3f), RoundedCornerShape(18.dp))
                                                        )
                                                        Spacer(Modifier.height(8.dp))
                                                        Text(song.title, fontSize = 13.sp, color = TextPure, fontWeight = FontWeight.Medium, maxLines = 1)
                                                        Text(song.artist, fontSize = 11.sp, color = TextSubtle, maxLines = 1)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(34.dp))
                                }

                                // E. DESI HIP-HOP & RAP CYPHER
                                if (dhhHits.isNotEmpty()) {
                                    HomeSongShelf(
                                        title = "Desi Hip-Hop & Rap",
                                        subtitle = "Raw bars & heavy basslines",
                                        songs = dhhHits,
                                        onPlay = { s -> onPlay(s, dhhHits) },
                                        onLongPress = { s -> onSongOptions(s) }
                                    )
                                    Spacer(Modifier.height(32.dp))
                                }

                                // F. PUNJABI HEATWAVE
                                if (punjabiWave.isNotEmpty()) {
                                    HomeSongShelf(
                                        title = "Punjabi Chartbusters",
                                        subtitle = "Non-stop club & drive anthems",
                                        songs = punjabiWave,
                                        onPlay = { s -> onPlay(s, punjabiWave) },
                                        onLongPress = { s -> onSongOptions(s) }
                                    )
                                    Spacer(Modifier.height(32.dp))
                                }

                                // G. JUMP BACK IN (ARTIST CIRCLES)
                                if (jumpBackIn.isNotEmpty()) {
                                    Text(
                                        "Favorite Artists (Hold for Radio)",
                                        fontSize = 20.sp,
                                        color = TextPure,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 24.dp)
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 24.dp),
                                        horizontalArrangement = Arrangement.spacedBy(18.dp)
                                    ) {
                                        items(jumpBackIn.take(8)) { song ->
                                            Column(
                                                Modifier
                                                    .width(105.dp)
                                                    .combinedClickable(
                                                        onClick = { onPlay(song, jumpBackIn) },
                                                        onLongClick = { startArtistRadio(song.artist) }
                                                    ),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                AsyncImage(
                                                    model = song.thumbnailUrl,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.size(105.dp).clip(CircleShape).border(1.5.dp, GlassSurface, CircleShape)
                                                )
                                                Spacer(Modifier.height(10.dp))
                                                Text(song.artist, fontSize = 12.sp, color = TextPure, maxLines = 1, fontWeight = FontWeight.Medium)
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(34.dp))
                                }

                                // H. LATE NIGHT LO-FI SANCTUARY
                                if (lofiSanctuary.isNotEmpty()) {
                                    HomeSongShelf(
                                        title = "Late Night Lo-Fi & Chill",
                                        subtitle = "Slowed, reverb & midnight study vibes",
                                        songs = lofiSanctuary,
                                        onPlay = { s -> onPlay(s, lofiSanctuary) },
                                        onLongPress = { s -> onSongOptions(s) }
                                    )
                                    Spacer(Modifier.height(32.dp))
                                }

                                // I. INDIE & ACOUSTIC GOLD
                                if (indieAcoustic.isNotEmpty()) {
                                    HomeSongShelf(
                                        title = "Indie & Acoustic Sanctuary",
                                        subtitle = "Warm guitars & heartfelt storytelling",
                                        songs = indieAcoustic,
                                        onPlay = { s -> onPlay(s, indieAcoustic) },
                                        onLongPress = { s -> onSongOptions(s) }
                                    )
                                    Spacer(Modifier.height(32.dp))
                                }

                                // J. GLOBAL VIRAL HITS
                                if (globalCharts.isNotEmpty()) {
                                    HomeSongShelf(
                                        title = "Global Viral Hits",
                                        subtitle = "Worldwide chart-toppers",
                                        songs = globalCharts,
                                        onPlay = { s -> onPlay(s, globalCharts) },
                                        onLongPress = { s -> onSongOptions(s) }
                                    )
                                    Spacer(Modifier.height(32.dp))
                                }

                                // K. RETRO GOLDEN CLASSICS
                                if (retroClassics.isNotEmpty()) {
                                    HomeSongShelf(
                                        title = "Retro Golden Classics",
                                        subtitle = "Timeless 70s, 80s & 90s melodies",
                                        songs = retroClassics,
                                        onPlay = { s -> onPlay(s, retroClassics) },
                                        onLongPress = { s -> onSongOptions(s) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun HomeSongShelf(
        title: String,
        subtitle: String,
        songs: List<Song>,
        onPlay: (Song) -> Unit,
        onLongPress: (Song) -> Unit
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = title,
                fontSize = 20.sp,
                color = TextPure,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(14.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(songs.take(10), key = { it.id }) { song ->
                    Column(
                        Modifier
                            .width(135.dp)
                            .combinedClickable(
                                onClick = { onPlay(song) },
                                onLongClick = { onLongPress(song) }
                            )
                    ) {
                        Box(Modifier.size(135.dp).clip(RoundedCornerShape(18.dp))) {
                            AsyncImage(
                                model = song.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            song.title,
                            fontSize = 14.sp,
                            color = TextPure,
                            maxLines = 1,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = song.artist,
                            fontSize = 12.sp,
                            color = TextSubtle,
                            maxLines = 1,
                            modifier = Modifier.combinedClickable(
                                onClick = { onPlay(song) },
                                onLongClick = { startArtistRadio(song.artist) }
                            )
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun TrendingHits3DCarousel(
        songs: List<Song>,
        onPlay: (Song) -> Unit,
        onLongPress: (Song) -> Unit
    ) {
        if (songs.isEmpty()) return

        val virtualPageCount = 10000
        val initialCenterPage = (virtualPageCount / 2) - ((virtualPageCount / 2) % songs.size)
        val pagerState = rememberPagerState(
            initialPage = initialCenterPage,
            pageCount = { virtualPageCount }
        )

        LaunchedEffect(pagerState, songs.size) {
            if (songs.size <= 1) return@LaunchedEffect
            while (true) {
                delay(3200)
                while (pagerState.isScrollInProgress) {
                    delay(1000)
                }
                try {
                    pagerState.animateScrollToPage(
                        page = pagerState.currentPage + 1,
                        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing)
                    )
                } catch (_: Exception) {}
            }
        }

        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 115.dp),
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxWidth().height(205.dp)
        ) { page ->
            val actualIndex = (page % songs.size + songs.size) % songs.size
            val song = songs[actualIndex]

            val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
            val scale = lerp(0.82f, 1.0f, (1f - pageOffset.coerceIn(0f, 1f)))
            val alpha = lerp(0.40f, 1.0f, (1f - pageOffset.coerceIn(0f, 1f)))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
                    .combinedClickable(
                        onClick = { onPlay(song) },
                        onLongClick = { onLongPress(song) }
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(145.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(DeepSurface)
                ) {
                    AsyncImage(
                        model = song.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = song.title,
                    fontSize = 14.sp,
                    color = TextPure,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(140.dp)
                )
                Text(
                    text = song.artist,
                    fontSize = 11.sp,
                    color = TextMuted,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(140.dp)
                )
            }
        }
    }

    // ==========================================
    // SCENE 02: EXPLORE (With Queue Options & Download Rings)
    // ==========================================
    @Composable
    private fun ExploreScene(
        onPlaySongTap: (Song) -> Unit,
        onSongOptions: (Song) -> Unit,
        repo: JioSaavnRepository
    ) {
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        var query by remember { mutableStateOf("") }
        var results by remember { mutableStateOf<List<Song>>(emptyList()) }
        var isSearching by remember { mutableStateOf(false) }
        var trendingSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
        var isTrendingLoading by remember { mutableStateOf(true) }
        var isFocused by remember { mutableStateOf(false) }

        val recentSearches = remember {
            val saved = prefs.getString("recent_search_queries", "") ?: ""
            mutableStateListOf<String>().apply {
                addAll(saved.split("||").filter { it.isNotBlank() }.take(8))
            }
        }

        fun saveSearchQuery(q: String) {
            val clean = q.trim()
            if (clean.length < 2) return
            recentSearches.remove(clean)
            recentSearches.add(0, clean)
            if (recentSearches.size > 8) recentSearches.removeAt(recentSearches.lastIndex)
            prefs.edit().putString("recent_search_queries", recentSearches.joinToString("||")).apply()
        }

        // Intercept Back gesture when a search query is active so it clears search first!
        BackHandler(enabled = query.isNotEmpty() || isFocused) {
            if (query.isNotEmpty()) {
                query = ""
                results = emptyList()
                isSearching = false
            }
            focusManager.clearFocus()
        }

        // Debounced search execution: waits 320ms after last keystroke before hitting API
        LaunchedEffect(query) {
            val trimmed = query.trim()
            if (trimmed.length < 2) {
                results = emptyList()
                isSearching = false
                return@LaunchedEffect
            }
            isSearching = true
            delay(320)
            val fetched = repo.searchSongs(trimmed)
            results = fetched
            isSearching = false
        }

        val shimmerBrush = rememberShimmerBrush()

        LaunchedEffect(Unit) {
            isTrendingLoading = true
            trendingSongs = repo.searchSongs("Top Global & Hindi Hits")
            isTrendingLoading = false
        }

        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(520.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0x3800E5FF),
                                Color(0x1E7C4DFF),
                                Color.Transparent
                            ),
                            center = Offset(300f, 0f),
                            radius = 950f
                        )
                    )
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp)
            ) {
                // Search Bar with Loading Spinner & Keyboard Search Action
                val shadowRadius by animateDpAsState(if (isFocused) 20.dp else 0.dp, label = "search_shadow")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .shadow(shadowRadius, CircleShape, ambientColor = Color(0xFF00E5FF), spotColor = Color(0xFF00E5FF))
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0x22FFFFFF))
                        .border(
                            width = 1.dp,
                            color = if (isFocused) Color(0xFF00E5FF) else Color(0x22FFFFFF),
                            shape = RoundedCornerShape(26.dp)
                        )
                        .padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (query.isNotEmpty()) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier
                                    .size(22.dp)
                                    .clickable {
                                        query = ""
                                        results = emptyList()
                                        focusManager.clearFocus()
                                    }
                            )
                        } else {
                            Icon(
                                Icons.Rounded.Search,
                                contentDescription = null,
                                tint = if (isFocused) Color(0xFF00E5FF) else TextMuted
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            textStyle = TextStyle(color = TextPure, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                            cursorBrush = SolidColor(Color(0xFF00E5FF)),
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Search
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onSearch = {
                                    saveSearchQuery(query)
                                    focusManager.clearFocus()
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { isFocused = it.isFocused },
                            decorationBox = { inner ->
                                if (query.isEmpty()) {
                                    Text("Search songs, artists, or start a radio...", color = TextMuted, fontSize = 14.sp)
                                } else {
                                    inner()
                                }
                            }
                        )
                        if (isSearching) {
                            CircularProgressIndicator(
                                color = Color(0xFF00E5FF),
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        if (query.isNotEmpty()) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Clear",
                                tint = TextMuted,
                                modifier = Modifier.clickable {
                                    query = ""
                                    results = emptyList()
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Quick Genre Filter Pills
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val quickTags = listOf("Desi Hip-Hop", "Punjabi Hits", "Arijit Singh", "Lo-Fi Slowed", "90s Classics", "The Weeknd", "Gym Phonk")
                    items(quickTags) { tag ->
                        val active = query.equals(tag, ignoreCase = true)
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (active) Color(0xFF00E5FF) else Color(0x18FFFFFF))
                                .border(1.dp, if (active) Color(0xFF00E5FF) else Color(0x1FFFFFFF), RoundedCornerShape(16.dp))
                                .clickable {
                                    query = if (active) "" else tag
                                    if (!active) saveSearchQuery(tag)
                                    focusManager.clearFocus()
                                }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = tag,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (active) VoidBlack else TextPure
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                if (query.isEmpty()) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 180.dp)
                    ) {
                        // Recent Searches Section
                        if (recentSearches.isNotEmpty()) {
                            item {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Recent Searches", fontSize = 15.sp, color = TextPure, fontWeight = FontWeight.Bold)
                                    Text(
                                        "Clear All",
                                        fontSize = 12.sp,
                                        color = Color(0xFF00E5FF),
                                        modifier = Modifier.clickable {
                                            recentSearches.clear()
                                            prefs.edit().remove("recent_search_queries").apply()
                                        }
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(recentSearches) { recent ->
                                        Row(
                                            Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(DeepSurface)
                                                .border(1.dp, GlassSurface, RoundedCornerShape(12.dp))
                                                .clickable { query = recent }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Rounded.History, contentDescription = null, tint = TextSubtle, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(recent, fontSize = 12.sp, color = TextPure)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(24.dp))
                            }
                        }

                        item {
                            Text("Explore", fontSize = 26.sp, color = TextMuted, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)
                            Spacer(Modifier.height(22.dp))
                        }

                        item {
                            Text("Curated Atmospheres", fontSize = 18.sp, color = TextPure, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(14.dp))
                            Row(
                                Modifier.fillMaxWidth().height(175.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                ExploreAtmosphereCard(
                                    modifier = Modifier.weight(1.05f).fillMaxHeight(),
                                    tag = "",
                                    title = "Pop Odyssey",
                                    subtitle = "",
                                    gradient = listOf(Color(0xFFFF2A6D), Color(0xFF8E24AA)),
                                    vectorType = 1,
                                    onClick = { query = "Pop Hits" }
                                )
                                Column(
                                    Modifier.weight(1f).fillMaxHeight(),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    ExploreAtmosphereCard(
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        tag = "",
                                        title = "Desi Hip-Hop",
                                        subtitle = "",
                                        gradient = listOf(Color(0xFF00E5FF), Color(0xFF0052D4)),
                                        vectorType = 2,
                                        onClick = { query = "Desi Hip Hop" }
                                    )
                                    ExploreAtmosphereCard(
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        tag = "",
                                        title = "Acoustic Gold",
                                        subtitle = "",
                                        gradient = listOf(Color(0xFFFFB300), Color(0xFFFF3D00)),
                                        vectorType = 3,
                                        onClick = { query = "Acoustic Hits" }
                                    )
                                }
                            }
                            Spacer(Modifier.height(32.dp))
                        }

                        item {
                            Text("Trending Frequencies", fontSize = 18.sp, color = TextPure, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text("Tap any track to launch an endless radio", fontSize = 12.sp, color = TextMuted)
                            Spacer(Modifier.height(14.dp))
                        }

                        if (isTrendingLoading) {
                            items(6) {
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(DeepSurface).padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(shimmerBrush))
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Box(Modifier.fillMaxWidth(0.6f).height(15.dp).clip(RoundedCornerShape(4.dp)).background(shimmerBrush))
                                        Spacer(Modifier.height(6.dp))
                                        Box(Modifier.fillMaxWidth(0.35f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(shimmerBrush))
                                    }
                                }
                            }
                        } else {
                            itemsIndexed(trendingSongs.take(10), key = { _, s -> s.id }) { index, song ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(DeepSurface)
                                        .combinedClickable(
                                            onClick = { onPlaySongTap(song) },
                                            onLongClick = { onSongOptions(song) }
                                        )
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "#%02d".format(index + 1),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Black,
                                        color = if (index < 3) Color(0xFF00E5FF) else TextSubtle,
                                        modifier = Modifier.width(36.dp)
                                    )
                                    AsyncImage(
                                        model = song.thumbnailUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(song.title, fontSize = 15.sp, color = TextPure, fontWeight = FontWeight.Bold, maxLines = 1)
                                        Text(
                                            text = song.artist,
                                            fontSize = 12.sp,
                                            color = TextMuted,
                                            maxLines = 1,
                                            modifier = Modifier.combinedClickable(
                                                onClick = { onPlaySongTap(song) },
                                                onLongClick = { startArtistRadio(song.artist) }
                                            )
                                        )
                                    }
                                    DownloadStateIcon(
                                        progress = downloadProgressMap[song.id]
                                            ?: (if (downloadedSongsList.any { it.id == song.id }) -1f else 0f),
                                        onClick = { downloadSongInternally(song) }
                                    )
                                    IconButton(onClick = { onSongOptions(song) }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Rounded.MoreVert, contentDescription = "Options", tint = TextMuted, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Search Results List
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 180.dp)
                    ) {
                        if (!isSearching && results.isEmpty() && query.trim().length >= 2) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                                    Text("No matching frequencies found for \"$query\"", color = TextMuted, fontSize = 14.sp)
                                }
                            }
                        }

                        itemsIndexed(results, key = { idx, s -> "${s.id}_$idx" }) { index, song ->
                            val isBestMatch = index == 0
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(
                                        if (isBestMatch) Brush.horizontalGradient(listOf(Color(0xFF003D4D), Color(0xFF0C1929)))
                                        else SolidColor(DeepSurface)
                                    )
                                    .border(
                                        1.dp,
                                        if (isBestMatch) Color(0xFF00E5FF).copy(alpha = 0.7f) else Color.Transparent,
                                        RoundedCornerShape(18.dp)
                                    )
                                    .combinedClickable(
                                        onClick = {
                                            saveSearchQuery(query)
                                            focusManager.clearFocus()
                                            // 1-Tap immediately starts fresh radio for this song
                                            onPlaySongTap(song)
                                        },
                                        onLongClick = { onSongOptions(song) }
                                    )
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = song.thumbnailUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp))
                                )
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    if (isBestMatch) {
                                        Text(
                                            "TOP RESULT • TAP FOR RADIO",
                                            fontSize = 9.sp,
                                            letterSpacing = 1.sp,
                                            color = Color(0xFF00E5FF),
                                            fontWeight = FontWeight.Black
                                        )
                                        Spacer(Modifier.height(2.dp))
                                    }
                                    Text(
                                        song.title,
                                        fontSize = 15.sp,
                                        color = if (isBestMatch) TextPure else TextPure.copy(alpha = 0.92f),
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = song.artist,
                                        fontSize = 12.sp,
                                        color = TextMuted,
                                        maxLines = 1,
                                        modifier = Modifier.combinedClickable(
                                            onClick = {
                                                saveSearchQuery(query)
                                                focusManager.clearFocus()
                                                onPlaySongTap(song)
                                            },
                                            onLongClick = { startArtistRadio(song.artist) }
                                        )
                                    )
                                }
                                DownloadStateIcon(
                                    progress = downloadProgressMap[song.id]
                                        ?: (if (downloadedSongsList.any { it.id == song.id }) -1f else 0f),
                                    onClick = { downloadSongInternally(song) }
                                )
                                // 3-Dot button opens Play Next / Play Immediately (Keep Queue) / Add to Queue
                                IconButton(
                                    onClick = { onSongOptions(song) },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.MoreVert,
                                        contentDescription = "Queue Options",
                                        tint = if (isBestMatch) Color(0xFF00E5FF) else TextMuted,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ExploreAtmosphereCard(
        modifier: Modifier,
        tag: String,
        title: String,
        subtitle: String,
        gradient: List<Color>,
        vectorType: Int,
        onClick: () -> Unit
    ) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(gradient))
                .clickable { onClick() }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                when (vectorType) {
                    1 -> {
                        val path = Path().apply {
                            moveTo(size.width * 0.2f, 0f)
                            cubicTo(
                                size.width * 0.7f, size.height * 0.25f,
                                size.width * 0.1f, size.height * 0.65f,
                                size.width, size.height * 0.85f
                            )
                            lineTo(size.width, 0f)
                            close()
                        }
                        drawPath(path, Color.White.copy(alpha = 0.16f))
                        drawCircle(
                            color = Color.White.copy(alpha = 0.10f),
                            radius = size.width * 0.55f,
                            center = Offset(size.width * 0.9f, size.height * 0.25f)
                        )
                    }

                    2 -> {
                        for (i in 1..4) {
                            drawCircle(
                                color = Color.White.copy(alpha = 0.05f * i),
                                radius = (size.height * 0.40f) * i,
                                center = Offset(size.width * 0.88f, size.height * 0.5f),
                                style = Stroke(width = 3.5f)
                            )
                        }
                    }

                    3 -> {
                        val barWidth = 7f
                        val gap = 9f
                        var startX = size.width * 0.55f
                        listOf(0.35f, 0.65f, 0.25f, 0.85f, 0.5f, 0.75f, 0.3f).forEach { ratio ->
                            val barH = size.height * 0.65f * ratio
                            drawLine(
                                color = Color.White.copy(alpha = 0.20f),
                                start = Offset(startX, size.height - 18f),
                                end = Offset(startX, size.height - 18f - barH),
                                strokeWidth = barWidth,
                                cap = StrokeCap.Round
                            )
                            startX += barWidth + gap
                        }
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x60000000))))
            )

            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.25f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = tag,
                        fontSize = 9.sp,
                        color = Color.White.copy(alpha = 0.9f),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                }

                Column {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        color = TextPure,
                        fontWeight = FontWeight.Black,
                        lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.75f),
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }

    // ==========================================
    // SCENE 03: MY WORLD
    // ==========================================
    @Composable
    private fun MyWorldScene(
        likedCount: Int,
        recentCount: Int,
        downloadCount: Int,
        playlists: List<UserPlaylist>,
        onOpenLiked: () -> Unit,
        onOpenRecent: () -> Unit,
        onOpenDownloads: () -> Unit,
        onCreatePlaylist: () -> Unit,
        onOpenPlaylist: (NavRoute.PlaylistDetail) -> Unit
    ) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            contentPadding = PaddingValues(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp,
                bottom = 180.dp
            )
        ) {
            item {
                Text("My World", fontSize = 38.sp, color = TextPure, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Your personalized sonic sanctuary", fontSize = 14.sp, color = TextMuted)
                Spacer(Modifier.height(26.dp))
            }

            item {
                VaultCategoryCard(
                    title = "Liked Songs",
                    subtitle = "$likedCount Frequencies",
                    icon = Icons.Rounded.Favorite,
                    solidColor = Color(0xFFE91E63),
                    onClick = onOpenLiked
                )
                Spacer(Modifier.height(14.dp))
                VaultCategoryCard(
                    title = "Recently Played",
                    subtitle = "$recentCount Past Echoes",
                    icon = Icons.Rounded.History,
                    solidColor = Color(0xFF0288D1),
                    onClick = onOpenRecent
                )
                Spacer(Modifier.height(14.dp))
                VaultCategoryCard(
                    title = "Downloads",
                    subtitle = "$downloadCount Offline Tracks",
                    icon = Icons.Rounded.DownloadDone,
                    solidColor = Color(0xFF00897B),
                    onClick = onOpenDownloads
                )
                Spacer(Modifier.height(34.dp))
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Collections", fontSize = 20.sp, color = TextPure, fontWeight = FontWeight.Bold)
                    IconButton(
                        onClick = onCreatePlaylist,
                        modifier = Modifier.size(42.dp).clip(CircleShape).background(Color(0xFFFFD54F).copy(alpha = 0.2f))
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Create", tint = Color(0xFFFFD54F), modifier = Modifier.size(26.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            val chunked = playlists.chunked(2)
            items(chunked) { rowItems ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    rowItems.forEach { pl ->
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(22.dp))
                                .clickable {
                                    onOpenPlaylist(NavRoute.PlaylistDetail(pl.name, pl.coverUrl, pl.songs))
                                }
                        ) {
                            AsyncImage(
                                model = pl.coverUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000)))
                                )
                            )
                            Text(
                                pl.name,
                                fontSize = 15.sp,
                                color = TextPure,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.BottomStart).padding(14.dp)
                            )
                        }
                    }
                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    @Composable
    private fun VaultCategoryCard(
        title: String,
        subtitle: String,
        icon: ImageVector,
        solidColor: Color,
        onClick: () -> Unit
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(solidColor)
                .clickable { onClick() }
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Medium)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.8f))
        }
    }

    @Composable
    private fun CreatePlaylistScene(onBack: () -> Unit, onCreate: (String, String) -> Unit) {
        var name by remember { mutableStateOf("") }
        var selectedCoverUrl by remember { mutableStateOf(PRESET_COVERS[0]) }
        val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) selectedCoverUrl = uri.toString()
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp
            )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = TextPure)
                }
                Text("Sculpt New Playlist", fontSize = 20.sp, color = TextPure, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(28.dp))

            Box(
                Modifier
                    .size(190.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(GlassSurface)
                    .border(1.dp, GlassSurface, RoundedCornerShape(28.dp))
                    .align(Alignment.CenterHorizontally)
                    .clickable { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = selectedCoverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(Modifier.height(18.dp))
            Text("Choose from 7 Artworks or Upload Custom", fontSize = 12.sp, color = TextMuted, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(12.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                item {
                    Box(
                        Modifier
                            .size(62.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(GlassSurface)
                            .border(1.dp, Color(0xFFFFD54F), RoundedCornerShape(14.dp))
                            .clickable { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = "Upload", tint = Color(0xFFFFD54F))
                    }
                }
                items(PRESET_COVERS) { preset ->
                    val isChosen = selectedCoverUrl == preset
                    Box(
                        Modifier
                            .size(62.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(2.dp, if (isChosen) Color(0xFFFFD54F) else Color.Transparent, RoundedCornerShape(14.dp))
                            .clickable { selectedCoverUrl = preset }
                    ) {
                        AsyncImage(model = preset, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }

            Spacer(Modifier.height(26.dp))
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                textStyle = TextStyle(color = TextPure, fontSize = 20.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold),
                singleLine = true,
                cursorBrush = SolidColor(Color(0xFFFFD54F)),
                modifier = Modifier.fillMaxWidth().background(DeepSurface, RoundedCornerShape(16.dp)).padding(18.dp),
                decorationBox = { inner ->
                    if (name.isEmpty()) Text("Collection Title", color = TextMuted, fontSize = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) else inner()
                }
            )
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = { if (name.isNotBlank()) onCreate(name, selectedCoverUrl) },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD54F)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("FORGE COLLECTION", color = VoidBlack, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }
        }
    }

    // ==========================================
    // PLAYLIST DETAIL SCENE
    // ==========================================
    @Composable
    private fun PlaylistDetailScene(
        playlist: UserPlaylist,
        onPlay: (Song, List<Song>) -> Unit,
        onDelete: () -> Unit,
        onSave: () -> Unit,
        onBack: () -> Unit,
        repo: JioSaavnRepository
    ) {
        val context = LocalContext.current
        var dominantColor by remember { mutableStateOf(DeepSurface) }
        val smoothDominant by animateColorAsState(dominantColor, tween(600), label = "pl_color")
        var showMenu by remember { mutableStateOf(false) }

        var showEditSheet by remember { mutableStateOf(false) }
        var editName by remember { mutableStateOf(playlist.name) }
        var editCover by remember { mutableStateOf(playlist.coverUrl) }

        var showAddSongsSheet by remember { mutableStateOf(false) }
        var searchSongQuery by remember { mutableStateOf("") }
        var searchSongResults by remember { mutableStateOf<List<Song>>(emptyList()) }

        var showRemoveSongsSheet by remember { mutableStateOf(false) }
        val selectedSongsToRemove = remember { mutableStateListOf<Song>() }
        var showRemoveConfirmation by remember { mutableStateOf(false) }
        var showDeleteConfirmation by remember { mutableStateOf(false) }

        val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) editCover = uri.toString()
        }

        LaunchedEffect(playlist.coverUrl) {
            if (playlist.coverUrl.isNotBlank()) {
                val (color, _) = extractDominantColorAndBitmap(context, playlist.coverUrl)
                dominantColor = color
            }
        }

        Box(Modifier.fillMaxSize().background(smoothDominant)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(360.dp)) {
                    AsyncImage(
                        model = playlist.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.0f to Color.Transparent,
                                        0.35f to Color.Transparent,
                                        0.65f to smoothDominant.copy(alpha = 0.55f),
                                        0.85f to smoothDominant.copy(alpha = 0.88f),
                                        1.0f to smoothDominant
                                    )
                                )
                            )
                    )

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp,
                                start = 16.dp,
                                end = 16.dp
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = TextPure, modifier = Modifier.size(30.dp))
                        }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "Options", tint = TextPure, modifier = Modifier.size(30.dp))
                        }
                    }

                    Column(Modifier.align(Alignment.BottomStart).padding(24.dp)) {
                        Text(playlist.name, fontSize = 36.sp, color = TextPure, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, maxLines = 2)
                        Spacer(Modifier.height(6.dp))
                        Text("${playlist.songs.size} Frequencies", fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f))
                    }
                }

                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp)
                ) {
                    if (playlist.songs.isEmpty()) {
                        item { Text("No frequencies added yet.", color = TextMuted) }
                    }
                    items(playlist.songs) { song ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.Black.copy(alpha = 0.28f))
                                .clickable { onPlay(song, playlist.songs) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = song.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.title, fontSize = 15.sp, color = TextPure, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(
                                    text = song.artist,
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.75f),
                                    maxLines = 1,
                                    modifier = Modifier.combinedClickable(
                                        onClick = { onPlay(song, playlist.songs) },
                                        onLongClick = { startArtistRadio(song.artist) }
                                    )
                                )
                            }
                            DownloadStateIcon(
                                progress = downloadProgressMap[song.id]
                                    ?: (if (downloadedSongsList.any { it.id == song.id }) -1f else 0f),
                                onClick = { downloadSongInternally(song) }
                            )
                        }
                    }
                }
            }

            if (showMenu) {
                ModalBottomSheet(onDismissRequest = { showMenu = false }, containerColor = DeepSurface) {
                    Column(Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 32.dp)) {
                        Text(playlist.name, fontSize = 20.sp, color = TextPure, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, maxLines = 1)
                        Spacer(Modifier.height(18.dp))

                        ListItem(
                            headlineContent = { Text("Edit Playlist", color = TextPure) },
                            supportingContent = { Text("Change name and cover image", color = TextMuted) },
                            leadingContent = { Icon(Icons.Rounded.Edit, tint = Color(0xFF00E5FF), contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable {
                                showMenu = false
                                editName = playlist.name
                                editCover = playlist.coverUrl
                                showEditSheet = true
                            }
                        )

                        ListItem(
                            headlineContent = { Text("Add Songs", color = TextPure) },
                            supportingContent = { Text("Search and append tracks", color = TextMuted) },
                            leadingContent = { Icon(Icons.Rounded.Add, tint = Color(0xFFFFD54F), contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable {
                                showMenu = false
                                searchSongQuery = ""
                                searchSongResults = emptyList()
                                showAddSongsSheet = true
                            }
                        )

                        if (playlist.songs.isNotEmpty()) {
                            ListItem(
                                headlineContent = { Text("Remove Songs", color = TextPure) },
                                supportingContent = { Text("Select tracks to remove from collection", color = TextMuted) },
                                leadingContent = { Icon(Icons.Rounded.PlaylistRemove, tint = Color(0xFFFFB74D), contentDescription = null) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable {
                                    showMenu = false
                                    selectedSongsToRemove.clear()
                                    showRemoveSongsSheet = true
                                }
                            )
                        }

                        ListItem(
                            headlineContent = { Text("Delete Whole Playlist", color = MeloAccent, fontWeight = FontWeight.Bold) },
                            supportingContent = { Text("Permanently remove this collection", color = TextMuted) },
                            leadingContent = { Icon(Icons.Rounded.DeleteOutline, tint = MeloAccent, contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable {
                                showMenu = false
                                showDeleteConfirmation = true
                            }
                        )
                    }
                }
            }

            if (showEditSheet) {
                ModalBottomSheet(onDismissRequest = { showEditSheet = false }, containerColor = DeepSurface) {
                    Column(Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 32.dp)) {
                        Text("Edit Playlist", fontSize = 22.sp, color = TextPure, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                        Spacer(Modifier.height(18.dp))

                        Box(
                            Modifier
                                .size(130.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(GlassSurface)
                                .align(Alignment.CenterHorizontally)
                                .clickable { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(model = editCover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.height(12.dp))

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            item {
                                Box(
                                    Modifier
                                        .size(50.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(GlassSurface)
                                        .clickable { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(22.dp))
                                }
                            }
                            items(PRESET_COVERS) { preset ->
                                Box(
                                    Modifier
                                        .size(50.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(2.dp, if (editCover == preset) Color(0xFFFFD54F) else Color.Transparent, RoundedCornerShape(12.dp))
                                        .clickable { editCover = preset }
                                ) {
                                    AsyncImage(model = preset, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                            }
                        }

                        Spacer(Modifier.height(18.dp))
                        BasicTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            textStyle = TextStyle(color = TextPure, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                            singleLine = true,
                            cursorBrush = SolidColor(Color(0xFFFFD54F)),
                            modifier = Modifier.fillMaxWidth().background(VoidBlack, RoundedCornerShape(14.dp)).border(1.dp, GlassSurface, RoundedCornerShape(14.dp)).padding(14.dp)
                        )
                        Spacer(Modifier.height(20.dp))

                        Button(
                            onClick = {
                                if (editName.isNotBlank()) {
                                    playlist.name = editName.trim()
                                    playlist.coverUrl = editCover
                                    onSave()
                                    showEditSheet = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD54F)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("SAVE CHANGES", color = VoidBlack, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }

            if (showAddSongsSheet) {
                ModalBottomSheet(onDismissRequest = { showAddSongsSheet = false }, containerColor = DeepSurface) {
                    Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(20.dp)) {
                        Text("Add Songs to Playlist", fontSize = 22.sp, color = TextPure, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                        Spacer(Modifier.height(16.dp))

                        BasicTextField(
                            value = searchSongQuery,
                            onValueChange = { q ->
                                searchSongQuery = q
                                if (q.length > 2) {
                                    lifecycleScope.launch {
                                        searchSongResults = repo.searchSongs(q)
                                    }
                                }
                            },
                            textStyle = TextStyle(color = TextPure, fontSize = 15.sp),
                            singleLine = true,
                            cursorBrush = SolidColor(Color(0xFF00E5FF)),
                            modifier = Modifier.fillMaxWidth().background(VoidBlack, RoundedCornerShape(14.dp)).border(1.dp, GlassSurface, RoundedCornerShape(14.dp)).padding(14.dp),
                            decorationBox = { inner ->
                                if (searchSongQuery.isEmpty()) Text("Search tracks or artists...", color = TextMuted, fontSize = 14.sp) else inner()
                            }
                        )
                        Spacer(Modifier.height(16.dp))

                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                            items(searchSongResults) { song ->
                                val alreadyAdded = playlist.songs.any { it.id == song.id }
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(VoidBlack).padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = song.thumbnailUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(song.title, color = TextPure, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        Text(song.artist, color = TextMuted, fontSize = 12.sp, maxLines = 1)
                                    }
                                    IconButton(
                                        onClick = {
                                            if (!alreadyAdded) {
                                                playlist.songs.add(song)
                                                if (playlist.coverUrl.isBlank()) playlist.coverUrl = song.thumbnailUrl
                                                onSave()
                                            }
                                        }
                                    ) {
                                        Icon(
                                            if (alreadyAdded) Icons.Rounded.Check else Icons.Rounded.Add,
                                            contentDescription = null,
                                            tint = if (alreadyAdded) Color(0xFF00E676) else Color(0xFFFFD54F)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (showRemoveSongsSheet) {
                ModalBottomSheet(onDismissRequest = { showRemoveSongsSheet = false }, containerColor = DeepSurface) {
                    Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(20.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Select Songs to Remove", fontSize = 20.sp, color = TextPure, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                            TextButton(
                                onClick = { if (selectedSongsToRemove.isNotEmpty()) showRemoveConfirmation = true },
                                enabled = selectedSongsToRemove.isNotEmpty()
                            ) {
                                Text(
                                    "Remove (${selectedSongsToRemove.size})",
                                    color = if (selectedSongsToRemove.isNotEmpty()) MeloAccent else TextSubtle,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))

                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                            items(playlist.songs) { song ->
                                val isSelected = selectedSongsToRemove.any { it.id == song.id }
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (isSelected) Color(0x33FF5252) else VoidBlack)
                                        .border(1.dp, if (isSelected) MeloAccent else Color.Transparent, RoundedCornerShape(14.dp))
                                        .clickable {
                                            if (isSelected) selectedSongsToRemove.removeAll { it.id == song.id }
                                            else selectedSongsToRemove.add(song)
                                        }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            if (checked) selectedSongsToRemove.add(song)
                                            else selectedSongsToRemove.removeAll { it.id == song.id }
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = MeloAccent, uncheckedColor = TextMuted)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    AsyncImage(
                                        model = song.thumbnailUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(song.title, color = TextPure, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        Text(song.artist, color = TextMuted, fontSize = 12.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (showRemoveConfirmation) {
                AlertDialog(
                    onDismissRequest = { showRemoveConfirmation = false },
                    title = { Text("Remove Songs?", color = TextPure, fontWeight = FontWeight.Bold) },
                    text = { Text("Are you sure you want to remove ${selectedSongsToRemove.size} song(s) from '${playlist.name}'?", color = TextMuted) },
                    confirmButton = {
                        TextButton(onClick = {
                            val removeIds = selectedSongsToRemove.map { it.id }.toSet()
                            playlist.songs.removeAll { it.id in removeIds }
                            onSave()
                            showRemoveConfirmation = false
                            showRemoveSongsSheet = false
                        }) { Text("Remove", color = MeloAccent, fontWeight = FontWeight.Bold) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRemoveConfirmation = false }) { Text("Cancel", color = TextPure) }
                    },
                    containerColor = DeepSurface
                )
            }

            if (showDeleteConfirmation) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirmation = false },
                    title = { Text("Delete Playlist?", color = TextPure, fontWeight = FontWeight.Bold) },
                    text = { Text("Are you sure you want to delete '${playlist.name}'? All collected frequencies will be erased.", color = TextMuted) },
                    confirmButton = {
                        TextButton(onClick = {
                            showDeleteConfirmation = false
                            onDelete()
                        }) { Text("Delete", color = MeloAccent, fontWeight = FontWeight.Bold) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancel", color = TextPure) }
                    },
                    containerColor = DeepSurface
                )
            }
        }
    }

    @Composable
    private fun SongCollectionDetailScene(
        title: String,
        accentColor: Color,
        bgColorTop: Color,
        songs: List<Song>,
        onPlay: (Song, List<Song>) -> Unit,
        onBack: () -> Unit
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(bgColorTop, bgColorTop.copy(alpha = 0.55f), VoidBlack)))
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = TextPure)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(title, fontSize = 24.sp, color = TextPure, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "${songs.size} tracks available",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 52.dp)
                )
                Spacer(Modifier.height(24.dp))

                if (songs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No frequencies collected yet.", color = TextMuted, fontSize = 15.sp)
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 120.dp)
                    ) {
                        items(songs) { song ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(bgColorTop.copy(alpha = 0.35f))
                                    .border(1.dp, accentColor.copy(alpha = 0.30f), RoundedCornerShape(18.dp))
                                    .clickable { onPlay(song, songs) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = song.thumbnailUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(50.dp).clip(RoundedCornerShape(14.dp))
                                )
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, fontSize = 15.sp, color = TextPure, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text(
                                        text = song.artist,
                                        fontSize = 12.sp,
                                        color = Color.White.copy(alpha = 0.75f),
                                        maxLines = 1,
                                        modifier = Modifier.combinedClickable(
                                            onClick = { onPlay(song, songs) },
                                            onLongClick = { startArtistRadio(song.artist) }
                                        )
                                    )
                                }
                                DownloadStateIcon(
                                    progress = downloadProgressMap[song.id]
                                        ?: (if (downloadedSongsList.any { it.id == song.id }) -1f else 0f),
                                    onClick = { downloadSongInternally(song) }
                                )
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = accentColor)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DownloadsDetailScene(
        songs: List<Song>,
        onPlay: (Song, List<Song>) -> Unit,
        onDelete: (Song) -> Unit,
        onBack: () -> Unit
    ) {
        var songToDelete by remember { mutableStateOf<Song?>(null) }
        val emeraldTop = Color(0xFF004D40)
        val emeraldAccent = Color(0xFF00E676)

        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(emeraldTop, emeraldTop.copy(alpha = 0.55f), VoidBlack)))
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = TextPure)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Offline Downloads", fontSize = 24.sp, color = TextPure, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "${songs.size} offline tracks stored in sanctuary",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 52.dp)
                )
                Spacer(Modifier.height(24.dp))

                if (songs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No offline downloads yet.", color = TextMuted, fontSize = 15.sp)
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 120.dp)
                    ) {
                        items(songs) { song ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(emeraldTop.copy(alpha = 0.35f))
                                    .border(1.dp, emeraldAccent.copy(alpha = 0.30f), RoundedCornerShape(18.dp))
                                    .clickable { onPlay(song, songs) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = song.thumbnailUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(50.dp).clip(RoundedCornerShape(14.dp))
                                )
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, fontSize = 15.sp, color = TextPure, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text(song.artist, fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
                                }
                                IconButton(onClick = { songToDelete = song }) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete Download", tint = MeloAccent)
                                }
                            }
                        }
                    }
                }
            }

            if (songToDelete != null) {
                AlertDialog(
                    onDismissRequest = { songToDelete = null },
                    title = { Text("Delete Download?", color = TextPure, fontWeight = FontWeight.Bold) },
                    text = { Text("Are you sure you want to remove '${songToDelete!!.title}' from offline storage?", color = TextMuted) },
                    confirmButton = {
                        TextButton(onClick = { onDelete(songToDelete!!); songToDelete = null }) {
                            Text("Delete", color = MeloAccent, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { songToDelete = null }) { Text("Cancel", color = TextPure) }
                    },
                    containerColor = DeepSurface
                )
            }
        }
    }

    // ==========================================
    // IMMERSIVE FULL-SCREEN PLAYER
    // ==========================================
    @Composable
    private fun ImmersivePlayerScene(
        song: Song,
        artworkColor: Color,
        secondaryColor: Color,
        isPlaying: Boolean,
        isBuffering: Boolean,
        currentPositionMs: Float,
        isLiked: Boolean,
        queue: List<Song>,
        currentQueueIndex: Int,
        playlists: List<UserPlaylist>,
        playMode: Int,
        downloadProgress: Float,
        onDownloadClick: () -> Unit,
        onLikeToggle: () -> Unit,
        onAddToPlaylist: (UserPlaylist) -> Unit,
        onPlayQueueSong: (Song) -> Unit,
        onRemoveFromQueue: (Song) -> Unit,
        onCollapse: () -> Unit,
        onTogglePlay: () -> Unit,
        onNext: () -> Unit,
        onPrev: () -> Unit,
        onTogglePlayMode: () -> Unit,
        onSeek: (Float) -> Unit,
        onUserSeekingChange: (Boolean) -> Unit,
        onEnterCinematic: () -> Unit,
        repository: JioSaavnRepository
    ) {
        var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
        var activeTab by remember { mutableStateOf("Player") }
        var showMenu by remember { mutableStateOf(false) }
        var showQueue by remember { mutableStateOf(false) }
        var showPlaylistPicker by remember { mutableStateOf(false) }
        var showDetailsDialog by remember { mutableStateOf(false) }

        LaunchedEffect(song.id) {
            lyrics = repository.getSyncedLyrics(song)
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(VoidBlack)
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            val infinite = rememberInfiniteTransition(label = "blob_infinite")
            val offsetX1 by infinite.animateFloat(
                initialValue = -70f,
                targetValue = 70f,
                animationSpec = infiniteRepeatable(animation = tween(7500, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
                label = "offsetX1"
            )
            val offsetY1 by infinite.animateFloat(
                initialValue = -75f,
                targetValue = 75f,
                animationSpec = infiniteRepeatable(animation = tween(6500, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
                label = "offsetY1"
            )

            Box(
                Modifier
                    .fillMaxSize()
                    .blur(60.dp)
                    .alpha(0.85f)
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(
                        color = artworkColor,
                        radius = size.width * 0.52f,
                        center = Offset(size.width / 2 + offsetX1, size.height * 0.35f + offsetY1)
                    )
                    drawCircle(
                        color = secondaryColor,
                        radius = size.width * 0.48f,
                        center = Offset(size.width / 2 - offsetX1, size.height * 0.65f - offsetY1)
                    )
                }
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            VoidBlack.copy(alpha = 0.35f),
                            VoidBlack.copy(alpha = 0.85f)
                        )
                    )
                )
            )

            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onCollapse) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = TextPure, modifier = Modifier.size(32.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DownloadStateIcon(
                            progress = downloadProgress,
                            onClick = onDownloadClick
                        )
                        Spacer(Modifier.width(6.dp))
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Rounded.MoreHoriz, contentDescription = null, tint = TextPure, modifier = Modifier.size(28.dp))
                        }
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AnimatedContent(
                        targetState = activeTab,
                        transitionSpec = {
                            (fadeIn(tween(350)) + scaleIn(initialScale = 0.96f)) togetherWith (fadeOut(tween(350)) + scaleOut(targetScale = 0.96f))
                        },
                        label = "player_content"
                    ) { tab ->
                        if (tab == "Player") {
                            Column(
                                Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                val artScale by animateFloatAsState(
                                    targetValue = if (isPlaying) 1f else 0.88f,
                                    animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
                                    label = "art_scale"
                                )
                                AsyncImage(
                                    model = song.thumbnailUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(290.dp)
                                        .scale(artScale)
                                        .clip(RoundedCornerShape(32.dp))
                                        .shadow(36.dp, RoundedCornerShape(32.dp), ambientColor = artworkColor, spotColor = artworkColor)
                                )
                            }
                        } else {
                            val listState = rememberLazyListState()
                            val isSynced = remember(lyrics) { lyrics.any { it.time >= 0f } }
                            val activeLyricIndex = remember(lyrics, currentPositionMs, isSynced) {
                                if (!isSynced) -1
                                else lyrics.indexOfLast { (it.time * 1000f) <= (currentPositionMs + 120f) }
                            }

                            LaunchedEffect(activeLyricIndex) {
                                if (isSynced && activeLyricIndex in lyrics.indices) {
                                    listState.animateScrollToItem(
                                        index = activeLyricIndex,
                                        scrollOffset = -240
                                    )
                                }
                            }

                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
                                contentPadding = PaddingValues(top = 40.dp, bottom = 120.dp),
                                verticalArrangement = Arrangement.spacedBy(24.dp)
                            ) {
                                if (lyrics.isEmpty()) {
                                    item {
                                        Text(
                                            text = "No lyrics found for this track.",
                                            color = TextMuted,
                                            fontFamily = FontFamily.SansSerif,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                                itemsIndexed(lyrics) { index, line ->
                                    val isActive = if (isSynced) index == activeLyricIndex else true
                                    val scale by animateFloatAsState(
                                        targetValue = if (!isSynced) 1f else if (isActive) 1.06f else 0.96f,
                                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                        label = "lyric_scale"
                                    )
                                    val alpha by animateFloatAsState(
                                        targetValue = if (!isSynced) 0.88f else if (isActive) 1f else 0.28f,
                                        animationSpec = tween(180),
                                        label = "lyric_alpha"
                                    )
                                    Text(
                                        text = line.text,
                                        fontFamily = FontFamily.SansSerif,
                                        fontWeight = FontWeight.Black,
                                        fontSize = if (isSynced) 28.sp else 22.sp,
                                        color = if (isActive) TextPure else TextMuted,
                                        lineHeight = if (isSynced) 36.sp else 32.sp,
                                        softWrap = true,
                                        textAlign = TextAlign.Start,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .graphicsLayer {
                                                transformOrigin = TransformOrigin(0f, 0.5f)
                                                scaleX = scale
                                                scaleY = scale
                                                this.alpha = alpha
                                            }
                                            .clickable(enabled = isSynced && line.time >= 0f) {
                                                onSeek(line.time * 1000f)
                                            }
                                    )
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = activeTab == "Player", enter = fadeIn(), exit = fadeOut()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 18.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    song.title,
                                    fontSize = 24.sp,
                                    color = TextPure,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    modifier = Modifier.basicMarquee()
                                )
                                Spacer(Modifier.height(4.dp))
                                // FIX #7: Long-press artist in player to launch Artist Radio
                                Text(
                                    text = song.artist,
                                    fontSize = 15.sp,
                                    color = TextMuted,
                                    maxLines = 1,
                                    modifier = Modifier.combinedClickable(
                                        onClick = {},
                                        onLongClick = { startArtistRadio(song.artist) }
                                    )
                                )
                            }
                            IconButton(onClick = onLikeToggle) {
                                Icon(
                                    if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = null,
                                    tint = if (isLiked) MeloAccent else TextPure,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))

                        val songDurationMs = parseDurationMs(song.durationText)
                        val progress = (currentPositionMs / songDurationMs).coerceIn(0f, 1f)
                        InteractiveWaveScrubber(
                            progress = progress,
                            accentColor = artworkColor,
                            currentPositionMs = currentPositionMs,
                            totalDurationMs = songDurationMs,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            onUserSeekingChange = onUserSeekingChange,
                            onSeekProgress = { seekPercent ->
                                val safeTarget = (seekPercent * songDurationMs).coerceAtMost(songDurationMs - 1000f)
                                onSeek(safeTarget)
                            }
                        )
                        Spacer(Modifier.height(20.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onTogglePlayMode) {
                                Icon(
                                    imageVector = when (playMode) {
                                        MODE_SHUFFLE -> Icons.Rounded.Shuffle
                                        MODE_REPEAT_ONE -> Icons.Rounded.RepeatOne
                                        else -> Icons.Rounded.Repeat
                                    },
                                    contentDescription = null,
                                    tint = MeloAccent,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            IconButton(onClick = onPrev) {
                                Icon(Icons.Rounded.SkipPrevious, contentDescription = null, tint = TextPure, modifier = Modifier.size(42.dp))
                            }
                            val interactionSource = remember { MutableInteractionSource() }
                            val isPressed by interactionSource.collectIsPressedAsState()
                            val scale by animateFloatAsState(
                                if (isPressed) 0.92f else 1f,
                                animationSpec = spring(dampingRatio = 0.6f),
                                label = "play_scale"
                            )
                            Box(
                                Modifier
                                    .size(76.dp)
                                    .scale(scale)
                                    .clip(CircleShape)
                                    .background(TextPure)
                                    .clickable(interactionSource = interactionSource, indication = null) { onTogglePlay() },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isBuffering) {
                                    CircularProgressIndicator(color = VoidBlack, modifier = Modifier.size(24.dp))
                                } else {
                                    Icon(
                                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = VoidBlack,
                                        modifier = Modifier.size(44.dp)
                                    )
                                }
                            }
                            IconButton(onClick = onNext) {
                                Icon(Icons.Rounded.SkipNext, contentDescription = null, tint = TextPure, modifier = Modifier.size(42.dp))
                            }
                            IconButton(onClick = { showQueue = true }) {
                                Icon(Icons.Rounded.QueueMusic, contentDescription = "Queue", tint = TextMuted, modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                }

                Box(Modifier.fillMaxWidth().padding(bottom = 26.dp), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .width(180.dp)
                            .height(38.dp)
                            .clip(RoundedCornerShape(19.dp))
                            .background(GlassSurface)
                            .padding(3.dp)
                    ) {
                        val tabWidth = 87.dp
                        val indicatorOffset by animateDpAsState(
                            targetValue = if (activeTab == "Player") 0.dp else tabWidth,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.8f),
                            label = "tab_pill"
                        )
                        Box(
                            Modifier
                                .offset(x = indicatorOffset)
                                .width(tabWidth)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(16.dp))
                                .background(DeepSurface)
                        )
                        Row(Modifier.fillMaxSize()) {
                            Box(
                                Modifier.weight(1f).fillMaxHeight().clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { activeTab = "Player" },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Player", fontSize = 12.sp, color = if (activeTab == "Player") TextPure else TextSubtle, fontWeight = FontWeight.Bold)
                            }
                            Box(
                                Modifier.weight(1f).fillMaxHeight().clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { activeTab = "Lyrics" },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Lyrics", fontSize = 12.sp, color = if (activeTab == "Lyrics") TextPure else TextSubtle, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            if (showMenu) {
                ModalBottomSheet(onDismissRequest = { showMenu = false }, containerColor = DeepSurface) {
                    Column(Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 32.dp)) {
                        Text("Sonic Controls", fontSize = 24.sp, color = TextPure, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                        Spacer(Modifier.height(18.dp))
                        ListItem(
                            headlineContent = { Text("Cinematic Horizon", color = TextPure) },
                            leadingContent = { Icon(Icons.Rounded.Fullscreen, tint = Color(0xFF00E5FF), contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { showMenu = false; onEnterCinematic() }
                        )
                        ListItem(
                            headlineContent = { Text("Start Artist Radio (${song.artist.split(",").first()})", color = TextPure) },
                            leadingContent = { Icon(Icons.Rounded.Radio, tint = Color(0xFF00E676), contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                showMenu = false
                                startArtistRadio(song.artist)
                            }
                        )
                        ListItem(
                            headlineContent = { Text("Add to Collection", color = TextPure) },
                            leadingContent = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, tint = Color(0xFFFFD54F), contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                showMenu = false
                                showPlaylistPicker = true
                            }
                        )
                        ListItem(
                            headlineContent = { Text("View Details", color = TextPure) },
                            leadingContent = { Icon(Icons.Rounded.Info, tint = Color(0xFF9B51E0), contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                showMenu = false
                                showDetailsDialog = true
                            }
                        )
                    }
                }
            }

            if (showDetailsDialog) {
                ModalBottomSheet(onDismissRequest = { showDetailsDialog = false }, containerColor = DeepSurface) {
                    Column(Modifier.fillMaxWidth().padding(26.dp).padding(bottom = 36.dp)) {
                        Text("Track Details", fontSize = 24.sp, color = TextPure, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                        Spacer(Modifier.height(18.dp))
                        DetailRow(label = "Title", value = song.title)
                        DetailRow(label = "Artist", value = song.artist)
                        DetailRow(label = "Album", value = song.album)
                        DetailRow(label = "Length", value = song.durationText + " sec")
                        DetailRow(label = "Codec", value = "AAC 320 kbps (Lossless Spatial)")
                        DetailRow(
                            label = "Storage",
                            value = if (downloadProgress == -1f) "Offline Local Storage" else "Cloud Stream (JioSaavn CDN)"
                        )
                        Spacer(Modifier.height(18.dp))
                    }
                }
            }

            if (showPlaylistPicker) {
                ModalBottomSheet(onDismissRequest = { showPlaylistPicker = false }, containerColor = DeepSurface) {
                    Column(Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 32.dp)) {
                        Text("Add to Collection", fontSize = 22.sp, color = TextPure, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                        Spacer(Modifier.height(16.dp))
                        if (playlists.isEmpty()) {
                            Text("No collections created yet. Go to My World to create one.", color = TextMuted)
                        } else {
                            playlists.forEach { pl ->
                                ListItem(
                                    headlineContent = { Text(pl.name, color = TextPure, fontWeight = FontWeight.Bold) },
                                    supportingContent = { Text("${pl.songs.size} tracks", color = TextMuted) },
                                    leadingContent = {
                                        AsyncImage(
                                            model = pl.coverUrl,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
                                        )
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    modifier = Modifier.clickable {
                                        onAddToPlaylist(pl)
                                        showPlaylistPicker = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ==========================================
            // UP NEXT QUEUE SHEET (Fixes #3 Visual Hierarchy)
            // ==========================================
            AnimatedVisibility(
                visible = showQueue,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                Box(Modifier.fillMaxSize().background(VoidBlack)) {
                    val infiniteMesh = rememberInfiniteTransition(label = "queue_mesh")
                    val qOffsetX by infiniteMesh.animateFloat(
                        initialValue = -90f,
                        targetValue = 90f,
                        animationSpec = infiniteRepeatable(animation = tween(7000, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
                        label = "qOffset"
                    )
                    Box(Modifier.fillMaxSize().blur(60.dp).alpha(0.85f)) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawCircle(
                                color = artworkColor,
                                radius = size.width * 0.50f,
                                center = Offset(size.width / 2 + qOffsetX, size.height * 0.35f)
                            )
                            drawCircle(
                                color = secondaryColor,
                                radius = size.width * 0.45f,
                                center = Offset(size.width / 2 - qOffsetX, size.height * 0.65f)
                            )
                        }
                    }
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, VoidBlack.copy(alpha = 0.4f), VoidBlack.copy(alpha = 0.85f))
                            )
                        )
                    )

                    Column(
                        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Up Next Radio",
                                fontSize = 24.sp,
                                color = TextPure,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Serif
                            )
                            IconButton(onClick = { showQueue = false }) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "Close",
                                    tint = TextPure,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        Text("NOW RESONATING", fontSize = 11.sp, letterSpacing = 1.5.sp, color = artworkColor, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(artworkColor.copy(alpha = 0.30f), Color(0x28FFFFFF))
                                    )
                                )
                                .border(1.5.dp, artworkColor.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = song.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp))
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.title, fontSize = 16.sp, color = TextPure, fontWeight = FontWeight.Black, maxLines = 1)
                                Text(song.artist, fontSize = 13.sp, color = Color(0xEEFFFFFF), maxLines = 1)
                            }
                            Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = artworkColor, modifier = Modifier.size(26.dp))
                        }

                        Spacer(Modifier.height(20.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("UPCOMING SEQUENCE", fontSize = 11.sp, letterSpacing = 1.5.sp, color = TextMuted, fontWeight = FontWeight.Bold)
                            Text("Swipe right to remove", fontSize = 11.sp, color = TextSubtle)
                        }
                        Spacer(Modifier.height(10.dp))

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 60.dp)
                        ) {
                            itemsIndexed(
                                queue.drop(currentQueueIndex + 1),
                                key = { index, item -> "${item.id}_$index" }
                            ) { idx, qSong ->
                                SwipeToDismissSongRow(
                                    song = qSong,
                                    queuePosition = idx + 1,
                                    accentColor = artworkColor,
                                    secondaryColor = secondaryColor,
                                    isLiked = likedSongsList.any { it.id == qSong.id },
                                    onSongClick = { onPlayQueueSong(qSong); showQueue = false },
                                    onLikeToggle = { toggleLikeSong(qSong) },
                                    onDismiss = { onRemoveFromQueue(qSong) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DetailRow(label: String, value: String) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = TextMuted, fontSize = 14.sp)
            Text(value, color = TextPure, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }

    // FIX #3: Clear Visual Hierarchy in Up Next Queue Rows
    @Composable
    private fun SwipeToDismissSongRow(
        song: Song,
        queuePosition: Int,
        accentColor: Color,
        secondaryColor: Color,
        isLiked: Boolean,
        onSongClick: () -> Unit,
        onLikeToggle: () -> Unit,
        onDismiss: () -> Unit
    ) {
        val offsetX = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        var isDismissed by remember { mutableStateOf(false) }

        val isImmediateNext = queuePosition == 1
        val rowAlpha = when {
            queuePosition == 1 -> 1.0f
            queuePosition <= 4 -> 0.92f
            else -> 0.78f
        }

        if (!isDismissed) {
            val dragFraction = (offsetX.value / 220f).coerceIn(0f, 1f)

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    // Only tint red when actively dragging; completely transparent at rest
                    .background(Color(0xFFE53935).copy(alpha = (dragFraction * 0.45f)))
            ) {
                // Only show the delete icon when the user is actively swiping the card
                if (offsetX.value > 16f) {
                    Icon(
                        Icons.Rounded.DeleteSweep,
                        contentDescription = "Dismiss",
                        tint = TextPure.copy(alpha = dragFraction),
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 20.dp)
                    )
                }

                val cardAlpha = (1f - (offsetX.value / 600f).coerceIn(0f, 1f)) * rowAlpha

                // Adaptive background blended from the active song's blob palette
                val adaptiveBrush = if (isImmediateNext) {
                    Brush.horizontalGradient(
                        listOf(
                            accentColor.copy(alpha = 0.28f),
                            secondaryColor.copy(alpha = 0.18f)
                        )
                    )
                } else {
                    Brush.horizontalGradient(
                        listOf(
                            accentColor.copy(alpha = 0.14f),
                            secondaryColor.copy(alpha = 0.08f)
                        )
                    )
                }

                Row(
                    Modifier
                        .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                        .alpha(cardAlpha)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xCC0D0D12)) // Solid dark base to block background bleed
                        .background(adaptiveBrush)     // Adaptive blob color tint overlay
                        .border(
                            width = 1.dp,
                            color = if (isImmediateNext) accentColor.copy(alpha = 0.55f) else accentColor.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable { onSongClick() }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (offsetX.value > 220f) {
                                        scope.launch {
                                            offsetX.animateTo(
                                                targetValue = 1200f,
                                                animationSpec = tween(280, easing = FastOutSlowInEasing)
                                            )
                                            isDismissed = true
                                            onDismiss()
                                        }
                                    } else {
                                        scope.launch {
                                            offsetX.animateTo(
                                                targetValue = 0f,
                                                animationSpec = spring(
                                                    dampingRatio = 0.75f,
                                                    stiffness = Spring.StiffnessMediumLow
                                                )
                                            )
                                        }
                                    }
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                if (dragAmount > 0 || offsetX.value > 0) {
                                    scope.launch {
                                        offsetX.snapTo((offsetX.value + dragAmount).coerceAtLeast(0f))
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = if (isImmediateNext) 12.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = song.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(if (isImmediateNext) 52.dp else 44.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.title,
                            fontSize = if (isImmediateNext) 15.sp else 14.sp,
                            color = TextPure,
                            fontWeight = if (isImmediateNext) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            song.artist,
                            fontSize = 12.sp,
                            color = if (isImmediateNext) Color(0xEEFFFFFF) else TextMuted,
                            maxLines = 1
                        )
                    }
                    IconButton(onClick = onLikeToggle) {
                        Icon(
                            if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = null,
                            tint = if (isLiked) MeloAccent else TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun CinematicLandscapeScene(
        song: Song,
        artworkColor: Color,
        secondaryColor: Color,
        isPlaying: Boolean,
        currentPositionMs: Float,
        onExit: () -> Unit,
        onTogglePlay: () -> Unit,
        onSeek: (Float) -> Unit,
        repository: JioSaavnRepository
    ) {
        var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
        LaunchedEffect(song.id) {
            lyrics = repository.getSyncedLyrics(song)
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(VoidBlack)
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            val infinite = rememberInfiniteTransition(label = "cinematic_blob")
            val offsetX1 by infinite.animateFloat(
                initialValue = -150f,
                targetValue = 150f,
                animationSpec = infiniteRepeatable(animation = tween(8000, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
                label = "cinematic_offsetX"
            )
            Box(Modifier.fillMaxSize().blur(70.dp).alpha(0.85f)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(
                        color = artworkColor,
                        radius = size.height * 0.75f,
                        center = Offset(size.width / 3 + offsetX1, size.height / 2)
                    )
                    drawCircle(
                        color = secondaryColor,
                        radius = size.height * 0.75f,
                        center = Offset(size.width / 1.5f - offsetX1, size.height / 2)
                    )
                }
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(listOf(VoidBlack.copy(alpha = 0.3f), VoidBlack.copy(alpha = 0.8f)))
                )
            )

            Row(
                Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    Modifier.weight(0.75f).fillMaxHeight(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.Start
                ) {
                    IconButton(onClick = onExit) {
                        Icon(Icons.Rounded.FullscreenExit, contentDescription = null, tint = TextPure, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(18.dp))
                    AsyncImage(
                        model = song.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(220.dp).clip(RoundedCornerShape(26.dp))
                            .shadow(32.dp, ambientColor = artworkColor, spotColor = artworkColor)
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(song.title, fontSize = 26.sp, color = TextPure, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, maxLines = 1)
                    Text(song.artist, fontSize = 15.sp, color = TextMuted, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, maxLines = 1)
                    Spacer(Modifier.height(18.dp))
                    IconButton(onClick = onTogglePlay) {
                        Icon(
                            if (isPlaying) Icons.Rounded.PauseCircle else Icons.Rounded.PlayCircle,
                            contentDescription = null,
                            tint = TextPure,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                }

                val listState = rememberLazyListState()
                val isSynced = remember(lyrics) { lyrics.any { it.time >= 0f } }
                val activeLyricIndex = remember(lyrics, currentPositionMs, isSynced) {
                    if (!isSynced) -1
                    else lyrics.indexOfLast { (it.time * 1000f) <= (currentPositionMs + 120f) }
                }

                LaunchedEffect(activeLyricIndex) {
                    if (isSynced && activeLyricIndex in lyrics.indices) {
                        listState.animateScrollToItem(
                            index = activeLyricIndex,
                            scrollOffset = -240
                        )
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
                    contentPadding = PaddingValues(top = 40.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    if (lyrics.isEmpty()) {
                        item {
                            Text(
                                text = "No lyrics found for this track.",
                                color = TextMuted,
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    itemsIndexed(lyrics) { index, line ->
                        val isActive = if (isSynced) index == activeLyricIndex else true
                        val scale by animateFloatAsState(
                            targetValue = if (!isSynced) 1f else if (isActive) 1.06f else 0.96f,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "lyric_scale"
                        )
                        val alpha by animateFloatAsState(
                            targetValue = if (!isSynced) 0.88f else if (isActive) 1f else 0.28f,
                            animationSpec = tween(180),
                            label = "lyric_alpha"
                        )
                        Text(
                            text = line.text,
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Black,
                            fontSize = if (isSynced) 28.sp else 22.sp,
                            color = if (isActive) TextPure else TextMuted,
                            lineHeight = if (isSynced) 36.sp else 32.sp,
                            softWrap = true,
                            textAlign = TextAlign.Start,
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    transformOrigin = TransformOrigin(0f, 0.5f)
                                    scaleX = scale
                                    scaleY = scale
                                    this.alpha = alpha
                                }
                                .clickable(enabled = isSynced && line.time >= 0f) {
                                    onSeek(line.time * 1000f)
                                }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun PremiumMiniPlayer(
        song: Song,
        artworkColor: Color,
        isPlaying: Boolean,
        isBuffering: Boolean,
        onExpand: () -> Unit,
        onTogglePlay: () -> Unit,
        onNext: () -> Unit,
        onPrev: () -> Unit
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(68.dp)
                .clip(RoundedCornerShape(34.dp))
                .background(Color(0xFF111116))
                .background(
                    Brush.horizontalGradient(
                        0.0f to artworkColor.copy(alpha = if (isPlaying) 0.65f else 0.35f),
                        0.35f to artworkColor.copy(alpha = if (isPlaying) 0.28f else 0.12f),
                        0.70f to Color.Transparent,
                        1.0f to Color.Transparent
                    )
                )
                .clickable { onExpand() }
        ) {
            if (isPlaying) {
                val infinite = rememberInfiniteTransition(label = "mini_wave")
                val phase1 by infinite.animateFloat(
                    initialValue = 0f,
                    targetValue = (2f * Math.PI).toFloat(),
                    animationSpec = infiniteRepeatable(animation = tween(2200, easing = LinearEasing)),
                    label = "phase1"
                )
                val phase2 by infinite.animateFloat(
                    initialValue = 0f,
                    targetValue = (2f * Math.PI).toFloat(),
                    animationSpec = infiniteRepeatable(animation = tween(1600, easing = LinearEasing)),
                    label = "phase2"
                )

                Canvas(Modifier.fillMaxWidth().height(48.dp).align(Alignment.BottomCenter).alpha(0.85f)) {
                    val path1 = Path()
                    val path2 = Path()
                    path1.moveTo(0f, size.height)
                    path2.moveTo(0f, size.height)

                    for (x in 0..size.width.toInt() step 4) {
                        val xF = x.toFloat()
                        val y1 = size.height - (sin((xF * 0.035f) + phase1) * 16f + 18f)
                        val y2 = size.height - (sin((xF * 0.055f) + phase2) * 12f + 14f)
                        path1.lineTo(xF, y1)
                        path2.lineTo(xF, y2)
                    }

                    path1.lineTo(size.width, size.height)
                    path1.close()
                    path2.lineTo(size.width, size.height)
                    path2.close()

                    drawPath(path1, artworkColor.copy(alpha = 0.75f))
                    drawPath(path2, Color(0xFF00E5FF).copy(alpha = 0.5f))
                }
            }

            Row(
                Modifier.fillMaxSize().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = song.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(50.dp).clip(CircleShape)
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        fontSize = 15.sp,
                        color = TextPure,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee()
                    )
                    Text(
                        text = song.artist,
                        fontSize = 12.sp,
                        color = TextSubtle,
                        maxLines = 1,
                        modifier = Modifier.combinedClickable(
                            onClick = { onExpand() },
                            onLongClick = { startArtistRadio(song.artist) }
                        )
                    )
                }
                IconButton(onClick = onPrev) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = null, tint = TextPure, modifier = Modifier.size(28.dp))
                }
                IconButton(onClick = onTogglePlay) {
                    if (isBuffering) {
                        CircularProgressIndicator(color = TextPure, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    } else {
                        Icon(
                            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = TextPure,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = null, tint = TextPure, modifier = Modifier.size(28.dp))
                }
            }
        }
    }

    // FIX #1: Stable Wave Scrubber (Never fights hardware playback ticks)
    @Composable
    private fun InteractiveWaveScrubber(
        progress: Float,
        accentColor: Color,
        currentPositionMs: Float,
        totalDurationMs: Float,
        modifier: Modifier,
        onUserSeekingChange: (Boolean) -> Unit,
        onSeekProgress: (Float) -> Unit
    ) {
        var isDragging by remember { mutableStateOf(false) }
        var localDragProgress by remember { mutableFloatStateOf(0f) }

        val displayProgress = if (isDragging) localDragProgress else progress.coerceIn(0f, 1f)

        val infinite = rememberInfiniteTransition(label = "scrubber")
        val phase by infinite.animateFloat(
            initialValue = 0f,
            targetValue = (2f * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(animation = tween(1500, easing = LinearEasing)),
            label = "scrubber_phase"
        )

        Column(modifier = modifier) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .pointerInput(totalDurationMs) {
                        detectTapGestures { offset ->
                            if (size.width > 0) {
                                val targetProgress = (offset.x / size.width.toFloat()).coerceIn(0f, 0.99f)
                                onUserSeekingChange(true)
                                onSeekProgress(targetProgress)
                            }
                        }
                    }
                    .pointerInput(totalDurationMs) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                isDragging = true
                                onUserSeekingChange(true)
                                localDragProgress = (offset.x / size.width.toFloat()).coerceIn(0f, 0.99f)
                            },
                            onDragEnd = {
                                isDragging = false
                                onSeekProgress(localDragProgress)
                            },
                            onDragCancel = {
                                isDragging = false
                                onUserSeekingChange(false)
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                localDragProgress = (change.position.x / size.width.toFloat()).coerceIn(0f, 0.99f)
                            }
                        )
                    }
            ) {
                val centerY = size.height / 2
                drawLine(GlassSurface, Offset(0f, centerY), Offset(size.width, centerY), strokeWidth = 10f, cap = StrokeCap.Round)

                val fillWidth = size.width * displayProgress

                val path = Path()
                for (x in 0..fillWidth.toInt() step 4) {
                    val y = centerY + sin((x * 0.08f) - phase) * 5f
                    if (x == 0) path.moveTo(0f, y) else path.lineTo(x.toFloat(), y)
                }
                drawPath(path, accentColor, style = Stroke(width = 10f))

                val thumbRadius = if (isDragging) 18f else 13f
                drawCircle(Color(0x66000000), radius = thumbRadius + 8f, center = Offset(fillWidth, centerY))
                drawCircle(DeepSurface, radius = thumbRadius + 4f, center = Offset(fillWidth, centerY))
                drawCircle(TextPure, radius = thumbRadius, center = Offset(fillWidth, centerY))
                drawCircle(accentColor, radius = thumbRadius * 0.5f, center = Offset(fillWidth, centerY))
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatTime((displayProgress * totalDurationMs).toLong()),
                    fontSize = 11.sp,
                    color = TextMuted,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    formatTime(totalDurationMs.toLong()),
                    fontSize = 11.sp,
                    color = TextMuted,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    private fun formatTime(millis: Long): String {
        val totalSecs = (millis / 1000).coerceAtLeast(0)
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return "%d:%02d".format(mins, secs)
    }

    @Composable
    private fun MeloBottomNav(
        selectedTab: MeloTab,
        onSelectTab: (MeloTab) -> Unit,
        modifier: Modifier = Modifier
    ) {
        val tabs = MeloTab.entries
        val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)

        val navWidth = 280.dp
        val navHeight = 56.dp
        val horizontalPadding = 6.dp
        val itemWidth = (navWidth - (horizontalPadding * 2)) / tabs.size

        val indicatorOffset by animateDpAsState(
            targetValue = horizontalPadding + (itemWidth * selectedIndex),
            animationSpec = spring(
                dampingRatio = 0.76f,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "nav_sliding_indicator"
        )

        val activeGlowColor by animateColorAsState(
            targetValue = selectedTab.activeTint.copy(alpha = 0.28f),
            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
            label = "nav_active_glow"
        )

        Box(
            modifier = modifier
                .shadow(
                    elevation = 20.dp,
                    shape = RoundedCornerShape(32.dp),
                    ambientColor = activeGlowColor,
                    spotColor = activeGlowColor
                )
                .clip(RoundedCornerShape(32.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xEE121218),
                            Color(0xFA08080C)
                        )
                    )
                )
                .border(
                    BorderStroke(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.18f),
                                Color.White.copy(alpha = 0.04f)
                            )
                        )
                    ),
                    RoundedCornerShape(32.dp)
                )
                .size(width = navWidth, height = navHeight)
        ) {
            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset, y = 6.dp)
                    .size(width = itemWidth, height = navHeight - 12.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                selectedTab.activeTint.copy(alpha = 0.22f),
                                selectedTab.activeTint.copy(alpha = 0.10f)
                            )
                        )
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            selectedTab.activeTint.copy(alpha = 0.35f)
                        ),
                        RoundedCornerShape(22.dp)
                    )
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { tab ->
                    val isSelected = selectedTab == tab
                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()

                    val itemScale by animateFloatAsState(
                        targetValue = when {
                            isPressed -> 0.90f
                            isSelected -> 1.05f
                            else -> 1.0f
                        },
                        animationSpec = spring(
                            dampingRatio = 0.65f,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "tab_item_scale"
                    )

                    val iconTint by animateColorAsState(
                        targetValue = if (isSelected) tab.activeTint else Color.White.copy(alpha = 0.45f),
                        animationSpec = tween(durationMillis = 200),
                        label = "tab_icon_tint"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .scale(itemScale)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) {
                                onSelectTab(tab)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                tint = iconTint,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = tab.label,
                                fontSize = 10.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.40f),
                                letterSpacing = 0.2.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}