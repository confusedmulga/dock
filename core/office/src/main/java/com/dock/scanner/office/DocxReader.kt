package com.dock.scanner.office

/**
 * Reads Word documents (.docx). Styles are resolved the way Word does it (document defaults,
 * then the paragraph style chain, then character styles, then direct formatting), lists are
 * numbered, and pages are split where Word last broke them when it saved the file
 * (`lastRenderedPageBreak`), so pages match Word's closely without a layout engine.
 */
internal class DocxReader(private val pkg: ZipPackage) {

    private lateinit var docPath: String
    private lateinit var rels: Map<String, Relationship>
    private var theme = Theme.Default
    private val styles = HashMap<String, StyleDef>()
    private var defaultParagraphStyle: String? = null
    private var defaults = RunProps()
    private var defaultPara = ParaProps()
    private val numbering = Numbering()

    fun read(): TextDocument {
        docPath = pkg.relationships("").values.firstOrNull { it.type.endsWith("/officeDocument") }?.target
            ?: "word/document.xml".takeIf(pkg::has)
            ?: throw UnreadableDocumentException("This isn't a Word document")
        rels = pkg.relationships(docPath)
        fun related(suffix: String) = rels.values.firstOrNull { it.type.endsWith(suffix) }?.target
        theme = Theme.parse(related("/theme")?.let(pkg::xml))
        related("/styles")?.let(pkg::xml)?.let(::readStyles)
        related("/numbering")?.let(pkg::xml)?.let(numbering::read)

        val root = pkg.xml(docPath) ?: throw UnreadableDocumentException("This Word document is empty")
        val body = root.child("w:body") ?: throw UnreadableDocumentException("This Word document is empty")

        val sectPr = body.child("w:sectPr")
        val size = sectPr?.child("w:pgSz")
        val margins = sectPr?.child("w:pgMar")
        var width = twips(size?.attr("w:w")) ?: 612f
        var height = twips(size?.attr("w:h")) ?: 792f
        if (size?.attr("w:orient") == "landscape" && width < height) width = height.also { height = width }

        val out = PageWriter()
        readBlocks(body, out, topLevel = true)
        return TextDocument(
            pageWidth = width,
            pageHeight = height,
            margins = Margins(
                left = twips(margins?.attr("w:left")) ?: 72f,
                top = twips(margins?.attr("w:top")) ?: 72f,
                right = twips(margins?.attr("w:right")) ?: 72f,
                bottom = twips(margins?.attr("w:bottom")) ?: 72f,
            ),
            pages = out.pages(),
            images = pkg.images(),
        )
    }

    // region Blocks

    /** Collects blocks into pages; page breaks only count at the top level, not inside tables. */
    private class PageWriter {
        private val pages = mutableListOf(mutableListOf<Block>())
        fun add(block: Block) {
            pages.last() += block
        }

        fun newPage() {
            if (pages.last().isNotEmpty()) pages += mutableListOf<Block>()
        }

        fun pages(): List<List<Block>> = pages.filter { it.isNotEmpty() }.ifEmpty { listOf(emptyList()) }
    }

    private fun readBlocks(container: XNode, out: PageWriter, topLevel: Boolean) {
        for (node in container.children) {
            when (node.name) {
                "w:p" -> readParagraph(node, out, topLevel)
                "w:tbl" -> out.add(readTable(node))
                "w:sdt" -> node.child("w:sdtContent")?.let { readBlocks(it, out, topLevel) }
                "w:customXml", "w:ins", "w:moveTo" -> readBlocks(node, out, topLevel)
                "mc:AlternateContent" -> alternate(node)?.let { readBlocks(it, out, topLevel) }
            }
        }
    }

    private fun blocksOf(container: XNode): List<Block> {
        val out = PageWriter()
        readBlocks(container, out, topLevel = false)
        return out.pages().flatten()
    }

