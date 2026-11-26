package com.dock.scanner.data.tools

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContract
import com.dock.scanner.pdf.PasswordProtectedException
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** A PDF chosen as input for a tool. */
data class SourcePdf(
    val uri: Uri,
    val name: String,
    val size: Long,
    val pages: Int,
    /** Library folder it lives in, when it came from the library; outputs go next to it. */
    val parentId: String? = null,
)

object PdfSources {

    /** Display name (without .pdf), size and page count of a picked PDF. */
    fun describe(resolver: ContentResolver, uri: Uri, parentId: String? = null): SourcePdf {
        var name = "Document"
        var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val pages = withRenderer(resolver, uri) { it.pageCount }
        return SourcePdf(uri, name.substringBeforeLast('.', name), size, pages, parentId)
    }

    /** Copies a picked file into [dir] (a scratch folder in the cache), for libraries that need a real file. */
    fun copyToCache(context: Context, uri: Uri, dir: File, name: String): File {
        val file = File(dir.apply { mkdirs() }, name)
        context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    fun <T> withRenderer(resolver: ContentResolver, uri: Uri, block: (PdfRenderer) -> T): T {
        val fd = resolver.openFileDescriptor(uri, "r") ?: error("Can't open this PDF")
        val renderer = try {
            PdfRenderer(fd)
        } catch (e: Exception) {
            // Not opened (damaged, not a PDF, or locked): the file is still ours to close.
            runCatching { fd.close() }
            throw if (e is SecurityException) PasswordProtectedException() else e
        }
        return renderer.use(block)
    }

    /**
     * Renders one page on white at [dpi], capped at [maxSide] pixels on the long side.
     * Returns the bitmap and the page's size in points.
     */
    fun render(renderer: PdfRenderer, index: Int, dpi: Int, maxSide: Int = 4000): Pair<Bitmap, Pair<Float, Float>> =
        renderer.openPage(index).use { page ->
            val ptW = page.width.toFloat()
            val ptH = page.height.toFloat()
            var scale = dpi / 72f
            if (max(ptW, ptH) * scale > maxSide) scale = maxSide / max(ptW, ptH)
            val bitmap = Bitmap.createBitmap((ptW * scale).roundToInt().coerceAtLeast(1), (ptH * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            bitmap to (ptW to ptH)
        }
}

/** System file picker for PDFs that opens in the user's Dock folder. */
class PickPdfs(private val initialUri: () -> Uri?, private val multiple: Boolean) : ActivityResultContract<Unit, List<Uri>>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            .apply { initialUri()?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) } }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val clip = intent.clipData
        return if (clip != null) List(clip.itemCount) { clip.getItemAt(it).uri } else listOfNotNull(intent.data)
    }
}
