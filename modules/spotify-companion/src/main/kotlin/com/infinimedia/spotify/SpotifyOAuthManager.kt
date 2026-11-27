package com.infinimedia.spotify

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Authorization Code with PKCE for the Web API; no client secret is used or stored. */
class SpotifyOAuthManager(context: android.content.Context) {
    private val appContext = context.applicationContext
    private val store = SpotifySecureStore(appContext)

    fun clientId(): String = store.clientId
    fun redirectUri(): String = store.redirectUri
    fun saveConfiguration(clientId: String, redirectUri: String) {
        val normalizedClientId = clientId.trim()
        val normalizedRedirect = redirectUri.trim()
        require(normalizedClientId.isNotBlank()) { "Enter your Spotify client ID." }
        validateRedirect(normalizedRedirect)
        if (store.clientId.isNotBlank() && store.clientId != normalizedClientId) store.disconnect()
        if (store.redirectUri != normalizedRedirect) store.clearPending()
        store.clientId = normalizedClientId
        store.redirectUri = normalizedRedirect
    }

    fun hasAccessToken(): Boolean = store.token() != null
    fun grantedScopes(): Set<String> = store.token()?.scopes.orEmpty()

    fun authorize(activity: Activity, additionalScopes: Set<String> = emptySet()) {
        val clientId = store.clientId.trim()
        require(clientId.isNotEmpty()) { "Save your Spotify client ID first." }
        val redirect = store.redirectUri.trim()
        validateRedirect(redirect)
        val scopes = DEFAULT_SCOPES + additionalScopes
        val verifier = randomUrlSafe(32)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val state = randomUrlSafe(32)
        store.savePending(PendingSpotifyAuthorization(clientId, redirect, verifier, state, scopes))
        val uri = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirect)
            .appendQueryParameter("scope", scopes.sorted().joinToString(" "))
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .build()
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (error: Exception) {
            store.clearPending()
            throw SpotifyApiException(null, "No browser is available for Spotify authorization.", cause = error)
        }
    }

    /** Called only by the registered redirect activity after state validation. */
    fun completeAuthorization(callback: Uri): String {
        val pending = store.pending() ?: throw SpotifyApiException(null, "The Spotify sign-in request expired. Start again.")
        val returnedRedirect = callback.buildUpon().clearQuery().fragment(null).build().toString()
        if (returnedRedirect != pending.redirectUri) {
            store.clearPending()
            throw SpotifyApiException(null, "Spotify returned to a different redirect URI. Check the registered URI and try again.")
        }
        val returnedState = callback.getQueryParameter("state").orEmpty()
        if (!MessageDigest.isEqual(
                pending.state.toByteArray(StandardCharsets.UTF_8),
                returnedState.toByteArray(StandardCharsets.UTF_8),
            )) {
            store.clearPending()
            throw SpotifyApiException(null, "Spotify sign-in state did not match. Start again.")
        }
        callback.getQueryParameter("error")?.let { error ->
            store.clearPending()
            throw SpotifyApiException(null, if (error == "access_denied") "Spotify authorization was cancelled." else "Spotify authorization failed: $error")
        }
        val code = callback.getQueryParameter("code")
            ?: throw SpotifyApiException(null, "Spotify did not return an authorization code.")
        val payload = linkedMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to pending.redirectUri,
            "client_id" to pending.clientId,
            "code_verifier" to pending.verifier,
        )
        try {
            val response = tokenRequest(payload)
            val access = response.optString("access_token")
            if (access.isBlank()) throw SpotifyApiException(null, "Spotify returned an empty access token.")
            val refresh = response.optString("refresh_token")
            val expiresIn = response.optLong("expires_in", 3600L)
            val scopes = response.optString("scope").split(' ').filter(String::isNotBlank).toSet().ifEmpty { pending.scopes }
            store.saveToken(SpotifyStoredToken(access, refresh, System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(expiresIn), scopes))
            return "Spotify Web API authorized."
        } finally {
            store.clearPending()
        }
    }

    internal fun accessToken(): String = synchronized(refreshLock) {
        val current = store.token() ?: throw SpotifyApiException(401, "Connect to the Spotify Web API first.")
        if (current.expiresAtEpochMs - System.currentTimeMillis() > REFRESH_EARLY_MS) return@synchronized current.accessToken
        refreshLocked(current).accessToken
    }

    internal fun refreshAfterUnauthorized(rejectedToken: String): String = synchronized(refreshLock) {
        val current = store.token() ?: throw SpotifyApiException(401, "Spotify authorization expired. Connect again.")
        if (current.accessToken != rejectedToken && current.expiresAtEpochMs > System.currentTimeMillis()) return@synchronized current.accessToken
        refreshLocked(current).accessToken
    }

    fun disconnect() = store.disconnect()

    private fun refreshLocked(current: SpotifyStoredToken): SpotifyStoredToken {
        if (current.refreshToken.isBlank()) {
            store.disconnect()
            throw SpotifyApiException(401, "Spotify authorization expired. Connect again.")
        }
        val body = linkedMapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to current.refreshToken,
            "client_id" to store.clientId,
        )
        try {
            val response = tokenRequest(body)
            val access = response.optString("access_token")
            if (access.isBlank()) throw SpotifyApiException(401, "Spotify token refresh failed. Connect again.")
            val refresh = response.optString("refresh_token").ifBlank { current.refreshToken }
            val scopes = response.optString("scope").split(' ').filter(String::isNotBlank).toSet().ifEmpty { current.scopes }
            return SpotifyStoredToken(
                access,
                refresh,
                System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(response.optLong("expires_in", 3600L)),
                scopes,
            ).also(store::saveToken)
        } catch (error: SpotifyApiException) {
            if (error.statusCode == 400 || error.statusCode == 401) store.disconnect()
            throw error
        }
    }

    private fun tokenRequest(fields: Map<String, String>): JSONObject {
        val connection = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val body = fields.entries.joinToString("&") { "${form(it.key)}=${form(it.value)}" }
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()
                throw SpotifyApiException(status, message?.takeIf(String::isNotBlank) ?: "Spotify token request failed ($status).")
            }
            return JSONObject(raw)
        } catch (error: SpotifyApiException) {
            throw error
        } catch (error: Exception) {
            throw SpotifyApiException(null, "Could not reach Spotify authorization service.", cause = error)
        } finally {
            connection.disconnect()
        }
    }

    private fun validateRedirect(value: String) {
        val uri = runCatching { Uri.parse(value) }.getOrNull()
        require(uri != null && uri.scheme == REDIRECT_SCHEME && uri.host != null && uri.query == null && uri.fragment == null) {
            "Redirect URI must use the registered $REDIRECT_SCHEME app-link scheme without a query or fragment."
        }
    }

    private fun randomUrlSafe(size: Int): String = Base64.encodeToString(
        ByteArray(size).also(SecureRandom()::nextBytes),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private fun form(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
        const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        const val REDIRECT_SCHEME = "com.infinimedia.spotify"
        const val DEFAULT_REDIRECT_URI = "com.infinimedia.spotify://callback"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
        const val REFRESH_EARLY_MS = 60_000L
        val DEFAULT_SCOPES = setOf(
            "playlist-read-private",
            "playlist-read-collaborative",
            "playlist-modify-private",
        )
        val refreshLock = Any()
    }
}
