package com.dock.scanner.ui.viewer

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dock.scanner.R
import com.dock.scanner.data.storage.LibraryItem
import com.dock.scanner.data.storage.PdfPreviews
import com.dock.scanner.ui.LibraryViewModel
import com.dock.scanner.ui.components.DockDropdownMenu
import com.dock.scanner.ui.components.DockMenuDivider
import com.dock.scanner.ui.components.DockMenuItem
import com.dock.scanner.ui.components.ItemDialogs
import com.dock.scanner.ui.components.OcrMenuItem
import com.dock.scanner.ui.components.SharedKeys
import com.dock.scanner.ui.components.rememberItemDialogState
import com.dock.scanner.ui.components.shareDocument
import kotlin.math.sqrt

/** Android won't draw a bitmap over 100 MB; 16 MP (64 MB) keeps very tall pages well below that. */
private const val MAX_PAGE_PIXELS = 16_000_000.0

/** Pixel size for rendering a page of [pageW] x [pageH] points [widthPx] wide, scaled down if it would be too big. */
internal fun pageRenderSize(widthPx: Int, pageW: Int, pageH: Int): Pair<Int, Int> {
    val height = widthPx.toDouble() * pageH / pageW
    val shrink = minOf(1.0, sqrt(MAX_PAGE_PIXELS / (widthPx * height)))
    return (widthPx * shrink).toInt().coerceAtLeast(1) to (height * shrink).toInt().coerceAtLeast(1)
}

/**
 * A PDF from the library in the [PdfViewer], with its file actions under the name (rename,
 * folder, edit, compress, searchable text, delete), share in the ⋮ menu, and pages that can
 * be rearranged and saved back to the file.
 */
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
    val dialogs = rememberItemDialogState()
    val destinations by vm.destinations.collectAsStateWithLifecycle()
    val revision by vm.viewingRevision.collectAsStateWithLifecycle()

    PdfViewer(
        uri = item.uri,
        version = item.lastModified to revision,
        title = item.displayName,
        onBack = onBack,
        titleMenu = { expanded, onDismiss ->
            FileMenu(
                expanded = expanded,
                item = item,
                onDismiss = onDismiss,
                onRename = { dialogs.renaming = item },
                onMove = { dialogs.moving = item },
                onEdit = { onEdit(item) },
                onCompress = { onCompress(item) },
                onDelete = { dialogs.deleting = item },
            )
        },
        moreItems = { dismiss ->
            DockMenuItem(
                text = { Text("Share") },
                leadingIcon = { Icon(Icons.Filled.Share, null) },
                onClick = {
                    dismiss()
                    shareDocument(context, item.uri, item.name)
                },
            )
        },
        onSaveOrder = { order, done -> vm.reorderPages(item, order, done) },
        // The list's thumbnail stands in for the first page until it's rendered, so the page
        // that grows out of the list isn't blank.
        firstPage = remember(item.uri, item.lastModified) { PdfPreviews.cached(item.uri, item.lastModified)?.thumbnail },
        firstPageKey = SharedKeys.pdf(item.documentId, vm.viewingFrom),
    )

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
    DockDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DockMenuItem(
            text = { Text("Rename") },
            leadingIcon = { Icon(Icons.Filled.Edit, null) },
            onClick = { run(onRename) },
        )
        val folder = item.folderName
        DockMenuItem(
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
            DockMenuItem(
                text = { Text("Edit pages") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_edit_doc), null) },
                onClick = { run(edit) },
            )
        }
        onCompress?.let { compress ->
            DockMenuItem(
                text = { Text("Compress") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_compress), null) },
                onClick = { run(compress) },
            )
        }
        if (item.isPdf) OcrMenuItem(item, onDismiss)
        DockMenuDivider()
        DockMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
            onClick = { run(onDelete) },
        )
    }
}
