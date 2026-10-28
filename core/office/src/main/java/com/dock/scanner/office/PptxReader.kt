package com.dock.scanner.office

/**
 * Reads PowerPoint presentations (.pptx). Slides inherit from their layout and master the
 * way PowerPoint does it: placeholders take their position and text styles from the matching
 * layout/master placeholder, theme colors go through the master's color map, and the
 * master's and layout's decorations are drawn behind each slide.
 */
internal class PptxReader(private val pkg: ZipPackage) {

    private class Part(val path: String, val root: XNode, val rels: Map<String, Relationship>)

    private inner class Master(val part: Part) {
        val theme: Theme = Theme.parse(part.rels.values.firstOrNull { it.type.endsWith("/theme") }?.target?.let(pkg::xml))
        val colorMap: Map<String, String> = part.root.child("p:clrMap")?.attrs?.mapKeys { it.key.substringAfter(':') } ?: DEFAULT_COLOR_MAP
        val txStyles: XNode? = part.root.child("p:txStyles")
        val tree: XNode? = part.root.path("p:cSld", "p:spTree")
        val background: XNode? = part.root.path("p:cSld", "p:bg")
        fun placeholder(type: String, idx: String?): XNode? = findPlaceholder(tree, type, idx, byIndex = false)
    }

    private inner class Layout(val part: Part, val master: Master) {
        val tree: XNode? = part.root.path("p:cSld", "p:spTree")
        val background: XNode? = part.root.path("p:cSld", "p:bg")
        val showMasterShapes = part.root.attr("showMasterSp") != "0"
        fun placeholder(type: String, idx: String?): XNode? = findPlaceholder(tree, type, idx, byIndex = true)
    }

    private val masters = HashMap<String, Master>()
    private val layouts = HashMap<String, Layout>()
    private var defaultTextStyle: XNode? = null

    fun read(): Presentation {
        val presPath = pkg.relationships("").values.firstOrNull { it.type.endsWith("/officeDocument") }?.target
            ?: "ppt/presentation.xml".takeIf(pkg::has)
            ?: throw UnreadableDocumentException("This isn't a PowerPoint presentation")
        val pres = pkg.xml(presPath) ?: throw UnreadableDocumentException("This presentation is empty")
        val rels = pkg.relationships(presPath)
        defaultTextStyle = pres.child("p:defaultTextStyle")
        val size = pres.child("p:sldSz")
        val width = size?.attr("cx")?.toLongOrNull() ?: 12_192_000L
        val height = size?.attr("cy")?.toLongOrNull() ?: 6_858_000L

        val slides = pres.child("p:sldIdLst")?.children("p:sldId").orEmpty().mapNotNull { id ->
            val path = id.attr("r:id")?.let { rels[it]?.target } ?: return@mapNotNull null
            runCatching { readSlide(path) }.getOrNull()
        }
        if (slides.isEmpty()) throw UnreadableDocumentException("This presentation has no slides")
        return Presentation(width, height, slides, pkg.images())
    }

    private fun part(path: String): Part? {
        val root = pkg.xml(path) ?: return null
        return Part(path, root, pkg.relationships(path))
    }

    private fun layoutOf(slide: Part): Layout? {
        val path = slide.rels.values.firstOrNull { it.type.endsWith("/slideLayout") }?.target ?: return null
        return layouts.getOrPut(path) {
            val part = part(path) ?: return null
            val masterPath = part.rels.values.firstOrNull { it.type.endsWith("/slideMaster") }?.target ?: return null
            val master = masters.getOrPut(masterPath) { Master(part(masterPath) ?: return null) }
            Layout(part, master)
        }
    }

