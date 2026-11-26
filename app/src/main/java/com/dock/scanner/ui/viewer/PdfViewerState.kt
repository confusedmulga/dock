package com.dock.scanner.ui.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.util.LruCache
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dock.scanner.pdf.PdfPageText
import com.dock.scanner.ui.components.ColumnZoom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Selected characters [start] to [end] (inclusive) of a page, by its index in the file. */
data class TextSelection(val page: Int, val start: Int, val end: Int)

/** One place the search text was found: characters [range] of a page, by its index in the file. */
data class SearchMatch(val page: Int, val range: IntRange)

enum class SelectionHandle { Start, End }

/**
 * Everything an open PDF viewer shows and does: its pages and the order they're in, page
 * thumbnails, a text selection and a search. Pages are referred to by their index in the file;
 * [order] says where each one is shown.
 *
 * Owns [renderer] (which draws one page at a time, so every use goes through [lock]) and
 * [text]; [close] both when the viewer goes away.
 */
@Stable
class PdfViewerState(
    private val renderer: PdfRenderer,
    val text: PdfTextSource,
    private val scope: CoroutineScope,
) {
    val lock = Mutex()
    val pageCount: Int = renderer.pageCount

    @Volatile private var closed = false

    val listState = LazyListState()
    val zoom = ColumnZoom()

    /** Each page's width over its height, once known: read for all of them up front, so pages don't jump as they're drawn. */
    val aspects = mutableStateMapOf<Int, Float>()

    /** How wide pages are laid out, in pixels, and how far below the top bar a found word is brought: set by the screen. */
    var pageWidthPx = 0
    var revealMarginPx = 0

    /** The page in the middle of the screen, by its place in [order]. */
    val currentPosition: Int by derivedStateOf {
        val info = listState.layoutInfo
        val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
        (info.visibleItemsInfo.lastOrNull { it.offset <= middle } ?: info.visibleItemsInfo.firstOrNull())?.index ?: 0
    }

    // region Rendering

    /** Renders page [index] [widthPx] wide, on white. Null if it can't be (e.g. the viewer closed). */
    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed) return@withLock null
            runCatching {
                renderer.openPage(index).use { p ->
                    aspects[index] = p.width.toFloat() / p.height
                    val (width, height) = pageRenderSize(widthPx, p.width, p.height)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.WHITE)
                        p.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }.getOrNull()
        }
    }

    private val thumbnails = object : LruCache<Int, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    fun cachedThumbnail(index: Int): Bitmap? = thumbnails.get(index)

    /** A small picture of page [index] for the sidebar, kept while there's room. */
    suspend fun thumbnail(index: Int, widthPx: Int): Bitmap? =
        thumbnails.get(index) ?: render(index, widthPx)?.also { thumbnails.put(index, it) }

    /** Reads every page's size, a page at a time so drawing pages isn't held up. */
    suspend fun loadPageSizes() = withContext(Dispatchers.IO) {
        for (index in 0 until pageCount) {
            if (aspects.containsKey(index)) continue
            val ok = lock.withLock {
                if (closed) return@withLock false
                runCatching { renderer.openPage(index).use { aspects[index] = it.width.toFloat() / it.height } }.isSuccess
            }
            if (!ok) break
        }
    }

    fun close() {
        closed = true
        searchJob?.cancel()
        text.close()
        // Wait for any page still being drawn before closing the file.
        CoroutineScope(Dispatchers.IO).launch { lock.withLock { renderer.close() } }
    }

    // endregion

    // region Order

    /** Where each page is shown: the file's index of the page in each place. */
    var order by mutableStateOf(List(pageCount) { it })
        private set

    /** Pages are being dragged into a new order in the sidebar. */
    var rearranging by mutableStateOf(false)
        private set

    val orderChanged: Boolean get() = order.withIndex().any { (place, page) -> place != page }

    fun startRearranging() {
        clearSelection()
        closeSearch()
        rearranging = true
    }

    /** Leaves rearranging; the pages go back to the file's order. */
    fun stopRearranging() {
        rearranging = false
        order = List(pageCount) { it }
    }

    /** Moves the page at place [from] to place [to]. */
    fun move(from: Int, to: Int) {
        if (from == to || from !in order.indices || to !in order.indices) return
        order = order.toMutableList().apply { add(to, removeAt(from)) }
    }

    // endregion

    // region Text

    /** Text of the pages read so far, to highlight selections and matches on. */
    val texts = mutableStateMapOf<Int, PdfPageText>()

    /** A page whose text is being read for a long press: it can take a moment when the page is only an image. */
    var reading by mutableStateOf<Int?>(null)
        private set

    suspend fun loadText(page: Int): PdfPageText {
        texts[page]?.let { return it }
        return text.page(page).also { texts[page] = it }
    }

    var selection by mutableStateOf<TextSelection?>(null)
        private set

    /** A finger is down on the selection, extending it or moving a handle: the pages shouldn't pan meanwhile. */
    var selecting by mutableStateOf(false)

    /** The word the long press landed on; the selection grows from it, word by word. */
    private var anchor: IntRange? = null

    /** Where the finger is since the long press, in case the page's text arrives after it moved. */
    private var dragPoint: Pair<Float, Float>? = null

    /**
     * Starts a selection with the word at ([x], [y]) on [page] (fractions of the page). The
     * page's text is read first if need be; [onNoText] is told when it turns out to have none.
     */
    fun longPress(page: Int, x: Float, y: Float, onNoText: () -> Unit) {
        selection = null
        anchor = null
        dragPoint = null
        scope.launch {
            if (texts[page] == null) reading = page
            val text = try {
                loadText(page)
            } finally {
                if (reading == page) reading = null
            }
            if (text.isEmpty) {
                onNoText()
                return@launch
            }
            val index = text.charAt(x, y)
            if (index < 0 || !near(text, index, x, y)) return@launch
            val word = text.wordAt(index).takeUnless { it.isEmpty() } ?: return@launch
            anchor = word
            selection = TextSelection(page, word.first, word.last)
            dragPoint?.let { (dx, dy) -> extend(page, dx, dy) }
        }
    }

    /** The finger moved after a long press: the selection reaches to the word under it. */
    fun extend(page: Int, x: Float, y: Float) {
        dragPoint = x to y
        val current = selection?.takeIf { it.page == page } ?: return
        val from = anchor ?: return
        val text = texts[page] ?: return
        val index = text.charAt(x, y).takeIf { it >= 0 } ?: return
        val word = text.wordAt(index).takeUnless { it.isEmpty() } ?: (index..index)
        selection = current.copy(start = min(from.first, word.first), end = max(from.last, word.last))
    }

    /** Where the selection's handles sit on [page], as fractions: the start's bottom left and the end's bottom right. */
    fun handlePoints(page: Int): Pair<Pair<Float, Float>, Pair<Float, Float>>? {
        val s = selection?.takeIf { it.page == page } ?: return null
        val text = texts[page] ?: return null
        val rects = text.rects(s.start, s.end)
        if (rects.isEmpty()) return null
        return (rects.first().left to rects.first().bottom) to (rects.last().right to rects.last().bottom)
    }

    /**
     * Drags [handle] to the character at ([x], [y]). Dragging one past the other swaps them;
     * returns the handle that's being dragged from now on.
     */
    fun moveHandle(page: Int, handle: SelectionHandle, x: Float, y: Float): SelectionHandle {
        val s = selection?.takeIf { it.page == page } ?: return handle
        val text = texts[page] ?: return handle
        val index = text.charAt(x, y).takeIf { it >= 0 } ?: return handle
        return when {
            handle == SelectionHandle.Start && index <= s.end -> { selection = s.copy(start = index); handle }
            handle == SelectionHandle.Start -> { selection = s.copy(start = s.end, end = index); SelectionHandle.End }
            index >= s.start -> { selection = s.copy(end = index); handle }
            else -> { selection = s.copy(start = index, end = s.start); SelectionHandle.Start }
        }.also { anchor = null }
    }

    fun selectAll() {
        val page = selection?.page ?: return
        val text = texts[page]?.takeUnless { it.isEmpty } ?: return
        selection = TextSelection(page, 0, text.length - 1)
    }

    fun clearSelection() {
        selection = null
        anchor = null
        dragPoint = null
    }

    val selectedText: String
        get() {
            val s = selection ?: return ""
            return texts[s.page]?.textOf(s.start, s.end).orEmpty()
        }

    /** Whether a press at ([x], [y]) was meant for the character at [index]: on it or close by, not out in the margin. */
    private fun near(text: PdfPageText, index: Int, x: Float, y: Float): Boolean {
        val box = text.box(index)
        val dx = max(0f, max(box.left - x, x - box.right))
        val dy = max(0f, max(box.top - y, y - box.bottom))
        return dx < NEAR_X && dy < max(NEAR_Y, abs(box.bottom - box.top))
    }

    // endregion

    // region Search

    var searchOpen by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set

    /** Every match so far, in the order the pages are shown. */
    var matches by mutableStateOf<List<SearchMatch>>(emptyList())
        private set

    /** The match that was brought into view, or -1. */
    var currentMatch by mutableIntStateOf(-1)
        private set

    /** How much of the PDF has been searched (0..1) while a search runs; null otherwise. */
    var searchProgress by mutableStateOf<Float?>(null)
        private set

    val matchesByPage: Map<Int, List<IntRange>> by derivedStateOf { matches.groupBy({ it.page }, { it.range }) }

    private var searchJob: Job? = null

    fun openSearch() {
        clearSelection()
        searchOpen = true
    }

    /**
     * Looks for [text] on every page, in the order they're shown, shortly after the last
     * keystroke. The first match is brought into view as soon as it's found.
     */
    fun search(text: String) {
        query = text
        searchJob?.cancel()
        matches = emptyList()
        currentMatch = -1
        val needle = text.trim()
        if (needle.isEmpty()) {
            searchProgress = null
            return
        }
        searchJob = scope.launch {
            delay(SEARCH_DELAY_MS)
            searchProgress = 0f
            val pages = order.toList()
            val found = ArrayList<SearchMatch>()
            for ((n, page) in pages.withIndex()) {
                val hits = loadText(page).find(needle)
                if (hits.isNotEmpty()) {
                    hits.mapTo(found) { SearchMatch(page, it) }
                    matches = found.toList()
                    if (currentMatch < 0) showMatch(0)
                }
                searchProgress = (n + 1f) / pages.size
            }
            searchProgress = null
        }
    }

    fun nextMatch() {
        if (matches.isNotEmpty()) showMatch((currentMatch + 1).mod(matches.size))
    }

    fun previousMatch() {
        if (matches.isNotEmpty()) showMatch((currentMatch - 1).mod(matches.size))
    }

    fun closeSearch() {
        searchJob?.cancel()
        searchOpen = false
        query = ""
        matches = emptyList()
        currentMatch = -1
        searchProgress = null
    }

    /** Scrolls match [i] into view, a little below the top bar, at normal size. */
    private fun showMatch(i: Int) {
        currentMatch = i
        val match = matches.getOrNull(i) ?: return
        val place = order.indexOf(match.page).takeIf { it >= 0 } ?: return
        val text = texts[match.page] ?: return
        val aspect = aspects[match.page] ?: (text.width / text.height)
        val pageHeight = if (aspect > 0f) pageWidthPx / aspect else 0f
        val offset = (text.box(match.range.first).top * pageHeight - revealMarginPx).toInt().coerceAtLeast(0)
        zoom.reset()
        scope.launch { listState.animateScrollToItem(place, offset) }
    }

    // endregion

    private companion object {
        /** A long press this far from any text (fractions of the page) is in the margin, not on a word. */
        const val NEAR_X = 0.08f
        const val NEAR_Y = 0.03f

        const val SEARCH_DELAY_MS = 250L
    }
}
