package com.dock.scanner.ui.files

import androidx.compose.foundation.shape.CircleShape
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dock.scanner.ui.LibraryViewModel
import com.dock.scanner.ui.components.ChromeButton
import com.dock.scanner.ui.components.EmptyMessage
import com.dock.scanner.ui.components.LargeTitle
import com.dock.scanner.ui.components.PdfActions
import com.dock.scanner.ui.components.PdfRow
import com.dock.scanner.ui.components.SectionHeader
import com.dock.scanner.ui.components.dateSection
import com.dock.scanner.ui.theme.DockMotion

/** Every PDF and document in the library, newest first, with search across names and folders. */
@Composable
fun FilesScreen(vm: LibraryViewModel, actions: PdfActions, bottomInset: Dp) {
    val pdfs by vm.allFiles.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }

    val results = remember(pdfs, query) {
        val q = query.trim()
        if (q.isEmpty()) pdfs.orEmpty()
        else pdfs.orEmpty().filter {
            it.displayName.contains(q, ignoreCase = true) || it.folderName?.contains(q, ignoreCase = true) == true
        }
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = top + 8.dp, bottom = bottomInset),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "title") {
            LargeTitle(
                title = "Files",
                subtitle = pdfs?.takeIf { it.isNotEmpty() }?.let { files ->
                    val count = files.count { it.isPdf }
                    val documents = files.size - count
                    listOfNotNull(
                        if (count == 1) "1 PDF" else "$count PDFs",
                        documents.takeIf { it > 0 }?.let { if (it == 1) "1 document" else "$it documents" },
                    ).joinToString(" · ")
                },
            ) {
                if (!searching) {
                    ChromeButton(
                        rememberVectorPainter(Icons.Filled.Search),
                        contentDescription = "Search files",
                        onClick = { searching = true },
                    )
                }
            }
        }
        item(key = "search") {
            AnimatedVisibility(
                visible = searching,
                enter = expandVertically(DockMotion.standard()) + fadeIn(DockMotion.standard()),
                exit = shrinkVertically(DockMotion.standard()) + fadeOut(DockMotion.standard()),
            ) {
                SearchField(query, onQueryChange = { query = it }, onCancel = { searching = false; query = "" })
            }
        }

        when {
            pdfs == null -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            pdfs!!.isEmpty() -> item(key = "empty") {
                EmptyMessage("No files yet", "Scan a document or use a tool on Home. Every PDF you make is saved in your Dock folder and shows up here, along with any Word, Excel or PowerPoint files you put there.")
            }
            query.isNotBlank() -> {
                if (results.isEmpty()) {
                    item(key = "noresults") { EmptyMessage("No results", "Nothing matches “${query.trim()}”.") }
                }
                items(results, key = { it.documentId }) { PdfRow(it, actions, showFolder = true) }
            }
            else -> {
                results.groupBy { dateSection(it.lastModified) }.forEach { (section, items) ->
                    item(key = "section-$section") { SectionHeader(section) }
                    items(items, key = { it.documentId }) { PdfRow(it, actions, showFolder = true) }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                if (query.isEmpty()) {
                    Text("Search files and folders", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.size(4.dp))
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun rememberVectorPainter(image: androidx.compose.ui.graphics.vector.ImageVector) =
    androidx.compose.ui.graphics.vector.rememberVectorPainter(image)
