package com.dock.scanner.office

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * Reads Excel workbooks (.xlsx, .xlsm). Sheet data is streamed rather than built into a
 * tree, so large sheets fit in memory; very large ones are cut at [MAX_ROWS] x [MAX_COLUMNS].
 */
internal class XlsxReader(private val pkg: ZipPackage) {

    private var theme = Theme.Default
    private var date1904 = false
    private var sharedStrings: List<String> = emptyList()
    private var styles: List<CellXf> = emptyList()

    private class CellXf(val format: String, val style: CellStyle)

    fun read(): Workbook {
        val bookPath = pkg.relationships("").values.firstOrNull { it.type.endsWith("/officeDocument") }?.target
            ?: "xl/workbook.xml".takeIf(pkg::has)
            ?: throw UnreadableDocumentException("This isn't an Excel workbook")
        val rels = pkg.relationships(bookPath)
        fun related(suffix: String) = rels.values.firstOrNull { it.type.endsWith(suffix) }?.target
        theme = Theme.parse(related("/theme")?.let(pkg::xml))
        sharedStrings = related("/sharedStrings")?.let(::readSharedStrings).orEmpty()
        styles = related("/styles")?.let(pkg::xml)?.let(::readStyles).orEmpty()

        val book = pkg.xml(bookPath) ?: throw UnreadableDocumentException("This workbook is empty")
        date1904 = book.child("workbookPr")?.attr("date1904").let { it == "1" || it == "true" }
        val sheets = book.child("sheets")?.children("sheet").orEmpty()
            .filter { it.attr("state").let { s -> s != "hidden" && s != "veryHidden" } }
            .mapNotNull { s ->
                val target = s.attr("r:id")?.let { rels[it]?.target } ?: return@mapNotNull null
                if (!pkg.has(target)) return@mapNotNull null // chart sheets and the like
                readSheet(s.attr("name") ?: "Sheet", target)
            }
        if (sheets.isEmpty()) throw UnreadableDocumentException("This workbook has no sheets to show")
        return Workbook(sheets)
    }

    private fun readSharedStrings(path: String): List<String> {
        val strings = ArrayList<String>()
        val input = pkg.open(path) ?: return strings
        input.use {
            Xml.stream(it, object : DefaultHandler() {
                val sb = StringBuilder()
                var inText = false
                var phonetic = 0

                override fun startElement(uri: String?, localName: String, qName: String, attributes: Attributes) {
                    when (localName) {
                        "si" -> sb.clear()
                        "t" -> inText = phonetic == 0
                        "rPh" -> phonetic++
                    }
                }

                override fun endElement(uri: String?, localName: String, qName: String) {
                    when (localName) {
                        "si" -> strings += sb.toString()
                        "t" -> inText = false
                        "rPh" -> phonetic--
                    }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (inText) sb.appendRange(ch, start, start + length)
                }
            })
        }
        return strings
    }

    // region Styles

    private fun readStyles(root: XNode): List<CellXf> {
        val formats = HashMap<Int, String>(NumberFormat.BUILTIN)
        root.child("numFmts")?.children("numFmt")?.forEach { f ->
            val id = f.attr("numFmtId")?.toIntOrNull() ?: return@forEach
            f.attr("formatCode")?.let { formats[id] = it }
        }
        val fonts = root.child("fonts")?.children("font").orEmpty()
        val fills = root.child("fills")?.children("fill").orEmpty()
        val borders = root.child("borders")?.children("border").orEmpty()
        val baseSize = fonts.firstOrNull()?.child("sz")?.attr("val")?.toFloatOrNull() ?: 11f

        return root.child("cellXfs")?.children("xf").orEmpty().map { xf ->
            val font = xf.attr("fontId")?.toIntOrNull()?.let(fonts::getOrNull)
            val fill = xf.attr("fillId")?.toIntOrNull()?.let(fills::getOrNull)?.child("patternFill")
            val border = xf.attr("borderId")?.toIntOrNull()?.let(borders::getOrNull)
            val alignment = xf.child("alignment")
            fun flag(node: XNode?, name: String) = node?.child(name)?.let { it.attr("val").let { v -> v == null || v == "1" || v == "true" } } ?: false
            CellXf(
                format = xf.attr("numFmtId")?.toIntOrNull()?.let(formats::get) ?: "General",
                style = CellStyle(
                    bold = flag(font, "b"),
                    italic = flag(font, "i"),
                    underline = font?.child("u")?.let { it.attr("val") != "none" } ?: false,
                    strike = flag(font, "strike"),
                    size = font?.child("sz")?.attr("val")?.toFloatOrNull() ?: baseSize,
                    color = font?.child("color")?.let(::color)?.takeIf { it != Colors.BLACK },
                    fill = fill?.takeIf { it.attr("patternType").let { t -> t != null && t != "none" && t != "gray125" } }
                        ?.let { it.child("fgColor")?.let(::color) ?: it.child("bgColor")?.let(::color) },
                    align = when (alignment?.attr("horizontal")) {
                        "left" -> CellAlign.Left
                        "center", "centerContinuous" -> CellAlign.Center
                        "right" -> CellAlign.Right
                        else -> CellAlign.General
                    },
                    wrap = alignment?.attr("wrapText").let { it == "1" || it == "true" },
                    border = border?.children?.any { side -> side.attr("style").let { it != null && it != "none" } } ?: false,
                ),
            )
        }
    }