    private fun readSlide(path: String): Slide {
        val slide = part(path) ?: throw UnreadableDocumentException("Missing slide")
        val layout = layoutOf(slide)
        val master = layout?.master
        val theme = master?.theme ?: Theme.Default
        val colorMap = slide.root.path("p:clrMapOvr", "a:overrideClrMapping")?.attrs?.mapKeys { it.key.substringAfter(':') }
            ?: master?.colorMap ?: DEFAULT_COLOR_MAP

        // Background: the slide's own, else the layout's, else the master's.
        var background: Int? = Colors.WHITE
        var backgroundImage: String? = null
        for ((bg, part) in listOf(slide.root.path("p:cSld", "p:bg") to slide, layout?.background to layout?.part, master?.background to master?.part)) {
            if (bg == null || part == null) continue
            val props = bg.child("p:bgPr")
            if (props != null) {
                val blip = props.child("a:blipFill")?.find("a:blip")?.attr("r:embed")
                if (blip != null) backgroundImage = part.rels[blip]?.target
                background = fillColor(props, theme, colorMap) ?: background
            } else {
                bg.child("p:bgRef")?.let { ref -> background = drawingColor(ref, theme, colorMap) ?: background }
            }
            break
        }

        val elements = ArrayList<SlideElement>()
        val showMaster = slide.root.attr("showMasterSp") != "0"
        if (layout != null && showMaster) {
            if (layout.showMasterShapes) {
                layout.master.tree?.let { readTree(it, Context(layout.master.part, theme, colorMap, layout, decoration = true), elements) }
            }
            layout.tree?.let { readTree(it, Context(layout.part, theme, colorMap, layout, decoration = true), elements) }
        }
        slide.root.path("p:cSld", "p:spTree")?.let { readTree(it, Context(slide, theme, colorMap, layout, decoration = false), elements) }
        return Slide(background, backgroundImage, elements)
    }

    private class Context(
        val part: Part,
        val theme: Theme,
        val colorMap: Map<String, String>,
        val layout: Layout?,
        /** Reading a layout or master: only its non-placeholder shapes appear on slides. */
        val decoration: Boolean,
        val transform: (Frame) -> Frame = { it },
    )

    private fun readTree(tree: XNode, ctx: Context, out: MutableList<SlideElement>) {
        for (node in tree.children) {
            if (node.find("p:cNvPr")?.attr("hidden").let { it == "1" || it == "true" }) continue
            when (node.name) {
                "p:sp" -> readShape(node, ctx)?.let(out::add)
                "p:pic" -> readPicture(node, ctx)?.let(out::add)
                "p:cxnSp" -> readShape(node, ctx)?.let(out::add)
                "p:graphicFrame" -> readGraphicFrame(node, ctx)?.let(out::add)
                "p:grpSp" -> readGroup(node, ctx, out)
                "mc:AlternateContent" -> {
                    val branch = node.children("mc:Choice").firstOrNull() ?: node.child("mc:Fallback")
                    branch?.let { readTree(it, ctx, out) }
                }
            }
        }
    }

    private fun readGroup(group: XNode, ctx: Context, out: MutableList<SlideElement>) {
        val xfrm = group.path("p:grpSpPr", "a:xfrm")
        val off = xfrm?.child("a:off")
        val ext = xfrm?.child("a:ext")
        val chOff = xfrm?.child("a:chOff")
        val chExt = xfrm?.child("a:chExt")
        val ox = off.long("x")
        val oy = off.long("y")
        val cx = chOff.long("x")
        val cy = chOff.long("y")
        val sx = ext.long("cx").toDouble() / chExt.long("cx").coerceAtLeast(1)
        val sy = ext.long("cy").toDouble() / chExt.long("cy").coerceAtLeast(1)
        val scaleX = if (chExt == null) 1.0 else sx
        val scaleY = if (chExt == null) 1.0 else sy
        val inner = Context(ctx.part, ctx.theme, ctx.colorMap, ctx.layout, ctx.decoration) { f ->
            ctx.transform(
                f.copy(
                    x = ox + ((f.x - cx) * scaleX).toLong(),
                    y = oy + ((f.y - cy) * scaleY).toLong(),
                    width = (f.width * scaleX).toLong(),
                    height = (f.height * scaleY).toLong(),
                ),
            )
        }
        readTree(group, inner, out)
    }

    // region Shapes

    private fun placeholderOf(node: XNode): XNode? =
        node.child("p:nvSpPr")?.path("p:nvPr", "p:ph")
            ?: node.child("p:nvPicPr")?.path("p:nvPr", "p:ph")
            ?: node.child("p:nvGraphicFramePr")?.path("p:nvPr", "p:ph")

    /** The layout and master placeholders a slide placeholder inherits from (master first). */
    private fun inherited(ph: XNode?, ctx: Context): List<XNode> {
        if (ph == null) return emptyList()
        val type = ph.attr("type") ?: "obj"
        val idx = ph.attr("idx")
        val layout = ctx.layout ?: return emptyList()
        return when (ctx.part) {
            layout.part -> listOfNotNull(layout.master.placeholder(type, idx))
            layout.master.part -> emptyList()
            else -> {
                val fromLayout = layout.placeholder(type, idx)
                val masterType = fromLayout?.let(::placeholderOf)?.attr("type") ?: type
                listOfNotNull(layout.master.placeholder(masterType, idx), fromLayout)
            }
        }
    }

