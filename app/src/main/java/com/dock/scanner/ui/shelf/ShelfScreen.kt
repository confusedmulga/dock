package com.dock.scanner.ui.shelf

import androidx.compose.foundation.shape.CircleShape
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dock.scanner.R
import com.dock.scanner.data.storage.LibraryItem
import com.dock.scanner.ui.LibraryViewModel
import com.dock.scanner.ui.components.ChromeButton
import com.dock.scanner.ui.components.EmptyMessage
import com.dock.scanner.ui.components.ItemDialogState
import com.dock.scanner.ui.components.LargeTitle
import com.dock.scanner.ui.components.PdfActions
import com.dock.scanner.ui.components.PdfRow
import com.dock.scanner.ui.components.SectionHeader
import com.dock.scanner.ui.components.DockDropdownMenu
import com.dock.scanner.ui.components.DockMenuItem

/** Folders inside the Dock folder, as real directories on the device. */
@Composable
fun ShelfScreen(
    vm: LibraryViewModel,
    actions: PdfActions,
    dialogs: ItemDialogState,
    bottomInset: Dp,
    onChangeLocation: () -> Unit,
    /** Whether the Shelf is the tab in front: it stays composed beside its neighbours, where back isn't its to handle. */
    active: Boolean = true,
    listState: LazyListState = rememberLazyListState(),
) {
    val path by vm.shelfPath.collectAsStateWithLifecycle()
    val contents by vm.shelfContents.collectAsStateWithLifecycle()
    val rootLabel by vm.rootLabel.collectAsStateWithLifecycle()
    val current = path.lastOrNull()
    BackHandler(enabled = active && current != null) { vm.closeFolder() }
    // A folder's list starts at its top, not where the one before it was scrolled to. Coming back
    // to the tab is not a folder change, so the place it was left at is kept.
    var shownFolder by rememberSaveable { mutableStateOf(current?.documentId) }
    LaunchedEffect(current?.documentId) {
        if (shownFolder != current?.documentId) {
            shownFolder = current?.documentId
            listState.scrollToItem(0)
        }
    }

    val folders = contents.orEmpty().filter { it.isFolder }
    val files = contents.orEmpty().filterNot { it.isFolder }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = top + 8.dp, bottom = bottomInset),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            Column {
                if (current != null) {
                    // Where am I, and how do I get out: the parent's name is the way back.
                    val parentName = path.getOrNull(path.size - 2)?.name ?: "Shelf"
                    Row(
                        Modifier
                            .padding(start = 8.dp, top = 4.dp)
                            .clip(CircleShape)
                            .clickable(onClickLabel = "Back to $parentName") { vm.closeFolder() }
                            .padding(end = 12.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                        Text(parentName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
                var menu by remember { mutableStateOf(false) }
                LargeTitle(
                    title = current?.name ?: "Shelf",
                    subtitle = when {
                        current == null -> rootLabel
                        folders.isEmpty() && files.isEmpty() -> null
                        else -> countLabel(folders.size, files.count { it.isPdf }, files.count { it.isImage }, files.count { it.isDocument })
                    },
                ) {
                    ChromeButton(
                        painterResource(R.drawable.ic_folder_add),
                        contentDescription = "New folder",
                        onClick = { dialogs.creatingFolder = true },
                    )
                    if (current == null) {
                        Box {
                            ChromeButton(
                                androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Filled.MoreVert),
                                contentDescription = "Shelf options",
                                onClick = { menu = true },
                            )
                            DockDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DockMenuItem(
                                    text = { Text("Change storage folder") },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_folder_outline), null) },
                                    onClick = { menu = false; onChangeLocation() },
                                )
                            }
                        }
                    }
                }
            }
        }

        when {
            contents == null -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            folders.isEmpty() && files.isEmpty() -> item(key = "empty") {
                if (current == null) {
                    EmptyMessage(
                        "Your shelf is empty",
                        "Create folders to sort your scans, like Receipts or Bills. They're real folders you can also open in your file manager.",
                        icon = painterResource(R.drawable.ic_folder_outline),
                    )
                } else {
                    EmptyMessage(
                        "Empty folder",
                        "Scan while this folder is open, or move files here from the Files tab.",
                        icon = painterResource(R.drawable.ic_folder_outline),
                    )
                }
            }
            else -> {
                if (folders.isNotEmpty()) {
                    item(key = "folders-title") { SectionHeader("Folders") }
                    // Two tiles per row; 14 dp row inset + 6 dp tile inset = the 20 dp title margin.
                    items(folders.chunked(2), key = { row -> row.first().documentId }) { row ->
                        // Both tiles are as tall as the taller one (a long name takes two lines).
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 14.dp)) {
                            row.forEach { folder ->
                                FolderTile(
                                    folder,
                                    onOpen = { vm.openFolder(folder) },
                                    onRename = { dialogs.renaming = folder },
                                    onDelete = { dialogs.deleting = folder },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                if (files.isNotEmpty()) {
                    item(key = "files-title") {
                        SectionHeader(
                            when {
                                current == null -> "Not in a folder"
                                files.all { it.isImage } -> "Images"
                                files.all { it.isPdf } -> "PDFs"
                                files.all { it.isDocument } -> "Documents"
                                else -> "Files"
                            },
                        )
                    }
                    items(files, key = { it.documentId }) {
                        PdfRow(it, actions, showFolder = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderTile(
    folder: LibraryItem,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.large
    Box(modifier.padding(6.dp)) {
        Column(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .clickable(onClickLabel = "Open ${folder.name}", onClick = onOpen)
                .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    painterResource(R.drawable.ic_folder),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp).padding(top = 2.dp),
                )
                Box(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More actions for ${folder.name}", modifier = Modifier.size(20.dp))
                    }
                    DockDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DockMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menu = false; onRename() },
                        )
                        DockMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
            Text(
                folder.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp, end = 12.dp),
            )
            Text(
                if (folder.childCount == 1) "1 item" else "${folder.childCount} items",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun countLabel(folders: Int, pdfs: Int, images: Int, documents: Int): String = buildList {
    if (folders > 0) add(if (folders == 1) "1 folder" else "$folders folders")
    if (pdfs > 0 || images + documents == 0) add(if (pdfs == 1) "1 PDF" else "$pdfs PDFs")
    if (documents > 0) add(if (documents == 1) "1 document" else "$documents documents")
    if (images > 0) add(if (images == 1) "1 image" else "$images images")
}.joinToString(" · ")
