// File: app/src/main/java/com/melomusic/app/data/SupabaseSyncManager.kt
package com.melomusic.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.melomusic.app.UserPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class CloudVaultSnapshot(
    val displayName: String,
    val likedSongs: List<Song>,
    val playlists: List<UserPlaylist>,
    val recentSongs: List<Song>,
    val savedQueue: List<Song>
)

sealed class AuthResult {
    data class Success(
        val userId: String,
        val email: String,
        val displayName: String,
        val vault: CloudVaultSnapshot?
    ) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

class SupabaseSyncManager(
    context: Context,
    private val httpClient: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        const val SUPABASE_URL = "https://ynebqxnvawfebsrrrmmr.supabase.co"
        const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InluZWJxeG52YXdmZWJzcnJybW1yIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTE5NjM4ODIsImV4cCI6MjEwNzUzOTg4Mn0.o2bPzvJ1VSFk2NloXrF9W4BNVv4sJpteMOnD11l1G8w"
    }

    private val authPrefs = context.getSharedPreferences("melo_supabase_auth", Context.MODE_PRIVATE)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    val isConfigured: Boolean
        get() = !SUPABASE_URL.contains("YOUR_PROJECT_ID") && !SUPABASE_ANON_KEY.contains("YOUR_SUPABASE")

    var currentUserId: String?
        get() = authPrefs.getString("user_id", null)
        private set(value) = authPrefs.edit().putString("user_id", value).apply()

    var currentUserEmail: String?
        get() = authPrefs.getString("user_email", null)
        private set(value) = authPrefs.edit().putString("user_email", value).apply()

    private var accessToken: String?
        get() = authPrefs.getString("access_token", null)
        set(value) = authPrefs.edit().putString("access_token", value).apply()

    private var refreshToken: String?
        get() = authPrefs.getString("refresh_token", null)
        set(value) = authPrefs.edit().putString("refresh_token", value).apply()

    val isLoggedIn: Boolean
        get() = !currentUserId.isNullOrBlank() && !accessToken.isNullOrBlank()

