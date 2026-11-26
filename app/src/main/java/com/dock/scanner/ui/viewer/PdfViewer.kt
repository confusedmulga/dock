package com.dock.scanner.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dock.scanner.DockApp
import com.dock.scanner.R
import com.dock.scanner.ui.components.DockDropdownMenu
import com.dock.scanner.ui.components.DockMenuItem
import com.dock.scanner.ui.components.EmptyMessage
import com.dock.scanner.ui.components.GlassBubble
import com.dock.scanner.ui.components.ZoomableColumn
import com.dock.scanner.ui.components.glass
import com.dock.scanner.ui.components.sharedPage
import com.dock.scanner.ui.theme.DockColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay

/** What opening a PDF for viewing came to. */
private sealed interface OpenedPdf {
    class Ready(val renderer: PdfRenderer) : OpenedPdf

    /** Needs a password, which Dock can't ask for yet. */
    data object Locked : OpenedPdf

    /** Gone, unreadable or not a PDF. */
    data object Unreadable : OpenedPdf
}

private fun openPdf(context: Context, uri: Uri): OpenedPdf {
    val fd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return OpenedPdf.Unreadable
    return try {
        OpenedPdf.Ready(PdfRenderer(fd))
    } catch (e: Exception) {
        // The renderer only takes the file over once it opened it: otherwise it's still ours to close.
        runCatching { fd.close() }
        if (e is SecurityException) OpenedPdf.Locked else OpenedPdf.Unreadable
    }
}

/** Search matches on white paper: the marker yellow everyone knows, stronger for the one in view. */
private val MatchColor = Color(0xFFFFC107).copy(alpha = 0.36f)
private val CurrentMatchColor = Color(0xFFFF9800).copy(alpha = 0.55f)

private val HandleRadius = 7.dp
private val HandleTouch = 30.dp

/**
 * A PDF, page under page, to zoom, read and search: the whole viewer, for files in the
 * library and files opened from other apps alike.
 *
 * - The top bar: back, the [title] (opening [titleMenu] when there is one), search, and a ⋮
 *   menu with [moreItems] and the viewer's own options.
 * - A sidebar of page thumbnails for PDFs with more than one page, shown or hidden from ⋮.
 * - Long-press a word to select text; drag to select more, or move the handles. Copy, select
 *   the page's text or share it from the bar that comes up. Pages without text of their own
 *   are read with text recognition first.
 * - Long-press a thumbnail to rearrange the pages (after a confirmation); [onSaveOrder] writes
 *   the new order, and when it's null (the file can't be changed) pages can't be rearranged.
 *
 * @param version anything that changes when the file was rewritten, so it's opened afresh.
 * @param onSaveOrder writes the pages in the given order (each the page's index in the file),
 *   then calls back with whether it worked.
 */
@Composable
fun PdfViewer(
    uri: Uri,
    version: Any,
    title: String,
    onBack: () -> Unit,
    titleMenu: (@Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit)? = null,
    moreItems: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit = {},
    onSaveOrder: ((order: List<Int>, done: (Boolean) -> Unit) -> Unit)? = null,
    firstPage: Bitmap? = null,
    firstPageKey: String? = null,
) {
    val context = LocalContext.current
    val hazeState = remember { HazeState() }
    val opened = remember(uri, version) { openPdf(context, uri) }
    val renderer = (opened as? OpenedPdf.Ready)?.renderer

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        if (renderer == null) {
            Box(Modifier.fillMaxSize().hazeSource(hazeState), contentAlignment = Alignment.Center) {
                if (opened is OpenedPdf.Locked) {
                    EmptyMessage("This PDF is locked", "It needs a password, and Dock can't open password-protected PDFs yet.", icon = painterResource(R.drawable.ic_lock))
                } else {
                    EmptyMessage("Can't open this PDF", "It may be damaged, or it was moved or deleted.", icon = painterResource(R.drawable.ic_doc_outline))
                }
            }
            TopBar(hazeState, title, onBack, titleMenu, onSearch = null) { dismiss -> moreItems(dismiss) }
            return@Box
        }

        val scope = rememberCoroutineScope()
        val state = remember(renderer) { PdfViewerState(renderer, PdfTextSource(context, uri), scope) }
        DisposableEffect(state) { onDispose { state.close() } }
        LaunchedEffect(state) { state.loadPageSizes() }
        // A file opened afresh (e.g. after saving a new page order) starts over: nothing drawn
        // or remembered for the old one, where page 1 may have been another page, carries over.
        key(state) {
            Viewer(state, hazeState, title, onBack, titleMenu, moreItems, onSaveOrder, firstPage, firstPageKey)
        }
    }
}

