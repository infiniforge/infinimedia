package com.infinifolder.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkPaletteDetectorTest {
    @Test fun returnsUniqueQuantizedColorsInPopularityOrder() {
        val teal = 0xff2080a0.toInt()
        val nearTeal = 0xff2181a1.toInt() // Same six-bit RGB bucket as teal.
        val red = 0xffc02020.toInt()
        val colors = intArrayOf(teal, nearTeal, teal, red, red, red, red, red)

        val palette = ArtworkPaletteDetector.detectArgb(colors)

        assertEquals(2, palette.colors.size)
        assertEquals((red ushr 16 and 0xff) / 255f, palette.colors.first().red, .03f)
        assertEquals(2f, palette.toChannels().getValue("fx.palette.count"))
        assertTrue(palette.toChannels().containsKey("fx.palette.color.1.b"))
        assertTrue(!palette.toChannels().containsKey("fx.palette.color.2.r"))
    }

    @Test fun ignoresTransparentSamplesAndHasAColorWhenArtworkHasNoOpaqueSamples() {
        val palette = ArtworkPaletteDetector.detectArgb(intArrayOf(0x00112233, 0xff00ff00.toInt()))
        assertEquals(1, palette.colors.size)
        assertEquals(0f, palette.colors.single().red, .001f)
        assertEquals(1f, palette.colors.single().green, .001f)
        assertEquals(0f, palette.colors.single().blue, .001f)

        val empty = ArtworkPaletteDetector.detectArgb(intArrayOf(0x00000000))
        assertEquals(listOf(ArtworkPaletteColor.DEFAULT), empty.colors)
    }
}
