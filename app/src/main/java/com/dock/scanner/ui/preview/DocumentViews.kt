package com.dock.scanner.ui.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dock.scanner.office.Align
import com.dock.scanner.office.Anchor
import com.dock.scanner.office.Baseline
import com.dock.scanner.office.Block
import com.dock.scanner.office.FontKind
import com.dock.scanner.office.Geometry
import com.dock.scanner.office.ImageSpan
import com.dock.scanner.office.ImageStore
import com.dock.scanner.office.Paragraph
import com.dock.scanner.office.PictureElement
import com.dock.scanner.office.PlaceholderElement
import com.dock.scanner.office.Presentation
import com.dock.scanner.office.ShapeElement
import com.dock.scanner.office.Slide
import com.dock.scanner.office.SlideElement
import com.dock.scanner.office.SlideParagraph
import com.dock.scanner.office.Table
import com.dock.scanner.office.TableElement
import com.dock.scanner.office.TextDocument
import com.dock.scanner.office.TextFrame
import com.dock.scanner.office.TextSpan
import com.dock.scanner.office.TextStyle
import com.dock.scanner.ui.components.ZoomableColumn
import com.dock.scanner.ui.theme.DockColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private val Ink = Color(0xFF1B1B1B)
private val Hairline = Color(0xFFB8B8B8)

/**
 * A margin, indent or spacing from the document as padding. Files can hold negative values
 * (Word's negative top margins and table indents, or a damaged file), which Compose's padding
 * rejects with an exception, so they become zero.
 */
private fun gap(dp: Float): Dp = dp.coerceAtLeast(0f).dp

/** Paper and slide sizes, with a damaged file's zero or negative size replaced by the usual one. */
private val TextDocument.paperWidth: Float get() = pageWidth.takeIf { it > 0f } ?: 612f
private val Presentation.slideWidth: Long get() = width.takeIf { it > 0 } ?: 12_192_000L
private val Presentation.slideHeight: Long get() = height.takeIf { it > 0 } ?: 6_858_000L

// region Text documents

/** Part of a page: long pages are split so the list only composes what's on screen. */
private class Segment(val blocks: List<Block>, val first: Boolean, val last: Boolean)

private const val BLOCKS_PER_SEGMENT = 30

/** Pages on paper, the width of the screen, zoomable. Text keeps its size relative to the page. */
@Composable
fun TextDocumentView(doc: TextDocument, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val segments = remember(doc) {
        doc.pages.flatMap { page ->
            val chunks = page.chunked(BLOCKS_PER_SEGMENT).ifEmpty { listOf(emptyList()) }
            chunks.mapIndexed { i, chunk -> Segment(chunk, first = i == 0, last = i == chunks.lastIndex) }
        }
    }
    ZoomableColumn(modifier = modifier, contentPadding = contentPadding, spacing = 0.dp) { pageWidth ->
        val scale = pageWidth.value / doc.paperWidth // dp per point
        items(segments.size) { i -> PageSegment(segments[i], doc, scale) }
    }
}

@Composable
private fun PageSegment(segment: Segment, doc: TextDocument, scale: Float) {
    val radius = 4.dp
    val shape = RoundedCornerShape(
        topStart = if (segment.first) radius else 0.dp,
        topEnd = if (segment.first) radius else 0.dp,
        bottomStart = if (segment.last) radius else 0.dp,
        bottomEnd = if (segment.last) radius else 0.dp,
    )
    val wholePage = segment.first && segment.last
    val m = doc.margins
    Column {
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (wholePage) Modifier.heightIn(min = gap(doc.pageHeight * scale)).shadow(3.dp, shape) else Modifier)
                .clip(shape)
                .background(DockColors.Paper)
                .padding(
                    start = gap(m.left * scale),
                    end = gap(m.right * scale),
                    top = if (segment.first) gap(m.top * scale) else 0.dp,
                    bottom = if (segment.last) gap(m.bottom * scale) else 0.dp,
                ),
        ) {
            val width = ((doc.paperWidth - m.left - m.right) * scale).dp
            segment.blocks.forEach { BlockView(it, scale, doc.images, width) }
        }
        if (segment.last) Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun BlockView(block: Block, scale: Float, images: ImageStore?, width: Dp) {
    when (block) {
        is Paragraph -> ParagraphView(block, scale, images, width)
        is Table -> TableView(block, scale, images, width)
    }
}

