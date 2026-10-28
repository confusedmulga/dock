# Dock: bug review

## Third pass (2026-10-28): `89de23e` and later

Scope: everything added since the last review, about 12,600 lines in the commit "camera focus controls, Images tab, document preview and swipeable pages". It added the new `core:office` module, the exported "Open with Dock" activity, the camera session, the Images tab, the tool flows and the editor pager. Every file was read whole. The Office readers and the preview were also read through a security lens, because they now take files from any app.

**Result:** 1 High and 6 Medium findings, all fixed and verified. 6 Lows are in the backlog. `./gradlew check` is green again, and the fixes are uncommitted.

| ID | Sev | Status | Where | Finding | Fix |
|---|---|---|---|---|---|
| T1 | **High** | Confirmed on emulator, fixed | `ui/preview/DocumentViews.kt` | Opening a Word file whose table has a negative indent crashed the app: `IllegalArgumentException: Padding must be non-negative` at `TableView`. Word 2003–2010-compatible documents write `tblInd = -108` by default, so this is common. Negative top margins and spacing, slide insets, and zero or negative page sizes from damaged files crashed the same way, or through `aspectRatio`. | All padding built from document values goes through `gap()` (clamped at 0). The table fit factor stays positive. Page and slide sizes of 0 or below fall back to the usual size. Re-tested on the emulator: the same file renders. |
| T2 | Medium | Confirmed on emulator, fixed | `PreviewActivity.kt`, manifest | The exported preview accepted `file://` URIs and read them with Dock's own access. Any app could make Dock display, share or "Save to Dock" its private files. The intent filter alone doesn't help, since explicit intents bypass it. | Only accepts `content://` URIs that aren't Dock's own provider; the `file` scheme is gone from the intent filter. Re-tested: a `file://` path into Dock's data now closes the activity without reading. |
| T3 | Medium | Confirmed (unit test), fixed | `office/DocReader.kt` | A `.doc` whose piece table starts with a property block of size -3 looped forever: preview stuck on "Opening…" and one CPU core pegged. | A negative size makes the file unreadable. |
| T4 | Medium | Confirmed (unit test), fixed | `office/RtfReader.kt` | `\bin` with a negative length jumped back onto itself: infinite loop. | The length is clamped at 0. |
| T5 | Medium | Confirmed (unit test), fixed | `office/NumberFormat.kt` | Fraction formats try every denominator up to 10^digits, so `?/??????????` (from the file) took over 2 billion steps. | The denominator is capped at 5 digits. |
| T6 | Medium | Confirmed (unit test), fixed | `office/NumberFormat.kt` | Rounding used the exact binary value, so 2.675 showed as 2.67 and 1.005 as 1.00; Excel shows 2.68 and 1.01. That's wrong figures in prices and totals. | Round to 15 significant digits first, as Excel does. |
| T7 | Medium | Confirmed (unit test), fixed | `office/XlsxReader.kt` | A sheet whose XML is over 64 MB (about 100k-row exports) failed with "too large to preview", even though the reader is meant to cut at 20,000 rows. | Reading stops at the row limit, and the streamed sheet gets a 256 MB cap. |
| T8 | Medium | Confirmed (API contract), fixed | `ui/camera/CameraSession.kt`, `CameraScreen.kt` | It always bound the back camera with no error handling. On a device without one (the manifest makes the camera optional), `bindToLifecycle` throws and the app crashed opening the scanner. | Uses the back camera, else the front. If neither works, it shows "The camera isn't available" and closes. |
| T9 | Low | Confirmed, fixed | `CameraSession.kt` | Kotlin's `@OptIn` on an AndroidX opt-in marker has no effect: 15 lint errors made `./gradlew check` red. | Switched to `androidx.annotation.OptIn`. |
| T10 | Low | Confirmed (unit test), fixed | `office/DocxReader.kt` | Word's rendered page breaks inside a table cell or text box became a line break, adding a blank line to cells of tables that span pages. | Only honoured at the top level. |
| T11 | Low | Confirmed (unit test), fixed | `office/Xml.kt` | The SAX features meant to block DTDs don't exist on Android's parser, so a "billion laughs" file could expand entities until memory ran out. | DOCTYPEs are refused, through a lexical handler that Android's parser honours plus the Xerces feature. |

**Ruled out:** a negative font size (RTF `\fs-24`) doesn't crash. Compose accepts it; checked on the emulator.

