package com.dock.scanner.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dock.scanner.R
import com.dock.scanner.ui.LibraryViewModel
import com.dock.scanner.ui.components.ChromeButton
import com.dock.scanner.ui.components.LargeTitle
import com.dock.scanner.ui.components.PdfActions
import com.dock.scanner.ui.components.PdfRow
import com.dock.scanner.ui.components.SectionHeader
import com.dock.scanner.ui.theme.DockMotion

/** Everything Dock can do, in the order it's shown. */
enum class HomeTool(val title: String, val description: String, @param:DrawableRes val icon: Int) {
    Scan("Scan document", "Turn paper into a clean PDF", R.drawable.ic_camera),
    Edit("Edit PDF", "Crop, filter, mark up or reorder pages", R.drawable.ic_edit_doc),
    Compress("Compress PDF", "Make a PDF smaller for sharing", R.drawable.ic_compress),
    ToImages("PDF to images", "Save every page as a picture", R.drawable.ic_image),
    Merge("Merge PDFs", "Combine several PDFs into one", R.drawable.ic_merge),
    Split("Split PDF", "Pull out pages into separate PDFs", R.drawable.ic_split),
    ImagesToPdf("Images to PDF", "Turn photos into a PDF", R.drawable.ic_pdf),
    ResizeImages("Resize images", "Change size, format and quality of pictures", R.drawable.ic_resize),
    ViewDocuments("View documents", "Open Word, Excel and PowerPoint files", R.drawable.ic_article),
}

@Composable
fun HomeScreen(
    vm: LibraryViewModel,
    actions: PdfActions,
    bottomInset: Dp,
    onTool: (HomeTool) -> Unit,
    onSeeAllFiles: () -> Unit,
    onAbout: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    val files by vm.allFiles.collectAsStateWithLifecycle()
    val recent = files.orEmpty().take(3)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val tools = remember { HomeTool.entries.drop(1) }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = top + 8.dp, bottom = bottomInset),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "title") {
            LargeTitle("Dock", subtitle = "Scan, edit and convert PDFs. All on your device.") {
                ChromeButton(rememberVectorPainter(Icons.Outlined.Info), contentDescription = "About Dock", onClick = onAbout)
            }
        }
        item(key = "scan") {
            ScanCard(HomeTool.Scan, onClick = { onTool(HomeTool.Scan) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        item(key = "tools-title") { SectionHeader("Tools") }
        item(key = "tools") {
            // One quiet surface holding every tool: a row each, told apart by a hairline.
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Column {
                    tools.forEachIndexed { i, tool ->
                        ToolRow(tool, onClick = { onTool(tool) })
                        if (i < tools.lastIndex) {
                            // Starts under the text, so the icons read as one column.
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 70.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item(key = "recent-title") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    SectionHeader("Recent", Modifier.weight(1f))
                    // 14 dp puts the button's label on the heading's line (the heading sits low in its own padding).
                    TextButton(onClick = onSeeAllFiles, modifier = Modifier.padding(end = 8.dp, top = 14.dp)) { Text("See all") }
                }
            }
            items(recent, key = { "recent-" + it.documentId }) { PdfRow(it, actions, showFolder = true) }
        }
    }
}

/** The one tinted surface on Home: scanning is what the app is for. */
@Composable
private fun ScanCard(tool: HomeTool, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Feedback starts on touch-down: a quick, critically damped shrink.
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, DockMotion.press(), label = "scanScale")
    val shape = MaterialTheme.shapes.extraLarge
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = shape,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tool.title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(2.dp))
                Text(tool.description, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(16.dp))
            Box(
                Modifier.size(52.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(tool.icon), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(26.dp))
            }
        }
    }
}

/** A neutral icon tile, the tool's name and what it does in a line. */
@Composable
private fun ToolRow(tool: HomeTool, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(tool.icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(tool.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                tool.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}
