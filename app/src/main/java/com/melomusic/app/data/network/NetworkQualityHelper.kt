// File: app/src/main/java/com/melomusic/app/data/network/NetworkQualityHelper.kt
package com.melomusic.app.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkQualityHelper {
    enum class AudioBitratePreference {
        HIGH_320,
        STANDARD_160
    }

    fun getPreferredBitrate(context: Context): AudioBitratePreference {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return AudioBitratePreference.STANDARD_160

        val network = connectivityManager.activeNetwork ?: return AudioBitratePreference.STANDARD_160
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return AudioBitratePreference.STANDARD_160

        val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val isUnmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        return if (isWifi && isUnmetered) {
            AudioBitratePreference.HIGH_320
        } else {
            AudioBitratePreference.STANDARD_160
        }
    }
}