    private fun frameOf(node: XNode, inherited: List<XNode>, ctx: Context): Frame? {
        val own = node.child("p:spPr")?.child("a:xfrm") ?: node.child("p:xfrm")
        val xfrm = own ?: inherited.asReversed().firstNotNullOfOrNull { it.child("p:spPr")?.child("a:xfrm") } ?: return null
        val off = xfrm.child("a:off")
        val ext = xfrm.child("a:ext")
        return ctx.transform(
            Frame(
                x = off.long("x"),
                y = off.long("y"),
                width = ext.long("cx"),
                height = ext.long("cy"),
                rotation = (xfrm.attr("rot")?.toFloatOrNull() ?: 0f) / 60_000f,
                flipH = xfrm.attr("flipH") == "1",
                flipV = xfrm.attr("flipV") == "1",
            ),
        )
    }

    private fun readShape(sp: XNode, ctx: Context): SlideElement? {
        val ph = placeholderOf(sp)
        // A layout's or master's placeholders are slots for slide content, not things to draw.
        if (ctx.decoration && ph != null) return null
        val inherited = inherited(ph, ctx)
        val frame = frameOf(sp, inherited, ctx) ?: return null
        val spPr = sp.child("p:spPr")
        val style = sp.child("p:style")

        // A shape filled with a picture is drawn as that picture.
        spPr?.child("a:blipFill")?.find("a:blip")?.attr("r:embed")?.let { id ->
            ctx.part.rels[id]?.target?.let { return PictureElement(frame, it) }
        }

        val prst = spPr?.child("a:prstGeom")?.attr("prst") ?: inherited.asReversed().firstNotNullOfOrNull { it.path("p:spPr", "a:prstGeom")?.attr("prst") }
        val geometry = when {
            sp.name == "p:cxnSp" -> Geometry.Line
            prst == null -> if (spPr?.child("a:custGeom") != null) Geometry.Other else Geometry.Rect
            prst == "rect" -> Geometry.Rect
            prst in setOf("roundRect", "round2SameRect", "snipRoundRect", "flowChartAlternateProcess") -> Geometry.RoundRect
            prst in setOf("ellipse", "flowChartConnector", "donut") -> Geometry.Ellipse
            prst in setOf("triangle", "rtTriangle") -> Geometry.Triangle
            prst in setOf("diamond", "flowChartDecision") -> Geometry.Diamond
            prst.contains("line", ignoreCase = true) || prst.contains("Connector") -> Geometry.Line
            else -> Geometry.Other
        }

        val fill = when {
            spPr?.child("a:noFill") != null -> null
            spPr != null && hasFill(spPr) -> fillColor(spPr, ctx.theme, ctx.colorMap)
            style != null -> style.child("a:fillRef")?.takeIf { (it.attr("idx")?.toIntOrNull() ?: 0) > 0 }?.let { drawingColor(it, ctx.theme, ctx.colorMap) }
            else -> inherited.asReversed().firstNotNullOfOrNull { it.child("p:spPr")?.takeIf(::hasFill)?.let { s -> fillColor(s, ctx.theme, ctx.colorMap) } }
        }
        val ln = spPr?.child("a:ln")
        val outline = when {
            ln?.child("a:noFill") != null -> null
            ln?.child("a:solidFill") != null -> drawingColor(ln.child("a:solidFill"), ctx.theme, ctx.colorMap)?.let { Outline(it, ln.attr("w")?.toLongOrNull() ?: 12_700) }
            style != null -> style.child("a:lnRef")?.takeIf { (it.attr("idx")?.toIntOrNull() ?: 0) > 0 }
                ?.let { drawingColor(it, ctx.theme, ctx.colorMap) }?.let { Outline(it, ln?.attr("w")?.toLongOrNull() ?: 12_700) }
            else -> null
        }
        val fontColor = style?.child("a:fontRef")?.let { drawingColor(it, ctx.theme, ctx.colorMap) }
        val text = sp.child("p:txBody")?.let { readTextBody(it, ph, inherited, ctx, fontColor) }?.takeIf { it.paragraphs.any { p -> p.spans.any { s -> s.text.isNotBlank() } } }

        if (fill == null && outline == null && text == null) return null
        return ShapeElement(frame, geometry, fill, outline, text)
    }