@Composable
private fun BoxScope.Viewer(
    state: PdfViewerState,
    hazeState: HazeState,
    title: String,
    onBack: () -> Unit,
    titleMenu: (@Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit)?,
    moreItems: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
    onSaveOrder: ((order: List<Int>, done: (Boolean) -> Unit) -> Unit)?,
    firstPage: Bitmap?,
    firstPageKey: String?,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val settings = remember { (context.applicationContext as DockApp).settings }
    var sidebarOn by remember { mutableStateOf(settings.viewerSidebar) }
    var askRearrange by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }
    var saving by remember(state) { mutableStateOf(false) }

    val multiPage = state.pageCount > 1
    val sidebar = multiPage && (sidebarOn || state.rearranging)
    val canRearrange = onSaveOrder != null && multiPage
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val pagesStart = if (sidebar) 12.dp + SidebarWidth + 12.dp else 16.dp
    SideEffect { state.revealMarginPx = with(density) { 24.dp.roundToPx() } }

    // Back first undoes what's going on in the viewer: rearranging, a selection, a search.
    fun back() {
        when {
            state.rearranging -> if (state.orderChanged) askDiscard = true else state.stopRearranging()
            state.selection != null -> state.clearSelection()
            state.searchOpen -> state.closeSearch()
            else -> onBack()
        }
    }
    BackHandler(enabled = state.rearranging || state.selection != null || state.searchOpen) { back() }

    fun save() {
        val write = onSaveOrder
        if (!state.orderChanged || write == null) {
            state.stopRearranging()
            return
        }
        saving = true
        write(state.order) { ok ->
            saving = false
            if (ok) {
                Toast.makeText(context, "Saved the new page order", Toast.LENGTH_SHORT).show()
                state.stopRearranging()
            }
        }
    }

    Pages(
        state = state,
        firstPage = firstPage,
        firstPageKey = firstPageKey,
        contentPadding = PaddingValues(start = pagesStart, end = 16.dp, top = top + 76.dp, bottom = bottom + 88.dp),
        onNoText = { Toast.makeText(context, "No text found on this page", Toast.LENGTH_SHORT).show() },
        modifier = Modifier.fillMaxSize().hazeSource(hazeState),
    )

    if (sidebar) {
        PageSidebar(
            state = state,
            hazeState = hazeState,
            canRearrange = canRearrange,
            onAskRearrange = { askRearrange = true },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = top + 72.dp, bottom = bottom + 16.dp)
                .width(SidebarWidth)
                .fillMaxHeight(),
        )
    }

    when {
        state.rearranging -> RearrangeBar(hazeState, onBack = ::back)
        state.searchOpen -> SearchBar(state, hazeState)
        else -> TopBar(hazeState, title, ::back, titleMenu, onSearch = state::openSearch) { dismiss ->
            moreItems(dismiss)
            if (multiPage) {
                DockMenuItem(
                    text = { Text(if (sidebarOn) "Hide page thumbnails" else "Show page thumbnails") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_sidebar), null) },
                    onClick = {
                        dismiss()
                        sidebarOn = !sidebarOn
                        settings.viewerSidebar = sidebarOn
                    },
                )
            }
            if (canRearrange) {
                DockMenuItem(
                    text = { Text("Rearrange pages") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_reorder), null) },
                    onClick = { dismiss(); state.startRearranging() },
                )
            }
        }
    }

    // The bars along the bottom sit over the pages, clear of the sidebar.
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(start = if (sidebar) pagesStart - 8.dp else 0.dp, bottom = bottom + 16.dp),
    ) {
        val selection = state.selection
        when {
            state.rearranging -> RearrangeActions(
                hazeState = hazeState,
                saving = saving,
                onCancel = { if (state.orderChanged) askDiscard = true else state.stopRearranging() },
                onSave = ::save,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp),
            )
            state.reading != null -> StatusPill(hazeState, "Reading text…", Modifier.align(Alignment.BottomCenter))
            selection != null -> SelectionBar(
                hazeState = hazeState,
                onCopy = {
                    copy(context, state.selectedText)
                    state.clearSelection()
                },
                onSelectAll = state::selectAll,
                onShare = { shareText(context, state.selectedText) },
                onClose = state::clearSelection,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            multiPage -> PageCounter(state.listState, state.currentPosition, state.pageCount, Modifier.align(Alignment.BottomCenter))
        }
    }

    if (askRearrange) {
        AlertDialog(
            onDismissRequest = { askRearrange = false },
            title = { Text("Rearrange pages?") },
            text = { Text("Drag pages in the sidebar into the order you want, then tap the tick to save it.") },
            confirmButton = {
                TextButton(onClick = {
                    askRearrange = false
                    state.startRearranging()
                }) { Text("Rearrange") }
            },
            dismissButton = { TextButton(onClick = { askRearrange = false }) { Text("Cancel") } },
        )
    }
    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text("Discard the new order?") },
            text = { Text("The pages go back to the order they were in. Nothing has been saved.") },
            confirmButton = {
                TextButton(onClick = {
                    askDiscard = false
                    state.stopRearranging()
                }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askDiscard = false }) { Text("Keep rearranging") } },
        )
    }
}

