// File: app/src/main/java/com/melomusic/app/playback/MeloAudioEffects.kt
package com.melomusic.app.playback

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EqualizerBand(
    val index: Short,
    val centerFreqHz: Int,
    val currentLevelMb: Short,
    val minLevelMb: Short,
    val maxLevelMb: Short
)

data class AudioFxState(
    val isEnabled: Boolean = false,
    val activePresetName: String = "Flat",
    val bassBoostStrength: Int = 0, // 0..1000
    val virtualizerStrength: Int = 0, // 0..1000
    val bands: List<EqualizerBand> = emptyList(),
    val sleepTimerRemainingMs: Long = 0L
)

class MeloAudioEffects(context: Context, private val scope: CoroutineScope) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("melo_vault", Context.MODE_PRIVATE)

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var boundSessionId: Int = 0
    private var sleepTimerJob: Job? = null

    private val _state = MutableStateFlow(
        AudioFxState(
            isEnabled = prefs.getBoolean("fx_enabled", false),
            activePresetName = prefs.getString("fx_preset", "Flat") ?: "Flat",
            bassBoostStrength = prefs.getInt("fx_bass", 0),
            virtualizerStrength = prefs.getInt("fx_virt", 0)
        )
    )
    val state: StateFlow<AudioFxState> = _state.asStateFlow()

    val customPresets: Map<String, List<Short>> = mapOf(
        "Flat" to listOf(0, 0, 0, 0, 0),
        "DHH Cypher (Punchy Bass)" to listOf(600, 350, -100, 250, 400),
        "Lo-Fi Warmth" to listOf(450, 300, 0, -250, -400),
        "Acoustic & Vocals" to listOf(-150, 150, 450, 350, 200),
        "Gym Phonk (Extreme)" to listOf(850, 500, -150, 300, 550)
    )

    fun bindToAudioSession(audioSessionId: Int) {
        if (audioSessionId <= 0 || audioSessionId == boundSessionId) return
        releaseEffects()
        boundSessionId = audioSessionId

        runCatching {
            val enabled = _state.value.isEnabled
            equalizer = Equalizer(0, audioSessionId).apply {
                this.enabled = enabled
            }
            bassBoost = BassBoost(0, audioSessionId).apply {
                this.enabled = enabled
                if (strengthSupported) {
                    setStrength(_state.value.bassBoostStrength.toShort().coerceIn(0, 1000))
                }
            }
            virtualizer = Virtualizer(0, audioSessionId).apply {
                this.enabled = enabled
                if (strengthSupported) {
                    setStrength(_state.value.virtualizerStrength.toShort().coerceIn(0, 1000))
                }
            }
            applyPresetInternal(_state.value.activePresetName)
            refreshBandSnapshot()
        }
    }

    private fun refreshBandSnapshot() {
        val eq = equalizer ?: return
        val range = eq.bandLevelRange
        val minMb = range.getOrElse(0) { -1500 }
        val maxMb = range.getOrElse(1) { 1500 }

        val bandList = (0 until eq.numberOfBands).map { i ->
            val bandIdx = i.toShort()
            EqualizerBand(
                index = bandIdx,
                centerFreqHz = eq.getCenterFreq(bandIdx) / 1000,
                currentLevelMb = eq.getBandLevel(bandIdx),
                minLevelMb = minMb,
                maxLevelMb = maxMb
            )
        }
        _state.value = _state.value.copy(bands = bandList)
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("fx_enabled", enabled).apply()
        runCatching {
            equalizer?.enabled = enabled
            bassBoost?.enabled = enabled
            virtualizer?.enabled = enabled
        }
        _state.value = _state.value.copy(isEnabled = enabled)
    }

    fun selectPreset(presetName: String) {
        prefs.edit().putString("fx_preset", presetName).apply()
        applyPresetInternal(presetName)
        refreshBandSnapshot()
    }

    private fun applyPresetInternal(presetName: String) {
        val eq = equalizer ?: return
        val gains = customPresets[presetName] ?: return
        val range = eq.bandLevelRange
        val minMb = range.getOrElse(0) { -1500 }
        val maxMb = range.getOrElse(1) { 1500 }

        for (i in 0 until minOf(eq.numberOfBands.toInt(), gains.size)) {
            val target = gains[i].coerceIn(minMb, maxMb)
            runCatching { eq.setBandLevel(i.toShort(), target) }
        }
        _state.value = _state.value.copy(activePresetName = presetName)
    }

    fun setBandLevel(bandIndex: Short, levelMb: Short) {
        val eq = equalizer ?: return
        runCatching {
            eq.setBandLevel(bandIndex, levelMb)
            prefs.edit().putString("fx_preset", "Custom").apply()
            _state.value = _state.value.copy(activePresetName = "Custom")
            refreshBandSnapshot()
        }
    }

    fun setBassBoost(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        prefs.edit().putInt("fx_bass", clamped).apply()
        runCatching {
            if (bassBoost?.strengthSupported == true) {
                bassBoost?.setStrength(clamped.toShort())
            }
        }
        _state.value = _state.value.copy(bassBoostStrength = clamped)
    }

    fun setVirtualizer(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        prefs.edit().putInt("fx_virt", clamped).apply()
        runCatching {
            if (virtualizer?.strengthSupported == true) {
                virtualizer?.setStrength(clamped.toShort())
            }
        }
        _state.value = _state.value.copy(virtualizerStrength = clamped)
    }

    fun startSleepTimer(minutes: Int, onFadeOutAndPause: () -> Unit) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _state.value = _state.value.copy(sleepTimerRemainingMs = 0L)
            return
        }
        val totalMs = minutes * 60_000L
        _state.value = _state.value.copy(sleepTimerRemainingMs = totalMs)

        sleepTimerJob = scope.launch {
            var remaining = totalMs
            while (remaining > 0L) {
                delay(1000L)
                remaining -= 1000L
                _state.value = _state.value.copy(sleepTimerRemainingMs = remaining)
            }
            onFadeOutAndPause()
        }
    }

    fun releaseEffects() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        equalizer = null
        bassBoost = null
        virtualizer = null
        boundSessionId = 0
    }
}