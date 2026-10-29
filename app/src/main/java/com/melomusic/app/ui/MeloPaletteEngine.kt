// File: app/src/main/java/com/melomusic/app/ui/MeloPaletteEngine.kt
package com.melomusic.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class MeloPalette(
    val primary: Color,
    val secondary: Color
)

object MeloPaletteEngine {
    private val memoryCache = LruCache<String, MeloPalette>(100)

    val DefaultPalette = MeloPalette(
        primary = Color(0xFFFF5252),
        secondary = Color(0xFF00E5FF)
    )

    fun getCached(imageUrl: String?): MeloPalette? {
        if (imageUrl.isNullOrBlank()) return null
        return memoryCache.get(imageUrl)
    }

    suspend fun extractPalette(context: Context, imageUrl: String?): MeloPalette {
        if (imageUrl.isNullOrBlank()) return DefaultPalette
        memoryCache.get(imageUrl)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .size(48, 48) // Micro-sample size is 4x faster and eliminates GC stutter
                    .bitmapConfig(Bitmap.Config.RGB_565)
                    .allowHardware(false)
                    .build()

                val result = (context.imageLoader.execute(request) as? SuccessResult)?.drawable
                val bitmap = (result as? BitmapDrawable)?.bitmap ?: return@withContext DefaultPalette

                var bestColor1 = android.graphics.Color.DKGRAY
                var bestColor2 = android.graphics.Color.parseColor("#00E5FF")
                var highestScore1 = -1f
                var highestScore2 = -1f
                val hsv = FloatArray(3)

                val step = (bitmap.width / 12).coerceAtLeast(1)
                for (x in 0 until bitmap.width step step) {
                    for (y in 0 until bitmap.height step step) {
                        val pixel = bitmap.getPixel(x, y)
                        android.graphics.Color.colorToHSV(pixel, hsv)
                        val sat = hsv[1]
                        val value = hsv[2]

                        // Prioritize rich, saturated jewel tones suitable for blurred auras
                        if (sat > 0.20f && value > 0.20f) {
                            val score = sat * 2.5f + (1.0f - abs(value - 0.65f))
                            if (score > highestScore1) {
                                highestScore2 = highestScore1
                                bestColor2 = bestColor1
                                highestScore1 = score
                                bestColor1 = pixel
                            } else if (score > highestScore2 && abs(pixel - bestColor1) > 40000) {
                                highestScore2 = score
                                bestColor2 = pixel
                            }
                        }
                    }
                }

                // Boost vibrancy so dark album art never produces muddy black auras
                fun boost(c: Int): Color {
                    val h = FloatArray(3)
                    android.graphics.Color.colorToHSV(c, h)
                    h[1] = h[1].coerceIn(0.60f, 0.95f)
                    h[2] = h[2].coerceIn(0.70f, 0.95f)
                    return Color(android.graphics.Color.HSVToColor(h))
                }

                val palette = MeloPalette(
                    primary = boost(bestColor1),
                    secondary = boost(bestColor2)
                )
                memoryCache.put(imageUrl, palette)
                palette
            } catch (_: Exception) {
                DefaultPalette
            }
        }
    }
}