    suspend fun signUp(
        email: String,
        password: String,
        displayName: String,
        initialVault: CloudVaultSnapshot
    ): AuthResult = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            return@withContext AuthResult.Error("Add your SUPABASE_URL and SUPABASE_ANON_KEY in SupabaseSyncManager.kt")
        }
        try {
            val payload = JsonObject().apply {
                addProperty("email", email.trim())
                addProperty("password", password)
                add("data", JsonObject().apply {
                    addProperty("display_name", displayName.trim())
                })
            }
            val req = Request.Builder()
                .url("$SUPABASE_URL/auth/v1/signup")
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Content-Type", "application/json")
                .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: "{}"
                val json = JsonParser.parseString(raw).asJsonObject
                if (!resp.isSuccessful) {
                    val msg = json.get("msg")?.asString
                        ?: json.get("error_description")?.asString
                        ?: json.get("message")?.asString
                        ?: "Sign up failed (${resp.code})"
                    return@withContext AuthResult.Error(msg)
                }

                val token = json.get("access_token")?.asString
                val refresh = json.get("refresh_token")?.asString
                val userObj = json.getAsJsonObject("user") ?: json
                val uid = userObj.get("id")?.asString

                if (uid == null) {
                    return@withContext AuthResult.Error("Please check your email to confirm your account.")
                }
                if (token != null) {
                    accessToken = token
                    refreshToken = refresh
                    currentUserId = uid
                    currentUserEmail = email.trim()
                    pushCloudVault(initialVault.copy(displayName = displayName.trim()))
                }
                return@withContext AuthResult.Success(
                    userId = uid,
                    email = email.trim(),
                    displayName = displayName.trim(),
                    vault = initialVault.copy(displayName = displayName.trim())
                )
            }

        } catch (e: Exception) {
            return@withContext AuthResult.Error(e.localizedMessage ?: "Network error during sign up")
        }
    }

    suspend fun signIn(
        email: String,
        password: String,
        localFallbackVault: CloudVaultSnapshot
    ): AuthResult = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            return@withContext AuthResult.Error("Add your SUPABASE_URL and SUPABASE_ANON_KEY in SupabaseSyncManager.kt")
        }
        try {
            val payload = JsonObject().apply {
                addProperty("email", email.trim())
                addProperty("password", password)
            }
            val req = Request.Builder()
                .url("$SUPABASE_URL/auth/v1/token?grant_type=password")
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Content-Type", "application/json")
                .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: "{}"
                val json = JsonParser.parseString(raw).asJsonObject
                if (!resp.isSuccessful) {
                    val msg = json.get("error_description")?.asString
                        ?: json.get("msg")?.asString
                        ?: "Invalid email or password"
                    return@withContext AuthResult.Error(msg)
                }

                val token = json.get("access_token")?.asString ?: return@withContext AuthResult.Error("Missing access token")
                val refresh = json.get("refresh_token")?.asString
                val userObj = json.getAsJsonObject("user")
                val uid = userObj?.get("id")?.asString ?: return@withContext AuthResult.Error("Missing user ID")
                val metaName = userObj.getAsJsonObject("user_metadata")?.get("display_name")?.asString ?: ""

                accessToken = token
                refreshToken = refresh
                currentUserId = uid
                currentUserEmail = email.trim()

                val remoteVault = fetchCloudVault()
                val finalVault = if (remoteVault != null) {
                    remoteVault.copy(
                        displayName = remoteVault.displayName.ifBlank { metaName.ifBlank { email.substringBefore("@") } }
                    )
                } else {
                    val seeded = localFallbackVault.copy(
                        displayName = metaName.ifBlank { email.substringBefore("@") }
                    )
                    pushCloudVault(seeded)
                    seeded
                }

                return@withContext AuthResult.Success(
                    userId = uid,
                    email = email.trim(),
                    displayName = finalVault.displayName,
                    vault = finalVault
                )
            }
        } catch (e: Exception) {
            return@withContext AuthResult.Error(e.localizedMessage ?: "Network error during sign in")
        }
    }

    suspend fun sendPasswordReset(email: String): AuthResult = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            return@withContext AuthResult.Error("Add your SUPABASE_URL and SUPABASE_ANON_KEY in SupabaseSyncManager")
        }
        try {
            val payload = com.google.gson.JsonObject().apply {
                addProperty("email", email.trim())
            }
            val req = Request.Builder()
                .url("$SUPABASE_URL/auth/v1/recover")
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Content-Type", "application/json")
                .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext AuthResult.Error("Could not send reset email (${resp.code})")
                }
                return@withContext AuthResult.Success(
                    userId = "",
                    email = email.trim(),
                    displayName = "",
                    vault = null
                )
            }
        } catch (e: Exception) {
            return@withContext AuthResult.Error(e.localizedMessage ?: "Network error")
        }
    }

    suspend fun fetchCloudVault(): CloudVaultSnapshot? = withContext(Dispatchers.IO) {
        val uid = currentUserId ?: return@withContext null
        val token = accessToken ?: return@withContext null
        if (!isConfigured) return@withContext null

        try {
            val req = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/melo_user_vault?user_id=eq.$uid&select=*")
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 401 && refreshSession()) {
                    return@withContext fetchCloudVault()
                }
                if (!resp.isSuccessful) return@withContext null
                val raw = resp.body?.string() ?: "[]"
                val arr = JsonParser.parseString(raw).asJsonArray
                if (arr.size() == 0) return@withContext null

                val row = arr.get(0).asJsonObject
                val displayName = row.get("display_name")?.asString ?: ""
                val liked: List<Song> = gson.fromJson(
                    row.get("liked_songs"),
                    object : TypeToken<List<Song>>() {}.type
                ) ?: emptyList()
                val playlists: List<UserPlaylist> = gson.fromJson(
                    row.get("user_playlists"),
                    object : TypeToken<List<UserPlaylist>>() {}.type
                ) ?: emptyList()
                val recent: List<Song> = gson.fromJson(
                    row.get("recent_songs"),
                    object : TypeToken<List<Song>>() {}.type
                ) ?: emptyList()
                val queue: List<Song> = gson.fromJson(
                    row.get("saved_queue"),
                    object : TypeToken<List<Song>>() {}.type
                ) ?: emptyList()

                return@withContext CloudVaultSnapshot(
                    displayName = displayName,
                    likedSongs = liked,
                    playlists = playlists,
                    recentSongs = recent,
                    savedQueue = queue
                )
            }
        } catch (_: Exception) {
            return@withContext null
        }
    }

    suspend fun pushCloudVault(snapshot: CloudVaultSnapshot): Boolean = withContext(Dispatchers.IO) {
        val uid = currentUserId ?: return@withContext false
        val token = accessToken ?: return@withContext false
        if (!isConfigured) return@withContext false

        try {
            val bodyObj = JsonObject().apply {
                addProperty("user_id", uid)
                addProperty("display_name", snapshot.displayName)
                add("liked_songs", JsonParser.parseString(gson.toJson(snapshot.likedSongs)))
                add("user_playlists", JsonParser.parseString(gson.toJson(snapshot.playlists)))
                add("recent_songs", JsonParser.parseString(gson.toJson(snapshot.recentSongs.take(30))))
                add("saved_queue", JsonParser.parseString(gson.toJson(snapshot.savedQueue.take(40))))
            }

            val req = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/melo_user_vault")
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .header("Prefer", "resolution=merge-duplicates")
                .post(gson.toJson(bodyObj).toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 401 && refreshSession()) {
                    return@withContext pushCloudVault(snapshot)
                }
                return@withContext resp.isSuccessful
            }
        } catch (_: Exception) {
            return@withContext false
        }
    }

    private fun refreshSession(): Boolean {
        val refresh = refreshToken ?: return false
        return try {
            val payload = JsonObject().apply { addProperty("refresh_token", refresh) }
            val req = Request.Builder()
                .url("$SUPABASE_URL/auth/v1/token?grant_type=refresh_token")
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Content-Type", "application/json")
                .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val json = JsonParser.parseString(resp.body?.string() ?: "{}").asJsonObject
                accessToken = json.get("access_token")?.asString ?: return false
                refreshToken = json.get("refresh_token")?.asString ?: refresh
                true
            }
        } catch (_: Exception) {
            false
        }
    }
    fun getGoogleOAuthUrl(): String {
        val redirectUri = java.net.URLEncoder.encode("melomusic://auth-callback", "UTF-8")
        return "$SUPABASE_URL/auth/v1/authorize?provider=google&redirect_to=$redirectUri"
    }

    suspend fun completeOAuthSignIn(
        tokenFromOAuth: String,
        refreshFromOAuth: String?,
        localFallback: CloudVaultSnapshot
    ): AuthResult = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            return@withContext AuthResult.Error("Add your SUPABASE_URL and SUPABASE_ANON_KEY in SupabaseSyncManager.kt")
        }
        try {
            val userReq = Request.Builder()
                .url("$SUPABASE_URL/auth/v1/user")
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer $tokenFromOAuth")
                .get()
                .build()

            httpClient.newCall(userReq).execute().use { resp ->
                val raw = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) {
                    return@withContext AuthResult.Error("Google sign-in verification failed (${resp.code})")
                }
                val json = JsonParser.parseString(raw).asJsonObject
                val uid = json.get("id")?.asString ?: return@withContext AuthResult.Error("Missing user ID")
                val email = json.get("email")?.asString ?: ""
                val meta = json.getAsJsonObject("user_metadata")
                val fullName = meta?.get("full_name")?.asString
                    ?: meta?.get("name")?.asString
                    ?: meta?.get("display_name")?.asString
                    ?: email.substringBefore("@")

                accessToken = tokenFromOAuth
                if (!refreshFromOAuth.isNullOrBlank()) {
                    refreshToken = refreshFromOAuth
                }
                currentUserId = uid
                currentUserEmail = email

                val remoteVault = fetchCloudVault()
                val finalVault = if (remoteVault != null) {
                    remoteVault.copy(
                        displayName = remoteVault.displayName.ifBlank { fullName }
                    )
                } else {
                    val seeded = localFallback.copy(displayName = fullName)
                    pushCloudVault(seeded)
                    seeded
                }

                return@withContext AuthResult.Success(
                    userId = uid,
                    email = email,
                    displayName = finalVault.displayName,
                    vault = finalVault
                )
            }
        } catch (e: Exception) {
            return@withContext AuthResult.Error(e.localizedMessage ?: "Google sign-in failed")
        }
    }

    fun signOut() {
        authPrefs.edit().clear().apply()
    }
}