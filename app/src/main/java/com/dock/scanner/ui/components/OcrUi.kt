package com.dock.scanner.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dock.scanner.DockApp
import com.dock.scanner.R
import com.dock.scanner.data.ocr.DocumentOcr
import com.dock.scanner.data.storage.LibraryItem
import com.dock.scanner.pdf.PdfTextLayer
import kotlin.math.roundToInt

@Composable
private fun documentOcr(): DocumentOcr = (LocalContext.current.applicationContext as DockApp).ocr

/** How far the text of [item] has been read (0..1), or null when nothing is running for it. */
@Composable
fun rememberOcrProgress(item: LibraryItem): Float? {
    val progress by documentOcr().progress.collectAsStateWithLifecycle()
    return progress[item.documentId]
}

private sealed interface OcrState {
    data object Loading : OcrState
    data object Unavailable : OcrState
    class Known(val info: PdfTextLayer.Info) : OcrState
}

/**
 * A PDF's searchable-text entry: a switch for Dock's scans, "Run OCR" for PDFs from elsewhere
 * (a switch too once it has run), and the progress while the text is being read (tap to stop).
 */
@Composable
fun OcrMenuItem(item: LibraryItem, onDismiss: () -> Unit) {
    val ocr = documentOcr()
    val progress = rememberOcrProgress(item)
    val state by produceState<OcrState>(OcrState.Loading, item.uri, item.lastModified) {
        value = ocr.info(item)?.let(OcrState::Known) ?: OcrState.Unavailable
    }
    val icon = @Composable { Icon(painterResource(R.drawable.ic_ocr), null) }
    if (progress != null) {
        DropdownMenuItem(
            text = { Labelled("Reading text… ${(progress * 100).roundToInt()}%", "Tap to stop") },
            leadingIcon = { CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp) },
            onClick = { ocr.stop(item.documentId); onDismiss() },
        )
        return
    }
    when (val s = state) {
        OcrState.Loading -> DropdownMenuItem(
            text = { Text("Searchable text") },
            leadingIcon = icon,
            trailingIcon = { Switch(checked = false, onCheckedChange = null, enabled = false) },
            enabled = false,
            onClick = {},
        )
        OcrState.Unavailable -> Unit
        is OcrState.Known -> when {
            s.info.hasLayer -> DropdownMenuItem(
                text = { Labelled("Searchable text", "OCR") },
                leadingIcon = icon,
                trailingIcon = { Switch(checked = true, onCheckedChange = null) },
                onClick = { ocr.turnOff(item); onDismiss() },
            )
            s.info.madeByDock || s.info.choice != null -> DropdownMenuItem(
                text = { Labelled("Searchable text", "OCR") },
                leadingIcon = icon,
                trailingIcon = { Switch(checked = false, onCheckedChange = null) },
                onClick = { ocr.turnOn(item); onDismiss() },
            )
            else -> DropdownMenuItem(
                text = { Labelled("Run OCR", "Make the text searchable") },
                leadingIcon = icon,
                onClick = { ocr.turnOn(item); onDismiss() },
            )
        }
    }
}

@Composable
private fun Labelled(title: String, detail: String) {
    Column {
        Text(title)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
