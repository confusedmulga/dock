package com.dock.scanner.ui.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dock.scanner.R
import com.dock.scanner.data.storage.LibraryItem
import com.dock.scanner.data.storage.PdfPreviews
import com.dock.scanner.ui.LibraryViewModel
import com.dock.scanner.ui.components.EmptyMessage
import com.dock.scanner.ui.components.GlassBubble
import com.dock.scanner.ui.components.ItemDialogs
import com.dock.scanner.ui.components.ZoomableColumn
import com.dock.scanner.ui.components.SharedKeys
import com.dock.scanner.ui.components.glass
import com.dock.scanner.ui.components.rememberItemDialogState
import com.dock.scanner.ui.components.sharedPage
import com.dock.scanner.ui.components.shareDocument
import com.dock.scanner.ui.theme.DockColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** Android won't draw a bitmap over 100 MB; 16 MP (64 MB) keeps very tall pages well below that. */
private const val MAX_PAGE_PIXELS = 16_000_000.0

/** Pixel size for rendering a page of [pageW] x [pageH] points [widthPx] wide, scaled down if it would be too big. */
internal fun pageRenderSize(widthPx: Int, pageW: Int, pageH: Int): Pair<Int, Int> {
    val height = widthPx.toDouble() * pageH / pageW
    val shrink = minOf(1.0, sqrt(MAX_PAGE_PIXELS / (widthPx * height)))
    return (widthPx * shrink).toInt().coerceAtLeast(1) to (height * shrink).toInt().coerceAtLeast(1)
}

/** Shows a saved PDF, zoomable, with its file actions in a dropdown under the name. */
@Composable
fun PdfViewerScreen(
    vm: LibraryViewModel,
    onBack: () -> Unit,
    onEdit: (LibraryItem) -> Unit,
    onCompress: (LibraryItem) -> Unit,
) {
    val viewing by vm.viewing.collectAsStateWithLifecycle()
    val item = viewing
    // Deleted (or never set, e.g. after the app was restarted): leave.
    LaunchedEffect(item == null) { if (item == null) onBack() }
    if (item == null) return

    val context = LocalContext.current
    val hazeState = remember { HazeState() }
    val dialogs = rememberItemDialogState()
    val destinations by vm.destinations.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        ZoomablePdf(
            uri = item.uri,
            // The list's thumbnail stands in for the first page until it's rendered, so the
            // page that grows out of the list isn't blank.
            firstPage = remember(item.uri) { PdfPreviews.cached(item.uri, item.lastModified)?.thumbnail },
            firstPageKey = SharedKeys.pdf(item.documentId, vm.viewingFrom),
            modifier = Modifier.fillMaxSize().hazeSource(hazeState),
        )

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
                            .clickable(onClickLabel = "File options") { menu = true }
                            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            item.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(24.dp))
                    }
                    FileMenu(
                        expanded = menu,
                        item = item,
                        onDismiss = { menu = false },
                        onRename = { dialogs.renaming = item },
                        onMove = { dialogs.moving = item },
                        onEdit = { onEdit(item) },
                        onCompress = { onCompress(item) },
                        onDelete = { dialogs.deleting = item },
                    )
                }
            }
            GlassBubble(hazeState, rememberVectorPainter(Icons.Filled.Share), "Share", { shareDocument(context, item.uri, item.name) }, size = 48.dp)
        }
    }

    ItemDialogs(dialogs, vm, destinations)
}

/** The file actions under a viewer's name pill; [onEdit] and [onCompress] are PDF-only. */
@Composable
internal fun FileMenu(
    expanded: Boolean,
    item: LibraryItem,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onCompress: (() -> Unit)? = null,
) {
    fun run(action: () -> Unit) {
        onDismiss()
        action()
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Rename") },
            leadingIcon = { Icon(Icons.Filled.Edit, null) },
            onClick = { run(onRename) },
        )
        val folder = item.folderName
        DropdownMenuItem(
            text = {
                if (folder != null) {
                    // Already filed: show where, in blue; tapping lets you pick another folder.
                    Column {
                        Text(folder, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Change folder", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text("Add to folder")
                }
            },
            leadingIcon = {
                Icon(
                    painterResource(if (folder != null) R.drawable.ic_folder else R.drawable.ic_folder_add),
                    null,
                    tint = if (folder != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            onClick = { run(onMove) },
        )
        onEdit?.let { edit ->
            DropdownMenuItem(
                text = { Text("Edit pages") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_edit_doc), null) },
                onClick = { run(edit) },
            )
        }
        onCompress?.let { compress ->
            DropdownMenuItem(
                text = { Text("Compress") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_compress), null) },
                onClick = { run(compress) },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
            onClick = { run(onDelete) },
        )
    }
}

/** The PDF's pages in a [ZoomableColumn]; the first one shares its element with the list thumbnail. */
@Composable
fun ZoomablePdf(uri: Uri, modifier: Modifier, firstPage: Bitmap? = null, firstPageKey: String? = null) {
    val context = LocalContext.current
    // PdfRenderer allows one open page at a time, so all rendering is serialized.
    val document = remember(uri) {
        runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.let(::PdfRenderer) }.getOrNull()
    }
    val lock = remember(uri) { Mutex() }
    DisposableEffect(document) {
        // Wait for any page still rendering before closing the file.
        onDispose { CoroutineScope(Dispatchers.IO).launch { lock.withLock { document?.close() } } }
    }
    if (document == null) {
        Box(modifier, contentAlignment = Alignment.Center) { EmptyMessage("Can't open this PDF", "It may have been moved or deleted.") }
        return
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current
    ZoomableColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 76.dp, bottom = bottom + 24.dp),
    ) { pageWidth ->
        // Rendered sharper than the layout, so zooming in stays crisp for a while.
        val renderWidthPx = (with(density) { pageWidth.roundToPx() } * 1.75f).toInt().coerceAtMost(2000)
        items(document.pageCount) { index ->
            if (index == 0 && firstPageKey != null) {
                PdfPage(document, lock, index, renderWidthPx, placeholder = firstPage, modifier = Modifier.sharedPage(firstPageKey))
            } else {
                PdfPage(document, lock, index, renderWidthPx)
            }
        }
    }
}

@Composable
private fun PdfPage(
    document: PdfRenderer,
    lock: Mutex,
    index: Int,
    widthPx: Int,
    modifier: Modifier = Modifier,
    placeholder: Bitmap? = null,
) {
    val page by produceState<Bitmap?>(null, index, widthPx) {
        value = withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    document.openPage(index).use { p ->
                        val (width, height) = pageRenderSize(widthPx, p.width, p.height)
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                            eraseColor(Color.WHITE)
                            p.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }.getOrNull()
            }
        }
    }
    val shape = MaterialTheme.shapes.extraSmall
    val bitmap = page ?: placeholder
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(bitmap?.let { it.width.toFloat() / it.height } ?: (1 / 1.414f))
            .shadow(3.dp, shape)
            .clip(shape)
            .background(DockColors.Paper),
    ) {
        if (bitmap != null) {
            // Crop rather than stretch: while the page morphs from a list thumbnail, its box
            // briefly has the thumbnail's shape. At rest the box matches the page exactly.
            Image(bitmap.asImageBitmap(), contentDescription = "Page ${index + 1}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