**New tests:** `core/office/.../RobustnessTest.kt` has 9 tests covering T3–T7, T10 and T11, plus a check that the hand-built `.doc` reads. All 8 bug tests failed before the fix and pass after; the hang tests failed by timeout.

**Backlog (Low, not fixed):**
- Preview copies in `cache/preview` stay behind after a crash or process kill.
- An `.xls` string whose header ends exactly at a record boundary misreads its flag byte. Excel and POI avoid writing this.
- An RTF font table without per-font groups keeps only the last font.
- Text and CSV over 16 MB fall back to Windows-1252 if the cut lands mid-character.
- ODS cells drop `text:s`, tab and line-break spacing.
- Several Word lists sharing one definition restart numbering instead of continuing (fidelity).
- **Suspicion:** at Original quality, pages up to 8192 px run through OpenCV filters with several full-size copies, which risks memory pressure on 50 MP+ imports.

**Oracles after the fixes:** build OK, lint 0 errors in all four modules, no Kotlin warnings. Unit tests: `core:office` 24, app 16, `core:scanner` 11, all passing in debug and release.

---

Snapshot: `main` @ `a34139f` (clean tree), reviewed 2026-10-15.

## Verdict

**Not done.** 4 Medium findings are open (3 confirmed by code trace, 1 plausible). No Blocker or High findings.
This was one solo review pass plus a separate refutation pass, with nothing run on a device. Read the result as
"no Blocker/High found in 1 pass", not as "bug-free".

| Severity | Count |
|---|---|
| Blocker | 0 |
| High | 0 |
| Medium | 4 |
| Low (backlog) | 16 |
| Questions / suspicions | 2 |

## Fix status (second pass, same day)

M1–M4 and L1 are fixed in the working tree (not committed). `./gradlew check` now passes: lint has 0 errors in all modules, and all unit tests pass (11 existing, plus 5 new in `app/src/test`).

| ID | Fix | Test |
|---|---|---|
| M1 | Each run gets its own scratch folder (`cache/tools/<uuid>`). `start()` marks the old run as superseded, and a superseded run no longer writes state or progress. `CancellationException` is rethrown. A superseded run still finishes and saves its own output, as before. I didn't add an `ensureActive()` check because it would leave Split and To-images outputs half-written. | none (needs Robolectric or a device) |
| M2 | `SheetLayout.clampAll`, called from `render()` on success via `fitSheets`. Pages whose shape isn't known yet are skipped. | `SheetLayoutTest` (2) |
| M3 | Camera: back acts like ✕ once pages exist. Nav host: Edit PDF, Images to PDF and Viewer → Edit ask "Discard unsaved pages?" (Discard / Review pages) when a session has pages. | none (UI) |
| M4 | `pageRenderSize` caps the viewer bitmap at 16 MP and keeps its aspect ratio. | `PageRenderSizeTest` (3) |
| L1 | `BackSwipe` now uses the public `currentBackStackEntryFlow` and `previousBackStackEntry`, still with lifecycle-aware collection, so behavior is unchanged. `SheetEditor` uses a `Box`. | lint |

Not verified on a device or emulator. The diff-scoped re-review (logic, failure paths, contracts) found nothing new.

New suspicion from the re-review (Low, not fixed): in `BackSwipe`, Navigation lowers a popped screen's lifecycle before it publishes the new back stack, so the lifecycle-aware collector never sees the pop. A committed pop probably follows `held(p, 0.9p)` and ends at 90% instead of sliding fully off, as the KDoc says. This behavior predates the fix and is unchanged by it. Check it on a device with animations slowed down.

## Baseline oracles

| Oracle | Result |
|---|---|
| `:app:assembleDebug` | ✅ builds |
| Kotlin compile (`--rerun`, all 3 modules) | ✅ no warnings |
| `:core:scanner:testDebugUnitTest` | ✅ 11/11 pass (Adjustments 3, JpegPdfWriter 3, Quad 5) |
| `:core:scanner:lintDebug`, `:core:pdf:lintDebug` | ✅ only dependency-version warnings |
| `:app:lintDebug` | ❌ **2 errors** (L1): `RestrictedApi` at `PredictiveBack.kt:74`, `UnusedBoxWithConstraintsScope` at `Sheet.kt:97` |
| Instrumented tests (`connectedDebugAndroidTest`) | not run (no device attached) |

