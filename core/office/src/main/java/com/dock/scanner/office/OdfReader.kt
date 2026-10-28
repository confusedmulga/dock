package com.dock.scanner.office

/**
 * Reads OpenDocument files (.odt, .ods, .odp), as written by LibreOffice, Google Docs and
 * Microsoft Office. Styles are resolved through their parent chain; spreadsheet cells use
 * the display text the file stores, so numbers and dates look exactly as they were saved.
 */
internal class OdfReader(private val pkg: ZipPackage) {

    private class Props(
        val text: XNode?,
        val paragraph: XNode?,
        val cell: XNode?,
        val column: XNode?,
        val row: XNode?,
        val graphic: XNode?,
        val parent: String?,
        val family: String?,
        val listStyle: String?,
    )

    private val styles = HashMap<String, Props>()
    private val listStyles = HashMap<String, XNode>()
    private val fonts = HashMap<String, String>()
    private var pageLayouts = HashMap<String, XNode>()
    private var masterPages = HashMap<String, String>()

    fun read(): OfficeDocument {
        val content = pkg.xml("content.xml") ?: throw UnreadableDocumentException("This OpenDocument file is empty")
        pkg.xml("styles.xml")?.let { loadStyles(it) }
        loadStyles(content)
        val body = content.child("office:body") ?: throw UnreadableDocumentException("This OpenDocument file is empty")
        body.child("office:text")?.let { return readText(it) }
        body.child("office:spreadsheet")?.let { return readSpreadsheet(it) }
        body.child("office:presentation")?.let { return readPresentation(it) }
        throw UnreadableDocumentException("This kind of OpenDocument file can't be previewed")
    }

    // region Styles

    private fun loadStyles(root: XNode) {
        root.child("office:font-face-decls")?.children("style:font-face")?.forEach { f ->
            val name = f.attr("style:name") ?: return@forEach
            fonts[name] = f.attr("svg:font-family")?.trim('\'', '"') ?: name
        }
        for (section in listOf("office:styles", "office:automatic-styles", "office:master-styles")) {
            val container = root.child(section) ?: continue
            for (s in container.children) {
                when (s.name) {
                    "style:style", "style:default-style" -> {
                        val name = s.attr("style:name") ?: if (s.name == "style:default-style") "#default-" + s.attr("style:family") else null
                        name ?: continue
                        styles[name] = Props(
                            text = s.child("style:text-properties"),
                            paragraph = s.child("style:paragraph-properties"),
                            cell = s.child("style:table-cell-properties"),
                            column = s.child("style:table-column-properties"),
                            row = s.child("style:table-row-properties"),
                            graphic = s.child("style:graphic-properties"),
                            parent = s.attr("style:parent-style-name") ?: s.attr("style:family")?.let { "#default-$it" }?.takeIf { s.name != "style:default-style" },
                            family = s.attr("style:family"),
                            listStyle = s.attr("style:list-style-name"),
                        )
                    }
                    "text:list-style" -> s.attr("style:name")?.let { listStyles[it] = s }
                    "style:page-layout" -> s.attr("style:name")?.let { pageLayouts[it] = s }
                    "style:master-page" -> {
                        val name = s.attr("style:name") ?: continue
                        s.attr("style:page-layout-name")?.let { masterPages[name] = it }
                    }
                }
            }
        }
    }

    /** A style and its ancestors, nearest first. */
    private fun chain(name: String?): List<Props> {
        val out = ArrayList<Props>()
        var n = name
        while (n != null && out.size < 16) {
            val p = styles[n] ?: break
            out += p
            n = p.parent
        }
        return out
    }

    /** The first value of [attr] in a style chain's properties picked by [pick]. */
    private fun lookup(chain: List<Props>, pick: (Props) -> XNode?, attr: String): String? =
        chain.firstNotNullOfOrNull { pick(it)?.attr(attr) }

