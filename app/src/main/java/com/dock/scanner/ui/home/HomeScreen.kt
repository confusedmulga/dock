package com.dock.scanner.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dock.scanner.R
import com.dock.scanner.ui.LibraryViewModel
import com.dock.scanner.ui.components.LargeTitle
import com.dock.scanner.ui.components.PdfActions
import com.dock.scanner.ui.components.PdfRow
import com.dock.scanner.ui.components.SectionHeader
import com.dock.scanner.ui.theme.DockMotion
import com.dock.scanner.ui.theme.ToolAccent
import com.dock.scanner.ui.theme.toolAccent

/** Everything Dock can do, in the order it's shown. */
enum class HomeTool(val title: String, val description: String, @param:DrawableRes val icon: Int, val accent: ToolAccent) {
    Scan("Scan document", "Use the camera to turn paper into a clean PDF", R.drawable.ic_camera, ToolAccent.Scan),
    Edit("Edit PDF", "Open a PDF to crop, filter, mark up or rearrange its pages", R.drawable.ic_edit_doc, ToolAccent.Edit),
    Compress("Compress PDF", "Make a PDF smaller for sharing", R.drawable.ic_compress, ToolAccent.Compress),
    ToImages("PDF to images", "Save every page as a picture, in its own folder", R.drawable.ic_image, ToolAccent.ToImages),
    Merge("Merge PDFs", "Combine several PDFs into one", R.drawable.ic_merge, ToolAccent.Merge),
    Split("Split PDF", "Pull out pages or ranges into separate PDFs", R.drawable.ic_split, ToolAccent.Split),
    ImagesToPdf("Images to PDF", "Turn photos from your gallery into a PDF", R.drawable.ic_pdf, ToolAccent.ImagesToPdf),
    ViewDocuments("View documents", "Open Word, Excel, PowerPoint and other files", R.drawable.ic_article, ToolAccent.Documents),
}

@Composable
fun HomeScreen(
    vm: LibraryViewModel,
    actions: PdfActions,
    bottomInset: Dp,
    onTool: (HomeTool) -> Unit,
    onSeeAllFiles: () -> Unit,
) {
    val files by vm.allFiles.collectAsStateWithLifecycle()
    val recent = files.orEmpty().take(3)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val gridTools = remember { HomeTool.entries.drop(1).chunked(2) }

    LazyColumn(contentPadding = PaddingValues(top = top + 8.dp, bottom = bottomInset), modifier = Modifier.fillMaxSize()) {
        item(key = "title") {
            LargeTitle("Dock", subtitle = "Scan, edit and convert PDFs. All on your device.")
        }
        item(key = "scan") {
            HeroCard(HomeTool.Scan, onClick = { onTool(HomeTool.Scan) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        items(gridTools, key = { row -> row.first().name }) { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { tool -> ToolCard(tool, onClick = { onTool(tool) }, modifier = Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (recent.isNotEmpty()) {
            item(key = "recent-title") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                    SectionHeader("Recent", Modifier.weight(1f))
                    TextButton(onClick = onSeeAllFiles, modifier = Modifier.padding(end = 8.dp, top = 10.dp)) { Text("See all") }
                }
            }
            items(recent, key = { "recent-" + it.documentId }) { PdfRow(it, actions, showFolder = true) }
        }
    }
}

/** Press feedback shared by the cards: a quick, critically damped shrink on touch-down. */
@Composable
private fun pressScale(interaction: MutableInteractionSource): Float {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, DockMotion.press(), label = "cardScale")
    return scale
}

@Composable
private fun HeroCard(tool: HomeTool, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = toolAccent(tool.accent)
    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    Surface(
        color = accent.container,
        contentColor = accent.onContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(MaterialTheme.shapes.extraLarge)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tool.title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(tool.description, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(16.dp))
            Box(
                Modifier.size(64.dp).clip(MaterialTheme.shapes.large).background(accent.onContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(tool.icon), contentDescription = null, tint = accent.container, modifier = Modifier.size(32.dp))
            }
        }
    }
}

/** A tinted icon tile, a bold title and a one-line description. */
@Composable
private fun ToolCard(tool: HomeTool, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = toolAccent(tool.accent)
    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .heightIn(min = 148.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(MaterialTheme.shapes.large)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Box(
                Modifier.size(48.dp).clip(MaterialTheme.shapes.medium).background(accent.container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(tool.icon), contentDescription = null, tint = accent.onContainer, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(tool.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                tool.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