    private fun hasFill(spPr: XNode) = spPr.children.any { it.name in FILLS }

    private fun fillColor(props: XNode, theme: Theme, colorMap: Map<String, String>): Int? {
        props.child("a:solidFill")?.let { return drawingColor(it, theme, colorMap) }
        props.child("a:gradFill")?.let { grad ->
            // A gradient's middle stop is a fair single-color stand-in.
            val stops = grad.child("a:gsLst")?.children("a:gs").orEmpty()
            return stops.getOrNull(stops.size / 2)?.let { drawingColor(it, theme, colorMap) }
        }
        props.child("a:pattFill")?.let { return drawingColor(it.child("a:fgClr"), theme, colorMap) }
        return null
    }

    private fun readPicture(pic: XNode, ctx: Context): SlideElement? {
        val ph = placeholderOf(pic)
        if (ctx.decoration && ph != null) return null
        val frame = frameOf(pic, inherited(ph, ctx), ctx) ?: return null
        val id = pic.child("p:blipFill")?.find("a:blip")?.attr("r:embed") ?: return null
        val target = ctx.part.rels[id]?.target ?: return null
        return PictureElement(frame, target)
    }

    private fun readGraphicFrame(gf: XNode, ctx: Context): SlideElement? {
        val ph = placeholderOf(gf)
        if (ctx.decoration && ph != null) return null
        val frame = frameOf(gf, inherited(ph, ctx), ctx) ?: return null
        val data = gf.path("a:graphic", "a:graphicData") ?: return null
        data.child("a:tbl")?.let { return readTable(it, frame, ctx) }
        val uri = data.attr("uri").orEmpty()
        val label = when {
            "chart" in uri -> "Chart"
            "diagram" in uri -> "SmartArt"
            else -> "Object"
        }
        // Embedded objects often carry a picture of themselves.
        data.find("a:blip")?.attr("r:embed")?.let { id -> ctx.part.rels[id]?.target?.let { return PictureElement(frame, it) } }
        return PlaceholderElement(frame, label)
    }

    private fun readTable(tbl: XNode, frame: Frame, ctx: Context): SlideElement {
        val widths = tbl.child("a:tblGrid")?.children("a:gridCol")?.map { it.attr("w")?.toLongOrNull() ?: 0L }.orEmpty()
        val props = tbl.child("a:tblPr")
        val headerRow = props?.attr("firstRow") == "1"
        val banded = props?.attr("bandRow") == "1"
        val accent = ctx.theme.colors["accent1"] ?: Colors.BLACK
        val rows = tbl.children("a:tr").mapIndexed { r, tr ->
            SlideTableRow(
                height = tr.attr("h")?.toLongOrNull() ?: 370_840L,
                cells = tr.children("a:tc").filter { it.attr("hMerge") != "1" }.map { tc ->
                    val isHeader = headerRow && r == 0
                    // PowerPoint's default table style: accent header, light banded rows.
                    val styleFill = when {
                        isHeader -> accent
                        banded -> Colors.tint(accent, if ((r - if (headerRow) 1 else 0) % 2 == 0) 0.2f else 0.4f)
                        else -> null
                    }
                    val tcPr = tc.child("a:tcPr")
                    val fill = when {
                        tcPr?.child("a:noFill") != null -> null
                        tcPr != null && hasFill(tcPr) -> fillColor(tcPr, ctx.theme, ctx.colorMap)
                        else -> styleFill
                    }
                    val textColor = if (isHeader && tcPr?.let(::hasFill) != true) Colors.WHITE else null
                    SlideTableCell(
                        text = tc.child("a:txBody")?.let { readTextBody(it, null, emptyList(), ctx, textColor, forceBold = isHeader) }
                            ?: TextFrame(emptyList()),
                        fill = fill,
                        columnSpan = tc.attr("gridSpan")?.toIntOrNull() ?: 1,
                    )
                },
            )
        }
        return TableElement(frame, widths, rows)
    }

    // endregion

    // region Text

    private data class Bullet(val char: String? = null, val autoNum: String? = null, val start: Int = 1, val none: Boolean = false)