    private fun textStyle(chain: List<Props>, base: TextStyle = TextStyle(size = 12f)): TextStyle {
        fun t(attr: String) = lookup(chain, Props::text, attr)
        val sizeValue = t("fo:font-size")
        val size = when {
            sizeValue == null -> base.size
            sizeValue.endsWith("%") -> base.size * (sizeValue.dropLast(1).toFloatOrNull() ?: 100f) / 100f
            else -> length(sizeValue) ?: base.size
        }
        val position = t("style:text-position").orEmpty()
        val font = t("style:font-name")?.let { fonts[it] ?: it } ?: t("fo:font-family")
        return TextStyle(
            size = size,
            bold = t("fo:font-weight")?.let { it == "bold" || (it.toIntOrNull() ?: 400) >= 600 } ?: base.bold,
            italic = t("fo:font-style")?.let { it == "italic" || it == "oblique" } ?: base.italic,
            underline = t("style:text-underline-style")?.let { it != "none" } ?: base.underline,
            strike = t("style:text-line-through-style")?.let { it != "none" } ?: base.strike,
            color = t("fo:color")?.let(Colors::hex) ?: base.color,
            background = t("fo:background-color")?.takeIf { it != "transparent" }?.let(Colors::hex) ?: base.background,
            font = font?.let(::fontKind) ?: base.font,
            baseline = when {
                position.startsWith("super") || position.startsWith("33%") -> Baseline.Superscript
                position.startsWith("sub") || position.startsWith("-33%") -> Baseline.Subscript
                else -> base.baseline
            },
            allCaps = t("fo:text-transform")?.let { it == "uppercase" } ?: base.allCaps,
        )
    }

    private fun align(value: String?): Align? = when (value) {
        "center" -> Align.Center
        "end", "right" -> Align.End
        "justify" -> Align.Justify
        "start", "left" -> Align.Start
        else -> null
    }

    // endregion

    // region Text documents

    private inner class TextBuilder {
        val pages = mutableListOf(mutableListOf<Block>())
        fun add(b: Block) {
            pages.last() += b
        }

        fun newPage() {
            if (pages.last().any { !(it is Paragraph && it.isEmpty) }) pages += mutableListOf<Block>()
        }
    }

    private fun readText(text: XNode): TextDocument {
        val out = TextBuilder()
        readBlocks(text, out, topLevel = true, list = null)
        // Page size from the first master page's layout.
        val layout = masterPages.values.firstOrNull()?.let { pageLayouts[it] }?.child("style:page-layout-properties")
        return TextDocument(
            pageWidth = length(layout?.attr("fo:page-width")) ?: 612f,
            pageHeight = length(layout?.attr("fo:page-height")) ?: 792f,
            margins = Margins(
                length(layout?.attr("fo:margin-left")) ?: 72f,
                length(layout?.attr("fo:margin-top")) ?: 72f,
                length(layout?.attr("fo:margin-right")) ?: 72f,
                length(layout?.attr("fo:margin-bottom")) ?: 72f,
            ),
            pages = out.pages.filter { it.isNotEmpty() }.ifEmpty { listOf(emptyList<Block>()) },
            images = pkg.images(),
        )
    }

    /** The list a paragraph is in: its style, nesting level and item counters. */
    private class ListContext(val style: XNode?, val level: Int, val counters: IntArray)

    private fun readBlocks(container: XNode, out: TextBuilder, topLevel: Boolean, list: ListContext?) {
        for (node in container.children) {
            when (node.name) {
                "text:p", "text:h" -> readParagraph(node, out, topLevel, list, firstInItem = false)
                "text:list" -> readList(node, out, topLevel, list)
                "table:table" -> out.add(readTable(node))
                "text:section", "text:index-body", "text:table-of-content", "text:alphabetical-index", "text:illustration-index" ->
                    readBlocks(node, out, topLevel, list)
                "text:soft-page-break" -> if (topLevel) out.newPage()
            }
        }
    }

    /** A list, one level deeper than [parent]; only an item's first paragraph gets a marker. */
    private fun readList(node: XNode, out: TextBuilder, topLevel: Boolean, parent: ListContext?) {
        val style = node.attr("text:style-name")?.let(listStyles::get) ?: parent?.style
        val level = (parent?.level ?: -1) + 1
        val counters = parent?.counters ?: IntArray(10)
        if (level < counters.size) counters[level] = 0
        val ctx = ListContext(style, level, counters)
        for (item in node.children) {
            if (item.name != "text:list-item" && item.name != "text:list-header") continue
            var first = item.name == "text:list-item"
            for (child in item.children) {
                when (child.name) {
                    "text:p", "text:h" -> {
                        readParagraph(child, out, topLevel, ctx, firstInItem = first)
                        first = false
                    }
                    "text:list" -> readList(child, out, topLevel, ctx)
                }
            }
        }
    }

