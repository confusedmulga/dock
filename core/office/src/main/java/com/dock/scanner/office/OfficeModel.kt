package com.dock.scanner.office

/**
 * A document read from a Word, Excel, PowerPoint, OpenDocument, RTF, CSV or text file, in a
 * form that's simple to draw. Readers keep what a preview needs (text, formatting, tables,
 * images, layout) and drop what it doesn't (editing history, macros, comments).
 *
 * Colors are ARGB ints. Lengths are points (1/72 inch) unless a name says otherwise.
 */
sealed interface OfficeDocument

// region Text documents

/**
 * A flowing document. [pages] are split at explicit page breaks only: there's no layout
 * engine to break long text into pages, so a page can be taller than the paper.
 */
class TextDocument(
    val pageWidth: Float,
    val pageHeight: Float,
    val margins: Margins,
    val pages: List<List<Block>>,
    val images: ImageStore? = null,
) : OfficeDocument

data class Margins(val left: Float, val top: Float, val right: Float, val bottom: Float)

sealed interface Block

enum class Align { Start, Center, End, Justify }

data class Paragraph(
    val spans: List<Span>,
    val align: Align = Align.Start,
    /** Indent of the whole paragraph from the left margin. */
    val indent: Float = 0f,
    /** Extra indent of the first line; negative for a hanging indent (lists). */
    val firstLineIndent: Float = 0f,
    val spaceBefore: Float = 0f,
    val spaceAfter: Float = 0f,
    /** Line height as a multiple of single spacing. */
    val lineSpacing: Float = 1f,
    /** List bullet or number, e.g. "•" or "2.". */
    val marker: String? = null,
    val markerStyle: TextStyle? = null,
    val background: Int? = null,
) : Block {
    val isEmpty: Boolean get() = marker == null && spans.all { it is TextSpan && it.text.isEmpty() }
}

sealed interface Span

data class TextSpan(val text: String, val style: TextStyle) : Span

/** A picture in the text flow, [ref] being its name in the document's [ImageStore]. */
data class ImageSpan(val ref: String, val width: Float, val height: Float) : Span

enum class FontKind { Sans, Serif, Mono }

enum class Baseline { Normal, Superscript, Subscript }

data class TextStyle(
    val size: Float = 11f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val color: Int? = null,
    val background: Int? = null,
    val font: FontKind = FontKind.Sans,
    val baseline: Baseline = Baseline.Normal,
    val allCaps: Boolean = false,
    val link: String? = null,
)

data class Table(
    /** Widths of the grid columns; cells span one or more of them. */
    val columnWidths: List<Float>,
    val rows: List<TableRow>,
    val borders: Boolean,
    val indent: Float = 0f,
) : Block

data class TableRow(val cells: List<TableCell>)

data class TableCell(
    val blocks: List<Block>,
    val columnSpan: Int = 1,
    val background: Int? = null,
    /** Continues a vertically merged cell from the row above: drawn empty, without a top border. */
    val mergedWithAbove: Boolean = false,
)

// endregion

// region Workbooks

class Workbook(val sheets: List<Sheet>) : OfficeDocument

/**
 * One sheet. Sizes are in pixels at 96 dpi, the way spreadsheet apps measure them; the
 * preview draws one of those pixels as one dp.
 */
class Sheet(
    val name: String,
    val rowCount: Int,
    val columnCount: Int,
    /** Cells by row, then column (both 0-based); missing cells are empty. */
    val rows: Map<Int, Map<Int, Cell>>,
    val columnWidths: Map<Int, Float> = emptyMap(),
    val defaultColumnWidth: Float = 64f,
    val rowHeights: Map<Int, Float> = emptyMap(),
    val defaultRowHeight: Float = 20f,
    val merges: List<CellRange> = emptyList(),
    val hiddenColumns: Set<Int> = emptySet(),
    val hiddenRows: Set<Int> = emptySet(),
    /** True when the sheet was cut short because it's too big to preview. */
    val truncated: Boolean = false,
) {
    fun columnWidth(col: Int): Float = if (col in hiddenColumns) 0f else columnWidths[col] ?: defaultColumnWidth
    fun rowHeight(row: Int): Float = if (row in hiddenRows) 0f else rowHeights[row] ?: defaultRowHeight
}

data class CellRange(val firstRow: Int, val firstColumn: Int, val lastRow: Int, val lastColumn: Int)

data class Cell(val text: String, val style: CellStyle = CellStyle.Default, val isNumber: Boolean = false)

enum class CellAlign { General, Left, Center, Right }

data class CellStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val size: Float = 11f,
    val color: Int? = null,
    val fill: Int? = null,
    val align: CellAlign = CellAlign.General,
    val wrap: Boolean = false,
    val border: Boolean = false,
) {
    companion object {
        val Default = CellStyle()
    }
}

// endregion

// region Presentations

/** Slides measured in EMU (914 400 per inch), as PowerPoint stores them. */
class Presentation(
    val width: Long,
    val height: Long,
    val slides: List<Slide>,
    val images: ImageStore? = null,
) : OfficeDocument

class Slide(
    val background: Int?,
    val backgroundImage: String? = null,
    val elements: List<SlideElement>,
)

/** Position and size in EMU; rotation in degrees, clockwise. */
data class Frame(
    val x: Long,
    val y: Long,
    val width: Long,
    val height: Long,
    val rotation: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
)

sealed interface SlideElement {
    val frame: Frame
}

enum class Geometry { Rect, RoundRect, Ellipse, Triangle, Diamond, Line, Other }

data class Outline(val color: Int, val width: Long)

data class ShapeElement(
    override val frame: Frame,
    val geometry: Geometry = Geometry.Rect,
    val fill: Int? = null,
    val outline: Outline? = null,
    val text: TextFrame? = null,
) : SlideElement

data class PictureElement(override val frame: Frame, val ref: String) : SlideElement

data class TableElement(
    override val frame: Frame,
    val columnWidths: List<Long>,
    val rows: List<SlideTableRow>,
) : SlideElement

data class SlideTableRow(val height: Long, val cells: List<SlideTableCell>)

data class SlideTableCell(val text: TextFrame, val fill: Int?, val columnSpan: Int = 1)

/** Stands in for something the preview can't draw, such as a chart. */
data class PlaceholderElement(override val frame: Frame, val label: String) : SlideElement

enum class Anchor { Top, Middle, Bottom }

data class TextFrame(
    val paragraphs: List<SlideParagraph>,
    val anchor: Anchor = Anchor.Top,
    /** Insets in EMU: left, top, right, bottom. */
    val insets: List<Long> = listOf(91_440, 45_720, 91_440, 45_720),
    /** "Shrink text on overflow": the fraction PowerPoint scaled the text to. */
    val fontScale: Float = 1f,
    val wrap: Boolean = true,
)

data class SlideParagraph(
    val spans: List<TextSpan>,
    val align: Align = Align.Start,
    val bullet: String? = null,
    val bulletColor: Int? = null,
    /** Left margin and first-line indent, in EMU. */
    val marginLeft: Long = 0,
    val indent: Long = 0,
    val spaceBefore: Float = 0f,
    val spaceAfter: Float = 0f,
    val lineSpacing: Float = 1f,
    /** Size of an empty paragraph's line, in points. */
    val emptySize: Float = 18f,
)

// endregion

/** Pictures inside a document, loaded on demand by name. */
interface ImageStore {
    /** The picture's bytes, or null if it's missing or in a format the preview can't draw (e.g. EMF). */
    fun bytes(ref: String): ByteArray?
}