    private data class LevelStyle(
        val align: Align? = null,
        val marginLeft: Long? = null,
        val indent: Long? = null,
        val size: Float? = null,
        val bold: Boolean? = null,
        val italic: Boolean? = null,
        val underline: Boolean? = null,
        val strike: Boolean? = null,
        val color: Int? = null,
        val font: String? = null,
        val caps: Boolean? = null,
        val bullet: Bullet? = null,
        val bulletColor: Int? = null,
        val spaceBefore: Pair<Float, Boolean>? = null, // (amount, isPercent)
        val spaceAfter: Pair<Float, Boolean>? = null,
        val lineSpacing: Float? = null,
    ) {
        fun merge(o: LevelStyle?): LevelStyle = if (o == null) this else LevelStyle(
            o.align ?: align, o.marginLeft ?: marginLeft, o.indent ?: indent, o.size ?: size, o.bold ?: bold,
            o.italic ?: italic, o.underline ?: underline, o.strike ?: strike, o.color ?: color, o.font ?: font,
            o.caps ?: caps, o.bullet ?: bullet, o.bulletColor ?: bulletColor, o.spaceBefore ?: spaceBefore,
            o.spaceAfter ?: spaceAfter, o.lineSpacing ?: lineSpacing,
        )
    }

    private fun levelStyle(pPr: XNode?, ctx: Context): LevelStyle? {
        pPr ?: return null
        val def = pPr.child("a:defRPr")
        val run = def?.let { runStyle(it, ctx) } ?: LevelStyle()
        fun spacing(node: XNode?): Pair<Float, Boolean>? {
            node ?: return null
            node.child("a:spcPts")?.attr("val")?.toFloatOrNull()?.let { return it / 100f to false }
            node.child("a:spcPct")?.attr("val")?.toFloatOrNull()?.let { return it / 100_000f to true }
            return null
        }
        val bullet = when {
            pPr.child("a:buNone") != null -> Bullet(none = true)
            pPr.child("a:buChar") != null -> {
                val raw = pPr.child("a:buChar")?.attr("char") ?: "•"
                val font = pPr.child("a:buFont")?.attr("typeface").orEmpty()
                Bullet(char = if (font.startsWith("Wingdings", ignoreCase = true)) wingdings(raw) else DocxReader.bullet(raw))
            }
            pPr.child("a:buAutoNum") != null -> pPr.child("a:buAutoNum")!!.let {
                Bullet(autoNum = it.attr("type") ?: "arabicPeriod", start = it.attr("startAt")?.toIntOrNull() ?: 1)
            }
            else -> null
        }
        return run.copy(
            align = when (pPr.attr("algn")) {
                "ctr" -> Align.Center
                "r" -> Align.End
                "just", "dist", "justLow" -> Align.Justify
                "l" -> Align.Start
                else -> null
            },
            marginLeft = pPr.attr("marL")?.toLongOrNull(),
            indent = pPr.attr("indent")?.toLongOrNull(),
            bullet = bullet,
            bulletColor = pPr.child("a:buClr")?.let { drawingColor(it, ctx.theme, ctx.colorMap) },
            spaceBefore = spacing(pPr.child("a:spcBef")),
            spaceAfter = spacing(pPr.child("a:spcAft")),
            lineSpacing = pPr.child("a:lnSpc")?.child("a:spcPct")?.attr("val")?.toFloatOrNull()?.div(100_000f),
        )
    }

    private fun runStyle(rPr: XNode, ctx: Context): LevelStyle {
        val typeface = rPr.child("a:latin")?.attr("typeface")?.let {
            when (it) {
                "+mj-lt" -> ctx.theme.majorFont
                "+mn-lt" -> ctx.theme.minorFont
                else -> it
            }
        }
        return LevelStyle(
            size = rPr.attr("sz")?.toFloatOrNull()?.div(100f),
            bold = rPr.attr("b")?.let { it == "1" || it == "true" },
            italic = rPr.attr("i")?.let { it == "1" || it == "true" },
            underline = rPr.attr("u")?.let { it != "none" },
            strike = rPr.attr("strike")?.let { it != "noStrike" },
            color = rPr.child("a:solidFill")?.let { drawingColor(it, ctx.theme, ctx.colorMap) },
            font = typeface,
            caps = rPr.attr("cap")?.let { it == "all" },
        )
    }

