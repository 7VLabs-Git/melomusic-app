// File: app/src/main/java/com/melomusic/app/MainActivity.kt
package com.melomusic.app

import com.melomusic.app.R
import com.melomusic.app.data.AuthResult
import com.melomusic.app.data.CloudVaultSnapshot
import com.melomusic.app.data.SupabaseSyncManager
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
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.util.LruCache
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
import androidx.compose.ui.graphics.BlendMode
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.melomusic.app.data.JioSaavnRepository
import com.melomusic.app.data.LyricLine
import com.melomusic.app.data.MeloVaultDatabase
import com.melomusic.app.data.Song
import com.melomusic.app.data.VaultEntryEntity
import com.melomusic.app.playback.MusicService
import com.melomusic.app.playback.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.cos
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
    object AuthPortal : NavRoute()
    object ProfilePortal : NavRoute()
    object CreatePlaylist : NavRoute()
    data class PlaylistDetail(val name: String, val cover: String, val songs: List<Song>) : NavRoute()
    object LikedSongs : NavRoute()
    object RecentlyPlayed : NavRoute()
    object Downloads : NavRoute()
}

data class UserPlaylist(
    var name: String,
    var coverUrl: String,
    val songs: MutableList<Song> = mutableListOf()
)

const val MODE_SHUFFLE = 0
const val MODE_REPEAT_ALL = 1
const val MODE_REPEAT_ONE = 2
private const val UPDATE_NOTIFICATION_ID = 5050
private const val UPDATE_CHANNEL_ID = "melo_updates_channel"

@UnstableApi
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class
)
class MainActivity : ComponentActivity() {

    private lateinit var player: PlayerConnection
    private val saavnRepository by lazy { JioSaavnRepository(this) }
    private val vaultDb by lazy { MeloVaultDatabase.getInstance(this) }

    private val supabaseSync by lazy { SupabaseSyncManager(this, httpClient, gson) }
    private var cloudSyncDebounceJob: Job? = null

    private fun scheduleCloudVaultSync() {
        if (!supabaseSync.isLoggedIn) return
        cloudSyncDebounceJob?.cancel()
        cloudSyncDebounceJob = lifecycleScope.launch {
            delay(1200) // Debounce rapid likes/edits into a single atomic cloud push
            supabaseSync.pushCloudVault(
                CloudVaultSnapshot(
                    displayName = userProfileName.orEmpty(),
                    likedSongs = likedSongsList.toList(),
                    playlists = userPlaylists.toList(),
                    recentSongs = recentlyPlayedList.toList(),
                    savedQueue = currentQueue.take(40).toList()
                )
            )
        }
    }

    private fun applyCloudVaultToLocal(vault: CloudVaultSnapshot) {
        userProfileName = vault.displayName.ifBlank { null }
        prefs.edit().putString("user_profile_name", userProfileName).apply()

        likedSongsList.clear()
        likedSongsList.addAll(vault.likedSongs)
        saveVaultPayloadAsync("liked_songs", likedSongsList.toList())

        userPlaylists.clear()
        userPlaylists.addAll(vault.playlists)
        saveVaultPayloadAsync("user_playlists", userPlaylists.toList())

        recentlyPlayedList.clear()
        recentlyPlayedList.addAll(vault.recentSongs)
        saveVaultPayloadAsync("recent_songs", recentlyPlayedList.toList())

        if (vault.savedQueue.isNotEmpty() && currentQueue.isEmpty()) {
            currentQueue.addAll(vault.savedQueue)
            currentQueueIndex = 0
            persistQueueState()
        }
    }

    private fun performFullLogout() {
        supabaseSync.signOut()
        userProfileName = null
        likedSongsList.clear()
        userPlaylists.clear()
        recentlyPlayedList.clear()
        prefs.edit()
            .remove("user_profile_name")
            .putString("liked_songs", "[]")
            .putString("user_playlists", "[]")
            .putString("recent_songs", "[]")
            .apply()
        saveVaultPayloadAsync("liked_songs", emptyList<Song>())
        saveVaultPayloadAsync("user_playlists", emptyList<UserPlaylist>())
        saveVaultPayloadAsync("recent_songs", emptyList<Song>())
        Toast.makeText(this, "Signed out of Sanctuary Vault", Toast.LENGTH_SHORT).show()
    }
    private val httpClient = OkHttpClient.Builder().followRedirects(true).build()
    private val gson = Gson()
    private lateinit var prefs: SharedPreferences

    // In-memory LRU cache for extracted artwork palette colors
    private val artworkPaletteCache = LruCache<String, Pair<Color, Color>>(60)

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val userPlaylists = mutableStateListOf<UserPlaylist>()
    private val likedSongsList = mutableStateListOf<Song>()
    private val recentlyPlayedList = mutableStateListOf<Song>()
    private val downloadedSongsList = mutableStateListOf<Song>()
    private val playbackHistory = mutableListOf<Song>()
    private val downloadProgressMap = mutableStateMapOf<String, Float>()

    // Hoisted Home Feed Caches so switching tabs is 0ms and never re-fetches unnecessarily
    private var cachedTopPicks by mutableStateOf<List<Song>>(emptyList())
    private var cachedDhhHits by mutableStateOf<List<Song>>(emptyList())
    private var cachedPunjabiWave by mutableStateOf<List<Song>>(emptyList())
    private var cachedJumpBackIn by mutableStateOf<List<Song>>(emptyList())
    private var cachedLofiSanctuary by mutableStateOf<List<Song>>(emptyList())
    private var cachedIndieAcoustic by mutableStateOf<List<Song>>(emptyList())
    private var cachedGlobalCharts by mutableStateOf<List<Song>>(emptyList())
    private var cachedRetroClassics by mutableStateOf<List<Song>>(emptyList())
    private var cachedHomeFilter by mutableStateOf<String?>(null)
    private var isHomeFeedLoaded by mutableStateOf(false)

    private var currentQueue = mutableStateListOf<Song>()
    private var currentQueueIndex by mutableIntStateOf(0)

    // Isolated position & duration states — read ONLY via lambdas in leaf composables!
    private var currentPlaybackPositionMs by mutableFloatStateOf(0f)
    private var currentTrackDurationMs by mutableFloatStateOf(0f)
    private var isUserSeeking by mutableStateOf(false)
    private var playMode by mutableIntStateOf(MODE_REPEAT_ALL)
    private var userPausedManually = false


    private var userProfileName by mutableStateOf<String?>(null)
    private var currentRoute by mutableStateOf<NavRoute>(NavRoute.Main)
    private var updateVersion by mutableStateOf<String?>(null)
    private var updateNotes by mutableStateOf("")
    private var updateUrl by mutableStateOf("")
    private var isCheckingUpdate by mutableStateOf(false)
    private var showUpdateSheetState by mutableStateOf(false)
    private var downloadId by mutableLongStateOf(-1L)

    private var wakeLock: PowerManager.WakeLock? = null
    private var backgroundPlaybackJob: Job? = null
    private var activeMoodOrMixJob: Job? = null

    private val currentAppVersionName: String
        get() = "v${BuildConfig.VERSION_NAME.removePrefix("v")}"

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
        userProfileName = prefs.getString("user_profile_name", null)
        loadPersistedStateAsync()

        createUpdateNotificationChannel()
        ContextCompat.registerReceiver(
            this,
            updateReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )

        MusicService.onSkipNextRequested = {
            lifecycleScope.launch(Dispatchers.Main.immediate) { playNext() }
        }
        MusicService.onSkipPreviousRequested = {
            lifecycleScope.launch(Dispatchers.Main.immediate) { playPrev() }
        }

        player = PlayerConnection(this, lifecycleScope)
        player.connect {
            lifecycleScope.launch(Dispatchers.IO) {
                val lastSongJson = vaultDb.vaultDao().getPayload("last_active_song")
                    ?: prefs.getString("last_active_song", null)
                val fallbackSong = if (!lastSongJson.isNullOrBlank()) {
                    runCatching { gson.fromJson(lastSongJson, Song::class.java) }.getOrNull()
                } else {
                    withContext(Dispatchers.Main) {
                        currentQueue.getOrNull(currentQueueIndex) ?: recentlyPlayedList.firstOrNull()
                    }
                }
                if (fallbackSong != null) {
                    withContext(Dispatchers.Main) {
                        player.restoreLastSongIfIdle(fallbackSong)
                    }
                }
            }
        }

        startBackgroundPlaybackTracker()
        checkGithubForUpdates(silentBackgroundCheck = true)

        if (intent?.getBooleanExtra("OPEN_UPDATE_SHEET", false) == true) {
            showUpdateSheetState = true
        }
        handleGoogleOAuthRedirect(intent)

