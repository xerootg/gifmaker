# GIF Maker

An Android app that turns a set of JPEGs into an animated GIF, entirely on-device.

- Pick photos through the system Photo Picker (no storage permission) or the file picker
  (no selection limit, for long timelapses).
- Frames are **sorted by capture date**: EXIF `DateTimeOriginal` first, then the gallery's
  "date taken", then a timestamp in the file name, then file modification time. The source of
  each frame's date is shown in the frame strip; undated frames go last in selection order.
- **Crop** with a draggable rectangle (free, or locked to 1:1, 4:3, 3:2, 16:9, portrait variants
  or the original aspect). The crop is stored as fractions of the frame, so it applies cleanly
  even when the input photos differ in size or orientation.
- **Frame rate** 1–50 fps (shown quantised to the GIF's 10 ms timing grid), use-every-Nth-frame,
  loop forever / once / N times, and a boomerang (forward-then-backward) mode.
- **Output resolution** by width preset or free width × height, optionally locked to the crop's
  aspect; fill / fit / stretch when the aspects differ.
- **Palette**: one global palette for the whole GIF (no inter-frame flicker, smaller file) or a
  palette per frame; 16–256 colours; optional Floyd–Steinberg dithering.
- Live preview of the cropped sequence before encoding, and playback of the actual encoded GIF
  afterwards. Save through the system file picker or share to any app.

## Architecture

```
app/src/main/java/dev/xerootg/gifmaker
├── gif/                     Pure-JVM GIF89a encoder, no Android dependencies
│   ├── LzwEncoder.kt        Variable-width GIF LZW with 4 MiB direct-mapped dictionary
│   ├── ColorQuantizer.kt    15-bit histogram, median-cut palette, Floyd–Steinberg mapper
│   └── GifWriter.kt         Streaming writer: header, NETSCAPE loop, GCE + image blocks
├── media/
│   ├── FrameMetadata.kt     Bounds, EXIF orientation and best-available capture date
│   ├── FrameRenderer.kt     Crop → raw-JPEG rect → BitmapRegionDecoder → orient → scale
│   ├── BitmapLoader.kt      Oriented whole-image decodes for previews / crop editor
│   └── GifExporter.kt       Two-pass (global palette) or single-pass (per-frame) pipeline
├── model/Models.kt          FrameItem, CropRect, ExportSettings, UiState (derived ordering)
├── GifMakerViewModel.kt     State, frame loading, preview cache, export job
└── ui/                      Compose: MainScreen, CropEditorScreen, LivePreview, GifResultView
```

Design notes:

- The crop is defined in *oriented* image space and mapped back through the EXIF transform so
  only the needed rectangle is region-decoded from the JPEG, at the largest `inSampleSize` that
  still exceeds the output size. A 12 MP source rendering to 640 px never inflates to a full
  bitmap.
- Global-palette mode renders each frame once, feeds the histogram, and keeps the rendered
  pixels when the whole sequence fits a 96 MB budget; otherwise it re-decodes on the second pass.
- The LZW encoder emits a Clear code when the dictionary fills, grows the code width in step with
  every standard decoder, and is verified by unit tests against a reference decoder and Java's
  ImageIO GIF reader.

## Installing with Obtainium

Every push to `main` publishes a GitHub Release with a signed APK (`gifmaker-vX.Y.Z.apk`),
so [Obtainium](https://github.com/ImranR98/Obtainium) can track the app straight from this
repository:

1. In Obtainium tap **Add App** and paste `https://github.com/xerootg/gifmaker`, or open
   [`obtainium://add/https://github.com/xerootg/gifmaker`](obtainium://add/https://github.com/xerootg/gifmaker)
   on the phone.
2. Leave the defaults; the release APK is the only asset, and version names follow `0.1.<build>`.

Releases are signed with the key held in the repository's secrets (see `docs/signing.md`), so each
one installs as an update over the previous build. Until those secrets exist, CI signs with a
throw-away key per run and the release notes say so; such builds install, but updating between
them needs an uninstall first.

## Building

Requires JDK 17+ and the Android SDK (platform 35, build-tools 35). Then:

```
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`; `:app:assembleRelease` produces
the minified, signed build that CI ships. The workflow in `.github/workflows/release.yml` runs the
unit tests and Lint, then on pushes to `main` publishes the signed release APK as a GitHub Release
tagged `v0.1.<run number>`; pull requests only upload the APK as a workflow artifact.

Minimum Android version: 8.0 (API 26). The encoded-GIF playback preview needs Android 9+; saving
and sharing work everywhere.
