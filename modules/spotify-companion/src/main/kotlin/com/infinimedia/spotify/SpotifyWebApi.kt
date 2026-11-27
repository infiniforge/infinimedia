package com.infinimedia.spotify

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Small, bounded Web API client for endpoints supported in Spotify Development Mode. */
class SpotifyWebApi(context: android.content.Context) {
    private val auth = SpotifyOAuthManager(context)

    fun search(query: String, entityType: String, offset: Int = 0): JSONObject = get(
        "/search",
        mapOf("q" to query, "type" to entityType, "limit" to "10", "offset" to offset.toString()),
    )

    fun artistAlbums(artistId: String, offset: Int = 0): JSONObject = get(
        "/artists/${Uri.encode(artistId)}/albums",
        mapOf("limit" to "10", "offset" to offset.toString()),
    )

    fun album(albumId: String): JSONObject = get("/albums/${Uri.encode(albumId)}")

    fun albumTracks(albumId: String, offset: Int = 0): JSONObject = get(
        "/albums/${Uri.encode(albumId)}/tracks",
        mapOf("limit" to "20", "offset" to offset.toString()),
    )

    fun myPlaylists(offset: Int = 0): JSONObject = get(
        "/me/playlists",
        mapOf("limit" to "20", "offset" to offset.toString()),
    )

    fun playlistItems(playlistId: String, offset: Int = 0): JSONObject = get(
        "/playlists/${Uri.encode(playlistId)}/items",
        mapOf("limit" to "20", "offset" to offset.toString()),
    )

    fun createPlaylist(name: String, description: String, isPublic: Boolean): JSONObject = request(
        "POST", "/me/playlists", body = JSONObject()
            .put("name", name)
            .put("description", description)
            .put("public", isPublic),
    )

    fun addItems(playlistId: String, uris: List<String>): JSONObject {
        require(uris.isNotEmpty()) { "Select at least one Spotify track." }
        require(uris.size <= MAX_PLAYLIST_WRITE_ITEMS) { "Spotify accepts up to 100 items per playlist write." }
        return request(
            "POST", "/playlists/${Uri.encode(playlistId)}/items",
            body = JSONObject().put("uris", JSONArray(uris)),
        )
    }

    fun removeItem(playlistId: String, uri: String, snapshotId: String?): JSONObject {
        val body = JSONObject().put("items", JSONArray().put(JSONObject().put("uri", uri)))
        if (!snapshotId.isNullOrBlank()) body.put("snapshot_id", snapshotId)
        return request("DELETE", "/playlists/${Uri.encode(playlistId)}/items", body = body)
    }

    private fun get(path: String, query: Map<String, String> = emptyMap()): JSONObject = request("GET", path, query)

    private fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
    ): JSONObject {
        val suffix = query.entries.joinToString("&") { "${form(it.key)}=${form(it.value)}" }
        val url = API_ROOT + path + if (suffix.isEmpty()) "" else "?$suffix"
        var token = auth.accessToken()
        var authRetry = false
        var serverRetry = false
        while (true) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            try {
                if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
                val status = connection.responseCode
                if (status == 401 && !authRetry) {
                    token = auth.refreshAfterUnauthorized(token)
                    authRetry = true
                    continue
                }
                if (status == 429) {
                    val retryAfter = connection.getHeaderField("Retry-After")?.toIntOrNull()
                    val errorBody = connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                    val errorObject = runCatching { JSONObject(errorBody).optJSONObject("error") }.getOrNull()
                    if (errorObject?.optString("reason") == "QUOTA_EXCEEDED") {
                        throw SpotifyApiException(429, "Spotify's Development Mode request quota is exhausted. Try again later.", retryAfter)
                    }
                    if (retryAfter != null && retryAfter in 1..3 && !serverRetry) {
                        Thread.sleep(retryAfter * 1000L)
                        serverRetry = true
                        continue
                    }
                    throw SpotifyApiException(429, "Spotify rate limit reached. Try again${retryAfter?.let { " in $it seconds" } ?: " later"}.", retryAfter)
                }
                if (status in 500..599 && !serverRetry && method == "GET") {
                    Thread.sleep(400L)
                    serverRetry = true
                    continue
                }
                val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val reason = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()
                    val message = when (status) {
                        401 -> "Spotify authorization expired. Reconnect to Spotify."
                        403 -> reason?.takeIf(String::isNotBlank) ?: "Spotify denied this action. Check playlist ownership, account eligibility, or granted scopes."
                        404 -> "That Spotify item is no longer available."
                        else -> reason?.takeIf(String::isNotBlank) ?: "Spotify request failed ($status)."
                    }
                    throw SpotifyApiException(status, message)
                }
                return if (raw.isBlank()) JSONObject() else JSONObject(raw)
            } catch (error: SpotifyApiException) {
                throw error
            } catch (error: Exception) {
                throw SpotifyApiException(null, "Could not reach Spotify. Check your connection and try again.", cause = error)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun form(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val API_ROOT = "https://api.spotify.com/v1"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_PLAYLIST_WRITE_ITEMS = 100
    }
}
