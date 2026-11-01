# Dock

An open-source, fully offline document scanner for Android, built with Kotlin and Jetpack Compose.

Point the camera at a page and Dock finds the edges, straightens the page, cleans up shadows and
saves a searchable PDF. No account, no cloud, no network access: the app doesn't even request the
`INTERNET` permission. Text recognition runs on the phone, with small models built into the app.

## Features (prototype)

- Live page outline in the camera preview, or **Standard** mode that keeps the whole 4:3 frame.
  OpenCV finds the edges and a small learned corner model (DocAligner) double-checks them: it
  takes over when OpenCV finds nothing or is fooled, e.g. by a page on a light desk
- Tap to focus: a focus circle with a lens slider beside it; **Manual** focus holds the lens at one distance
- Automatic crop + perspective correction, adjustable corners with a magnifier
- Filters: Original, Auto (shadow removal), Grayscale, Black & White
- Import photos from the gallery
- Multi-page PDFs saved straight into a real folder (Documents/Dock by default)
- **Searchable PDFs**: the text of each new scan is recognized on the phone (PaddleOCR PP-OCRv5,
  Latin-script languages) and added as an invisible layer, so any PDF app can search and copy it.
  Switch it per PDF from its menu, **Run OCR** on PDFs from elsewhere, or **Make all searchable** in Files.
  Adding or removing the text doesn't change the date a file shows
- Scan quality: Standard (small, print-ready) or Original (full resolution, no extra compression)
- **Home**: tools — scan, edit PDF, compress (presets, custom DPI or a target file size), PDF to images, merge, split, images to PDF
- **Images**: resize photos by percent, pixels, cm or inches, set DPI, crop/fit/stretch, save as JPG, PNG or GIF
- **Files**: every PDF and document, newest first, with search · **Shelf**: organise them into folders
- The tabs swipe sideways, like braim
- Page editor: swipe between pages, pinch or double-tap to zoom; crop, filters, brightness/contrast, eraser, markup, paper sizes, multi-page sheets
- Built-in PDF viewer; share, rename, move and delete from the list
- Document preview, also as "Open with Dock" from other apps: Word (.docx, .doc, .rtf, .odt),
  Excel (.xlsx, .xls, .ods, .csv) and PowerPoint (.pptx, .ppt, .odp), read on the device

See [docs/ROADMAP.md](docs/ROADMAP.md) for what's next.

## Project layout

| Module | What it does |
|---|---|
| `core:scanner` | Image pipeline with no UI: `DocumentDetector` (OpenCV edge detection), `CornerModel` (learned fallback), `PageProcessor` (warp, filters), `ImageFilters`, `JpegPdfWriter` (tiny dependency-free PDF writer) |
| `core:pdf` | PDF operations that keep text and vectors: merge, split, compress, Dock's invisible OCR text layer (PdfBox-Android) |
| `core:ocr` | On-device text recognition: PaddleOCR PP-OCRv5 mobile models on ONNX Runtime, with word positions for the PDF text layer |
| `third_party/onnxruntime` | ONNX Runtime 1.31.0 built from source with only the operators Dock's models use (~4 MB instead of ~34 MB per device), and how to rebuild it |
| `tools/models` | Where the models come from, their licenses, and the scripts that make the shipped files |
| `core:office` | Readers for Office and OpenDocument files (no dependencies: zip + XML for the modern formats, the compound file format for .doc/.xls/.ppt) into a simple model the app draws |
| `app` | Compose UI: storage setup, main shell (glass island with swipeable Home / Images / Files / Shelf tabs + camera bubble), camera, review + save, page editor, PDF viewer, document preview, image resizer. `data/storage/DockLibrary` wraps the Storage Access Framework |

## Building

Requirements: Android Studio (or JDK 17 + Android SDK 36).

```bash
./gradlew :app:assembleDebug          # build
./gradlew :app:installDebug           # install on a connected device / emulator
./gradlew :core:scanner:testDebugUnitTest                # JVM unit tests
./gradlew :core:scanner:connectedDebugAndroidTest        # detector tests on a device
```

Min SDK 26 (Android 8.0). The debug APK bundles native code for `arm64-v8a` and `x86_64`.

## Tech

Kotlin · Jetpack Compose · Material 3 · CameraX · OpenCV · ONNX Runtime (minimal build) + PaddleOCR + DocAligner · PdfBox-Android · Coil · Haze (backdrop blur) · Navigation Compose

## Privacy

Dock collects no data. See the [privacy policy](PRIVACY.md).

## License

MIT. See [LICENSE](LICENSE).

Dock bundles third-party software and models under their own licenses (Apache 2.0, MIT, BSD,
SIL OFL and others). The app lists each with its copyright, license text and any changes Dock
made, under **About → Open-source software**; the texts are in
[app/src/main/assets/licenses](app/src/main/assets/licenses). Model sources and changes:
[tools/models](tools/models).
