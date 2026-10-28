package com.dock.scanner.ui

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dock.scanner.DockApp
import com.dock.scanner.core.DocumentDetector
import com.dock.scanner.core.JpegPdfWriter
import com.dock.scanner.core.PageFilter
import com.dock.scanner.core.PageProcessor
import com.dock.scanner.core.Point2
import com.dock.scanner.core.Quad
import com.dock.scanner.data.ImageImport
import com.dock.scanner.data.InkStroke
import com.dock.scanner.data.Page
import com.dock.scanner.data.PageCompositor
import com.dock.scanner.data.Paper
import com.dock.scanner.data.Placement
import com.dock.scanner.data.ScanMode
import com.dock.scanner.data.ScanQuality
import com.dock.scanner.data.SheetLayout
import com.dock.scanner.data.StrokeKind
import com.dock.scanner.data.storage.DockLibrary
import com.dock.scanner.data.storage.LibraryItem
import com.dock.scanner.data.tools.PdfSources
import com.dock.scanner.pdf.PasswordProtectedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.sqrt

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
}

/** The PDF being edited: saving can write back to [item], or make a copy. */
data class EditTarget(val name: String, val item: LibraryItem?)

/**
 * Holds the current scan session: the pages captured so far and their processing state.
 *
 * The session is a draft: it lives until it's saved as a PDF into the library or discarded.
 *
 * Rendering has two stages. The base (crop, perspective, filter, rotation) is the slow OpenCV
 * step and only re-runs when one of those changes; brightness/contrast and ink strokes are
 * composited on top of it cheaply, so sliders and drawing stay responsive.
 */