@Composable
private fun ParagraphView(p: Paragraph, scale: Float, images: ImageStore?, width: Dp) {
    val density = LocalDensity.current
    fun sp(points: Float): TextUnit = with(density) { (points * scale).dp.toSp() }
    val textSpans = p.spans.filterIsInstance<TextSpan>()
    val largest = textSpans.maxOfOrNull { it.style.size } ?: 11f
    val lineHeight = sp(largest * 1.17f * p.lineSpacing.coerceIn(0.7f, 3f))
    val align = p.align.toTextAlign()
    val hanging = (-p.firstLineIndent).coerceAtLeast(0f)
    val markerWidth = if (p.marker != null) max(hanging, 18f) else 0f
    val start = if (p.marker != null) max(0f, p.indent - markerWidth) else max(0f, p.indent + min(0f, p.firstLineIndent))
    val indent = if (p.marker == null) {
        if (p.firstLineIndent >= 0) TextIndent(firstLine = sp(p.firstLineIndent)) else TextIndent(restLine = sp(hanging))
    } else {
        TextIndent.None
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = gap(p.spaceBefore * scale), bottom = gap(p.spaceAfter * scale))
            .then(p.background?.let { Modifier.background(Color(it)) } ?: Modifier)
            .padding(start = gap(start * scale)),
    ) {
        p.marker?.let { marker ->
            val style = p.markerStyle ?: textSpans.firstOrNull()?.style ?: TextStyle()
            Text(
                annotated(listOf(TextSpan(marker, style)), scale, density),
                lineHeight = lineHeight,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width((markerWidth * scale).dp),
            )
        }
        Column(Modifier.weight(1f)) {
            // Text runs flow together; pictures sit on lines of their own.
            var run = ArrayList<TextSpan>()
            val parts = ArrayList<Any>()
            for (span in p.spans) {
                when (span) {
                    is TextSpan -> run += span
                    is ImageSpan -> {
                        if (run.isNotEmpty()) parts.add(run)
                        run = ArrayList()
                        parts.add(span)
                    }
                }
            }
            if (run.isNotEmpty() || parts.isEmpty()) parts.add(run)
            for (part in parts) {
                if (part is ImageSpan) {
                    DocumentImage(images, part.ref, part.width, part.height, scale, width, align)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val spans = part as List<TextSpan>
                    Text(
                        annotated(spans.ifEmpty { listOf(TextSpan("", textSpans.firstOrNull()?.style ?: TextStyle())) }, scale, density),
                        style = androidx.compose.ui.text.TextStyle(textIndent = indent, lineHeight = lineHeight),
                        textAlign = align,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun Align.toTextAlign() = when (this) {
    Align.Start -> TextAlign.Start
    Align.Center -> TextAlign.Center
    Align.End -> TextAlign.End
    Align.Justify -> TextAlign.Justify
}

/** Text spans as styled text, sized relative to the page ([scale] dp per point). */
private fun annotated(spans: List<TextSpan>, scale: Float, density: androidx.compose.ui.unit.Density, sizeFactor: Float = 1f): AnnotatedString =
    buildAnnotatedString {
        for (span in spans) {
            val s = span.style
            val small = s.baseline != Baseline.Normal
            val size = with(density) { (s.size * sizeFactor * scale * if (small) 0.7f else 1f).dp.toSp() }
            val decoration = when {
                s.underline && s.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                s.underline -> TextDecoration.Underline
                s.strike -> TextDecoration.LineThrough
                else -> null
            }
            withStyle(
                SpanStyle(
                    color = s.color?.let { Color(it) } ?: Ink,
                    fontSize = size,
                    fontWeight = if (s.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (s.italic) FontStyle.Italic else FontStyle.Normal,
                    fontFamily = when (s.font) {
                        FontKind.Serif -> FontFamily.Serif
                        FontKind.Mono -> FontFamily.Monospace
                        FontKind.Sans -> FontFamily.SansSerif
                    },
                    textDecoration = decoration,
                    background = s.background?.let { Color(it) } ?: Color.Unspecified,
                    baselineShift = when (s.baseline) {
                        Baseline.Superscript -> BaselineShift.Superscript
                        Baseline.Subscript -> BaselineShift.Subscript
                        Baseline.Normal -> null
                    },
                ),
            ) {
                append(if (s.allCaps) span.text.uppercase() else span.text)
            }
        }
    }

@Composable
private fun TableView(table: Table, scale: Float, images: ImageStore?, width: Dp) {
    val total = table.columnWidths.sum().coerceAtLeast(1f)
    // Word often indents tables slightly to the left of the margin (a negative indent); they start at the margin here.
    val indent = (table.indent * scale).coerceAtLeast(0f)
    // Tables wider than the text area are scaled down to fit it.
    val fit = min(1f, (width.value - indent).coerceAtLeast(1f) / (total * scale))
    Column(Modifier.padding(start = indent.dp, top = 2.dp, bottom = 2.dp)) {
        for (row in table.rows) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                var col = 0
                for (cell in row.cells) {
                    val span = cell.columnSpan.coerceAtLeast(1)
                    val w = table.columnWidths.subList(col.coerceAtMost(table.columnWidths.size), (col + span).coerceAtMost(table.columnWidths.size)).sum()
                        .takeIf { it > 0 } ?: (total / max(1, table.columnWidths.size))
                    col += span
                    val cellWidth = (w * scale * fit).dp
                    Box(
                        Modifier
                            .width(cellWidth)
                            .fillMaxHeight()
                            .then(cell.background?.let { Modifier.background(Color(it)) } ?: Modifier)
                            .then(if (table.borders) Modifier.border(0.5.dp, Hairline) else Modifier),
                    ) {
                        if (!cell.mergedWithAbove) {
                            Column(Modifier.padding(horizontal = (5.4f * scale).dp, vertical = (1f * scale).dp)) {
                                val inner = cellWidth - (10.8f * scale).dp
                                cell.blocks.forEach { BlockView(it, scale * fit, images, inner) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// endregion

// region Images

private val imageCache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

/** A picture from a document, decoded at about [targetWidthPx]; null while loading or if it can't be shown. */
@Composable
private fun rememberDocumentImage(images: ImageStore?, ref: String, targetWidthPx: Int): ImageBitmap? {
    val key = "${System.identityHashCode(images)}:$ref:${Integer.highestOneBit(targetWidthPx.coerceAtLeast(1))}"
    val bitmap by produceState(imageCache.get(key), key) {
        if (value != null || images == null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = images.bytes(ref) ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetWidthPx && sample < 64) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()?.also { imageCache.put(key, it) }
        }
    }
    return remember(bitmap) { bitmap?.asImageBitmap() }
}

@Composable
private fun DocumentImage(images: ImageStore?, ref: String, widthPt: Float, heightPt: Float, scale: Float, maxWidth: Dp, align: TextAlign) {
    val density = LocalDensity.current
    var w = widthPt * scale
    var h = heightPt * scale
    if (w > maxWidth.value) {
        h *= maxWidth.value / w
        w = maxWidth.value
    }
    // Decoded sharper than shown, for zooming in.
    val image = rememberDocumentImage(images, ref, with(density) { (w * 2).dp.roundToPx() })
    Box(
        Modifier.fillMaxWidth(),
        contentAlignment = when (align) {
            TextAlign.Center -> Alignment.Center
            TextAlign.End -> Alignment.CenterEnd
            else -> Alignment.CenterStart
        },
    ) {
        Box(Modifier.size(w.dp, h.dp).background(if (image == null) Color(0x14000000) else Color.Transparent)) {
            image?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
        }
    }
}

// endregion

// region Presentations

/** Every slide, one under the other, zoomable. */
@Composable
fun PresentationView(pres: Presentation, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    ZoomableColumn(modifier = modifier, contentPadding = contentPadding, spacing = 16.dp) { pageWidth ->
        val scale = pageWidth.value / pres.slideWidth // dp per EMU
        items(pres.slides.size) { i -> SlideView(pres.slides[i], pres, scale) }
    }
}

@Composable
private fun SlideView(slide: Slide, pres: Presentation, scale: Float) {
    val shape = MaterialTheme.shapes.extraSmall
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(pres.slideWidth.toFloat() / pres.slideHeight)
            .shadow(3.dp, shape)
            .clip(shape)
            .background(slide.background?.let { Color(it) } ?: Color.White),
    ) {
        slide.backgroundImage?.let { ref ->
            val image = rememberDocumentImage(pres.images, ref, with(LocalDensity.current) { (pres.slideWidth * scale).dp.roundToPx() })
            image?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        slide.elements.forEach { ElementView(it, scale, pres.images) }
    }
}

@Composable
private fun ElementView(e: SlideElement, scale: Float, images: ImageStore?) {
    val f = e.frame
    val w = (f.width * scale).coerceAtLeast(0f)
    val h = (f.height * scale).coerceAtLeast(0f)
    Box(
        Modifier
            .offset((f.x * scale).dp, (f.y * scale).dp)
            .size(w.dp, h.dp)
            .graphicsLayer { rotationZ = f.rotation },
    ) {
        when (e) {
            is ShapeElement -> {
                if (e.fill != null || e.outline != null) {
                    val stroke = e.outline?.let { with(LocalDensity.current) { (it.width * scale).dp.toPx().coerceAtLeast(1f) } } ?: 0f
                    Canvas(Modifier.fillMaxSize().graphicsLayer { scaleX = if (f.flipH) -1f else 1f; scaleY = if (f.flipV) -1f else 1f }) {
                        drawGeometry(e.geometry, e.fill?.let { Color(it) }, e.outline?.let { Color(it.color) }, stroke)
                    }
                }
                e.text?.let { TextFrameView(it, scale) }
            }
            is PictureElement -> {
                val image = rememberDocumentImage(images, e.ref, with(LocalDensity.current) { (w * 2).dp.roundToPx() })
                if (image != null) {
                    Image(
                        image,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = if (f.flipH) -1f else 1f; scaleY = if (f.flipV) -1f else 1f },
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(Color(0x14000000)))
                }
            }
            is TableElement -> SlideTableView(e, scale)
            is PlaceholderElement -> Box(
                Modifier.fillMaxSize().background(Color(0x0F000000)).border(1.dp, Color(0x33000000)),
                contentAlignment = Alignment.Center,
            ) {
                Text(e.label, color = Color(0xFF6B6B6B), fontSize = with(LocalDensity.current) { (h / 8).coerceIn(6f, 18f).dp.toSp() })
            }
        }
    }
}

private fun DrawScope.drawGeometry(geometry: Geometry, fill: Color?, line: Color?, stroke: Float) {
    val inset = stroke / 2
    val topLeft = Offset(inset, inset)
    val box = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f))
    fun both(draw: (Color, DrawStyleKind) -> Unit) {
        fill?.let { draw(it, DrawStyleKind.Fill) }
        line?.let { draw(it, DrawStyleKind.Stroke) }
    }
    val strokeStyle = Stroke(stroke)
    when (geometry) {
        Geometry.Line -> line?.let { drawLine(it, Offset.Zero, Offset(size.width, size.height), stroke) }
        Geometry.Ellipse -> both { c, k -> if (k == DrawStyleKind.Fill) drawOval(c, topLeft, box) else drawOval(c, topLeft, box, style = strokeStyle) }
        Geometry.RoundRect -> {
            val r = androidx.compose.ui.geometry.CornerRadius(min(size.width, size.height) * 0.16f)
            both { c, k -> if (k == DrawStyleKind.Fill) drawRoundRect(c, topLeft, box, r) else drawRoundRect(c, topLeft, box, r, style = strokeStyle) }
        }
        Geometry.Triangle, Geometry.Diamond -> {
            val path = Path().apply {
                if (geometry == Geometry.Triangle) {
                    moveTo(size.width / 2, inset); lineTo(size.width - inset, size.height - inset); lineTo(inset, size.height - inset)
                } else {
                    moveTo(size.width / 2, inset); lineTo(size.width - inset, size.height / 2); lineTo(size.width / 2, size.height - inset); lineTo(inset, size.height / 2)
                }
                close()
            }
            both { c, k -> if (k == DrawStyleKind.Fill) drawPath(path, c) else drawPath(path, c, style = strokeStyle) }
        }
        Geometry.Rect, Geometry.Other -> both { c, k -> if (k == DrawStyleKind.Fill) drawRect(c, topLeft, box) else drawRect(c, topLeft, box, style = strokeStyle) }
    }
}

private enum class DrawStyleKind { Fill, Stroke }

/** Text in a shape: inset, anchored top/middle/bottom, allowed to overflow like PowerPoint does. */
@Composable
private fun TextFrameView(frame: TextFrame, scale: Float) {
    val i = frame.insets
    Box(
        Modifier
            .fillMaxSize()
            .padding(start = gap(i[0] * scale), top = gap(i[1] * scale), end = gap(i[2] * scale), bottom = gap(i[3] * scale)),
        contentAlignment = when (frame.anchor) {
            Anchor.Top -> Alignment.TopStart
            Anchor.Middle -> Alignment.CenterStart
            Anchor.Bottom -> Alignment.BottomStart
        },
    ) {
        Column(Modifier.fillMaxWidth().wrapContentHeight(unbounded = true)) {
            frame.paragraphs.forEachIndexed { index, p -> SlideParagraphView(p, scale, frame.fontScale, frame.wrap, first = index == 0) }
        }
    }
}

private const val EMU_PER_POINT = 12_700f

@Composable
private fun SlideParagraphView(p: SlideParagraph, scale: Float, fontScale: Float, wrap: Boolean, first: Boolean) {
    val density = LocalDensity.current
    val pointScale = scale * EMU_PER_POINT // dp per point on this slide
    val hasText = p.spans.any { it.text.isNotEmpty() }
    val largest = p.spans.maxOfOrNull { it.style.size } ?: p.emptySize
    val lineHeight = with(density) { (largest * fontScale * pointScale * 1.2f * p.lineSpacing.coerceIn(0.6f, 3f)).dp.toSp() }
    val align = p.align.toTextAlign()
    val bulletWidth = if (p.bullet != null) max(-p.indent.toFloat(), largest * EMU_PER_POINT * 0.6f) else 0f
    val start = if (p.bullet != null) max(0f, (p.marginLeft - bulletWidth)) else p.marginLeft.toFloat()
    val textIndent = if (p.bullet == null && p.indent != 0L) {
        val v = with(density) { (p.indent * scale).dp.toSp() }
        if (p.indent > 0) TextIndent(firstLine = v) else TextIndent(restLine = with(density) { (-p.indent * scale).dp.toSp() })
    } else {
        TextIndent.None
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = if (first) 0.dp else gap(p.spaceBefore * pointScale), bottom = gap(p.spaceAfter * pointScale))
            .padding(start = gap(start * scale)),
    ) {
        p.bullet?.let { bullet ->
            val style = p.spans.firstOrNull()?.style ?: TextStyle(size = p.emptySize)
            Text(
                annotated(listOf(TextSpan(bullet, style.copy(color = p.bulletColor ?: style.color, bold = false, underline = false))), pointScale, density, fontScale),
                lineHeight = lineHeight,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width((bulletWidth * scale).dp),
            )
        }
        val spans = if (hasText) p.spans else listOf(TextSpan("", TextStyle(size = p.emptySize)))
        Text(
            annotated(spans, pointScale, density, fontScale),
            style = androidx.compose.ui.text.TextStyle(textIndent = textIndent, lineHeight = lineHeight),
            textAlign = align,
            softWrap = wrap,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SlideTableView(table: TableElement, scale: Float) {
    val total = table.columnWidths.sum().coerceAtLeast(1)
    val fit = table.frame.width.toFloat() / total
    Column(Modifier.fillMaxWidth()) {
        for (row in table.rows) {
            Row(Modifier.heightIn(min = (row.height * scale).dp).height(IntrinsicSize.Min)) {
                var col = 0
                for (cell in row.cells) {
                    val span = cell.columnSpan.coerceAtLeast(1)
                    val w = table.columnWidths.subList(col.coerceAtMost(table.columnWidths.size), (col + span).coerceAtMost(table.columnWidths.size)).sum()
                    col += span
                    Box(
                        Modifier
                            .width((w * fit * scale).dp)
                            .fillMaxHeight()
                            .background(cell.fill?.let { Color(it) } ?: Color.Transparent)
                            .border(0.5.dp, Color.White.copy(alpha = 0.6f)),
                    ) {
                        Column(Modifier.padding(horizontal = (91_440 * scale).dp, vertical = (45_720 * scale).dp)) {
                            cell.text.paragraphs.forEachIndexed { index, p -> SlideParagraphView(p, scale, cell.text.fontScale, true, first = index == 0) }
                        }
                    }
                }
            }
        }
    }
}

// endregion

/** Shown when a page or sheet had to be cut short. */
@Composable
internal fun TruncatedNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(16.dp),
        textAlign = TextAlign.Center,
    )
}
