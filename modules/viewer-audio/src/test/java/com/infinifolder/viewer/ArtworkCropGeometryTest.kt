package com.infinifolder.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkCropGeometryTest {
    @Test fun squareArtworkIsCenteredOnWidePaneWithNegativeVerticalOverflow() {
        val crop = ArtworkCropGeometry.centerCropSourceRect(1000, 1000, 1600, 900)
        assertEquals(0f, crop.left, 0.0001f)
        assertEquals(7f / 32f, crop.bottom, 0.0001f)
        assertEquals(1f, crop.right, 0.0001f)
        assertEquals(25f / 32f, crop.top, 0.0001f)
    }

    @Test fun matchingAspectKeepsTheWholeArtwork() {
        val crop = ArtworkCropGeometry.centerCropSourceRect(1600, 900, 1280, 720)
        assertEquals(0f, crop.left, 0f)
        assertEquals(0f, crop.bottom, 0f)
        assertEquals(1f, crop.right, 0f)
        assertEquals(1f, crop.top, 0f)
    }
}
