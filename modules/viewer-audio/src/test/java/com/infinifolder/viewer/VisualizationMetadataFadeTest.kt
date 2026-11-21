package com.infinifolder.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class VisualizationMetadataFadeTest {
    @Test fun labelFadesAfterTenSecondsAndBackInElevenSecondsBeforeEnd() {
        assertEquals(1f, VisualizationMetadataFade.alpha(9_999L, 60_000L), 0f)
        assertEquals(1f, VisualizationMetadataFade.alpha(10_000L, 60_000L), 0f)
        assertEquals(.5f, VisualizationMetadataFade.alpha(10_500L, 60_000L), .001f)
        assertEquals(0f, VisualizationMetadataFade.alpha(11_000L, 60_000L), 0f)
        assertEquals(0f, VisualizationMetadataFade.alpha(48_999L, 60_000L), 0f)
        assertEquals(0f, VisualizationMetadataFade.alpha(49_000L, 60_000L), 0f)
        assertEquals(.5f, VisualizationMetadataFade.alpha(49_500L, 60_000L), .001f)
        assertEquals(1f, VisualizationMetadataFade.alpha(50_000L, 60_000L), 0f)
    }

    @Test fun seekBackwardRestoresLabelAndShortTracksKeepMetadataReadable() {
        assertEquals(1f, VisualizationMetadataFade.alpha(3_000L, 60_000L), 0f)
        assertEquals(1f, VisualizationMetadataFade.alpha(19_000L, 20_000L), 0f)
        assertEquals(0f, VisualizationMetadataFade.alpha(12_000L, 0L), 0f)
    }
}
