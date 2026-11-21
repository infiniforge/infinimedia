# Visualization prototypes and FX pipeline

Infinimedia keeps a small set of player-owned visualization prototypes and now
hosts a reusable effects graph from libinfinifx. The app owns presentation,
bitmap sources, metadata adaptation, and GLES lifecycle; the library owns
reusable effects and graph execution.

## Retained prototypes

- `VisualizerView` remains the segmented 16-band spectrum prototype in the
  player console and classic Winamp modes. In the standard audio-artwork pane,
  a full-width instance is anchored to the pane's bottom edge and remains visible
  until an InfiniFX frame successfully swaps to the TextureView surface, then
  returns if rendering fails. Its quiet baseline stays visible before audio
  analysis arrives; the metadata label overlays it at lower left. A randomized graph
  failure retries a minimal lightning-only libinfinifx graph; diagnostics record
  the original error, surface readiness, and successful frame swaps.
- `WinampMainWindowView` can switch among spectrum, oscilloscope, and VU modes.
- `RealAudioVisualizerBridge` reads decoded PCM through libinfinisound's shared
  analyzer. The views do not decode or analyze audio independently.
- Album artwork, still and animated images, video, ANSI output, and ANSI Music
  retain their existing media-presentation paths.

## libinfinifx host pipeline

