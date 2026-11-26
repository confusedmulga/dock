package com.dock.scanner.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import com.dock.scanner.pdf.PasswordProtectedException
import com.dock.scanner.pdf.PdfTools
import java.io.File
import java.io.IOException
import java.util.UUID
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dock.scanner.DockApp
import com.dock.scanner.data.FileSort
import com.dock.scanner.data.storage.DockLibrary
import com.dock.scanner.data.storage.LibraryItem
import com.dock.scanner.ui.components.MainTab
import kotlinx.coroutines.CancellationException
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

    private val settings = (app as DockApp).settings

    /** Order of the Files tab; remembered between launches. */
    private val _fileSort = MutableStateFlow(settings.fileSort)
    val fileSort: StateFlow<FileSort> = _fileSort.asStateFlow()

    fun setFileSort(sort: FileSort) {
        settings.fileSort = sort
        _fileSort.value = sort
    }

    /**
     * Where the library lives, e.g. "Documents/Dock". Folders on other providers (a cloud drive, an
     * SD card app) have to be asked for their name, which happens off the main thread.
     */
    val rootLabel: StateFlow<String> = _root
        .mapLatest { root -> root?.let { withContext(Dispatchers.IO) { runCatching { library.rootLabel(it) }.getOrNull() } } ?: "Dock" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _root.value?.let(library::localRootLabel) ?: "Dock")

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

    /** Searchable text for PDFs, worked on in the background. */
    val ocr = (app as DockApp).ocr

    init {
        viewModelScope.launch { ocr.messages.collect { _messages.send(it) } }
        // A PDF got its text added or taken out: it's a new file now (new size and date).
        viewModelScope.launch {
            ocr.changed.collect { documentId ->
                refresh()
                val viewed = _viewing.value ?: return@collect
                val root = _root.value ?: return@collect
                if (viewed.documentId == documentId) {
                    withContext(Dispatchers.IO) { runCatching { library.itemFor(root, documentId, viewed.parentId) }.getOrNull() }
                        ?.let { fresh -> _viewing.value = fresh.copy(folderName = viewed.folderName) }
                }
            }
        }
    }

    /**
     * The PDF open in the viewer. Kept up to date when it's renamed or moved (both give it a new
     * URI); becomes null when it's deleted.
     */
    private val _viewing = MutableStateFlow<LibraryItem?>(null)
    val viewing: StateFlow<LibraryItem?> = _viewing.asStateFlow()

    /**
     * Goes up each time Dock rewrites the viewed file itself, so the viewer opens it afresh. Its
     * modified time can't be relied on for that: right after a write, storage can still report
     * the old one for a moment.
     */
    private val _viewingRevision = MutableStateFlow(0)
    val viewingRevision: StateFlow<Int> = _viewingRevision.asStateFlow()

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

    /** Creates a folder in the Dock folder to save into (from the save sheet), and hands it to [onCreated]. */
    fun createDestination(name: String, onCreated: (Destination) -> Unit) {
        val root = _root.value ?: return
        viewModelScope.launch {
            val created = withContext(Dispatchers.IO) {
                runCatching {
                    val rootId = library.rootId(root)
                    val id = library.idOf(library.createFolder(root, rootId, name) ?: error("create failed"))
                    // The provider has the last word on the name: it adds " (1)" when it's taken.
                    Destination(id, library.itemFor(root, id, rootId)?.name ?: name)
                }.getOrNull()
            }
            if (created == null) {
                _messages.trySend("Couldn't create the folder")
            } else {
                refresh()
                onCreated(created)
            }
        }
    }

    /**
     * Saves [item] with its pages in [order] (each the page's index in the file now). The PDF
     * is rewritten from a copy, and put back as it was if writing fails partway. [done] hears
     * whether it worked; the viewer then shows the saved file.
     */
    fun reorderPages(item: LibraryItem, order: List<Int>, done: (Boolean) -> Unit) {
        val root = _root.value ?: return done(false)
        val app = getApplication<Application>()
        viewModelScope.launch {
            val problem = withContext(Dispatchers.IO) {
                val dir = File(app.cacheDir, "reorder/${UUID.randomUUID()}").apply { mkdirs() }
                try {
                    val before = library.stat(item.uri) ?: return@withContext "The PDF isn't there anymore"
                    val input = File(dir, "in.pdf")
                    app.contentResolver.openInputStream(item.uri)!!.use { src -> input.outputStream().use { src.copyTo(it) } }
                    val output = File(dir, "out.pdf")
                    PdfTools.reorder(input, output, order)
                    // Rewritten meanwhile (its text was just made searchable, or another app saved it): keep that.
                    if (library.stat(item.uri) != before) return@withContext "The PDF changed while saving. Try again."
                    try {
                        write(app, output, item.uri)
                    } catch (e: IOException) {
                        // The file was emptied by then: put the original back.
                        runCatching { write(app, input, item.uri) }
                        throw e
                    }
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PasswordProtectedException) {
                    "Password-protected PDFs can't be rearranged yet"
                } catch (e: Throwable) {
                    // Errors too: a malformed PDF can overflow the stack, a huge one run out of memory.
                    Log.w("LibraryViewModel", "Couldn't reorder pages", e)
                    "Couldn't save the new page order"
                } finally {
                    dir.deleteRecursively()
                }
            }
            if (problem != null) {
                _messages.trySend(problem)
                done(false)
                return@launch
            }
            if (_viewing.value?.documentId == item.documentId) {
                withContext(Dispatchers.IO) { runCatching { library.itemFor(root, item.documentId, item.parentId) }.getOrNull() }
                    ?.let { fresh -> _viewing.value = fresh.copy(folderName = item.folderName) }
                _viewingRevision.value++
            }
            refresh()
            done(true)
        }
    }

    private fun write(app: Application, file: File, uri: Uri) {
        app.contentResolver.openOutputStream(uri, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
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
