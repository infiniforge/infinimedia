package com.infinifolder.viewer

/** Playback-position schedule for the player-owned artwork metadata overlay. */
internal object VisualizationMetadataFade {
    private const val HOLD_MS = 10_000L
    private const val FADE_MS = 1_000L
    private const val FADE_IN_LEAD_MS = 11_000L

    fun alpha(positionMs: Long, durationMs: Long): Float {
        val position = positionMs.coerceAtLeast(0L)
        val duration = durationMs.coerceAtLeast(0L)
        if (duration in 1L..(HOLD_MS + FADE_MS + FADE_IN_LEAD_MS)) return 1f
        if (position < HOLD_MS) return 1f
        if (position < HOLD_MS + FADE_MS) {
            return 1f - (position - HOLD_MS).toFloat() / FADE_MS
        }
        if (duration == 0L) return 0f
        val fadeInStart = duration - FADE_IN_LEAD_MS
        if (position < fadeInStart) return 0f
        if (position < fadeInStart + FADE_MS) {
            return (position - fadeInStart).toFloat() / FADE_MS
        }
        return 1f
    }
}