## Intent sheet and assumptions

Where intent comes from: README, `docs/ROADMAP.md`, KDoc and comments, and tests.

- **core:scanner**: detect a page in a photo, warp it, filter it and write JPEG-only PDFs. Invariant: quads are normalized and convex; what the editor previews is what gets exported.
- **core:pdf**: merge, split and compress without re-rasterizing text. Compress "never makes a file larger".
- **ScanViewModel**: the scan session is a draft that "lives until it's saved as a PDF into the library or discarded" (KDoc). Only the newest render revision of a page may commit. A sheet layout keeps every page "entirely on the sheet" (`SheetLayout.clamp`).
- **ToolsViewModel**: runs one tool at a time. Results go into the library and never overwrite the input.
- **LibraryViewModel / DockLibrary**: the library is plain SAF files. Rename and move follow the viewed file to its new URI.
- **Viewer**: zoomable rendering of any PDF in the library.
- Trust boundary: picked files (PDFs and images) and their provider metadata. The app has no network access.
- Assumption: a phone in portrait (the app locks orientation), with device widths of about 1000 to 2000 px.

## Findings

| ID | Sev | Status | Location | One line |
|---|---|---|---|---|
| M1 | Medium | Confirmed (trace) | `ui/tools/ToolsViewModel.kt:67-73, 204-222` | Leaving a running tool and opening any tool "cancels" it, but the work keeps going. It then writes "StandaloneCoroutine was cancelled" over the new tool's state, deletes the shared temp folder and still saves its output. |
| M2 | Medium | Confirmed (trace) | `ui/ScanViewModel.kt:249, 273-283, 158-187, 591-597`; `ui/editor/Sheet.kt:59-75` | Cropping, rotating or retaking a page that sits on a paper sheet keeps the old placement width, so the image can spill off the paper and be cut off in the PDF. |
| M3 | Medium | Confirmed (trace) | `ui/DockNavHost.kt:121-129, 138-157`; `ui/ScanViewModel.kt:195` | Back from the camera leaves captured pages in a hidden session. "Images to PDF" or "Edit" later wipes them with no prompt. |
| M4 | Medium | Plausible | `ui/viewer/PdfViewerScreen.kt:354, 376-386` | The viewer renders each page at up to 2000 px wide with no height cap. A very tall page (long receipt, about 1:7 to 1:9) makes a bitmap over 100 MB, and drawing it crashes the app. |

## Details by root cause

### M1: tool cancellation doesn't cancel, and runs share one temp folder

**Trigger.** Start Compress on a large PDF. While "Compressing…" shows, press back (`ToolScreen.kt:159`; no `BackHandler` blocks it). Open any tool from Home, Files or the viewer. That calls `ToolsViewModel.start()`, which runs `job?.cancel()` and sets the state to `Idle`.

**Expected vs actual.**
- `block(root)` only calls blocking functions, so the old run keeps going to the end. It still copies its result into the library (`savePdf`).
- When it finishes, `withContext` throws `CancellationException`. `catch (e: Exception)` catches it, and its value is assigned to `_state`. The new tool's screen then shows the error card **"StandaloneCoroutine was cancelled"**, and its button comes back even if the new run is still working.
- The old run's `finally` does `File(cacheDir, "tools").deleteRecursively()`. That deletes the new run's inputs while it uses them. Compress and Split also both use `tools/in.pdf`. If the new run's `copyToCache` truncates `in.pdf` while the old run's `PDDocument` is still reading it lazily, the old run can save a corrupted "… (compressed).pdf".

**Evidence.**
```kotlin
job?.cancel()                                   // start()
_state.value = try { ToolState.Done(withContext(Dispatchers.IO) { block(root) }) }
  catch (e: Exception) { ToolState.Failed(e.message ?: …) }   // also catches CancellationException
  finally { File(context.cacheDir, "tools").deleteRecursively() }
```

**Fix (smallest change).**
1. Catch and rethrow `CancellationException` before the generic `catch`, so a cancelled run never writes `_state`.
2. Give each run its own temp folder (`cacheDir/tools/<uuid>`), and delete only that folder in `finally`.
3. Call `coroutineContext.ensureActive()` before `savePdf` / `copyInto`, so a cancelled run doesn't save its output.

