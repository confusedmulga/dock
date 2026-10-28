package com.dock.scanner.ui.images

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.dock.scanner.R
import com.dock.scanner.data.images.ImageResizer
import com.dock.scanner.data.images.OutputFormat
import com.dock.scanner.data.images.ResizeMode
import com.dock.scanner.data.images.ResizeOptions
import com.dock.scanner.data.images.SizeUnit
import com.dock.scanner.data.storage.LibraryItem
import com.dock.scanner.ui.components.LargeTitle
import com.dock.scanner.ui.components.SectionHeader
import com.dock.scanner.ui.components.shareDocument
import com.dock.scanner.ui.components.viewFile
import com.dock.scanner.ui.theme.ToolAccent
import com.dock.scanner.ui.theme.toolAccent

/**
 * Resize, convert and compress pictures, with the same controls as reduceimages.com: new
 * size in percent, pixels, centimeters or inches with a lock for proportions, a resolution,
 * stretch/crop/fit when the shape changes, JPG/PNG/GIF, quality and background.
 */
@Composable
fun ImagesScreen(vm: ImagesViewModel, bottomInset: Dp, onShowFolder: (LibraryItem) -> Unit) {
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val listState = rememberLazyListState()

    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { vm.add(it) }
    val browse = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.add(it) }

    // Bring the result into view when resizing finishes.
    LaunchedEffect(state) {
        if (state is ResizeState.Done || state is ResizeState.Failed) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
    }

    LazyColumn(state = listState, contentPadding = PaddingValues(top = top + 8.dp, bottom = bottomInset), modifier = Modifier.fillMaxSize()) {
        item(key = "title") {
            LargeTitle("Images", subtitle = "Resize, convert and compress pictures. All on your device.")
        }
        item(key = "drop") {
            DropZone(
                onSelect = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onBrowse = { browse.launch(arrayOf("image/*")) },
                loading = loading,
            )
        }
        if (inputs.isNotEmpty()) {
            item(key = "inputs-title") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader(if (inputs.size == 1) "1 image" else "${inputs.size} images", Modifier.weight(1f))
                    TextButton(onClick = vm::clear, modifier = Modifier.padding(end = 8.dp, top = 10.dp)) { Text("Clear") }
                }
            }
            items(inputs, key = { it.uri.toString() }) { input -> InputRow(input, onRemove = { vm.remove(input) }) }
        }
        item(key = "options") {
            Options(form, inputs.firstOrNull(), vm)
        }
        item(key = "action") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                val s = state
                if (s is ResizeState.Working) {
                    Text("Now resizing…", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    val accent = toolAccent(ToolAccent.Resize)
                    Button(
                        onClick = vm::resize,
                        enabled = inputs.isNotEmpty() && form.options() != null && !loading,
                        colors = ButtonDefaults.buttonColors(containerColor = accent.onContainer, contentColor = accent.container),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text(if (inputs.size > 1) "Resize images" else "Resize image", style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
        item(key = "result") {
            AnimatedVisibility(
                visible = state is ResizeState.Done || state is ResizeState.Failed,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                when (val s = state) {
                    is ResizeState.Done -> Results(s, onShowFolder)
                    is ResizeState.Failed -> ErrorCard(s.message)
                    else -> Spacer(Modifier.height(0.dp))
                }
            }
        }
    }
}

@Composable
private fun DropZone(onSelect: () -> Unit, onBrowse: () -> Unit, loading: Boolean) {
    val accent = toolAccent(ToolAccent.Resize)
    val outline = MaterialTheme.colorScheme.outline
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .dashedBorder(outline)
            .clickable(onClickLabel = "Select images", onClick = onSelect)
            .padding(vertical = 28.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(accent.container), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_upload), contentDescription = null, tint = accent.onContainer, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(if (loading) "Reading images…" else "Choose the images to resize", style = MaterialTheme.typography.titleMedium)
        Text(
            "JPG, PNG, BMP, GIF and more",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onSelect,
                colors = ButtonDefaults.buttonColors(containerColor = accent.onContainer, contentColor = accent.container),
            ) { Text("Select images") }
            TextButton(onClick = onBrowse) { Text("Browse files") }
        }
    }
}

/** The dashed outline of an upload area. */
private fun Modifier.dashedBorder(color: Color, radius: Dp = 28.dp) = drawWithContent {
    drawContent()
    val stroke = 1.5.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()))),
    )
}

