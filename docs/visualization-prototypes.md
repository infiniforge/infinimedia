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
  analysis arrives; the metadata label overlays it at lower left. An effect graph
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
in the host window ([Android SurfaceView documentation](https://developer.android.com/reference/android/view/SurfaceView)). If an image-effect graph fails, the host retries the lightning generator alone through libinfinifx and logs the original error. The original artwork remains visible underneath the transparent output; the full-width segmented spectrum fallback stays available until a rendered frame has successfully swapped and returns if the lightning-only graph also fails.

The FxEffectPipeline executes an ordered user-editable chain. Its default order
is tempo-smoothed, bounded distort:rotozoom, display:crt, then
distort:water-refraction; each item can be replaced or removed. The FX editor lists available
filters/effects and every active chain item. Each row can be replaced with any
available image-chain effect, moved up or down, or removed; Add controls append
available effects. The chain may contain zero through twelve items, and its
selection persists when the playlist changes. Fire is a generator inserted as an
explicit alpha-composited layer. The lightning-spectrum generator remains a
separate, always-on overlay above the processed artwork. An empty image chain
passes the album art through to that overlay.

The default chain is rotozoom, CRT, and water refraction. Available options
include pass-through, CRT, LCD, rotozoom, kaleidoscope, water refraction, heat
ripple, and the fire layer. Graph validation and the renderer's effect contracts
remain in libinfinifx; UI and selection state are app-owned. The editor also
controls lightning gain, height, width (default 100%), tendrils, speed, glow, and
idle baseline, applying changes to the active graph as the controls move.

The lightning branch covers the output width and height by default and draws
only antialiased lightning paths: there are no filled rectangular bar bodies.
Each frequency slot selects from up to eight popular artwork colors. The app
samples the bitmap on a deterministic 32×32 grid, quantizes RGB to six bits
total (two bits per channel), merges duplicate bins, averages colors within
each bin, and orders the palette by sample count. Present zero-valued bands have
zero height; the low autonomous baseline is used only when band data is missing.
The segmented prototype is full-width and keeps a quiet baseline while acting
as the first-frame or error fallback.

Tapping the visual pane reveals EDIT FX beside the full-screen button for the
same four-second overlay window.
Renderer status, band levels, and errors are recorded in the app
DiagnosticLog under audio.fx.*. Effect IDs and parameter schemas come from
libinfinifx.

Fullscreen visual mode stays active when the current song changes to another
queue item. It exits when the queue is cleared or the user leaves fullscreen.
The selected track's artwork and metadata update inside the retained fullscreen
host.

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
the seven `FxPipelineTest` cases for valid generated graphs, lightning overlay,
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


### Centered spectrum and smoothed rotozoom — historical checkpoint, 2026-11-21

At this earlier checkpoint, the lightning generator used a 90% width default.
The later full-output outline and artwork-palette behavior is recorded below.
The then-current build verified normalized metadata and bounded rotozoom.

PASS: `./build.sh --max-workers=1 --console=plain` completed after resolving the
pinned source dependencies, assembled `InfiniMedia-debug.apk`, and passed all
129 viewer-audio JVM tests (0 failures). `adb devices -l` reports no connected
device. Shader-driver compilation, zero-band pixels, visual width, and runtime
rotozoom smoothness remain **not verified on device**. The library checkout
separately passed its portable/GLES tests, AAR/sample build, and 12-stage GLSL
syntax validation at this commit.


### Full-output artwork-palette lightning — 2026-11-21

The current graph defaults to 100% of the image width and height. The lightning
generator draws outlined electric paths only, with no filled rectangular bars.
Its procedural lightning evolves at 20× real time. The current treatment uses
thin antialiased lines with seeded-random tendrils. Outline colors cycle through
the eight most common unique colors from a deterministic 32×32 bitmap sample quantized to six-bit RGB.
The library receives these values through `FxMetadataChannels`, so palette
extraction remains an app-owned operation. `FxArtworkView` resumes rendering
when reattached after fullscreen reparenting, ignores stale SurfaceTexture
destroy callbacks, and retries transient EGL lifecycle failures with bounded
backoff. Gain is linear by default to preserve the full normalized band range,
and present zero values remain zero height.

The app pins libinfinifx commit `0d62f0bd132974a61e81e82a52e183150db76dec`.
PASS: `python3 scripts/fetch-dependencies.py --check` verified all pinned
revisions. PASS: `ANDROID_HOME="$HOME/android-sdk" ./build.sh --no-daemon
--max-workers=1 --console=plain` assembled `InfiniMedia-debug.apk` and passed all
133 viewer-audio JVM tests (0 failures, 0 errors, 0 skipped), including palette
ranking/deduplication, full-output graph defaults, and bounded render retry
delays. `adb devices -l` reported no attached device, so fullscreen GLES
recovery, visual palette contrast, actual edge coverage, shader-driver behavior,
and performance remain **not verified on device**.

### Random hairline lightning with tendrils — 2026-11-21

The lightning-spectrum generator now uses seeded, interpolated hash noise for
its trunk, side branches, secondary twigs, idle pulse, and flicker instead of
sine-wave shapes. Its bright core is screen-space antialiased at approximately 1.4 output
pixels at nominal scale. Six independently seeded tendrils and a short twig per tendril
are enabled by default; the editor adjusts tendril count, while preserving the
20× animation-speed setting. The editor replaces the previous stroke-width
slider with a tendril-count slider. `branchCount` replaces `boltWidth` in library
descriptor v5. The app pins library commit `4b0955cec4ced121ab69f5dd06c9006bbccceb5f`. The source revision and verification
results are recorded in the library's [test log](../libraries/libinfinifx/docs/builds-and-testing.md).

PASS: `python3 scripts/fetch-dependencies.py --check` verified the library pin.
PASS: `ANDROID_HOME="$HOME/android-sdk" ./build.sh --no-daemon --max-workers=1
--console=plain` assembled the InfiniMedia debug APK and passed all 133
viewer-audio unit tests (0 failures, 0 errors, 0 skipped). `adb devices -l`
reported no connected device, so the resulting lightning appearance and GLES
driver/runtime behavior remain **not verified on device**.


### Editable image-effect chain — 2026-11-21

The FX editor now displays both the available libinfinifx image effects and the
current ordered chain. A user can replace any row, move it up or down, add an
available effect, or remove an item. The chain accepts zero through twelve
stages; the starting selection is rotozoom, CRT, and water refraction, and it
remains selected across track changes. Fire is represented as an independent
generator layer with explicit alpha blending. Lightning spectrum remains a
separate overlay. Per-effect parameter editing and saved recipes are still
future work; the current sliders continue to control the lightning overlay.

InfiniMedia pins libinfinifx commit
`197b639e141e099140ab33019dbb6002e63f3bd8`.
`python3 scripts/fetch-dependencies.py --check` verified all pinned revisions.
PASS: `ANDROID_HOME="$HOME/android-sdk" ./gradlew :viewer-audio:testDebugUnitTest
--no-daemon --max-workers=1 --console=plain` — 134 tests, zero failures,
errors, or skips. PASS: `ANDROID_HOME="$HOME/android-sdk" ./build.sh
--no-daemon --max-workers=1 --console=plain` assembled
`InfiniMedia-debug.apk` from the same library source tree.

The lightning shader also no longer clamps noisy spine positions at the band
edge, which could create a short straight vertical streak. Its antialiased core
is slightly stronger at about 1.4 output pixels at nominal scale. Shader syntax
validation, GLES AAR/sample assembly, and portable library tests passed;
`adb devices -l` reports no connected device, so the editor presentation and
updated stroke appearance remain **not verified on device**.
