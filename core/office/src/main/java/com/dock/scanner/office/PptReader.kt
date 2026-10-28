package com.dock.scanner.office

/**
 * Reads PowerPoint 97-2003 presentations (.ppt): each slide's text boxes, filled shapes and
 * pictures, where they sit on the slide. The current version of every slide is found
 * through the persist directory, since saving appends edits instead of rewriting the file.
 * Text formatting (fonts, sizes, colors) isn't read; titles and body text get sensible defaults.
 */
internal class PptReader(private val cfb: Cfb) {

    private lateinit var doc: Bytes
    private val persist = HashMap<Int, Int>()
    private val images = HashMap<String, ByteArray>()
    private val blips = ArrayList<String?>() // BLIP store, 1-based in shapes

    /** The master's color scheme: background, text, shadow, title, fill, then three accents. */
    private var scheme: IntArray? = null

    private class Record(val type: Int, val instance: Int, val version: Int, val body: Int, val length: Int) {
        val end get() = body + length
        val isContainer get() = version == 0xF
    }

    private fun record(at: Int): Record? {
        if (at < 0 || at + 8 > doc.size) return null
        val verInst = doc.u16(at)
        val len = doc.i32(at + 4)
        if (len < 0 || at + 8 + len > doc.size) return null
        return Record(doc.u16(at + 2), verInst shr 4, verInst and 0xF, at + 8, len)
    }

    private fun children(parent: Record): List<Record> {
        val out = ArrayList<Record>()
        var at = parent.body
        while (at + 8 <= parent.end) {
            val r = record(at) ?: break
            out += r
            at = r.end
        }
        return out
    }

    private fun find(parent: Record, type: Int): Record? = children(parent).firstOrNull { it.type == type }

    fun read(): Presentation {
        doc = Bytes(cfb.stream("PowerPoint Document") ?: throw UnreadableDocumentException("This isn't a PowerPoint file"))
        if (cfb.has("EncryptedSummary")) throw UnreadableDocumentException("This presentation is password-protected")
        val user = Bytes(cfb.stream("Current User") ?: throw UnreadableDocumentException("This PowerPoint file is damaged"))
        var editAt = user.i32(16)
        val documentRef = record(editAt)?.takeIf { it.type == USER_EDIT }?.let { doc.i32(it.body + 16) }
            ?: throw UnreadableDocumentException("This PowerPoint file is damaged")
        // Newer edits come first and win; older ones fill in what they don't mention.
        var guard = 0
        while (editAt > 0 && guard++ < 1000) {
            val edit = record(editAt)?.takeIf { it.type == USER_EDIT } ?: break
            readPersistDirectory(doc.i32(edit.body + 12))
            editAt = doc.i32(edit.body + 8)
        }

        val document = persist[documentRef]?.let(::record)?.takeIf { it.type == DOCUMENT }
            ?: throw UnreadableDocumentException("This PowerPoint file is damaged")
        val atom = find(document, DOCUMENT_ATOM)
        val width = atom?.let { doc.i32(it.body) } ?: 5760
        val height = atom?.let { doc.i32(it.body + 4) } ?: 4320
        readPictures(document)
        scheme = children(document).firstOrNull { it.type == SLIDE_LIST && it.instance == 1 }
            ?.let { masters -> children(masters).firstOrNull { it.type == SLIDE_PERSIST } }
            ?.let { persist[doc.i32(it.body)] }?.let(::record)
            ?.let { master -> children(master).firstOrNull { it.type == COLOR_SCHEME && it.instance == 1 } }
            ?.let { atom -> IntArray(8) { k -> doc.i32(atom.body + k * 4).let { v -> Colors.rgb(v and 0xFF, (v shr 8) and 0xFF, (v shr 16) and 0xFF) } } }

        val slides = ArrayList<Slide>()
        val list = children(document).firstOrNull { it.type == SLIDE_LIST && it.instance == 0 }
        if (list != null) {
            var persistRef: Int? = null
            var texts = ArrayList<Pair<Int, String>>()
            fun flush() {
                val ref = persistRef ?: return
                slides += readSlide(ref, texts, width, height)
            }
            var textType = 4
            for (r in children(list)) {
                when (r.type) {
                    SLIDE_PERSIST -> {
                        flush()
                        persistRef = doc.i32(r.body)
                        texts = ArrayList()
                    }
                    TEXT_HEADER -> textType = doc.i32(r.body)
                    TEXT_CHARS -> texts += textType to String(doc.data, r.body, r.length, Charsets.UTF_16LE)
                    TEXT_BYTES -> texts += textType to String(doc.data, r.body, r.length, Charsets.ISO_8859_1)
                }
            }
            flush()
        }
        if (slides.isEmpty()) throw UnreadableDocumentException("This presentation has no slides")
        return Presentation(width * EMU, height * EMU, slides, MemoryImages(images))
    }