    private fun readParagraph(p: XNode, out: PageWriter, topLevel: Boolean) {
        val pPr = p.child("w:pPr")
        val styleId = pPr?.child("w:pStyle")?.attr("w:val") ?: defaultParagraphStyle
        val styleChain = chain(styleId)
        var para = defaultPara
        var paraRun = defaults
        for (s in styleChain) {
            para = para.merge(s.para)
            paraRun = paraRun.merge(s.run)
        }
        para = para.merge(pPr?.let(::paraProps))

        // List marker, with the level's indents unless the paragraph sets its own.
        var marker: String? = null
        var markerStyle: TextStyle? = null
        val numId = para.numId
        if (numId != null && numId != "0") {
            val level = para.ilvl ?: 0
            numbering.next(numId, level)?.let { (text, lvl) ->
                marker = text
                markerStyle = style(paraRun.merge(lvl.run))
                para = ParaProps(indent = lvl.indent, firstLine = lvl.firstLine).merge(para)
            }
        }

        if (topLevel && para.pageBreakBefore == true) out.newPage()

        val spans = ArrayList<Span>()
        val pending = ArrayList<Block>() // text boxes found in this paragraph
        fun emit() {
            val content = if (spans.isEmpty()) listOf(TextSpan("", style(paraRun))) else spans.toList()
            out.add(
                Paragraph(
                    spans = content,
                    align = para.align ?: Align.Start,
                    indent = para.indent ?: 0f,
                    firstLineIndent = para.firstLine ?: 0f,
                    spaceBefore = para.spaceBefore ?: 0f,
                    spaceAfter = para.spaceAfter ?: 0f,
                    lineSpacing = para.lineSpacing(paraRun.size ?: 11f),
                    marker = marker,
                    markerStyle = markerStyle,
                    background = para.shading,
                ),
            )
            marker = null
            spans.clear()
        }

        val ctx = RunContext(paraRun, topLevel, onPageBreak = {
            if (topLevel) {
                if (spans.isNotEmpty() || marker != null) emit()
                out.newPage()
            } else {
                spans += TextSpan("\n", style(paraRun))
            }
        }, spans = spans, pending = pending)
        readInline(p, ctx, link = null)
        if (spans.isNotEmpty() || marker != null || pending.isEmpty()) emit()
        pending.forEach(out::add)

        // A section break that starts a new page.
        val sect = pPr?.child("w:sectPr")
        if (topLevel && sect != null) {
            val type = sect.child("w:type")?.attr("w:val") ?: "nextPage"
            if (type != "continuous") out.newPage()
        }
    }

    private class RunContext(
        val paraRun: RunProps,
        val topLevel: Boolean,
        val onPageBreak: () -> Unit,
        val spans: MutableList<Span>,
        val pending: MutableList<Block>,
    )

    /** Runs, hyperlinks and the wrappers around them, in order. */
    private fun readInline(container: XNode, ctx: RunContext, link: String?) {
        for (node in container.children) {
            when (node.name) {
                "w:r" -> readRun(node, ctx, link)
                "w:hyperlink" -> {
                    val target = node.attr("r:id")?.let { rels[it]?.target } ?: node.attr("w:anchor")?.let { "#$it" } ?: ""
                    readInline(node, ctx, target)
                }
                "w:fldSimple", "w:smartTag", "w:customXml", "w:ins", "w:moveTo", "w:dir", "w:bdo" -> readInline(node, ctx, link)
                "w:sdt" -> node.child("w:sdtContent")?.let { readInline(it, ctx, link) }
                "mc:AlternateContent" -> alternate(node)?.let { readInline(it, ctx, link) }
                // Skipped: w:del, w:moveFrom (deleted text), w:proofErr, bookmarks, comments.
            }
        }
    }

