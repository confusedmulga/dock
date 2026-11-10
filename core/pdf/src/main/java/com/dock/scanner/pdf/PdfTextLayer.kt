package com.dock.scanner.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.util.Locale

/**
 * Dock's searchable text: recognized words written over a page as invisible text, the way
 * OCRmyPDF and Tesseract do it. Any PDF app can then search, select and copy the page's text,
 * while the page looks exactly as before.
 *
 * Everything Dock adds is marked, so the layer can be taken out again without touching the
 * rest of the page. Whether the user wants the layer is remembered in the PDF itself, in its
 * document information, so it survives renames, moves and copies.
 */
object PdfTextLayer {

    /** One word, positioned on an image of the whole page as Android's PdfRenderer draws it (pixels, y down). */
    class Word(
        val text: String,
        /** Bottom-left corner. */
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        /** Direction the text runs in (cos and sin of its angle; 1 and 0 for level text). */
        val cos: Float,
        val sin: Float,
    )

    /** The words of one page, line by line in reading order, on a [imageWidth] x [imageHeight] page image. */
    class PageText(val imageWidth: Int, val imageHeight: Int, val lines: List<List<Word>>)

    /**
     * @param madeByDock scanned or edited in Dock (rather than imported from elsewhere).
     * @param hasLayer Dock's text layer is in the file.
     * @param choice what the user picked for this PDF, or null if they never did.
     */
    data class Info(val madeByDock: Boolean, val hasLayer: Boolean, val choice: Boolean?)

    /** Marks Dock's content streams, so they can be told apart from the page's own. */
    private val MARK = COSName.getPDFName("DockOCR")

    /** The invisible font's name in page resources. */
    private val FONT = COSName.getPDFName("DockOCR")

    /** Document information key holding the user's choice: "on" or "off". */
    const val INFO_KEY = "DockOCR"

    /** What JpegPdfWriter puts in the files Dock writes. */
    private const val DOCK_PRODUCER = "Dock"

    /** Pages with fewer letters and digits than this count as having no text of their own (stray page numbers, stamps). */
    private const val MIN_OWN_TEXT = 20

    /** The full path, because R8 renames this class: a path relative to it fails in release builds. */
    internal const val GLYPHLESS_FONT = "com/dock/scanner/pdf/glyphless.ttf"

    fun info(doc: PDDocument): Info = Info(
        madeByDock = doc.documentInformation.producer == DOCK_PRODUCER,
        hasLayer = doc.pages.any { page -> markedStreams(page).isNotEmpty() },
        choice = choice(doc),
    )

    fun choice(doc: PDDocument): Boolean? = when (doc.documentInformation.getCustomMetadataValue(INFO_KEY)) {
        "on" -> true
        "off" -> false
        else -> null
    }

    fun setChoice(doc: PDDocument, on: Boolean) =
        doc.documentInformation.setCustomMetadataValue(INFO_KEY, if (on) "on" else "off")

    /**
     * 0-based indexes of the pages worth recognizing: every page of a Dock scan, and the pages
     * of other PDFs that don't already have text of their own. Takes Dock's layer out of [doc]
     * first, so it isn't mistaken for the page's own text.
     */
    fun pagesWithoutText(doc: PDDocument): List<Int> {
        remove(doc)
        val pages = 0 until doc.numberOfPages
        if (info(doc).madeByDock) return pages.toList()
        val stripper = PDFTextStripper()
        return pages.filter { index ->
            stripper.startPage = index + 1
            stripper.endPage = index + 1
            val text = runCatching { stripper.getText(doc) }.getOrDefault("")
            text.count { it.isLetterOrDigit() } < MIN_OWN_TEXT
        }
    }

