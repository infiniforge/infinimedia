package com.infinifolder.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class FxRenderRetryDelayTest {
    @Test fun retryDelayBacksOffAndCapsAtOneSecond() {
        assertEquals(100L, FxRenderRetryDelay.forFailureCount(1))
        assertEquals(200L, FxRenderRetryDelay.forFailureCount(2))
        assertEquals(400L, FxRenderRetryDelay.forFailureCount(3))
        assertEquals(800L, FxRenderRetryDelay.forFailureCount(4))
        assertEquals(1_000L, FxRenderRetryDelay.forFailureCount(5))
        assertEquals(1_000L, FxRenderRetryDelay.forFailureCount(9))
    }
}