        setContent { MeloRoot() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("OPEN_UPDATE_SHEET", false)) {
            showUpdateSheetState = true
        }
        handleGoogleOAuthRedirect(intent)
    }

    private fun handleGoogleOAuthRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "melomusic" && data.host == "auth-callback") {
            val fragment = data.fragment.orEmpty()
            val params = fragment.split("&").associate {
                val parts = it.split("=", limit = 2)
                parts.getOrElse(0) { "" } to Uri.decode(parts.getOrElse(1) { "" })
            }
            val accessToken = params["access_token"] ?: data.getQueryParameter("access_token")
            val refreshToken = params["refresh_token"] ?: data.getQueryParameter("refresh_token")
            if (!accessToken.isNullOrBlank()) {
                intent.data = null
                lifecycleScope.launch {
                    val localSnapshot = CloudVaultSnapshot(
                        displayName = userProfileName.orEmpty(),
                        likedSongs = likedSongsList.toList(),
                        playlists = userPlaylists.toList(),
                        recentSongs = recentlyPlayedList.toList(),
                        savedQueue = currentQueue.take(40).toList()
                    )
                    val result = supabaseSync.completeOAuthSignIn(accessToken, refreshToken, localSnapshot)
                    withContext(Dispatchers.Main) {
                        if (result is AuthResult.Success) {
                            result.vault?.let { applyCloudVaultToLocal(it) }
                            if (userProfileName.isNullOrBlank() && result.displayName.isNotBlank()) {
                                userProfileName = result.displayName
                                prefs.edit().putString("user_profile_name", userProfileName).apply()
                            }
                            currentRoute = NavRoute.Main
                            Toast.makeText(this@MainActivity, "Signed in with Google!", Toast.LENGTH_SHORT).show()
                        } else if (result is AuthResult.Error) {
                            Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        backgroundPlaybackJob?.cancel()
        activeMoodOrMixJob?.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        unregisterReceiver(updateReceiver)
        MusicService.onSkipNextRequested = null
        MusicService.onSkipPreviousRequested = null
        player.release()
        super.onDestroy()
    }

    private var lastAutoAdvancedSongId: String? = null

    private fun startBackgroundPlaybackTracker() {
        backgroundPlaybackJob?.cancel()
        backgroundPlaybackJob = lifecycleScope.launch(Dispatchers.Default) {
            player.state.collect { playbackState ->
                withContext(Dispatchers.Main.immediate) {
                    if (!isUserSeeking) {
                        currentPlaybackPositionMs = playbackState.positionMs.toFloat()
                    }
                    if (playbackState.durationMs > 0L) {
                        currentTrackDurationMs = playbackState.durationMs.toFloat()
                    }

                    // Keep WakeLock alive while playing, buffering, OR transitioning at track end
                    val shouldHoldWakeLock = playbackState.isPlaying ||
                            playbackState.isBuffering ||
                            (playbackState.isEnded && !userPausedManually)

                    if (shouldHoldWakeLock) {
                        if (wakeLock?.isHeld == false) {
                            try { wakeLock?.acquire(60 * 60 * 1000L) } catch (_: Exception) {}
                        }
                    } else if (wakeLock?.isHeld == true) {
                        try { wakeLock?.release() } catch (_: Exception) {}
                    }

                    val activeId = playbackState.song?.id
                    if (playbackState.isPlaying && activeId != null && playbackState.positionMs in 100..2500) {
                        // Reset guard once the new track is actively playing
                        if (lastAutoAdvancedSongId == activeId) {
                            lastAutoAdvancedSongId = null
                        }
                    }

                    // Trigger auto-advance even when screen is locked
                    val nearEnd = playbackState.durationMs > 15_000L &&
                            playbackState.positionMs >= (playbackState.durationMs - 450L)

                    if ((playbackState.isEnded || (nearEnd && !playbackState.isPlaying)) &&
                        !playbackState.isBuffering &&
                        !userPausedManually &&
                        activeId != null &&
                        lastAutoAdvancedSongId != activeId
                    ) {
                        lastAutoAdvancedSongId = activeId
                        playNext()
                    }
                }
            }
        }
    }

    // Off-main-thread Room + SharedPreferences fallback loader
    private fun loadPersistedStateAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dao = vaultDb.vaultDao()

                suspend fun readJson(key: String, defaultVal: String): String {
                    val fromRoom = dao.getPayload(key)
                    if (!fromRoom.isNullOrBlank()) return fromRoom
                    val fromPrefs = prefs.getString(key, null)
                    if (!fromPrefs.isNullOrBlank()) {
                        dao.putPayload(VaultEntryEntity(key, fromPrefs))
                        return fromPrefs
                    }
                    return defaultVal
                }

                val likedJson = readJson("liked_songs", "[]")
                val liked: List<Song> = gson.fromJson(likedJson, object : TypeToken<List<Song>>() {}.type) ?: emptyList()

                val recentJson = readJson("recent_songs", "[]")
                val recent: List<Song> = gson.fromJson(recentJson, object : TypeToken<List<Song>>() {}.type) ?: emptyList()

                val dlJson = readJson("downloaded_songs", "[]")
                val downloaded: List<Song> = gson.fromJson(dlJson, object : TypeToken<List<Song>>() {}.type) ?: emptyList()
                val validDownloaded = downloaded.mapNotNull { s ->
                    val resolvedPath = resolvePlayableUrl(s)
                    if (resolvedPath != null) {
                        s.localPath = resolvedPath
                        s
                    } else null
                }

                val savedQueueJson = readJson("saved_queue", "[]")
                val qList: List<Song> = gson.fromJson(savedQueueJson, object : TypeToken<List<Song>>() {}.type) ?: emptyList()
                val savedIdx = prefs.getInt("saved_queue_index", 0)

                val plJson = readJson("user_playlists", "[]")
                val plList: List<UserPlaylist> = gson.fromJson(plJson, object : TypeToken<List<UserPlaylist>>() {}.type) ?: emptyList()

                withContext(Dispatchers.Main) {
                    likedSongsList.clear()
                    likedSongsList.addAll(liked)

                    recentlyPlayedList.clear()
                    recentlyPlayedList.addAll(recent)

                    downloadedSongsList.clear()
                    downloadedSongsList.addAll(validDownloaded)
                    validDownloaded.forEach { downloadProgressMap[it.id] = -1f }

                    if (qList.isNotEmpty() && currentQueue.isEmpty()) {
                        currentQueue.addAll(qList)
                        currentQueueIndex = savedIdx.coerceIn(0, qList.lastIndex)
                    }

                    userPlaylists.clear()
                    userPlaylists.addAll(plList)
                }
                if (supabaseSync.isLoggedIn) {
                    lifecycleScope.launch {
                        supabaseSync.fetchCloudVault()?.let { remoteVault ->
                            applyCloudVaultToLocal(remoteVault)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveVaultPayloadAsync(key: String, dataSnapshot: Any) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val json = gson.toJson(dataSnapshot)
                vaultDb.vaultDao().putPayload(VaultEntryEntity(key, json))
                prefs.edit().putString(key, json).apply()
            }
        }
    }

    private fun persistLikedSongs() {
        saveVaultPayloadAsync("liked_songs", likedSongsList.toList())
        scheduleCloudVaultSync()
    }
    private fun persistRecentSongs() {
        saveVaultPayloadAsync("recent_songs", recentlyPlayedList.toList())
        scheduleCloudVaultSync()
    }
    private fun persistDownloadedSongs() = saveVaultPayloadAsync("downloaded_songs", downloadedSongsList.toList())
    fun persistUserPlaylists() {
        saveVaultPayloadAsync("user_playlists", userPlaylists.toList())
        scheduleCloudVaultSync()
    }

    private fun persistQueueState(activeSong: Song? = currentQueue.getOrNull(currentQueueIndex)) {
        val queueSnapshot = currentQueue.take(40).toList()
        val idxSnapshot = currentQueueIndex
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val qJson = gson.toJson(queueSnapshot)
                vaultDb.vaultDao().putPayload(VaultEntryEntity("saved_queue", qJson))
                val editor = prefs.edit()
                    .putString("saved_queue", qJson)
                    .putInt("saved_queue_index", idxSnapshot)
                if (activeSong != null) {
                    val songJson = gson.toJson(activeSong)
                    vaultDb.vaultDao().putPayload(VaultEntryEntity("last_active_song", songJson))
                    editor.putString("last_active_song", songJson)
                }
                editor.apply()
            }
        }
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

    // Snapshot-safe playlist mutation helper so Compose UI always recomposes immediately
    private fun updatePlaylistInState(targetName: String, transform: (UserPlaylist) -> UserPlaylist) {
        val idx = userPlaylists.indexOfFirst { it.name == targetName }
        if (idx != -1) {
            userPlaylists[idx] = transform(userPlaylists[idx])
            persistUserPlaylists()
        }
    }

    private fun addSongToPlaylist(playlist: UserPlaylist, song: Song) {
        updatePlaylistInState(playlist.name) { current ->
            val updatedSongs = current.songs.toMutableList()
            if (updatedSongs.none { it.id == song.id }) {
                updatedSongs.add(song)
            }
            val updatedCover = current.coverUrl.ifBlank { song.thumbnailUrl }
            current.copy(coverUrl = updatedCover, songs = updatedSongs)
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
                    val uriStr = Uri.fromFile(f).toString()
                    song.localPath = uriStr
                    return uriStr
                }
            }
        }
        return null
    }

    private fun downloadSongInternally(song: Song) {
        if (downloadProgressMap[song.id] == -1f || downloadProgressMap[song.id] != null) return
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

                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                    val body = resp.body ?: throw Exception("Empty stream body")
                    val totalLength = body.contentLength()

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var read: Int
                            var currentBytes = 0L
                            var lastEmittedProgress = 0f
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                currentBytes += read
                                if (totalLength > 0) {
                                    val progress = (currentBytes.toFloat() / totalLength.toFloat()).coerceIn(0.01f, 0.99f)
                                    // Throttle UI progress updates to >= 2% increments to avoid main-thread flooding
                                    if (progress - lastEmittedProgress >= 0.02f) {
                                        lastEmittedProgress = progress
                                        withContext(Dispatchers.Main) {
                                            downloadProgressMap[song.id] = progress
                                        }
                                    }
                                }
                            }
                            output.flush()
                        }
                    }
                }

                if (tempFile.length() < 5000) {
                    tempFile.delete()
                    throw Exception("Corrupted download file")
                }

                if (targetFile.exists()) targetFile.delete()
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()

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
        currentTrackDurationMs = parseDurationMs(seedSong.durationText)
        userPausedManually = false

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
                persistQueueState(seedSong)
            }
        }
    }

    private fun launchMoodOrMixRadio(query: String, label: String) {
        activeMoodOrMixJob?.cancel()
        activeMoodOrMixJob = lifecycleScope.launch {
            val tracks = saavnRepository.searchSongs(query)
            if (tracks.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    playSongWithSmartRadio(tracks.first(), label)
                }
            }
        }
    }

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
        persistQueueState()
        Toast.makeText(this, "Playing next: ${song.title}", Toast.LENGTH_SHORT).show()
    }

    private fun appendToQueue(song: Song) {
        if (currentQueue.isEmpty()) {
            playSongWithSmartRadio(song)
            return
        }
        if (currentQueue.none { it.id == song.id }) {
            currentQueue.add(song)
            persistQueueState()
        }
        Toast.makeText(this, "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
    }

    private fun startArtistRadio(artistName: String) {
        val cleanArtist = artistName.split(",", "&", "/").firstOrNull()?.trim() ?: artistName
        if (cleanArtist.isBlank()) return
        Toast.makeText(this, "Starting $cleanArtist Radio...", Toast.LENGTH_SHORT).show()
        activeMoodOrMixJob?.cancel()
        activeMoodOrMixJob = lifecycleScope.launch {
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
        currentTrackDurationMs = parseDurationMs(song.durationText)
        userPausedManually = false

        persistQueueState(song)

        player.play(song) { target ->
            resolvePlayableUrl(target) ?: saavnRepository.getStreamUrl(target)
        }

        if (currentQueue.size - currentQueueIndex <= 3 && !isRefillingQueue) {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val hasNetwork = cm?.activeNetwork != null
            if (hasNetwork) {
                isRefillingQueue = true
                lifecycleScope.launch {
                    val nextBatch = saavnRepository.getNextEndlessBatch()
                    withContext(Dispatchers.Main) {
                        if (nextBatch.isNotEmpty()) {
                            val existingIds = currentQueue.map { it.id }.toSet()
                            currentQueue.addAll(nextBatch.filter { it.id !in existingIds })
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
            currentTrackDurationMs = parseDurationMs(previousSong.durationText)
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

    private fun isRemoteVersionNewer(currentVer: String, remoteTag: String): Boolean {
        val cleanCurrent = currentVer.removePrefix("v").trim().split(".").map { it.toIntOrNull() ?: 0 }
        val cleanRemote = remoteTag.removePrefix("v").trim().split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(cleanCurrent.size, cleanRemote.size)
        for (i in 0 until maxLen) {
            val c = cleanCurrent.getOrElse(i) { 0 }
            val r = cleanRemote.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

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
                httpClient.newCall(req).execute().use { resp ->
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
                        if (tag != null && isRemoteVersionNewer(currentAppVersionName, tag)) {
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

        var showAccountSheet by remember { mutableStateOf(false) }
        var showImportSheet by remember { mutableStateOf(false) }
        var selectedSongForOptions by remember { mutableStateOf<Song?>(null) }

        var extractedArtworkColor by remember { mutableStateOf(Color(0xFFFF5252)) }
        var extractedSecondaryColor by remember { mutableStateOf(Color(0xFF00E5FF)) }

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
                    val reco = saavnRepository.searchSongs("$artistName Hits")
                    if (reco.isNotEmpty()) becauseRecommendations = reco
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
                        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                        label = "tab"
                    ) { tab ->
                        when (tab) {
                            MeloTab.HOME -> HomeScene(
                                onPlay = { s, q -> startSongPlayback(s, q) },
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
                                onPlaySongTap = { song -> playSongWithSmartRadio(song) },
                                onSongOptions = { selectedSongForOptions = it },
                                repo = saavnRepository
                            )
                            MeloTab.LIBRARY -> MyWorldScene(
                                likedSongs = likedSongsList,
                                recentSongs = recentlyPlayedList,
                                downloadedSongs = downloadedSongsList,
                                playlists = userPlaylists,
                                onOpenLiked = { currentRoute = NavRoute.LikedSongs },
                                onOpenRecent = { currentRoute = NavRoute.RecentlyPlayed },
                                onOpenDownloads = { currentRoute = NavRoute.Downloads },
                                onCreatePlaylist = { currentRoute = NavRoute.CreatePlaylist },
                                onImportPlaylist = { showImportSheet = true },
                                onOpenPlaylist = { currentRoute = it },
                                onPlayCollection = { list ->
                                    if (list.isNotEmpty()) startSongPlayback(list.first(), list)
                                }
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
                                onUpdatePlaylist = { newName, newCover, newSongs ->
                                    updatePlaylistInState(targetPlaylist.name) {
                                        UserPlaylist(newName, newCover, newSongs.toMutableList())
                                    }
                                    currentRoute = NavRoute.PlaylistDetail(newName, newCover, newSongs)
                                },
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
                        is NavRoute.AuthPortal -> MeloAuthMasterpieceScene(
                            onBack = { currentRoute = NavRoute.Main },
                            onAuthSuccess = { snapshot ->
                                applyCloudVaultToLocal(snapshot)
                                currentRoute = NavRoute.Main
                            }
                        )
                        is NavRoute.ProfilePortal -> MeloProfileMasterpieceScene(
                            onBack = { currentRoute = NavRoute.Main },
                            onSignInRequest = { currentRoute = NavRoute.AuthPortal },
                            onCheckUpdates = {
                                showUpdateSheetState = true
                                checkGithubForUpdates(silentBackgroundCheck = false)
                            }
                        )
                    }
                }
            }

            val isKeyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            val miniPlayerBottomPadding =
                if (isKeyboardOpen) 10.dp else if (currentRoute == NavRoute.Main) 86.dp else 16.dp

            AnimatedVisibility(
                visible = playerState.song != null && playerTier == PlayerTier.MINI &&
                        currentRoute != NavRoute.AuthPortal && currentRoute != NavRoute.ProfilePortal,
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
                        secondaryColor = smoothSecondaryColor,
                        isPlaying = playerState.isPlaying,
                        isBuffering = playerState.isBuffering,
                        progressProvider = {
                            val total = if (currentTrackDurationMs > 0f) currentTrackDurationMs else parseDurationMs(song.durationText)
                            (currentPlaybackPositionMs / total).coerceIn(0f, 1f)
                        },
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
                        currentPositionProvider = { currentPlaybackPositionMs },
                        totalDurationProvider = {
                            if (currentTrackDurationMs > 0f) currentTrackDurationMs
                            else parseDurationMs(song.durationText)
                        },
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
                                persistQueueState()
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
                        currentPositionProvider = { currentPlaybackPositionMs },
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
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF14141A))
                                    .border(
                                        width = 1.dp,
                                        color = Color.White.copy(alpha = 0.08f),
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                    .clickable {
                                        showAccountSheet = false
                                        if (supabaseSync.isLoggedIn || userProfileName != null) {
                                            currentRoute = NavRoute.ProfilePortal
                                        } else {
                                            currentRoute = NavRoute.AuthPortal
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFFCD01).copy(alpha = 0.14f))
                                        .border(1.dp, Color(0xFFFFCD01).copy(alpha = 0.35f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (supabaseSync.isLoggedIn) Icons.Rounded.VerifiedUser else Icons.Rounded.PersonOutline,
                                        tint = Color(0xFFFFCD01),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Spacer(Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    val firstNameOnly = userProfileName?.trim()?.split("\\s+".toRegex())?.firstOrNull()
                                    Text(
                                        text = if (!firstNameOnly.isNullOrBlank()) firstNameOnly else "Account",
                                        color = TextPure,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = (-0.2).sp
                                    )
                                    if (supabaseSync.isLoggedIn && !supabaseSync.currentUserEmail.isNullOrBlank()) {
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = supabaseSync.currentUserEmail.orEmpty(),
                                            color = TextMuted,
                                            fontSize = 12.sp
                                        )
                                    }
                                }

                                Icon(
                                    imageVector = Icons.Rounded.ArrowForwardIos,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
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
                            supportingContent = { Text("Current build: $currentAppVersionName", color = TextMuted) },
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
                                            httpClient.newCall(headReq).execute().use { headResp ->
                                                targetUrl = headResp.request.url.toString()
                                            }
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
                                                httpClient.newCall(oembedReq).execute().use { res ->
                                                    val oembedResp = res.body?.string() ?: ""
                                                    val oembedJson = JsonParser.parseString(oembedResp).asJsonObject
                                                    playlistTitle = oembedJson.get("title")?.asString ?: playlistTitle
                                                    playlistCover = oembedJson.get("thumbnail_url")?.asString ?: playlistCover
                                                }
                                            } catch (_: Exception) {}

                                            if (spotifyId != null) {
                                                val embedUrl = "https://open.spotify.com/embed/$type/$spotifyId"
                                                val embedReq = Request.Builder()
                                                    .url(embedUrl)
                                                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                                    .header("Accept-Language", "en-US,en;q=0.9")
                                                    .build()
                                                val respHtml = httpClient.newCall(embedReq).execute().use { it.body?.string() ?: "" }

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
                                    Text("Melo $currentAppVersionName is the newest release", fontSize = 14.sp, color = TextMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

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
        artworkPaletteCache.get(imageUrl)?.let { (cachedPrimary, cachedSecondary) ->
            return Triple(cachedPrimary, cachedSecondary, null)
        }
        return withContext(Dispatchers.IO) {
            try {
                val loader = context.imageLoader
                val request = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .size(96, 96)
                    .bitmapConfig(Bitmap.Config.RGB_565)
                    .allowHardware(false)
                    .build()
                val result = (loader.execute(request) as? SuccessResult)?.drawable
                val bitmap = (result as? BitmapDrawable)?.bitmap
                if (bitmap != null) {
                    var bestColor1 = android.graphics.Color.DKGRAY
                    var bestColor2 = android.graphics.Color.parseColor("#00E5FF")
                    var highestScore1 = -1f
                    var highestScore2 = -1f
                    val hsv = FloatArray(3)
                    val stepX = (bitmap.width / 16).coerceAtLeast(1)
                    val stepY = (bitmap.height / 16).coerceAtLeast(1)

                    for (x in 0 until bitmap.width step stepX) {
                        for (y in 0 until bitmap.height step stepY) {
                            val pixel = bitmap.getPixel(x, y)
                            android.graphics.Color.colorToHSV(pixel, hsv)
                            val sat = hsv[1]
                            val value = hsv[2]
                            if (sat > 0.15f && value > 0.15f) {
                                val score = sat * 2.2f + (1.0f - abs(value - 0.65f))
                                if (score > highestScore1) {
                                    highestScore2 = highestScore1
                                    bestColor2 = bestColor1
                                    highestScore1 = score
                                    bestColor1 = pixel
                                } else if (score > highestScore2 && abs(pixel - bestColor1) > 50000) {
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
                    val boosted1 = Color(android.graphics.Color.HSVToColor(hsv))

                    android.graphics.Color.colorToHSV(bestColor2, hsv)
                    if (hsv[2] < 0.60f) hsv[2] = 0.70f
                    if (hsv[1] < 0.45f) hsv[1] = 0.60f
                    val boosted2 = Color(android.graphics.Color.HSVToColor(hsv))

                    artworkPaletteCache.put(imageUrl, boosted1 to boosted2)
                    Triple(boosted1, boosted2, bitmap)
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
    // SCENE 01: HOME + TRUE BOREALIS RIBBON AURORA
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
        val infinite = rememberInfiniteTransition(label = "living_gemini_aurora")

        val colorCycle by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 15000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "colorCycle"
        )

        val breathPrimary by infinite.animateFloat(
            initialValue = 0.84f,
            targetValue = 1.30f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathPrimary"
        )
        val breathSecondary by infinite.animateFloat(
            initialValue = 1.26f,
            targetValue = 0.82f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathSecondary"
        )

        val orbitPhase by infinite.animateFloat(
            initialValue = 0f,
            targetValue = (2f * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 10000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "orbitPhase"
        )

        // HSV-locked interpolation so colors NEVER lose saturation during transitions
        fun sampleVividSpectrum(palette: List<Color>, phase: Float): Color {
            val scaled = (phase % 1f) * palette.size
            val idx = scaled.toInt() % palette.size
            val nextIdx = (idx + 1) % palette.size
            val frac = scaled - scaled.toInt()
            val blended = androidx.compose.ui.graphics.lerp(palette[idx], palette[nextIdx], frac)

            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(
                android.graphics.Color.argb(
                    255,
                    (blended.red * 255).toInt(),
                    (blended.green * 255).toInt(),
                    (blended.blue * 255).toInt()
                ),
                hsv
            )
            // Force high saturation and max luminance so mid-transitions stay hyper-vivid
            hsv[1] = hsv[1].coerceAtLeast(0.88f)
            hsv[2] = hsv[2].coerceAtLeast(0.96f)
            return Color(android.graphics.Color.HSVToColor(hsv))
        }

        val crownPalette = remember {
            listOf(
                Color(0xFF0055FF), // Hyper Cobalt Blue
                Color(0xFF00F0FF), // Pure Electric Cyan
                Color(0xFFB829FF), // Ultra Neon Violet
                Color(0xFFFF1E56), // Vivid Crimson-Rose
                Color(0xFFFFBE00), // Saturated Golden Amber
                Color(0xFF00E676)  // Luminous Aurora Emerald
            )
        }
        val wingPalette = remember {
            listOf(
                Color(0xFF00E5FF), // Electric Aqua
                Color(0xFF7C3AED), // Deep Vivid Purple
                Color(0xFFFF007F), // Hyper Magenta
                Color(0xFFFF8800), // Radiant Orange-Gold
                Color(0xFF76FF03), // Electric Lime
                Color(0xFF2979FF)  // Vivid Sapphire
            )
        }

        val colorA = sampleVividSpectrum(crownPalette, colorCycle)
        val colorB = sampleVividSpectrum(wingPalette, (colorCycle + 0.33f) % 1f)
        val colorC = sampleVividSpectrum(crownPalette, (colorCycle + 0.66f) % 1f)

        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(500.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(64.dp) // Tighter blur keeps saturation punchy while staying smooth
            ) {
                val w = size.width
                val h = size.height

                // Orb 1: High-Intensity Top-Left Crown
                val c1 = Offset(
                    x = w * (0.22f + 0.22f * sin(orbitPhase)),
                    y = h * (0.04f + 0.08f * cos(orbitPhase * 2f))
                )
                val r1 = w * 0.88f * breathPrimary
                drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to colorA.copy(alpha = 0.96f),
                        0.38f to colorA.copy(alpha = 0.68f),
                        0.72f to colorB.copy(alpha = 0.26f),
                        1.0f to Color.Transparent,
                        center = c1,
                        radius = r1
                    ),
                    radius = r1,
                    center = c1,
                    blendMode = BlendMode.Screen
                )

                // Orb 2: High-Saturation Top-Right Wing
                val c2 = Offset(
                    x = w * (0.80f + 0.18f * cos(orbitPhase)),
                    y = h * (0.12f + 0.09f * sin(orbitPhase))
                )
                val r2 = w * 0.85f * breathSecondary
                drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to colorB.copy(alpha = 0.92f),
                        0.42f to colorC.copy(alpha = 0.54f),
                        0.82f to Color.Transparent,
                        center = c2,
                        radius = r2
                    ),
                    radius = r2,
                    center = c2,
                    blendMode = BlendMode.Screen
                )

                // Orb 3: Center Atmospheric Core
                val c3 = Offset(
                    x = w * (0.50f - 0.16f * sin(orbitPhase)),
                    y = h * (0.20f - 0.06f * cos(orbitPhase))
                )
                val r3 = w * 0.70f * ((breathPrimary + breathSecondary) * 0.5f)
                drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to colorC.copy(alpha = 0.82f),
                        0.48f to colorA.copy(alpha = 0.38f),
                        1.0f to Color.Transparent,
                        center = c3,
                        radius = r3
                    ),
                    radius = r3,
                    center = c3,
                    blendMode = BlendMode.Screen
                )
            }

            // Vignette melt into VoidBlack
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.50f to Color.Transparent,
                            0.80f to VoidBlack.copy(alpha = 0.80f),
                            1.0f to VoidBlack
                        )
                    )
            )
        }
    }

    @Composable
    private fun HomeScene(
        onPlay: (Song, List<Song>) -> Unit,
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
        var isLoading by remember { mutableStateOf(!isHomeFeedLoaded) }
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

        val activeQuery = cachedHomeFilter ?: "Top Trending Hindi Hits 2025"

        LaunchedEffect(activeQuery) {
            if (!isHomeFeedLoaded || cachedTopPicks.isEmpty()) {
                isLoading = true
            }
            coroutineScope {
                val topDeferred = async { repo.searchSongs(activeQuery) }
                val dhhDeferred = if (cachedDhhHits.isEmpty()) async { repo.searchSongs("Seedhe Maut Divine KRSNA Hits") } else null
                val punjabiDeferred = if (cachedPunjabiWave.isEmpty()) async { repo.searchSongs("Karan Aujla Diljit Dosanjh Shubh") } else null
                val jumpDeferred = if (cachedJumpBackIn.isEmpty()) async { repo.searchSongs("Arijit Singh Atif Aslam KK") } else null
                val lofiDeferred = if (cachedLofiSanctuary.isEmpty()) async { repo.searchSongs("Bollywood Lofi Chill Beats") } else null
                val indieDeferred = if (cachedIndieAcoustic.isEmpty()) async { repo.searchSongs("Anuv Jain Prateek Kuhad Indie") } else null
                val globalDeferred = if (cachedGlobalCharts.isEmpty()) async { repo.searchSongs("The Weeknd Post Malone Popular") } else null
                val retroDeferred = if (cachedRetroClassics.isEmpty()) async { repo.searchSongs("Kishore Kumar 90s Bollywood Classics") } else null

                val fetchedTop = topDeferred.await()
                if (fetchedTop.isNotEmpty()) cachedTopPicks = fetchedTop
                dhhDeferred?.await()?.let { cachedDhhHits = it }
                punjabiDeferred?.await()?.let { cachedPunjabiWave = it }
                jumpDeferred?.await()?.let { cachedJumpBackIn = it }
                lofiDeferred?.await()?.let { cachedLofiSanctuary = it }
                indieDeferred?.await()?.let { cachedIndieAcoustic = it }
                globalDeferred?.await()?.let { cachedGlobalCharts = it }
                retroDeferred?.await()?.let { cachedRetroClassics = it }
            }
            isHomeFeedLoaded = true
            isLoading = false
        }

        Box(Modifier.fillMaxSize()) {
            GeminiAuroraHeader()

            LazyColumn(
                state = scrollState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp,
                    bottom = 180.dp
                )
            ) {
                item {
                    Column(Modifier.padding(horizontal = 24.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = com.melomusic.app.R.drawable.ic_melo_logo),
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
                            val firstName = userName.trim().split("\\s+".toRegex()).firstOrNull().orEmpty()
                            Text("$greeting,", fontSize = 24.sp, color = TextMuted, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)
                            Text("$firstName.", fontSize = 38.sp, color = TextPure, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                        } else {
                            Text("$greeting.", fontSize = 36.sp, color = TextPure, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }

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
                            val isSelected = cachedHomeFilter == queryTarget
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) color else Color.White.copy(alpha = 0.10f))
                                        .border(1.dp, if (isSelected) color else Color.White.copy(alpha = 0.16f), CircleShape)
                                        .clickable {
                                            if (isSelected) {
                                                cachedHomeFilter = null
                                            } else {
                                                cachedHomeFilter = queryTarget
                                                launchMoodOrMixRadio(queryTarget, label)
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
                                val quickGridSongs = remember(recentSongs.size, cachedTopPicks, cachedDhhHits) {
                                    (recentSongs + cachedTopPicks + cachedDhhHits).distinctBy { it.id }.take(6)
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

                                if (cachedTopPicks.isNotEmpty()) {
                                    Text(
                                        text = if (cachedHomeFilter != null) "Mood Spotlight" else "Trending Now",
                                        fontSize = 20.sp,
                                        color = TextPure,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 24.dp)
                                    )
                                    Spacer(Modifier.height(18.dp))
                                    TrendingHits3DCarousel(
                                        songs = cachedTopPicks,
                                        onPlay = { selectedSong -> onPlay(selectedSong, cachedTopPicks) },
                                        onLongPress = { selectedSong -> onSongOptions(selectedSong) }
                                    )
                                    Spacer(Modifier.height(32.dp))
                                }

                                Text(
                                    text = "Made For You Mixes",
                                    fontSize = 20.sp,
                                    color = TextPure,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 24.dp)
                                )
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
                                                    launchMoodOrMixRadio(mix.query, mix.title)
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
                                                items(becauseList.take(8), key = { it.id }) { song ->
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

                                if (cachedDhhHits.isNotEmpty()) {
                                    HomeSongShelf(
                                        title = "Desi Hip-Hop & Rap",
                                        subtitle = "Raw bars & heavy basslines",
                                        songs = cachedDhhHits,
                                        onPlay = { s -> onPlay(s, cachedDhhHits) },
                                        onLongPress = { s -> onSongOptions(s) }
                                    )
                                    Spacer(Modifier.height(32.dp))
                                }

                                if (cachedPunjabiWave.isNotEmpty()) {
                                    HomeSongShelf(
                                        title = "Punjabi Chartbusters",
                                        subtitle = "Non-stop club & drive anthems",
                                        songs = cachedPunjabiWave,
                                        onPlay = { s -> onPlay(s, cachedPunjabiWave) },
                                        onLongPress = { s -> onSongOptions(s) }
                                    )
                                    Spacer(Modifier.height(32.dp))
                                }

                                if (cachedJumpBackIn.isNotEmpty()) {
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
                                        items(cachedJumpBackIn.take(8), key = { it.id }) { song ->
                                            Column(
                                                Modifier
                                                    .width(105.dp)
                                                    .combinedClickable(
                                                        onClick = { onPlay(song, cachedJumpBackIn) },
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

                                if (cachedLofiSanctuary.isNotEmpty()) {
                                    HomeSongShelf(
                                        title = "Late Night Lo-Fi & Chill",
                                        subtitle = "Slowed, reverb & midnight study vibes",
                                        songs = cachedLofiSanctuary,
                                        onPlay = { s -> onPlay(s, cachedLofiSanctuary) },
                                        onLongPress = { s -> onSongOptions(s) }
                                    )
                                    Spacer(Modifier.height(32.dp))
                                }

                                if (cachedIndieAcoustic.isNotEmpty()) {
                                    HomeSongShelf(
                                        title = "Indie & Acoustic Sanctuary",
                                        subtitle = "Warm guitars & heartfelt storytelling",
                                        songs = cachedIndieAcoustic,
                                        onPlay = { s -> onPlay(s, cachedIndieAcoustic) },
                                        onLongPress = { s -> onSongOptions(s) }
                                    )
                                    Spacer(Modifier.height(32.dp))
                                }

                                if (cachedGlobalCharts.isNotEmpty()) {
                                    HomeSongShelf(
                                        title = "Global Viral Hits",
                                        subtitle = "Worldwide chart-toppers",
                                        songs = cachedGlobalCharts,
                                        onPlay = { s -> onPlay(s, cachedGlobalCharts) },
                                        onLongPress = { s -> onSongOptions(s) }
                                    )
                                    Spacer(Modifier.height(32.dp))
                                }

                                if (cachedRetroClassics.isNotEmpty()) {
                                    HomeSongShelf(
                                        title = "Retro Golden Classics",
                                        subtitle = "Timeless 70s, 80s & 90s melodies",
                                        songs = cachedRetroClassics,
                                        onPlay = { s -> onPlay(s, cachedRetroClassics) },
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
    // SCENE 02: EXPLORE
    // ==========================================
    @Composable
    private fun ExploreScene(
        onPlaySongTap: (Song) -> Unit,
        onSongOptions: (Song) -> Unit,
        repo: JioSaavnRepository
    ) {
        val focusManager = LocalFocusManager.current
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

        BackHandler(enabled = query.isNotEmpty() || isFocused) {
            if (query.isNotEmpty()) {
                query = ""
                results = emptyList()
                isSearching = false
            }
            focusManager.clearFocus()
        }

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
                                    tag = "PULSE",
                                    title = "Pop Odyssey",
                                    subtitle = "High-gloss global synth",
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
                                        tag = "CYPHER",
                                        title = "Desi Hip-Hop",
                                        subtitle = "",
                                        gradient = listOf(Color(0xFF00E5FF), Color(0xFF0052D4)),
                                        vectorType = 2,
                                        onClick = { query = "Desi Hip Hop" }
                                    )
                                    ExploreAtmosphereCard(
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        tag = "UNPLUGGED",
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
                if (tag.isNotBlank()) {
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
                } else {
                    Spacer(Modifier.height(4.dp))
                }

                Column {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        color = TextPure,
                        fontWeight = FontWeight.Black,
                        lineHeight = 20.sp
                    )
                    if (subtitle.isNotBlank()) {
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
    }

    // ==========================================
    // SCENE 03: REDESIGNED "MY WORLD" SANCTUARY
    // ==========================================
    @Composable
    private fun MyWorldScene(
        likedSongs: List<Song>,
        recentSongs: List<Song>,
        downloadedSongs: List<Song>,
        playlists: List<UserPlaylist>,
        onOpenLiked: () -> Unit,
        onOpenRecent: () -> Unit,
        onOpenDownloads: () -> Unit,
        onCreatePlaylist: () -> Unit,
        onImportPlaylist: () -> Unit,
        onOpenPlaylist: (NavRoute.PlaylistDetail) -> Unit,
        onPlayCollection: (List<Song>) -> Unit
    ) {
        var filterQuery by remember { mutableStateOf("") }
        val filteredPlaylists = remember(playlists.toList(), filterQuery) {
            if (filterQuery.isBlank()) playlists
            else playlists.filter { it.name.contains(filterQuery.trim(), ignoreCase = true) }
        }

        Box(Modifier.fillMaxSize()) {
            // Subtle Golden-Amber & Violet Sanctuary Atmosphere
            Canvas(Modifier.fillMaxWidth().height(420.dp)) {
                drawRect(
                    brush = Brush.verticalGradient(
                        0.0f to Color(0xFF1F170B),
                        0.50f to Color(0xFF0C0A10),
                        1.0f to VoidBlack
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to Color(0xFFFFD54F).copy(alpha = 0.18f),
                        0.55f to Color(0xFF9B51E0).copy(alpha = 0.06f),
                        1.0f to Color.Transparent,
                        center = Offset(size.width * 0.85f, size.height * 0.18f),
                        radius = size.width * 0.75f
                    ),
                    center = Offset(size.width * 0.85f, size.height * 0.18f),
                    radius = size.width * 0.75f,
                    blendMode = BlendMode.Screen
                )
            }

            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 22.dp),
                contentPadding = PaddingValues(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 18.dp,
                    bottom = 180.dp
                )
            ) {
                // 1. Header & Quick Actions
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "MY SANCTUARY",
                                fontSize = 10.sp,
                                letterSpacing = 2.2.sp,
                                color = Color(0xFFFFD54F),
                                fontWeight = FontWeight.Black
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "My World",
                                fontSize = 36.sp,
                                color = TextPure,
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            IconButton(
                                onClick = onImportPlaylist,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                            ) {
                                Icon(Icons.Rounded.CloudDownload, contentDescription = "Import Spotify", tint = Color(0xFF00E5FF), modifier = Modifier.size(20.dp))
                            }
                            IconButton(
                                onClick = onCreatePlaylist,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFFD54F))
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = "Create Playlist", tint = VoidBlack, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                }

                // 2. Sonic Footprint Glass Telemetry Strip
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0x14FFFFFF))
                            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(20.dp))
                            .padding(vertical = 14.dp, horizontal = 18.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SanctuaryStatPill(count = likedSongs.size.toString(), label = "Liked", accent = Color(0xFFFF4081))
                        Box(Modifier.width(1.dp).height(26.dp).background(GlassSurface))
                        SanctuaryStatPill(count = downloadedSongs.size.toString(), label = "Offline", accent = Color(0xFF00E676))
                        Box(Modifier.width(1.dp).height(26.dp).background(GlassSurface))
                        SanctuaryStatPill(count = playlists.size.toString(), label = "Collections", accent = Color(0xFFFFD54F))
                        Box(Modifier.width(1.dp).height(26.dp).background(GlassSurface))
                        SanctuaryStatPill(count = recentSongs.size.toString(), label = "Echoes", accent = Color(0xFF00E5FF))
                    }
                    Spacer(Modifier.height(22.dp))
                }

                // 3. Bento Grid Vault Cards (Liked, Offline Downloads, Recently Played)
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Featured Full-Width Hero Bento Card: Liked Songs
                        BentoVaultHeroCard(
                            title = "Liked Frequencies",
                            subtitle = if (likedSongs.isEmpty()) "Tap the heart on any track" else "${likedSongs.size} curated favorites",
                            previewSongs = likedSongs.take(3),
                            icon = Icons.Rounded.Favorite,
                            accentColor = Color(0xFFFF4081),
                            gradientColors = listOf(Color(0xFF4A0E2E), Color(0xFF1A0918), DeepSurface),
                            onClick = onOpenLiked,
                            onQuickPlay = { onPlayCollection(likedSongs) }
                        )

                        // Dual Bento Row: Offline Downloads & Recently Played
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            BentoVaultHalfCard(
                                modifier = Modifier.weight(1f),
                                title = "Downloads",
                                subtitle = "${downloadedSongs.size} offline tracks",
                                icon = Icons.Rounded.DownloadDone,
                                accentColor = Color(0xFF00E676),
                                gradientColors = listOf(Color(0xFF073B32), Color(0xFF0A1414)),
                                latestArt = downloadedSongs.firstOrNull()?.thumbnailUrl,
                                onClick = onOpenDownloads,
                                onQuickPlay = { onPlayCollection(downloadedSongs) }
                            )
                            BentoVaultHalfCard(
                                modifier = Modifier.weight(1f),
                                title = "Recent Echoes",
                                subtitle = "${recentSongs.size} played tracks",
                                icon = Icons.Rounded.History,
                                accentColor = Color(0xFF00E5FF),
                                gradientColors = listOf(Color(0xFF082E4A), Color(0xFF0A1018)),
                                latestArt = recentSongs.firstOrNull()?.thumbnailUrl,
                                onClick = onOpenRecent,
                                onQuickPlay = { onPlayCollection(recentSongs) }
                            )
                        }
                    }
                    Spacer(Modifier.height(30.dp))
                }

                // 4. Collections Header + Search Filter
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Your Collections", fontSize = 20.sp, color = TextPure, fontWeight = FontWeight.Bold)
                            Text("Custom playlists & imported sets", fontSize = 12.sp, color = TextMuted)
                        }
                        TextButton(onClick = onCreatePlaylist) {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("New", color = Color(0xFFFFD54F), fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    if (playlists.size > 2) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(DeepSurface)
                                .border(1.dp, GlassSurface, RoundedCornerShape(16.dp))
                                .padding(horizontal = 14.dp, vertical = 11.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Search, contentDescription = null, tint = TextSubtle, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                BasicTextField(
                                    value = filterQuery,
                                    onValueChange = { filterQuery = it },
                                    singleLine = true,
                                    textStyle = TextStyle(color = TextPure, fontSize = 14.sp),
                                    cursorBrush = SolidColor(Color(0xFFFFD54F)),
                                    modifier = Modifier.weight(1f),
                                    decorationBox = { inner ->
                                        if (filterQuery.isEmpty()) Text("Filter collections...", color = TextSubtle, fontSize = 13.sp)
                                        else inner()
                                    }
                                )
                                if (filterQuery.isNotEmpty()) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = "Clear",
                                        tint = TextMuted,
                                        modifier = Modifier.size(16.dp).clickable { filterQuery = "" }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }

                // 5. Empty State or Rich Collection Grid
                if (filteredPlaylists.isEmpty()) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(DeepSurface)
                                .border(1.dp, GlassSurface, RoundedCornerShape(24.dp))
                                .padding(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFFD54F).copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Rounded.LibraryMusic, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(28.dp))
                                }
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    if (filterQuery.isNotBlank()) "No matching collections" else "Sculpt Your First Collection",
                                    fontSize = 17.sp,
                                    color = TextPure,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Build a custom playlist from scratch or import any Spotify playlist link in seconds.",
                                    fontSize = 12.5.sp,
                                    color = TextMuted,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(18.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(
                                        onClick = onCreatePlaylist,
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD54F)),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Create Playlist", color = VoidBlack, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                    OutlinedButton(
                                        onClick = onImportPlaylist,
                                        border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.6f)),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Import Spotify", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val chunked = filteredPlaylists.chunked(2)
                    items(chunked) { rowItems ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            rowItems.forEach { pl ->
                                SanctuaryPlaylistCard(
                                    modifier = Modifier.weight(1f),
                                    playlist = pl,
                                    onClick = {
                                        onOpenPlaylist(NavRoute.PlaylistDetail(pl.name, pl.coverUrl, pl.songs))
                                    },
                                    onQuickPlay = {
                                        if (pl.songs.isNotEmpty()) onPlayCollection(pl.songs)
                                        else onOpenPlaylist(NavRoute.PlaylistDetail(pl.name, pl.coverUrl, pl.songs))
                                    }
                                )
                            }
                            if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun SanctuaryStatPill(count: String, label: String, accent: Color) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(count, fontSize = 18.sp, color = accent, fontWeight = FontWeight.Black)
            Text(label, fontSize = 11.sp, color = TextMuted, fontWeight = FontWeight.Medium)
        }
    }

    @Composable
    private fun BentoVaultHeroCard(
        title: String,
        subtitle: String,
        previewSongs: List<Song>,
        icon: ImageVector,
        accentColor: Color,
        gradientColors: List<Color>,
        onClick: () -> Unit,
        onQuickPlay: () -> Unit
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.horizontalGradient(gradientColors))
                .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                .clickable { onClick() }
                .padding(20.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(title, fontSize = 19.sp, color = TextPure, fontWeight = FontWeight.Bold)
                            Text(subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = 0.78f))
                        }
                    }
                    if (previewSongs.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy((-10).dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            previewSongs.forEach { s ->
                                AsyncImage(
                                    model = s.thumbnailUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .border(1.5.dp, VoidBlack, CircleShape)
                                )
                            }
                            Spacer(Modifier.width(18.dp))
                            Text(
                                text = previewSongs.joinToString(", ") { it.title },
                                fontSize = 11.sp,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                if (previewSongs.isNotEmpty()) {
                    IconButton(
                        onClick = onQuickPlay,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Play Liked", tint = VoidBlack, modifier = Modifier.size(26.dp))
                    }
                } else {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = TextMuted)
                }
            }
        }
    }

    @Composable
    private fun BentoVaultHalfCard(
        modifier: Modifier = Modifier,
        title: String,
        subtitle: String,
        icon: ImageVector,
        accentColor: Color,
        gradientColors: List<Color>,
        latestArt: String?,
        onClick: () -> Unit,
        onQuickPlay: () -> Unit
    ) {
        Box(
            modifier = modifier
                .height(132.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(gradientColors))
                .border(1.dp, accentColor.copy(alpha = 0.28f), RoundedCornerShape(22.dp))
                .clickable { onClick() }
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
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.20f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(18.dp))
                    }
                    if (!latestArt.isNullOrBlank()) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(accentColor)
                                .clickable { onQuickPlay() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "Quick Play", tint = VoidBlack, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Column {
                    Text(title, fontSize = 16.sp, color = TextPure, fontWeight = FontWeight.Bold, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, fontSize = 11.5.sp, color = Color.White.copy(alpha = 0.72f), maxLines = 1)
                }
            }
        }
    }

    @Composable
    private fun SanctuaryPlaylistCard(
        modifier: Modifier = Modifier,
        playlist: UserPlaylist,
        onClick: () -> Unit,
        onQuickPlay: () -> Unit
    ) {
        Box(
            modifier = modifier
                .aspectRatio(0.92f)
                .clip(RoundedCornerShape(22.dp))
                .background(DeepSurface)
                .border(1.dp, GlassSurface, RoundedCornerShape(22.dp))
                .clickable { onClick() }
        ) {
            // If playlist has 4+ songs and uses a default cover, show a 2x2 Mosaic Grid!
            if (playlist.songs.size >= 4 && playlist.coverUrl in PRESET_COVERS) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        AsyncImage(model = playlist.songs[0].thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight())
                        AsyncImage(model = playlist.songs[1].thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        AsyncImage(model = playlist.songs[2].thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight())
                        AsyncImage(model = playlist.songs[3].thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
            } else {
                AsyncImage(
                    model = playlist.coverUrl.ifBlank { playlist.songs.firstOrNull()?.thumbnailUrl ?: PRESET_COVERS[0] },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.45f to Color(0x66000000),
                            1.0f to Color(0xEB050508)
                        )
                    )
            )

            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        playlist.name,
                        fontSize = 15.sp,
                        color = TextPure,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${playlist.songs.size} tracks",
                        fontSize = 11.sp,
                        color = Color(0xFFFFD54F),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFFD54F))
                        .clickable { onQuickPlay() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = VoidBlack, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
    // ==========================================
    // FULL-SCREEN PROFILE & SETTINGS PORTAL
    // ==========================================
    @Composable
    private fun MeloProfileMasterpieceScene(
        onBack: () -> Unit,
        onSignInRequest: () -> Unit,
        onCheckUpdates: () -> Unit
    ) {
        val context = LocalContext.current
        var showLogoutDialog by remember { mutableStateOf(false) }
        var showEditNameDialog by remember { mutableStateOf(false) }
        var showClearDownloadsDialog by remember { mutableStateOf(false) }
        var tempName by remember(userProfileName) { mutableStateOf(userProfileName.orEmpty()) }
        var isSyncingNow by remember { mutableStateOf(false) }

        var streamingQuality by remember {
            mutableStateOf(prefs.getString("pref_streaming_quality", "320 kbps (Extreme)") ?: "320 kbps (Extreme)")
        }
        var autoRadioEnabled by remember {
            mutableStateOf(prefs.getBoolean("pref_auto_radio", true))
        }
        var highResArtwork by remember {
            mutableStateOf(prefs.getBoolean("pref_high_res_art", true))
        }

        var cacheSizeMb by remember {
            val bytes = (context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
            mutableStateOf("%.1f MB".format(bytes.toFloat() / 1048576f))
        }
        val downloadsSizeMb = remember(downloadedSongsList.size) {
            val dlDir = File(filesDir, "downloads")
            val bytes = if (dlDir.exists()) dlDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
            "%.1f MB".format(bytes.toFloat() / 1048576f)
        }

        val bgVoid = Color(0xFF070709)
        val cardSurface = Color(0xFF121217)
        val cardBorder = Color(0xFF23232C)
        val meloGold = Color(0xFFFFCD01)
        val pureWhite = Color(0xFFF7F7FA)
        val mutedText = Color(0xFF8E8E98)

        val displayName = userProfileName?.takeIf { it.isNotBlank() }
            ?: supabaseSync.currentUserEmail?.substringBefore("@")
            ?: "Melo Listener"
        val userEmail = supabaseSync.currentUserEmail ?: "Local Sanctuary Mode"
        val initials = displayName.trim().split("\\s+".toRegex())
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .take(2)
            .joinToString("")
            .ifEmpty { "M" }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgVoid)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .blur(85.dp)
            ) {
                drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to meloGold.copy(alpha = 0.14f),
                        0.75f to Color.Transparent,
                        center = Offset(size.width / 2f, 0f),
                        radius = size.width / 1.3f
                    ),
                    radius = size.width / 1.3f,
                    center = Offset(size.width / 2f, 0f)
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.06f))
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = pureWhite,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Text(
                            text = "Account",
                            color = pureWhite,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.size(38.dp))
                    }
                    Spacer(Modifier.height(24.dp))
                }

                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(cardSurface)
                            .border(1.dp, cardBorder, RoundedCornerShape(22.dp))
                            .padding(20.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(meloGold, Color(0xFFFF8F00))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = initials,
                                    color = VoidBlack,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }

                            Spacer(Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = displayName,
                                        color = pureWhite,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = "Edit Name",
                                        tint = meloGold,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable {
                                                tempName = displayName
                                                showEditNameDialog = true
                                            }
                                    )
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = userEmail,
                                    color = mutedText,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(if (supabaseSync.isLoggedIn) Color(0xFF00E676) else meloGold)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = if (supabaseSync.isLoggedIn) "Cloud Sanctuary Active" else "Local Device Profile",
                                        color = if (supabaseSync.isLoggedIn) Color(0xFF00E676) else meloGold,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(18.dp))
                        HorizontalDivider(color = cardBorder)
                        Spacer(Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            SanctuaryStatPill(count = likedSongsList.size.toString(), label = "Liked", accent = Color(0xFFFF4081))
                            SanctuaryStatPill(count = userPlaylists.size.toString(), label = "Playlists", accent = meloGold)
                            SanctuaryStatPill(count = downloadedSongsList.size.toString(), label = "Offline", accent = Color(0xFF00E676))
                            SanctuaryStatPill(count = recentlyPlayedList.size.toString(), label = "History", accent = Color(0xFF00E5FF))
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                }

                item {
                    Text(
                        text = "CLOUD VAULT",
                        color = mutedText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp,
                        modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(cardSurface)
                            .border(1.dp, cardBorder, RoundedCornerShape(18.dp))
                    ) {
                        if (supabaseSync.isLoggedIn) {
                            ProfileSettingRow(
                                icon = Icons.Rounded.CloudSync,
                                iconTint = Color(0xFF00E676),
                                title = if (isSyncingNow) "Syncing Vault..." else "Sync Cloud Vault Now",
                                subtitle = "Push & pull playlists, likes, and listening history",
                                onClick = {
                                    if (!isSyncingNow) {
                                        isSyncingNow = true
                                        lifecycleScope.launch {
                                            val snap = CloudVaultSnapshot(
                                                displayName = userProfileName.orEmpty(),
                                                likedSongs = likedSongsList.toList(),
                                                playlists = userPlaylists.toList(),
                                                recentSongs = recentlyPlayedList.toList(),
                                                savedQueue = currentQueue.take(40).toList()
                                            )
                                            val ok = supabaseSync.pushCloudVault(snap)
                                            isSyncingNow = false
                                            Toast.makeText(
                                                context,
                                                if (ok) "Cloud Vault backed up!" else "Sync failed. Check connection.",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            )
                        } else {
                            ProfileSettingRow(
                                icon = Icons.Rounded.Login,
                                iconTint = meloGold,
                                title = "Sign in to Cloud Vault",
                                subtitle = "Connect Google or Email to back up your music",
                                onClick = onSignInRequest
                            )
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                }

                item {
                    Text(
                        text = "AUDIO & PLAYBACK",
                        color = mutedText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp,
                        modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(cardSurface)
                            .border(1.dp, cardBorder, RoundedCornerShape(18.dp))
                    ) {
                        ProfileSettingRow(
                            icon = Icons.Rounded.GraphicEq,
                            iconTint = meloGold,
                            title = "Streaming & Download Quality",
                            subtitle = streamingQuality,
                            onClick = {
                                val options = listOf("320 kbps (Extreme)", "160 kbps (High)", "96 kbps (Data Saver)")
                                val next = options[(options.indexOf(streamingQuality) + 1) % options.size]
                                streamingQuality = next
                                prefs.edit().putString("pref_streaming_quality", next).apply()
                            }
                        )
                        HorizontalDivider(color = cardBorder, modifier = Modifier.padding(horizontal = 16.dp))
                        ProfileToggleRow(
                            icon = Icons.Rounded.Radio,
                            iconTint = Color(0xFF00E5FF),
                            title = "Endless Smart Radio",
                            subtitle = "Auto-queue similar songs when queue ends",
                            checked = autoRadioEnabled,
                            onCheckedChange = {
                                autoRadioEnabled = it
                                prefs.edit().putBoolean("pref_auto_radio", it).apply()
                            }
                        )
                        HorizontalDivider(color = cardBorder, modifier = Modifier.padding(horizontal = 16.dp))
                        ProfileToggleRow(
                            icon = Icons.Rounded.HighQuality,
                            iconTint = Color(0xFF9B51E0),
                            title = "High-Resolution Artwork",
                            subtitle = "Load 500x500 UHD covers & dynamic palettes",
                            checked = highResArtwork,
                            onCheckedChange = {
                                highResArtwork = it
                                prefs.edit().putBoolean("pref_high_res_art", it).apply()
                            }
                        )
                    }
                    Spacer(Modifier.height(22.dp))
                }

                item {
                    Text(
                        text = "STORAGE & DATA",
                        color = mutedText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp,
                        modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(cardSurface)
                            .border(1.dp, cardBorder, RoundedCornerShape(18.dp))
                    ) {
                        ProfileSettingRow(
                            icon = Icons.Rounded.CleaningServices,
                            iconTint = Color(0xFFFFB74D),
                            title = "Clear Artwork & Stream Cache",
                            subtitle = "Currently using $cacheSizeMb",
                            onClick = {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    runCatching { context.cacheDir.deleteRecursively() }
                                    withContext(Dispatchers.Main) {
                                        cacheSizeMb = "0.0 MB"
                                        Toast.makeText(context, "Cache cleared", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                        HorizontalDivider(color = cardBorder, modifier = Modifier.padding(horizontal = 16.dp))
                        ProfileSettingRow(
                            icon = Icons.Rounded.OfflinePin,
                            iconTint = Color(0xFF00E676),
                            title = "Offline Downloads",
                            subtitle = "${downloadedSongsList.size} tracks ($downloadsSizeMb)",
                            onClick = {
                                if (downloadedSongsList.isNotEmpty()) {
                                    showClearDownloadsDialog = true
                                } else {
                                    Toast.makeText(context, "No offline downloads stored", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(22.dp))
                }

                item {
                    Text(
                        text = "ABOUT MELO",
                        color = mutedText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp,
                        modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(cardSurface)
                            .border(1.dp, cardBorder, RoundedCornerShape(18.dp))
                    ) {
                        ProfileSettingRow(
                            icon = Icons.Rounded.SystemUpdateAlt,
                            iconTint = Color(0xFF00E5FF),
                            title = "Software Update",
                            subtitle = "Installed build: $currentAppVersionName",
                            onClick = onCheckUpdates
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                }

                if (supabaseSync.isLoggedIn || userProfileName != null) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MeloAccent.copy(alpha = 0.14f))
                                .border(1.dp, MeloAccent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                .clickable { showLogoutDialog = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Logout,
                                    contentDescription = "Log Out",
                                    tint = MeloAccent,
                                    modifier = Modifier.size(19.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "Log Out",
                                    color = MeloAccent,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }

                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 42.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "7V LABS",
                            color = mutedText.copy(alpha = 0.40f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 3.5.sp
                        )
                    }
                }
            }

            if (showEditNameDialog) {
                AlertDialog(
                    onDismissRequest = { showEditNameDialog = false },
                    containerColor = DeepSurface,
                    title = { Text("Edit Display Name", color = pureWhite, fontWeight = FontWeight.Bold) },
                    text = {
                        BasicTextField(
                            value = tempName,
                            onValueChange = { tempName = it },
                            singleLine = true,
                            textStyle = TextStyle(color = pureWhite, fontSize = 16.sp),
                            cursorBrush = SolidColor(meloGold),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(VoidBlack, RoundedCornerShape(12.dp))
                                .border(1.dp, cardBorder, RoundedCornerShape(12.dp))
                                .padding(14.dp)
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val clean = tempName.trim()
                            if (clean.isNotBlank()) {
                                userProfileName = clean
                                prefs.edit().putString("user_profile_name", clean).apply()
                                scheduleCloudVaultSync()
                            }
                            showEditNameDialog = false
                        }) {
                            Text("Save", color = meloGold, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showEditNameDialog = false }) {
                            Text("Cancel", color = mutedText)
                        }
                    }
                )
            }

            if (showClearDownloadsDialog) {
                AlertDialog(
                    onDismissRequest = { showClearDownloadsDialog = false },
                    containerColor = DeepSurface,
                    title = { Text("Delete All Offline Tracks?", color = pureWhite, fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            "This will remove all ${downloadedSongsList.size} downloaded songs from your device storage.",
                            color = mutedText
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            downloadedSongsList.toList().forEach { deleteDownloadedSong(it) }
                            showClearDownloadsDialog = false
                            Toast.makeText(context, "Offline downloads removed", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("Delete All", color = MeloAccent, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearDownloadsDialog = false }) {
                            Text("Cancel", color = pureWhite)
                        }
                    }
                )
            }

            if (showLogoutDialog) {
                AlertDialog(
                    onDismissRequest = { showLogoutDialog = false },
                    containerColor = DeepSurface,
                    title = {
                        Text("Log Out of Melo?", color = pureWhite, fontWeight = FontWeight.Bold)
                    },
                    text = {
                        Text(
                            "Your playlists and liked songs are safely backed up in your Cloud Vault. Are you sure you want to log out?",
                            color = mutedText,
                            fontSize = 14.sp
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showLogoutDialog = false
                                performFullLogout()
                                onBack()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MeloAccent),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Log Out", color = pureWhite, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showLogoutDialog = false }) {
                            Text("Cancel", color = pureWhite)
                        }
                    }
                )
            }
        }
    }

    @Composable
    private fun ProfileSettingRow(
        icon: ImageVector,
        iconTint: Color,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconTint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = TextPure, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = TextMuted, fontSize = 12.sp)
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = TextSubtle,
                modifier = Modifier.size(18.dp)
            )
        }
    }

    @Composable
    private fun ProfileToggleRow(
        icon: ImageVector,
        iconTint: Color,
        title: String,
        subtitle: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconTint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = TextPure, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = TextMuted, fontSize = 12.sp)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = VoidBlack,
                    checkedTrackColor = Color(0xFFFFCD01),
                    uncheckedThumbColor = TextMuted,
                    uncheckedTrackColor = Color(0xFF23232C)
                )
            )
        }
    }

    // ==========================================
    // MINIMAL, INDUSTRY-GRADE MELO AUTH SCENE
    // ==========================================
    @Composable
    private fun MeloAuthMasterpieceScene(
        onBack: () -> Unit,
        onAuthSuccess: (CloudVaultSnapshot) -> Unit
    ) {
        val context = LocalContext.current
        val density = LocalDensity.current
        val focusManager = LocalFocusManager.current

        var isSignUp by remember { mutableStateOf(false) }
        var passwordVisible by remember { mutableStateOf(false) }

        var name by remember { mutableStateOf("") }
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var confirmPassword by remember { mutableStateOf("") }

        var focusedField by remember { mutableStateOf("") }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var isSubmitting by remember { mutableStateOf(false) }

        // Exact root coordinates for smooth logo flight between "Sign in to [Logo]" and Top-Right
        var rootOrigin by remember { mutableStateOf(Offset.Zero) }
        var inlineLogoSlotPos by remember { mutableStateOf(Offset.Zero) }
        var topRightLogoSlotPos by remember { mutableStateOf(Offset.Zero) }

        val bgVoid = Color(0xFF070709)
        val fieldSurface = Color(0xFF111115)
        val fieldBorder = Color(0xFF24242C)
        val meloGold = Color(0xFFFFCD01)
        val darkInk = Color(0xFF0B0B0E)
        val mutedText = Color(0xFF8E8E98)
        val pureWhite = Color(0xFFF7F7FA)

        fun submitForm() {
            focusManager.clearFocus()
            errorMessage = null

            val cleanName = name.trim()
            val cleanEmail = email.trim()

            if (isSignUp && cleanName.isBlank()) {
                errorMessage = "Enter your name."
                return
            }
            if (cleanEmail.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
                errorMessage = "Enter a valid email address."
                return
            }
            if (password.length < 6) {
                errorMessage = "Password must be at least 6 characters."
                return
            }
            if (isSignUp && password != confirmPassword) {
                errorMessage = "Passwords do not match."
                return
            }

            isSubmitting = true
            val currentLocalSnapshot = CloudVaultSnapshot(
                displayName = cleanName.ifBlank { userProfileName.orEmpty() },
                likedSongs = likedSongsList.toList(),
                playlists = userPlaylists.toList(),
                recentSongs = recentlyPlayedList.toList(),
                savedQueue = currentQueue.take(40).toList()
            )

            lifecycleScope.launch {
                val result = if (isSignUp) {
                    supabaseSync.signUp(cleanEmail, password, cleanName, currentLocalSnapshot)
                } else {
                    supabaseSync.signIn(cleanEmail, password, currentLocalSnapshot)
                }
                isSubmitting = false
                when (result) {
                    is AuthResult.Success -> {
                        result.vault?.let { onAuthSuccess(it) }
                    }
                    is AuthResult.Error -> {
                        errorMessage = result.message
                    }
                }
            }
        }

        val targetLogoPos = if (isSignUp) topRightLogoSlotPos else inlineLogoSlotPos
        val animatedLogoPos by animateOffsetAsState(
            targetValue = targetLogoPos,
            animationSpec = spring(
                dampingRatio = 0.82f,
                stiffness = 300f
            ),
            label = "melo_logo_pos"
        )
        val animatedLogoWidth by animateDpAsState(
            targetValue = if (isSignUp) 68.dp else 74.dp,
            animationSpec = spring(dampingRatio = 0.82f, stiffness = 300f),
            label = "melo_logo_w"
        )
        val animatedLogoHeight by animateDpAsState(
            targetValue = if (isSignUp) 36.dp else 38.dp,
            animationSpec = spring(dampingRatio = 0.82f, stiffness = 300f),
            label = "melo_logo_h"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgVoid)
                .onGloballyPositioned { rootOrigin = it.positionInRoot() }
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
        ) {
            // Subtle, ultra-clean ambient top spotlight
            // Subtle, ultra-clean ambient top spotlight
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .blur(80.dp)
            ) {
                drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to meloGold.copy(alpha = 0.14f),
                        0.7f to Color.Transparent,
                        center = Offset(size.width / 2f, 0f),
                        radius = size.width / 1.4f
                    ),
                    radius = size.width / 1.4f,
                    center = Offset(size.width / 2f, 0f)
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding(),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 1. Top Bar (Back on Top-Left, Invisible Anchor Slot on Top-Right for Create Account)
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.06f))
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = pureWhite,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Top-Right target anchor slot (where logo glides when isSignUp == true)
                        Box(
                            modifier = Modifier
                                .size(width = 68.dp, height = 36.dp)
                                .onGloballyPositioned { coords ->
                                    topRightLogoSlotPos = coords.positionInRoot() - rootOrigin
                                }
                        )
                    }
                    Spacer(Modifier.height(38.dp))
                }

                // 2. Smooth Animated Heading + Inline Logo Slot
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.animation.AnimatedContent(
                            targetState = isSignUp,
                            transitionSpec = {
                                (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 3 })
                                    .togetherWith(fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 3 })
                            },
                            label = "heading_switch"
                        ) { signUpMode ->
                            if (signUpMode) {
                                Text(
                                    text = "Create your account",
                                    color = pureWhite,
                                    fontSize = 25.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.5).sp
                                )
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "Sign in to",
                                        color = pureWhite,
                                        fontSize = 25.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (-0.5).sp
                                    )
                                    Spacer(Modifier.width(9.dp))
                                    // Inline target anchor slot right after "Sign in to"
                                    Box(
                                        modifier = Modifier
                                            .size(width = 74.dp, height = 38.dp)
                                            .offset(y = (-1).dp)
                                            .onGloballyPositioned { coords ->
                                                inlineLogoSlotPos = coords.positionInRoot() - rootOrigin
                                            }
                                    )
                                }
                            }
                        }
                    }
                    // Generous breathing room between Heading and Continue with Google
                    Spacer(Modifier.height(44.dp))
                }

                // 3. Official Google Sign-In Button + Minimal Divider
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF16161C))
                            .border(1.dp, fieldBorder, RoundedCornerShape(12.dp))
                            .clickable {
                                runCatching {
                                    val oauthUrl = supabaseSync.getGoogleOAuthUrl()
                                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(oauthUrl))
                                    context.startActivity(browserIntent)
                                }.onFailure {
                                    Toast.makeText(
                                        context,
                                        "Unable to open browser for Google Sign-In.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OfficialGoogleLogo(modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Continue with Google",
                            color = pureWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = fieldBorder)
                        Text(
                            text = "or",
                            color = mutedText,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 14.dp)
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f), color = fieldBorder)
                    }

                    Spacer(Modifier.height(24.dp))
                }

                // 4. Minimal Input Fields with Smooth Spring Expansion
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(
                                animationSpec = spring(
                                    dampingRatio = 0.85f,
                                    stiffness = 320f
                                )
                            )
                    ) {
                        AnimatedVisibility(
                            visible = isSignUp,
                            enter = expandVertically(tween(280, easing = FastOutSlowInEasing)) + fadeIn(tween(240)),
                            exit = shrinkVertically(tween(240, easing = FastOutSlowInEasing)) + fadeOut(tween(180))
                        ) {
                            Column {
                                MinimalAuthField(
                                    label = "Name",
                                    value = name,
                                    onValueChange = { name = it; errorMessage = null },
                                    placeholder = "Your name",
                                    isFocused = focusedField == "name",
                                    fieldSurface = fieldSurface,
                                    fieldBorder = fieldBorder,
                                    accentColor = meloGold,
                                    textColor = pureWhite,
                                    mutedColor = mutedText,
                                    onFocusChange = { if (it) focusedField = "name" }
                                )
                                Spacer(Modifier.height(14.dp))
                            }
                        }

                        MinimalAuthField(
                            label = "Email",
                            value = email,
                            onValueChange = { email = it; errorMessage = null },
                            placeholder = "name@example.com",
                            isFocused = focusedField == "email",
                            fieldSurface = fieldSurface,
                            fieldBorder = fieldBorder,
                            accentColor = meloGold,
                            textColor = pureWhite,
                            mutedColor = mutedText,
                            onFocusChange = { if (it) focusedField = "email" }
                        )

                        Spacer(Modifier.height(14.dp))

                        MinimalAuthField(
                            label = "Password",
                            value = password,
                            onValueChange = { password = it; errorMessage = null },
                            placeholder = "••••••••",
                            isPassword = true,
                            passwordVisible = passwordVisible,
                            onTogglePassword = { passwordVisible = !passwordVisible },
                            isFocused = focusedField == "password",
                            fieldSurface = fieldSurface,
                            fieldBorder = fieldBorder,
                            accentColor = meloGold,
                            textColor = pureWhite,
                            mutedColor = mutedText,
                            onFocusChange = { if (it) focusedField = "password" }
                        )

                        AnimatedVisibility(
                            visible = isSignUp,
                            enter = expandVertically(tween(280, easing = FastOutSlowInEasing)) + fadeIn(tween(240)),
                            exit = shrinkVertically(tween(240, easing = FastOutSlowInEasing)) + fadeOut(tween(180))
                        ) {
                            Column {
                                Spacer(Modifier.height(14.dp))
                                MinimalAuthField(
                                    label = "Confirm password",
                                    value = confirmPassword,
                                    onValueChange = { confirmPassword = it; errorMessage = null },
                                    placeholder = "••••••••",
                                    isPassword = true,
                                    passwordVisible = passwordVisible,
                                    onTogglePassword = null,
                                    isFocused = focusedField == "confirm",
                                    fieldSurface = fieldSurface,
                                    fieldBorder = fieldBorder,
                                    accentColor = meloGold,
                                    textColor = pureWhite,
                                    mutedColor = mutedText,
                                    onFocusChange = { if (it) focusedField = "confirm" }
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = !isSignUp,
                            enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                            exit = shrinkVertically(tween(180)) + fadeOut(tween(150))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Text(
                                    text = "Forgot password?",
                                    color = mutedText,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.clickable {
                                        val cleanEmail = email.trim()
                                        if (cleanEmail.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
                                            errorMessage = "Enter your email address first."
                                            return@clickable
                                        }
                                        lifecycleScope.launch {
                                            when (val res = supabaseSync.sendPasswordReset(cleanEmail)) {
                                                is AuthResult.Success -> {
                                                    Toast.makeText(context, "Reset link sent to $cleanEmail", Toast.LENGTH_LONG).show()
                                                }
                                                is AuthResult.Error -> errorMessage = res.message
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        AnimatedVisibility(visible = errorMessage != null) {
                            Text(
                                text = errorMessage.orEmpty(),
                                color = Color(0xFFFF5252),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }

                        Spacer(Modifier.height(24.dp))

                        // Primary Action Button
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(meloGold)
                                .clickable(enabled = !isSubmitting) { submitForm() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(
                                    color = darkInk,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Text(
                                    text = if (isSignUp) "Create account" else "Sign in",
                                    color = darkInk,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // 5. Minimal Bottom Mode Switcher
                item {
                    Spacer(Modifier.height(26.dp))
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                isSignUp = !isSignUp
                                errorMessage = null
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = if (isSignUp) "Already have an account? " else "Don't have an account? ",
                            color = mutedText,
                            fontSize = 13.sp
                        )
                        Text(
                            text = if (isSignUp) "Sign in" else "Create account",
                            color = meloGold,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(72.dp))
                }
            }

            // Floating Animated Melo Logo that physically glides between "Sign in to [Melo]" and Top-Right
            if (inlineLogoSlotPos != Offset.Zero || topRightLogoSlotPos != Offset.Zero) {
                Icon(
                    painter = painterResource(id = com.melomusic.app.R.drawable.ic_melo_logo),
                    contentDescription = "Melo Music",
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = animatedLogoPos.x.roundToInt(),
                                y = animatedLogoPos.y.roundToInt()
                            )
                        }
                        .size(width = animatedLogoWidth, height = animatedLogoHeight)
                )
            }

            // Permanent "7V LABS" signature on BOTH Sign In & Create Account pages, positioned slightly higher
            Text(
                text = "7V LABS",
                color = mutedText.copy(alpha = 0.45f),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 3.5.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 38.dp)
            )
        }
    }

    @Composable
    private fun MinimalAuthField(
        label: String,
        value: String,
        onValueChange: (String) -> Unit,
        placeholder: String,
        isPassword: Boolean = false,
        passwordVisible: Boolean = false,
        onTogglePassword: (() -> Unit)? = null,
        isFocused: Boolean,
        fieldSurface: Color,
        fieldBorder: Color,
        accentColor: Color,
        textColor: Color,
        mutedColor: Color,
        onFocusChange: (Boolean) -> Unit
    ) {
        val visualTransformation = if (isPassword && !passwordVisible) {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        }

        Column(Modifier.fillMaxWidth()) {
            Text(
                text = label,
                color = if (isFocused) textColor else mutedTextColor(mutedColor),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(fieldSurface)
                    .border(
                        width = 1.dp,
                        color = if (isFocused) accentColor.copy(alpha = 0.85f) else fieldBorder,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        visualTransformation = visualTransformation,
                        textStyle = TextStyle(
                            color = textColor,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        cursorBrush = SolidColor(accentColor),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { onFocusChange(it.isFocused) },
                        decorationBox = { inner ->
                            if (value.isEmpty()) {
                                Text(placeholder, color = mutedColor.copy(alpha = 0.45f), fontSize = 14.sp)
                            } else {
                                inner()
                            }
                        }
                    )
                    if (isPassword && onTogglePassword != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (passwordVisible) "Hide" else "Show",
                            color = mutedColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { onTogglePassword() }
                        )
                    }
                }
            }
        }
    }

    private fun mutedTextColor(c: Color) = c

    // Official 4-Color Vector Google "G" Logo
    @Composable
    private fun OfficialGoogleLogo(modifier: Modifier = Modifier) {
        Canvas(modifier = modifier) {
            val w = size.width
            val h = size.height
            val stroke = w * 0.20f
            val inset = stroke / 2f
            val arcSize = androidx.compose.ui.geometry.Size(w - stroke, h - stroke)
            val topLeft = Offset(inset, inset)

            // Red Top Arc
            drawArc(
                color = Color(0xFFEA4335),
                startAngle = -145f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Butt)
            )
            // Yellow Left Arc
            drawArc(
                color = Color(0xFFFBBC05),
                startAngle = 145f,
                sweepAngle = 70f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Butt)
            )
            // Green Bottom Arc
            drawArc(
                color = Color(0xFF34A853),
                startAngle = 45f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Butt)
            )
            // Blue Right Arc + Horizontal Bar
            drawArc(
                color = Color(0xFF4285F4),
                startAngle = -12f,
                sweepAngle = 58f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Butt)
            )
            drawRect(
                color = Color(0xFF4285F4),
                topLeft = Offset(w * 0.50f, h * 0.41f),
                size = androidx.compose.ui.geometry.Size(w * 0.46f, stroke * 0.92f)
            )
        }
    }

    @Composable
    private fun GoldAuthField(
        label: String,
        value: String,
        onValueChange: (String) -> Unit,
        placeholder: String,
        isPassword: Boolean = false,
        passwordVisible: Boolean = false,
        onTogglePassword: (() -> Unit)? = null,
        isFocused: Boolean,
        fieldSurface: Color,
        fieldBorder: Color,
        goldAccent: Color,
        textColor: Color,
        mutedColor: Color,
        onFocusChange: (Boolean) -> Unit
    ) {
        val visualTransformation = if (isPassword && !passwordVisible) {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        }

        Column(Modifier.fillMaxWidth()) {
            Text(
                text = label,
                color = if (isFocused) goldAccent else mutedColor,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(7.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(fieldSurface)
                    .border(
                        width = if (isFocused) 1.5.dp else 1.dp,
                        color = if (isFocused) goldAccent else fieldBorder,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 15.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        visualTransformation = visualTransformation,
                        textStyle = TextStyle(
                            color = textColor,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        cursorBrush = SolidColor(goldAccent),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { onFocusChange(it.isFocused) },
                        decorationBox = { inner ->
                            if (value.isEmpty()) {
                                Text(placeholder, color = mutedColor.copy(alpha = 0.55f), fontSize = 14.5.sp)
                            } else {
                                inner()
                            }
                        }
                    )
                    if (isPassword && onTogglePassword != null) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = if (passwordVisible) "HIDE" else "SHOW",
                            color = goldAccent,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.clickable { onTogglePassword() }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SanctuaryInputField(
        label: String,
        value: String,
        onValueChange: (String) -> Unit,
        placeholder: String,
        leadingIcon: ImageVector,
        isPassword: Boolean = false,
        passwordVisible: Boolean = false,
        onTogglePassword: (() -> Unit)? = null,
        isFocused: Boolean,
        accentColor: Color,
        onFocusChange: (Boolean) -> Unit
    ) {
        val visualTransformation = if (isPassword && !passwordVisible) {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        }

        Column(Modifier.fillMaxWidth()) {
            Text(
                text = label,
                fontSize = 10.sp,
                letterSpacing = 1.4.sp,
                color = if (isFocused) accentColor else TextMuted,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF06060A))
                    .border(
                        width = if (isFocused) 1.5.dp else 1.dp,
                        color = if (isFocused) accentColor else Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 13.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = if (isFocused) accentColor else TextSubtle,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        visualTransformation = visualTransformation,
                        textStyle = TextStyle(
                            color = TextPure,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        cursorBrush = SolidColor(accentColor),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { onFocusChange(it.isFocused) },
                        decorationBox = { inner ->
                            if (value.isEmpty()) {
                                Text(placeholder, color = TextSubtle, fontSize = 14.sp)
                            } else {
                                inner()
                            }
                        }
                    )
                    if (isPassword && onTogglePassword != null) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = "Toggle Password",
                            tint = TextMuted,
                            modifier = Modifier
                                .size(19.dp)
                                .clickable { onTogglePassword() }
                        )
                    }
                }
            }
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
                onClick = { if (name.isNotBlank()) onCreate(name.trim(), selectedCoverUrl) },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD54F)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("FORGE COLLECTION", color = VoidBlack, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }
        }
    }

    // ==========================================
    // PLAYLIST DETAIL SCENE (Snapshot-Safe)
    // ==========================================
    @Composable
    private fun PlaylistDetailScene(
        playlist: UserPlaylist,
        onPlay: (Song, List<Song>) -> Unit,
        onDelete: () -> Unit,
        onUpdatePlaylist: (String, String, List<Song>) -> Unit,
        onBack: () -> Unit,
        repo: JioSaavnRepository
    ) {
        val context = LocalContext.current
        var dominantColor by remember { mutableStateOf(DeepSurface) }
        val smoothDominant by animateColorAsState(dominantColor, tween(600), label = "pl_color")

        var showMenu by remember { mutableStateOf(false) }
        var showEditSheet by remember { mutableStateOf(false) }
        var editName by remember(playlist.name) { mutableStateOf(playlist.name) }
        var editCover by remember(playlist.coverUrl) { mutableStateOf(playlist.coverUrl) }

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

                    Row(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(playlist.name, fontSize = 34.sp, color = TextPure, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, maxLines = 2)
                            Spacer(Modifier.height(6.dp))
                            Text("${playlist.songs.size} Frequencies", fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f))
                        }
                        if (playlist.songs.isNotEmpty()) {
                            FloatingActionButton(
                                onClick = { onPlay(playlist.songs.first(), playlist.songs) },
                                containerColor = Color(0xFFFFD54F),
                                contentColor = VoidBlack,
                                shape = CircleShape
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play All", modifier = Modifier.size(30.dp))
                            }
                        }
                    }
                }

                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 140.dp)
                ) {
                    if (playlist.songs.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                                TextButton(onClick = { showAddSongsSheet = true }) {
                                    Icon(Icons.Rounded.Add, contentDescription = null, tint = Color(0xFFFFD54F))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Tap to add frequencies to this collection", color = Color(0xFFFFD54F))
                                }
                            }
                        }
                    }
                    items(playlist.songs, key = { it.id }) { song ->
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
                                    onUpdatePlaylist(editName.trim(), editCover, playlist.songs)
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
                LaunchedEffect(searchSongQuery) {
                    val q = searchSongQuery.trim()
                    if (q.length > 2) {
                        delay(300)
                        searchSongResults = repo.searchSongs(q)
                    }
                }

                ModalBottomSheet(onDismissRequest = { showAddSongsSheet = false }, containerColor = DeepSurface) {
                    Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(20.dp)) {
                        Text("Add Songs to Playlist", fontSize = 22.sp, color = TextPure, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                        Spacer(Modifier.height(16.dp))
                        BasicTextField(
                            value = searchSongQuery,
                            onValueChange = { searchSongQuery = it },
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
                            items(searchSongResults, key = { it.id }) { song ->
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
                                                val updated = playlist.songs.toMutableList().apply { add(song) }
                                                val cover = playlist.coverUrl.ifBlank { song.thumbnailUrl }
                                                onUpdatePlaylist(playlist.name, cover, updated)
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
                            items(playlist.songs, key = { it.id }) { song ->
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
                            val remaining = playlist.songs.filterNot { it.id in removeIds }
                            onUpdatePlaylist(playlist.name, playlist.coverUrl, remaining)
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
                        items(songs, key = { it.id }) { song ->
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
                        items(songs, key = { it.id }) { song ->
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
    // IMMERSIVE FULL-SCREEN PLAYER (Recomposition-Isolated)
    // ==========================================
    @Composable
    private fun ImmersivePlayerScene(
        song: Song,
        artworkColor: Color,
        secondaryColor: Color,
        isPlaying: Boolean,
        isBuffering: Boolean,
        currentPositionProvider: () -> Float,
        totalDurationProvider: () -> Float,
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
                            SyncedLyricsViewport(
                                lyrics = lyrics,
                                currentPositionProvider = currentPositionProvider,
                                onSeek = onSeek
                            )
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

                        InteractiveWaveScrubber(
                            accentColor = artworkColor,
                            currentPositionProvider = currentPositionProvider,
                            totalDurationProvider = totalDurationProvider,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            onUserSeekingChange = onUserSeekingChange,
                            onSeek = onSeek
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
                                key = { _, item -> item.id }
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
    private fun SyncedLyricsViewport(
        lyrics: List<LyricLine>,
        currentPositionProvider: () -> Float,
        onSeek: (Float) -> Unit
    ) {
        val listState = rememberLazyListState()
        val isSynced = remember(lyrics) { lyrics.any { it.time >= 0f } }
        val currentPositionMs = currentPositionProvider()

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
        val offsetX = remember(song.id) { Animatable(0f) }
        val scope = rememberCoroutineScope()
        var isDismissed by remember(song.id) { mutableStateOf(false) }

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
                    .background(Color(0xFFE53935).copy(alpha = (dragFraction * 0.45f)))
            ) {
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
                        .background(Color(0xCC0D0D12))
                        .background(adaptiveBrush)
                        .border(
                            width = 1.dp,
                            color = if (isImmediateNext) accentColor.copy(alpha = 0.55f) else accentColor.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable { onSongClick() }
                        .pointerInput(song.id) {
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

    // ==========================================
    // CINEMATIC LANDSCAPE SCENE
    // ==========================================
    @Composable
    private fun CinematicLandscapeScene(
        song: Song,
        artworkColor: Color,
        secondaryColor: Color,
        isPlaying: Boolean,
        currentPositionProvider: () -> Float,
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
                label = "c_offsetX"
            )

            Box(Modifier.fillMaxSize().blur(75.dp).alpha(0.75f)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(
                        color = artworkColor,
                        radius = size.height * 0.7f,
                        center = Offset(size.width * 0.25f + offsetX1, size.height * 0.5f)
                    )
                    drawCircle(
                        color = secondaryColor,
                        radius = size.height * 0.65f,
                        center = Offset(size.width * 0.75f - offsetX1, size.height * 0.5f)
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 36.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    Modifier.weight(0.42f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    AsyncImage(
                        model = song.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(190.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .shadow(28.dp, RoundedCornerShape(28.dp), ambientColor = artworkColor, spotColor = artworkColor)
                            .clickable { onTogglePlay() }
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(song.title, fontSize = 20.sp, color = TextPure, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.basicMarquee())
                    Text(song.artist, fontSize = 14.sp, color = TextMuted, maxLines = 1)
                }

                Spacer(Modifier.width(28.dp))

                Box(Modifier.weight(0.58f).fillMaxHeight()) {
                    SyncedLyricsViewport(
                        lyrics = lyrics,
                        currentPositionProvider = currentPositionProvider,
                        onSeek = onSeek
                    )
                }
            }

            IconButton(
                onClick = onExit,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(GlassSurface)
            ) {
                Icon(Icons.Rounded.FullscreenExit, contentDescription = "Exit", tint = TextPure)
            }
        }
    }

    // ==========================================
    // MINI PLAYER, WAVE SCRUBBER & BOTTOM NAV
    // ==========================================
    @Composable
    private fun PremiumMiniPlayer(
        song: Song,
        artworkColor: Color,
        secondaryColor: Color,
        isPlaying: Boolean,
        isBuffering: Boolean,
        progressProvider: () -> Float,
        onExpand: () -> Unit,
        onTogglePlay: () -> Unit,
        onNext: () -> Unit,
        onPrev: () -> Unit
    ) {
        var dragOffset by remember { mutableFloatStateOf(0f) }

        // Smoothly rises when playing (1f) and recedes flat when paused (0f)
        val waveAmplitude by animateFloatAsState(
            targetValue = if (isPlaying) 1f else 0f,
            animationSpec = spring(
                dampingRatio = 0.72f,
                stiffness = Spring.StiffnessLow
            ),
            label = "waveAmplitude"
        )

        val infinite = rememberInfiniteTransition(label = "mini_fluid_physics")
        val spinAngle by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 12000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "spin"
        )
        val phaseFast by infinite.animateFloat(
            initialValue = 0f,
            targetValue = (2f * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "phaseFast"
        )
        val phaseSlow by infinite.animateFloat(
            initialValue = (2f * Math.PI).toFloat(),
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "phaseSlow"
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
                .shadow(
                    elevation = 28.dp,
                    shape = RoundedCornerShape(35.dp),
                    ambientColor = artworkColor.copy(alpha = 0.55f),
                    spotColor = artworkColor.copy(alpha = 0.65f)
                )
                .clip(RoundedCornerShape(35.dp))
                // 1. Opaque Obsidian Base so background text never bleeds through
                .background(Color(0xF808080C))
                // 2. Subtle Left Artwork Ambient Spotlight
                .background(
                    Brush.horizontalGradient(
                        0.0f to artworkColor.copy(alpha = 0.32f),
                        0.45f to secondaryColor.copy(alpha = 0.10f),
                        1.0f to Color.Transparent
                    )
                )
                // 3. Specular Glass Rim Border
                .border(
                    BorderStroke(
                        1.dp,
                        Brush.linearGradient(
                            colors = listOf(
                                artworkColor.copy(alpha = 0.70f),
                                secondaryColor.copy(alpha = 0.35f),
                                Color.White.copy(alpha = 0.08f)
                            )
                        )
                    ),
                    RoundedCornerShape(35.dp)
                )
                .clickable { onExpand() }
                .pointerInput(song.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (dragOffset < -80f) onNext()
                            else if (dragOffset > 80f) onPrev()
                            dragOffset = 0f
                        },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            dragOffset += amount
                        }
                    )
                }
        ) {
            // LAYER 1: Multi-Harmonic Cover-Art Fluid Waves at Bottom Edge
            // LAYER 1: Slim Tapered Silk Wave (Emerges & Ends at Both Sides of MiniPlayer)
            if (waveAmplitude > 0.005f) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 18.dp) // Aligns start/end tips inside the rounded capsule corners
                ) {
                    val w = size.width
                    val h = size.height
                    val anchorY = h - 3.dp.toPx() // Sits sleekly along the bottom edge

                    fun drawTaperedSilkWave(
                        freqA: Float,
                        freqB: Float,
                        phase1: Float,
                        phase2: Float,
                        maxAmpPx: Float,
                        ribbonColor: Color,
                        secondaryTint: Color,
                        strokeWidthPx: Float,
                        fillAlpha: Float
                    ) {
                        val wavePath = Path()
                        val steps = 64
                        val stepX = w / steps

                        // Upper undulating curve from Left Tip (0f) -> Right Tip (w)
                        for (i in 0..steps) {
                            val x = i * stepX
                            val nx = i.toFloat() / steps.toFloat()

                            // Raised-sine pinch envelope: strictly 0.0 at nx=0 and nx=1, smooth peak in middle
                            val s = sin(nx * Math.PI).toFloat().coerceAtLeast(0f)
                            val pinchEnvelope = s * s * kotlin.math.sqrt(s) // sin^2.5(pi * x)

                            val wave = sin(nx * freqA + phase1) * 0.65f +
                                    cos(nx * freqB - phase2) * 0.35f

                            val y = anchorY - ( kotlin.math.abs(wave) * maxAmpPx * pinchEnvelope * waveAmplitude ) -
                                    (1.5.dp.toPx() * pinchEnvelope * waveAmplitude)

                            if (i == 0) wavePath.moveTo(x, anchorY)
                            else wavePath.lineTo(x, y)
                        }

                        // Draw the sleek glowing filament stroke (emerges from 0 at left, ends at 0 at right)
                        drawPath(
                            path = wavePath,
                            brush = Brush.horizontalGradient(
                                0.0f to Color.Transparent,
                                0.08f to ribbonColor.copy(alpha = 0.75f * waveAmplitude),
                                0.50f to secondaryTint.copy(alpha = 0.95f * waveAmplitude),
                                0.92f to ribbonColor.copy(alpha = 0.75f * waveAmplitude),
                                1.0f to Color.Transparent
                            ),
                            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
                            blendMode = BlendMode.Screen
                        )

                        // Close back along the anchor baseline to create a slim, tapered lens glow
                        wavePath.lineTo(w, anchorY)
                        wavePath.lineTo(0f, anchorY)
                        wavePath.close()

                        drawPath(
                            path = wavePath,
                            brush = Brush.horizontalGradient(
                                0.0f to Color.Transparent,
                                0.12f to ribbonColor.copy(alpha = fillAlpha * 0.6f * waveAmplitude),
                                0.50f to secondaryTint.copy(alpha = fillAlpha * waveAmplitude),
                                0.88f to ribbonColor.copy(alpha = fillAlpha * 0.6f * waveAmplitude),
                                1.0f to Color.Transparent
                            ),
                            blendMode = BlendMode.Screen
                        )
                    }

                    // Secondary Cover-Art Silk Strand (Counter-flowing, wider harmonic)
                    drawTaperedSilkWave(
                        freqA = 8.5f,
                        freqB = 5.0f,
                        phase1 = phaseSlow,
                        phase2 = phaseFast,
                        maxAmpPx = 7.dp.toPx(),
                        ribbonColor = secondaryColor,
                        secondaryTint = artworkColor,
                        strokeWidthPx = 1.4.dp.toPx(),
                        fillAlpha = 0.28f
                    )

                    // Primary Cover-Art Silk Strand (Lead harmonic, crisp luminous filament)
                    drawTaperedSilkWave(
                        freqA = 6.0f,
                        freqB = 11.0f,
                        phase1 = phaseFast,
                        phase2 = phaseSlow,
                        maxAmpPx = 9.dp.toPx(),
                        ribbonColor = artworkColor,
                        secondaryTint = secondaryColor,
                        strokeWidthPx = 2.0.dp.toPx(),
                        fillAlpha = 0.42f
                    )
                }
            }

            // LAYER 2: Foreground Content & Transport Controls
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Rotating Vinyl Disc Artwork
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, artworkColor.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = song.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                rotationZ = if (isPlaying) spinAngle else 0f
                            }
                    )
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .clip(CircleShape)
                            .background(Color(0xD908080C))
                            .border(1.dp, artworkColor, CircleShape)
                    )
                }

                Spacer(Modifier.width(12.dp))

                // Title & Artist
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        color = TextPure,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee()
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = song.artist,
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.width(4.dp))

                // Previous Track Button
                IconButton(
                    onClick = onPrev,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Previous",
                        tint = TextPure.copy(alpha = 0.90f),
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(Modifier.width(2.dp))

                // Coaxial Progress Ring + Glowing Play/Pause Orb
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable { onTogglePlay() },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize().padding(2.dp)) {
                        val p = progressProvider()
                        drawCircle(
                            color = Color.White.copy(alpha = 0.14f),
                            style = Stroke(width = 2.4.dp.toPx())
                        )
                        drawArc(
                            brush = Brush.sweepGradient(
                                listOf(artworkColor, secondaryColor, artworkColor)
                            ),
                            startAngle = -90f,
                            sweepAngle = 360f * p,
                            useCenter = false,
                            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isBuffering) {
                            CircularProgressIndicator(
                                color = TextPure,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = TextPure,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.width(2.dp))

                // Next Track Button
                IconButton(
                    onClick = onNext,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Next",
                        tint = TextPure.copy(alpha = 0.90f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun InteractiveWaveScrubber(
        accentColor: Color,
        currentPositionProvider: () -> Float,
        totalDurationProvider: () -> Float,
        modifier: Modifier = Modifier,
        onUserSeekingChange: (Boolean) -> Unit,
        onSeek: (Float) -> Unit
    ) {
        val currentPosMs = currentPositionProvider()
        val totalDurMs = totalDurationProvider().coerceAtLeast(1000f)
        val progress = (currentPosMs / totalDurMs).coerceIn(0f, 1f)

        val infinite = rememberInfiniteTransition(label = "wave_phase")
        val wavePhase by infinite.animateFloat(
            initialValue = 0f,
            targetValue = (2f * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
            label = "phase"
        )

        fun formatTime(ms: Float): String {
            val totalSeconds = (ms / 1000f).toLong().coerceAtLeast(0L)
            val mins = totalSeconds / 60
            val secs = totalSeconds % 60
            return "%d:%02d".format(mins, secs)
        }

        Column(modifier = modifier) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .pointerInput(totalDurMs) {
                        detectTapGestures { offset ->
                            val pct = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onSeek(pct * totalDurMs)
                        }
                    }
                    .pointerInput(totalDurMs) {
                        detectHorizontalDragGestures(
                            onDragStart = { onUserSeekingChange(true) },
                            onDragEnd = { onUserSeekingChange(false) },
                            onDragCancel = { onUserSeekingChange(false) }
                        ) { change, _ ->
                            change.consume()
                            val pct = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onSeek(pct * totalDurMs)
                        }
                    }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val midY = size.height / 2f
                    val activeX = size.width * progress

                    // Inactive track line
                    drawLine(
                        color = Color.White.copy(alpha = 0.22f),
                        start = Offset(activeX, midY),
                        end = Offset(size.width, midY),
                        strokeWidth = 4f,
                        cap = StrokeCap.Round
                    )

                    // Active sinusoidal wave path
                    val wavePath = Path()
                    val step = 6f
                    var x = 0f
                    wavePath.moveTo(0f, midY)
                    while (x <= activeX) {
                        val y = midY + sin((x * 0.045f) + wavePhase) * 5.5f
                        wavePath.lineTo(x, y)
                        x += step
                    }
                    drawPath(
                        path = wavePath,
                        color = accentColor,
                        style = Stroke(width = 5f, cap = StrokeCap.Round)
                    )

                    // Scrubber thumb
                    drawCircle(
                        color = TextPure,
                        radius = 7.5.dp.toPx(),
                        center = Offset(activeX, midY)
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatTime(currentPosMs), fontSize = 11.sp, color = TextMuted)
                Text(formatTime(totalDurMs), fontSize = 11.sp, color = TextMuted)
            }
        }
    }

    @Composable
    private fun MeloBottomNav(
        selectedTab: MeloTab,
        onSelectTab: (MeloTab) -> Unit,
        modifier: Modifier = Modifier
    ) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xE60B0B10))
                .border(1.dp, GlassSurface, RoundedCornerShape(28.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MeloTab.entries.forEach { tab ->
                val isSelected = selectedTab == tab
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isSelected) tab.activeTint.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable { onSelectTab(tab) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = if (isSelected) tab.activeTint else TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                    AnimatedVisibility(visible = isSelected) {
                        Row {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = tab.label,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPure
                            )
                        }
                    }
                }
            }
        }
    }
}