**Regression test.** A ViewModel test with a fake slow block. Launch the tool, call `start()`, then let the block finish. Assert that `_state` stays `Idle` and that the second run's temp folder still exists.

### M2: sheet placements aren't re-clamped when a page's shape changes

**Trigger.** Take a landscape photo with aspect 1.5. In Adjust, pick A4: the paper is landscape because `page.aspect > 1`, and the placement width is 0.92. Then use Crop → Rotate. Retaking the photo or re-cropping to a taller shape triggers it the same way.

**Expected vs actual.** The page should stay on the sheet (`SheetLayout.clamp`: "Keeps a page … entirely on the sheet"). Instead the width stays 0.92 while the aspect becomes 0.667. The height becomes 0.92 × 1.414 / 0.667 ≈ **1.95 of the sheet**. `SheetView` clips it, and `pdfPages` exports it at that size, so about half the page is lost. Clamping only happens in `updatePlacement`, `arranged` or a drag (see the grep: `ScanViewModel.kt:339,344,348,361`, `Sheet.kt:124`). Nothing re-clamps after `render()` updates `outputWidth` / `outputHeight`.

**Fix.** In `render()`'s success `update` (`ScanViewModel.kt:592`), when the page has a `layout`, re-clamp every placement with its current aspect: `l.copy(items = l.items.map { p -> l.clamp(p, aspectOf(p.pageId)) })`. Use the new `r.width / r.height` for the page itself.

**Regression test.** A JVM test on `SheetLayout`/`pdfPages`: place a page with aspect 1.5 at width 0.92 on landscape A4, flip the aspect to 0.667, and assert that every `PlacedImage` stays inside `[0, widthPt] × [0, heightPt]`.

### M3: an unsaved scan can be silently thrown away

**Trigger.** Home → Scan → take 3 photos → system back (or the back swipe). `CameraScreen` has no `BackHandler`, so Navigation pops to Main and the 3 pages stay in `ScanViewModel`. Nothing on Main shows that a draft exists. Then do either of these:
- Home → "Images to PDF": `onImagesPicked` calls `scanVm.clear()` (`DockNavHost.kt:126`).
- Files → ⋯ → Edit, or Viewer → Edit pages: `openPdf` calls `clear()` (`ScanViewModel.kt:195`).

**Expected vs actual.** The KDoc says the draft lives until it is saved or discarded, and Review asks before discarding ("Discard this scan?"). Instead the photos are deleted from disk with no prompt. The camera's own ✕ button does the right thing: it goes to Review when pages exist. Only system back is different.

**Fix.**
1. In `CameraContent`, add `BackHandler(enabled = pages.isNotEmpty() && retakePageId == null) { onDone() }`, so back behaves like ✕.
2. Before the two `clear()` calls, if `scanVm.pages.value.isNotEmpty()`, show the same discard confirmation, or go to Review instead.

**Regression test.** A Compose UI test: capture with a fake controller, press back, and assert that the route is `review`. Also a ViewModel-level test that `onImagesPicked` with an existing session asks first.

### M4: very tall PDF pages can crash the viewer

**Trigger.** Open a PDF whose page is much taller than wide, for example a long receipt scanned in Dock (`FIT_A4_WIDTH` keeps the full length) or a web page saved as a single PDF page. `renderWidthPx` is `maxWidth × 1.75`, capped at 2000. The height is `widthPx × pageH / pageW`, with no cap.

**Expected vs actual.** The page should render. But the ARGB bitmap goes over Android's 100 MB draw limit (`RecordingCanvas`: "Canvas: trying to draw too large bitmap"). That happens past an aspect of about 1:8.7 on a 1741 px render width, and about 1:6.6 at 2000 px, and drawing it throws a `RuntimeException`. `runCatching` only covers the render, not the draw, so the app crashes. Marked plausible because I couldn't run it on a device.

**Fix.** Cap the render size by pixel count, for example `scale = min(1, sqrt(24_000_000 / (w × h)))`, and shrink the width to match. Keep the layout aspect ratio so the page still lays out correctly.

**Regression test.** An instrumented test: render a 1:12 page with `PdfPage` and assert that it draws without throwing.

## Suspicions and questions (do not block)

