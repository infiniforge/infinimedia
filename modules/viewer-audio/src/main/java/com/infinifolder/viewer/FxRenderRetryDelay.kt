package com.infinifolder.viewer

/** Bounded retry cadence for transient EGL surface/context failures. */
internal object FxRenderRetryDelay {
    private const val INITIAL_MILLIS = 100L
    private const val MAX_MILLIS = 1_000L

    fun forFailureCount(failures: Int): Long {
        val exponent = (failures.coerceAtLeast(1) - 1).coerceAtMost(4)
        return (INITIAL_MILLIS shl exponent).coerceAtMost(MAX_MILLIS)
    }
}