    /** Replaces any previous layer with [pages] (by 0-based index) and records the choice as on. */
    fun apply(doc: PDDocument, pages: Map<Int, PageText>) {
        remove(doc)
        val font = lazy { glyphlessFont(doc) }
        for ((index, text) in pages) {
            if (text.lines.all { it.isEmpty() }) continue
            val page = doc.getPage(index)
            val resources = page.resources ?: PDResources().also { page.resources = it }
            val fonts = resources.cosObject.getDictionaryObject(COSName.FONT) as? COSDictionary
                ?: COSDictionary().also { resources.cosObject.setItem(COSName.FONT, it) }
            fonts.setItem(FONT, font.value)

            // Wrapped in q ... Q around the page's own content, so whatever state that leaves
            // behind can't shift the text.
            val contents = COSArray()
            contents.add(markedStream(doc, "q\n"))
            when (val existing = page.cosObject.getDictionaryObject(COSName.CONTENTS)) {
                is COSArray -> for (i in 0 until existing.size()) contents.add(existing.get(i))
                is COSStream -> contents.add(page.cosObject.getItem(COSName.CONTENTS))
                else -> Unit
            }
            contents.add(markedStream(doc, content(page, text)))
            page.cosObject.setItem(COSName.CONTENTS, contents)
        }
        setChoice(doc, true)
    }

    /** Takes Dock's layer out of every page. Returns whether there was one. */
    fun remove(doc: PDDocument): Boolean {
        var removed = false
        for (page in doc.pages) {
            val marked = markedStreams(page)
            if (marked.isEmpty()) continue
            removed = true
            val contents = page.cosObject.getDictionaryObject(COSName.CONTENTS) as COSArray
            val kept = COSArray()
            for (i in 0 until contents.size()) {
                if (i !in marked) kept.add(contents.get(i))
            }
            page.cosObject.setItem(COSName.CONTENTS, kept)
            (page.resources?.cosObject?.getDictionaryObject(COSName.FONT) as? COSDictionary)?.removeItem(FONT)
        }
        return removed
    }

    private fun markedStreams(page: PDPage): Set<Int> {
        val contents = page.cosObject.getDictionaryObject(COSName.CONTENTS) as? COSArray ?: return emptySet()
        return (0 until contents.size()).filterTo(HashSet()) { i ->
            (contents.getObject(i) as? COSStream)?.getBoolean(MARK, false) == true
        }
    }

    private fun markedStream(doc: PDDocument, content: String): COSStream =
        doc.document.createCOSStream().apply {
            setBoolean(MARK, true)
            createOutputStream(COSName.FLATE_DECODE).use { it.write(content.toByteArray(Charsets.US_ASCII)) }
        }

    /**
     * The words as invisible text (render mode 3). The image's pixel grid is mapped onto the
     * page's crop box, turned by the page's rotation the way PdfRenderer shows it, so each word
     * lands where it was seen. Each word is stretched (Tz) to the width it had on the page.
     */
    private fun content(page: PDPage, text: PageText): String {
        val box = page.cropBox
        val w = box.width
        val h = box.height
        val iw = text.imageWidth.toFloat()
        val ih = text.imageHeight.toFloat()
        // Image pixel (x, y) to page point: (m[0] x + m[2] y + m[4], m[1] x + m[3] y + m[5]).
        val m = when (Math.floorMod(page.rotation, 360)) {
            90 -> floatArrayOf(0f, h / iw, w / ih, 0f, box.lowerLeftX, box.lowerLeftY)
            180 -> floatArrayOf(-w / iw, 0f, 0f, h / ih, box.upperRightX, box.lowerLeftY)
            270 -> floatArrayOf(0f, -h / iw, -w / ih, 0f, box.upperRightX, box.upperRightY)
            else -> floatArrayOf(w / iw, 0f, 0f, -h / ih, box.lowerLeftX, box.upperRightY)
        }
        val out = StringBuilder("Q\nq\nBT\n3 Tr\n/").append(FONT.name).append(" 1 Tf\n")
        for (line in text.lines) {
            line.forEachIndexed { i, word ->
                if (word.text.isEmpty() || word.height <= 0f || word.width <= 0f) return@forEachIndexed
                val size = word.height
                // The text matrix in page space, so text extractors see ordinary upright text:
                // the word's direction and its "up" (against the image's downward y), mapped onto the page.
                val alongX = size * word.cos
                val alongY = size * word.sin
                val upX = size * word.sin
                val upY = -size * word.cos
                out.append(num(m[0] * alongX + m[2] * alongY)).append(' ').append(num(m[1] * alongX + m[3] * alongY)).append(' ')
                    .append(num(m[0] * upX + m[2] * upY)).append(' ').append(num(m[1] * upX + m[3] * upY)).append(' ')
                    .append(num(m[0] * word.x + m[2] * word.y + m[4])).append(' ')
                    .append(num(m[1] * word.x + m[3] * word.y + m[5])).append(" Tm\n")
                // Every glyph is half an em wide.
                out.append(num(100f * word.width / (word.text.length * 0.5f * size))).append(" Tz\n")
                // A trailing space keeps words apart when the text is copied.
                val shown = if (i < line.lastIndex) word.text + " " else word.text
                out.append('<').append(hex(shown)).append("> Tj\n")
            }
        }
        out.append("ET\nQ\n")
        return out.toString()
    }