    private fun readParagraph(p: XNode, out: TextBuilder, topLevel: Boolean, list: ListContext?, firstInItem: Boolean) {
        val chain = chain(p.attr("text:style-name"))
        val headingLevel = if (p.name == "text:h") p.attr("text:outline-level")?.toIntOrNull() ?: 1 else 0
        // Headings without a size in their style still need to look like headings.
        val fallback = if (headingLevel > 0) TextStyle(size = HEADING_SIZES.getOrElse(headingLevel - 1) { 12f }, bold = true) else TextStyle(size = 12f)
        val base = textStyle(chain, fallback)
        fun para(attr: String) = lookup(chain, Props::paragraph, attr)
        if (topLevel && para("fo:break-before") == "page") out.newPage()

        var marker: String? = null
        var indent = length(para("fo:margin-left")) ?: 0f
        var firstLine = length(para("fo:text-indent")) ?: 0f
        if (list != null && firstInItem) {
            val level = list.level.coerceIn(0, 9)
            val levelStyle = list.style?.children?.firstOrNull { (it.attr("text:level")?.toIntOrNull() ?: 1) == level + 1 }
            list.counters[level]++
            for (deeper in level + 1 until list.counters.size) list.counters[deeper] = 0
            marker = when (levelStyle?.name) {
                "text:list-level-style-number" -> {
                    val format = levelStyle.attr("style:num-format").orEmpty()
                    if (format.isEmpty()) null
                    else (levelStyle.attr("style:num-prefix").orEmpty() + number(list.counters[level], format) + levelStyle.attr("style:num-suffix").orEmpty())
                }
                "text:list-level-style-bullet" -> DocxReader.bullet(levelStyle.attr("text:bullet-char") ?: "•")
                else -> "•"
            }
            val props = levelStyle?.child("style:list-level-properties")
            val labelAlign = props?.child("style:list-level-label-alignment")
            if (indent == 0f) indent = length(labelAlign?.attr("fo:margin-left")) ?: ((level + 1) * 18f)
            if (firstLine == 0f) firstLine = length(labelAlign?.attr("fo:text-indent")) ?: -18f
        }

        val spans = ArrayList<Span>()
        inline(p, base, spans, link = null)
        out.add(
            Paragraph(
                spans = spans.ifEmpty { listOf(TextSpan("", base)) },
                align = align(para("fo:text-align")) ?: Align.Start,
                indent = indent,
                firstLineIndent = firstLine,
                spaceBefore = length(para("fo:margin-top")) ?: 0f,
                spaceAfter = length(para("fo:margin-bottom")) ?: 0f,
                lineSpacing = para("fo:line-height")?.takeIf { it.endsWith("%") }?.dropLast(1)?.toFloatOrNull()?.div(100f) ?: 1f,
                marker = marker,
                markerStyle = marker?.let { base },
                background = para("fo:background-color")?.takeIf { it != "transparent" }?.let(Colors::hex),
            ),
        )
    }

    /** Text and inline elements of a paragraph, in order. ODF collapses runs of white space. */
    private fun inline(node: XNode, style: TextStyle, out: MutableList<Span>, link: String?) {
        for (c in node.children) {
            when {
                c.isText -> {
                    val t = c.text.replace(Regex("[ \\t\\r\\n]+"), " ")
                    if (t.isNotEmpty()) out += TextSpan(t, style.copy(link = link))
                }
                c.name == "text:span" -> inline(c, textStyle(chain(c.attr("text:style-name")), style), out, link)
                c.name == "text:a" -> inline(c, style.copy(underline = true, color = style.color ?: 0xFF0563C1.toInt()), out, c.attr("xlink:href"))
                c.name == "text:s" -> out += TextSpan(" ".repeat(c.attr("text:c")?.toIntOrNull() ?: 1), style)
                c.name == "text:tab" -> out += TextSpan("\t", style)
                c.name == "text:line-break" -> out += TextSpan("\n", style)
                c.name == "draw:frame" -> {
                    val href = c.find("draw:image")?.attr("xlink:href")
                    if (href != null && !href.startsWith("http")) {
                        out += ImageSpan(href.removePrefix("./"), length(c.attr("svg:width")) ?: 200f, length(c.attr("svg:height")) ?: 150f)
                    } else {
                        // A text frame: its paragraphs' text, inline.
                        c.find("draw:text-box")?.let { inline(it, style, out, link) }
                    }
                }
                c.name == "text:p" || c.name == "text:h" -> {
                    if (out.isNotEmpty()) out += TextSpan("\n", style)
                    inline(c, textStyle(chain(c.attr("text:style-name")), style), out, link)
                }
                c.name in INLINE_CONTAINERS -> inline(c, style, out, link)
                // Skipped: text:note (footnotes), annotations, bookmarks, change tracking marks.
            }
        }
    }