/** The pages in their current order, zoomable, with selections and search matches drawn on them. */
@Composable
private fun Pages(
    state: PdfViewerState,
    firstPage: Bitmap?,
    firstPageKey: String?,
    contentPadding: PaddingValues,
    onNoText: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    ZoomableColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        listState = state.listState,
        zoom = state.zoom,
        panBlocked = { state.selecting },
    ) { pageWidth ->
        val widthPx = with(density) { pageWidth.roundToPx() }
        // Rendered sharper than the layout, so zooming in stays crisp for a while.
        val renderWidthPx = (widthPx * 1.75f).toInt().coerceAtMost(2000)
        items(state.order.size, key = { state.order[it] }) { place ->
            val page = state.order[place]
            SideEffect { state.pageWidthPx = widthPx }
            // Only the first page grows out of (and back into) the list's thumbnail.
            val shared = if (page == 0 && firstPageKey != null) Modifier.sharedPage(firstPageKey) else Modifier
            PdfPage(
                state = state,
                page = page,
                renderWidthPx = renderWidthPx,
                placeholder = if (page == 0) firstPage else null,
                haptics = haptics,
                onNoText = onNoText,
                modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null).then(shared),
            )
        }
    }
}

@Composable
private fun PdfPage(
    state: PdfViewerState,
    page: Int,
    renderWidthPx: Int,
    placeholder: Bitmap?,
    haptics: HapticFeedback,
    onNoText: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rendered by produceState<Bitmap?>(null, state, page, renderWidthPx) { value = state.render(page, renderWidthPx) }
    val bitmap = rendered ?: placeholder
    val aspect = state.aspects[page] ?: bitmap?.let { it.width.toFloat() / it.height } ?: (1 / 1.414f)
    val shape = MaterialTheme.shapes.extraSmall
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(aspect.coerceIn(0.05f, 20f))
            .shadow(3.dp, shape)
            .clip(shape)
            .background(DockColors.Paper)
            .textSelection(state, page, haptics, onNoText),
    ) {
        if (bitmap != null) {
            // Crop rather than stretch: while the page morphs from a list thumbnail, its box
            // briefly has the thumbnail's shape. At rest the box matches the page exactly.
            Image(bitmap.asImageBitmap(), contentDescription = "Page ${page + 1}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        PageMarks(state, page, Modifier.matchParentSize())
    }
}

/** Search matches and the selection with its handles, drawn over a page. */
@Composable
private fun PageMarks(state: PdfViewerState, page: Int, modifier: Modifier) {
    val text = state.texts[page] ?: return
    val selection = state.selection?.takeIf { it.page == page }
    val matches = state.matchesByPage[page]
    if (selection == null && matches == null) return
    val current = state.matches.getOrNull(state.currentMatch)?.takeIf { it.page == page }
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val handleColor = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        matches?.forEach { range ->
            val color = if (current?.range == range) CurrentMatchColor else MatchColor
            for (r in text.rects(range.first, range.last)) {
                drawRect(color, Offset(r.left * w, r.top * h), Size((r.right - r.left) * w, (r.bottom - r.top) * h))
            }
        }
        if (selection != null) {
            val rects = text.rects(selection.start, selection.end)
            for (r in rects) {
                drawRect(selectionColor, Offset(r.left * w, r.top * h), Size((r.right - r.left) * w, (r.bottom - r.top) * h))
            }
            if (rects.isNotEmpty()) {
                // Handles keep their size on screen at any zoom.
                val scale = state.zoom.scale
                val radius = HandleRadius.toPx() / scale
                val stroke = 2.dp.toPx() / scale
                val first = rects.first()
                val last = rects.last()
                for ((x, rect) in listOf(first.left to first, last.right to last)) {
                    val cx = x * w
                    drawLine(handleColor, Offset(cx, rect.top * h), Offset(cx, rect.bottom * h + radius), stroke)
                    drawCircle(handleColor, radius, Offset(cx, rect.bottom * h + radius))
                }
            }
        }
    }
}

