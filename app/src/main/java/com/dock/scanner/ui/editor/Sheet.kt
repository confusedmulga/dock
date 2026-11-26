package com.dock.scanner.ui.editor

import com.dock.scanner.ui.theme.DockColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dock.scanner.data.Page
import com.dock.scanner.data.Placement
import com.dock.scanner.data.SheetLayout
import kotlin.math.roundToInt

/** Where a placement lands on a sheet of [sheetW] x [sheetH] pixels. */
private fun Placement.rect(aspect: Float, sheetW: Float, sheetH: Float): Rect {
    val w = width * sheetW
    val h = w / aspect
    val left = centerX * sheetW - w / 2
    val top = centerY * sheetH - h / 2
    return Rect(left, top, left + w, top + h)
}

/** A sheet of paper with its pages placed on it, drawn exactly as it will be exported. */
@Composable
fun SheetView(
    layout: SheetLayout,
    pages: Map<String, Page>,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
) {
    val density = LocalDensity.current
    // Clipped like the PDF page, which only shows what's on the paper.
    BoxWithConstraints(modifier.aspectRatio(layout.aspect).clipToBounds().background(DockColors.Paper)) {
        val sheetW = constraints.maxWidth.toFloat()
        val sheetH = constraints.maxHeight.toFloat()
        layout.items.forEach { item ->
            val page = pages[item.pageId] ?: return@forEach
            val r = item.rect(page.aspect, sheetW, sheetH)
            val selected = item.pageId == selectedId
            AsyncImage(
                model = page.output,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                    .size(with(density) { DpSize(r.width.toDp(), r.height.toDp()) })
                    .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier),
            )
        }
    }
}

/**
 * The Adjust tool's canvas: drag a page to move it on the sheet, pinch to resize it.
 * Touching a page selects it; the page follows the finger 1:1 from where it was grabbed.
 */
@Composable
fun SheetEditor(
    layout: SheetLayout,
    pages: Map<String, Page>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onChange: (Placement) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentLayout by rememberUpdatedState(layout)
    val currentPages by rememberUpdatedState(pages)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnChange by rememberUpdatedState(onChange)

    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        // aspectRatio tries the full width first and falls back to the height when that's too tall.
        Box(
            Modifier
                .aspectRatio(layout.aspect)
                .shadow(8.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val sheetW = size.width.toFloat()
                        val sheetH = size.height.toFloat()
                        // Topmost page under the finger wins.
                        val hit = currentLayout.items.lastOrNull { item ->
                            val page = currentPages[item.pageId] ?: return@lastOrNull false
                            item.rect(page.aspect, sheetW, sheetH).contains(down.position)
                        }
                        currentOnSelect(hit?.pageId)
                        if (hit == null) return@awaitEachGesture
                        var item: Placement = hit
                        val aspect = currentPages[hit.pageId]?.aspect ?: 1f
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                            val pan = event.calculatePan()
                            val zoom = event.calculateZoom()
                            if (pan != Offset.Zero || zoom != 1f) {
                                // Clamp here too, so dragging past an edge doesn't build up hidden offset.
                                item = currentLayout.clamp(
                                    item.copy(
                                        centerX = item.centerX + pan.x / sheetW,
                                        centerY = item.centerY + pan.y / sheetH,
                                        width = item.width * zoom,
                                    ),
                                    aspect,
                                )
                                currentOnChange(item)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                },
        ) {
            SheetView(layout, pages, Modifier.fillMaxSize(), selectedId)
        }
    }
}
