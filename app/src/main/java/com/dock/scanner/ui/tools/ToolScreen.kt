package com.dock.scanner.ui.tools

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dock.scanner.R
import com.dock.scanner.data.storage.LibraryItem
import com.dock.scanner.data.storage.PdfPreviews
import com.dock.scanner.data.tools.PickPdfs
import com.dock.scanner.data.tools.SourcePdf
import com.dock.scanner.pdf.PdfTools
import com.dock.scanner.ui.components.EmptyMessage
import com.dock.scanner.ui.components.LargeTitle
import com.dock.scanner.ui.components.PdfThumbnail
import com.dock.scanner.ui.components.SectionHeader
import com.dock.scanner.ui.components.friendlyDate
import com.dock.scanner.ui.components.rememberPdfPreview
import com.dock.scanner.ui.components.shareDocument
import com.dock.scanner.ui.theme.Accent
import com.dock.scanner.ui.theme.ToolAccent
import com.dock.scanner.ui.theme.toolAccent
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** @param prompt what to do on the list of PDFs. */
private data class ToolInfo(val title: String, val description: String, val prompt: String, val action: String, val accent: ToolAccent)

private fun PdfTool.info() = when (this) {
    PdfTool.Edit -> ToolInfo("Edit PDF", "Crop, filter, mark up or rearrange a PDF's pages.", "Choose the PDF to edit.", "Edit", ToolAccent.Edit)
    PdfTool.Compress -> ToolInfo("Compress PDF", "Shrinks the pictures inside a PDF. Text stays sharp and selectable.", "Choose the PDF to compress.", "Compress", ToolAccent.Compress)
    PdfTool.ToImages -> ToolInfo("PDF to images", "Every page becomes a picture. Each PDF gets its own folder.", "Choose the PDFs to turn into images.", "Convert", ToolAccent.ToImages)
    PdfTool.Merge -> ToolInfo("Merge PDFs", "Combine PDFs into one, in the order shown.", "Choose the PDFs to merge, in order.", "Merge", ToolAccent.Merge)
    PdfTool.Split -> ToolInfo("Split PDF", "Save pages or page ranges as separate PDFs.", "Choose the PDF to split.", "Split", ToolAccent.Split)
}

private enum class CompressMode { Preset, Custom, TargetSize }

/** Position of the options in the options step's list, before the inputs are counted: header, title, add button. */
private const val OPTIONS_INDEX = 3

/** Units for a target size. Decimal, like the sizes Android shows (and so never over a binary limit). */
private enum class SizeUnit(val bytes: Long) { KB(1_000), MB(1_000_000) }

/**
 * A PDF tool in two steps: choose PDFs from the library (or "Add files" from anywhere else),
 * then set the options and run it. Opened for a particular file, it starts on the options.
 */