    private fun readTable(table: XNode): Table {
        val widths = ArrayList<Float>()
        fun columns(container: XNode) {
            for (col in container.children) {
                when (col.name) {
                    "table:table-column" -> {
                        val w = lookup(chain(col.attr("table:style-name")), Props::column, "style:column-width")?.let(::length) ?: 72f
                        repeat((col.attr("table:number-columns-repeated")?.toIntOrNull() ?: 1).coerceAtMost(64)) { widths += w }
                    }
                    "table:table-columns", "table:table-header-columns", "table:table-column-group" -> columns(col)
                }
            }
        }
        columns(table)
        val rows = ArrayList<TableRow>()
        fun rowsOf(container: XNode) {
            for (r in container.children) {
                when (r.name) {
                    "table:table-row" -> rows += TableRow(
                        r.children.mapNotNull { cell ->
                            when (cell.name) {
                                "table:table-cell" -> {
                                    val out = TextBuilder()
                                    readBlocks(cell, out, topLevel = false, list = null)
                                    TableCell(
                                        blocks = out.pages.flatten(),
                                        columnSpan = cell.attr("table:number-columns-spanned")?.toIntOrNull() ?: 1,
                                        background = lookup(chain(cell.attr("table:style-name")), Props::cell, "fo:background-color")
                                            ?.takeIf { it != "transparent" }?.let(Colors::hex),
                                    )
                                }
                                // Covered by a cell spanning rows: drawn as part of the one above.
                                "table:covered-table-cell" -> if (rows.isNotEmpty()) TableCell(emptyList(), mergedWithAbove = true) else null
                                else -> null
                            }
                        },
                    )
                    "table:table-rows", "table:table-header-rows", "table:table-row-group" -> rowsOf(r)
                }
            }
        }
        rowsOf(table)
        val count = rows.maxOfOrNull { row -> row.cells.sumOf { it.columnSpan } } ?: 0
        val cols = if (widths.size >= count && widths.isNotEmpty()) widths.take(maxOf(count, 1)) else List(count) { 72f }
        return Table(cols, rows, borders = true)
    }

    // endregion

    // region Spreadsheets

    private fun readSpreadsheet(sheet: XNode): Workbook {
        val sheets = sheet.children("table:table").map(::readSheet)
        if (sheets.isEmpty()) throw UnreadableDocumentException("This spreadsheet has no sheets")
        return Workbook(sheets)
    }