    /**
     * The style a paragraph's level gets from everything above it, lowest priority first.
     * [fontColor] is the shape style's text color (e.g. white on a filled shape): it beats
     * the presentation and master defaults but not the shape's own formatting.
     */
    private fun levelChain(level: Int, ph: XNode?, inherited: List<XNode>, body: XNode, ctx: Context, fontColor: Int?): LevelStyle {
        val key = "a:lvl${level + 1}pPr"
        val master = ctx.layout?.master?.txStyles
        // Plain shapes use the presentation's defaults only.
        val masterStyle = when (ph?.let { it.attr("type") ?: "obj" }) {
            null -> null
            "title", "ctrTitle" -> master?.child("p:titleStyle")
            "dt", "ftr", "sldNum", "hdr" -> master?.child("p:otherStyle")
            else -> master?.child("p:bodyStyle")
        }
        var style = LevelStyle()
        style = style.merge(levelStyle(defaultTextStyle?.child(key), ctx))
        style = style.merge(levelStyle(masterStyle?.child(key), ctx))
        if (fontColor != null) style = style.copy(color = fontColor)
        for (node in inherited) style = style.merge(levelStyle(node.path("p:txBody", "a:lstStyle", key), ctx))
        style = style.merge(levelStyle(body.child("a:lstStyle")?.child(key), ctx))
        return style
    }

    private fun readTextBody(body: XNode, ph: XNode?, inherited: List<XNode>, ctx: Context, fontColor: Int?, forceBold: Boolean = false): TextFrame {
        // Body properties: the master's, then the layout's, then the shape's own.
        var anchor: String? = null
        val insets = mutableListOf(91_440L, 45_720L, 91_440L, 45_720L)
        var wrap = true
        var fontScale = 1f
        for (node in inherited.map { it.path("p:txBody", "a:bodyPr") } + body.child("a:bodyPr")) {
            node ?: continue
            node.attr("anchor")?.let { anchor = it }
            listOf("lIns", "tIns", "rIns", "bIns").forEachIndexed { i, k -> node.attr(k)?.toLongOrNull()?.let { insets[i] = it } }
            node.attr("wrap")?.let { wrap = it != "none" }
            node.child("a:normAutofit")?.let { fontScale = (it.attr("fontScale")?.toFloatOrNull() ?: 100_000f) / 100_000f }
        }

        val counters = IntArray(9)
        val paragraphs = body.children("a:p").map { p ->
            val pPr = p.child("a:pPr")
            val level = (pPr?.attr("lvl")?.toIntOrNull() ?: 0).coerceIn(0, 8)
            var style = levelChain(level, ph, inherited, body, ctx, fontColor)
            style = style.merge(levelStyle(pPr, ctx))
            if (forceBold) style = style.copy(bold = style.bold ?: true)

            val spans = ArrayList<TextSpan>()
            for (node in p.children) {
                when (node.name) {
                    "a:r", "a:fld" -> {
                        val text = node.child("a:t")?.text.orEmpty()
                        val rPr = node.child("a:rPr")
                        var s = style.merge(rPr?.let { runStyle(it, ctx) })
                        var link: String? = null
                        rPr?.child("a:hlinkClick")?.attr("r:id")?.let { id ->
                            link = ctx.part.rels[id]?.target
                            s = s.copy(underline = true, color = s.color ?: ctx.theme.colors["hlink"])
                        }
                        var ts = textStyle(s, ctx)
                        rPr?.attr("baseline")?.toIntOrNull()?.let { b ->
                            ts = ts.copy(baseline = if (b > 0) Baseline.Superscript else if (b < 0) Baseline.Subscript else Baseline.Normal)
                        }
                        rPr?.child("a:highlight")?.let { drawingColor(it, ctx.theme, ctx.colorMap) }?.let { ts = ts.copy(background = it) }
                        spans += TextSpan(text, ts.copy(link = link))
                    }
                    "a:br" -> spans += TextSpan("\n", textStyle(style.merge(node.child("a:rPr")?.let { runStyle(it, ctx) }), ctx))
                }
            }
            val hasText = spans.any { it.text.isNotBlank() }
            val bullet = style.bullet
            val marker = when {
                !hasText || bullet == null || bullet.none -> {
                    if (bullet?.autoNum == null) counters[level] = 0
                    null
                }
                bullet.autoNum != null -> {
                    if (counters[level] == 0) counters[level] = bullet.start - 1
                    counters[level]++
                    for (deeper in level + 1 until counters.size) counters[deeper] = 0
                    autoNumber(counters[level], bullet.autoNum)
                }
                else -> bullet.char
            }
            val size = style.size ?: 18f
            SlideParagraph(
                spans = spans,
                align = style.align ?: Align.Start,
                bullet = marker,
                bulletColor = style.bulletColor,
                marginLeft = style.marginLeft ?: 0L,
                indent = style.indent ?: 0L,
                spaceBefore = style.spaceBefore?.let { (v, pct) -> if (pct) v * size * 1.2f else v } ?: 0f,
                spaceAfter = style.spaceAfter?.let { (v, pct) -> if (pct) v * size * 1.2f else v } ?: 0f,
                lineSpacing = style.lineSpacing ?: 1f,
                emptySize = p.child("a:endParaRPr")?.attr("sz")?.toFloatOrNull()?.div(100f) ?: size,
            )
        }
        return TextFrame(
            paragraphs = paragraphs,
            anchor = when (anchor) {
                "ctr" -> Anchor.Middle
                "b" -> Anchor.Bottom
                else -> Anchor.Top
            },
            insets = insets,
            fontScale = fontScale,
            wrap = wrap,
        )
    }

