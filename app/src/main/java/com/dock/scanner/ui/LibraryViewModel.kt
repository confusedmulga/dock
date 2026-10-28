package com.dock.scanner.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dock.scanner.DockApp
import com.dock.scanner.data.storage.DockLibrary
import com.dock.scanner.data.storage.LibraryItem
import com.dock.scanner.ui.components.MainTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A folder the user can save into: the Dock folder itself or one of its sub-folders. */
data class Destination(val documentId: String, val name: String)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    val library: DockLibrary = (app as DockApp).library

    private val _root = MutableStateFlow(library.rootUri())
    val root: StateFlow<Uri?> = _root.asStateFlow()

    private val reload = MutableStateFlow(0)

    /** Every PDF and document, newest first; null while the first load runs. */
    val allFiles: StateFlow<List<LibraryItem>?> = combine(_root, reload) { root, _ -> root }
        .mapLatest { root -> root?.let { withContext(Dispatchers.IO) { runCatching { library.allFiles(it) }.getOrDefault(emptyList()) } } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Just the PDFs, for the PDF tools. */
    val allPdfs: StateFlow<List<LibraryItem>?> = allFiles.map { files -> files?.filter { it.isPdf } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Folders open on the Shelf, outermost first. Empty = the Dock folder itself. */
    private val _shelfPath = MutableStateFlow<List<LibraryItem>>(emptyList())
    val shelfPath: StateFlow<List<LibraryItem>> = _shelfPath.asStateFlow()

    val shelfContents: StateFlow<List<LibraryItem>?> = combine(_root, _shelfPath, reload) { root, path, _ -> root to path }
        .mapLatest { (root, path) ->
            root ?: return@mapLatest null
            val parent = path.lastOrNull()?.documentId ?: library.rootId(root)
            withContext(Dispatchers.IO) { runCatching { library.list(root, parent) }.getOrDefault(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The Dock folder plus its direct sub-folders: where a scan can be saved or a PDF moved. */
    val destinations: StateFlow<List<Destination>> = combine(_root, reload) { root, _ -> root }
        .mapLatest { root ->
            root ?: return@mapLatest emptyList()
            withContext(Dispatchers.IO) {
                runCatching {
                    val rootId = library.rootId(root)
                    listOf(Destination(rootId, rootName(root))) +
                        library.list(root, rootId).filter { it.isFolder }.map { Destination(it.documentId, it.name) }
                }.getOrDefault(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Selected bottom tab; lives here so tools can send the user to a result in the Shelf. */
    val tab = MutableStateFlow(MainTab.Home)

    /** Opens [folder] on the Shelf tab. */
    fun showInShelf(folder: LibraryItem) {
        _shelfPath.value = listOf(folder)
        tab.value = MainTab.Shelf
        refresh()
    }

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /**
     * The PDF open in the viewer. Kept up to date when it's renamed or moved (both give it a new
     * URI); becomes null when it's deleted.
     */
    private val _viewing = MutableStateFlow<LibraryItem?>(null)
    val viewing: StateFlow<LibraryItem?> = _viewing.asStateFlow()

    /** The tab the viewed file was opened from, so its page morphs back into the right thumbnail. */
    var viewingFrom: String = ""
        private set

    fun view(item: LibraryItem) {
        viewingFrom = tab.value.name
        _viewing.value = item
        viewModelScope.launch { _viewing.value = withFolderName(item) }
    }

    /** Fills in the name of the folder the item is in (null when it's directly in the Dock folder). */
    private suspend fun withFolderName(item: LibraryItem): LibraryItem {
        val root = _root.value ?: return item
        if (item.parentId == library.rootId(root)) return item.copy(folderName = null)
        val name = destinations.value.firstOrNull { it.documentId == item.parentId }?.name
            ?: withContext(Dispatchers.IO) { runCatching { library.itemFor(root, item.parentId, "")?.name }.getOrNull() }
        return item.copy(folderName = name)
    }

    /** After a rename or move: re-read the item under its new URI if it's the one being viewed. */
    private suspend fun followViewed(old: LibraryItem, root: Uri, newUri: Uri, parentId: String) {
        if (_viewing.value?.documentId != old.documentId) return
        val fresh = library.itemFor(root, library.idOf(newUri), parentId) ?: return
        _viewing.value = withFolderName(fresh)
    }

    /** Folder currently open on the Shelf, used as the default target for a new scan. */
    val currentFolderId: String? get() = _shelfPath.value.lastOrNull()?.documentId

    fun rootLabel(): String = _root.value?.let { runCatching { library.rootLabel(it) }.getOrNull() } ?: "Dock"

    private fun rootName(root: Uri) = library.rootLabel(root).substringAfterLast('/')

    /** Saves the folder the user picked. Returns false if Android didn't grant lasting access. */
    fun onRootChosen(treeUri: Uri): Boolean {
        val ok = runCatching { library.setRoot(treeUri) }.isSuccess
        if (!ok) _messages.trySend("Couldn't get access to that folder")
        _shelfPath.value = emptyList()
        refresh()
        return ok && _root.value != null
    }

    /** Re-checks access (it can be revoked from system settings) and reloads everything. */
    fun refresh() {
        _root.value = library.rootUri()
        reload.value++
    }

    fun openFolder(folder: LibraryItem) {
        _shelfPath.value = _shelfPath.value + folder
    }

    fun closeFolder() {
        _shelfPath.value = _shelfPath.value.dropLast(1)
    }

    fun createFolder(name: String) = mutate("Couldn't create the folder") { root ->
        val parent = currentFolderId ?: library.rootId(root)
        library.createFolder(root, parent, name) ?: error("create failed")
    }

    fun rename(item: LibraryItem, name: String) = mutate("Couldn't rename") { root ->
        val uri = library.rename(item, name) ?: error("rename failed")
        stepOutOf(item)
        followViewed(item, root, uri, item.parentId)
    }

    fun move(item: LibraryItem, target: Destination) = mutate("Couldn't move") { root ->
        val uri = library.move(root, item, target.documentId) ?: error("move failed")
        stepOutOf(item)
        followViewed(item, root, uri, target.documentId)
        _messages.trySend("Moved to ${target.name}")
    }

    /** Creates a folder in the Dock folder and moves [item] into it. */
    fun moveToNewFolder(item: LibraryItem, folderName: String) = mutate("Couldn't create the folder") { root ->
        val folderUri = library.createFolder(root, library.rootId(root), folderName) ?: error("create failed")
        val folderId = library.idOf(folderUri)
        val uri = library.move(root, item, folderId) ?: error("move failed")
        followViewed(item, root, uri, folderId)
        _messages.trySend("Moved to $folderName")
    }

    fun delete(item: LibraryItem) = mutate("Couldn't delete") {
        if (!library.delete(item)) error("delete failed")
        stepOutOf(item)
        if (_viewing.value?.documentId == item.documentId) _viewing.value = null
    }

    /** A folder open on the Shelf that was renamed, moved or deleted no longer exists under its old ID. */
    private fun stepOutOf(item: LibraryItem) {
        if (_shelfPath.value.any { it.documentId == item.documentId }) {
            _shelfPath.value = _shelfPath.value.takeWhile { it.documentId != item.documentId }
        }
    }

    private fun mutate(failure: String, block: suspend (Uri) -> Unit) {
        val root = _root.value ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { block(root) }.isSuccess }
            if (!ok) _messages.trySend(failure)
            refresh()
        }
    }
}
