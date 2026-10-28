package com.dock.scanner.ui.review

import androidx.compose.foundation.shape.CircleShape
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import com.dock.scanner.ui.editor.SheetView
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.dock.scanner.R
import com.dock.scanner.data.Page
import com.dock.scanner.ui.Destination
import com.dock.scanner.ui.LibraryViewModel
import com.dock.scanner.ui.ScanViewModel
import com.dock.scanner.ui.components.EmptyMessage
import com.dock.scanner.ui.components.GlassBubble
import com.dock.scanner.ui.components.LargeTitle
import com.dock.scanner.ui.components.SharedKeys
import com.dock.scanner.ui.components.StatusBarBlur
import com.dock.scanner.ui.components.sharedPage
import com.dock.scanner.ui.theme.DockMotion
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/** The scan in progress: check pages, add more, then save it as a PDF to the library. */
@Composable
fun ReviewScreen(
    scanVm: ScanViewModel,
    libraryVm: LibraryViewModel,
    onAddPages: () -> Unit,
    onOpenPage: (String) -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val all by scanVm.pages.collectAsStateWithLifecycle()
    val pages by scanVm.sheets.collectAsStateWithLifecycle()
    val pagesById = remember(all) { all.associateBy { it.id } }
    val saving by scanVm.exporting.collectAsStateWithLifecycle()
    val reordering by scanVm.reorderMode.collectAsStateWithLifecycle()
    val editing by scanVm.editTarget.collectAsStateWithLifecycle()
    val destinations by libraryVm.destinations.collectAsStateWithLifecycle()
    val root by libraryVm.root.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }
    var saveSheet by remember { mutableStateOf(false) }
    val hazeState = remember { HazeState() }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) scanVm.importImages(uris)
    }
    fun leave() = when {
        reordering -> scanVm.reorderMode.value = false
        all.isEmpty() -> onFinished()
        else -> confirmDiscard = true
    }
    BackHandler { leave() }

    // Drag-and-drop state. Card bounds are recorded in grid coordinates as they're laid out.
    val gridState = rememberLazyGridState()
    val bounds = remember { mutableMapOf<String, Rect>() }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var gridHeight by remember { mutableFloatStateOf(0f) }
    /** After a move, wait until the dragged card has been laid out in its new slot. */
    var awaitingSlot by remember { mutableStateOf<Offset?>(null) }
    /**
     * Converts the grid's layout offsets into pointer coordinates. Hit-testing uses layout slots
     * rather than drawn positions: those include the drag translation and the neighbours'
     * slide animations, which would make cards swap back and forth.
     */
    var slotOrigin by remember { mutableStateOf(Offset.Zero) }
    fun slot(key: String): Rect? = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let {
        Rect(Offset(it.offset.x.toFloat(), it.offset.y.toFloat()) + slotOrigin, it.size.toSize())
    }

    // Auto-scroll while a card is held near the top or bottom edge.
    LaunchedEffect(dragKey) {
        val edge = 90f * context.resources.displayMetrics.density
        while (dragKey != null) {
            val speed = when {
                pointerY < edge -> -(edge - pointerY) / edge * 18f
                pointerY > gridHeight - edge -> (pointerY - (gridHeight - edge)) / edge * 18f
                else -> 0f
            }
            if (speed != 0f) {
                val consumed = gridState.scrollBy(speed)
                dragOffset += Offset(0f, consumed)
            }
            delay(16)
        }
    }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 100.dp

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = top, bottom = bottom),
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .onSizeChanged { gridHeight = it.height.toFloat() }
                .pointerInput(reordering) {
                    if (!reordering) return@pointerInput
                    detectDragGestures(
                        onDragStart = { pos ->
                            val hit = bounds.entries.firstOrNull { (key, r) -> key in pagesById && gridState.isVisible(key) && r.contains(pos) }
                            dragKey = hit?.key
                            if (hit != null) {
                                val info = gridState.layoutInfo.visibleItemsInfo.first { it.key == hit.key }
                                slotOrigin = hit.value.topLeft - Offset(info.offset.x.toFloat(), info.offset.y.toFloat())
                            }
                            dragOffset = Offset.Zero
                            pointerY = pos.y
                            awaitingSlot = null
                            if (dragKey != null) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDrag = { change, amount ->
                            val key = dragKey ?: return@detectDragGestures
                            change.consume()
                            dragOffset += amount
                            pointerY = change.position.y
                            val home = slot(key) ?: return@detectDragGestures
                            awaitingSlot?.let { expected ->
                                if ((home.topLeft - expected).getDistance() > 2f) return@detectDragGestures
                                awaitingSlot = null
                            }
                            val center = home.center + dragOffset
                            val target = gridState.layoutInfo.visibleItemsInfo
                                .mapNotNull { info -> (info.key as? String)?.takeIf { it != key && it in pagesById } }
                                .firstNotNullOfOrNull { k -> slot(k)?.takeIf { it.contains(center) }?.let { k to it } }
                            if (target != null) {
                                // The card jumps into the target's slot; shift the offset so it stays under the finger.
                                dragOffset += home.topLeft - target.second.topLeft
                                awaitingSlot = target.second.topLeft
                                scanVm.moveSheet(key, target.first)
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onDragEnd = { dragKey = null; dragOffset = Offset.Zero },
                        onDragCancel = { dragKey = null; dragOffset = Offset.Zero },
                    )
                },
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                // The grid is inset 14 dp; 6 dp more puts text on the same 20 dp line as other screens.
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!reordering) {
                            TextButton(onClick = ::leave, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Cancel") }
                        }
                        Spacer(Modifier.weight(1f))
                        if (reordering) {
                            TextButton(onClick = { scanVm.reorderMode.value = false }) { Text("Done") }
                        } else if (pages.size > 1) {
                            TextButton(onClick = { scanVm.reorderMode.value = true }) { Text("Reorder") }
                        }
                    }
                    LargeTitle(
                        when {
                            reordering -> "Reorder pages"
                            editing != null -> editing!!.name
                            else -> "New scan"
                        },
                        subtitle = when {
                            reordering -> "Drag pages into the order you want"
                            pages.isEmpty() -> null
                            else -> "${pages.size} page${if (pages.size == 1) "" else "s"} · tap a page to edit it"
                        },
                        start = 6.dp,
                    )
                }
            }
            if (pages.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    EmptyMessage("No pages", "Add pages with the camera or from your photos.")
                }
            }
            itemsIndexed(pages, key = { _, p -> p.id }) { i, page ->
                val dragging = page.id == dragKey
                PageCard(
                    number = i + 1,
                    page = page,
                    pagesById = pagesById,
                    reordering = reordering,
                    onClick = { onOpenPage(page.id) },
                    modifier = Modifier
                        .then(if (dragging) Modifier else Modifier.animateItem())
                        .onGloballyPositioned { bounds[page.id] = Rect(it.positionInParent(), it.size.toSize()) }
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            if (dragging) {
                                translationX = dragOffset.x
                                translationY = dragOffset.y
                                scaleX = 1.05f
                                scaleY = 1.05f
                                shadowElevation = 24f
                            }
                        },
                )
            }
        }

        StatusBarBlur(hazeState, Modifier.align(Alignment.TopCenter))

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (reordering) {
                SaveCapsule(enabled = true, busy = false, label = "Done", onClick = { scanVm.reorderMode.value = false })
            } else {
                GlassBubble(
                    hazeState,
                    painterResource(R.drawable.ic_gallery),
                    contentDescription = "Add pages from photos",
                    onClick = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
                Spacer(Modifier.width(10.dp))
                GlassBubble(hazeState, painterResource(R.drawable.ic_camera), contentDescription = "Add pages with the camera", onClick = onAddPages)
                Spacer(Modifier.width(10.dp))
                SaveCapsule(enabled = pages.isNotEmpty() && !saving, busy = saving, onClick = { saveSheet = true })
            }
        }
    }

    if (saveSheet && root != null) {
        val original = editing?.item
        SaveSheet(
            suggestedName = remember { editing?.name ?: scanVm.suggestedFileName() },
            destinations = destinations,
            preselected = scanVm.targetFolderId,
            canReplace = original != null,
            onDismiss = { saveSheet = false },
            onSave = { name, destination, replace ->
                saveSheet = false
                val done = { where: String ->
                    android.widget.Toast.makeText(context, where, android.widget.Toast.LENGTH_SHORT).show()
                    libraryVm.refresh()
                    onFinished()
                }
                if (replace && original != null) {
                    scanVm.saveOverOriginal(libraryVm.library, original, name) { done("Saved “$it”") }
                } else {
                    scanVm.saveToLibrary(libraryVm.library, root!!, destination.documentId, name) { done("Saved to ${destination.name}") }
                }
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(if (editing != null) "Discard changes?" else "Discard this scan?") },
            text = {
                Text(
                    if (editing != null) "Your changes to “${editing!!.name}” will be lost. The original PDF isn't touched."
                    else "${if (all.size == 1) "The page" else "The ${all.size} pages"} you scanned will be deleted. Nothing has been saved yet.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    scanVm.clear()
                    onFinished()
                }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

/** The one solid, colored control: the primary action stands apart from the glass chrome. */
@Composable
private fun SaveCapsule(enabled: Boolean, busy: Boolean, onClick: () -> Unit, label: String = "Save PDF") {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, DockMotion.press(), label = "saveScale")
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.4f),
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = CircleShape,
        shadowElevation = 10.dp,
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(60.dp)
            .clip(CircleShape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(if (busy) "Saving…" else label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveSheet(
    suggestedName: String,
    destinations: List<Destination>,
    preselected: String?,
    canReplace: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Destination, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(suggestedName) }
    var replace by remember { mutableStateOf(canReplace) }
    var target by remember(destinations) {
        mutableStateOf(destinations.firstOrNull { it.documentId == preselected } ?: destinations.firstOrNull())
    }
    val valid = name.isNotBlank() && name.none { it in "/\\:*?\"<>|" } && target != null
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Save PDF", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                suffix = { Text(".pdf") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            if (canReplace) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { replace = !replace }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Replace original", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (replace) "Overwrites the PDF you opened" else "Saves a new PDF and keeps the original",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = replace, onCheckedChange = { replace = it })
                }
            }
            if (!replace) {
                Spacer(Modifier.height(18.dp))
                Text("Save to", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    destinations.forEachIndexed { i, destination ->
                        FilterChip(
                            selected = destination == target,
                            onClick = { target = destination },
                            label = { Text(if (i == 0) "${destination.name} (main)" else destination.name) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { onSave(name.trim(), target!!, replace) },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun PageCard(
    number: Int,
    page: Page,
    pagesById: Map<String, Page>,
    reordering: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.large
    Box(
        modifier
            .padding(6.dp)
            .fillMaxWidth()
            .aspectRatio(0.75f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (reordering) Modifier else Modifier.clickable(onClickLabel = "Edit page $number", onClick = onClick))
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        val layout = page.layout
        val shared = Modifier.sharedPage(SharedKeys.page(page.id))
        if (layout != null) {
            SheetView(layout, pagesById, shared)
        } else if (page.output != null) {
            // Sized to the page itself (not the whole card), so it lines up with the editor's page.
            AsyncImage(
                model = page.output,
                contentDescription = "Page $number",
                contentScale = ContentScale.Crop,
                modifier = shared.aspectRatio(page.aspect),
            )
        }
        if (pagesById.values.any { it.processing && (it.id == page.id || it.hostId == page.id) }) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.75f),
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Text("$number", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp))
        }
        if (reordering) {
            Icon(
                painterResource(R.drawable.ic_reorder),
                contentDescription = "Drag to reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopEnd).size(20.dp),
            )
        }
    }
}

/** Card bounds of items scrolled out of view are stale, so only visible cards take part in drag and drop. */
private fun LazyGridState.isVisible(key: String) = layoutInfo.visibleItemsInfo.any { it.key == key }
