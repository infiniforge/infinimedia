package com.infinimedia.spotify

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class PendingSpotifyAuthorization(
    val clientId: String,
    val redirectUri: String,
    val verifier: String,
    val state: String,
    val scopes: Set<String>,
)

/** Stores app configuration plainly and OAuth secrets encrypted with an Android Keystore key. */
internal class SpotifySecureStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    var clientId: String
        get() = preferences.getString(KEY_CLIENT_ID, "").orEmpty()
        set(value) { preferences.edit().putString(KEY_CLIENT_ID, value.trim()).apply() }

    var redirectUri: String
        get() = preferences.getString(KEY_REDIRECT_URI, DEFAULT_REDIRECT_URI).orEmpty()
        set(value) { preferences.edit().putString(KEY_REDIRECT_URI, value.trim()).apply() }

    @Synchronized
    fun saveToken(token: SpotifyStoredToken) {
        val json = JSONObject()
            .put("access_token", token.accessToken)
            .put("refresh_token", token.refreshToken)
            .put("expires_at", token.expiresAtEpochMs)
            .put("scopes", token.scopes.joinToString(" "))
        preferences.edit().putString(KEY_TOKEN, encrypt(json.toString())).apply()
    }

    @Synchronized
    fun token(): SpotifyStoredToken? = runCatching {
        val encrypted = preferences.getString(KEY_TOKEN, null) ?: return null
        val json = JSONObject(decrypt(encrypted))
        SpotifyStoredToken(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token"),
            expiresAtEpochMs = json.getLong("expires_at"),
            scopes = json.optString("scopes").split(' ').filter(String::isNotBlank).toSet(),
        )
    }.getOrNull()

    @Synchronized
    fun savePending(value: PendingSpotifyAuthorization) {
        val json = JSONObject()
            .put("client_id", value.clientId)
            .put("redirect_uri", value.redirectUri)
            .put("verifier", value.verifier)
            .put("state", value.state)
            .put("scopes", value.scopes.sorted().joinToString(" "))
        preferences.edit().putString(KEY_PENDING, encrypt(json.toString())).apply()
    }

    @Synchronized
    fun pending(): PendingSpotifyAuthorization? = runCatching {
        val encrypted = preferences.getString(KEY_PENDING, null) ?: return null
        val json = JSONObject(decrypt(encrypted))
        PendingSpotifyAuthorization(
            clientId = json.getString("client_id"),
            redirectUri = json.getString("redirect_uri"),
            verifier = json.getString("verifier"),
            state = json.getString("state"),
            scopes = json.optString("scopes").split(' ').filter(String::isNotBlank).toSet(),
        )
    }.getOrNull()

    @Synchronized
    fun clearPending() { preferences.edit().remove(KEY_PENDING).apply() }

    @Synchronized
    fun disconnect() {
        preferences.edit().remove(KEY_TOKEN).remove(KEY_PENDING).apply()
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null); deleteEntry(KEY_ALIAS) }
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val packed = Base64.decode(value, Base64.NO_WRAP)
        require(packed.size > IV_BYTES)
        val iv = packed.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(packed.copyOfRange(IV_BYTES, packed.size)), StandardCharsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES = "spotify_companion"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_REDIRECT_URI = "redirect_uri"
        const val KEY_TOKEN = "encrypted_token"
        const val KEY_PENDING = "encrypted_pending_authorization"
        const val KEY_ALIAS = "infinimedia.spotify.oauth.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val DEFAULT_REDIRECT_URI = "com.infinimedia.spotify://callback"
    }
}
