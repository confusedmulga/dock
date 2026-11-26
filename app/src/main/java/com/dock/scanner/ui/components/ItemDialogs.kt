package com.dock.scanner.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dock.scanner.R
import com.dock.scanner.data.storage.LibraryItem
import com.dock.scanner.ui.Destination
import com.dock.scanner.ui.LibraryViewModel

/** Which file dialog is open, shared by Home and Shelf. */
@Stable
class ItemDialogState {
    var renaming by mutableStateOf<LibraryItem?>(null)
    var moving by mutableStateOf<LibraryItem?>(null)
    var deleting by mutableStateOf<LibraryItem?>(null)
    var creatingFolder by mutableStateOf(false)
    /** Creating a folder from the move dialog, to move this item straight into it. */
    var movingToNewFolder by mutableStateOf<LibraryItem?>(null)
}

@Composable
fun rememberItemDialogState() = remember { ItemDialogState() }

/** What to call [item] in a dialog: "folder", "PDF", "image" or "file". */
private fun kindOf(item: LibraryItem) = when {
    item.isFolder -> "folder"
    item.isPdf -> "PDF"
    item.isImage -> "image"
    else -> "file"
}

@Composable
fun ItemDialogs(state: ItemDialogState, vm: LibraryViewModel, destinations: List<Destination>) {
    state.renaming?.let { item ->
        NameDialog(
            title = "Rename ${kindOf(item)}",
            initial = item.displayName,
            confirm = "Rename",
            onConfirm = { vm.rename(item, it); state.renaming = null },
            onDismiss = { state.renaming = null },
        )
    }
    if (state.creatingFolder) {
        NameDialog(
            title = "New folder",
            initial = "",
            confirm = "Create",
            onConfirm = { vm.createFolder(it); state.creatingFolder = false },
            onDismiss = { state.creatingFolder = false },
        )
    }
    state.movingToNewFolder?.let { item ->
        NameDialog(
            title = "New folder",
            initial = "",
            confirm = "Create and move",
            onConfirm = { vm.moveToNewFolder(item, it); state.movingToNewFolder = null },
            onDismiss = { state.movingToNewFolder = null },
        )
    }
    state.moving?.let { item ->
        val targets = destinations.filter { it.documentId != item.parentId && it.documentId != item.documentId }
        AlertDialog(
            onDismissRequest = { state.moving = null },
            title = { Text("Move “${item.displayName}”", maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                // Scrolls: a shelf with many folders is taller than the dialog.
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { state.movingToNewFolder = item; state.moving = null }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_folder_add),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Text("New folder…", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 14.dp))
                    }
                    targets.forEach { target ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.move(item, target); state.moving = null }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_folder),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                if (target == destinations.firstOrNull()) "${target.name} (main)" else target.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 14.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { state.moving = null }) { Text("Cancel") } },
        )
    }
    state.deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { state.deleting = null },
            title = { Text("Delete ${kindOf(item)}?") },
            text = {
                Text(
                    if (item.isFolder) {
                        "“${item.name}”${when (item.childCount) { 0 -> ""; 1 -> " and the item inside it"; else -> " and the ${item.childCount} items inside it" }} will be removed from your device. This can't be undone."
                    } else {
                        "“${item.displayName}” will be removed from your device. This can't be undone."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.delete(item); state.deleting = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { state.deleting = null }) { Text("Cancel") } },
        )
    }
}

/** Single-field name prompt; the text arrives selected so typing replaces it. */
@Composable
fun NameDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val valid = isValidFileName(value.text)
    val problem = fileNameProblem(value.text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                isError = problem != null,
                supportingText = problem?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(value.text.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.text.trim()) }, enabled = valid) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val INVALID_CHARS = "/\\:*?\"<>|"

/**
 * What's wrong with [name] as a file or folder name, to show under the field; null when it's
 * fine or still empty (an empty field just keeps the button off).
 */
fun fileNameProblem(name: String): String? = when {
    name.any { it in INVALID_CHARS } -> "Names can't contain / \\ : * ? \" < > |"
    // Dock, like file managers, hides names that start with a dot: the file would vanish from the lists.
    name.trimStart().startsWith(".") -> "Names can't start with a dot"
    else -> null
}

fun isValidFileName(name: String): Boolean = name.isNotBlank() && fileNameProblem(name) == null