- **Q1: owner-password PDFs.** `PdfTools.open` (`core/pdf/.../PdfTools.kt:57`) rejects every PDF where `isEncrypted` is true. That includes owner-password-only files, which open without a password (common for bank statements). They open fine in the viewer and editor, but Compress, Merge and Split say "Password-protected PDFs aren't supported yet". Is that intended? `doc.setAllSecurityToBeRemoved(true)` would let them through.
- **Q2: compress colors.** `PdfTools.decode` (`PdfTools.kt:190-201`) decodes DCT images with `BitmapFactory`, which ignores the PDF's `/Decode` array and Separation/DeviceN/ICC color spaces. Rare inputs could come out inverted or with shifted colors after compression. No concrete file was found to confirm this.

## Low backlog (not blocking, no further rounds)

- **L1.** `:app:lintDebug` fails with 2 errors. `PredictiveBack.kt:74` uses the library-restricted `NavController.currentBackStack`. `Sheet.kt:97` has a `BoxWithConstraints` whose scope is never used. `./gradlew check` fails because of these.
- **L2.** `PdfSources.withRenderer` (`PdfSources.kt:52-61`) closes the file descriptor only on `SecurityException`. A corrupt PDF (`IOException`) leaks it.
- **L3.** `ZoomablePdf` (`PdfViewerScreen.kt:246-248`) opens the file descriptor and `PdfRenderer` on the main thread, and leaks the descriptor if the constructor throws.
- **L4.** `rememberDecodedBitmap` (`PageCanvas.kt:190-205`) isn't keyed on the file. If you switch in the page strip to a page whose `base` is still null, the editor keeps showing the previous page's image, and strokes drawn then use the wrong geometry.
- **L5.** `LiveDocumentAnalyzer` never releases its `Mat` or the detector's three kernels, so native memory leaks a little per camera session.
- **L6.** If `openPdf` fails halfway, the page JPEGs it already rendered stay in `files/session` until the next app start (`ScanViewModel.kt:201-228`).
- **L7.** `DockLibrary.rename` / `createPdf` strip the extension case-sensitively. Typing "Report.PDF" gives "Report.PDF.pdf".
- **L8.** The Save and Rename dialogs accept names starting with ".", but `DockLibrary.query` hides those files (`DockLibrary.kt:156`), so the saved PDF disappears from the app.
- **L9.** `targetFolderId` isn't reset on discard or by "Images to PDF" (`DockNavHost.kt:125-129`), so the save sheet can preselect a folder from an earlier session.
- **L10.** `filterForAll` / `adjustForAll` survive `clear()`. A new scan starts with "Apply to all pages" still on.
- **L11.** `PdfRow` runs `rememberPdfPreview` for image files too (`LibraryUi.kt:121`). The `PdfRenderer` failure isn't cached, so every recomposition retries and holds one of the 2 preview permits.
- **L12.** `ToolsViewModel.addInputs` isn't tied to `job`. Switching tools quickly can add the previous tool's preset input to the new tool. Overlapping loads also clear `loadingInputs` early.
- **L13.** Editing a page while a save is running: the edits are dropped by `clear()` after the save, and a render commit can delete an output file the writer is still reading (short window).
- **L14.** `ShelfScreen` calls `vm.rootLabel()` during composition, which does a provider query on the main thread for non-external-storage roots.
- **L15.** Viewer: renaming or moving the file while pages are still rendering can leave those pages blank, because `produceState` isn't keyed on `document`.
- **L16.** A pinch in `SheetEditor` can shrink a page to 0.05, below the Size slider's `MIN_WIDTH` of 0.08, so the slider then shows a clamped value.

## Coverage

- **Reviewed (whole files):** all 50 Kotlin sources under `app/`, `core/scanner/`, `core/pdf/`, plus the manifest, Gradle files, ProGuard/consumer rules and the FileProvider paths. Lenses: logic and edge cases, failure paths, state and concurrency, contracts and integration, and security (path handling in `copyToCache`; display names are fixed by the callers, so no traversal).
- **Not covered:** visual and animation code in `Glass.kt` / `Theme.kt` (skimmed only). Instrumented tests weren't run. Nothing was run on a device or emulator, so M4 and the timing part of M1 are traced, not reproduced. Performance was only looked at where it affects correctness.

## Convergence metrics

- Independent finder passes: 1 (solo). There was a separate refutation pass; every Medium survived it.
- Thorough mode wasn't requested, so no capture-recapture estimate is available.
- Next step to converge: fix M1–M4, then run a fresh blind pass over the changed hunks and their callers.