@Composable
private fun InputRow(input: ImageInput, onRemove: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = input.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(input.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${input.width} × ${input.height} px · ${Formatter.formatShortFileSize(context, input.bytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove ${input.name}") }
    }
}

@Composable
private fun Options(form: ResizeForm, first: ImageInput?, vm: ImagesViewModel) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        SectionHeader("Choose new size and format", start = 4.dp)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                // Width and height, joined by the lock, with the units beside them.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("Width", form.width, vm::setWidth)
                        NumberField("Height", form.height, vm::setHeight)
                    }
                    LockButton(form.locked, vm::toggleLock)
                    UnitPicker(form.unit, vm::setUnit, Modifier.weight(0.9f))
                }
                Spacer(Modifier.height(12.dp))
                NumberField("Resolution", form.dpi, { text -> vm.edit { it.copy(dpi = text.filter(Char::isDigit).take(4)) } }, suffix = "DPI", decimal = false)
                // The new size of the first picture, so the numbers mean something.
                val options = form.options()
                if (first != null && options != null) {
                    val (w, h) = ImageResizer.targetSize(first.width, first.height, options)
                    Text(
                        "New size: $w × $h px",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
            }
        }

        // Only matters once the proportions can change.
        AnimatedVisibility(visible = !form.locked) {
            Column {
                SectionHeader("When the shape changes", start = 4.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ResizeMode.entries.forEach { mode ->
                        ModeTile(mode, selected = mode == form.mode, onClick = { vm.edit { it.copy(mode = mode) } }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        SectionHeader("Format", start = 4.dp)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            OutputFormat.entries.forEachIndexed { i, format ->
                SegmentedButton(
                    selected = format == form.format,
                    onClick = { vm.edit { it.copy(format = format) } },
                    shape = SegmentedButtonDefaults.itemShape(i, OutputFormat.entries.size),
                ) { Text(format.label) }
            }
        }

        SectionHeader("Quality", start = 4.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = form.quality.toFloat(),
                onValueChange = { v -> vm.edit { it.copy(quality = v.toInt()) } },
                valueRange = 1f..100f,
                enabled = form.format == OutputFormat.Jpg,
                modifier = Modifier.weight(1f),
            )
            Text("${form.quality}%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(52.dp).padding(start = 12.dp))
        }
        if (form.format != OutputFormat.Jpg) {
            Text(
                if (form.format == OutputFormat.Png) "PNG is lossless, so quality only applies to JPG." else "GIF uses 256 colors; quality only applies to JPG.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        SectionHeader("Background", start = 4.dp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf(ResizeOptions.WHITE to "White", ResizeOptions.BLACK to "Black").forEach { (color, label) ->
                Swatch(Color(color), label, selected = form.background == color, onClick = { vm.edit { it.copy(background = color) } })
            }
            Text(
                "Fills transparent areas in JPG, and the margins in Fit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, suffix: String? = null, decimal: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() || (decimal && (it == '.' || it == ',')) }.take(8)) },
        label = { Text(label) },
        singleLine = true,
        suffix = suffix?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The chain between width and height: closed keeps the picture's proportions. */
@Composable
private fun LockButton(locked: Boolean, onToggle: () -> Unit) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(2.dp).height(22.dp).background(line))
        IconButton(onClick = onToggle) {
            Icon(
                painterResource(if (locked) R.drawable.ic_lock else R.drawable.ic_lock_open),
                contentDescription = if (locked) "Proportions locked" else "Proportions unlocked",
                tint = if (locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.width(2.dp).height(22.dp).background(line))
    }
}

@Composable
private fun UnitPicker(unit: SizeUnit, onPick: (SizeUnit) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 14.dp, end = 6.dp)) {
            Text(unit.label, modifier = Modifier.weight(1f), maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SizeUnit.entries.forEach { u ->
                DropdownMenuItem(text = { Text(u.label) }, onClick = { open = false; onPick(u) })
            }
        }
    }
}

/** A small picture of what each mode does to a wide photo in a square frame. */
@Composable
private fun ModeTile(mode: ResizeMode, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val frame = MaterialTheme.colorScheme.onSurface
    val photo = MaterialTheme.colorScheme.primary
    val outside = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    Column(
        modifier
            .clip(MaterialTheme.shapes.large)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(64.dp, 48.dp)) { drawMode(mode, frame, photo, outside) }
        Spacer(Modifier.height(8.dp))
        Text(mode.label, style = MaterialTheme.typography.labelLarge)
    }
}

private fun DrawScope.drawMode(mode: ResizeMode, frame: Color, photo: Color, outside: Color) {
    val f = size.height * 0.8f
    val left = (size.width - f) / 2
    val top = (size.height - f) / 2
    val stroke = 2.dp.toPx()
    when (mode) {
        ResizeMode.Stretch -> drawRect(photo, Offset(left, top), Size(f, f))
        ResizeMode.Crop -> {
            // The wide photo overflows the square; the part outside is trimmed.
            drawRect(outside, Offset(left - f * 0.25f, top), Size(f * 1.5f, f))
            clipRect(left, top, left + f, top + f) { drawRect(photo, Offset(left - f * 0.25f, top), Size(f * 1.5f, f)) }
        }
        ResizeMode.Fit -> {
            drawRect(frame.copy(alpha = 0.12f), Offset(left, top), Size(f, f))
            drawRect(photo, Offset(left, top + f / 6), Size(f, f * 2 / 3))
        }
    }
    drawRect(frame, Offset(left, top), Size(f, f), style = Stroke(stroke))
}

@Composable
private fun Swatch(color: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
            .padding(if (selected) 5.dp else 1.dp)
            .clip(CircleShape)
            .background(color)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
    )
}

@Composable
private fun Results(done: ResizeState.Done, onShowFolder: (LibraryItem) -> Unit) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (done.files.size == 1) "Your image has been resized!" else "Your images have been resized!",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                if (done.folder != null) "Saved in the folder “${done.folder.name}”" else "Saved in your Dock folder",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            done.files.forEach { file ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = file.item.uri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(MaterialTheme.shapes.small).clickable { viewFile(context, file.item) },
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(file.item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${file.width} × ${file.height} px · ${Formatter.formatShortFileSize(context, file.bytes)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    IconButton(onClick = { shareDocument(context, file.item.uri, file.item.name, file.item.mimeType) }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share ${file.item.name}")
                    }
                }
            }
            done.folder?.let { folder ->
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onShowFolder(folder) }) {
                    Icon(painterResource(R.drawable.ic_folder), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Show in Shelf")
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