@Composable
fun ToolScreen(
    vm: ToolsViewModel,
    /** Every PDF in the library, newest first; null while loading. */
    pdfs: List<LibraryItem>?,
    initialUri: () -> Uri?,
    onBack: () -> Unit,
    onOpenPdf: (LibraryItem) -> Unit,
    onShowInShelf: (LibraryItem) -> Unit,
    onEditPdf: (Uri, LibraryItem?) -> Unit,
    onLibraryChanged: () -> Unit,
) {
    val context = LocalContext.current
    val tool by vm.tool.collectAsStateWithLifecycle()
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val loading by vm.loadingInputs.collectAsStateWithLifecycle()
    val choosing by vm.choosing.collectAsStateWithLifecycle()
    val backToOptions by vm.backToOptions.collectAsStateWithLifecycle()
    val info = tool.info()
    val accent = toolAccent(info.accent)
    val working = state is ToolState.Working

    // "Add files": PDFs from anywhere else on the phone, through the system picker.
    val pick = rememberLauncherForActivityResult(PickPdfs(initialUri, tool.multiple)) { uris ->
        if (tool == PdfTool.Edit) uris.firstOrNull()?.let { onEditPdf(it, null) } else vm.addPicked(uris)
    }
    // A finished tool saved new files: the list should show them.
    LaunchedEffect(state) { if (state is ToolState.Done) onLibraryChanged() }

    // Back moves between the two steps before it leaves the tool.
    val listBackToOptions = choosing && backToOptions && inputs.isNotEmpty()
    val optionsBackToList = !choosing && vm.startedWithList && !working
    BackHandler(enabled = listBackToOptions || optionsBackToList) {
        if (choosing) vm.showOptions() else vm.choose(returnToOptions = false)
    }
    val back: () -> Unit = {
        when {
            listBackToOptions -> vm.showOptions()
            optionsBackToList -> vm.choose(returnToOptions = false)
            else -> onBack()
        }
    }

    // Per-tool options; kept here so they survive switching between the steps.
    var compressMode by rememberSaveable { mutableStateOf(CompressMode.Preset) }
    var preset by rememberSaveable { mutableStateOf(CompressPreset.Balanced) }
    var dpi by rememberSaveable { mutableFloatStateOf(120f) }
    var quality by rememberSaveable { mutableFloatStateOf(0.65f) }
    var targetText by rememberSaveable { mutableStateOf("") }
    var targetUnit by rememberSaveable { mutableStateOf(SizeUnit.MB) }
    var format by rememberSaveable { mutableStateOf(ImageFormat.JPG) }
    var imageDpi by rememberSaveable { mutableStateOf(150) }
    var mergeName by rememberSaveable { mutableStateOf("") }
    var everyPage by rememberSaveable { mutableStateOf(true) }
    var ranges by rememberSaveable { mutableStateOf("") }

    val parsedRanges = inputs.firstOrNull()?.let { PdfTools.parseRanges(ranges, it.pages) }
    val inputBytes = inputs.firstOrNull()?.size?.takeIf { it > 0 }
    val targetBytes = targetText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * targetUnit.bytes).toLong() }
    val targetOk = targetBytes != null && (inputBytes == null || targetBytes < inputBytes)
    val ready = !working && !loading && when (tool) {
        PdfTool.Edit -> false
        PdfTool.Compress -> inputs.size == 1 && (compressMode != CompressMode.TargetSize || targetOk)
        PdfTool.Merge -> inputs.size >= 2 && mergeName.isNotBlank() && mergeName.none { it in "/\\:*?\"<>|" }
        PdfTool.Split -> inputs.size == 1 && (everyPage || parsedRanges != null)
        PdfTool.ToImages -> inputs.isNotEmpty()
    }
    LaunchedEffect(inputs.firstOrNull()?.uri) {
        if (mergeName.isBlank()) inputs.firstOrNull()?.let { mergeName = "${it.name} merged" }
    }

    AnimatedContent(
        targetState = choosing,
        transitionSpec = {
            // The list comes first: on to the options slides left, back to the list slides right.
            val forward = !targetState
            (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
        },
        label = "toolStep",
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
    ) { list ->
        if (list) {
            ChooseStep(
                tool = tool,
                info = info,
                accent = accent,
                pdfs = pdfs,
                inputs = inputs,
                loading = loading,
                onBack = back,
                onAddFiles = { pick.launch(Unit) },
                onPick = { item -> if (tool == PdfTool.Edit) onEditPdf(item.uri, item) else vm.toggle(item) },
                onRemove = vm::remove,
                onContinue = vm::showOptions,
            )
            return@AnimatedContent
        }

        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        // Bring the result into view; it appears at the bottom, under the action bar otherwise.
        LaunchedEffect(state) {
            if (state is ToolState.Done || state is ToolState.Failed) {
                listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
            }
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, contentPadding = PaddingValues(top = top, bottom = 140.dp), modifier = Modifier.fillMaxSize()) {
                item(key = "header") { Header(info.title, info.description, back) }
                item(key = "inputs-title") { SectionHeader(if (tool.multiple) "PDFs" else "PDF") }
                itemsIndexed(inputs, key = { i, it -> "${it.uri}#$i" }) { i, input ->
                    InputRow(
                        input = input,
                        index = i,
                        count = inputs.size,
                        reorderable = tool == PdfTool.Merge,
                        enabled = !working,
                        onRemove = { vm.remove(i) },
                        onMove = { to -> vm.move(i, to) },
                    )
                }
                item(key = "add") {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Back to the list, where "Add files" reaches the rest of the phone.
                        OutlinedButton(onClick = { vm.choose(returnToOptions = true) }, enabled = !working) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when {
                                    tool.multiple -> "Add PDFs"
                                    inputs.isEmpty() -> "Choose a PDF"
                                    else -> "Choose another PDF"
                                },
                            )
                        }
                        if (loading) {
                            Spacer(Modifier.width(12.dp))
                            Text("Reading…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item(key = "options") {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        when (tool) {
                            PdfTool.Edit -> Unit
                            PdfTool.Compress -> CompressOptions(
                                mode = compressMode, preset = preset, dpi = dpi, quality = quality,
                                targetText = targetText, targetUnit = targetUnit, targetBytes = targetBytes, inputBytes = inputBytes,
                                onMode = { mode ->
                                    compressMode = mode
                                    // Their settings open below the last choice, behind the action bar: bring them up.
                                    scope.launch { listState.animateScrollToItem(OPTIONS_INDEX + inputs.size) }
                                },
                                onPreset = { preset = it; compressMode = CompressMode.Preset },
                                onDpi = { dpi = it }, onQuality = { quality = it },
                                onTargetText = { targetText = it }, onTargetUnit = { targetUnit = it },
                            )
                            PdfTool.ToImages -> ImageOptions(format, imageDpi, onFormat = { format = it }, onDpi = { imageDpi = it })
                            PdfTool.Merge -> {
                                SectionHeader("Name", start = 4.dp)
                                OutlinedTextField(
                                    value = mergeName,
                                    onValueChange = { mergeName = it },
                                    singleLine = true,
                                    suffix = { Text(".pdf") },
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            PdfTool.Split -> SplitOptions(
                                everyPage = everyPage,
                                ranges = ranges,
                                pageCount = inputs.firstOrNull()?.pages,
                                valid = parsedRanges != null,
                                onEveryPage = { everyPage = it },
                                onRanges = { ranges = it },
                            )
                        }
                    }
                }
                item(key = "result") {
                    AnimatedVisibility(
                        visible = state is ToolState.Done || state is ToolState.Failed,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        when (val s = state) {
                            is ToolState.Done -> ResultCard(
                                output = s.output,
                                onOpen = { s.output.pdf?.let(onOpenPdf) },
                                onShare = { s.output.pdf?.let { shareDocument(context, it.uri, it.name) } },
                                onShowFolder = { s.output.folder?.let(onShowInShelf) },
                            )
                            is ToolState.Failed -> ErrorCard(s.message)
                            else -> Spacer(Modifier.height(0.dp))
                        }
                    }
                }
            }

            // The primary button, or progress while a tool runs.
            ActionBar(Modifier.align(Alignment.BottomCenter)) {
                val s = state
                if (s is ToolState.Working) {
                    Text("${s.label}…", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))
                    val p = s.progress
                    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                } else {
                    AccentButton(
                        text = info.action,
                        accent = accent,
                        enabled = ready,
                        onClick = {
                            when (tool) {
                                PdfTool.Edit -> Unit
                                PdfTool.Compress -> when (compressMode) {
                                    CompressMode.Preset -> vm.compress(preset.settings)
                                    CompressMode.Custom -> vm.compress(PdfTools.CompressSettings(dpi.roundToInt(), quality))
                                    CompressMode.TargetSize -> targetBytes?.let(vm::compressToSize)
                                }
                                PdfTool.ToImages -> vm.toImages(imageDpi, format)
                                PdfTool.Merge -> vm.merge(mergeName.trim())
                                PdfTool.Split -> vm.split(if (everyPage) null else parsedRanges)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String, onBack: () -> Unit) {
    Column {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Back", modifier = Modifier.size(30.dp))
        }
        LargeTitle(title, subtitle = subtitle)
    }
}

/** The bar pinned to the bottom of both steps. */
@Composable
private fun ActionBar(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp), content = content)
    }
}

@Composable
private fun AccentButton(text: String, accent: Accent, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = accent.onContainer, contentColor = accent.container),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/**
 * The first step: the library's PDFs, newest first, ready to tap. Single-file tools move on as
 * soon as one is tapped; multi-file tools number the PDFs in the order they're picked.
 */
@Composable
private fun ChooseStep(
    tool: PdfTool,
    info: ToolInfo,
    accent: Accent,
    pdfs: List<LibraryItem>?,
    inputs: List<SourcePdf>,
    loading: Boolean,
    onBack: () -> Unit,
    onAddFiles: () -> Unit,
    onPick: (LibraryItem) -> Unit,
    onRemove: (Int) -> Unit,
    onContinue: () -> Unit,
) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Chosen through "Add files", so they aren't in the library list.
    val added = inputs.withIndex().filter { (_, input) -> pdfs.orEmpty().none { it.uri == input.uri } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(top = top, bottom = if (tool.multiple) 140.dp else bottom + 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") { Header(info.title, info.prompt, onBack) }
            item(key = "add") {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = onAddFiles) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add files")
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (loading) "Reading…" else "From anywhere on your phone",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (added.isNotEmpty()) {
                item(key = "added-title") { SectionHeader("Added files") }
                items(added, key = { (_, input) -> "added-${input.uri}" }) { (i, input) ->
                    InputRow(input, index = i, count = inputs.size, reorderable = false, enabled = true, onRemove = { onRemove(i) }, onMove = {})
                }
            }
            item(key = "library-title") { SectionHeader("In Dock") }
            when {
                pdfs == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                pdfs.isEmpty() -> item(key = "empty") {
                    EmptyMessage("No PDFs yet", "Scan a document first, or use Add files to choose a PDF from elsewhere on your phone.")
                }
                else -> items(pdfs, key = { it.documentId }) { item ->
                    val position = inputs.indexOfFirst { it.uri == item.uri }
                    ChoiceRow(item, order = if (position >= 0) position + 1 else null, multiple = tool.multiple, onClick = { onPick(item) })
                }
            }
        }

        if (tool.multiple) {
            ActionBar(Modifier.align(Alignment.BottomCenter)) {
                val count = inputs.size
                AccentButton(
                    text = when {
                        count == 0 && tool.minInputs > 1 -> "Choose at least ${tool.minInputs} PDFs"
                        count == 0 -> "Choose PDFs"
                        count < tool.minInputs -> "Choose ${tool.minInputs - count} more"
                        else -> "Continue with $count PDF${if (count == 1) "" else "s"}"
                    },
                    accent = accent,
                    enabled = count >= tool.minInputs && !loading,
                    onClick = onContinue,
                )
            }
        }
    }
}