    private fun readPersistDirectory(at: Int) {
        val r = record(at)?.takeIf { it.type == PERSIST_DIRECTORY } ?: return
        var p = r.body
        while (p + 4 <= r.end) {
            val info = doc.i32(p)
            val first = info and 0xFFFFF
            val count = info ushr 20
            p += 4
            for (k in 0 until count) {
                if (p + 4 > r.end) break
                persist.putIfAbsent(first + k, doc.i32(p))
                p += 4
            }
        }
    }

    /** The picture store: BLIP entries point into the "Pictures" stream. */
    private fun readPictures(document: Record) {
        val pictures = cfb.stream("Pictures")?.let(::Bytes)
        val group = find(document, DRAWING_GROUP) ?: return
        val dgg = find(group, 0xF000) ?: return
        val store = find(dgg, 0xF001) ?: return
        for (fbse in children(store)) {
            if (fbse.type != 0xF007) {
                blips += null
                continue
            }
            val offset = doc.i32(fbse.body + 28)
            blips += pictures?.let { blip(it, offset, blips.size + 1) }
        }
    }

    private fun blip(stream: Bytes, at: Int, index: Int): String? {
        if (at < 0 || at + 8 > stream.size) return null
        val inst = stream.u16(at) shr 4
        val type = stream.u16(at + 2)
        val len = stream.i32(at + 4)
        val ext = when (type) {
            0xF01E -> "png"
            0xF01D, 0xF02A -> "jpg"
            else -> return null // EMF, WMF, PICT, DIB and TIFF can't be shown
        }
        val skip = 16 + (if (inst and 1 == 1) 16 else 0) + 1
        val start = at + 8 + skip
        val end = (at + 8 + len).coerceAtMost(stream.size)
        if (start >= end) return null
        val ref = "blip$index.$ext"
        images[ref] = stream.data.copyOfRange(start, end)
        return ref
    }

    private fun readSlide(persistRef: Int, outline: List<Pair<Int, String>>, width: Int, height: Int): Slide {
        val elements = ArrayList<SlideElement>()
        val used = HashSet<Int>()
        val container = persist[persistRef]?.let(::record)?.takeIf { it.type == SLIDE }
        val drawing = container?.let { find(it, PP_DRAWING) }?.let { find(it, 0xF002) }
        drawing?.let { dg -> find(dg, 0xF003)?.let { readShapes(it, outline, used, elements) } }

        // Outline text that no shape placed: lay it out as a plain title-and-body slide.
        val loose = outline.withIndex().filter { it.index !in used && it.value.first != 2 }
        if (loose.isNotEmpty()) {
            val titleY = height / 12
            var bodyY = height / 4
            for ((_, entry) in loose) {
                val (type, text) = entry
                val title = type == 0 || type == 6
                val frame = if (title) Frame(width / 12L * EMU, titleY.toLong() * EMU, width * 10 / 12L * EMU, height / 6L * EMU)
                else Frame(width / 12L * EMU, bodyY.toLong() * EMU, width * 10 / 12L * EMU, height * 2 / 3L * EMU).also { bodyY += height / 3 }
                elements += ShapeElement(frame, text = textFrame(type, text))
            }
        }
        return Slide(Colors.WHITE, null, elements)
    }

    private fun readShapes(group: Record, outline: List<Pair<Int, String>>, used: MutableSet<Int>, out: MutableList<SlideElement>) {
        for (sp in children(group)) {
            when (sp.type) {
                0xF003 -> readShapes(sp, outline, used, out)
                0xF004 -> readShape(sp, outline, used)?.let(out::add)
            }
        }
    }

