# InfiniMedia architecture

InfiniMedia is an independent Android media player. Its own Gradle root owns the
application and audio and video viewer modules. It does not include Infinifolder
or Infiniscribe as Gradle projects and does not import their application source.

## Application ownership

- The application root owns launch routing, app configuration, exported intent
  handling, and generated Android file-opening filters.
- `modules/viewer-audio` owns audio playback UI and service behavior, queues,
  playlists, Cast integration, and the bridge from shared audio decoding to
  Android output.
- `modules/viewer-video` owns video playback controls, lifecycle, and media
  source leases while using the public `libinfinivideo` API for playback.
- `modules/viewer-audio` keeps the PCM-driven spectrum, oscilloscope, and VU
  prototypes and hosts a `libinfinifx` graph over audio artwork/static images.
  Its TextureView-backed `FxArtworkView` owns an EGL/GLES render thread and bitmap
  texture lifecycle, preserving normal Android child-view composition.
  FxEffectPipeline owns an ordered, user-editable image-effect chain (default:
  rotozoom, CRT, water refraction, lightning spectrum). Generator effects are
  explicitly alpha-composited over the image accumulated at their chain
  position. Lightning is the default final generator stage. Each stage row has an effect dropdown and ✏️ edit, ⬆️ up, ⬇️ down, and 🗑️ delete buttons, with an Add effect dropdown below the chain (up to 12 items). Edit renders controls from that effect's parameter descriptors. Choices persist across track changes.
  The full-output lightning-spectrum generator renders
  20×-speed seeded-random antialiased bolts, six tendrils and shorter twigs
  per trace by default, and no filled bar bodies. The bolts cycle through up to eight popular colors from artwork sampled on a deterministic 32×32 grid and quantized to six-bit RGB (two bits per channel), with duplicate bins merged and ranked by frequency. Zero-valued bands have zero height. A player-owned
  metadata label sits above the full-pane effect stack. libinfinisound supplies
  estimated tempo and low-band transient events through the existing PCM
  analyzer. The app owns a small live parameter editor and metadata fade schedule.
- `libinfinifx` owns the public graph/effect API, GLES executor, shaders, and
  per-node state. Its Infinimedia adapter is app-owned and does not import this app.
- `libinfinisound`, `libinfinivideo`, `libinfiniselect`, and `libinfiniui` own
  reusable APIs and adapters in their respective repositories.

Some shared library types retain the `com.infinifolder.*` package namespace for
compatibility. That namespace does not mean the Infinifolder application is a
build dependency. Application and library revisions are pinned within this
checkout; see [standalone build instructions](../README.md#fresh-checkout-build). The
libinfinifx dependency is source-pinned in `dependencies.lock.json` and included
as a Gradle composite; no sibling checkout or app project dependency is used.

## Storage boundary

The app opens local and user-granted SAF resources through Android content URIs.
`libinfiniselect` preserves authoritative resource identity separately from a
current Android access URI and can resolve resources exposed by Infinifolder's
provider. Infinifolder remains an optional companion: without it, Media retains
its Android SAF and local-file workflows. See [storage integration](storage.md).