    private fun readRun(r: XNode, ctx: RunContext, link: String?) {
        val rPr = r.child("w:rPr")
        var props = ctx.paraRun
        rPr?.child("w:rStyle")?.attr("w:val")?.let { id -> chain(id).forEach { props = props.merge(it.run) } }
        props = props.merge(rPr?.let(::runProps))
        if (props.hidden == true) return
        var style = style(props)
        if (link != null) {
            style = style.copy(link = link, underline = true, color = style.color ?: HYPERLINK_BLUE)
        }
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) {
                ctx.spans += TextSpan(text.toString(), style)
                text.clear()
            }
        }
        for (node in r.children) {
            when (node.name) {
                "w:t" -> text.append(node.text)
                "w:tab", "w:ptab" -> text.append('\t')
                "w:br" -> when (node.attr("w:type")) {
                    "page" -> {
                        flush()
                        ctx.onPageBreak()
                    }
                    "column" -> text.append('\n')
                    else -> text.append('\n')
                }
                // Where Word's last layout broke the page. Inside a table or text box that's
                // the table running onto the next page, not a break in the text.
                "w:lastRenderedPageBreak" -> if (ctx.topLevel) {
                    flush()
                    ctx.onPageBreak()
                }
                "w:cr" -> text.append('\n')
                "w:noBreakHyphen" -> text.append('‑')
                "w:sym" -> node.attr("w:char")?.toIntOrNull(16)?.let { text.append(symbol(it)) }
                "w:drawing", "w:pict", "w:object", "mc:AlternateContent" -> {
                    flush()
                    readDrawing(node, ctx, style)
                }
            }
        }
        flush()
    }

    /** A picture, a text box or a chart inside a run. */
    private fun readDrawing(node: XNode, ctx: RunContext, style: TextStyle) {
        val content = if (node.name == "mc:AlternateContent") alternate(node) ?: return else node
        // Text boxes: their paragraphs follow the one they're anchored in.
        content.find("w:txbxContent")?.let { ctx.pending += blocksOf(it) }

        val blip = content.find("a:blip")?.attr("r:embed")
        val extent = content.find("wp:extent")
        if (blip != null) {
            val target = rels[blip]?.target ?: return
            val w = emu(extent?.attr("cx")) ?: 200f
            val h = emu(extent?.attr("cy")) ?: 150f
            ctx.spans += ImageSpan(target, w, h)
            return
        }
        // Old VML pictures.
        val imageData = content.find("v:imagedata")
        val vmlId = imageData?.attr("r:id")
        if (vmlId != null) {
            val target = rels[vmlId]?.target ?: return
            val css = content.find("v:shape")?.attr("style").orEmpty()
            ctx.spans += ImageSpan(target, cssLength(css, "width") ?: 200f, cssLength(css, "height") ?: 150f)
            return
        }
        if (content.find("c:chart") != null) {
            ctx.spans += TextSpan("[Chart]", style.copy(italic = true, color = 0xFF808080.toInt()))
        }
    }

    private fun readTable(tbl: XNode): Table {
        val tblPr = tbl.child("w:tblPr")
        val grid = tbl.child("w:tblGrid")?.children("w:gridCol")?.map { twips(it.attr("w:w")) ?: 0f }.orEmpty()
        val styleBorders = tblPr?.child("w:tblStyle")?.attr("w:val")?.let { id -> chain(id).lastOrNull { it.tableBorders != null }?.tableBorders }
        val borders = tblPr?.child("w:tblBorders")?.let(::hasBorders) ?: styleBorders ?: false

        val rows = tbl.children.flatMap { node ->
            when (node.name) {
                "w:tr" -> listOf(node)
                "w:sdt", "w:customXml" -> node.findAll("w:tr")
                else -> emptyList()
            }
        }.map { tr ->
            TableRow(
                tr.children.filter { it.name == "w:tc" || it.name == "w:sdt" }.mapNotNull { tc ->
                    val cell = if (tc.name == "w:sdt") tc.find("w:tc") ?: return@mapNotNull null else tc
                    val tcPr = cell.child("w:tcPr")
                    val vMerge = tcPr?.child("w:vMerge")
                    TableCell(
                        blocks = blocksOf(cell),
                        columnSpan = tcPr?.child("w:gridSpan")?.attr("w:val")?.toIntOrNull() ?: 1,
                        background = tcPr?.child("w:shd")?.attr("w:fill")?.let(Colors::hex),
                        mergedWithAbove = vMerge != null && vMerge.attr("w:val") != "restart",
                    )
                },
            )
        }
        val columns = rows.maxOfOrNull { row -> row.cells.sumOf { it.columnSpan } } ?: 0
        val widths = if (grid.size >= columns && grid.sum() > 0) grid else List(columns) { 1f }
        return Table(widths, rows, borders, indent = twips(tblPr?.child("w:tblInd")?.attr("w:w")) ?: 0f)
    }

    private fun hasBorders(node: XNode): Boolean = node.children.any { it.attr("w:val").let { v -> v != null && v != "nil" && v != "none" } }

    /** Takes the modern branch of an mc:AlternateContent when it's one we read, else the fallback. */
    private fun alternate(node: XNode): XNode? =
        node.children("mc:Choice").firstOrNull() ?: node.child("mc:Fallback")

    // endregion

    // region Styles

    private class StyleDef(val basedOn: String?, val para: ParaProps, val run: RunProps, val tableBorders: Boolean?)

    private fun readStyles(root: XNode) {
        root.path("w:docDefaults", "w:rPrDefault", "w:rPr")?.let { defaults = defaults.merge(runProps(it)) }
        root.path("w:docDefaults", "w:pPrDefault", "w:pPr")?.let { defaultPara = defaultPara.merge(paraProps(it)) }
        for (s in root.children("w:style")) {
            val id = s.attr("w:styleId") ?: continue
            styles[id] = StyleDef(
                basedOn = s.child("w:basedOn")?.attr("w:val"),
                para = s.child("w:pPr")?.let(::paraProps) ?: ParaProps(),
                run = s.child("w:rPr")?.let(::runProps) ?: RunProps(),
                tableBorders = s.path("w:tblPr", "w:tblBorders")?.let(::hasBorders),
            )
            if (s.attr("w:type") == "paragraph" && s.attr("w:default").let { it == "1" || it == "true" }) defaultParagraphStyle = id
        }
    }

    /** A style and the styles it's based on, outermost first. */
    private fun chain(id: String?): List<StyleDef> {
        val result = ArrayList<StyleDef>()
        var current = id
        while (current != null && result.size < 16) {
            val def = styles[current] ?: break
            result.add(0, def)
            current = def.basedOn
        }
        return result
    }

    private fun runProps(rPr: XNode): RunProps {
        fun toggle(name: String): Boolean? = rPr.child(name)?.let { it.attr("w:val") !in FALSE }
        val fonts = rPr.child("w:rFonts")
        val font = fonts?.attr("w:ascii") ?: fonts?.attr("w:hAnsi") ?: when (fonts?.attr("w:asciiTheme")) {
            "majorHAnsi", "majorAscii" -> theme.majorFont
            "minorHAnsi", "minorAscii" -> theme.minorFont
            else -> null
        }
        val colorNode = rPr.child("w:color")
        return RunProps(
            bold = toggle("w:b"),
            italic = toggle("w:i"),
            underline = rPr.child("w:u")?.let { (it.attr("w:val") ?: "single") != "none" },
            strike = toggle("w:strike") ?: toggle("w:dstrike"),
            size = rPr.child("w:sz")?.attr("w:val")?.toFloatOrNull()?.div(2),
            color = colorNode?.let(::wordColor),
            highlight = rPr.child("w:highlight")?.attr("w:val")?.let { Colors.HIGHLIGHTS[it] }
                ?: rPr.child("w:shd")?.attr("w:fill")?.let(Colors::hex),
            font = font,
            baseline = when (rPr.child("w:vertAlign")?.attr("w:val")) {
                "superscript" -> Baseline.Superscript
                "subscript" -> Baseline.Subscript
                null -> null
                else -> Baseline.Normal
            },
            hidden = toggle("w:vanish"),
            caps = toggle("w:caps"),
        )
    }

    private fun wordColor(node: XNode): Int? {
        node.attr("w:val")?.takeIf { it != "auto" }?.let(Colors::hex)?.let { return it }
        val slot = when (val t = node.attr("w:themeColor")) {
            null -> return null
            "text1", "dark1" -> "dk1"
            "text2", "dark2" -> "dk2"
            "background1", "light1" -> "lt1"
            "background2", "light2" -> "lt2"
            "hyperlink" -> "hlink"
            "followedHyperlink" -> "folHlink"
            else -> t
        }
        var c = theme.colors[slot] ?: return null
        node.attr("w:themeShade")?.toIntOrNull(16)?.let { c = Colors.shade(c, it / 255f) }
        node.attr("w:themeTint")?.toIntOrNull(16)?.let { c = Colors.tint(c, it / 255f) }
        return c
    }

    private fun paraProps(pPr: XNode): ParaProps {
        val ind = pPr.child("w:ind")
        val spacing = pPr.child("w:spacing")
        val hanging = twips(ind?.attr("w:hanging"))
        val line = spacing?.attr("w:line")?.toFloatOrNull()
        val rule = spacing?.attr("w:lineRule") ?: "auto"
        val numPr = pPr.child("w:numPr")
        return ParaProps(
            align = when (pPr.child("w:jc")?.attr("w:val")) {
                "center" -> Align.Center
                "right", "end" -> Align.End
                "both", "distribute", "justify" -> Align.Justify
                "left", "start" -> Align.Start
                else -> null
            },
            indent = twips(ind?.attr("w:left") ?: ind?.attr("w:start")),
            firstLine = hanging?.let { -it } ?: twips(ind?.attr("w:firstLine")),
            spaceBefore = twips(spacing?.attr("w:before")),
            spaceAfter = twips(spacing?.attr("w:after")),
            lineMultiple = if (line != null && rule == "auto") line / 240f else null,
            lineExact = if (line != null && rule != "auto") line / 20f else null,
            numId = numPr?.child("w:numId")?.attr("w:val"),
            ilvl = numPr?.child("w:ilvl")?.attr("w:val")?.toIntOrNull(),
            pageBreakBefore = pPr.child("w:pageBreakBefore")?.let { it.attr("w:val") !in FALSE },
            shading = pPr.child("w:shd")?.attr("w:fill")?.let(Colors::hex),
        )
    }

    private fun style(p: RunProps) = TextStyle(
        size = p.size ?: 11f,
        bold = p.bold ?: false,
        italic = p.italic ?: false,
        underline = p.underline ?: false,
        strike = p.strike ?: false,
        color = p.color,
        background = p.highlight,
        font = fontKind(p.font ?: theme.minorFont),
        baseline = p.baseline ?: Baseline.Normal,
        allCaps = p.caps ?: false,
    )

    // endregion

    // region Numbering

    private class Level(val format: String, val text: String, val start: Int, val indent: Float?, val firstLine: Float?, val run: RunProps?)

    private inner class Numbering {
        private val abstracts = HashMap<String, Map<Int, Level>>()
        private val nums = HashMap<String, Pair<String, Map<Int, Int>>>()
        private val counters = HashMap<String, IntArray>()

        fun read(root: XNode) {
            for (a in root.children("w:abstractNum")) {
                val id = a.attr("w:abstractNumId") ?: continue
                abstracts[id] = a.children("w:lvl").mapNotNull { lvl ->
                    val i = lvl.attr("w:ilvl")?.toIntOrNull() ?: return@mapNotNull null
                    val ind = lvl.path("w:pPr", "w:ind")
                    i to Level(
                        format = lvl.child("w:numFmt")?.attr("w:val") ?: "decimal",
                        text = lvl.child("w:lvlText")?.attr("w:val") ?: "",
                        start = lvl.child("w:start")?.attr("w:val")?.toIntOrNull() ?: 1,
                        indent = twips(ind?.attr("w:left") ?: ind?.attr("w:start")),
                        firstLine = twips(ind?.attr("w:hanging"))?.let { -it } ?: twips(ind?.attr("w:firstLine")),
                        run = lvl.child("w:rPr")?.let(::runProps),
                    )
                }.toMap()
            }
            for (n in root.children("w:num")) {
                val id = n.attr("w:numId") ?: continue
                val abstract = n.child("w:abstractNumId")?.attr("w:val") ?: continue
                val overrides = n.children("w:lvlOverride").mapNotNull { o ->
                    val lvl = o.attr("w:ilvl")?.toIntOrNull() ?: return@mapNotNull null
                    val start = o.child("w:startOverride")?.attr("w:val")?.toIntOrNull() ?: return@mapNotNull null
                    lvl to start
                }.toMap()
                nums[id] = abstract to overrides
            }
        }

        /** Advances the list and returns this item's marker text and level. */
        fun next(numId: String, ilvl: Int): Pair<String, Level>? {
            val (abstractId, overrides) = nums[numId] ?: return null
            val levels = abstracts[abstractId] ?: return null
            val level = levels[ilvl] ?: return null
            fun start(i: Int) = overrides[i] ?: levels[i]?.start ?: 1
            val c = counters.getOrPut(numId) { IntArray(9) { start(it) - 1 } }
            if (ilvl !in c.indices) return null
            c[ilvl]++
            for (deeper in ilvl + 1 until c.size) c[deeper] = start(deeper) - 1
            if (level.format == "bullet") return bullet(level.text) to level
            if (level.format == "none") return "" to level
            val text = Regex("%([1-9])").replace(level.text) { m ->
                val i = m.groupValues[1].toInt() - 1
                format(maxOf(c.getOrElse(i) { 1 }, start(i)), levels[i]?.format ?: "decimal")
            }
            return text to level
        }
    }

    // endregion

    private data class RunProps(
        val bold: Boolean? = null,
        val italic: Boolean? = null,
        val underline: Boolean? = null,
        val strike: Boolean? = null,
        val size: Float? = null,
        val color: Int? = null,
        val highlight: Int? = null,
        val font: String? = null,
        val baseline: Baseline? = null,
        val hidden: Boolean? = null,
        val caps: Boolean? = null,
    ) {
        fun merge(o: RunProps?): RunProps = if (o == null) this else RunProps(
            o.bold ?: bold, o.italic ?: italic, o.underline ?: underline, o.strike ?: strike, o.size ?: size,
            o.color ?: color, o.highlight ?: highlight, o.font ?: font, o.baseline ?: baseline, o.hidden ?: hidden, o.caps ?: caps,
        )
    }

    private data class ParaProps(
        val align: Align? = null,
        val indent: Float? = null,
        val firstLine: Float? = null,
        val spaceBefore: Float? = null,
        val spaceAfter: Float? = null,
        val lineMultiple: Float? = null,
        val lineExact: Float? = null,
        val numId: String? = null,
        val ilvl: Int? = null,
        val pageBreakBefore: Boolean? = null,
        val shading: Int? = null,
    ) {
        fun merge(o: ParaProps?): ParaProps = if (o == null) this else ParaProps(
            o.align ?: align, o.indent ?: indent, o.firstLine ?: firstLine, o.spaceBefore ?: spaceBefore,
            o.spaceAfter ?: spaceAfter,
            if (o.lineMultiple != null || o.lineExact != null) o.lineMultiple else lineMultiple,
            if (o.lineMultiple != null || o.lineExact != null) o.lineExact else lineExact,
            o.numId ?: numId, o.ilvl ?: ilvl, o.pageBreakBefore ?: pageBreakBefore, o.shading ?: shading,
        )

        /** Line spacing as a multiple; an exact height is converted using the paragraph's font size. */
        fun lineSpacing(fontSize: Float): Float = lineMultiple
            ?: lineExact?.let { (it / (fontSize * 1.17f)).coerceIn(0.6f, 4f) }
            ?: 1f
    }

    companion object {
        private val FALSE = setOf("0", "false", "off")
        private const val HYPERLINK_BLUE = 0xFF0563C1.toInt()

        fun twips(v: String?): Float? = v?.toFloatOrNull()?.div(20f)

        fun emu(v: String?): Float? = v?.toLongOrNull()?.let { it / 12_700f }

        /** "width:120pt" style lengths in VML. */
        fun cssLength(css: String, property: String): Float? {
            val m = Regex("""(?:^|;)\s*$property\s*:\s*([\d.]+)\s*(pt|in|px|cm|mm)?""").find(css) ?: return null
            val n = m.groupValues[1].toFloatOrNull() ?: return null
            return when (m.groupValues[2]) {
                "in" -> n * 72
                "px" -> n * 0.75f
                "cm" -> n * 72 / 2.54f
                "mm" -> n * 72 / 25.4f
                else -> n
            }
        }

        /** Bullet characters from the Symbol/Wingdings fonts, which the phone doesn't have. */
        fun bullet(text: String): String {
            val c = text.firstOrNull() ?: return "•"
            return when (c.code) {
                0xF0B7, 0xB7 -> "•"
                0xF0A7, 0xA7 -> "▪"
                0xF0D8 -> "➢"
                0xF0FC -> "✓"
                0xF076 -> "❖"
                0xF06E -> "■"
                0xF071 -> "❑"
                else -> when {
                    c == 'o' -> "◦"
                    c.code in 0xF000..0xF0FF -> "•"
                    else -> text
                }
            }
        }

        fun symbol(code: Int): String = if (code in 0xF000..0xF0FF) bullet(code.toChar().toString()) else code.toChar().toString()

        fun format(n: Int, format: String): String = when (format) {
            "lowerLetter" -> letters(n).lowercase()
            "upperLetter" -> letters(n)
            "lowerRoman" -> roman(n).lowercase()
            "upperRoman" -> roman(n)
            "decimalZero" -> n.toString().padStart(2, '0')
            "ordinal" -> "$n${ordinalSuffix(n)}"
            else -> n.toString()
        }

        private fun letters(n: Int): String {
            if (n <= 0) return ""
            val letter = 'A' + (n - 1) % 26
            return letter.toString().repeat((n - 1) / 26 + 1)
        }

        fun roman(n: Int): String {
            if (n <= 0 || n >= 4000) return n.toString()
            val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            var rest = n
            return buildString {
                values.forEachIndexed { i, v ->
                    while (rest >= v) {
                        append(symbols[i])
                        rest -= v
                    }
                }
            }
        }

        private fun ordinalSuffix(n: Int) = when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
    }
}