    private fun color(node: XNode): Int? {
        val base = node.attr("rgb")?.let(Colors::hex)
            ?: node.attr("theme")?.toIntOrNull()?.let(theme::indexed)
            ?: node.attr("indexed")?.toIntOrNull()?.let { Colors.INDEXED.getOrNull(it) }
            ?: return null
        val tint = node.attr("tint")?.toFloatOrNull() ?: 0f
        return Colors.excelTint(base, tint)
    }

    // endregion

    private fun readSheet(name: String, path: String): Sheet {
        val rows = HashMap<Int, HashMap<Int, Cell>>()
        val widths = HashMap<Int, Float>()
        val heights = HashMap<Int, Float>()
        val hiddenCols = HashSet<Int>()
        val hiddenRows = HashSet<Int>()
        val merges = ArrayList<CellRange>()
        var defaultWidth = 64f
        var defaultHeight = 20f
        var maxRow = -1
        var maxCol = -1
        var truncated = false

        val input = pkg.open(path) ?: return Sheet(name, 0, 0, emptyMap())
        input.use {
            Xml.stream(it, object : DefaultHandler() {
                var row = -1
                var col = -1
                var type: String? = null
                var styleIndex = 0
                val value = StringBuilder()
                var inValue = false
                var inInline = false

                override fun startElement(uri: String?, localName: String, qName: String, a: Attributes) {
                    when (localName) {
                        "sheetFormatPr" -> {
                            a.getValue("defaultColWidth")?.toFloatOrNull()?.let { w -> defaultWidth = charsToPx(w) }
                                ?: a.getValue("baseColWidth")?.toFloatOrNull()?.let { w -> defaultWidth = charsToPx(w + 0.71f) }
                            a.getValue("defaultRowHeight")?.toFloatOrNull()?.let { h -> defaultHeight = h * 4 / 3 }
                        }
                        "col" -> {
                            val min = (a.getValue("min")?.toIntOrNull() ?: return) - 1
                            val max = ((a.getValue("max")?.toIntOrNull() ?: return) - 1).coerceAtMost(MAX_COLUMNS)
                            val w = a.getValue("width")?.toFloatOrNull()
                            val hidden = a.getValue("hidden").let { h -> h == "1" || h == "true" }
                            for (c in min..max) {
                                if (w != null) widths[c] = charsToPx(w)
                                if (hidden) hiddenCols += c
                            }
                        }
                        "row" -> {
                            row = (a.getValue("r")?.toIntOrNull() ?: (row + 2)) - 1
                            col = -1
                            if (row >= MAX_ROWS) truncated = true
                            a.getValue("ht")?.toFloatOrNull()?.let { h -> heights[row] = h * 4 / 3 }
                            if (a.getValue("hidden").let { h -> h == "1" || h == "true" }) hiddenRows += row
                        }
                        "c" -> {
                            val ref = a.getValue("r")
                            col = if (ref != null) columnIndex(ref) else col + 1
                            ref?.let { r -> rowIndex(r)?.let { ri -> row = ri } }
                            type = a.getValue("t")
                            styleIndex = a.getValue("s")?.toIntOrNull() ?: 0
                            value.clear()
                        }
                        "v" -> inValue = true
                        "is" -> inInline = true
                        "t" -> if (inInline) inValue = true
                        "mergeCell" -> a.getValue("ref")?.let(::range)?.let { m -> merges += m }
                    }
                }

                override fun endElement(uri: String?, localName: String, qName: String) {
                    when (localName) {
                        "v" -> inValue = false
                        "t" -> if (inInline) inValue = false
                        "is" -> inInline = false
                        "c" -> {
                            if (row !in 0 until MAX_ROWS || col !in 0 until MAX_COLUMNS) {
                                if (value.isNotEmpty()) truncated = true
                                return
                            }
                            val cell = cell(type, value.toString(), styleIndex) ?: return
                            rows.getOrPut(row) { HashMap() }[col] = cell
                            if (row > maxRow) maxRow = row
                            if (col > maxCol) maxCol = col
                        }
                    }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (inValue) value.appendRange(ch, start, start + length)
                }
            })
        }
        for (m in merges) {
            maxRow = maxOf(maxRow, m.lastRow.coerceAtMost(MAX_ROWS - 1))
            maxCol = maxOf(maxCol, m.lastColumn.coerceAtMost(MAX_COLUMNS - 1))
        }
        return Sheet(
            name = name,
            rowCount = maxRow + 1,
            columnCount = maxCol + 1,
            rows = rows,
            columnWidths = widths,
            defaultColumnWidth = defaultWidth,
            rowHeights = heights,
            defaultRowHeight = defaultHeight,
            merges = merges,
            hiddenColumns = hiddenCols,
            hiddenRows = hiddenRows,
            truncated = truncated,
        )
    }

