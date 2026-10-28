package com.dock.scanner.ui.components

import com.dock.scanner.ui.theme.DockColors
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dock.scanner.R
import com.dock.scanner.data.storage.DockLibrary
import com.dock.scanner.data.storage.LibraryItem
import com.dock.scanner.data.storage.PdfPreviews
import com.dock.scanner.office.OfficeFormat
import com.dock.scanner.ui.tools.PdfTool
import coil3.compose.AsyncImage
import androidx.compose.material3.HorizontalDivider
import java.util.Calendar

/** Large, tightly tracked screen title with optional subtitle and trailing actions. */
@Composable
fun LargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /** Distance from the container's edge; screens with their own inset pass less so titles line up at 20 dp. */
    start: Dp = 20.dp,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth().padding(start = start, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.headlineLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, start: Dp = 20.dp) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = start, end = 20.dp, top = 18.dp, bottom = 4.dp),
    )
}

/** What a file row can do. */
class PdfActions(
    val onOpen: (LibraryItem) -> Unit,
    val onShare: (LibraryItem) -> Unit,
    val onRename: (LibraryItem) -> Unit,
    val onMove: (LibraryItem) -> Unit,
    val onDelete: (LibraryItem) -> Unit,
    val onEdit: (LibraryItem) -> Unit = {},
    val onTool: (PdfTool, LibraryItem) -> Unit = { _, _ -> },
)

@Composable
fun PdfRow(item: LibraryItem, actions: PdfActions, showFolder: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preview by rememberPdfPreview(item, 48.dp)
    var menu by remember { mutableStateOf(false) }
    val thumbSize = Modifier.size(width = 48.dp, height = 64.dp)

    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open") { actions.onOpen(item) }
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val format = item.documentFormat
        when {
            item.isImage -> AsyncImage(
                model = item.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = thumbSize.clip(MaterialTheme.shapes.extraSmall).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            format != null -> DocumentThumbnail(format, item.extension, thumbSize)
            else -> PdfThumbnail(preview?.thumbnail, Modifier.sharedPage(SharedKeys.pdf(item.documentId, LocalSharedOrigin.current)).then(thumbSize))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(item.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            val details = buildList {
                add(friendlyDate(context, item.lastModified))
                when {
                    item.isImage -> add(item.extension.uppercase())
                    format != null -> add(item.extension.uppercase())
                    else -> preview?.pageCount?.let { add(if (it == 1) "1 page" else "$it pages") }
                }
                add(Formatter.formatShortFileSize(context, item.size))
            }.joinToString(" · ")
            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (showFolder && item.folderName != null) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_folder),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        item.folderName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More actions for ${item.displayName}")
            }
            // Anchored to the button that opened it.
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (item.isPdf) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_edit_doc), null) },
                        onClick = { menu = false; actions.onEdit(item) },
                    )
                    DropdownMenuItem(
                        text = { Text("Compress") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_compress), null) },
                        onClick = { menu = false; actions.onTool(PdfTool.Compress, item) },
                    )
                    DropdownMenuItem(
                        text = { Text("Convert to images") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_image), null) },
                        onClick = { menu = false; actions.onTool(PdfTool.ToImages, item) },
                    )
                    DropdownMenuItem(
                        text = { Text("Split") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_split), null) },
                        onClick = { menu = false; actions.onTool(PdfTool.Split, item) },
                    )
                    HorizontalDivider()
                }
                DropdownMenuItem(
                    text = { Text("Share") },
                    leadingIcon = { Icon(Icons.Filled.Share, null) },
                    onClick = { menu = false; actions.onShare(item) },
                )
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                    onClick = { menu = false; actions.onRename(item) },
                )
                DropdownMenuItem(
                    text = { Text("Move to…") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_folder_outline), null) },
                    onClick = { menu = false; actions.onMove(item) },
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; actions.onDelete(item) },
                )
            }
        }
    }
}

@Composable
fun rememberPdfPreview(item: LibraryItem, width: Dp) = run {
    val context = LocalContext.current
    // 2x the display size so thumbnails stay crisp on high-density screens.
    val widthPx = with(LocalDensity.current) { (width * 2).roundToPx() }
    produceState(PdfPreviews.cached(item.uri, item.lastModified), item.uri, item.lastModified) {
        if (value == null && item.isPdf) value = PdfPreviews.load(context, item.uri, item.lastModified, widthPx)
    }
}

@Composable
fun PdfThumbnail(bitmap: android.graphics.Bitmap?, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.extraSmall
    Box(
        modifier
            .clip(shape)
            .background(if (bitmap != null) DockColors.Paper else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                painterResource(R.drawable.ic_doc),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** A tinted tile with the kind of document and its extension, standing in for a page preview. */
@Composable
fun DocumentThumbnail(format: OfficeFormat, extension: String, modifier: Modifier = Modifier) {
    val (color, icon) = documentLook(format)
    val shape = MaterialTheme.shapes.extraSmall
    Column(
        modifier
            .clip(shape)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.35f), shape),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Text(
            extension.uppercase().take(5),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Blue for text, green for sheets, orange for slides: the colors people associate with each. */
@Composable
fun documentLook(format: OfficeFormat): Pair<Color, Int> {
    val dark = GlassColors.isDark
    return when (format.kind) {
        OfficeFormat.Kind.Text -> (if (dark) Color(0xFF8AB4F8) else Color(0xFF2B5CB8)) to R.drawable.ic_article
        OfficeFormat.Kind.Sheet -> (if (dark) Color(0xFF81C995) else Color(0xFF1E7B45)) to R.drawable.ic_table
        OfficeFormat.Kind.Slides -> (if (dark) Color(0xFFF6AE80) else Color(0xFFC4501E)) to R.drawable.ic_slides
    }
}

@Composable
fun EmptyMessage(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** "Today, 4:30 PM", "Yesterday, 9:12 AM" or "12 Oct 2026". */
fun friendlyDate(context: Context, millis: Long): String {
    val time = DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)
    return when {
        DateUtils.isToday(millis) -> "Today, $time"
        DateUtils.isToday(millis + DateUtils.DAY_IN_MILLIS) -> "Yesterday, $time"
        else -> DateUtils.formatDateTime(
            context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
        )
    }
}

/** Groups for the Home list. */
fun dateSection(millis: Long, now: Long = System.currentTimeMillis()): String {
    val startOfToday = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return when {
        millis >= startOfToday -> "Today"
        millis >= startOfToday - 6 * DateUtils.DAY_IN_MILLIS -> "Previous 7 Days"
        millis >= startOfToday - 29 * DateUtils.DAY_IN_MILLIS -> "Previous 30 Days"
        else -> "Earlier"
    }
}

/** Opens a file (e.g. an exported page image) in the user's viewer app. */
fun viewFile(context: Context, item: LibraryItem) {
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, item.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(view) }
        .onFailure { android.widget.Toast.makeText(context, "No app can open this file", android.widget.Toast.LENGTH_SHORT).show() }
}

fun shareDocument(context: Context, uri: Uri, name: String, mimeType: String = DockLibrary.MIME_PDF) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share $name"))
}