class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val sessionDir = File(app.filesDir, "session").apply {
        deleteRecursively()
        mkdirs()
    }

    /** Two pages at a time: keeps the UI responsive and memory bounded with 12 MP photos. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.Default.limitedParallelism(2)
    private val sequence = AtomicLong()

    private val _pages = MutableStateFlow<List<Page>>(emptyList())

    /** Every page, including ones placed onto another page's sheet. */
    val pages: StateFlow<List<Page>> = _pages.asStateFlow()

    /** The pages that become PDF pages, in order (pages placed on a sheet are part of their host). */
    val sheets: StateFlow<List<Page>> = _pages.map { list -> list.filter { it.hostId == null } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    /** "Apply to all pages" switches; they stay on while you move between pages. */
    val filterForAll = MutableStateFlow(false)
    val adjustForAll = MutableStateFlow(false)

    /** Set by the editor's Reorder tool; the review grid then lets pages be dragged. */
    val reorderMode = MutableStateFlow(false)

    /** Non-null while the session is an opened PDF rather than a new scan. */
    val editTarget = MutableStateFlow<EditTarget?>(null)

    /** Progress (0..1) while a PDF's pages are being loaded for editing. */
    private val _opening = MutableStateFlow<Float?>(null)
    val opening: StateFlow<Float?> = _opening.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val settings = (app as DockApp).settings

    /** Scan quality for new pages; changing it re-renders the pages already in the session. */
    private val _quality = MutableStateFlow(settings.scanQuality)
    val quality: StateFlow<ScanQuality> = _quality.asStateFlow()

    fun setQuality(quality: ScanQuality) {
        settings.scanQuality = quality
        _quality.value = quality
        _pages.value.forEach { page -> edit(page.id) { it.copy(quality = quality) } }
    }

    /** Whether camera photos are cropped to the page's edges or kept whole. */
    private val _scanMode = MutableStateFlow(settings.scanMode)
    val scanMode: StateFlow<ScanMode> = _scanMode.asStateFlow()

    fun setScanMode(mode: ScanMode) {
        settings.scanMode = mode
        _scanMode.value = mode
    }

    fun newCaptureFile(): File = File(sessionDir, "src_${UUID.randomUUID()}.jpg")

    // region Adding pages

    /**
     * Adds a photo that is already on disk (camera capture or import), detects edges and renders it.
     * Without [cropToEdges] the whole photo is kept; the edges found are still offered by the crop tool.
     */
    fun addPhoto(file: File, seq: Long = sequence.incrementAndGet(), cropToEdges: Boolean = true) {
        viewModelScope.launch {
            val page = withContext(worker) {
                runCatching {
                    val (w, h) = PageProcessor.uprightSize(file)
                    val detected = runCatching { PageProcessor.detect(file, DocumentDetector()) }.getOrNull()
                    Page(
                        id = UUID.randomUUID().toString(),
                        sequence = seq,
                        source = file,
                        sourceWidth = w,
                        sourceHeight = h,
                        quad = detected?.takeIf { cropToEdges } ?: Quad.FULL,
                        detectedQuad = detected,
                        filter = PageFilter.AUTO,
                        rotation = 0,
                        quality = _quality.value,
                    )
                }.getOrNull()
            }
            if (page == null) {
                file.delete()
                message("Couldn't read that image")
                return@launch
            }
            _pages.update { (it + page).sortedBy(Page::sequence) }
            render(page.id)
        }
    }

    fun importImages(uris: List<Uri>) {
        val resolver = getApplication<Application>().contentResolver
        for (uri in uris) {
            val seq = sequence.incrementAndGet()
            viewModelScope.launch {
                val file = newCaptureFile()
                val ok = withContext(Dispatchers.IO) {
                    runCatching { ImageImport.copyAsUprightJpeg(resolver, uri, file, _quality.value) }.isSuccess
                }
                if (ok) addPhoto(file, seq) else message("Couldn't import an image")
            }
        }
    }

    /**
     * Replaces a page's photo with a new capture. Filter and brightness carry over; the crop is
     * detected again, and strokes are dropped because they were drawn on the old photo.
     */
    fun retakePhoto(id: String, file: File, cropToEdges: Boolean = true) {
        viewModelScope.launch {
            val info = withContext(worker) {
                runCatching {
                    val size = PageProcessor.uprightSize(file)
                    size to runCatching { PageProcessor.detect(file, DocumentDetector()) }.getOrNull()
                }.getOrNull()
            }
            val old = find(id)
            if (info == null || old == null) {
                file.delete()
                if (info == null) message("Couldn't read the new photo")
                return@launch
            }
            val (size, detected) = info
            update(id) {
                it.copy(
                    source = file,
                    sourceWidth = size.first,
                    sourceHeight = size.second,
                    quad = detected?.takeIf { cropToEdges } ?: Quad.FULL,
                    detectedQuad = detected,
                    rotation = 0,
                    strokes = emptyList(),
                )
            }
            old.source.delete()
            render(id)
        }
    }

    /**
     * Loads every page of a PDF into the session as an editable page (rendered at 200 dpi, or
     * 300 at Original quality), so it can be edited with the same tools as a scan.
     */
    fun openPdf(uri: Uri, item: LibraryItem?, onReady: () -> Unit) {
        if (_opening.value != null) return
        clear()
        _opening.value = 0f
        val resolver = getApplication<Application>().contentResolver
        val quality = _quality.value
        viewModelScope.launch {
            try {
                val name = item?.displayName ?: withContext(Dispatchers.IO) { PdfSources.describe(resolver, uri).name }
                val pages = withContext(Dispatchers.IO) {
                    PdfSources.withRenderer(resolver, uri) { renderer ->
                        List(renderer.pageCount) { index ->
                            val (bitmap, size) = PdfSources.render(renderer, index, quality.pdfDpi, quality.pdfMaxSide)
                            val width = bitmap.width
                            val height = bitmap.height
                            val file = newCaptureFile()
                            try {
                                val jpegQuality = if (quality == ScanQuality.Original) 100 else 92
                                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, jpegQuality, it) }
                            } finally {
                                bitmap.recycle()
                            }
                            _opening.value = (index + 1f) / renderer.pageCount
                            Page(
                                id = UUID.randomUUID().toString(),
                                sequence = sequence.incrementAndGet(),
                                source = file,
                                sourceWidth = width,
                                sourceHeight = height,
                                quad = Quad.FULL,
                                detectedQuad = null,
                                filter = PageFilter.ORIGINAL,
                                rotation = 0,
                                pdfPageSize = size,
                                quality = quality,
                            )
                        }
                    }
                }
                if (pages.isEmpty()) error("This PDF has no pages")
                editTarget.value = EditTarget(name, item)
                _pages.value = pages
                pages.forEach { render(it.id) }
                onReady()
            } catch (e: PasswordProtectedException) {
                message("Password-protected PDFs can't be opened yet")
            } catch (e: Exception) {
                clear()
                message("Couldn't open the PDF: ${e.message}")
            } finally {
                _opening.value = null
            }
        }
    }

    // endregion

    // region Page edits

    fun setQuad(id: String, quad: Quad) = edit(id) { it.copy(quad = quad) }

    fun setFilter(id: String, filter: PageFilter) {
        if (filterForAll.value) _pages.value.forEach { page -> edit(page.id) { it.copy(filter = filter) } }
        else edit(id) { it.copy(filter = filter) }
    }

    /** Turning "apply to all" on copies this page's filter to every page right away. */
    fun setFilterForAll(enabled: Boolean, fromId: String) {
        filterForAll.value = enabled
        if (enabled) find(fromId)?.let { setFilter(fromId, it.filter) }
    }

    fun setAdjust(id: String, brightness: Float, contrast: Float) {
        val targets = if (adjustForAll.value) _pages.value.map { it.id } else listOf(id)
        targets.forEach { pageId -> edit(pageId) { it.copy(brightness = brightness, contrast = contrast) } }
    }

    fun setAdjustForAll(enabled: Boolean, fromId: String) {
        adjustForAll.value = enabled
        if (enabled) find(fromId)?.let { setAdjust(fromId, it.brightness, it.contrast) }
    }

    /** Rotates the page and its strokes together, so drawings stay where they were put. */
    fun rotateClockwise(id: String) = edit(id) { page ->
        val ratio = if (page.outputWidth > 0 && page.outputHeight > 0) page.outputWidth.toFloat() / page.outputHeight else 1f
        page.copy(
            rotation = (page.rotation + 90) % 360,
            strokes = page.strokes.map { s ->
                s.copy(points = s.points.map { Point2(1f - it.y, it.x) }, width = s.width * ratio)
            },
            outputWidth = page.outputHeight,
            outputHeight = page.outputWidth,
        )
    }

    fun addStroke(id: String, stroke: InkStroke) = edit(id) { it.copy(strokes = it.strokes + stroke) }

    fun undoStroke(id: String, kind: StrokeKind) = edit(id) { page ->
        val last = page.strokes.indexOfLast { it.kind == kind }
        if (last < 0) page else page.copy(strokes = page.strokes.filterIndexed { i, _ -> i != last })
    }

    fun delete(id: String) {
        val page = find(id) ?: return
        _pages.update { list ->
            normalized(
                list.filterNot { it.id == id }.map {
                    when {
                        it.hostId == id -> it.copy(hostId = null) // its sheet is gone: back to a page of its own
                        it.id == page.hostId -> it.copy(layout = it.layout?.without(id))
                        else -> it
                    }
                },
            )
        }
        page.source.delete()
        page.base?.delete()
        page.output?.delete()
    }

    fun clear() {
        val old = _pages.value
        _pages.value = emptyList()
        reorderMode.value = false
        editTarget.value = null
        old.forEach {
            it.source.delete()
            it.base?.delete()
            it.output?.delete()
        }
    }

    // endregion

    // region Sheets (Adjust tool)

    /** Puts the page on a sheet of [paper], or back to its own size when [paper] is null. */
    fun setPaper(id: String, paper: Paper?, landscape: Boolean) {
        val page = find(id) ?: return
        if (paper == null) {
            if (page.layout?.items.orEmpty().size > 1) {
                message("Remove the other pages from this sheet first")
                return
            }
            update(id) { it.copy(layout = null) }
            return
        }
        val layout = (page.layout ?: SheetLayout(paper, landscape, listOf(Placement(id, 0.5f, 0.5f, 1f))))
            .copy(paper = paper, landscape = landscape)
        update(id) { it.copy(layout = arranged(layout)) }
    }

    fun updatePlacement(hostId: String, placement: Placement) = update(hostId) { host ->
        val aspect = find(placement.pageId)?.aspect ?: return@update host
        host.copy(layout = host.layout?.let { it.with(it.clamp(placement, aspect)) })
    }

    /** Lays the sheet's pages out again in an even grid. */
    fun tidySheet(hostId: String) = update(hostId) { host -> host.copy(layout = host.layout?.let(::arranged)) }

    /** Places another page onto this page's sheet; it stops being a PDF page of its own. */
    fun addToSheet(hostId: String, guestId: String) {
        val host = find(hostId) ?: return
        val guest = find(guestId) ?: return
        if (guest.layout?.items.orEmpty().size > 1) {
            message("That page already has its own sheet")
            return
        }
        val base = host.layout ?: SheetLayout(
            Paper.A4, landscape = false, items = listOf(Placement(hostId, 0.5f, 0.5f, 1f)),
        )
        val layout = arranged(base.with(Placement(guestId, 0.5f, 0.5f, 0.5f)))
        _pages.update { list ->
            normalized(
                list.map {
                    when (it.id) {
                        hostId -> it.copy(layout = layout)
                        guestId -> it.copy(hostId = hostId, layout = null)
                        else -> it
                    }
                },
            )
        }
    }

    fun removeFromSheet(hostId: String, guestId: String) {
        _pages.update { list ->
            normalized(
                list.map {
                    when (it.id) {
                        hostId -> it.copy(layout = it.layout?.without(guestId))
                        guestId -> it.copy(hostId = null)
                        else -> it
                    }
                },
            )
        }
    }

    /** Crop, rotation and retake change a page's shape: keep every sheet's pages on the paper. */
    private fun fitSheets(list: List<Page>): List<Page> {
        val aspects = list.filter { it.outputWidth > 0 && it.outputHeight > 0 }.associate { it.id to it.aspect }
        return list.map { page -> page.layout?.let { page.copy(layout = it.clampAll(aspects::get)) } ?: page }
    }

    /** Fits every page on the sheet into an even grid of cells. */
    private fun arranged(layout: SheetLayout): SheetLayout {
        val n = layout.items.size
        if (n == 0) return layout
        val cols = if (layout.landscape) ceil(sqrt(n.toFloat())).toInt().coerceAtLeast(if (n > 1) 2 else 1)
        else if (n <= 2) 1 else ceil(sqrt(n.toFloat())).toInt()
        val rows = ceil(n / cols.toFloat()).toInt()
        val margin = if (n == 1) 0.92f else 0.86f
        return layout.copy(
            items = layout.items.mapIndexed { i, item ->
                val aspect = find(item.pageId)?.aspect ?: (1 / 1.414f)
                val col = i % cols
                val row = i / cols
                val width = minOf(margin / cols, (margin / rows) * aspect / layout.aspect)
                item.copy(centerX = (col + 0.5f) / cols, centerY = (row + 0.5f) / rows, width = width)
            },
        )
    }

    // endregion

    // region Order

    /** Moves a sheet in the review grid; its placed pages travel with it. */
    fun moveSheet(fromId: String, toId: String) {
        _pages.update { list ->
            val sheets = list.filter { it.hostId == null }.toMutableList()
            val from = sheets.indexOfFirst { it.id == fromId }
            val to = sheets.indexOfFirst { it.id == toId }
            if (from < 0 || to < 0 || from == to) return@update list
            sheets.add(to, sheets.removeAt(from))
            val guests = list.filter { it.hostId != null }
            normalized(sheets + guests)
        }
    }

    /** Keeps each sheet's placed pages right after it and renumbers, so new pages append at the end. */
    private fun normalized(list: List<Page>): List<Page> {
        val guestsByHost = list.filter { it.hostId != null }.groupBy { it.hostId }
        val ordered = list.filter { it.hostId == null }.flatMap { listOf(it) + guestsByHost[it.id].orEmpty() }
        val orphans = list.filter { it.hostId != null && ordered.none { o -> o.id == it.id } }.map { it.copy(hostId = null) }
        return (ordered + orphans).mapIndexed { i, p -> p.copy(sequence = i.toLong()) }
    }

    // endregion

    // region Saving

    fun suggestedFileName(): String =
        "Scan ${SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date())}"

    /** Folder that was open on the Shelf when the scan started; the save sheet preselects it. */
    var targetFolderId: String? = null

    /**
     * Writes the session as a PDF into the library folder [parentId], then clears the session.
     * The PDF is created first and removed again if writing fails, so no empty files are left behind.
     */
    fun saveToLibrary(library: DockLibrary, root: Uri, parentId: String, name: String, onSaved: (String) -> Unit) =
        save(onSaved, name) { library.createPdf(root, parentId, name) ?: error("Couldn't create the file") }

    /** Writes the edited PDF back over the original file, renaming it if the name changed. */
    fun saveOverOriginal(library: DockLibrary, item: LibraryItem, name: String, onSaved: (String) -> Unit) =
        save(onSaved, name, replacing = true) {
            if (name != item.displayName) library.rename(item, name) ?: error("Couldn't rename") else item.uri
        }

    private fun save(onSaved: (String) -> Unit, name: String, replacing: Boolean = false, target: () -> Uri) {
        if (_exporting.value || _pages.value.isEmpty()) return
        _exporting.value = true
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            try {
                // Wait for any edits still rendering so the PDF matches the screen.
                val pages = _pages.first { list -> list.none { it.processing } }
                withContext(Dispatchers.IO) {
                    val specs = pdfPages(pages)
                    if (replacing) {
                        // Write to a temp file first, so a failure can't leave the original half-written.
                        val tmp = File(sessionDir, "replace.pdf")
                        tmp.outputStream().use { JpegPdfWriter().writePages(specs, it) }
                        val uri = target()
                        resolver.openOutputStream(uri, "wt")!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                        tmp.delete()
                    } else {
                        val uri = target()
                        try {
                            resolver.openOutputStream(uri, "wt")!!.use { JpegPdfWriter().writePages(specs, it) }
                        } catch (e: Exception) {
                            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                            throw e
                        }
                    }
                }
                clear()
                targetFolderId = null
                onSaved(name)
            } catch (e: Exception) {
                message("Couldn't save: ${e.message}")
            } finally {
                _exporting.value = false
            }
        }
    }

    private fun pdfPages(pages: List<Page>): List<JpegPdfWriter.PageSpec> {
        val byId = pages.associateBy { it.id }
        return pages.filter { it.hostId == null }.map { page ->
            val layout = page.layout
            if (layout == null) {
                val out = page.output ?: error("A page isn't ready")
                val info = JpegPdfWriter.readJpegInfo(out)
                val original = page.pdfPageSize
                val (w, h) = if (original != null) {
                    // Pages from an opened PDF keep their paper width.
                    val width = if (page.rotation % 180 == 0) original.first else original.second
                    width to width * info.height / info.width
                } else {
                    JpegPdfWriter.FIT_A4_WIDTH.size(info)
                }
                JpegPdfWriter.PageSpec(w, h, listOf(JpegPdfWriter.PlacedImage(out, 0f, 0f, w, h)))
            } else {
                val sheetW = layout.widthPt
                val sheetH = layout.heightPt
                JpegPdfWriter.PageSpec(
                    sheetW, sheetH,
                    layout.items.mapNotNull { item ->
                        val placed = byId[item.pageId] ?: return@mapNotNull null
                        val out = placed.output ?: return@mapNotNull null
                        val w = item.width * sheetW
                        val h = w / placed.aspect
                        val top = item.centerY * sheetH - h / 2
                        // PDF's origin is bottom-left; the layout's is top-left.
                        JpegPdfWriter.PlacedImage(out, item.centerX * sheetW - w / 2, sheetH - top - h, w, h)
                    },
                )
            }
        }
    }

    // endregion

    // region Rendering

    /** Changes a page and re-renders it. */
    private fun edit(id: String, change: (Page) -> Page) {
        val before = find(id) ?: return
        val after = change(before)
        if (after == before) return
        _pages.update { list -> list.map { if (it.id == id) after else it } }
        render(id)
    }

    /** Changes a page without re-rendering (layout-only changes). */
    private fun update(id: String, change: (Page) -> Page) {
        _pages.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private class Rendered(val base: File, val baseKey: String, val output: File, val width: Int, val height: Int)

    /**
     * Re-renders a page. Each request bumps the page's revision; a render that finishes
     * after a newer request was made is thrown away, so rapid edits never show stale output.
     */
    private fun render(id: String) {
        var revision = 0
        _pages.update { list ->
            list.map {
                if (it.id != id) it else it.copy(processing = true, revision = it.revision + 1).also { p -> revision = p.revision }
            }
        }
        viewModelScope.launch {
            val created = ArrayList<File>()
            val result = withContext(worker) {
                val page = find(id)?.takeIf { it.revision == revision } ?: return@withContext null
                runCatching {
                    var base = page.base
                    var width = page.outputWidth
                    var height = page.outputHeight
                    if (base == null || page.baseKey != page.renderKey || !base.exists()) {
                        base = File(sessionDir, "base_${id}_$revision.jpg").also(created::add)
                        val size = PageProcessor.process(
                            page.source, page.quad, page.filter, page.rotation, base,
                            maxSide = page.quality.maxSide,
                            jpegQuality = page.quality.jpegQuality,
                        )
                        width = size.first
                        height = size.second
                    }
                    val output = if (PageCompositor.needsCompositing(page)) {
                        File(sessionDir, "page_${id}_$revision.jpg").also {
                            created += it
                            PageCompositor.compose(
                                base, page.brightness, page.contrast, page.strokes, it,
                                jpegQuality = maxOf(88, page.quality.jpegQuality),
                            )
                        }
                    } else {
                        base
                    }
                    Rendered(base, page.renderKey, output, width, height)
                }
            } ?: return@launch

            val current = find(id)
            if (current == null || current.revision != revision) {
                created.forEach(File::delete)
                return@launch
            }
            result.onSuccess { r ->
                _pages.update { list ->
                    fitSheets(
                        list.map {
                            if (it.id != id) it else it.copy(
                                base = r.base, baseKey = r.baseKey, output = r.output,
                                outputWidth = r.width, outputHeight = r.height, processing = false,
                            )
                        },
                    )
                }
                listOfNotNull(current.base, current.output)
                    .filter { it != r.base && it != r.output }
                    .forEach(File::delete)
            }.onFailure {
                created.forEach(File::delete)
                update(id) { it.copy(processing = false) }
                message("Couldn't process page: ${it.message}")
            }
        }
    }

    // endregion

    fun find(id: String): Page? = _pages.value.firstOrNull { it.id == id }

    private fun message(text: String) {
        _events.trySend(UiEvent.Message(text))
    }
}