    /** A cell's displayed text and style; null for a cell that's only formatting. */
    private fun cell(type: String?, raw: String, styleIndex: Int): Cell? {
        val xf = styles.getOrNull(styleIndex)
        val style = xf?.style ?: CellStyle.Default
        return when (type) {
            "s" -> raw.trim().toIntOrNull()?.let { sharedStrings.getOrNull(it) }?.let { Cell(it, style) }
            "inlineStr", "str" -> Cell(raw, style)
            "b" -> Cell(if (raw.trim() == "1") "TRUE" else "FALSE", style.copy(align = style.align.orCenter()))
            "e" -> Cell(raw, style.copy(align = style.align.orCenter()))
            "d" -> Cell(raw.replace('T', ' ').removeSuffix("Z"), style, isNumber = true)
            else -> {
                if (raw.isBlank()) return if (style.fill != null || style.border) Cell("", style) else null
                val number = raw.trim().toDoubleOrNull() ?: return Cell(raw, style)
                Cell(NumberFormat.format(number, xf?.format ?: "General", date1904), style, isNumber = true)
            }
        }
    }

    private fun CellAlign.orCenter() = if (this == CellAlign.General) CellAlign.Center else this

    companion object {
        const val MAX_ROWS = 20_000
        const val MAX_COLUMNS = 256

        /** Column width in characters of the default font to pixels, as Excel measures it. */
        fun charsToPx(chars: Float): Float = (chars * 7 + 5).coerceAtLeast(0f)

        /** "BC12" -> 54 (0-based column). */
        fun columnIndex(ref: String): Int {
            var n = 0
            for (ch in ref) {
                if (!ch.isLetter()) break
                n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
            }
            return n - 1
        }

        fun rowIndex(ref: String): Int? = ref.dropWhile { it.isLetter() }.toIntOrNull()?.minus(1)

        fun range(ref: String): CellRange? {
            val parts = ref.split(':')
            val a = parts[0]
            val b = parts.getOrElse(1) { a }
            return CellRange(
                rowIndex(a) ?: return null, columnIndex(a),
                rowIndex(b) ?: return null, columnIndex(b),
            )
        }

        /** 0 -> "A", 27 -> "AB". */
        fun columnName(index: Int): String {
            var n = index + 1
            val sb = StringBuilder()
            while (n > 0) {
                val r = (n - 1) % 26
                sb.append('A' + r)
                n = (n - 1) / 26
            }
            return sb.reverse().toString()
        }
    }
}