`modules/viewer-audio` pins libinfinifx through `dependencies.lock.json` and
includes its source checkout as a standalone Gradle composite build. The
app-owned `FxArtworkView` is a `TextureView` with a private EGL/GLES 3 render
thread. It uploads a caller-owned bitmap to `GL_TEXTURE_2D`, supplies monotonic
timing and source/destination geometry, and renders into the TextureView surface.
TextureView participates in normal Android child ordering, so the processed
artwork can sit between the underlying album-art ImageView and the existing
title/spectrum overlays. The spectrum fallback is hidden only after the first
successful `eglSwapBuffers`. This replaces a SurfaceView configured as a media
overlay, whose special surface z-order could place it behind ordinary content
in the host window ([Android SurfaceView documentation](https://developer.android.com/reference/android/view/SurfaceView)). If a randomized effect graph fails, the host retries the lightning generator alone through libinfinifx and logs the original error. The original artwork remains visible underneath the transparent output; the full-width spectrum prototype stays available until a rendered frame has successfully swapped and returns if the lightning-only graph also fails.

The RandomFxPipeline begins every artwork graph with the fixed
distort:rotozoom node, then holds two sequential randomized image-effect stages.
On each changed visual playlist item it chooses one randomized stage and
replaces it with a different available effect ID. The final graph adds an
independent generator:lightning-spectrum branch and alpha-blends it over the
processed artwork. Its 16 colored electric bars span the entire output width
and use the current normalized audio bands; with missing/silent bands, a low
autonomous baseline remains visible. The segmented prototype is also full-width
and keeps a quiet baseline while acting as the first-frame or error fallback.
The randomized options are CRT, LCD, water refraction, heat ripple, kaleidoscope,
and the independent fire generator followed by an explicit alpha blend. This is
temporary testing behavior, not a selection UI; deliberate user/app selection
can later replace this policy while retaining the public graph/effect
contracts. Tapping the visual pane reveals EDIT FX beside the full-screen button
for the same four-second overlay window. It adjusts gain, maximum height,
spacing, glow, and idle baseline, applying changes to the active graph as the
sliders move. Renderer status, band levels, and errors are recorded in the app
DiagnosticLog under audio.fx.*. Effect IDs and parameter schemas come from
libinfinifx.

### Artwork and metadata composition

The album-art ImageView and the FX frame both use center-crop geometry, so the
art fills the entire visual pane while retaining its aspect ratio. For a square
image on a 16:9 pane, fitting the image to pane width places its centered top at
Y = -7/32 pane-width; the visible source crop removes 7/32 from both vertical
edges. This crop is passed in FxFrameInput.geometry.contentRect, so graph output
and source presentation show the same centered region. ArtworkCropGeometryTest
locks this mapping.

The MTV-style metadata label is a player-owned child overlay outside the FX
graph. Audio metadata uses five rows in this order: artist, quoted track title,
album, record company, and producer. All rows use the same font; missing label
or producer metadata is an empty row. When no actual metadata is present, the
filename is shown alone. The current metadata readers provide artist/title/album;
record company and producer are optional AudioTrack fields and remain blank
until a source supplies them. The label is brought above the GPU artwork and
spectrum layers and does not shrink the graph's full-pane viewport. Its black
background is approximately 15% opaque. For audio with a known duration longer than 22 seconds, it remains
visible through 10 seconds, fades out during seconds 10–11, and fades in during
the final 11–10 seconds. A backward seek recomputes opacity from playhead time.
Short tracks keep the label visible because the hide and end-fade windows would
overlap. With unknown duration it fades out after 10 seconds and stays hidden.

The host maps the existing smoothed 16 spectrum bands to normalized low/mid/high
energy and uses the existing VU RMS/peak values. libinfinisound estimates tempo
from the low-band envelope and emits uniquely numbered low-band transient
events through the same analysis stream. Rotozoom scales its base rotation rate
relative to 120 BPM, clamped to 0.5x–2x; transient events pulse zoom and
rotation. This lightweight estimate is not a beat-grid or musical downbeat
detector. The adapter maps event IDs and timestamps into FxAudioEvent, whose
per-node cursor prevents replay when a metadata snapshot is reused. Missing or
stale audio metadata leaves autonomous effects animated and audio-only
modulation at its neutral value.

FX currently runs for audio artwork and static image bitmaps. Video remains on
its existing playback renderer because its current output uses
`GL_TEXTURE_EXTERNAL_OES`; libinfinifx currently accepts `GL_TEXTURE_2D` only.
Animated image, ANSI, and ANSI Music also bypass the FX overlay. These paths
remain on their existing renderers; the library is packaged as an app dependency
and does not need a separate Android application installation.

## Removed simulation

The app no longer contains the previous live CPU heat/smoke simulation, wind and
lightning effects, shared mutable simulation fields, or their OpenGL overlay.
Heat ripple and fire are now independent built-ins in libinfinifx; the app does
not maintain shared effect simulation state.

## Verification status

On 2026-11-20, after pinning libinfinifx commit
`7bc9ae8b115ba54b469c765bd5d34f77deb46f5c`, the standalone Termux command
`./build.sh --no-daemon --max-workers=1 --console=plain` completed successfully.
It assembled `InfiniMedia-debug.apk` and passed all 121 JVM unit tests, including
the five `FxPipelineTest` cases for valid generated graphs, lightning overlay,
lightning-only fallback validation, visible idle baseline, and editor parameter
propagation. The build includes the TextureView/EGL renderer, lightning generator graph
branch, silent-band visibility floor, parameter editor, and `audio.fx.*`
diagnostic events. The pinned
libinfinisound Termux compatibility update remains at
`3412205da2501248a0c89f5ab9402049fc89cfd9`.

The standalone libinfinifx validation at the same library commit also passed
`:fx-core:test`, built its GLES library and sample APK, and passed
`python3 scripts/validate-shaders.py` (12 shader checks). These are compile,
portable unit-test, and shader-source checks only. `adb devices -l` reported no
attached device, so the lightning appearance, orientation, TextureView
composition, frame rate, and thermal behavior have **not been verified on an
Android device**. See [app architecture](architecture.md) and the
[libinfinifx effect guide](https://github.com/infiniforge/libinfinifx/blob/main/docs/effects.md).

On 2026-11-21, after changing the editor to a tap-revealed overlay and keeping a
full-width spectrum fallback until a successful EGL buffer swap, the same
standalone command `./build.sh --no-daemon --max-workers=1 --console=plain`
completed successfully again and passed all 121 JVM tests. It rebuilt
`InfiniMedia-debug.apk`. `adb devices -l` still reports no attached device, so
this build is not visual device acceptance. The supplied prior diagnostic bundle
contains no `audio.fx.*` render records; the current build records source size,
surface availability/destruction, skipped render inputs, errors, and successful
swaps to make the next capture actionable.

### Latest build checkpoint — 2026-11-21

With libinfinisound commit 095c4216ee71257779f7b4f31749cf57f4e2f583 and
libinfinifx commit d48be61714fc4fa231d3eb48df9d56dd7a6869ff pinned in
`dependencies.lock.json`, `./build.sh --max-workers=1 --console=plain`
completed successfully. It built `InfiniMedia-debug.apk` and passed all 123
viewer-audio JVM tests, including the graph-stage, audio metadata adapter, and
metadata fade tests. In the standalone libinfinisound checkout,
`./gradlew :audio-core:test --no-daemon --max-workers=1 --console=plain`
passed, including synthetic 120 BPM pulse and transient-ID coverage. The
libinfinifx `:fx-core:test` task passed, and the app build compiled its GLES
module with the BPM-aware rotozoom runtime.

These are compiler and portable test results. `adb devices -l` listed no
attached device during this checkpoint, so album-art cropping, layer order,
rotozoom tempo behavior, label fade appearance, shader output, and frame timing
remain **not verified on device**.


### GLES shader compile follow-up — 2026-11-21

The latest supplied diagnostic bundle reports a 768×768 artwork bitmap and a
1276×717 FX surface, but every graph render fails before drawing with the
#version directive not on the first line. The screenshot therefore shows the
underlying ImageView fallback; it does not show the rotozoom pipeline.

The fix is in libinfinifx commit
64be3fec6bf3cf65e9ee1ca2b1a019f6a33c54ab, now pinned in
dependencies.lock.json. The backend trims Kotlin raw-string indentation
before GLES compilation. A fresh Infinimedia build against that pinned commit
passed all 125 viewer-audio JVM tests and assembled the debug APK. The
center-crop mapping remains unchanged: for square source art in a 16:9 pane,
the visible source rectangle is Y=7/32..25/32, centered at Y=1/2. No Android
device was connected after the fix, so actual shader-driver compilation and
the artwork's on-screen placement are not yet verified on device.


### MTV metadata label follow-up — 2026-11-21

The audio lower third now renders artist, quoted title, album, record company,
and producer as five rows using the same typeface and size. Missing company or
producer values keep their row blank. If all available metadata is absent, the
filename is shown alone. Optional company/producer fields are preserved in the
app's saved playlist snapshot; current metadata readers do not populate them.

PASS: ANDROID_HOME="$HOME/android-sdk" ./build.sh --no-daemon --max-workers=1 --console=plain
completed and assembled InfiniMedia-debug.apk. The viewer-audio JVM suite
reported 129 tests, 0 failures. adb devices -l reports no attached device;
metadata appearance and GLES layer visibility remain not verified on device.
