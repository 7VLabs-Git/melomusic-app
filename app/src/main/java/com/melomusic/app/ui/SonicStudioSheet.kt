// File: app/src/main/java/com/melomusic/app/ui/SonicStudioSheet.kt
package com.melomusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.melomusic.app.playback.MeloAudioEffects

private val VoidBlack = Color(0xFF030304)
private val DeepSurface = Color(0xFF0D0D11)
private val CardSurface = Color(0xFF14141A)
private val GlassBorder = Color(0x22FFFFFF)
private val MeloGold = Color(0xFFFFCD01)
private val MeloCyan = Color(0xFF00E5FF)
private val MeloAccent = Color(0xFFFF5252)
private val TextPure = Color(0xFFFFFFFF)
private val TextMuted = Color(0x9EFFFFFF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SonicStudioModalSheet(
    audioEffects: MeloAudioEffects,
    onDismiss: () -> Unit,
    onTriggerSleepPause: () -> Unit
) {
    val fxState by audioEffects.state.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DeepSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 36.dp)
        ) {
            // Header + Master Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MeloGold.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = MeloGold,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Sonic DSP Studio",
                            fontSize = 20.sp,
                            color = TextPure,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Serif
                        )
                        Text(
                            text = if (fxState.isEnabled) "Active • ${fxState.activePresetName}" else "Bypassed (Pure Bitstream)",
                            fontSize = 12.sp,
                            color = if (fxState.isEnabled) MeloGold else TextMuted
                        )
                    }
                }
                Switch(
                    checked = fxState.isEnabled,
                    onCheckedChange = { audioEffects.setEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = VoidBlack,
                        checkedTrackColor = MeloGold,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = VoidBlack
                    )
                )
            }

            Spacer(Modifier.height(20.dp))

            // Preset Selector Pills
            Text(
                text = "ACOUSTIC PRESETS",
                fontSize = 10.5.sp,
                letterSpacing = 1.3.sp,
                color = TextMuted,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(audioEffects.customPresets.keys.toList()) { preset ->
                    val isSelected = fxState.activePresetName == preset
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) MeloGold else CardSurface)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) MeloGold else GlassBorder,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                if (!fxState.isEnabled) audioEffects.setEnabled(true)
                                audioEffects.selectPreset(preset)
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = preset,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) VoidBlack else TextPure
                        )
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            // 5-Band Equalizer Sliders
            if (fxState.bands.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(CardSurface)
                        .border(1.dp, GlassBorder, RoundedCornerShape(18.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    fxState.bands.forEach { band ->
                        val freqLabel = if (band.centerFreqHz >= 1000) {
                            "${band.centerFreqHz / 1000} kHz"
                        } else {
                            "${band.centerFreqHz} Hz"
                        }
                        val dbValue = band.currentLevelMb / 100f
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = freqLabel,
                                color = TextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.width(52.dp)
                            )
                            Slider(
                                value = band.currentLevelMb.toFloat(),
                                onValueChange = {
                                    if (!fxState.isEnabled) audioEffects.setEnabled(true)
                                    audioEffects.setBandLevel(band.index, it.toInt().toShort())
                                },
                                valueRange = band.minLevelMb.toFloat()..band.maxLevelMb.toFloat(),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(28.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = MeloGold,
                                    activeTrackColor = MeloGold,
                                    inactiveTrackColor = VoidBlack
                                )
                            )
                            Text(
                                text = "%+.1f dB".format(dbValue),
                                color = TextPure,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(54.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }

            // Analog Bass Boost & Spatial Virtualizer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(CardSurface)
                        .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Speaker,
                            contentDescription = null,
                            tint = MeloAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Sub-Bass Punch",
                            color = TextPure,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Slider(
                        value = fxState.bassBoostStrength.toFloat(),
                        onValueChange = {
                            if (!fxState.isEnabled) audioEffects.setEnabled(true)
                            audioEffects.setBassBoost(it.toInt())
                        },
                        valueRange = 0f..1000f,
                        colors = SliderDefaults.colors(
                            thumbColor = MeloAccent,
                            activeTrackColor = MeloAccent,
                            inactiveTrackColor = VoidBlack
                        )
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(CardSurface)
                        .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.SurroundSound,
                            contentDescription = null,
                            tint = MeloCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "3D Soundstage",
                            color = TextPure,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Slider(
                        value = fxState.virtualizerStrength.toFloat(),
                        onValueChange = {
                            if (!fxState.isEnabled) audioEffects.setEnabled(true)
                            audioEffects.setVirtualizer(it.toInt())
                        },
                        valueRange = 0f..1000f,
                        colors = SliderDefaults.colors(
                            thumbColor = MeloCyan,
                            activeTrackColor = MeloCyan,
                            inactiveTrackColor = VoidBlack
                        )
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Sleep Timer Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Bedtime,
                        contentDescription = null,
                        tint = MeloCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (fxState.sleepTimerRemainingMs > 0L) {
                            val mins = (fxState.sleepTimerRemainingMs / 60000L) + 1
                            "Sleep Timer: ${mins}m left"
                        } else {
                            "Sanctuary Sleep Timer"
                        },
                        color = TextPure,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0 to "Off", 15 to "15m", 30 to "30m", 45 to "45m", 60 to "60m").forEach { (mins, label) ->
                    val isTimerActive = (mins == 0 && fxState.sleepTimerRemainingMs == 0L) ||
                            (mins > 0 && fxState.sleepTimerRemainingMs in ((mins - 1) * 60_000L + 1)..(mins * 60_000L))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isTimerActive) MeloCyan else CardSurface)
                            .border(1.dp, if (isTimerActive) MeloCyan else GlassBorder, RoundedCornerShape(10.dp))
                            .clickable {
                                audioEffects.startSleepTimer(mins) {
                                    onTriggerSleepPause()
                                }
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isTimerActive) VoidBlack else TextPure
                        )
                    }
                }
            }
        }
    }
}