    private fun readSheet(table: XNode): Sheet {
        val rows = HashMap<Int, HashMap<Int, Cell>>()
        val widths = HashMap<Int, Float>()
        val heights = HashMap<Int, Float>()
        val merges = ArrayList<CellRange>()
        val columnStyles = ArrayList<String?>()
        var truncated = false
        var maxRow = -1
        var maxCol = -1

        var col = 0
        fun columns(container: XNode) {
            for (c in container.children) {
                when (c.name) {
                    "table:table-column" -> {
                        val repeat = c.attr("table:number-columns-repeated")?.toIntOrNull() ?: 1
                        val w = lookup(chain(c.attr("table:style-name")), Props::column, "style:column-width")?.let(::length)
                        for (k in 0 until repeat) {
                            if (col >= XlsxReader.MAX_COLUMNS) break
                            if (w != null) widths[col] = w * 4 / 3
                            columnStyles += c.attr("table:default-cell-style-name")
                            col++
                        }
                    }
                    "table:table-columns", "table:table-header-columns", "table:table-column-group" -> columns(c)
                }
            }
        }
        columns(table)

        var row = 0
        fun rowsOf(container: XNode) {
            for (r in container.children) {
                when (r.name) {
                    "table:table-row" -> {
                        val repeat = r.attr("table:number-rows-repeated")?.toIntOrNull() ?: 1
                        val height = lookup(chain(r.attr("table:style-name")), Props::row, "style:row-height")?.let(::length)?.times(4f / 3)
                        val cells = HashMap<Int, Cell>()
                        var c = 0
                        for (cell in r.children) {
                            if (cell.name != "table:table-cell" && cell.name != "table:covered-table-cell") continue
                            val colRepeat = cell.attr("table:number-columns-repeated")?.toIntOrNull() ?: 1
                            val text = cell.children.filter { it.name == "text:p" }.joinToString("\n") { it.allText() }
                            val styleName = cell.attr("table:style-name") ?: columnStyles.getOrNull(c)
                            if (cell.name == "table:table-cell" && (text.isNotEmpty() || styleName != null)) {
                                val spanCols = cell.attr("table:number-columns-spanned")?.toIntOrNull() ?: 1
                                val spanRows = cell.attr("table:number-rows-spanned")?.toIntOrNull() ?: 1
                                val style = cellStyle(styleName)
                                val isNumber = cell.attr("office:value-type") in NUMERIC_TYPES
                                if (text.isNotEmpty() || style.fill != null) {
                                    for (k in 0 until colRepeat.coerceAtMost(XlsxReader.MAX_COLUMNS)) {
                                        if (c + k >= XlsxReader.MAX_COLUMNS) { truncated = true; break }
                                        cells[c + k] = Cell(text, style, isNumber)
                                    }
                                }
                                if (spanCols > 1 || spanRows > 1) merges += CellRange(row, c, row + spanRows - 1, c + spanCols - 1)
                            }
                            c += colRepeat
                            if (c > 1_000_000) break
                        }
                        if (cells.isNotEmpty()) {
                            // Rows repeated with content are rare; empty repeats are just padding to the sheet's end.
                            for (k in 0 until repeat.coerceAtMost(1000)) {
                                if (row + k >= XlsxReader.MAX_ROWS) { truncated = true; break }
                                rows[row + k] = HashMap(cells)
                                height?.let { heights[row + k] = it }
                                maxRow = maxOf(maxRow, row + k)
                                maxCol = maxOf(maxCol, cells.keys.max())
                            }
                        }
                        row += repeat
                    }
                    "table:table-rows", "table:table-header-rows", "table:table-row-group" -> rowsOf(r)
                }
                if (row > XlsxReader.MAX_ROWS && maxRow < row - 1) break
            }
        }
        rowsOf(table)
        for (m in merges) {
            maxRow = maxOf(maxRow, m.lastRow.coerceAtMost(XlsxReader.MAX_ROWS - 1))
            maxCol = maxOf(maxCol, m.lastColumn.coerceAtMost(XlsxReader.MAX_COLUMNS - 1))
        }
        return Sheet(
            name = table.attr("table:name") ?: "Sheet",
            rowCount = maxRow + 1,
            columnCount = maxCol + 1,
            rows = rows,
            columnWidths = widths,
            rowHeights = heights,
            merges = merges,
            truncated = truncated,
        )
    }

    private val cellStyles = HashMap<String?, CellStyle>()

    private fun cellStyle(name: String?): CellStyle = cellStyles.getOrPut(name) {
        val chain = chain(name)
        val text = textStyle(chain, TextStyle(size = 10f))
        CellStyle(
            bold = text.bold,
            italic = text.italic,
            underline = text.underline,
            strike = text.strike,
            size = text.size,
            color = text.color?.takeIf { it != Colors.BLACK },
            fill = lookup(chain, Props::cell, "fo:background-color")?.takeIf { it != "transparent" }?.let(Colors::hex),
            align = when (lookup(chain, Props::paragraph, "fo:text-align")) {
                "center" -> CellAlign.Center
                "end", "right" -> CellAlign.Right
                "start", "left" -> CellAlign.Left
                else -> CellAlign.General
            },
            wrap = lookup(chain, Props::cell, "fo:wrap-option") == "wrap",
            border = lookup(chain, Props::cell, "fo:border").let { it != null && it != "none" },
        )
    }