@Composable
private fun ChoiceRow(item: LibraryItem, order: Int?, multiple: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val preview by rememberPdfPreview(item, 40.dp)
    val selected = order != null
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else Color.Transparent)
            .then(
                if (multiple) Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
                else Modifier.clickable(onClickLabel = "Choose", onClick = onClick),
            )
            .padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PdfThumbnail(preview?.thumbnail, Modifier.size(width = 40.dp, height = 54.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 12.dp)) {
            Text(item.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = buildList {
                add(friendlyDate(context, item.lastModified))
                preview?.pageCount?.let { add(if (it == 1) "1 page" else "$it pages") }
                add(Formatter.formatShortFileSize(context, item.size))
            }.joinToString(" · ")
            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            item.folderName?.let { folder ->
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_folder), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
                    Text(folder, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        when {
            multiple -> OrderMark(order)
            selected -> Icon(Icons.Filled.CheckCircle, contentDescription = "Chosen", tint = MaterialTheme.colorScheme.primary)
            else -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** An empty ring, or the PDF's place in the order once it's picked. */
@Composable
private fun OrderMark(order: Int?) {
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .then(
                if (order != null) Modifier.background(MaterialTheme.colorScheme.primary)
                else Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (order != null) Text("$order", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun InputRow(
    input: SourcePdf,
    index: Int,
    count: Int,
    reorderable: Boolean,
    enabled: Boolean,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val context = LocalContext.current
    val widthPx = with(LocalDensity.current) { 80.dp.roundToPx() }
    val preview by produceState(PdfPreviews.cached(input.uri, input.size), input.uri) {
        if (value == null) value = PdfPreviews.load(context, input.uri, input.size, widthPx)
    }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (reorderable) {
            Text("${index + 1}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(22.dp))
        }
        PdfThumbnail(preview?.thumbnail, Modifier.size(width = 40.dp, height = 54.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(input.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${if (input.pages == 1) "1 page" else "${input.pages} pages"} · ${Formatter.formatShortFileSize(context, input.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (reorderable) {
            IconButton(onClick = { onMove(index - 1) }, enabled = enabled && index > 0) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
            }
            IconButton(onClick = { onMove(index + 1) }, enabled = enabled && index < count - 1) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
            }
        }
        IconButton(onClick = onRemove, enabled = enabled) { Icon(Icons.Filled.Close, contentDescription = "Remove ${input.name}") }
    }
}

@Composable
private fun SelectableRow(selected: Boolean, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(MaterialTheme.shapes.large)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CompressOptions(
    mode: CompressMode,
    preset: CompressPreset,
    dpi: Float,
    quality: Float,
    targetText: String,
    targetUnit: SizeUnit,
    targetBytes: Long?,
    inputBytes: Long?,
    onMode: (CompressMode) -> Unit,
    onPreset: (CompressPreset) -> Unit,
    onDpi: (Float) -> Unit,
    onQuality: (Float) -> Unit,
    onTargetText: (String) -> Unit,
    onTargetUnit: (SizeUnit) -> Unit,
) {
    val context = LocalContext.current
    SectionHeader("Compression", start = 4.dp)
    CompressPreset.entries.forEach { p ->
        SelectableRow(selected = mode == CompressMode.Preset && p == preset, title = p.label, detail = p.detail, onClick = { onPreset(p) })
    }
    SelectableRow(
        selected = mode == CompressMode.Custom,
        title = "Custom",
        detail = "Choose resolution and quality yourself",
        onClick = { onMode(CompressMode.Custom) },
    )
    AnimatedVisibility(visible = mode == CompressMode.Custom) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            OptionSlider("Resolution", "${dpi.roundToInt()} dpi", dpi, 50f..300f, onDpi)
            OptionSlider("Quality", "${(quality * 100).roundToInt()}%", quality, 0.2f..0.95f, onQuality)
        }
    }
    SelectableRow(
        selected = mode == CompressMode.TargetSize,
        title = "Target size",
        detail = "Get the file under a size you choose",
        onClick = { onMode(CompressMode.TargetSize) },
    )
    AnimatedVisibility(visible = mode == CompressMode.TargetSize) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val tooBig = targetBytes != null && inputBytes != null && targetBytes >= inputBytes
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { text -> if (text.length <= 7 && text.all { it.isDigit() || it == '.' || it == ',' }) onTargetText(text) },
                    singleLine = true,
                    label = { Text("Under") },
                    placeholder = { Text(if (targetUnit == SizeUnit.MB) "1.5" else "500") },
                    isError = tooBig,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SizeUnit.entries.forEach { unit ->
                        FilterChip(selected = unit == targetUnit, onClick = { onTargetUnit(unit) }, label = { Text(unit.name) })
                    }
                }
            }
            Text(
                when {
                    inputBytes == null -> "Choose a PDF first."
                    targetBytes != null && targetBytes >= inputBytes ->
                        "This PDF is already ${Formatter.formatShortFileSize(context, inputBytes)}. Choose a smaller size."
                    else -> "Now ${Formatter.formatShortFileSize(context, inputBytes)}. Dock uses the sharpest settings that fit."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }
    }
}

@Composable
private fun OptionSlider(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(84.dp))
        Slider(value = current, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp).width(56.dp))
    }
}

@Composable
private fun ImageOptions(format: ImageFormat, dpi: Int, onFormat: (ImageFormat) -> Unit, onDpi: (Int) -> Unit) {
    SectionHeader("Format", start = 4.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ImageFormat.entries.forEach { f -> FilterChip(selected = f == format, onClick = { onFormat(f) }, label = { Text(f.label) }) }
    }
    SectionHeader("Resolution", start = 4.dp)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(72 to "Small · 72 dpi", 150 to "Standard · 150 dpi", 300 to "Print · 300 dpi").forEach { (value, label) ->
            FilterChip(selected = value == dpi, onClick = { onDpi(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun SplitOptions(
    everyPage: Boolean,
    ranges: String,
    pageCount: Int?,
    valid: Boolean,
    onEveryPage: (Boolean) -> Unit,
    onRanges: (String) -> Unit,
) {
    SectionHeader("Split into", start = 4.dp)
    SelectableRow(everyPage, "Every page", "One PDF per page", onClick = { onEveryPage(true) })
    SelectableRow(!everyPage, "Page ranges", "One PDF per range you type", onClick = { onEveryPage(false) })
    AnimatedVisibility(visible = !everyPage) {
        OutlinedTextField(
            value = ranges,
            onValueChange = onRanges,
            singleLine = true,
            label = { Text("Pages") },
            placeholder = { Text("e.g. 1-3, 5, 8-") },
            isError = ranges.isNotBlank() && !valid,
            supportingText = {
                Text(
                    when {
                        pageCount == null -> "Choose a PDF first"
                        ranges.isNotBlank() && !valid -> "Use page numbers from 1 to $pageCount, like 1-3, 5"
                        pageCount < 2 -> "This PDF has only 1 page."
                        else -> "This PDF has $pageCount pages. “2-” means page 2 to the end."
                    },
                )
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
private fun ResultCard(output: ToolOutput, onOpen: () -> Unit, onShare: () -> Unit, onShowFolder: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text(output.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            Text(output.detail, style = MaterialTheme.typography.bodyMedium)
            if (output.pdf != null || output.folder != null) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (output.pdf != null) {
                        Button(onClick = onOpen) { Text("Open") }
                        TextButton(onClick = onShare) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share")
                        }
                    }
                    if (output.folder != null) {
                        Button(onClick = onShowFolder) {
                            Icon(painterResource(R.drawable.ic_folder), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Show in Shelf")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