/**
 * Text selection on a page: a long press selects the word under the finger and dragging on
 * selects word by word; the selection's handles can be dragged; a tap clears it. Quick touches
 * are left to the pages (scrolling, zooming, double taps).
 */
private fun Modifier.textSelection(state: PdfViewerState, page: Int, haptics: HapticFeedback, onNoText: () -> Unit): Modifier =
    pointerInput(state, page) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (state.rearranging) return@awaitEachGesture
            val w = size.width.toFloat()
            val h = size.height.toFloat()
            val scale = state.zoom.scale
            val radius = HandleRadius.toPx() / scale

            // A handle: drag it. It's held from below, so the point that moves is the text's bottom edge.
            val handles = state.handlePoints(page)
            if (handles != null) {
                val (start, end) = handles
                val toStart = (Offset(start.first * w, start.second * h + radius) - down.position).getDistance()
                val toEnd = (Offset(end.first * w, end.second * h + radius) - down.position).getDistance()
                if (minOf(toStart, toEnd) <= HandleTouch.toPx() / scale) {
                    down.consume()
                    var handle = if (toStart <= toEnd) SelectionHandle.Start else SelectionHandle.End
                    val lift = radius * 2f
                    state.selecting = true
                    try {
                        drag(down.id) { change ->
                            change.consume()
                            handle = state.moveHandle(page, handle, change.position.x / w, (change.position.y - lift) / h)
                        }
                    } finally {
                        state.selecting = false
                    }
                    return@awaitEachGesture
                }
            }

            // Wait out the long-press delay: lifting first is a tap, moving or a second finger hands the touch back.
            val slop = viewConfiguration.touchSlop
            var outcome = HELD
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (outcome == HELD) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    outcome = when {
                        change == null || event.changes.count { it.pressed } > 1 -> CANCELLED
                        !change.pressed -> TAPPED
                        (change.position - down.position).getDistance() > slop -> CANCELLED
                        else -> HELD
                    }
                }
            }
            if (outcome != HELD) {
                if (outcome == TAPPED && state.selection != null) state.clearSelection()
                return@awaitEachGesture
            }

            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            state.selecting = true
            state.longPress(page, down.position.x / w, down.position.y / h, onNoText)
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    state.extend(page, change.position.x / w, change.position.y / h)
                }
            } finally {
                state.selecting = false
            }
        }
    }

private const val HELD = 0
private const val TAPPED = 1
private const val CANCELLED = 2

/** Back, the title (a menu under it, if there's one), search and the ⋮ menu, on glass. */
@Composable
private fun TopBar(
    hazeState: HazeState,
    title: String,
    onBack: () -> Unit,
    titleMenu: (@Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit)?,
    onSearch: (() -> Unit)?,
    moreItems: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var titleOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassBubble(hazeState, rememberVectorPainter(Icons.AutoMirrored.Filled.KeyboardArrowLeft), "Back", onBack, size = 48.dp)
        Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Box {
                Row(
                    Modifier
                        .widthIn(max = 260.dp)
                        .glass(hazeState, CircleShape, elevation = 8.dp)
                        .then(if (titleMenu != null) Modifier.clickable(onClickLabel = "File options") { titleOpen = true } else Modifier)
                        .padding(start = 16.dp, end = if (titleMenu != null) 8.dp else 16.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (titleMenu != null) Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(24.dp))
                }
                titleMenu?.invoke(titleOpen) { titleOpen = false }
            }
        }
        if (onSearch != null) {
            GlassBubble(hazeState, rememberVectorPainter(Icons.Filled.Search), "Search in this PDF", onSearch, size = 48.dp)
            Spacer(Modifier.width(8.dp))
        }
        Box {
            GlassBubble(hazeState, rememberVectorPainter(Icons.Filled.MoreVert), "More options", { moreOpen = true }, size = 48.dp)
            DockDropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                moreItems { moreOpen = false }
            }
        }
    }
}

