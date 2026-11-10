package com.dock.scanner.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import com.dock.scanner.data.DockSettings
import com.dock.scanner.data.storage.DockLibrary
import com.dock.scanner.data.storage.LibraryItem
import com.dock.scanner.ocr.OcrPage
import com.dock.scanner.ocr.TextRecognizer
import com.dock.scanner.pdf.PasswordProtectedException
import com.dock.scanner.pdf.PdfTextLayer
import com.dock.scanner.pdf.PdfTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Searchable text for the library's PDFs: reads the text of each page on the phone and writes
 * it into the PDF as an invisible layer ([PdfTextLayer]), so any PDF app can search and copy it.
 *
 * New scans get it right after saving (unless turned off in the scan settings); any PDF can
 * be switched on or off from its menu, or all at once. Jobs run one at a time in the
 * background while the app is alive. Each works on a copy and only writes it back if the file
 * didn't change meanwhile; the file keeps the date it showed before.
 */
class DocumentOcr(context: Context, private val library: DockLibrary, private val settings: DockSettings) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** @param bulk part of "Make all searchable": skipped quietly if not needed, reported together. */
    private class Task(
        val uri: Uri,
        val documentId: String,
        val name: String,
        val on: Boolean,
        val announce: Boolean,
        val bulk: Boolean = false,
    )

    private val queue = Channel<Task>(Channel.UNLIMITED)

    /** The latest job wanted for each file (by document ID); older ones still in the queue are skipped. */
    private val pending = ConcurrentHashMap<String, Task>()

    /** Files whose text is being read, now or soon (by document ID), and how far along they are (0..1). */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    /** Document IDs of files that were rewritten, so lists and viewers can reload them. */
    private val _changed = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val changed: SharedFlow<String> = _changed.asSharedFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** The task being worked on, so it can be stopped. */
    @Volatile private var running: Pair<Task, Job>? = null

    /** Loaded on first use and closed when the queue runs dry: the models take a lot of memory. */
    private var recognizer: TextRecognizer? = null

    private val infoCache = LruCache<String, PdfTextLayer.Info>(64)

    /** "Make all searchable" so far: PDFs that got text, and those that couldn't be read. */
    private var bulkMade = 0
    private var bulkFailed = 0
    @Volatile private var bulkRunning = false
    @Volatile private var bulkStopped = false

    init {
        scope.launch {
            for (task in queue) {
                if (pending[task.documentId] !== task) continue // replaced or stopped while waiting
                val job = launch(Dispatchers.IO) { process(task) }
                running = task to job
                job.join()
                running = null
                if (pending.remove(task.documentId, task)) _progress.update { it - task.documentId }
                if (pending.isEmpty()) {
                    recognizer?.close()
                    recognizer = null
                    if (bulkRunning) finishBulk()
                }
            }
        }
    }

    /** What Dock knows about [item]'s searchable text, or null if it can't be read (e.g. password-protected). */
    suspend fun info(item: LibraryItem): PdfTextLayer.Info? {
        val key = "${item.uri}@${item.lastModified}"
        infoCache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { app.contentResolver.openInputStream(item.uri)!!.use(PdfTools::textLayerInfo) }.getOrNull()
        }?.also { infoCache.put(key, it) }
    }

    /** The user's choice stored in the PDF at [uri], or null if there's none. */
    fun choice(uri: Uri): Boolean? =
        runCatching { app.contentResolver.openInputStream(uri)!!.use(PdfTools::textLayerChoice) }.getOrNull()

    /** Recognizes [item]'s text and adds it to the PDF. */
    fun turnOn(item: LibraryItem) = enqueue(Task(item.uri, item.documentId, item.displayName, on = true, announce = true))

    /** Takes the text out again, and remembers that the user wants it off. */
    fun turnOff(item: LibraryItem) {
        stop(item.documentId)
        enqueue(Task(item.uri, item.documentId, item.displayName, on = false, announce = false))
    }

    fun stop(documentId: String) {
        bulkStopped = true
        pending.remove(documentId)
        _progress.update { it - documentId }
        running?.takeIf { it.first.documentId == documentId }?.second?.cancel()
    }

    /**
     * Makes every PDF in [items] searchable that isn't yet, one at a time: PDFs the user
     * switched off and pages that already have text are left alone.
     */
    fun makeAllSearchable(items: List<LibraryItem>) {
        val pdfs = items.filter { it.isPdf && !pending.containsKey(it.documentId) }
        if (pdfs.isEmpty()) return
        if (!bulkRunning) {
            bulkMade = 0
            bulkFailed = 0
            bulkStopped = false
            bulkRunning = true
        }
        pdfs.forEach { enqueue(Task(it.uri, it.documentId, it.displayName, on = true, announce = false, bulk = true)) }
    }

    /** Stops everything, e.g. a "Make all searchable" that's taking too long. Finished PDFs keep their text. */
    fun stopAll() {
        pending.keys.toList().forEach(::stop)
    }

    private fun finishBulk() {
        bulkRunning = false
        val made = if (bulkMade == 1) "1 PDF is now searchable" else "$bulkMade PDFs are now searchable"
        _messages.trySend(
            when {
                bulkMade == 0 && bulkFailed == 0 -> if (bulkStopped) "Stopped" else "Every PDF was already searchable"
                bulkFailed == 0 -> made
                else -> "$made; $bulkFailed couldn't be read"
            },
        )
    }

    /**
     * After the scanner saved a PDF at [uri]: recognizes its text if [previousChoice] (the
     * replaced file's) says so, or, for a new file, if the setting for new scans is on.
     */
    fun afterSave(uri: Uri, name: String, previousChoice: Boolean?) {
        if (previousChoice ?: settings.ocrNewScans) {
            enqueue(Task(uri, library.idOf(uri), name, on = true, announce = false))
        }
    }

    private fun enqueue(task: Task) {
        if (pending[task.documentId]?.on == task.on) return // already on its way
        pending[task.documentId] = task
        if (task.on) _progress.update { it + (task.documentId to 0f) }
        queue.trySend(task)
    }

    private suspend fun process(task: Task) {
        val dir = File(app.cacheDir, "ocr/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val before = library.stat(task.uri) ?: return
            val input = File(dir, "in.pdf")
            app.contentResolver.openInputStream(task.uri)!!.use { src -> input.outputStream().use { src.copyTo(it) } }
            val output = File(dir, "out.pdf")
            if (task.bulk) {
                val info = input.inputStream().use(PdfTools::textLayerInfo)
                if (info.hasLayer || info.choice == false) return
            }
            if (task.on) {
                val pages = PdfTools.pagesWithoutText(input)
                if (pages.isEmpty()) {
                    if (task.announce) _messages.trySend("“${task.name}” already has selectable text")
                    return
                }
                val texts = recognize(input, pages) { done -> report(task, done) }
                // Blank pages, photos, or pages lying on their side, which the models can't read.
                if (texts.values.all { page -> page.lines.all { it.isEmpty() } }) {
                    if (task.announce) _messages.trySend("Dock couldn't find any text in “${task.name}”")
                    return
                }
                PdfTools.addTextLayer(input, output, texts)
            } else {
                PdfTools.removeTextLayer(input, output)
            }
            coroutineContext.ensureActive()
            // Saved over or edited elsewhere while we worked: keep that version.
            if (library.stat(task.uri) != before) {
                if (task.announce) _messages.trySend("“${task.name}” changed while its text was being read. Try again.")
                return
            }
            try {
                write(output, task.uri)
            } catch (e: IOException) {
                // E.g. the storage filled up. The file was emptied by then, so put the original back: it's smaller, so it fits.
                runCatching { write(input, task.uri) }
                throw e
            }
            // Only the text layer changed: the file keeps its place in the lists.
            library.keepDate(task.uri, before)
            _changed.tryEmit(task.documentId)
            if (task.bulk) bulkMade++
            if (task.announce) _messages.trySend("“${task.name}” is now searchable")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Errors too: a malformed PDF can overflow the stack, and a huge one can run out of memory.
            Log.w("DocumentOcr", "Text recognition failed", e)
            when {
                task.bulk -> bulkFailed++
                e is PasswordProtectedException -> _messages.trySend("“${task.name}” is password-protected")
                else -> _messages.trySend("Couldn't read the text of “${task.name}”")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun write(file: File, uri: Uri) {
        app.contentResolver.openOutputStream(uri, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /** Renders each page in [pages] (0-based) and reads its text. */
    private suspend fun recognize(pdf: File, pages: List<Int>, onProgress: (Float) -> Unit): Map<Int, PdfTextLayer.PageText> {
        val reader = recognizer ?: TextRecognizer.load(app).also { recognizer = it }
        val texts = HashMap<Int, PdfTextLayer.PageText>()
        PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            pages.forEachIndexed { n, index ->
                coroutineContext.ensureActive()
                val bitmap = renderer.openPage(index).use { page ->
                    val (width, height) = renderSize(page.width, page.height)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.WHITE)
                        page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
                val result = try {
                    reader.recognize(bitmap)
                } finally {
                    bitmap.recycle()
                }
                texts[index] = result.toPageText()
                onProgress((n + 1f) / pages.size)
            }
        }
        return texts
    }

    private fun report(task: Task, done: Float) {
        if (pending[task.documentId] === task) _progress.update { it + (task.documentId to done) }
    }

    private fun OcrPage.toPageText() = PdfTextLayer.PageText(
        width, height,
        lines.map { line -> line.words.map { PdfTextLayer.Word(it.text, it.x, it.y, it.width, it.height, it.cos, it.sin) } },
    )

    private companion object {
        /** Long side of a page image for reading: about 220 dpi on A4, plenty for body text. */
        const val RENDER_SIDE = 2560

        /** Caps odd shapes (long receipts, posters) at 12 MP. */
        const val MAX_PIXELS = 12_000_000.0

        fun renderSize(pageW: Int, pageH: Int): Pair<Int, Int> {
            var scale = RENDER_SIDE.toDouble() / max(pageW, pageH)
            scale = minOf(scale, sqrt(MAX_PIXELS / (pageW.toDouble() * pageH)))
            return (pageW * scale).toInt().coerceAtLeast(1) to (pageH * scale).toInt().coerceAtLeast(1)
        }
    }
}