    private fun readShape(sp: Record, outline: List<Pair<Int, String>>, used: MutableSet<Int>): SlideElement? {
        val parts = children(sp)
        val fsp = parts.firstOrNull { it.type == 0xF00A } ?: return null
        val flags = doc.i32(fsp.body + 4)
        if (flags and 0x40D != 0) return null // a group, the patriarch, a deleted shape or the background
        val anchor = parts.firstOrNull { it.type == 0xF010 } ?: return null
        val frame = if (anchor.length >= 16) {
            val top = doc.i32(anchor.body)
            val left = doc.i32(anchor.body + 4)
            Frame(left * EMU, top * EMU, (doc.i32(anchor.body + 8) - left) * EMU, (doc.i32(anchor.body + 12) - top) * EMU)
        } else {
            val top = doc.i16(anchor.body)
            val left = doc.i16(anchor.body + 2)
            Frame(left * EMU, top * EMU, (doc.i16(anchor.body + 4) - left) * EMU, (doc.i16(anchor.body + 6) - top) * EMU)
        }
        val props = parts.firstOrNull { it.type == 0xF00B }?.let(::properties).orEmpty()

        props[PIB]?.let { pib -> blips.getOrNull(pib - 1)?.let { return PictureElement(frame, it) } }

        // Text: either in the shape, or a reference to the slide's outline text. Shapes other
        // than text boxes center their text, as PowerPoint does.
        val inShape = fsp.instance != 202 && fsp.instance != 0
        var text: TextFrame? = null
        parts.firstOrNull { it.type == 0xF00D }?.let { box ->
            var type = 4
            for (r in children(box)) {
                when (r.type) {
                    TEXT_HEADER -> type = doc.i32(r.body)
                    TEXT_CHARS -> text = textFrame(type, String(doc.data, r.body, r.length, Charsets.UTF_16LE), inShape)
                    TEXT_BYTES -> text = textFrame(type, String(doc.data, r.body, r.length, Charsets.ISO_8859_1), inShape)
                    OUTLINE_REF -> {
                        val index = doc.i32(r.body)
                        outline.getOrNull(index)?.let { (t, s) ->
                            used += index
                            text = textFrame(t, s, inShape)
                        }
                    }
                }
            }
        }
        val shapeType = fsp.instance
        val filled = props[FILL_FLAGS]?.let { it and 0x10 != 0 || it and 0x100000 == 0 } ?: (shapeType != 202 && shapeType != 0)
        val fill = if (filled) props[FILL_COLOR]?.let(::colorRef) else null
        val lined = props[LINE_FLAGS]?.let { it and 0x08 != 0 } ?: false
        val line = if (lined) props[LINE_COLOR]?.let(::colorRef)?.let { Outline(it, 12_700) } else null
        val geometry = when (shapeType) {
            3 -> Geometry.Ellipse
            2 -> Geometry.RoundRect
            5, 6 -> Geometry.Triangle
            4 -> Geometry.Diamond
            20, 32 -> Geometry.Line
            1, 202, 0 -> Geometry.Rect
            else -> Geometry.Other
        }
        if (fill == null && line == null && text == null) return null
        return ShapeElement(frame, geometry, fill, line, text)
    }

    /** Simple (non-complex) shape properties by id. */
    private fun properties(fopt: Record): Map<Int, Int> {
        val map = HashMap<Int, Int>()
        for (k in 0 until fopt.instance) {
            val at = fopt.body + k * 6
            if (at + 6 > fopt.end) break
            map[doc.u16(at) and 0x3FFF] = doc.i32(at + 2)
        }
        return map
    }

    /** OfficeArt colors are RGB bytes in that order, or an index into the color scheme. */
    private fun colorRef(v: Int): Int? {
        val flags = v ushr 24
        if (flags and 0x08 != 0) return scheme?.getOrNull(v and 0xFF)
        if (flags and 0x10 != 0) return null // a system color index
        return Colors.rgb(v and 0xFF, (v shr 8) and 0xFF, (v shr 16) and 0xFF)
    }

    private fun textFrame(type: Int, text: String, inShape: Boolean = false): TextFrame {
        val title = type == 0 || type == 6
        val body = type == 1 || type == 7 || type == 8
        val centered = type == 5 || type == 6 || (inShape && type == 4)
        val style = TextStyle(size = if (title) 36f else if (body || type == 5) 24f else 18f, bold = false, color = Colors.BLACK)
        val paragraphs = text.split('\r').map { line ->
            SlideParagraph(
                spans = listOf(TextSpan(line.replace('\u000B', '\n'), style)),
                align = if (centered) Align.Center else Align.Start,
                bullet = if (body && line.isNotBlank()) "•" else null,
                marginLeft = if (body) 342_900 else 0,
                indent = if (body) -342_900 else 0,
                spaceBefore = if (body) 10f else 0f,
                emptySize = style.size,
            )
        }
        return TextFrame(paragraphs, anchor = if (title || (inShape && type == 4)) Anchor.Middle else Anchor.Top)
    }

    companion object {
        /** Master units (1/576 inch) to EMU. */
        private const val EMU = 1587L

        private const val DOCUMENT = 0x03E8
        private const val DOCUMENT_ATOM = 0x03E9
        private const val SLIDE = 0x03EE
        private const val SLIDE_PERSIST = 0x03F3
        private const val PP_DRAWING = 0x040C
        private const val DRAWING_GROUP = 0x040B
        private const val SLIDE_LIST = 0x0FF0
        private const val USER_EDIT = 0x0FF5
        private const val PERSIST_DIRECTORY = 0x1772
        private const val TEXT_HEADER = 0x0F9F
        private const val TEXT_CHARS = 0x0FA0
        private const val TEXT_BYTES = 0x0FA8
        private const val OUTLINE_REF = 0x0F9E
        private const val COLOR_SCHEME = 0x07F0

        private const val PIB = 0x0104
        private const val FILL_COLOR = 0x0181
        private const val FILL_FLAGS = 0x01BF
        private const val LINE_COLOR = 0x01C0
        private const val LINE_FLAGS = 0x01FF
    }
}