    // endregion

    // region Presentations

    private fun readPresentation(pres: XNode): Presentation {
        val pages = pres.children("draw:page")
        val layout = pages.firstOrNull()?.attr("draw:master-page-name")?.let(masterPages::get)?.let(pageLayouts::get)
            ?: masterPages.values.firstOrNull()?.let(pageLayouts::get)
        val props = layout?.child("style:page-layout-properties")
        val width = length(props?.attr("fo:page-width")) ?: 720f
        val height = length(props?.attr("fo:page-height")) ?: 405f
        val slides = pages.map { page ->
            val background = lookup(chain(page.attr("draw:style-name")), { it.graphic ?: it.paragraph }, "draw:fill-color")?.let(Colors::hex)
            val elements = ArrayList<SlideElement>()
            readShapes(page, elements)
            Slide(background ?: Colors.WHITE, null, elements)
        }
        if (slides.isEmpty()) throw UnreadableDocumentException("This presentation has no slides")
        return Presentation(emu(width), emu(height), slides, pkg.images())
    }

    private fun readShapes(container: XNode, out: MutableList<SlideElement>) {
        for (node in container.children) {
            if (node.isText) continue
            when (node.name) {
                "draw:g" -> readShapes(node, out)
                "draw:frame", "draw:custom-shape", "draw:rect", "draw:ellipse", "draw:line", "draw:polygon", "draw:circle" -> readShape(node)?.let(out::add)
            }
        }
    }

    private fun readShape(node: XNode): SlideElement? {
        val frame = Frame(
            x = emu(length(node.attr("svg:x") ?: node.attr("svg:x1")) ?: 0f),
            y = emu(length(node.attr("svg:y") ?: node.attr("svg:y1")) ?: 0f),
            width = emu(length(node.attr("svg:width")) ?: (length(node.attr("svg:x2")) ?: 0f) - (length(node.attr("svg:x1")) ?: 0f)),
            height = emu(length(node.attr("svg:height")) ?: (length(node.attr("svg:y2")) ?: 0f) - (length(node.attr("svg:y1")) ?: 0f)),
        )
        val image = node.child("draw:image")?.attr("xlink:href")
        if (node.name == "draw:frame" && image != null && node.child("draw:text-box") == null) {
            return PictureElement(frame, image.removePrefix("./"))
        }
        val styleChain = chain(node.attr("presentation:style-name") ?: node.attr("draw:style-name"))
        fun graphic(attr: String) = lookup(styleChain, Props::graphic, attr)
        val fill = if (graphic("draw:fill") == "solid" || (graphic("draw:fill") == null && node.name != "draw:frame")) graphic("draw:fill-color")?.let(Colors::hex) else null
        val stroke = if (graphic("draw:stroke") != "none" && node.name != "draw:frame") graphic("svg:stroke-color")?.let(Colors::hex) else null
        val geometry = when (node.name) {
            "draw:ellipse", "draw:circle" -> Geometry.Ellipse
            "draw:line" -> Geometry.Line
            "draw:custom-shape" -> {
                val geometry = node.child("draw:enhanced-geometry")
                when (geometry?.attr("draw:type")) {
                    "ellipse" -> Geometry.Ellipse
                    "round-rectangle" -> Geometry.RoundRect
                    "isosceles-triangle", "right-triangle" -> Geometry.Triangle
                    "diamond" -> Geometry.Diamond
                    "rectangle", null -> Geometry.Rect
                    // Microsoft Office writes its shapes as paths: arcs alone make an
                    // ellipse, arcs and lines a rounded rectangle.
                    "non-primitive" -> {
                        val path = " " + geometry.attr("draw:enhanced-path").orEmpty() + " "
                        val arcs = listOf(" A ", " W ", " T ", " U ").any { it in path }
                        val lines = " L " in path
                        when {
                            arcs && !lines -> Geometry.Ellipse
                            arcs -> Geometry.RoundRect
                            else -> Geometry.Rect
                        }
                    }
                    else -> Geometry.Other
                }
            }
            else -> Geometry.Rect
        }
        val textContainer = node.child("draw:text-box") ?: node
        val cls = node.attr("presentation:class")
        val baseSize = when (cls) {
            "title" -> 44f
            "subtitle" -> 32f
            "outline" -> 28f
            else -> 18f
        }
        val base = textStyle(styleChain, TextStyle(size = baseSize, color = Colors.BLACK))
        val paragraphs = ArrayList<SlideParagraph>()
        collectSlideParagraphs(textContainer, base, paragraphs, listLevel = -1, bulleted = cls == "outline")
        val text = paragraphs.takeIf { list -> list.any { p -> p.spans.any { it.text.isNotBlank() } } }?.let {
            TextFrame(
                it,
                anchor = when (graphic("draw:textarea-vertical-align")) {
                    "middle" -> Anchor.Middle
                    "bottom" -> Anchor.Bottom
                    else -> if (node.name == "draw:frame") Anchor.Top else Anchor.Middle
                },
            )
        }
        if (fill == null && stroke == null && text == null) return null
        return ShapeElement(frame, geometry, fill, stroke?.let { Outline(it, 12_700) }, text)
    }

