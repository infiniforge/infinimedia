# Spotify workspace

Debug builds expose an optional Spotify workspace from the player's hamburger menu; release builds hide the Spotify menu group for now. It uses Spotify's official Android App Remote SDK for user-directed external playback control and the Spotify Web API for authorized catalog and playlist operations. Spotify remains the audio renderer and owns its queue, decoding, DRM, audio output, and casting. Spotify items are never passed to InfiniMedia's local decoder or visualizer.

## Set up a Development Mode app

1. Create a Spotify Developer Dashboard app and select Android integration.
2. Register the app package name (`com.infinimedia`), the signing fingerprint for the build you install, and an exact redirect URI. The default URI is `com.infinimedia.spotify://callback`; it can be changed in the workspace to another URI using the `com.infinimedia.spotify` scheme.
3. Enter the dashboard client ID and registered redirect URI in Spotify settings in InfiniMedia and save them. No client secret is used or stored.
4. Start **Authorize Web API** to authorize catalog and playlist access with OAuth Authorization Code and PKCE. Public playlist creation is optional and asks for its own scope only when the checkbox is enabled. App Remote uses Spotify's separate built-in `app-remote-control` authorization when **Connect Spotify app for playback control** is tapped.
5. Install Spotify on the Android device and sign in there before using App Remote. The Spotify app must be available to the device, and the dashboard must have this app's package/signature configured.

Development Mode is intended for personal projects and experimentation. Current Spotify requirements include an active Premium account for the developer who owns the app and an allowlist of up to five Spotify users. Each test account must be allowlisted in the Developer Dashboard. Those account/quota rules do not disable supported Development Mode API features. See Spotify's [quota modes](https://developer.spotify.com/documentation/web-api/concepts/quota-modes) and [February 2026 migration guide](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide) for current conditions and endpoint changes.

## Available functionality

- **Live player:** reads the Spotify App Remote player-state subscription, current track metadata and artwork, playlist/context, volume when controllable, play state, position, shuffle/repeat state, and SDK-provided restrictions. Play/pause, next/previous, seeking, shuffle, repeat, and volume are enabled only when the live SDK state allows them. Selected tracks and albums can be started on Spotify when its capability response allows on-demand playback. App Remote disconnects when the workspace stops.
- **Catalog:** search for tracks, albums, and artists; browse an artist's first 10 releases; view album details; page through album tracks (20 per page); start or queue selected tracks; open the corresponding Spotify page. Results and displayed album art stay in the Spotify workspace, are not mixed into the native-media shelf, and include Spotify branding/link attribution.
- **Playlists:** list the current user's playlists (20 per page), browse accessible playlist items (20 per page), create private playlists, add up to 100 selected tracks per API write, add a track by URI/link, and remove an item after confirmation. Playlist permissions are checked from the granted OAuth scopes; Spotify remains authoritative about ownership and edit access. Playlist writes use Spotify snapshot IDs where the endpoint supports them. Network requests run off the UI thread, and non-idempotent writes are not retried automatically.
- **Disconnect:** removes encrypted Web API tokens and pending OAuth data from this device. Users can also revoke InfiniMedia in their Spotify account's app access settings. App Remote ends with the workspace lifecycle.

The workspace keeps the client ID and redirect URI in app preferences. Access/refresh tokens and pending PKCE state are encrypted with an Android Keystore AES-GCM key. Disconnect deletes the encrypted data and key. Spotify metadata and artwork are not cached as an offline library.

## Individually disabled features

The build leaves comprehensive discography aggregation, third-party release classification, exporting Spotify playlist data into local playlist formats, and feeding Spotify artwork/metadata into reactive visual effects disabled. Ordinary search, the first supported artist-release page, album browsing, live remote playback, and supported playlist reads/writes do not depend on these feature gates. The code exposes `SpotifyAdditionalApprovalFeatures` flags so a future approved feature can be enabled independently.

Spotify content is displayed without alteration. The workspace uses the official full Spotify logo, links results back to Spotify, and shows current artwork as received. It does not derive audio-reactive visuals, export Spotify audio, or access audio-analysis endpoints.

## SDK packaging and build

Spotify distributes App Remote as an Android AAR from its official SDK release, not through the configured Maven repositories. The pinned `spotify-app-remote-release-0.8.0.aar` and the extracted compile-time `classes.jar` are stored under this module's `libs/` directory. The Android app packages the AAR at the Gradle root because Android libraries cannot bundle another local AAR. The official SDK's Apache license and NOTICE are preserved alongside the module. The workspace uses Gson 2.8.6 as required by the SDK integration.

Build the provider and app with the normal standalone command from the repository root:

```sh
./build.sh --max-workers=1 --console=plain
```

No sibling app or machine-specific Java installation path is required. Java 17+ and Android SDK prerequisites are listed in the [README](../README.md#fresh-checkout-build).

## References

- [Spotify Android SDK getting started](https://developer.spotify.com/documentation/android/tutorials/getting-started)
- [Spotify App Remote SDK release](https://github.com/spotify/android-sdk/releases)
- [Authorization Code with PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow)
- [Spotify Web API search](https://developer.spotify.com/documentation/web-api/reference/search)
- [Artist albums](https://developer.spotify.com/documentation/web-api/reference/get-an-artists-albums)
- [Album tracks](https://developer.spotify.com/documentation/web-api/reference/get-an-albums-tracks)
- [Playlist items](https://developer.spotify.com/documentation/web-api/reference/get-playlists-items)
- [Create playlist](https://developer.spotify.com/documentation/web-api/reference/create-playlist)
- [Add playlist items](https://developer.spotify.com/documentation/web-api/reference/add-items-to-playlist)
- [Remove playlist items](https://developer.spotify.com/documentation/web-api/reference/remove-items-playlist)
- [Spotify design guidelines](https://developer.spotify.com/documentation/design)
