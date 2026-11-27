package com.infinimedia.spotify

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.Empty
import com.spotify.protocol.types.PlayerOptions
import com.spotify.protocol.types.PlayerRestrictions
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.PlayerContext
import com.spotify.protocol.types.VolumeState
import com.spotify.protocol.types.Repeat
import com.spotify.protocol.types.Track
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** Separate Spotify service workspace. It never routes Spotify audio through InfiniMedia's decoder. */
class SpotifyWorkspaceActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "InfiniMediaSpotifyApi").apply { isDaemon = true }
    }
    private lateinit var auth: SpotifyOAuthManager
    private lateinit var api: SpotifyWebApi
    private var remote: SpotifyAppRemote? = null
    private var playerSubscription: Subscription<PlayerState>? = null
    private var contextSubscription: Subscription<PlayerContext>? = null
    private var volumeSubscription: Subscription<VolumeState>? = null
    private var playerState: PlayerState? = null
    private var playerContext: PlayerContext? = null
    private var volumeState: VolumeState? = null
    private var volumeGesture = false
    private var stateObservedAt = 0L
    private var remoteConnecting = false
    private var artworkTrackUri: String? = null
    private var activityStarted = false
    private val seekGesture = AtomicBoolean(false)

    private lateinit var page: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var clientIdInput: EditText
    private lateinit var redirectInput: EditText
    private lateinit var publicScopeCheck: CheckBox
    private lateinit var authStatusText: TextView
    private lateinit var remoteStatusText: TextView
    private lateinit var artwork: ImageView
    private lateinit var nowTitle: TextView
    private lateinit var nowDetails: TextView
    private lateinit var openNowPlayingButton: Button
    private lateinit var contextText: TextView
    private lateinit var openContextButton: Button
    private lateinit var nowPosition: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var volumeText: TextView
    private lateinit var volumeBar: SeekBar
    private lateinit var previousButton: Button
    private lateinit var playPauseButton: Button
    private lateinit var nextButton: Button
    private lateinit var shuffleButton: Button
    private lateinit var repeatButton: Button
    private lateinit var searchInput: EditText
    private lateinit var searchResults: LinearLayout
    private lateinit var albumTracks: LinearLayout
    private lateinit var playlistNameInput: EditText
    private lateinit var playlistDescriptionInput: EditText
    private lateinit var playlistResults: LinearLayout
    private lateinit var playlistItems: LinearLayout
    private lateinit var playlistPageButton: Button
    private val playlistCache = mutableListOf<JSONObject>()
    private var playlistOffset = 0
    private var playlistHasNext = false
    private var activePlaylist: JSONObject? = null
    private var activePlaylistOffset = 0
    private var activePlaylistHasNext = false
    private var albumPageOffset = 0
    private var albumHasNext = false
    private var lastSpotifyUrl: String? = null

    private val progressTicker = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            updateProgressFromState()
            main.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = SpotifyOAuthManager(applicationContext)
        api = SpotifyWebApi(applicationContext)
        setContentView(buildWorkspace())
        renderAuthStatus()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(progressTicker)
        main.post(progressTicker)
    }

    override fun onStop() {
        activityStarted = false
        disconnectRemote(clearDisplay = true)
        main.removeCallbacks(progressTicker)
        super.onStop()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildWorkspace(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        val scroll = ScrollView(this)
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        scroll.addView(page, ViewGroup.LayoutParams(-1, -2))
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.spotify_full_logo_white)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
            contentDescription = "Spotify"
        }
        page.addView(logo, LinearLayout.LayoutParams(dp(144), dp(44)).apply { bottomMargin = dp(8) })
        text(page, "Spotify workspace", 26f, bold = true)
        text(page, "Live Spotify control and account browsing", 14f, color = MUTED, bottom = 18)

        section(page, "Connect")
        text(page, "Configure the Spotify Developer Dashboard client ID and exact redirect URI registered for this Android app. Web API sign-in and Spotify App Remote playback authorization are separate.", 13f, color = MUTED, bottom = 10)
        clientIdInput = field("Spotify client ID").also { it.setText(auth.clientId()) }
        page.addView(clientIdInput, matchWrap(bottom = 8))
        redirectInput = field("com.infinimedia.spotify://callback").also { it.setText(auth.redirectUri()) }
        page.addView(redirectInput, matchWrap(bottom = 8))
        publicScopeCheck = CheckBox(this).apply {
            text = "Request permission for public playlist creation"
            textSize = 14f
            setTextColor(WHITE)
            buttonTintList = ColorStateList.valueOf(GREEN)
            isChecked = auth.grantedScopes().contains(SCOPE_PUBLIC_WRITE)
        }
        page.addView(publicScopeCheck, matchWrap(bottom = 8))
        action(page, "Save client settings", bottom = 8) { saveSettings() }
        val authActions = horizontalRow(page)
        action(authActions, "Authorize Web API", weight = 1f, right = 6) { authorizeWebApi() }
        action(authActions, "Disconnect Web API", weight = 1f, left = 6) { disconnectWebApi() }
        authStatusText = text(page, "", 13f, color = MUTED, top = 8, bottom = 6)
        action(page, "Connect Spotify app for playback control", bottom = 8) { connectRemote() }
        remoteStatusText = text(page, "Spotify App Remote is disconnected.", 13f, color = MUTED, bottom = 14)

        section(page, "Now playing")
        text(page, "Spotify playback stays in Spotify. Infinimedia receives live metadata and sends only the controls you tap.", 13f, color = MUTED, bottom = 10)
        artwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Spotify album artwork"
            setBackgroundColor(Color.rgb(35, 35, 35))
        }
        page.addView(artwork, LinearLayout.LayoutParams(dp(180), dp(180)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(12)
        })
        nowTitle = text(page, "Connect to Spotify to see playback.", 20f, bold = true, bottom = 4)
        nowDetails = text(page, "", 14f, color = MUTED, bottom = 8)
        openNowPlayingButton = action(page, "Open track in Spotify", bottom = 6) { openSpotify(lastSpotifyUrl) }.apply { visibility = View.GONE }
        contextText = text(page, "Spotify playlist/context will appear here when available.", 13f, color = MUTED, top = 4, bottom = 4)
        openContextButton = action(page, "Open playlist/context in Spotify", bottom = 6) { openSpotify(spotifyUrl(playerContext?.uri.orEmpty())) }.apply { visibility = View.GONE }
        nowPosition = text(page, "--:-- / --:--", 13f, color = MUTED)
        seekBar = SeekBar(this).apply {
            max = 1_000
            progressTintList = ColorStateList.valueOf(GREEN)
            thumbTintList = ColorStateList.valueOf(GREEN)
            isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
                override fun onStartTrackingTouch(seekBar: SeekBar?) { seekGesture.set(true) }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    seekGesture.set(false)
                    val state = playerState ?: return
                    val duration = state.track?.duration ?: return
                    if (state.playbackRestrictions?.canSeek == true) {
                        val target = duration * ((seekBar?.progress ?: 0).toLong()) / 1_000L
                        remote?.playerApi?.seekTo(target)?.setErrorCallback(::showRemoteError)
                    }
                }
            })
        }
        page.addView(seekBar, matchWrap(bottom = 4))
        volumeText = text(page, "Spotify volume unavailable.", 12f, color = MUTED, top = 2)
        volumeBar = SeekBar(this).apply {
            max = 1_000
            progressTintList = ColorStateList.valueOf(GREEN)
            thumbTintList = ColorStateList.valueOf(GREEN)
            isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
                override fun onStartTrackingTouch(seekBar: SeekBar?) { volumeGesture = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    volumeGesture = false
                    if (volumeState?.mControllable == true) {
                        remote?.connectApi?.connectSetVolume(((seekBar?.progress ?: 0).coerceIn(0, 1_000) / 1_000f))
                            ?.setErrorCallback(::showRemoteError)
                    }
                }
            })
        }
        page.addView(volumeBar, matchWrap(bottom = 8))
        val transport = horizontalRow(page)
        previousButton = action(transport, "Previous", weight = 1f, right = 3) { transport { playerApi.skipPrevious() } }
        playPauseButton = action(transport, "Play", weight = 1f, horizontal = 3) {
            val state = playerState
            if (state?.isPaused == true) transport { playerApi.resume() } else transport { playerApi.pause() }
        }
        nextButton = action(transport, "Next", weight = 1f, left = 3) { transport { playerApi.skipNext() } }
        val modes = horizontalRow(page)
        shuffleButton = action(modes, "Shuffle off", weight = 1f, right = 3) {
            val state = playerState ?: return@action toast("Connect to Spotify playback first.")
            transport { playerApi.setShuffle(!state.playbackOptions.isShuffling) }
        }
        repeatButton = action(modes, "Repeat off", weight = 1f, left = 3) { toggleRepeat() }
        text(page, "Transport buttons follow Spotify's live playback restrictions. Progress is display-only unless Spotify reports seeking is allowed.", 12f, color = MUTED, top = 6, bottom = 18)

        section(page, "Search catalog")
        text(page, "Search tracks, albums and artists in Spotify's catalog.", 13f, color = MUTED, bottom = 8)
        searchInput = field("Search Spotify").apply { imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
        page.addView(searchInput, matchWrap(bottom = 8))
        action(page, "Search", bottom = 8) { searchCatalog() }
        searchResults = column(page)
        albumTracks = column(page)

        section(page, "Your playlists")
        text(page, "Browse the first 20 playlists or load the next page. Playlist items are readable when Spotify permits access to the playlist.", 13f, color = MUTED, bottom = 8)
        action(page, "Refresh playlists", bottom = 8) { loadPlaylists(0) }
        playlistResults = column(page)
        playlistPageButton = action(page, "Load next 20 playlists", bottom = 10) { loadPlaylists(playlistOffset + 20) }
        playlistPageButton.visibility = View.GONE
        val creator = card(page)
        text(creator, "Create a playlist", 18f, bold = true, bottom = 8)
        playlistNameInput = field("Playlist name")
        creator.addView(playlistNameInput, matchWrap(bottom = 8))
        playlistDescriptionInput = field("Description (optional)").apply {
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
        }
        creator.addView(playlistDescriptionInput, matchWrap(bottom = 8))
        action(creator, "Create private playlist", bottom = 0) { createPlaylist() }
        playlistItems = column(page)

        section(page, "Feature availability")
        text(page, "This build keeps comprehensive discography aggregation, third-party release classification, exporting Spotify playlists into local formats, and audio-reactive Spotify visuals individually disabled. Standard authorized playback, catalog search and supported playlist actions remain available.", 13f, color = MUTED, bottom = 12)
        text(page, "Spotify account data is fetched only for actions you start here. Disconnect Web API removes this app's stored tokens. App Remote disconnects when this screen closes.", 12f, color = MUTED, bottom = 8)
        statusText = text(page, "", 13f, color = GREEN, top = 8)
        return outer
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.hasExtra(EXTRA_AUTH_RESULT)) {
            val message = intent.getStringExtra(EXTRA_AUTH_RESULT).orEmpty()
            val success = intent.getBooleanExtra(EXTRA_AUTH_SUCCEEDED, false)
            statusText.text = message
            statusText.setTextColor(if (success) GREEN else ERROR)
            renderAuthStatus()
            if (success && intent.getStringExtra(EXTRA_OPEN_SECTION) == SECTION_PLAYLISTS) loadPlaylists(0)
        }
        when (intent.getStringExtra(EXTRA_OPEN_SECTION)) {
            SECTION_PLAYLISTS -> if (auth.hasAccessToken()) loadPlaylists(0) else status("Authorize the Spotify Web API to load your playlists.")
            SECTION_SEARCH -> searchInput.requestFocus()
        }
    }

    private fun saveSettings() {
        runCatching { auth.saveConfiguration(clientIdInput.text.toString(), redirectInput.text.toString()) }
            .onSuccess {
                status("Spotify client settings saved on this device.")
                renderAuthStatus()
            }
            .onFailure { showError(it.message ?: "Invalid Spotify settings.") }
    }

    private fun authorizeWebApi() {
        saveSettingsQuietly() ?: return
        runCatching {
            val extra = if (publicScopeCheck.isChecked) setOf(SCOPE_PUBLIC_WRITE) else emptySet()
            auth.authorize(this, extra)
        }.onFailure { showError(it.message ?: "Could not start Spotify sign-in.") }
    }

    private fun saveSettingsQuietly(): Boolean? = try {
        auth.saveConfiguration(clientIdInput.text.toString(), redirectInput.text.toString())
        true
    } catch (error: Exception) {
        showError(error.message ?: "Invalid Spotify settings.")
        null
    }

    private fun disconnectWebApi() {
        AlertDialog.Builder(this)
            .setTitle("Disconnect Spotify Web API?")
            .setMessage("This removes InfiniMedia's stored Spotify tokens. It does not revoke this app in Spotify account settings.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Disconnect") { _, _ ->
                auth.disconnect()
                playlistCache.clear()
                playlistResults.removeAllViews()
                playlistItems.removeAllViews()
                renderAuthStatus()
                status("Spotify Web API tokens removed from this device.")
            }
            .show()
    }

    private fun renderAuthStatus() {
        if (!::authStatusText.isInitialized) return
        val scopes = auth.grantedScopes().sorted()
        authStatusText.text = if (auth.hasAccessToken()) {
            "Web API: connected\nGranted: ${scopes.joinToString(", ").ifBlank { "scopes not reported" }}"
        } else {
            "Web API: not connected"
        }
    }

    private fun connectRemote() {
        if (remoteConnecting) return
        val saved = saveSettingsQuietly() ?: return
        if (saved != true) return
        if (!SpotifyAppRemote.isSpotifyInstalled(this)) {
            AlertDialog.Builder(this)
                .setTitle("Spotify app required")
                .setMessage("Install Spotify and sign in there to use Spotify App Remote playback control.")
                .setNegativeButton("Close", null)
                .setPositiveButton("Open Spotify") { _, _ -> openSpotifyApp() }
                .show()
            return
        }
        val id = auth.clientId().trim()
        val redirect = auth.redirectUri().trim()
        remoteConnecting = true
        remoteStatusText.text = "Connecting to the Spotify app…"
        val params = ConnectionParams.Builder(id).setRedirectUri(redirect).showAuthView(true).build()
        SpotifyAppRemote.connect(this, params, object : Connector.ConnectionListener {
            override fun onConnected(spotifyAppRemote: SpotifyAppRemote) {
                remoteConnecting = false
                if (!activityStarted || isFinishing || isDestroyed) {
                    SpotifyAppRemote.disconnect(spotifyAppRemote)
                    return
                }
                remote = spotifyAppRemote
                remoteStatusText.text = "Spotify App Remote connected · live"
                playerSubscription?.cancel()
                contextSubscription?.cancel()
                volumeSubscription?.cancel()
                val contextEvents = spotifyAppRemote.playerApi.subscribeToPlayerContext()
                contextSubscription = contextEvents
                contextEvents.setEventCallback { context -> main.post { renderPlayerContext(context, spotifyAppRemote) } }
                contextEvents.setErrorCallback(::showRemoteError)
                val volumeEvents = spotifyAppRemote.connectApi.subscribeToVolumeState()
                volumeSubscription = volumeEvents
                volumeEvents.setEventCallback { volume -> main.post { renderVolume(volume, spotifyAppRemote) } }
                volumeEvents.setErrorCallback(::showRemoteError)
                val subscription = spotifyAppRemote.playerApi.subscribeToPlayerState()
                playerSubscription = subscription
                subscription.setEventCallback { received -> main.post { renderPlayerState(received, spotifyAppRemote) } }
                subscription.setErrorCallback(::showRemoteError)
                spotifyAppRemote.playerApi.getPlayerState()
                    .setResultCallback { received -> main.post { renderPlayerState(received, spotifyAppRemote) } }
                    .setErrorCallback(::showRemoteError)
                spotifyAppRemote.userApi.getCapabilities()
                    .setResultCallback { main.post { updateTransportControls() } }
                    .setErrorCallback(::showRemoteError)
            }

            override fun onFailure(error: Throwable) {
                remoteConnecting = false
                remote = null
                remoteStatusText.text = "Spotify App Remote is disconnected."
                showRemoteError(error)
            }
        })
    }

    private fun disconnectRemote(clearDisplay: Boolean) {
        remoteConnecting = false
        playerSubscription?.cancel()
        contextSubscription?.cancel()
        volumeSubscription?.cancel()
        playerSubscription = null
        contextSubscription = null
        volumeSubscription = null
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        playerContext = null
        volumeState = null
        artworkTrackUri = null
        remoteStatusText.text = "Spotify App Remote is disconnected."
        if (clearDisplay && ::nowTitle.isInitialized) {
            playerState = null
            artwork.setImageDrawable(null)
            nowTitle.text = "Connect to Spotify to see playback."
            nowDetails.text = ""
            openNowPlayingButton.visibility = View.GONE
            contextText.text = "Spotify playlist/context will appear here when available."
            openContextButton.visibility = View.GONE
            volumeText.text = "Spotify volume unavailable."
            volumeBar.isEnabled = false
            artworkTrackUri = null
            lastSpotifyUrl = null
            updateTransportControls()
            updateProgressFromState()
        }
    }

    private fun renderPlayerState(state: PlayerState, connected: SpotifyAppRemote) {
        if (remote !== connected || !connected.isConnected) return
        playerState = state
        stateObservedAt = SystemClock.elapsedRealtime()
        val track = state.track
        if (track == null) {
            nowTitle.text = "Nothing is playing in Spotify."
            nowDetails.text = ""
            artwork.setImageDrawable(null)
            lastSpotifyUrl = null
            openNowPlayingButton.visibility = View.GONE
            artworkTrackUri = null
        } else {
            nowTitle.text = track.name
            val creditedArtists = track.artists?.joinToString(", ") { it.name }.orEmpty().ifBlank { track.artist?.name.orEmpty() }
            nowDetails.text = "$creditedArtists\n${track.album?.name.orEmpty()} · Spotify"
            lastSpotifyUrl = spotifyUrl(track.uri)
            openNowPlayingButton.visibility = if (lastSpotifyUrl.isNullOrBlank()) View.GONE else View.VISIBLE
            if (artworkTrackUri != track.uri) {
                artworkTrackUri = track.uri
                artwork.setImageDrawable(null)
                val imageUri = track.imageUri
                if (imageUri != null) {
                    connected.imagesApi.getImage(imageUri).setResultCallback { bitmap: Bitmap ->
                        main.post { if (remote === connected && playerState?.track?.uri == track.uri) artwork.setImageBitmap(bitmap) }
                    }.setErrorCallback(::showRemoteError)
                }
            }
        }
        updateTransportControls()
        updateProgressFromState()
    }

    private fun renderPlayerContext(context: PlayerContext, connected: SpotifyAppRemote) {
        if (remote !== connected || !connected.isConnected) return
        playerContext = context
        val name = context.title?.takeIf(String::isNotBlank)
        val subtitle = context.subtitle?.takeIf(String::isNotBlank)
        contextText.text = when {
            name != null && subtitle != null -> "Playing from ${context.type ?: "Spotify"}: $name · $subtitle"
            name != null -> "Playing from ${context.type ?: "Spotify"}: $name"
            else -> "Spotify playlist/context not reported."
        }
        openContextButton.visibility = if (spotifyUrl(context.uri.orEmpty()).isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun renderVolume(volume: VolumeState, connected: SpotifyAppRemote) {
        if (remote !== connected || !connected.isConnected) return
        volumeState = volume
        val percentage = (volume.mVolume.coerceIn(0f, 1f) * 100f).roundToInt()
        volumeText.text = if (volume.mControllable) "Spotify volume · $percentage%" else "Spotify volume · controlled by Spotify"
        volumeBar.isEnabled = volume.mControllable
        if (!volumeGesture) volumeBar.progress = (volume.mVolume.coerceIn(0f, 1f) * 1_000f).roundToInt()
    }

    private fun updateTransportControls() {
        if (!::previousButton.isInitialized) return
        val state = playerState
        val restrictions: PlayerRestrictions? = state?.playbackRestrictions
        previousButton.isEnabled = remote?.isConnected == true && restrictions?.canSkipPrev == true
        nextButton.isEnabled = remote?.isConnected == true && restrictions?.canSkipNext == true
        playPauseButton.isEnabled = remote?.isConnected == true && state?.track != null
        playPauseButton.text = if (state?.isPaused == true) "Play" else "Pause"
        seekBar.isEnabled = remote?.isConnected == true && state?.track != null && restrictions?.canSeek == true
        val options: PlayerOptions? = state?.playbackOptions
        val shuffleAllowed = remote?.isConnected == true && restrictions?.canToggleShuffle == true
        shuffleButton.isEnabled = shuffleAllowed
        shuffleButton.text = if (options?.isShuffling == true) "Shuffle on" else "Shuffle off"
        val repeatAvailable = remote?.isConnected == true && (restrictions?.canRepeatContext == true || restrictions?.canRepeatTrack == true)
        repeatButton.isEnabled = repeatAvailable
        repeatButton.text = when (options?.repeatMode) {
            Repeat.ONE -> "Repeat one"
            Repeat.ALL -> "Repeat all"
            else -> "Repeat off"
        }
    }

    private fun updateProgressFromState() {
        if (!::nowPosition.isInitialized) return
        val state = playerState
        val track = state?.track
        if (state == null || track == null || track.duration <= 0L) {
            nowPosition.text = "--:-- / --:--"
            if (!seekGesture.get()) seekBar.progress = 0
            return
        }
        val duration = track.duration
        val elapsed = if (state.isPaused) 0L else (SystemClock.elapsedRealtime() - stateObservedAt).coerceAtLeast(0L)
        val position = (state.playbackPosition + elapsed).coerceIn(0L, duration)
        nowPosition.text = "${time(position)} / ${time(duration)}"
        if (!seekGesture.get()) seekBar.progress = ((position * 1_000L) / duration).toInt().coerceIn(0, 1_000)
    }

    private fun transport(action: (SpotifyAppRemote.() -> CallResult<Empty>)) {
        val connected = remote
        if (connected == null || !connected.isConnected) {
            toast("Connect to the Spotify app first.")
            return
        }
        runCatching { connected.action().setErrorCallback(::showRemoteError) }
            .onFailure { showRemoteError(it) }
    }

    private fun toggleRepeat() {
        val state = playerState ?: return toast("Connect to Spotify playback first.")
        val restrictions = state.playbackRestrictions
        val nextMode = when (state.playbackOptions.repeatMode) {
            Repeat.OFF -> if (restrictions.canRepeatContext) Repeat.ALL else if (restrictions.canRepeatTrack) Repeat.ONE else return
            Repeat.ALL -> if (restrictions.canRepeatTrack) Repeat.ONE else Repeat.OFF
            else -> Repeat.OFF
        }
        transport { playerApi.setRepeat(nextMode) }
    }

    private fun playUri(uri: String) {
        val connected = remote
        if (connected == null || !connected.isConnected) return toast("Connect to the Spotify app before starting playback.")
        connected.userApi.getCapabilities().setResultCallback { capability ->
            if (!capability.canPlayOnDemand) {
                main.post { showError("This Spotify account or playback session cannot start selected tracks on demand.") }
            } else {
                connected.playerApi.play(uri).setErrorCallback(::showRemoteError)
            }
        }.setErrorCallback(::showRemoteError)
    }

    private fun queueUri(uri: String) {
        val connected = remote
        if (connected == null || !connected.isConnected) return toast("Connect to the Spotify app before adding to its queue.")
        runCatching {
            connected.playerApi.queue(uri)
                .setResultCallback { main.post { status("Added to the Spotify queue.") } }
                .setErrorCallback(::showRemoteError)
        }.onFailure(::showRemoteError)
    }

    private fun openSpotify(url: String?) {
        val target = url?.takeIf(String::isNotBlank) ?: lastSpotifyUrl
        if (target.isNullOrBlank()) return toast("No Spotify link is available for this item.")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
            .onFailure { showError("Could not open this Spotify link.") }
    }

    private fun openSpotifyApp() {
        val launch = packageManager.getLaunchIntentForPackage("com.spotify.music")
        if (launch == null) return toast("Spotify is not installed.")
        runCatching { startActivity(launch) }.onFailure { showError("Could not open Spotify.") }
    }

    private fun searchCatalog() {
        val query = searchInput.text.toString().trim()
        if (query.isBlank()) return toast("Enter a title, artist, or album to search.")
        runApi("Searching Spotify…", { api.search(query, "track,album,artist") }) { data ->
            searchResults.removeAllViews()
            albumTracks.removeAllViews()
            val tracks = data.optJSONObject("tracks")?.optJSONArray("items") ?: JSONArray()
            val albums = data.optJSONObject("albums")?.optJSONArray("items") ?: JSONArray()
            val artists = data.optJSONObject("artists")?.optJSONArray("items") ?: JSONArray()
            section(searchResults, "Tracks")
            if (tracks.length() == 0) text(searchResults, "No tracks found.", 13f, color = MUTED, bottom = 6)
            for (index in 0 until tracks.length()) renderTrackResult(searchResults, tracks.getJSONObject(index))
            section(searchResults, "Albums")
            if (albums.length() == 0) text(searchResults, "No albums found.", 13f, color = MUTED, bottom = 6)
            for (index in 0 until albums.length()) renderAlbumResult(searchResults, albums.getJSONObject(index))
            section(searchResults, "Artists")
            if (artists.length() == 0) text(searchResults, "No artists found.", 13f, color = MUTED, bottom = 6)
            for (index in 0 until artists.length()) renderArtistResult(searchResults, artists.getJSONObject(index))
            if (tracks.length() + albums.length() + artists.length() == 0) status("No Spotify results for “$query”.")
            else status("Spotify search returned ${tracks.length()} tracks, ${albums.length()} albums, and ${artists.length()} artists.")
        }
    }

    private fun renderTrackResult(parent: LinearLayout, item: JSONObject) {
        val uri = item.optString("uri")
        val name = item.optString("name", "Track")
        val artists = artistNames(item.optJSONArray("artists"))
        val album = item.optJSONObject("album")?.optString("name").orEmpty()
        val disc = item.optInt("disc_number", 0)
        val trackNumber = item.optInt("track_number", 0)
        val position = if (trackNumber > 0) "Disc $disc · Track $trackNumber" else ""
        val duration = item.optLong("duration_ms", 0L).takeIf { it > 0L }?.let(::time).orEmpty()
        val row = card(parent)
        text(row, name, 16f, bold = true, bottom = 3)
        val details = listOf(artists, album, position, duration, if (item.optBoolean("explicit")) "Explicit" else "").filter(String::isNotBlank).joinToString(" · ")
        text(row, details, 13f, color = MUTED, bottom = 7)
        val actions = horizontalRow(row)
        action(actions, "Play", weight = 1f, right = 3) { playUri(uri) }
        action(actions, "Queue", weight = 1f, left = 3) { queueUri(uri) }
        val moreActions = horizontalRow(row)
        action(moreActions, "Add to playlist", weight = 1f, right = 3) { choosePlaylistForAdd(listOf(uri)) }
        action(moreActions, "Open Spotify", weight = 1f, left = 3) { openSpotify(itemUrl(item, "track")) }
    }

    private fun renderAlbumResult(parent: LinearLayout, item: JSONObject) {
        val row = card(parent)
        val name = item.optString("name", "Album")
        val id = item.optString("id")
        text(row, name, 16f, bold = true, bottom = 3)
        text(row, "${artistNames(item.optJSONArray("artists"))} · ${item.optString("release_date", "Date unknown")} · ${item.optInt("total_tracks")} tracks · ${item.optString("album_type", "album")}", 13f, color = MUTED, bottom = 7)
        val actions = horizontalRow(row)
        action(actions, "Details", weight = 1f, right = 3) { loadAlbumDetails(id) }
        action(actions, "Tracks", weight = 1f, left = 3) { loadAlbumTracks(id, 0, name) }
        val moreActions = horizontalRow(row)
        action(moreActions, "Play album", weight = 1f, right = 3) { playUri(item.optString("uri")) }
        action(moreActions, "Open Spotify", weight = 1f, left = 3) { openSpotify(itemUrl(item, "album")) }
    }

    private fun renderArtistResult(parent: LinearLayout, item: JSONObject) {
        val row = card(parent)
        val name = item.optString("name", "Artist")
        val id = item.optString("id")
        text(row, name, 16f, bold = true, bottom = 7)
        val actions = horizontalRow(row)
        action(actions, "First 10 releases", weight = 1f, right = 3) { loadArtistAlbums(id, name) }
        action(actions, "Open Spotify", weight = 1f, left = 3) { openSpotify(itemUrl(item, "artist")) }
    }

    private fun loadArtistAlbums(id: String, artistName: String) {
        runApi("Loading first 10 releases for $artistName…", { api.artistAlbums(id, 0) }) { data ->
            albumTracks.removeAllViews()
            section(albumTracks, "$artistName · first release page")
            text(albumTracks, "This page shows up to 10 catalog releases. Comprehensive discography aggregation remains disabled.", 12f, color = MUTED, bottom = 6)
            val items = data.optJSONArray("items") ?: JSONArray()
            if (items.length() == 0) text(albumTracks, "No releases available.", 13f, color = MUTED)
            for (index in 0 until items.length()) renderAlbumResult(albumTracks, items.getJSONObject(index))
            button(albumTracks, "Open artist in Spotify") { openSpotify("https://open.spotify.com/artist/$id") }
        }
    }

    private fun loadAlbumDetails(albumId: String) {
        runApi("Loading Spotify album details…", { api.album(albumId) }) { data ->
            albumTracks.removeAllViews()
            section(albumTracks, data.optString("name", "Spotify album"))
            text(albumTracks, "${artistNames(data.optJSONArray("artists"))}\n${data.optString("album_type", "album").replaceFirstChar(Char::uppercase)} · ${data.optString("release_date", "Date unknown")} · ${data.optInt("total_tracks")} tracks\n${data.optString("label", "")}", 14f, color = MUTED, bottom = 8)
            data.optJSONArray("copyrights")?.let { values ->
                for (index in 0 until values.length()) {
                    val copyright = values.optJSONObject(index)?.optString("text").orEmpty()
                    if (copyright.isNotBlank()) text(albumTracks, copyright, 12f, color = MUTED, bottom = 2)
                }
            }
            val actions = horizontalRow(albumTracks)
            action(actions, "Tracks", weight = 1f, right = 3) { loadAlbumTracks(albumId, 0, data.optString("name", "Spotify album")) }
            action(actions, "Play album", weight = 1f, left = 3) { playUri(data.optString("uri")) }
            button(albumTracks, "Open album in Spotify") { openSpotify(itemUrl(data, "album")) }
        }
    }

    private fun loadAlbumTracks(albumId: String, offset: Int, albumName: String) {
        albumPageOffset = offset
        runApi("Loading album tracks…", { api.albumTracks(albumId, offset) }) { data ->
            albumHasNext = data.optString("next").isNotBlank() && data.optString("next") != "null"
            albumTracks.removeAllViews()
            section(albumTracks, "$albumName · tracks ${offset + 1}–${offset + 20}")
            text(albumTracks, "Album order and track numbers are preserved.", 12f, color = MUTED, bottom = 6)
            val items = data.optJSONArray("items") ?: JSONArray()
            if (items.length() == 0) text(albumTracks, "No tracks in this page.", 13f, color = MUTED)
            for (index in 0 until items.length()) renderTrackResult(albumTracks, items.getJSONObject(index))
            if (offset > 0) button(albumTracks, "Previous 20 tracks") { loadAlbumTracks(albumId, (offset - 20).coerceAtLeast(0), albumName) }
            if (albumHasNext) button(albumTracks, "Next 20 tracks") { loadAlbumTracks(albumId, offset + 20, albumName) }
        }
    }

    private fun loadPlaylists(offset: Int) {
        if (!auth.hasAccessToken()) return showError("Authorize the Spotify Web API to browse your playlists.")
        playlistOffset = offset
        playlistPageButton.visibility = View.GONE
        runApi("Loading Spotify playlists…", { api.myPlaylists(offset) }) { data ->
            val items = data.optJSONArray("items") ?: JSONArray()
            if (offset == 0) playlistCache.clear()
            playlistResults.removeAllViews()
            if (offset == 0 && items.length() == 0) text(playlistResults, "No playlists found.", 13f, color = MUTED, bottom = 8)
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                playlistCache.add(item)
                renderPlaylistResult(item)
            }
            playlistHasNext = data.optString("next").isNotBlank() && data.optString("next") != "null"
            playlistPageButton.visibility = if (playlistHasNext) View.VISIBLE else View.GONE
            playlistPageButton.text = "Load next 20 playlists"
            status("Loaded ${items.length()} Spotify playlists.")
        }
    }

    private fun renderPlaylistResult(item: JSONObject) {
        val id = item.optString("id")
        val name = item.optString("name", "Playlist")
        val tracks = item.optJSONObject("items")?.optInt("total", -1)
            ?.takeIf { it >= 0 } ?: item.optJSONObject("tracks")?.optInt("total", -1)?.takeIf { it >= 0 }
        val owner = item.optJSONObject("owner")?.optString("display_name").orEmpty()
        val collaborative = item.optBoolean("collaborative")
        val row = card(playlistResults)
        text(row, name, 16f, bold = true, bottom = 3)
        text(row, "${tracks?.let { "$it items" } ?: "Playlist"}${if (owner.isBlank()) "" else " · $owner"}${if (collaborative) " · collaborative" else ""}", 13f, color = MUTED, bottom = 7)
        val actions = horizontalRow(row)
        action(actions, "Items", weight = 1f, right = 3) { loadPlaylistItems(item, 0) }
        action(actions, "Add track URI", weight = 1f, horizontal = 3) { showAddTrackByUri(item) }
        action(actions, "Open Spotify", weight = 1f, left = 3) { openSpotify(itemUrl(item, "playlist")) }
    }

    private fun loadPlaylistItems(playlist: JSONObject, offset: Int) {
        val id = playlist.optString("id")
        if (id.isBlank()) return showError("Spotify did not provide a playlist ID.")
        activePlaylist = playlist
        activePlaylistOffset = offset
        runApi("Loading playlist items…", { api.playlistItems(id, offset) }) { data ->
            activePlaylistHasNext = data.optString("next").isNotBlank() && data.optString("next") != "null"
            playlistItems.removeAllViews()
            val name = playlist.optString("name", "Playlist")
            section(playlistItems, "$name · items ${offset + 1}–${offset + 20}")
            text(playlistItems, "${data.optInt("total")} items · changes use Spotify's playlist snapshot.", 12f, color = MUTED, bottom = 6)
            val items = data.optJSONArray("items") ?: JSONArray()
            for (index in 0 until items.length()) {
                val rowItem = items.optJSONObject(index) ?: continue
                val content = rowItem.optJSONObject("item") ?: rowItem.optJSONObject("track") ?: continue
                renderPlaylistTrack(content, offset + index, data.optString("snapshot_id").ifBlank { playlist.optString("snapshot_id") })
            }
            if (items.length() == 0) text(playlistItems, "This playlist page has no readable items.", 13f, color = MUTED)
            if (offset > 0) button(playlistItems, "Previous 20 items") { loadPlaylistItems(playlist, (offset - 20).coerceAtLeast(0)) }
            if (activePlaylistHasNext) button(playlistItems, "Next 20 items") { loadPlaylistItems(playlist, offset + 20) }
        }
    }

    private fun renderPlaylistTrack(item: JSONObject, position: Int, snapshot: String?) {
        val uri = item.optString("uri")
        val name = item.optString("name", "Unavailable item")
        val row = card(playlistItems)
        text(row, name, 15f, bold = true, bottom = 3)
        text(row, "${artistNames(item.optJSONArray("artists"))} · position ${position + 1}", 13f, color = MUTED, bottom = 7)
        val actions = horizontalRow(row)
        action(actions, "Play", weight = 1f, right = 3) { if (uri.isNotBlank()) playUri(uri) else toast("This item is not playable.") }
        action(actions, "Remove", weight = 1f, horizontal = 3) {
            AlertDialog.Builder(this)
                .setTitle("Remove playlist item?")
                .setMessage("Remove “$name” from this Spotify playlist?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove") { _, _ ->
                    val playlist = activePlaylist ?: return@setPositiveButton
                    runApi("Removing playlist item…", {
                        api.removeItem(playlist.optString("id"), uri, snapshot)
                    }) {
                        status("Playlist item removed.")
                        loadPlaylistItems(playlist, activePlaylistOffset)
                    }
                }
                .show()
        }
        action(actions, "Open Spotify", weight = 1f, left = 3) { openSpotify(itemUrl(item, "track")) }
    }

    private fun createPlaylist() {
        val name = playlistNameInput.text.toString().trim()
        if (name.isBlank()) return toast("Enter a playlist name.")
        if (!auth.grantedScopes().contains(SCOPE_PRIVATE_WRITE)) return showError("Reconnect with private playlist edit permission to create a playlist.")
        val isPublic = publicScopeCheck.isChecked
        if (isPublic && !auth.grantedScopes().contains(SCOPE_PUBLIC_WRITE)) return showError("Reconnect and explicitly grant public playlist permission first.")
        val description = playlistDescriptionInput.text.toString().trim()
        runApi("Creating Spotify playlist…", { api.createPlaylist(name, description, isPublic) }) { created ->
            playlistNameInput.setText("")
            playlistDescriptionInput.setText("")
            status("Created ${if (isPublic) "public" else "private"} playlist “${created.optString("name", name)}”.")
            loadPlaylists(0)
        }
    }

    private fun showAddTrackByUri(playlist: JSONObject) {
        if (!auth.grantedScopes().contains(SCOPE_PRIVATE_WRITE) && !auth.grantedScopes().contains(SCOPE_PUBLIC_WRITE)) {
            return showError("Reconnect with playlist edit permission to add tracks.")
        }
        val input = field("spotify:track:… or Spotify track link")
        AlertDialog.Builder(this)
            .setTitle("Add Spotify track")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                val raw = input.text.toString().trim()
                val uri = when {
                    raw.startsWith("spotify:track:") -> raw
                    raw.contains("open.spotify.com/track/") -> raw.substringAfter("/track/").substringBefore('?').substringBefore('/').takeIf(String::isNotBlank)?.let { "spotify:track:$it" }
                    else -> null
                }
                if (uri == null) return@setPositiveButton toast("Enter a Spotify track URI or track link.")
                val required = if (playlist.optBoolean("public")) SCOPE_PUBLIC_WRITE else SCOPE_PRIVATE_WRITE
                if (!auth.grantedScopes().contains(required)) return@setPositiveButton showError("Reconnect and grant $required permission to edit this playlist.")
                runApi("Adding track to ${playlist.optString("name", "playlist")}…", {
                    api.addItems(playlist.optString("id"), listOf(uri))
                }) {
                    status("Added the track to ${playlist.optString("name", "playlist")}.")
                    if (activePlaylist?.optString("id") == playlist.optString("id")) loadPlaylistItems(playlist, activePlaylistOffset)
                }
            }
            .show()
    }

    private fun choosePlaylistForAdd(uris: List<String>, preferred: JSONObject? = null) {
        if (!auth.grantedScopes().contains(SCOPE_PRIVATE_WRITE) && !auth.grantedScopes().contains(SCOPE_PUBLIC_WRITE)) {
            return showError("Reconnect with playlist edit permission to add tracks.")
        }
        val targets = buildList {
            if (preferred != null) add(preferred)
            playlistCache.filterNot { it.optString("id") == preferred?.optString("id") }.forEach(::add)
        }
        if (targets.isEmpty()) {
            loadPlaylists(0)
            return toast("Loading playlists. Tap Add again when they appear.")
        }
        val labels = targets.map { playlist ->
            val name = playlist.optString("name", "Playlist")
            val isCollaborative = playlist.optBoolean("collaborative")
            "$name${if (isCollaborative) " · collaborative" else ""}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose a Spotify playlist")
            .setItems(labels) { _, which ->
                val target = targets[which]
                val requiredScope = if (target.optBoolean("public")) SCOPE_PUBLIC_WRITE else SCOPE_PRIVATE_WRITE
                if (!auth.grantedScopes().contains(requiredScope)) return@setItems showError("Reconnect and grant $requiredScope permission to edit this playlist.")
                runApi("Adding ${uris.size} item${if (uris.size == 1) "" else "s"}…", {
                    api.addItems(target.optString("id"), uris.take(100))
                }) {
                    status("Added ${uris.size} item${if (uris.size == 1) "" else "s"} to ${target.optString("name", "playlist")}.")
                    if (activePlaylist?.optString("id") == target.optString("id")) loadPlaylistItems(target, activePlaylistOffset)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runApi(label: String, call: () -> JSONObject, success: (JSONObject) -> Unit) {
        status(label)
        executor.execute {
            val result = runCatching(call)
            main.post {
                if (isFinishing || isDestroyed) return@post
                result.onSuccess(success).onFailure { error ->
                    if (error is SpotifyApiException && error.statusCode == 401) renderAuthStatus()
                    showError(error.message ?: "Spotify request failed.")
                }
            }
        }
    }

    private fun showRemoteError(error: Throwable) {
        main.post {
            if (isFinishing || isDestroyed) return@post
            val explanation = when {
                error.javaClass.simpleName.contains("NotAuthorized", ignoreCase = true) -> "Spotify did not authorize App Remote control. Connect again and approve the playback-control request."
                error.message.isNullOrBlank() -> "Spotify App Remote could not complete that action."
                else -> "Spotify App Remote: ${error.message}"
            }
            remoteStatusText.text = explanation
            statusText.text = explanation
            statusText.setTextColor(ERROR)
        }
    }

    private fun showError(message: String) {
        statusText.text = message
        statusText.setTextColor(ERROR)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun status(message: String) {
        statusText.text = message
        statusText.setTextColor(GREEN)
    }

    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }

    private fun itemUrl(item: JSONObject, type: String): String? = item.optJSONObject("external_urls")?.optString("spotify")
        ?.takeIf(String::isNotBlank)
        ?: item.optString("id").takeIf(String::isNotBlank)?.let { "https://open.spotify.com/$type/$it" }

    private fun spotifyUrl(uri: String): String? {
        val parts = uri.split(':')
        if (parts.size < 3 || parts[0] != "spotify") return null
        return "https://open.spotify.com/${parts[1]}/${parts[2]}"
    }

    private fun artistNames(artists: JSONArray?): String = artists?.let { array ->
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }.joinToString(", ")
    }.orEmpty().ifBlank { "Spotify artist unavailable" }

    private fun time(milliseconds: Long): String {
        val seconds = (milliseconds / 1_000L).coerceAtLeast(0L)
        return "%d:%02d".format(seconds / 60L, seconds % 60L)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun section(parent: LinearLayout, title: String) {
        val label = text(parent, title, 19f, bold = true, top = 12, bottom = 7)
        label.setTextColor(WHITE)
    }

    private fun text(
        parent: LinearLayout,
        value: String,
        size: Float,
        color: Int = WHITE,
        bold: Boolean = false,
        top: Int = 0,
        bottom: Int = 0,
    ): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(top), 0, dp(bottom))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun field(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        setTextColor(WHITE)
        setHintTextColor(MUTED)
        textSize = 15f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        backgroundTintList = ColorStateList.valueOf(Color.rgb(85, 85, 85))
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    private fun button(parent: LinearLayout, label: String, onClick: () -> Unit): Button = action(parent, label, onClick = onClick)

    private fun action(
        parent: LinearLayout,
        label: String,
        weight: Float = 0f,
        bottom: Int = 5,
        left: Int = 0,
        right: Int = 0,
        horizontal: Int = 0,
        onClick: () -> Unit,
    ): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(Color.BLACK)
        backgroundTintList = ColorStateList.valueOf(GREEN)
        minHeight = dp(42)
        setOnClickListener { onClick() }
        val params = if (weight > 0f) LinearLayout.LayoutParams(0, -2, weight) else LinearLayout.LayoutParams(-1, -2)
        params.bottomMargin = dp(bottom)
        params.leftMargin = dp(left + horizontal)
        params.rightMargin = dp(right + horizontal)
        parent.addView(this, params)
    }

    private fun horizontalRow(parent: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun column(parent: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun card(parent: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            setColor(CARD)
            cornerRadius = dp(12).toFloat()
        }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun matchWrap(bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) }

    companion object {
        const val EXTRA_OPEN_SECTION = "com.infinimedia.spotify.open_section"
        const val EXTRA_AUTH_RESULT = "com.infinimedia.spotify.auth_result"
        const val EXTRA_AUTH_SUCCEEDED = "com.infinimedia.spotify.auth_succeeded"
        const val SECTION_PLAYLISTS = "playlists"
        const val SECTION_SEARCH = "search"
        const val SCOPE_PRIVATE_WRITE = "playlist-modify-private"
        const val SCOPE_PUBLIC_WRITE = "playlist-modify-public"
        const val BG = 0xFF111111.toInt()
        const val CARD = 0xFF202020.toInt()
        const val WHITE = 0xFFF5F5F5.toInt()
        const val MUTED = 0xFFB5B5B5.toInt()
        const val GREEN = 0xFF1ED760.toInt()
        const val ERROR = 0xFFFF7777.toInt()
    }
}