    private fun collectSlideParagraphs(container: XNode, base: TextStyle, out: MutableList<SlideParagraph>, listLevel: Int, bulleted: Boolean) {
        for (c in container.children) {
            when (c.name) {
                "text:p", "text:h" -> {
                    val chain = chain(c.attr("text:style-name"))
                    val style = textStyle(chain, base)
                    val spans = ArrayList<Span>()
                    inline(c, style, spans, link = null)
                    val textSpans = spans.filterIsInstance<TextSpan>()
                    val level = listLevel.coerceAtLeast(0)
                    out += SlideParagraph(
                        spans = textSpans,
                        align = align(lookup(chain, Props::paragraph, "fo:text-align")) ?: Align.Start,
                        bullet = if (listLevel >= 0 && bulleted && textSpans.any { it.text.isNotBlank() }) "•" else null,
                        marginLeft = if (listLevel >= 0) emu(18f + level * 27f) else 0L,
                        indent = if (listLevel >= 0 && bulleted) -emu(18f) else 0L,
                        spaceBefore = length(lookup(chain, Props::paragraph, "fo:margin-top")) ?: 0f,
                        emptySize = style.size,
                    )
                }
                "text:list" -> c.children("text:list-item").forEach { collectSlideParagraphs(it, base, out, listLevel + 1, bulleted = true) }
            }
        }
    }

    // endregion

    companion object {
        private val HEADING_SIZES = listOf(20f, 16f, 14f, 13f, 12f, 12f)
        private val NUMERIC_TYPES = setOf("float", "percentage", "currency", "date", "time")
        private val INLINE_CONTAINERS = setOf("text:meta", "text:ruby-base", "text:hidden-paragraph", "text:bookmark-ref", "text:reference-ref", "text:sequence", "text:date", "text:time", "text:page-number", "text:sender-name", "text:author-name", "text:title", "text:subject", "text:file-name", "text:user-defined", "text:variable-get", "text:expression", "office:annotation-end")

        /** ODF lengths: "2.54cm", "1in", "12pt", "10mm", "16px", "1pc". */
        fun length(value: String?): Float? {
            val v = value?.trim() ?: return null
            val number = v.takeWhile { it.isDigit() || it == '.' || it == '-' }.toFloatOrNull() ?: return null
            return when (v.drop(v.takeWhile { it.isDigit() || it == '.' || it == '-' }.length)) {
                "in" -> number * 72
                "cm" -> number * 72 / 2.54f
                "mm" -> number * 72 / 25.4f
                "px" -> number * 0.75f
                "pc" -> number * 12
                else -> number
            }
        }

        fun emu(points: Float): Long = (points * 12_700).toLong()

        fun number(n: Int, format: String): String = when (format) {
            "a" -> DocxReader.format(n, "lowerLetter")
            "A" -> DocxReader.format(n, "upperLetter")
            "i" -> DocxReader.format(n, "lowerRoman")
            "I" -> DocxReader.format(n, "upperRoman")
            else -> n.toString()
        }
    }
}
