package com.melomusic.app

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
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.melomusic.app.playback.PlayerConnection
import kotlinx.coroutines.Dispatchers
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
private val BlobColor1 = Color(0xFF9B51E0)
private val BlobColor3 = Color(0xFF00E5FF)

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

data class UserPlaylist(val name: String, var coverUrl: String, val songs: MutableList<Song> = mutableListOf())

const val MODE_SHUFFLE = 0
const val MODE_REPEAT_ALL = 1
const val MODE_REPEAT_ONE = 2
const val CURRENT_APP_VERSION = "v1.0.2"

private const val NOTIFICATION_ID = 4040
private const val CHANNEL_ID = "melo_playback_channel"

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private lateinit var player: PlayerConnection
    private val saavnRepository = JioSaavnRepository()
    private val httpClient = OkHttpClient()
    private val gson = Gson()
    private lateinit var prefs: SharedPreferences
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
    private var downloadId by mutableStateOf(-1L)

    private var mediaSession: MediaSession? = null
    private var currentArtworkBitmap: Bitmap? = null

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == downloadId && context != null) installApk(context)
        }
    }

    private val mediaActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.melomusic.app.ACTION_TOGGLE" -> {
                    userPausedManually = player.state.value.isPlaying
                    player.togglePlayPause()
                }
                "com.melomusic.app.ACTION_NEXT" -> playNext()
                "com.melomusic.app.ACTION_PREV" -> playPrev()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        prefs = getSharedPreferences("melo_vault", Context.MODE_PRIVATE)
        loadPersistedState()
        userProfileName = prefs.getString("user_profile_name", null)
        createNotificationChannel()

        ContextCompat.registerReceiver(this, updateReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
        val mediaFilter = IntentFilter().apply {
            addAction("com.melomusic.app.ACTION_TOGGLE")
            addAction("com.melomusic.app.ACTION_NEXT")
            addAction("com.melomusic.app.ACTION_PREV")
        }
        ContextCompat.registerReceiver(this, mediaActionReceiver, mediaFilter, ContextCompat.RECEIVER_EXPORTED)

        player = PlayerConnection(this, lifecycleScope)
        player.connect()
        setContent { MeloRoot() }
    }

    override fun onDestroy() {
        unregisterReceiver(updateReceiver)
        unregisterReceiver(mediaActionReceiver)
        mediaSession?.release()
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        player.release()
        super.onDestroy()
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

            downloaded.forEach { s ->
                val file = File(filesDir, "downloads/${s.id}.mp3")
                if (file.exists() && file.length() > 5000) {
                    s.localPath = Uri.fromFile(file).toString()
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

    private fun persistLikedSongs() = prefs.edit().putString("liked_songs", gson.toJson(likedSongsList.toList())).apply()
    private fun persistRecentSongs() = prefs.edit().putString("recent_songs", gson.toJson(recentlyPlayedList.toList())).apply()
    private fun persistDownloadedSongs() = prefs.edit().putString("downloaded_songs", gson.toJson(downloadedSongsList.toList())).apply()
    private fun persistUserPlaylists() = prefs.edit().putString("user_playlists", gson.toJson(userPlaylists.toList())).apply()

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
        val localFile = File(filesDir, "downloads/${song.id}.mp3")
        return if (localFile.exists() && localFile.length() > 5000) {
            Uri.fromFile(localFile).toString()
        } else if (!song.localPath.isNullOrBlank() && song.localPath!!.startsWith("file://")) {
            song.localPath
        } else {
            null
        }
    }

    private fun downloadSongInternally(song: Song) {
        if (downloadProgressMap[song.id] == -1f) return
        downloadProgressMap[song.id] = 0.01f

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val streamUrl = saavnRepository.getRemoteDecryptedUrl(song)
                if (streamUrl.isNullOrBlank()) {
                    withContext(Dispatchers.Main) { downloadProgressMap.remove(song.id) }
                    return@launch
                }

                val downloadDir = File(filesDir, "downloads")
                if (!downloadDir.exists()) downloadDir.mkdirs()
                val targetFile = File(downloadDir, "${song.id}.mp3")

                val req = Request.Builder()
                    .url(streamUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()

                val resp = httpClient.newCall(req).execute()
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                val body = resp.body ?: throw Exception("Empty stream body")
                val totalLength = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var read: Int
                        var currentBytes = 0L

                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            currentBytes += read
                            if (totalLength > 0) {
                                val progress = (currentBytes.toFloat() / totalLength.toFloat()).coerceIn(0.01f, 0.99f)
                                withContext(Dispatchers.Main) { downloadProgressMap[song.id] = progress }
                            }
                        }
                        output.flush()
                    }
                }

                if (targetFile.length() < 5000) {
                    targetFile.delete()
                    throw Exception("Corrupted audio file")
                }

                song.localPath = Uri.fromFile(targetFile).toString()
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
        val file = File(filesDir, "downloads/${song.id}.mp3")
        if (file.exists()) file.delete()
        downloadedSongsList.removeAll { it.id == song.id }
        downloadProgressMap.remove(song.id)
        persistDownloadedSongs()
    }

    private fun playSongWithSmartRadio(seedSong: Song) {
        player.state.value.song?.let { playbackHistory.add(it) }
        recordRecentlyPlayed(seedSong)
        currentQueue.clear()
        currentQueue.add(seedSong)
        currentQueueIndex = 0
        currentPlaybackPositionMs = 0f
        userPausedManually = false

        player.play(seedSong) { target ->
            resolvePlayableUrl(target) ?: saavnRepository.getStreamUrl(target)
        }

        lifecycleScope.launch {
            val radioTracks = saavnRepository.getRadioRecommendations(seedSong)
            withContext(Dispatchers.Main) {
                currentQueue.addAll(radioTracks)
            }
        }
    }

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

        player.play(song) { target ->
            resolvePlayableUrl(target) ?: saavnRepository.getStreamUrl(target)
        }

        if (currentQueue.size - currentQueueIndex <= 3) {
            lifecycleScope.launch {
                val moreRadio = saavnRepository.getRadioRecommendations(song)
                withContext(Dispatchers.Main) {
                    val existingIds = currentQueue.map { it.id }.toSet()
                    currentQueue.addAll(moreRadio.filter { it.id !in existingIds })
                }
            }
        }
    }

    private fun playNext() {
        if (currentQueue.isEmpty()) return
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

        val nextIndex = if (playMode == MODE_SHUFFLE && currentQueue.size > 1) {
            var rand = (currentQueue.indices).random()
            while (rand == currentQueueIndex && currentQueue.size > 1) {
                rand = (currentQueue.indices).random()
            }
            rand
        } else {
            (currentQueueIndex + 1) % currentQueue.size
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

    private fun seekSong(targetPositionMs: Float) {
        currentPlaybackPositionMs = targetPositionMs
        val targetLong = targetPositionMs.toLong()
        try {
            val m = player::class.java.getMethod("seekTo", Long::class.javaPrimitiveType)
            m.invoke(player, targetLong)
            return
        } catch (_: Exception) { }
        try {
            val m = player::class.java.getMethod("seekTo", Int::class.javaPrimitiveType)
            m.invoke(player, targetLong.toInt())
            return
        } catch (_: Exception) { }
        try {
            val m = player::class.java.getMethod("seekTo", Float::class.javaPrimitiveType)
            m.invoke(player, targetPositionMs)
            return
        } catch (_: Exception) { }
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Melo Playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Native lockscreen and notification media controls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun updateNativeNotification(song: Song?, isPlaying: Boolean, posMs: Long, artwork: Bitmap?) {
        if (song == null) {
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            mediaSession?.isActive = false
            return
        }

        if (mediaSession == null) {
            mediaSession = MediaSession(this, "MeloMusicSession").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() {
                        userPausedManually = false
                        player.togglePlayPause()
                    }
                    override fun onPause() {
                        userPausedManually = true
                        player.togglePlayPause()
                    }
                    override fun onSkipToNext() { playNext() }
                    override fun onSkipToPrevious() { playPrev() }
                    override fun onSeekTo(pos: Long) { seekSong(pos.toFloat()) }
                })
            }
        }

        val session = mediaSession ?: return
        session.isActive = true

        val playbackState = PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO)
            .setState(if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, posMs, 1.0f)
            .build()
        session.setPlaybackState(playbackState)

        val totalDurationMs = parseDurationMs(song.durationText).toLong()
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, song.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, song.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, totalDurationMs)
            .apply { if (artwork != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork) }
            .build()
        session.setMetadata(metadata)

        val prevIntent = PendingIntent.getBroadcast(this, 1, Intent("com.melomusic.app.ACTION_PREV"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val toggleIntent = PendingIntent.getBroadcast(this, 2, Intent("com.melomusic.app.ACTION_TOGGLE"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val nextIntent = PendingIntent.getBroadcast(this, 3, Intent("com.melomusic.app.ACTION_NEXT"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notifBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        notifBuilder
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)
            .addAction(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, "Toggle", toggleIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)

        if (artwork != null) notifBuilder.setLargeIcon(artwork)
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notifBuilder.build())
    }

    private fun checkGithubForUpdates() {
        isCheckingUpdate = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url("https://api.github.com/repos/7VLabs-Git/melomusic-app/releases/latest").build()
                val resp = httpClient.newCall(req).execute()
                val raw = resp.body?.string() ?: ""
                val json = JsonParser.parseString(raw).asJsonObject
                val tag = json.get("tag_name")?.asString
                val body = json.get("body")?.asString ?: "Enhanced sound pipeline and UI improvements."
                val assets = json.getAsJsonArray("assets")
                var apkUrl = ""
                if (assets != null && assets.size() > 0) apkUrl = assets.get(0).asJsonObject.get("browser_download_url")?.asString ?: ""
                withContext(Dispatchers.Main) {
                    if (tag != null && tag != CURRENT_APP_VERSION) {
                        updateVersion = tag
                        updateNotes = body
                        updateUrl = apkUrl
                    } else updateVersion = null
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
        } catch (e: Exception) { e.printStackTrace() }
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
        var showUpdateSheet by remember { mutableStateOf(false) }
        var extractedArtworkColor by remember { mutableStateOf(Color(0xFFFF5252)) }

        var listenedSongTimeMs by remember { mutableFloatStateOf(0f) }
        var currentSongIdTracked by remember { mutableStateOf<String?>(null) }
        var lastTriggeredSongId by remember { mutableStateOf<String?>(null) }
        var becauseArtistName by remember { mutableStateOf(prefs.getString("last_listened_artist", null)) }
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
                val (color, bmp) = extractDominantColorAndBitmap(context, url)
                extractedArtworkColor = color
                currentArtworkBitmap = bmp
                updateNativeNotification(playerState.song, playerState.isPlaying, currentPlaybackPositionMs.toLong(), bmp)
            }
        }

        // Dedicated playback progress ticker with seeking protection
        LaunchedEffect(playerState.isPlaying, playerState.song?.id, playMode) {
            updateNativeNotification(playerState.song, playerState.isPlaying, currentPlaybackPositionMs.toLong(), currentArtworkBitmap)

            if (playerState.song != null) {
                if (playerState.isPlaying) {
                    if (currentSongIdTracked != playerState.song?.id) {
                        currentSongIdTracked = playerState.song?.id
                        listenedSongTimeMs = 0f
                    }

                    while (playerState.isPlaying) {
                        delay(100)
                        if (!isUserSeeking) {
                            currentPlaybackPositionMs += 100f
                            listenedSongTimeMs += 100f
                        }

                        if (listenedSongTimeMs >= 30000f && playerState.song?.id != lastTriggeredSongId) {
                            lastTriggeredSongId = playerState.song?.id
                            val artistName = playerState.song?.artist?.split(",")?.firstOrNull()?.trim() ?: playerState.song?.artist ?: ""
                            if (artistName.isNotBlank()) {
                                becauseArtistName = artistName
                                prefs.edit().putString("last_listened_artist", artistName).apply()
                                lifecycleScope.launch {
                                    val reco = saavnRepository.searchSongs("$artistName Hits")
                                    if (reco.isNotEmpty()) becauseRecommendations = reco
                                }
                            }
                        }

                        val songDuration = parseDurationMs(playerState.song!!.durationText)
                        if (!isUserSeeking && currentPlaybackPositionMs >= (songDuration - 400f) && songDuration > 5000f) {
                            playNext()
                            break
                        }
                    }
                }
            } else {
                currentPlaybackPositionMs = 0f
            }
        }

        BackHandler(enabled = playerTier != PlayerTier.MINI || currentRoute != NavRoute.Main) {
            if (playerTier == PlayerTier.CINEMATIC) {
                playerTier = PlayerTier.EXPANDED
                context.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else if (playerTier == PlayerTier.EXPANDED) {
                playerTier = PlayerTier.MINI
            } else if (currentRoute != NavRoute.Main) {
                currentRoute = NavRoute.Main
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(VoidBlack)
        ) {
            if (currentRoute == NavRoute.Main) {
                Box(Modifier.fillMaxSize()) {
                    AnimatedContent(targetState = currentTab, transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(350)) }, label = "tab") { tab ->
                        when (tab) {
                            MeloTab.HOME -> HomeScene(
                                onPlay = { s, q -> startSongPlayback(s, q) },
                                repo = saavnRepository,
                                scrollState = homeScrollState,
                                becauseArtist = becauseArtistName,
                                becauseList = becauseRecommendations,
                                isLoggedIn = userProfileName != null,
                                userName = userProfileName.orEmpty(),
                                onAccountClick = { showAccountSheet = true }
                            )
                            MeloTab.EXPLORE -> ExploreScene(onPlaySongWithRadio = { playSongWithSmartRadio(it) }, repo = saavnRepository)
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

                    // RESTORED SLIM & PROPORTIONATE NAVIGATION CAPSULE
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
                        is NavRoute.CreatePlaylist -> CreatePlaylistScene(
                            onBack = { currentRoute = NavRoute.Main },
                            onCreate = { name, img ->
                                userPlaylists.add(0, UserPlaylist(name, img))
                                persistUserPlaylists()
                                currentRoute = NavRoute.Main
                            }
                        )
                        is NavRoute.PlaylistDetail -> PlaylistDetailScene(
                            route = route,
                            onPlay = { s, q -> startSongPlayback(s, q) },
                            onDelete = {
                                userPlaylists.removeAll { it.name == route.name }
                                persistUserPlaylists()
                                currentRoute = NavRoute.Main
                            },
                            onBack = { currentRoute = NavRoute.Main }
                        )
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
                        else -> {}
                    }
                }
            }

            // FLOATING MINI PLAYER
            val isKeyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            val miniPlayerBottomPadding = if (isKeyboardOpen) 10.dp else if (currentRoute == NavRoute.Main) 86.dp else 16.dp

            AnimatedVisibility(
                visible = playerState.song != null && playerTier == PlayerTier.MINI,
                enter = slideInVertically(initialOffsetY = { it }, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(250)) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(bottom = miniPlayerBottomPadding, start = 14.dp, end = 14.dp)
            ) {
                playerState.song?.let { song ->
                    PremiumMiniPlayer(
                        song = song,
                        artworkColor = extractedArtworkColor,
                        isPlaying = playerState.isPlaying,
                        isBuffering = playerState.isBuffering,
                        onExpand = { playerTier = PlayerTier.EXPANDED },
                        onTogglePlay = {
                            userPausedManually = playerState.isPlaying
                            player.togglePlayPause()
                        },
                        onNext = { playNext() },
                        onPrev = { playPrev() }
                    )
                }
            }

            // FULL-SCREEN IMMERSIVE PLAYER
            AnimatedVisibility(
                visible = playerState.song != null && playerTier == PlayerTier.EXPANDED,
                enter = slideInVertically(initialOffsetY = { it }, animationSpec = spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMediumLow)),
                exit = slideOutVertically(targetOffsetY = { it }, animationSpec = spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMediumLow))
            ) {
                playerState.song?.let { song ->
                    ImmersivePlayerScene(
                        song = song,
                        artworkColor = extractedArtworkColor,
                        isPlaying = playerState.isPlaying,
                        isBuffering = playerState.isBuffering,
                        currentPositionMs = currentPlaybackPositionMs,
                        isLiked = likedSongsList.any { it.id == song.id },
                        queue = currentQueue,
                        currentQueueIndex = currentQueueIndex,
                        playlists = userPlaylists,
                        playMode = playMode,
                        downloadProgress = downloadProgressMap[song.id] ?: (if (downloadedSongsList.any { it.id == song.id }) -1f else 0f),
                        onDownloadClick = { downloadSongInternally(song) },
                        onLikeToggle = { toggleLikeSong(song) },
                        onAddToPlaylist = { pl -> addSongToPlaylist(pl, song) },
                        onPlayQueueSong = { qSong -> startSongPlayback(qSong, currentQueue) },
                        onRemoveFromQueue = { songToRemove -> currentQueue.remove(songToRemove) },
                        onCollapse = { playerTier = PlayerTier.MINI },
                        onTogglePlay = {
                            userPausedManually = playerState.isPlaying
                            player.togglePlayPause()
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

            // CINEMATIC MODE
            AnimatedVisibility(
                visible = playerState.song != null && playerTier == PlayerTier.CINEMATIC,
                enter = fadeIn(tween(500)),
                exit = fadeOut(tween(500))
            ) {
                playerState.song?.let { song ->
                    CinematicLandscapeScene(
                        song = song,
                        artworkColor = extractedArtworkColor,
                        isPlaying = playerState.isPlaying,
                        currentPositionMs = currentPlaybackPositionMs,
                        onExit = {
                            playerTier = PlayerTier.EXPANDED
                            context.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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

            if (showAccountSheet) {
                var isEditingName by remember { mutableStateOf(false) }
                var tempNameInput by remember { mutableStateOf(userProfileName ?: "") }

                ModalBottomSheet(onDismissRequest = { showAccountSheet = false }, containerColor = DeepSurface) {
                    Column(Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 32.dp)) {
                        Text("Melo Sanctuary", fontSize = 24.sp, color = TextPure, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
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
                                    modifier = Modifier.fillMaxWidth().background(VoidBlack, RoundedCornerShape(12.dp)).border(1.dp, GlassSurface, RoundedCornerShape(12.dp)).padding(14.dp)
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
                                headlineContent = { Text(if (userProfileName != null) "Logged in as $userProfileName" else "Sound Profile", color = TextPure, fontWeight = FontWeight.Medium) },
                                supportingContent = { Text(if (userProfileName != null) "Tap to change name or log out" else "Tap to log in & personalize greeting", color = TextMuted) },
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
                            supportingContent = { Text("Import genuine songs from Spotify or YouTube Music", color = TextMuted) },
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
                                showUpdateSheet = true
                                checkGithubForUpdates()
                            }
                        )
                    }
                }
            }

            // RELIABLE SPOTIFY PLAYLIST IMPORTER
            if (showImportSheet) {
                var linkOrName by remember { mutableStateOf("") }
                var isImporting by remember { mutableStateOf(false) }
                var importProgress by remember { mutableFloatStateOf(0f) }
                var importStatus by remember { mutableStateOf("") }

                ModalBottomSheet(onDismissRequest = { if (!isImporting) showImportSheet = false }, containerColor = DeepSurface) {
                    Column(Modifier.fillMaxWidth().padding(26.dp).padding(bottom = 36.dp)) {
                        Text("Sonic Importer", fontSize = 24.sp, color = TextPure, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                        Spacer(Modifier.height(8.dp))
                        Text("Paste a Spotify playlist link to extract playlist name, original artwork, and accurate tracks.", color = TextMuted, fontSize = 13.sp)
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
                                modifier = Modifier.fillMaxWidth().background(VoidBlack, RoundedCornerShape(14.dp)).border(1.dp, GlassSurface, RoundedCornerShape(14.dp)).padding(16.dp),
                                decorationBox = { inner -> if (linkOrName.isEmpty()) Text("Paste Spotify playlist link...", color = TextSubtle, fontSize = 14.sp) else inner() }
                            )
                        }

                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = {
                                isImporting = true
                                importProgress = 0.05f
                                importStatus = "Fetching Spotify metadata..."
                                lifecycleScope.launch {
                                    var playlistTitle = "Imported Playlist"
                                    var playlistCover = ""
                                    val resolvedSongs = mutableListOf<Song>()
                                    val cleanInput = linkOrName.trim()

                                    if (cleanInput.contains("spotify.com")) {
                                        try {
                                            val playlistIdMatch = Regex("playlist/([a-zA-Z0-9]+)").find(cleanInput)
                                            val playlistId = playlistIdMatch?.groupValues?.get(1)

                                            if (playlistId != null) {
                                                val embedUrl = "https://open.spotify.com/embed/playlist/$playlistId"
                                                val embedReq = Request.Builder()
                                                    .url(embedUrl)
                                                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                                    .header("Accept", "text/html,application/xhtml+xml")
                                                    .build()

                                                val respHtml = httpClient.newCall(embedReq).execute().body?.string() ?: ""

                                                val nextDataMatch = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""").find(respHtml)
                                                if (nextDataMatch != null) {
                                                    val rawJson = nextDataMatch.groupValues[1]
                                                    val json = JsonParser.parseString(rawJson).asJsonObject
                                                    val entity = json.getAsJsonObject("props")
                                                        ?.getAsJsonObject("pageProps")
                                                        ?.getAsJsonObject("state")
                                                        ?.getAsJsonObject("data")
                                                        ?.getAsJsonObject("entity")

                                                    if (entity != null) {
                                                        playlistTitle = entity.get("name")?.asString
                                                            ?: entity.get("title")?.asString
                                                                    ?: "Spotify Playlist"

                                                        val images = entity.getAsJsonObject("visualIdentity")?.getAsJsonArray("image")
                                                        if (images != null && images.size() > 0) {
                                                            playlistCover = images.get(0).asJsonObject.get("url")?.asString.orEmpty()
                                                        }

                                                        val trackList = entity.getAsJsonArray("trackList")
                                                        if (trackList != null && trackList.size() > 0) {
                                                            val total = trackList.size().coerceAtMost(40)
                                                            for (i in 0 until total) {
                                                                val trackObj = trackList.get(i).asJsonObject
                                                                val tTitle = trackObj.get("title")?.asString ?: ""
                                                                val tSubtitle = trackObj.get("subtitle")?.asString ?: ""

                                                                withContext(Dispatchers.Main) {
                                                                    importProgress = 0.1f + ((i.toFloat() / total.toFloat()) * 0.85f)
                                                                    importStatus = "Resolving track ${i + 1}/$total: $tTitle"
                                                                }

                                                                val matched = saavnRepository.searchSongs("$tTitle $tSubtitle")
                                                                if (matched.isNotEmpty()) {
                                                                    resolvedSongs.add(matched[0])
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }

                                    if (resolvedSongs.isEmpty()) {
                                        playlistTitle = if (cleanInput.length > 25) "Imported Playlist" else cleanInput.replaceFirstChar { it.uppercase() }
                                        resolvedSongs.addAll(saavnRepository.searchSongs(playlistTitle))
                                    }

                                    val finalCover = playlistCover.ifBlank { resolvedSongs.firstOrNull()?.thumbnailUrl ?: PRESET_COVERS[0] }

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

            if (showUpdateSheet) {
                val updateSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(onDismissRequest = { showUpdateSheet = false }, sheetState = updateSheetState, containerColor = DeepSurface) {
                    Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        AnimatedContent(targetState = isCheckingUpdate to (updateVersion != null), label = "update_anim") { (checking, hasUpdate) ->
                            if (checking) {
                                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = MeloAccent, strokeWidth = 3.dp, modifier = Modifier.size(60.dp))
                                    Spacer(Modifier.height(24.dp))
                                    Text("Contacting Core...", color = TextPure, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                                }
                            } else if (hasUpdate) {
                                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(72.dp).clip(CircleShape).background(Color(0xFF00E5FF).copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.NewReleases, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(42.dp))
                                    }
                                    Spacer(Modifier.height(18.dp))
                                    Text("Update Available", fontSize = 28.sp, color = TextPure, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                                    Text("Release $updateVersion", fontSize = 16.sp, color = Color(0xFF00E5FF), fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(20.dp))
                                    Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(VoidBlack).padding(20.dp)) {
                                        Text(updateNotes, color = TextMuted, fontSize = 14.sp, lineHeight = 22.sp)
                                    }
                                    Spacer(Modifier.height(24.dp))
                                    Button(
                                        onClick = { downloadAndInstallUpdate(); showUpdateSheet = false },
                                        modifier = Modifier.fillMaxWidth().height(56.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                                        shape = RoundedCornerShape(18.dp)
                                    ) {
                                        Text("DOWNLOAD & AUTO-INSTALL", color = VoidBlack, fontWeight = FontWeight.Black)
                                    }
                                }
                            } else {
                                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(76.dp).clip(CircleShape).background(Color(0xFF00E676).copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
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

    private suspend fun extractDominantColorAndBitmap(context: Context, imageUrl: String): Pair<Color, Bitmap?> {
        return withContext(Dispatchers.IO) {
            try {
                val loader = context.imageLoader
                val request = ImageRequest.Builder(context).data(imageUrl).allowHardware(false).build()
                val result = (loader.execute(request) as? SuccessResult)?.drawable
                val bitmap = (result as? BitmapDrawable)?.bitmap
                if (bitmap != null) {
                    var bestColor = android.graphics.Color.DKGRAY
                    var highestScore = -1f
                    val hsv = FloatArray(3)
                    val stepX = (bitmap.width / 30).coerceAtLeast(1)
                    val stepY = (bitmap.height / 30).coerceAtLeast(1)

                    for (x in 0 until bitmap.width step stepX) {
                        for (y in 0 until bitmap.height step stepY) {
                            val pixel = bitmap.getPixel(x, y)
                            if (android.graphics.Color.alpha(pixel) < 128) continue
                            android.graphics.Color.colorToHSV(pixel, hsv)
                            val sat = hsv[1]
                            val value = hsv[2]

                            if (sat > 0.12f && value > 0.12f) {
                                val score = sat * 2.2f + (1.0f - kotlin.math.abs(value - 0.65f))
                                if (score > highestScore) {
                                    highestScore = score
                                    bestColor = pixel
                                }
                            }
                        }
                    }

                    if (highestScore == -1f) bestColor = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    android.graphics.Color.colorToHSV(bestColor, hsv)
                    if (hsv[2] < 0.60f) hsv[2] = 0.70f
                    if (hsv[1] < 0.45f) hsv[1] = 0.60f
                    val boosted = android.graphics.Color.HSVToColor(hsv)
                    Pair(Color(boosted), bitmap)
                } else Pair(Color(0xFFFF5252), null)
            } catch (_: Exception) { Pair(Color(0xFFFF5252), null) }
        }
    }

    @Composable
    private fun rememberShimmerBrush(): Brush {
        val transition = rememberInfiniteTransition(label = "shimmer_trans")
        val translateAnim by transition.animateFloat(
            initialValue = -350f,
            targetValue = 1350f,
            animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing), repeatMode = RepeatMode.Restart),
            label = "shimmer_offset"
        )
        return Brush.linearGradient(
            colors = listOf(Color(0xFF14141E), Color(0xFF262638), Color(0xFF14141E)),
            start = Offset(translateAnim, 0f),
            end = Offset(translateAnim + 350f, 250f)
        )
    }

    // ==========================================
    // SCENE 01: HOME (REFINED MODERN GRADIENT & HERO LOGO)
    // ==========================================
    @Composable
    private fun HomeScene(
        onPlay: (Song, List<Song>) -> Unit,
        repo: JioSaavnRepository,
        scrollState: LazyListState,
        becauseArtist: String?,
        becauseList: List<Song>,
        isLoggedIn: Boolean,
        userName: String,
        onAccountClick: () -> Unit
    ) {
        var topPicks by remember { mutableStateOf<List<Song>>(emptyList()) }
        var jumpBackIn by remember { mutableStateOf<List<Song>>(emptyList()) }
        var retroClassics by remember { mutableStateOf<List<Song>>(emptyList()) }
        var activeFilter by remember { mutableStateOf<String?>(null) }
        var isLoading by remember { mutableStateOf(true) }

        val shimmerBrush = rememberShimmerBrush()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when (hour) { in 0..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }

        val activeQuery = activeFilter ?: "Bollywood Hits"

        LaunchedEffect(activeQuery) {
            isLoading = true
            topPicks = repo.searchSongs(activeQuery)
            if (jumpBackIn.isEmpty()) jumpBackIn = repo.searchSongs("Arijit Singh")
            if (retroClassics.isEmpty()) retroClassics = repo.searchSongs("90s Bollywood Classics")
            isLoading = false
        }

        Box(Modifier.fillMaxSize()) {
            // REFINED MULTI-LAYERED AMBIENT GRADIENT
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(580.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0x3DFF2A6D),
                                Color(0x287C4DFF),
                                Color(0x1000E5FF),
                                Color.Transparent
                            ),
                            center = Offset(200f, -80f),
                            radius = 1100f
                        )
                    )
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0x30E91E63),
                                Color(0x12030304),
                                Color.Transparent
                            )
                        )
                    )
            )

            LazyColumn(
                state = scrollState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp, bottom = 180.dp)
            ) {
                item {
                    Column(Modifier.padding(horizontal = 24.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_melo_logo),
                                contentDescription = "Melo Logo",
                                tint = Color.Unspecified,
                                modifier = Modifier.size(75.dp)
                            )
                            IconButton(onClick = onAccountClick, modifier = Modifier.size(44.dp).clip(CircleShape).background(GlassSurface)) {
                                Icon(Icons.Rounded.AccountCircle, contentDescription = "Account", tint = Color(0xFFFFD54F), modifier = Modifier.size(28.dp))
                            }
                        }
                        Spacer(Modifier.height(24.dp))

                        if (isLoggedIn && userName.isNotBlank()) {
                            Text(greeting + ",", fontSize = 26.sp, color = TextMuted, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)
                            Text("$userName.", fontSize = 40.sp, color = TextPure, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                        } else {
                            Text(greeting + ".", fontSize = 38.sp, color = TextPure, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(30.dp))
                    }
                }

                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf(
                            Triple("Chill", Icons.Rounded.AcUnit, Color(0xFF00E5FF)),
                            Triple("Focus", Icons.Rounded.LensBlur, Color(0xFF9B51E0)),
                            Triple("Workout", Icons.Rounded.FitnessCenter, Color(0xFFFF5252)),
                            Triple("Love", Icons.Rounded.Favorite, Color(0xFFFF2A6D)),
                            Triple("Sad", Icons.Rounded.WaterDrop, Color(0xFF64B5F6))
                        ).forEach { (label, icon, color) ->
                            val queryTarget = if (label == "Chill") "Lofi Chill" else "$label Hits"
                            val isSelected = activeFilter == queryTarget

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) color else GlassSurface)
                                        .border(1.dp, if (isSelected) color else Color.Transparent, CircleShape)
                                        .clickable { activeFilter = if (isSelected) null else queryTarget },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(icon, contentDescription = null, tint = if (isSelected) VoidBlack else color)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(label, fontSize = 11.sp, color = if (isSelected) TextPure else TextMuted, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }

                item {
                    AnimatedContent(targetState = isLoading, transitionSpec = { fadeIn(tween(450)) togetherWith fadeOut(tween(250)) }, label = "home_content") { loading ->
                        if (loading) {
                            Column(Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Box(
                                        Modifier
                                            .padding(horizontal = 24.dp)
                                            .width(150.dp)
                                            .height(22.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(shimmerBrush)
                                    )
                                    Spacer(Modifier.height(18.dp))
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            Modifier
                                                .size(115.dp)
                                                .alpha(0.35f)
                                                .clip(RoundedCornerShape(18.dp))
                                                .background(shimmerBrush)
                                        )
                                        Spacer(Modifier.width(14.dp))
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Box(
                                                Modifier
                                                    .size(145.dp)
                                                    .clip(RoundedCornerShape(22.dp))
                                                    .background(shimmerBrush)
                                            )
                                            Spacer(Modifier.height(10.dp))
                                            Box(Modifier.width(120.dp).height(14.dp).clip(RoundedCornerShape(4.dp)).background(shimmerBrush))
                                            Spacer(Modifier.height(6.dp))
                                            Box(Modifier.width(80.dp).height(11.dp).clip(RoundedCornerShape(4.dp)).background(shimmerBrush))
                                        }
                                        Spacer(Modifier.width(14.dp))
                                        Box(
                                            Modifier
                                                .size(115.dp)
                                                .alpha(0.35f)
                                                .clip(RoundedCornerShape(18.dp))
                                                .background(shimmerBrush)
                                        )
                                    }
                                }

                                Spacer(Modifier.height(36.dp))

                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp)
                                        .clip(RoundedCornerShape(26.dp))
                                        .background(Color(0xFF13131B))
                                        .border(BorderStroke(1.dp, Color(0x33FFD700)), RoundedCornerShape(26.dp))
                                        .padding(vertical = 22.dp)
                                ) {
                                    Column {
                                        Row(
                                            Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Box(Modifier.width(135.dp).height(10.dp).clip(RoundedCornerShape(4.dp)).background(shimmerBrush))
                                                Spacer(Modifier.height(8.dp))
                                                Box(Modifier.width(180.dp).height(22.dp).clip(RoundedCornerShape(6.dp)).background(shimmerBrush))
                                            }
                                            Box(Modifier.size(24.dp).clip(CircleShape).background(shimmerBrush))
                                        }
                                        Spacer(Modifier.height(18.dp))
                                        Row(
                                            Modifier.padding(horizontal = 22.dp),
                                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                                        ) {
                                            repeat(3) {
                                                Column(Modifier.width(120.dp)) {
                                                    Box(Modifier.size(120.dp).clip(RoundedCornerShape(18.dp)).background(shimmerBrush))
                                                    Spacer(Modifier.height(8.dp))
                                                    Box(Modifier.width(90.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).background(shimmerBrush))
                                                    Spacer(Modifier.height(4.dp))
                                                    Box(Modifier.width(60.dp).height(10.dp).clip(RoundedCornerShape(4.dp)).background(shimmerBrush))
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(Modifier.height(36.dp))

                                Column(Modifier.fillMaxWidth()) {
                                    Box(
                                        Modifier
                                            .padding(horizontal = 24.dp)
                                            .width(140.dp)
                                            .height(20.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(shimmerBrush)
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Row(
                                        Modifier.padding(horizontal = 24.dp),
                                        horizontalArrangement = Arrangement.spacedBy(18.dp)
                                    ) {
                                        repeat(3) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Box(Modifier.size(105.dp).clip(CircleShape).background(shimmerBrush))
                                                Spacer(Modifier.height(10.dp))
                                                Box(Modifier.width(70.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).background(shimmerBrush))
                                            }
                                        }
                                    }
                                }

                                Spacer(Modifier.height(36.dp))

                                Column(Modifier.fillMaxWidth()) {
                                    Box(
                                        Modifier
                                            .padding(horizontal = 24.dp)
                                            .width(160.dp)
                                            .height(20.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(shimmerBrush)
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Row(
                                        Modifier.padding(horizontal = 24.dp),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        repeat(3) {
                                            Column(Modifier.width(135.dp)) {
                                                Box(Modifier.size(135.dp).clip(RoundedCornerShape(18.dp)).background(shimmerBrush))
                                                Spacer(Modifier.height(8.dp))
                                                Box(Modifier.width(100.dp).height(14.dp).clip(RoundedCornerShape(4.dp)).background(shimmerBrush))
                                                Spacer(Modifier.height(4.dp))
                                                Box(Modifier.width(70.dp).height(11.dp).clip(RoundedCornerShape(4.dp)).background(shimmerBrush))
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Column(Modifier.fillMaxWidth()) {
                                if (topPicks.isNotEmpty()) {
                                    Text(
                                        text = activeFilter ?: "Trending Hits",
                                        fontSize = 20.sp,
                                        color = TextPure,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 24.dp)
                                    )
                                    Spacer(Modifier.height(18.dp))

                                    TrendingHits3DCarousel(
                                        songs = topPicks,
                                        onPlay = { selectedSong -> onPlay(selectedSong, topPicks) }
                                    )

                                    Spacer(Modifier.height(32.dp))
                                }

                                if (becauseList.isNotEmpty()) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp)
                                            .clip(RoundedCornerShape(26.dp))
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(Color(0xFFD4AF37).copy(alpha = 0.35f), Color(0xFF8C6D23).copy(alpha = 0.15f), Color(0xFF030304))
                                                )
                                            )
                                            .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(Color(0xFFFFD700).copy(alpha = 0.5f), Color.Transparent))), RoundedCornerShape(26.dp))
                                            .padding(vertical = 22.dp)
                                    ) {
                                        Column {
                                            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Column {
                                                    Text("BECAUSE YOU LISTENED TO", fontSize = 10.sp, letterSpacing = 2.sp, color = Color(0xFFFFD700), fontWeight = FontWeight.Bold)
                                                    Text(becauseArtist ?: "Favorite Artist", fontSize = 22.sp, color = TextPure, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                                                }
                                                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Color(0xFFFFD700), modifier = Modifier.size(24.dp))
                                            }
                                            Spacer(Modifier.height(16.dp))
                                            LazyRow(contentPadding = PaddingValues(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                                items(becauseList.take(6)) { song ->
                                                    Column(Modifier.width(120.dp).clickable { onPlay(song, becauseList) }) {
                                                        AsyncImage(
                                                            model = song.thumbnailUrl,
                                                            contentDescription = null,
                                                            contentScale = ContentScale.Crop,
                                                            modifier = Modifier.size(120.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, Color(0xFFFFD700).copy(alpha = 0.3f), RoundedCornerShape(18.dp))
                                                        )
                                                        Spacer(Modifier.height(8.dp))
                                                        Text(song.title, fontSize = 13.sp, color = TextPure, fontWeight = FontWeight.Medium, maxLines = 1)
                                                        Text(song.artist, fontSize = 11.sp, color = TextSubtle, maxLines = 1)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(36.dp))
                                }

                                if (jumpBackIn.isNotEmpty()) {
                                    Text("Jump Back In", fontSize = 20.sp, color = TextPure, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp))
                                    Spacer(Modifier.height(16.dp))
                                    LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                        items(jumpBackIn.take(6)) { song ->
                                            Column(Modifier.width(105.dp).clickable { onPlay(song, jumpBackIn) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                                AsyncImage(model = song.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(105.dp).clip(CircleShape).border(1.5.dp, GlassSurface, CircleShape))
                                                Spacer(Modifier.height(10.dp))
                                                Text(song.artist, fontSize = 12.sp, color = TextPure, maxLines = 1, fontWeight = FontWeight.Medium)
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(36.dp))
                                }

                                if (retroClassics.isNotEmpty()) {
                                    Text("Retro Golden Classics", fontSize = 20.sp, color = TextPure, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp))
                                    Spacer(Modifier.height(16.dp))
                                    LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        items(retroClassics.take(6)) { song ->
                                            Column(Modifier.width(135.dp).clickable { onPlay(song, retroClassics) }) {
                                                AsyncImage(model = song.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(135.dp).clip(RoundedCornerShape(18.dp)))
                                                Spacer(Modifier.height(8.dp))
                                                Text(song.title, fontSize = 14.sp, color = TextPure, maxLines = 1, fontWeight = FontWeight.Medium)
                                                Text(song.artist, fontSize = 12.sp, color = TextSubtle, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // COMPACT INFINITE AUTO-SLIDING CAROUSEL (RESILIENT AUTO-SLIDE)
    @Composable
    private fun TrendingHits3DCarousel(
        songs: List<Song>,
        onPlay: (Song) -> Unit
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
                } catch (_: Exception) { }
            }
        }

        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 115.dp),
            pageSpacing = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(205.dp)
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
                    .clickable { onPlay(song) },
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
    // SCENE 02: EXPLORE (VIBRANT & ATMOSPHERIC)
    // ==========================================
    @Composable
    private fun ExploreScene(onPlaySongWithRadio: (Song) -> Unit, repo: JioSaavnRepository) {
        var query by remember { mutableStateOf("") }
        var results by remember { mutableStateOf<List<Song>>(emptyList()) }
        var trendingSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
        var isTrendingLoading by remember { mutableStateOf(true) }
        var isFocused by remember { mutableStateOf(false) }

        val shimmerBrush = rememberShimmerBrush()

        LaunchedEffect(Unit) {
            isTrendingLoading = true
            trendingSongs = repo.searchSongs("Top Global Hits")
            isTrendingLoading = false
        }

        Box(Modifier.fillMaxSize()) {
            // LIVED-IN AMBIENT BACKDROP GLOW
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(520.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0x3000E5FF),
                                Color(0x187C4DFF),
                                Color.Transparent
                            ),
                            center = Offset(300f, 0f),
                            radius = 900f
                        )
                    )
            )

            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp)) {
                val shadowRadius by animateDpAsState(if (isFocused) 18.dp else 0.dp, label = "search_shadow")
                Box(
                    Modifier.fillMaxWidth().shadow(shadowRadius, CircleShape, ambientColor = Color(0xFF00E5FF), spotColor = Color(0xFF00E5FF)).clip(RoundedCornerShape(26.dp)).background(GlassSurface).border(1.dp, if (isFocused) Color(0xFF00E5FF) else GlassSurface, RoundedCornerShape(26.dp)).padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = if (isFocused) Color(0xFF00E5FF) else TextMuted)
                        Spacer(Modifier.width(12.dp))
                        BasicTextField(
                            value = query,
                            onValueChange = { q ->
                                query = q
                                if (q.length > 2) lifecycleScope.launch { results = repo.searchSongs(q) }
                            },
                            textStyle = TextStyle(color = TextPure, fontSize = 15.sp),
                            cursorBrush = SolidColor(Color(0xFF00E5FF)),
                            singleLine = true,
                            modifier = Modifier.weight(1f).onFocusChanged { isFocused = it.isFocused },
                            decorationBox = { inner -> if (query.isEmpty()) Text("Search songs, artists, radio...", color = TextMuted, fontSize = 14.sp) else inner() }
                        )
                        if (query.isNotEmpty()) Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = TextMuted, modifier = Modifier.clickable { query = ""; results = emptyList() })
                    }
                }
                Spacer(Modifier.height(28.dp))

                if (query.isEmpty()) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 180.dp)) {
                        item {
                            Text("Explore", fontSize = 28.sp, color = TextMuted, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)
                            Text("Sonic Landscapes.", fontSize = 36.sp, color = TextPure, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(28.dp))
                        }
                        item {
                            Text("Curated Atmospheres", fontSize = 18.sp, color = TextPure, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(16.dp))
                            Row(Modifier.fillMaxWidth().height(175.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                ExploreAtmosphereCard(
                                    modifier = Modifier.weight(1.05f).fillMaxHeight(),
                                    tag = "VIBE 01",
                                    title = "Pop Odyssey",
                                    subtitle = "Future Nostalgia & Disco",
                                    gradient = listOf(Color(0xFFFF2A6D), Color(0xFF8E24AA)),
                                    vectorType = 1,
                                    onClick = { query = "Pop Hits"; lifecycleScope.launch { results = repo.searchSongs("Pop Hits") } }
                                )
                                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    ExploreAtmosphereCard(
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        tag = "WAVE 02",
                                        title = "Desi Hip-Hop",
                                        subtitle = "Underground Beats",
                                        gradient = listOf(Color(0xFF00E5FF), Color(0xFF0052D4)),
                                        vectorType = 2,
                                        onClick = { query = "Desi Hip Hop"; lifecycleScope.launch { results = repo.searchSongs("Desi Hip Hop") } }
                                    )
                                    ExploreAtmosphereCard(
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        tag = "AFTER HOURS",
                                        title = "Acoustic Gold",
                                        subtitle = "Unplugged Warmth",
                                        gradient = listOf(Color(0xFFFFB300), Color(0xFFFF3D00)),
                                        vectorType = 3,
                                        onClick = { query = "Acoustic Hits"; lifecycleScope.launch { results = repo.searchSongs("Acoustic Hits") } }
                                    )
                                }
                            }
                            Spacer(Modifier.height(36.dp))
                        }

                        item {
                            Text("Trending Frequencies", fontSize = 18.sp, color = TextPure, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(16.dp))
                        }

                        if (isTrendingLoading) {
                            items(6) {
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(DeepSurface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                            itemsIndexed(trendingSongs.take(8)) { index, song ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(DeepSurface).clickable { onPlaySongWithRadio(song) }.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("#%02d".format(index + 1), fontSize = 14.sp, fontWeight = FontWeight.Black, color = if (index < 3) Color(0xFF00E5FF) else TextSubtle, modifier = Modifier.width(36.dp))
                                    AsyncImage(model = song.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(song.title, fontSize = 15.sp, color = TextPure, fontWeight = FontWeight.Bold, maxLines = 1)
                                        Text(song.artist, fontSize = 12.sp, color = TextMuted, maxLines = 1)
                                    }
                                    Icon(Icons.Rounded.Radio, contentDescription = "Start Radio", tint = Color(0xFF00E5FF).copy(alpha = 0.8f))
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 180.dp)) {
                        itemsIndexed(results) { index, song ->
                            val isBestMatch = index == 0
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                    .background(if (isBestMatch) Brush.horizontalGradient(listOf(Color(0xFF003D4D), Color(0xFF0C1929))) else SolidColor(DeepSurface))
                                    .border(1.dp, if (isBestMatch) Color(0xFF00E5FF) else Color.Transparent, RoundedCornerShape(18.dp))
                                    .clickable { onPlaySongWithRadio(song) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(model = song.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(50.dp).clip(RoundedCornerShape(12.dp)))
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, fontSize = 15.sp, color = if (isBestMatch) Color(0xFF00E5FF) else TextPure, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text(song.artist, fontSize = 12.sp, color = TextMuted, maxLines = 1)
                                }
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Start Radio", tint = if (isBestMatch) Color(0xFF00E5FF) else TextMuted)
                            }
                        }
                    }
                }
            }
        }
    }

    // IMPROVED ATMOSPHERE CARD (ICON BOX REMOVED, BEAUTIFIED MESH & TAGS)
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
            // Organic Geometric / Sonic Wave Accents
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
                        drawCircle(
                            color = Color.White.copy(alpha = 0.08f),
                            radius = size.width * 0.35f,
                            center = Offset(size.width * 0.15f, size.height * 0.85f)
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

            // Soft Vignette Scrim
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0x60000000))
                        )
                    )
            )

            // Content Typography
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Subtle Tag Header
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

                // Title & Subtitle
                Column {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        color = TextPure,
                        fontWeight = FontWeight.Black,
                        lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(2.dp))
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

    // ==========================================
    // SCENE 03: MY WORLD (COLLECTIONS)
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
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentPadding = PaddingValues(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp, bottom = 180.dp)) {
            item {
                Text("My World", fontSize = 38.sp, color = TextPure, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Your personalized sonic sanctuary", fontSize = 14.sp, color = TextMuted)
                Spacer(Modifier.height(26.dp))
            }

            item {
                VaultCategoryCard(title = "Liked Songs", subtitle = "$likedCount Frequencies", icon = Icons.Rounded.Favorite, solidColor = Color(0xFFE91E63), onClick = onOpenLiked)
                Spacer(Modifier.height(14.dp))
                VaultCategoryCard(title = "Recently Played", subtitle = "$recentCount Past Echoes", icon = Icons.Rounded.History, solidColor = Color(0xFF0288D1), onClick = onOpenRecent)
                Spacer(Modifier.height(14.dp))
                VaultCategoryCard(title = "Downloads", subtitle = "$downloadCount Offline Tracks", icon = Icons.Rounded.DownloadDone, solidColor = Color(0xFF00897B), onClick = onOpenDownloads)
                Spacer(Modifier.height(34.dp))
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Collections", fontSize = 20.sp, color = TextPure, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onCreatePlaylist, modifier = Modifier.size(42.dp).clip(CircleShape).background(Color(0xFFFFD54F).copy(alpha = 0.2f))) {
                        Icon(Icons.Rounded.Add, contentDescription = "Create", tint = Color(0xFFFFD54F), modifier = Modifier.size(26.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            val chunked = playlists.chunked(2)
            items(chunked) { rowItems ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    rowItems.forEach { pl ->
                        Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(22.dp)).clickable { onOpenPlaylist(NavRoute.PlaylistDetail(pl.name, pl.coverUrl, pl.songs)) }) {
                            AsyncImage(model = pl.coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000)))))
                            Text(pl.name, fontSize = 15.sp, color = TextPure, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomStart).padding(14.dp))
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
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f)),
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

        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = TextPure) }
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
                AsyncImage(model = selectedCoverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
                decorationBox = { inner -> if (name.isEmpty()) Text("Collection Title", color = TextMuted, fontSize = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) else inner() }
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

    @Composable
    private fun PlaylistDetailScene(route: NavRoute.PlaylistDetail, onPlay: (Song, List<Song>) -> Unit, onDelete: () -> Unit, onBack: () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(320.dp)) {
                AsyncImage(model = route.cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, VoidBlack))))
                Row(Modifier.fillMaxWidth().padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp, start = 16.dp, end = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = TextPure, modifier = Modifier.size(30.dp)) }
                    IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete Playlist", tint = MeloAccent, modifier = Modifier.size(30.dp)) }
                }
                Column(Modifier.align(Alignment.BottomStart).padding(24.dp)) {
                    Text(route.name, fontSize = 36.sp, color = TextPure, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("${route.songs.size} Frequencies", fontSize = 14.sp, color = TextMuted)
                }
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 16.dp, bottom = 40.dp)) {
                if (route.songs.isEmpty()) item { Text("No frequencies added yet.", color = TextMuted) }
                items(route.songs) { song ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(DeepSurface).clickable { onPlay(song, route.songs) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(model = song.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title, fontSize = 15.sp, color = TextPure, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text(song.artist, fontSize = 12.sp, color = TextMuted, maxLines = 1)
                        }
                    }
                }
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
                .background(
                    Brush.verticalGradient(
                        listOf(
                            bgColorTop,
                            bgColorTop.copy(alpha = 0.55f),
                            VoidBlack
                        )
                    )
                )
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
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.15f))
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
                                    modifier = Modifier
                                        .size(50.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                )
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, fontSize = 15.sp, color = TextPure, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text(song.artist, fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
                                }
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
                .background(
                    Brush.verticalGradient(
                        listOf(
                            emeraldTop,
                            emeraldTop.copy(alpha = 0.55f),
                            VoidBlack
                        )
                    )
                )
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
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.15f))
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
                                    modifier = Modifier
                                        .size(50.dp)
                                        .clip(RoundedCornerShape(14.dp))
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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ImmersivePlayerScene(
        song: Song,
        artworkColor: Color,
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

        LaunchedEffect(song.id) { lyrics = repository.getSyncedLyrics(song.title, song.artist) }

        Box(
            Modifier
                .fillMaxSize()
                .background(VoidBlack)
        ) {
            val infinite = rememberInfiniteTransition(label = "blob_infinite")
            val offsetX1 by infinite.animateFloat(initialValue = -80f, targetValue = 80f, animationSpec = infiniteRepeatable(animation = tween(7000, easing = LinearEasing), repeatMode = RepeatMode.Reverse), label = "offsetX1")
            val offsetY1 by infinite.animateFloat(initialValue = -90f, targetValue = 90f, animationSpec = infiniteRepeatable(animation = tween(6000, easing = LinearEasing), repeatMode = RepeatMode.Reverse), label = "offsetY1")
            val offsetX2 by infinite.animateFloat(initialValue = 80f, targetValue = -80f, animationSpec = infiniteRepeatable(animation = tween(8500, easing = LinearEasing), repeatMode = RepeatMode.Reverse), label = "offsetX2")

            Box(Modifier.fillMaxSize().blur(80.dp).alpha(0.92f)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(color = artworkColor, radius = size.width * 0.44f, center = Offset(size.width / 2 + offsetX1, size.height / 3 + offsetY1))
                    drawCircle(color = BlobColor1, radius = size.width * 0.40f, center = Offset(size.width / 2 + offsetX2, size.height / 1.6f - offsetY1))
                    drawCircle(color = BlobColor3, radius = size.width * 0.32f, center = Offset(size.width / 3 - offsetX1, size.height / 2))
                }
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, VoidBlack.copy(alpha = 0.35f), VoidBlack.copy(alpha = 0.85f)))))

            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCollapse) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = TextPure, modifier = Modifier.size(32.dp)) }
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Rounded.MoreHoriz, contentDescription = null, tint = TextPure, modifier = Modifier.size(28.dp)) }
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AnimatedContent(targetState = activeTab, transitionSpec = { (fadeIn(tween(350)) + scaleIn(initialScale = 0.96f)) togetherWith (fadeOut(tween(350)) + scaleOut(targetScale = 0.96f)) }, label = "player_content") { tab ->
                        if (tab == "Player") {
                            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                val artScale by animateFloatAsState(targetValue = if (isPlaying) 1f else 0.88f, animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f), label = "art_scale")
                                AsyncImage(
                                    model = song.thumbnailUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(290.dp).scale(artScale).clip(RoundedCornerShape(32.dp)).shadow(36.dp, RoundedCornerShape(32.dp), ambientColor = artworkColor, spotColor = artworkColor)
                                )
                            }
                        } else {
                            val listState = rememberLazyListState()
                            val activeLyricIndex = remember(lyrics, currentPositionMs) {
                                lyrics.indexOfLast { (it.time * 1000f) <= (currentPositionMs + 250f) }
                            }

                            LaunchedEffect(activeLyricIndex) {
                                if (activeLyricIndex in lyrics.indices) listState.animateScrollToItem(activeLyricIndex, scrollOffset = -220)
                            }

                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp), contentPadding = PaddingValues(top = 30.dp, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                                if (lyrics.isEmpty()) item { Text("Instrumental / Pure Atmosphere", color = TextMuted, fontFamily = FontFamily.SansSerif, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                                itemsIndexed(lyrics) { index, line ->
                                    val isActive = index == activeLyricIndex
                                    val scale by animateFloatAsState(if (isActive) 1.08f else 0.96f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow), label = "lyric_scale")
                                    val alpha by animateFloatAsState(if (isActive) 1f else 0.22f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow), label = "lyric_alpha")
                                    Text(
                                        text = line.text,
                                        fontFamily = FontFamily.SansSerif,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 30.sp,
                                        color = if (isActive) TextPure else TextMuted,
                                        lineHeight = 38.sp,
                                        softWrap = true,
                                        textAlign = TextAlign.Start,
                                        modifier = Modifier.fillMaxWidth().graphicsLayer { transformOrigin = TransformOrigin(0f, 0.5f); scaleX = scale; scaleY = scale; this.alpha = alpha }.clickable { onSeek(line.time * 1000f) }
                                    )
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = activeTab == "Player", enter = fadeIn(), exit = fadeOut()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 18.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(song.title, fontSize = 24.sp, color = TextPure, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.basicMarquee())
                                Spacer(Modifier.height(4.dp))
                                Text(song.artist, fontSize = 15.sp, color = TextMuted, maxLines = 1)
                            }
                            IconButton(onClick = onLikeToggle) { Icon(if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, contentDescription = null, tint = if (isLiked) MeloAccent else TextPure, modifier = Modifier.size(28.dp)) }
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
                                val safeTarget = (seekPercent * songDurationMs).coerceAtMost(songDurationMs - 1800f)
                                onSeek(safeTarget)
                            }
                        )
                        Spacer(Modifier.height(20.dp))

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
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
                            IconButton(onClick = onPrev) { Icon(Icons.Rounded.SkipPrevious, contentDescription = null, tint = TextPure, modifier = Modifier.size(42.dp)) }
                            val interactionSource = remember { MutableInteractionSource() }
                            val isPressed by interactionSource.collectIsPressedAsState()
                            val scale by animateFloatAsState(if (isPressed) 0.92f else 1f, animationSpec = spring(dampingRatio = 0.6f), label = "play_scale")
                            Box(
                                Modifier.size(76.dp).scale(scale).clip(CircleShape).background(TextPure).clickable(interactionSource = interactionSource, indication = null) { onTogglePlay() },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isBuffering) CircularProgressIndicator(color = VoidBlack, modifier = Modifier.size(24.dp))
                                else Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null, tint = VoidBlack, modifier = Modifier.size(44.dp))
                            }
                            IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, contentDescription = null, tint = TextPure, modifier = Modifier.size(42.dp)) }
                            IconButton(onClick = { showQueue = true }) { Icon(Icons.Rounded.QueueMusic, contentDescription = "Queue", tint = TextMuted, modifier = Modifier.size(28.dp)) }
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

                        val isDownloaded = downloadProgress == -1f
                        val isDownloading = downloadProgress > 0f && downloadProgress < 1f
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = if (isDownloaded) "Downloaded" else if (isDownloading) "Downloading (${(downloadProgress * 100).roundToInt()}%)" else "Download Track",
                                    color = if (isDownloaded) Color(0xFF00E676) else TextPure,
                                    fontWeight = if (isDownloaded) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            leadingContent = {
                                if (isDownloaded) {
                                    Box(Modifier.size(26.dp).clip(CircleShape).background(Color(0xFF00E676)), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.Check, contentDescription = null, tint = VoidBlack, modifier = Modifier.size(16.dp))
                                    }
                                } else if (isDownloading) {
                                    CircularProgressIndicator(progress = { downloadProgress }, modifier = Modifier.size(24.dp), color = Color(0xFF00E5FF), strokeWidth = 2.5.dp)
                                } else {
                                    Icon(Icons.Rounded.Download, tint = Color(0xFFFFD54F), contentDescription = null)
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { if (!isDownloaded && !isDownloading) onDownloadClick() }
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
                                        AsyncImage(model = pl.coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
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

            AnimatedVisibility(visible = showQueue, enter = slideInVertically(initialOffsetY = { it }), exit = slideOutVertically(targetOffsetY = { it })) {
                Box(Modifier.fillMaxSize().background(VoidBlack)) {
                    val infiniteMesh = rememberInfiniteTransition(label = "queue_mesh")
                    val qOffsetX by infiniteMesh.animateFloat(initialValue = -90f, targetValue = 90f, animationSpec = infiniteRepeatable(animation = tween(7000, easing = LinearEasing), repeatMode = RepeatMode.Reverse), label = "qOffset")
                    Box(Modifier.fillMaxSize().blur(80.dp).alpha(0.85f)) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawCircle(color = artworkColor, radius = size.width * 0.45f, center = Offset(size.width / 2 + qOffsetX, size.height / 3))
                            drawCircle(color = BlobColor1, radius = size.width * 0.40f, center = Offset(size.width / 2 - qOffsetX, size.height / 1.6f))
                        }
                    }
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, VoidBlack.copy(alpha = 0.4f), VoidBlack.copy(alpha = 0.85f)))))

                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp)) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Up Next Radio", fontSize = 24.sp, color = TextPure, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                            IconButton(onClick = { showQueue = false }) { Icon(Icons.Rounded.Close, contentDescription = "Close", tint = TextPure, modifier = Modifier.size(28.dp)) }
                        }

                        Text("Now Resonating", fontSize = 14.sp, color = artworkColor, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0x28FFFFFF))
                                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(16.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(model = song.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.title, fontSize = 15.sp, color = TextPure, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(song.artist, fontSize = 13.sp, color = Color(0xCCFFFFFF), maxLines = 1)
                            }
                            Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = artworkColor)
                        }

                        Spacer(Modifier.height(20.dp))
                        Text("Slide right to eject from queue", fontSize = 12.sp, color = TextSubtle)
                        Spacer(Modifier.height(10.dp))

                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 60.dp)) {
                            itemsIndexed(queue.drop(currentQueueIndex + 1), key = { index, item -> "${item.id}_$index" }) { _, qSong ->
                                SwipeToDismissSongRow(
                                    song = qSong,
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
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TextMuted, fontSize = 14.sp)
            Text(value, color = TextPure, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }

    @Composable
    private fun SwipeToDismissSongRow(
        song: Song,
        isLiked: Boolean,
        onSongClick: () -> Unit,
        onLikeToggle: () -> Unit,
        onDismiss: () -> Unit
    ) {
        val offsetX = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        var isDismissed by remember { mutableStateOf(false) }

        if (!isDismissed) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFE53935).copy(alpha = 0.5f))
            ) {
                Icon(
                    Icons.Rounded.DeleteSweep,
                    contentDescription = "Dismiss",
                    tint = TextPure,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 20.dp)
                )

                val cardAlpha = (1f - (offsetX.value / 600f).coerceIn(0f, 1f))

                Row(
                    Modifier
                        .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                        .alpha(cardAlpha)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x28FFFFFF))
                        .border(1.dp, Color(0x20FFFFFF), RoundedCornerShape(16.dp))
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
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = song.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(50.dp).clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, fontSize = 15.sp, color = TextPure, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(song.artist, fontSize = 12.sp, color = Color(0xDDFFFFFF), maxLines = 1)
                    }
                    IconButton(onClick = onLikeToggle) {
                        Icon(
                            if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = null,
                            tint = if (isLiked) MeloAccent else TextMuted,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun CinematicLandscapeScene(song: Song, artworkColor: Color, isPlaying: Boolean, currentPositionMs: Float, onExit: () -> Unit, onTogglePlay: () -> Unit, onSeek: (Float) -> Unit, repository: JioSaavnRepository) {
        var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
        LaunchedEffect(song.id) { lyrics = repository.getSyncedLyrics(song.title, song.artist) }

        Box(
            Modifier
                .fillMaxSize()
                .background(VoidBlack)
        ) {
            val infinite = rememberInfiniteTransition(label = "cinematic_blob")
            val offsetX1 by infinite.animateFloat(initialValue = -150f, targetValue = 150f, animationSpec = infiniteRepeatable(animation = tween(8000, easing = LinearEasing), repeatMode = RepeatMode.Reverse), label = "cinematic_offsetX")
            Box(Modifier.fillMaxSize().blur(90.dp).alpha(0.85f)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(color = artworkColor, radius = size.height * 0.70f, center = Offset(size.width / 3 + offsetX1, size.height / 2))
                    drawCircle(color = BlobColor1, radius = size.height * 0.70f, center = Offset(size.width / 1.5f - offsetX1, size.height / 2))
                }
            }
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(VoidBlack.copy(alpha = 0.3f), VoidBlack.copy(alpha = 0.8f)))))

            Row(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(0.75f).fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.Start) {
                    IconButton(onClick = onExit) { Icon(Icons.Rounded.FullscreenExit, contentDescription = null, tint = TextPure, modifier = Modifier.size(32.dp)) }
                    Spacer(Modifier.height(18.dp))
                    AsyncImage(model = song.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(220.dp).clip(RoundedCornerShape(26.dp)).shadow(32.dp, ambientColor = artworkColor, spotColor = artworkColor))
                    Spacer(Modifier.height(18.dp))
                    Text(song.title, fontSize = 26.sp, color = TextPure, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, maxLines = 1)
                    Text(song.artist, fontSize = 15.sp, color = TextMuted, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, maxLines = 1)
                    Spacer(Modifier.height(18.dp))
                    IconButton(onClick = onTogglePlay) { Icon(if (isPlaying) Icons.Rounded.PauseCircle else Icons.Rounded.PlayCircle, contentDescription = null, tint = TextPure, modifier = Modifier.size(56.dp)) }
                }

                val listState = rememberLazyListState()
                val activeLyricIndex = remember(lyrics, currentPositionMs) {
                    lyrics.indexOfLast { (it.time * 1000f) <= (currentPositionMs + 250f) }
                }

                LaunchedEffect(activeLyricIndex) {
                    if (activeLyricIndex in lyrics.indices) listState.animateScrollToItem(activeLyricIndex, scrollOffset = -150)
                }

                LazyColumn(state = listState, modifier = Modifier.weight(1.25f).fillMaxHeight().padding(start = 36.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    if (lyrics.isEmpty()) item { Text("Instrumental Resonance", color = TextMuted, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                    itemsIndexed(lyrics) { index, line ->
                        val isActive = index == activeLyricIndex
                        val scale by animateFloatAsState(if (isActive) 1.06f else 0.95f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow), label = "cinematic_scale")
                        val alpha by animateFloatAsState(if (isActive) 1f else 0.22f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow), label = "cinematic_alpha")
                        Text(
                            line.text,
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Black,
                            fontSize = 36.sp,
                            color = TextPure,
                            lineHeight = 44.sp,
                            softWrap = true,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth().graphicsLayer { transformOrigin = TransformOrigin(0f, 0.5f); scaleX = scale; scaleY = scale; this.alpha = alpha }.clickable { onSeek(line.time * 1000f) }
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
                val phase1 by infinite.animateFloat(initialValue = 0f, targetValue = 2f * Math.PI.toFloat(), animationSpec = infiniteRepeatable(animation = tween(2200, easing = LinearEasing)), label = "phase1")
                val phase2 by infinite.animateFloat(initialValue = 0f, targetValue = 2f * Math.PI.toFloat(), animationSpec = infiniteRepeatable(animation = tween(1600, easing = LinearEasing)), label = "phase2")

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
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp),
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
                    Text(song.title, fontSize = 15.sp, color = TextPure, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.basicMarquee())
                    Text(song.artist, fontSize = 12.sp, color = TextSubtle, maxLines = 1)
                }
                IconButton(onClick = onPrev) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = null, tint = TextPure, modifier = Modifier.size(28.dp))
                }
                IconButton(onClick = onTogglePlay) {
                    if (isBuffering) CircularProgressIndicator(color = TextPure, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    else Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null, tint = TextPure, modifier = Modifier.size(34.dp))
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = null, tint = TextPure, modifier = Modifier.size(28.dp))
                }
            }
        }
    }

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
        var dragProgress by remember { mutableFloatStateOf(0f) }
        val infinite = rememberInfiniteTransition(label = "scrubber")
        val phase by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 2f * Math.PI.toFloat(),
            animationSpec = infiniteRepeatable(animation = tween(1500, easing = LinearEasing)),
            label = "scrubber_phase"
        )
        val displayProgress = (if (isDragging) dragProgress else progress).coerceIn(0f, 1f)

        Column(modifier = modifier) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            if (size.width > 0) {
                                onUserSeekingChange(true)
                                val seekPercent = (offset.x / size.width.toFloat()).coerceIn(0f, 0.98f)
                                onSeekProgress(seekPercent)
                                onUserSeekingChange(false)
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                isDragging = true
                                onUserSeekingChange(true)
                                dragProgress = (offset.x / size.width.toFloat()).coerceIn(0f, 0.98f)
                            },
                            onDragEnd = {
                                isDragging = false
                                onSeekProgress(dragProgress)
                                onUserSeekingChange(false)
                            },
                            onDragCancel = {
                                isDragging = false
                                onUserSeekingChange(false)
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                dragProgress = (change.position.x / size.width.toFloat()).coerceIn(0f, 0.98f)
                            }
                        )
                    }
            ) {
                val centerY = size.height / 2
                drawLine(GlassSurface, Offset(0f, centerY), Offset(size.width, centerY), strokeWidth = 12f, cap = StrokeCap.Round)
                val fillWidth = size.width * displayProgress
                val path = Path()
                for (x in 0..fillWidth.toInt() step 4) {
                    val y = centerY + sin((x * 0.08f) - phase) * 6f
                    if (x == 0) path.moveTo(0f, y) else path.lineTo(x.toFloat(), y)
                }
                drawPath(path, accentColor, style = Stroke(width = 12f))

                drawCircle(Color(0x55000000), radius = if (isDragging) 28f else 22f, center = Offset(fillWidth, centerY))
                drawCircle(Color(0xFF0D0D11), radius = if (isDragging) 22f else 18f, center = Offset(fillWidth, centerY))
                drawCircle(TextPure, radius = if (isDragging) 18f else 14f, center = Offset(fillWidth, centerY))
                drawCircle(accentColor, radius = if (isDragging) 10f else 7f, center = Offset(fillWidth, centerY))
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime((displayProgress * totalDurationMs).toLong()), fontSize = 11.sp, color = TextMuted, fontWeight = FontWeight.Medium)
                Text(formatTime(totalDurationMs.toLong()), fontSize = 11.sp, color = TextMuted, fontWeight = FontWeight.Medium)
            }
        }
    }

    private fun formatTime(millis: Long): String {
        val totalSecs = (millis / 1000).coerceAtLeast(0)
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return "%d:%02d".format(mins, secs)
    }

    // REFINED, FROSTED GLASS CAPSULE DESIGN (BALANCED SIZE & SUBTLE SOFT GLOW)
    @Composable
    private fun MeloBottomNav(
        selectedTab: MeloTab,
        onSelectTab: (MeloTab) -> Unit,
        modifier: Modifier = Modifier
    ) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(32.dp))
                .background(Color(0x88030304))
                .border(
                    BorderStroke(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(Color(0x40FFFFFF), Color(0x12FFFFFF))
                        )
                    ),
                    RoundedCornerShape(32.dp)
                )
                .padding(4.dp)
        ) {
            Row(
                Modifier
                    .width(270.dp)
                    .height(52.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(DeepSurface),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MeloTab.entries.forEach { tab ->
                    val active = selectedTab == tab
                    val animatedBgColor by animateColorAsState(
                        targetValue = if (active) tab.activeTint.copy(alpha = 0.20f) else Color.Transparent,
                        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                        label = "tabBgColor"
                    )
                    val iconScale by animateFloatAsState(
                        targetValue = if (active) 1.08f else 1.0f,
                        animationSpec = spring(dampingRatio = 0.75f),
                        label = "iconScale"
                    )

                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(22.dp))
                            .background(animatedBgColor)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onSelectTab(tab) }
                            .padding(vertical = 4.dp, horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                tint = if (active) tab.activeTint else TextMuted,
                                modifier = Modifier
                                    .size(20.dp)
                                    .graphicsLayer {
                                        scaleX = iconScale
                                        scaleY = iconScale
                                    }
                            )
                            AnimatedVisibility(
                                visible = active,
                                enter = fadeIn(tween(180)) + expandHorizontally(spring(dampingRatio = 0.8f)),
                                exit = fadeOut(tween(150)) + shrinkHorizontally(spring(dampingRatio = 0.8f))
                            ) {
                                Row {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = tab.label,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = tab.activeTint,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}