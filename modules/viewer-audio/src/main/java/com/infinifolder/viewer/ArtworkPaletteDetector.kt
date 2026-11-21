package com.infinifolder.viewer

import android.graphics.Bitmap

/** Deterministic six-bit RGB palette extracted from a small spatial sample of artwork. */
internal object ArtworkPaletteDetector {
    private const val SAMPLE_SIDE = 32
    private const val BUCKET_COUNT = 1 shl 6
    const val MAX_COLORS = 8

    fun detect(bitmap: Bitmap): ArtworkPalette {
        require(bitmap.width > 0 && bitmap.height > 0)
        val samples = IntArray(SAMPLE_SIDE * SAMPLE_SIDE)
        for (sampleY in 0 until SAMPLE_SIDE) {
            val y = ((sampleY.toLong() * bitmap.height) / SAMPLE_SIDE).toInt().coerceAtMost(bitmap.height - 1)
            for (sampleX in 0 until SAMPLE_SIDE) {
                val x = ((sampleX.toLong() * bitmap.width) / SAMPLE_SIDE).toInt().coerceAtMost(bitmap.width - 1)
                samples[sampleY * SAMPLE_SIDE + sampleX] = bitmap.getPixel(x, y)
            }
        }
        return detectArgb(samples)
    }

    /** Quantizes RGB to 2 bits per channel, merges duplicate bins, and ranks by sample count. */
    fun detectArgb(colors: IntArray, maxColors: Int = MAX_COLORS): ArtworkPalette {
        require(colors.isNotEmpty())
        require(maxColors in 1..MAX_COLORS)
        val counts = IntArray(BUCKET_COUNT)
        val redSums = IntArray(BUCKET_COUNT)
        val greenSums = IntArray(BUCKET_COUNT)
        val blueSums = IntArray(BUCKET_COUNT)

        for (color in colors) {
            if ((color ushr 24 and 0xff) < 128) continue
            val red = color ushr 16 and 0xff
            val green = color ushr 8 and 0xff
            val blue = color and 0xff
            val bucket = ((red shr 6) shl 4) or ((green shr 6) shl 2) or (blue shr 6)
            counts[bucket]++
            redSums[bucket] += red
            greenSums[bucket] += green
            blueSums[bucket] += blue
        }

        val rankedBins = counts.indices.filter { counts[it] > 0 }
            .sortedWith(compareByDescending<Int> { counts[it] }.thenBy { it })
            .take(maxColors)
        if (rankedBins.isEmpty()) return ArtworkPalette(listOf(ArtworkPaletteColor.DEFAULT))
        return ArtworkPalette(rankedBins.map { bucket ->
            val count = counts[bucket]
            ArtworkPaletteColor(
                red = redSums[bucket].toFloat() / count / 255f,
                green = greenSums[bucket].toFloat() / count / 255f,
                blue = blueSums[bucket].toFloat() / count / 255f,
            )
        })
    }
}

internal data class ArtworkPalette(val colors: List<ArtworkPaletteColor>) {
    init { require(colors.isNotEmpty() && colors.size <= ArtworkPaletteDetector.MAX_COLORS) }

    fun toChannels(): Map<String, Float> = buildMap {
        put("fx.palette.count", colors.size.toFloat())
        colors.forEachIndexed { index, color ->
            put("fx.palette.color.$index.r", color.red)
            put("fx.palette.color.$index.g", color.green)
            put("fx.palette.color.$index.b", color.blue)
        }
    }
}

internal data class ArtworkPaletteColor(val red: Float, val green: Float, val blue: Float) {
    companion object {
        val DEFAULT = ArtworkPaletteColor(0.18f, 0.72f, 1f)
    }
}
