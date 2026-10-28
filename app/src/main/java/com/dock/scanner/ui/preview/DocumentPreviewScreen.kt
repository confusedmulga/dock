package com.dock.scanner.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dock.scanner.office.Presentation
import com.dock.scanner.office.TextDocument
import com.dock.scanner.office.Workbook
import com.dock.scanner.ui.LibraryViewModel
import com.dock.scanner.ui.components.EmptyMessage
import com.dock.scanner.ui.components.GlassBubble
import com.dock.scanner.ui.components.ItemDialogs
import com.dock.scanner.ui.components.glass
import com.dock.scanner.ui.components.rememberItemDialogState
import com.dock.scanner.ui.components.shareDocument
import com.dock.scanner.ui.viewer.FileMenu
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/** A document from the library, with its file actions (rename, folder, delete) under the name. */
@Composable
fun DocumentPreviewScreen(vm: LibraryViewModel, onBack: () -> Unit) {
    val viewing by vm.viewing.collectAsStateWithLifecycle()
    val item = viewing
    LaunchedEffect(item == null) { if (item == null) onBack() }
    if (item == null) return

    val context = LocalContext.current
    val model: DocumentPreviewModel = viewModel()
    // Renaming or moving gives the file a new URI; the content is the same, so keep it open.
    val firstUri = remember { item.uri }
    LaunchedEffect(Unit) { model.load(firstUri, item.name, item.mimeType) }
    val state by model.state.collectAsStateWithLifecycle()
    val dialogs = rememberItemDialogState()
    val destinations by vm.destinations.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }

    PreviewFrame(
        title = item.displayName,
        onBack = onBack,
        onShare = { shareDocument(context, item.uri, item.name, item.mimeType.ifEmpty { "application/octet-stream" }) },
        titleMenu = {
            FileMenu(
                expanded = menu,
                item = item,
                onDismiss = { menu = false },
                onRename = { dialogs.renaming = item },
                onMove = { dialogs.moving = item },
                onDelete = { dialogs.deleting = item },
            )
        },
        onTitleClick = { menu = true },
    ) { padding -> PreviewContent(state, padding) }
    ItemDialogs(dialogs, vm, destinations)
}

/**
 * The preview's chrome over its [content]: a back bubble, the name in a glass pill (with an
 * optional menu), extra [actions], and share. The content scrolls underneath and blurs
 * behind the chrome; it gets the padding that keeps it clear of the chrome at rest.
 */
@Composable
fun PreviewFrame(
    title: String,
    onBack: () -> Unit,
    onShare: () -> Unit,
    titleMenu: (@Composable () -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable (HazeState) -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val hazeState = remember { HazeState() }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val padding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 76.dp, bottom = bottom + 24.dp)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
            content(padding)
        }
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
                            .then(if (onTitleClick != null) Modifier.clickable(onClickLabel = "File options", onClick = onTitleClick) else Modifier)
                            .padding(start = 16.dp, end = if (onTitleClick != null) 8.dp else 16.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (onTitleClick != null) Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(24.dp))
                    }
                    titleMenu?.invoke()
                }
            }
            actions(hazeState)
            GlassBubble(hazeState, rememberVectorPainter(Icons.Filled.Share), "Share", onShare, size = 48.dp)
        }
    }
}

/** A loaded document drawn the way its kind reads best, or loading/error states. */
@Composable
fun PreviewContent(state: PreviewState, padding: PaddingValues) {
    when (state) {
        PreviewState.Loading -> Centered {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Opening…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is PreviewState.Failed -> Centered { EmptyMessage("Can't show this file", state.message) }
        is PreviewState.Ready -> when (val doc = state.opened.document) {
            is TextDocument -> TextDocumentView(doc, padding)
            is Presentation -> PresentationView(doc, padding)
            is Workbook -> WorkbookView(doc, PaddingValues(top = padding.calculateTopPadding() - 8.dp, bottom = padding.calculateBottomPadding() - 24.dp))
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}
