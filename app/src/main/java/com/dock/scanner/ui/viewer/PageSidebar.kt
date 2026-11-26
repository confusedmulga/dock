package com.dock.scanner.ui.viewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.dock.scanner.ui.components.glass
import com.dock.scanner.ui.theme.DockColors
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The sidebar's width: a column of page thumbnails beside the pages. */
val SidebarWidth: Dp = 72.dp

/**
 * Every page as a small picture, in a frosted panel beside the pages. The one on screen is
 * outlined and kept in view; tapping one goes to it.
 *
 * A long press asks [onAskRearrange] to start rearranging (after the user confirms, so a press
 * held a moment too long doesn't do anything). While [PdfViewerState.rearranging], pages are
 * dragged into a new order right here, and the pages beside follow.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageSidebar(
    state: PdfViewerState,
    hazeState: HazeState,
    canRearrange: Boolean,
    onAskRearrange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val current = state.currentPosition
    val rearranging = state.rearranging

    // The page on screen stays in view, a row from the top.
    LaunchedEffect(current, rearranging) {
        if (rearranging) return@LaunchedEffect
        val visible = list.layoutInfo.visibleItemsInfo
        val shown = visible.drop(1).dropLast(1).any { it.index == current }
        if (!shown) list.animateScrollToItem((current - 1).coerceAtLeast(0))
    }

    // Dragging, while rearranging: which page, where the finger is (in the list's own
    // coordinates), and where on the thumbnail it took hold.
    var dragged by remember { mutableStateOf<Int?>(null) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var grab by remember { mutableFloatStateOf(0f) }
    var listHeight by remember { mutableFloatStateOf(0f) }

    // Scrolls while a page is held near the top or bottom edge.
    LaunchedEffect(dragged) {
        val edge = with(density) { 56.dp.toPx() }
        while (dragged != null) {
            val speed = when {
                pointerY < edge -> -(edge - pointerY) / edge * 14f
                pointerY > listHeight - edge -> (pointerY - (listHeight - edge)) / edge * 14f
                else -> 0f
            }
            if (speed != 0f) list.scrollBy(speed)
            reorderUnderPointer(state, list, dragged, pointerY)
            delay(16)
        }
    }

    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .glass(hazeState, shape, elevation = 10.dp, tint = if (rearranging) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Unspecified)
            .then(
                if (!rearranging) Modifier
                else Modifier.pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val item = list.itemAt(start.y) ?: return@detectDragGestures
                            dragged = item.key as? Int
                            grab = start.y - list.top(item)
                            pointerY = start.y
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDrag = { change, _ ->
                            if (dragged == null) return@detectDragGestures
                            change.consume()
                            pointerY = change.position.y
                            if (reorderUnderPointer(state, list, dragged, pointerY)) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onDragEnd = { dragged = null },
                        onDragCancel = { dragged = null },
                    )
                },
            ),
    ) {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            userScrollEnabled = !rearranging,
            modifier = Modifier.fillMaxSize().onSizeChanged { listHeight = it.height.toFloat() },
        ) {
            items(state.order.size, key = { state.order[it] }) { place ->
                val page = state.order[place]
                val isDragged = page == dragged
                Thumbnail(
                    state = state,
                    page = page,
                    number = place + 1,
                    current = place == current && !rearranging,
                    modifier = Modifier
                        .then(if (isDragged) Modifier else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null))
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer {
                            if (isDragged) {
                                // Under the finger, wherever the list has laid the page out by now.
                                val slot = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == page }
                                translationY = if (slot != null) pointerY - grab - list.top(slot) else 0f
                                scaleX = 1.08f
                                scaleY = 1.08f
                                shadowElevation = 16f
                            }
                        }
                        .then(
                            if (rearranging) Modifier
                            else Modifier.combinedClickable(
                                onClickLabel = "Go to page ${place + 1}",
                                onLongClickLabel = if (canRearrange) "Rearrange pages" else null,
                                onLongClick = if (canRearrange) ({
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onAskRearrange()
                                }) else null,
                                onClick = { scope.launch { state.listState.animateScrollToItem(place) } },
                            ),
                        ),
                )
            }
        }
    }
}

@Composable
private fun Thumbnail(state: PdfViewerState, page: Int, number: Int, current: Boolean, modifier: Modifier = Modifier) {
    val widthPx = with(LocalDensity.current) { (SidebarWidth * 1.4f).roundToPx() }
    val bitmap by produceState(state.cachedThumbnail(page), state, page) {
        if (value == null) value = state.thumbnail(page, widthPx)
    }
    val aspect = state.aspects[page] ?: bitmap?.let { it.width.toFloat() / it.height } ?: (1 / 1.414f)
    val shape = MaterialTheme.shapes.small
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect.coerceIn(0.25f, 4f))
                .clip(shape)
                .background(DockColors.Paper)
                .border(
                    if (current) 2.dp else 1.dp,
                    if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape,
                ),
        ) {
            bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Page $number", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        Text(
            "$number",
            style = MaterialTheme.typography.labelSmall,
            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** Where an item starts, in the list's own coordinates (its offsets are measured from after the top padding). */
private fun LazyListState.top(item: LazyListItemInfo): Float =
    (item.offset - layoutInfo.viewportStartOffset).toFloat()

private fun LazyListState.itemAt(y: Float) =
    layoutInfo.visibleItemsInfo.firstOrNull { y >= top(it) && y < top(it) + it.size }

/**
 * Moves the dragged page into the place of the one under the finger, once the finger is past
 * that one's middle (so pages of different heights don't swap back and forth). Waits until the
 * list has laid out the last move. Returns whether it moved anything.
 */
private fun reorderUnderPointer(state: PdfViewerState, list: LazyListState, dragged: Int?, pointerY: Float): Boolean {
    dragged ?: return false
    val visible = list.layoutInfo.visibleItemsInfo
    // The list still shows the order from before the last move: nothing to compare against yet.
    if (visible.any { state.order.getOrNull(it.index) != it.key }) return false
    val from = state.order.indexOf(dragged).takeIf { it >= 0 } ?: return false
    val target = visible.firstOrNull { item ->
        item.key != dragged && pointerY >= list.top(item) && pointerY < list.top(item) + item.size
    } ?: return false
    val middle = list.top(target) + target.size / 2f
    val passed = if (target.index > from) pointerY > middle else pointerY < middle
    if (!passed) return false
    state.move(from, target.index)
    return true
}
