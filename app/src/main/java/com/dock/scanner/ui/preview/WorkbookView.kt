package com.dock.scanner.ui.preview

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.dock.scanner.office.Cell
import com.dock.scanner.office.CellAlign
import com.dock.scanner.office.Sheet
import com.dock.scanner.office.Workbook
import com.dock.scanner.ui.theme.DockColors

private val GridLine = Color(0xFFD9D9D9)
private val HeaderFill = Color(0xFFF2F2F2)
private val HeaderInk = Color(0xFF666666)
private const val MIN_ZOOM = 0.4f
private const val MAX_ZOOM = 3f

/**
 * A workbook: the chosen sheet as a grid (column letters across the top, row numbers down
 * the side, both pinned), and tabs to switch sheets. One spreadsheet pixel is drawn as one
 * dp, times the pinch zoom.
 */
@Composable
fun WorkbookView(book: Workbook, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val sheet = book.sheets.getOrNull(selected) ?: book.sheets.first()
    Box(modifier.fillMaxSize()) {
        SheetGrid(sheet, contentPadding, bottomExtra = if (book.sheets.size > 1) 56.dp else 0.dp)
        if (book.sheets.size > 1) {
            SheetTabs(
                names = book.sheets.map { it.name },
                selected = selected,
                onSelect = { selected = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = contentPadding.calculateBottomPadding()),
            )
        }
    }
}

@Composable
private fun SheetTabs(names: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        names.forEachIndexed { i, name ->
            val on = i == selected
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SheetGrid(sheet: Sheet, contentPadding: PaddingValues, bottomExtra: Dp) {
    var zoom by remember(sheet) { mutableFloatStateOf(1f) }
    val horizontal = remember(sheet) { ScrollState(0) }
    val list = remember(sheet) { LazyListState() }
    // A little empty space past the used area, so the grid reads as a sheet.
    val columns = sheet.columnCount + 2
    val rows = sheet.rowCount + 4
    val headerWidth = (36 + 8 * rows.toString().length).dp * zoom
    val headerHeight = 22.dp * zoom
    val merged = remember(sheet) {
        // Horizontal merges: the first cell takes the width of the whole range.
        sheet.merges.filter { it.lastColumn > it.firstColumn }.associateBy { it.firstRow to it.firstColumn }
    }
    val covered = remember(sheet) {
        sheet.merges.flatMap { m -> (m.firstColumn + 1..m.lastColumn).map { c -> m.firstRow to c } }.toSet()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding())
            .background(DockColors.Paper)
            .pointerInput(sheet) {
                // Pinch to zoom; one finger is left to the scrolling grid.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val factor = event.calculateZoom()
                            val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            val applied = newZoom / zoom
                            if (applied != 1f) {
                                val c = event.calculateCentroid()
                                zoom = newZoom
                                // Keep the point between the fingers roughly in place.
                                horizontal.dispatchRawDelta((horizontal.value + c.x) * (applied - 1))
                                list.dispatchRawDelta(c.y * (applied - 1))
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        // Column letters.
        Row(Modifier.background(HeaderFill)) {
            HeaderCell("", headerWidth, headerHeight, zoom)
            Row(Modifier.horizontalScroll(horizontal)) {
                for (c in 0 until columns) {
                    val w = sheet.columnWidth(c)
                    if (w > 0f) HeaderCell(columnName(c), w.dp * zoom, headerHeight, zoom)
                }
            }
        }
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + bottomExtra),
            modifier = Modifier.weight(1f),
        ) {
            items(rows, key = { it }) { r ->
                val h = sheet.rowHeight(r)
                if (h <= 0f) return@items
                val height = h.dp * zoom
                Row {
                    HeaderCell((r + 1).toString(), headerWidth, height, zoom)
                    Row(Modifier.horizontalScroll(horizontal)) {
                        val cells = sheet.rows[r]
                        var c = 0
                        while (c < columns) {
                            if ((r to c) in covered) { c++; continue }
                            val w = sheet.columnWidth(c)
                            if (w <= 0f) { c++; continue }
                            val merge = merged[r to c]
                            val width = if (merge != null) (merge.firstColumn..merge.lastColumn).sumOf { sheet.columnWidth(it).toDouble() }.toFloat() else w
                            val cell = cells?.get(c)
                            // Text runs on into empty cells to its right, as in a spreadsheet app.
                            var spill = width
                            if (cell != null && merge == null && !cell.style.wrap && !cell.isNumber && cell.style.align.let { it == CellAlign.General || it == CellAlign.Left }) {
                                var next = c + 1
                                while (next < columns && cells[next] == null && next - c < 12) {
                                    spill += sheet.columnWidth(next)
                                    next++
                                }
                            }
                            CellView(cell, width.dp * zoom, height, spill.dp * zoom, zoom)
                            c++
                        }
                    }
                }
            }
            if (sheet.truncated) {
                item { TruncatedNote("This sheet is very large: only the first part is shown.") }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: Dp, height: Dp, zoom: Float) {
    val density = LocalDensity.current
    Box(
        Modifier
            .size(width, height)
            .background(HeaderFill)
            .gridLines(),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = HeaderInk, fontSize = with(density) { (11.dp * zoom).toSp() }, maxLines = 1)
    }
}

private fun Modifier.gridLines() = drawBehind {
    val w = 1f
    drawLine(GridLine, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w)
    drawLine(GridLine, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
}

@Composable
private fun CellView(cell: Cell?, width: Dp, height: Dp, textWidth: Dp, zoom: Float) {
    val style = cell?.style
    Box(
        Modifier
            .size(width, height)
            .then(if (textWidth > width) Modifier.zIndex(1f) else Modifier)
            .background(style?.fill?.let { Color(it) } ?: DockColors.Paper)
            .gridLines(),
        contentAlignment = Alignment.BottomStart,
    ) {
        if (cell == null || cell.text.isEmpty()) return@Box
        val density = LocalDensity.current
        val s = cell.style
        val align = when (s.align) {
            CellAlign.Left -> TextAlign.Start
            CellAlign.Center -> TextAlign.Center
            CellAlign.Right -> TextAlign.End
            CellAlign.General -> if (cell.isNumber) TextAlign.End else TextAlign.Start
        }
        // Point sizes at 96 dpi (one spreadsheet pixel per dp), a little smaller since Roboto
        // runs larger than the spreadsheet fonts rows are sized for.
        val fontSize = with(density) { (s.size * 4 / 3 * 0.88f * zoom).dp.toSp() }
        Text(
            cell.text,
            color = s.color?.let { Color(it) } ?: Color(0xFF1B1B1B),
            fontSize = fontSize,
            lineHeight = fontSize * 1.18f,
            style = androidx.compose.ui.text.TextStyle(
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
            fontWeight = if (s.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (s.italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = when {
                s.underline -> TextDecoration.Underline
                s.strike -> TextDecoration.LineThrough
                else -> null
            },
            textAlign = align,
            maxLines = if (s.wrap) Int.MAX_VALUE else 1,
            softWrap = s.wrap,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .padding(horizontal = 3.dp * zoom, vertical = 2.dp * zoom)
                .then(
                    if (textWidth > width) Modifier.wrapContentWidth(Alignment.Start, unbounded = true).requiredWidth(textWidth - 6.dp * zoom)
                    else Modifier.fillMaxWidth(),
                ),
        )
    }
}

private fun columnName(index: Int): String {
    var n = index + 1
    val sb = StringBuilder()
    while (n > 0) {
        val r = (n - 1) % 26
        sb.append('A' + r)
        n = (n - 1) / 26
    }
    return sb.reverse().toString()
}