/** The search field with the count of matches and the way between them. */
@Composable
private fun SearchBar(state: PdfViewerState, hazeState: HazeState) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    val found = state.matches.size
    val searching = state.searchProgress != null
    val status by remember {
        derivedStateOf {
            when {
                state.query.isBlank() -> ""
                state.matches.isEmpty() && state.searchProgress != null -> ""
                state.matches.isEmpty() -> "No matches"
                else -> "${state.currentMatch + 1} of ${state.matches.size}${if (state.searchProgress != null) "+" else ""}"
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(48.dp)
                .glass(hazeState, CircleShape, elevation = 8.dp)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                if (state.query.isEmpty()) {
                    Text("Search in this PDF", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                BasicTextField(
                    value = state.query,
                    // Pasted text can bring line breaks; the field is one line, and matching treats them as spaces anyway.
                    onValueChange = { state.search(it.replace('\n', ' ')) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        state.nextMatch()
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (searching) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
            }
            if (status.isNotEmpty()) {
                Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            IconButton(onClick = state::previousMatch, enabled = found > 0, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match")
            }
            IconButton(onClick = state::nextMatch, enabled = found > 0, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match")
            }
        }
        Spacer(Modifier.width(8.dp))
        GlassBubble(hazeState, rememberVectorPainter(Icons.Filled.Close), "Close search", state::closeSearch, size = 48.dp)
    }
}

/** While pages are being rearranged: back, and what to do. */
@Composable
private fun RearrangeBar(hazeState: HazeState, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassBubble(hazeState, rememberVectorPainter(Icons.AutoMirrored.Filled.KeyboardArrowLeft), "Back", onBack, size = 48.dp)
        Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(
                "Drag pages to rearrange",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.glass(hazeState, CircleShape, elevation = 8.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        Spacer(Modifier.width(48.dp))
    }
}

/** Cancel, and the frosted blue tick that saves the new order. */
@Composable
private fun RearrangeActions(hazeState: HazeState, saving: Boolean, onCancel: () -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        GlassBubble(hazeState, rememberVectorPainter(Icons.Filled.Close), "Cancel rearranging", { if (!saving) onCancel() }, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Box(contentAlignment = Alignment.Center) {
            GlassBubble(
                hazeState,
                rememberVectorPainter(Icons.Filled.Check),
                "Save the new page order",
                { if (!saving) onSave() },
                tint = MaterialTheme.colorScheme.primary,
            )
            if (saving) CircularProgressIndicator(Modifier.size(56.dp), strokeWidth = 2.dp)
        }
    }
}

/** Copy, select all and share for the selected text. */
@Composable
private fun SelectionBar(
    hazeState: HazeState,
    onCopy: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.glass(hazeState, CircleShape, elevation = 10.dp).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCopy) { Text("Copy") }
        TextButton(onClick = onSelectAll) { Text("Select all") }
        TextButton(onClick = onShare) { Text("Share") }
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Clear selection", modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun StatusPill(hazeState: HazeState, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.glass(hazeState, CircleShape, elevation = 10.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * "3 / 12" on a small pill: there while the pages move and for a moment after (and when the
 * PDF opens, so its length is known at a glance), then out of the way of the page.
 */
@Composable
private fun PageCounter(listState: LazyListState, current: Int, pageCount: Int, modifier: Modifier = Modifier) {
    val scrolling = listState.isScrollInProgress
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(current, scrolling) {
        visible = true
        if (!scrolling) {
            delay(1600)
            visible = false
        }
    }
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        Text(
            "${(current + 1).coerceAtMost(pageCount)} / $pageCount",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.82f))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

private fun copy(context: Context, text: String) {
    if (text.isEmpty()) return
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Text from a PDF", text))
    // Android 13 and later show their own confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, text: String) {
    if (text.isEmpty()) return
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, "Share text")) }
}
