package com.dock.scanner.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.dock.scanner.ocr.OcrPage
import com.dock.scanner.ocr.TextRecognizer
import com.dock.scanner.pdf.PdfPageText
import com.dock.scanner.pdf.PdfTextReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The text of an open PDF's pages, for selecting and searching it: read the first time a page
 * is asked for, then kept. A page with next to no text of its own (a scan that was never made
 * searchable) is read with on-device text recognition instead, so its words can be selected
 * and found too. Nothing is written to the file.
 *
 * Works on a copy of the PDF in the cache, one page at a time on a thread of its own.
 */
class PdfTextSource(context: Context, private val uri: Uri) : Closeable {

    private val app = context.applicationContext

    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val dir = File(app.cacheDir, "viewer/${UUID.randomUUID()}")
    private val pages = ConcurrentHashMap<Int, PdfPageText>()

    // Opened on first use, only ever touched on [worker].
    private var copy: File? = null
    private var reader: PdfTextReader? = null
    private var renderer: PdfRenderer? = null
    private var recognizer: TextRecognizer? = null
    private var unreadable = false

    @Volatile private var closed = false

    /** The text of page [index] if it has been read already. */
    fun cached(index: Int): PdfPageText? = pages[index]

    /**
     * The text of page [index] (0-based): the PDF's own, or, on a page that hardly has any,
     * whatever text recognition finds on its image. Empty when there's none, or the file can't
     * be read (e.g. it needs a password).
     */
    suspend fun page(index: Int): PdfPageText {
        pages[index]?.let { return it }
        return withContext(worker) {
            pages[index]?.let { return@withContext it }
            if (closed) return@withContext PdfPageText.empty()
            val own = runCatching { reader()?.page(index) }
                .onFailure { Log.w(TAG, "Couldn't read the text of page $index", it) }
                .getOrNull()
            var text = own
            if (own == null || own.letterCount < MIN_OWN_TEXT) {
                val read = try {
                    recognize(index, own)
                } catch (e: Throwable) {
                    // Errors too: running out of memory on a huge page mustn't take the viewer down.
                    Log.w(TAG, "Text recognition failed on page $index", e)
                    null
                }
                if (read != null && read.letterCount > (own?.letterCount ?: 0)) text = read
            }
            (text ?: PdfPageText.empty()).also { pages[index] = it }
        }
    }

    override fun close() {
        closed = true
        // On the worker, after whatever it's doing: nothing is closed while it's in use.
        CoroutineScope(worker).launch {
            runCatching { reader?.close() }
            runCatching { renderer?.close() }
            runCatching { recognizer?.close() }
            reader = null
            renderer = null
            recognizer = null
            dir.deleteRecursively()
        }
    }

    private fun file(): File? {
        copy?.let { return it }
        if (unreadable) return null
        return runCatching {
            dir.mkdirs()
            File(dir, "document.pdf").also { file ->
                app.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            }
        }.onFailure { unreadable = true }.getOrNull()?.also { copy = it }
    }

    private fun reader(): PdfTextReader? {
        reader?.let { return it }
        val file = file() ?: return null
        return runCatching { PdfTextReader(file) }.getOrNull()?.also { reader = it }
    }

    /** Reads the text on page [index]'s image. [own] is the page's own text, for its size. */
    private fun recognize(index: Int, own: PdfPageText?): PdfPageText? {
        val file = file() ?: return null
        val renderer = renderer ?: PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).also { renderer = it }
        if (index >= renderer.pageCount) return null
        val (bitmap, pageW, pageH) = renderer.openPage(index).use { page ->
            val scale = min(RENDER_SIDE.toDouble() / max(page.width, page.height), sqrt(MAX_PIXELS / (page.width.toDouble() * page.height)))
            val bitmap = Bitmap.createBitmap(
                (page.width * scale).toInt().coerceAtLeast(1),
                (page.height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            Triple(bitmap, page.width.toFloat(), page.height.toFloat())
        }
        try {
            if (closed) return null
            val reader = recognizer ?: TextRecognizer.load(app).also { recognizer = it }
            return reader.recognize(bitmap).toPageText(own?.width ?: pageW, own?.height ?: pageH)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val TAG = "PdfTextSource"

        /** Pages with fewer letters and digits than this get read from their image (as Dock's own OCR decides). */
        const val MIN_OWN_TEXT = 20

        /** Long side of a page image for reading: about 220 dpi on A4, plenty for body text. */
        const val RENDER_SIDE = 2560

        /** Caps odd shapes (long receipts, posters) at 12 MP. */
        const val MAX_PIXELS = 12_000_000.0
    }
}

/**
 * Recognized words as page text: each word's box (turned by its slant, if any) as fractions of
 * the image, its characters shared out evenly across it.
 */
internal fun OcrPage.toPageText(pageWidth: Float, pageHeight: Float): PdfPageText {
    val builder = PdfPageText.Builder(pageWidth, pageHeight)
    val w = width.toFloat()
    val h = height.toFloat()
    if (w <= 0f || h <= 0f) return builder.build()
    for (line in lines) {
        for (word in line.words) {
            // From the bottom-left corner: along the text by its width, and up (against the
            // image's downward y) by its height.
            val alongX = word.cos * word.width
            val alongY = word.sin * word.width
            val upX = word.sin * word.height
            val upY = -word.cos * word.height
            val xs = floatArrayOf(word.x, word.x + alongX, word.x + alongX + upX, word.x + upX)
            val ys = floatArrayOf(word.y, word.y + alongY, word.y + alongY + upY, word.y + upY)
            builder.spread(word.text, xs.min() / w, ys.min() / h, xs.max() / w, ys.max() / h)
            builder.space()
        }
        builder.lineBreak()
    }
    return builder.build()
}
