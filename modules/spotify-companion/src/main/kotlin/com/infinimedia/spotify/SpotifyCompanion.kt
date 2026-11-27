package com.infinimedia.spotify

/** Extra-approval surfaces stay off independently; ordinary Web API access is not gated by these flags. */
object SpotifyAdditionalApprovalFeatures {
    const val COMPREHENSIVE_DISCOGRAPHY = false
    const val EXTERNAL_RELEASE_CLASSIFICATION = false
    const val LOCAL_SPOTIFY_PLAYLIST_EXPORT = false
    const val AUDIO_REACTIVE_SPOTIFY_VISUALS = false
}

data class SpotifyStoredToken(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
    val scopes: Set<String>,
)

class SpotifyApiException(
    val statusCode: Int?,
    message: String,
    val retryAfterSeconds: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