    /** Character codes are the text's UTF-16 units; the font's ToUnicode map reads them back as they are. */
    private fun hex(text: String): String = buildString(text.length * 4) {
        for (ch in text) append(String.format(Locale.US, "%04X", ch.code))
    }

    private fun num(value: Float): String =
        String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }

    /**
     * A Type 0 font that draws nothing: every character maps to one empty glyph half an em
     * wide. It only exists so viewers can place, select and copy the text.
     */
    private fun glyphlessFont(doc: PDDocument): COSDictionary {
        val ttf = (PdfTextLayer::class.java.classLoader?.getResourceAsStream(GLYPHLESS_FONT) ?: error("$GLYPHLESS_FONT is missing"))
            .use { it.readBytes() }
        val fontFile = doc.document.createCOSStream().apply {
            setInt(COSName.LENGTH1, ttf.size)
            createOutputStream(COSName.FLATE_DECODE).use { it.write(ttf) }
        }
        val descriptor = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.FONT_DESC)
            setName(COSName.FONT_NAME, "GlyphLessFont")
            setInt(COSName.FLAGS, 5)
            setItem(COSName.FONT_BBOX, ints(0, 0, 500, 1000))
            setInt(COSName.ITALIC_ANGLE, 0)
            setInt(COSName.ASCENT, 1000)
            setInt(COSName.DESCENT, 0)
            setInt(COSName.CAP_HEIGHT, 1000)
            setInt(COSName.STEM_V, 80)
            setItem(COSName.FONT_FILE2, fontFile)
        }
        val cidToGid = doc.document.createCOSStream().apply {
            createOutputStream(COSName.FLATE_DECODE).use { out ->
                out.write(ByteArray(65536 * 2) { if (it % 2 == 1) 1 else 0 })
            }
        }
        val cidFont = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.FONT)
            setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE2)
            setName(COSName.BASE_FONT, "GlyphLessFont")
            setItem(COSName.CIDSYSTEMINFO, COSDictionary().apply {
                setString(COSName.REGISTRY, "Adobe")
                setString(COSName.ORDERING, "Identity")
                setInt(COSName.SUPPLEMENT, 0)
            })
            setItem(COSName.FONT_DESC, descriptor)
            setInt(COSName.DW, 500)
            setItem(COSName.CID_TO_GID_MAP, cidToGid)
        }
        val toUnicode = doc.document.createCOSStream().apply {
            createOutputStream(COSName.FLATE_DECODE).use { it.write(IDENTITY_TO_UNICODE.toByteArray(Charsets.US_ASCII)) }
        }
        return COSDictionary().apply {
            setItem(COSName.TYPE, COSName.FONT)
            setItem(COSName.SUBTYPE, COSName.TYPE0)
            setName(COSName.BASE_FONT, "GlyphLessFont")
            setItem(COSName.ENCODING, COSName.IDENTITY_H)
            setItem(COSName.DESCENDANT_FONTS, COSArray().apply { add(cidFont) })
            setItem(COSName.TO_UNICODE, toUnicode)
        }
    }

    private fun ints(vararg values: Int) = COSArray().apply { values.forEach { add(COSInteger.get(it.toLong())) } }

    private val IDENTITY_TO_UNICODE = """
        /CIDInit /ProcSet findresource begin
        12 dict begin
        begincmap
        /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
        /CMapName /Adobe-Identity-UCS def
        /CMapType 2 def
        1 begincodespacerange
        <0000> <FFFF>
        endcodespacerange
        1 beginbfrange
        <0000> <FFFF> <0000>
        endbfrange
        endcmap
        CMapName currentdict /CMap defineresource pop
        end
        end
    """.trimIndent() + "\n"
}
