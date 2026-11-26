package com.dock.scanner.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.Closeable
import java.io.File
import java.io.StringWriter
import kotlin.math.abs

/**
 * Reads where each character of a PDF's text is, page by page, for selecting and searching
 * it in a viewer. The text is whatever the PDF has: typed text, or the invisible text layer of
 * a scan that was made searchable (Dock's own, or any other app's).
 *
 * Keeps the PDF open until [close]. Not thread-safe; reading a page does disk I/O.
 */
class PdfTextReader(file: File) : Closeable {

    private val doc: PDDocument = PdfTools.openDocument(file)

    val pageCount: Int get() = doc.numberOfPages

    /** The text of the page at 0-based [index], with every character's box on the page as displayed. */
    fun page(index: Int): PdfPageText {
        val page = doc.getPage(index)
        val collector = Collector(page)
        collector.startPage = index + 1
        collector.endPage = index + 1
        collector.writeText(doc, StringWriter())
        return collector.result()
    }

    override fun close() = doc.close()

    /**
     * PdfBox's text extraction, which finds words and lines, with each word handed over along
     * with its characters' positions instead of being written out.
     */
    private class Collector(page: PDPage) : PDFTextStripper() {
        private val rotation = Math.floorMod(page.rotation, 360)
        private val box = page.cropBox
        /** The page as displayed: a quarter turn swaps its sides. */
        private val displayedWidth = if (rotation == 90 || rotation == 270) box.height else box.width
        private val displayedHeight = if (rotation == 90 || rotation == 270) box.width else box.height
        private val builder = PdfPageText.Builder(displayedWidth, displayedHeight)

        init {
            // The content's own order: what the PDF's author meant as reading order, which
            // sorting by position gets wrong on pages with columns.
            sortByPosition = false
        }

        fun result(): PdfPageText = builder.build()

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            if (displayedWidth <= 0f || displayedHeight <= 0f) return
            for (p in textPositions) {
                val unicode = p.unicode ?: continue
                // TextPosition's x and y already have the page's rotation applied and are
                // measured from the crop box's top left, with y at the baseline.
                val em = abs(p.textMatrix.scalingFactorY).takeIf { it > 0f } ?: (p.heightDir * 1.4f)
                val (ascent, descent) = metrics(p)
                val left = p.x / displayedWidth
                val right = (p.x + p.width) / displayedWidth
                val top = (p.y - ascent * em) / displayedHeight
                val bottom = (p.y - descent * em) / displayedHeight
                if (unicode.length == 1) builder.char(unicode[0], left, top, right, bottom)
                else builder.spread(unicode, left, top, right, bottom)
            }
        }

        override fun writeWordSeparator() = builder.space()

        override fun writeLineSeparator() = builder.lineBreak()

        /**
         * How far the font reaches above and below the baseline, in ems. Fonts' own numbers
         * where they're believable (Dock's invisible text font says exactly 1 and 0, the word's
         * height), else what most text fonts come close to.
         */
        private fun metrics(p: TextPosition): Pair<Float, Float> {
            val descriptor = runCatching { p.font?.fontDescriptor }.getOrNull()
            val ascent = descriptor?.ascent?.div(1000f)?.takeIf { it in 0.4f..1.2f } ?: DEFAULT_ASCENT
            val descent = descriptor?.descent?.div(1000f)?.takeIf { it in -0.6f..0f } ?: DEFAULT_DESCENT
            return ascent to descent
        }
    }

    private companion object {
        const val DEFAULT_ASCENT = 0.8f
        const val DEFAULT_DESCENT = -0.2f
    }
}