    private fun textStyle(s: LevelStyle, ctx: Context) = TextStyle(
        size = s.size ?: 18f,
        bold = s.bold ?: false,
        italic = s.italic ?: false,
        underline = s.underline ?: false,
        strike = s.strike ?: false,
        color = s.color ?: ctx.theme.colors[ctx.colorMap["tx1"] ?: "dk1"],
        font = fontKind(s.font ?: ctx.theme.minorFont),
        allCaps = s.caps ?: false,
    )

    // endregion

    companion object {
        private val FILLS = setOf("a:solidFill", "a:gradFill", "a:pattFill", "a:blipFill", "a:noFill")

        private fun XNode?.long(name: String): Long = this?.attr(name)?.toLongOrNull() ?: 0L

        /** Finds a placeholder by index (layouts) or by type (masters), falling back to type. */
        private fun findPlaceholder(tree: XNode?, type: String, idx: String?, byIndex: Boolean): XNode? {
            tree ?: return null
            val shapes = tree.children.filter { it.name == "p:sp" || it.name == "p:pic" || it.name == "p:graphicFrame" }
            fun ph(n: XNode) = n.child("p:nvSpPr")?.path("p:nvPr", "p:ph") ?: n.child("p:nvPicPr")?.path("p:nvPr", "p:ph")
                ?: n.child("p:nvGraphicFramePr")?.path("p:nvPr", "p:ph")
            fun typeOf(n: XNode) = ph(n)?.let { it.attr("type") ?: "obj" }
            val wanted = when (type) {
                "ctrTitle" -> listOf("ctrTitle", "title")
                "subTitle" -> listOf("subTitle", "body")
                "obj", "body", "tbl", "chart", "dgm", "media", "clipArt", "pic" -> listOf(type, "body", "obj")
                else -> listOf(type)
            }
            if (byIndex && idx != null) shapes.firstOrNull { ph(it)?.attr("idx") == idx }?.let { return it }
            for (t in wanted) shapes.firstOrNull { typeOf(it) == t }?.let { return it }
            if (!byIndex && type != "title" && type != "ctrTitle") return shapes.firstOrNull { typeOf(it) == "body" }
            return null
        }

        /** Common Wingdings bullets, which aren't on the phone. */
        private fun wingdings(c: String): String = when (c.firstOrNull()) {
            'Ø' -> "➢"
            '§' -> "▪"
            'ü' -> "✓"
            'q' -> "❑"
            'n' -> "■"
            'l' -> "●"
            'v' -> "❖"
            'ð' -> "➔"
            else -> "•"
        }

        fun autoNumber(n: Int, type: String): String {
            val body = when {
                type.startsWith("alphaLc") -> DocxReader.format(n, "lowerLetter")
                type.startsWith("alphaUc") -> DocxReader.format(n, "upperLetter")
                type.startsWith("romanLc") -> DocxReader.format(n, "lowerRoman")
                type.startsWith("romanUc") -> DocxReader.format(n, "upperRoman")
                else -> n.toString()
            }
            return when {
                type.endsWith("ParenBoth") -> "($body)"
                type.endsWith("ParenR") -> "$body)"
                type.endsWith("Plain") -> body
                else -> "$body."
            }
        }
    }
}
