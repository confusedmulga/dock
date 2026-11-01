package com.dock.scanner.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.content.edit
import androidx.core.net.toUri
import com.dock.scanner.office.OfficeFormat
import java.io.File

/** A file or folder inside the user's Dock folder. */
data class LibraryItem(
    val uri: Uri,
    val documentId: String,
    val parentId: String,
    val name: String,
    val isFolder: Boolean,
    val lastModified: Long,
    val size: Long,
    /** Containing folder's name, or null when the item sits directly in the Dock folder. */
    val folderName: String? = null,
    /** Number of entries, for folders. */
    val childCount: Int = 0,
    val mimeType: String = "",
    /**
     * When the file last changed in a way the user would count, for showing and sorting:
     * Dock adding or removing searchable text doesn't. [lastModified] is the file system's.
     */
    val date: Long = lastModified,
) {
    val isPdf: Boolean get() = !isFolder && (mimeType == DockLibrary.MIME_PDF || name.endsWith(".pdf", ignoreCase = true))
    val isImage: Boolean get() = !isFolder && mimeType.startsWith("image/")

    /** Word, Excel, PowerPoint, OpenDocument, RTF, CSV or text: shown in the document preview. */
    val documentFormat: OfficeFormat? get() = if (isFolder || isPdf || isImage) null else OfficeFormat.of(name, mimeType)
    val isDocument: Boolean get() = documentFormat != null

    /** Name without the file extension. */
    val displayName: String get() = if (isFolder) name else name.substringBeforeLast('.', name)
    val extension: String get() = if (isFolder || '.' !in name) "" else name.substringAfterLast('.')
}

/**
 * The PDF library: a real folder in shared storage (Documents/Dock by default) that the
 * user granted through the system folder picker. Everything goes through the Storage Access
 * Framework, so files stay visible and editable in any file manager and no storage
 * permission is needed.
 *
 * All methods do I/O; call them off the main thread.
 */
class DockLibrary(context: Context) {

    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    /** Dates to show for files Dock rewrote only for their text layer, keyed by their new size and time. */
    private val keptDates = context.getSharedPreferences("kept_dates", Context.MODE_PRIVATE)

    /** The granted folder, or null before setup or after the user revoked access. */
    fun rootUri(): Uri? {
        val uri = prefs.getString(KEY_ROOT, null)?.toUri() ?: return null
        val granted = resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        return if (granted) uri else null
    }

    fun setRoot(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.takePersistableUriPermission(treeUri, flags)
        // Only ever keep one folder grant around.
        resolver.persistedUriPermissions
            .filter { it.uri != treeUri }
            .forEach { runCatching { resolver.releasePersistableUriPermission(it.uri, flags) } }
        prefs.edit { putString(KEY_ROOT, treeUri.toString()) }
    }

    fun rootId(root: Uri): String = DocumentsContract.getTreeDocumentId(root)

    /** Human-readable location, e.g. "Documents/Dock". */
    fun rootLabel(root: Uri): String {
        val id = rootId(root)
        return if (root.authority == EXTERNAL_STORAGE) id.substringAfter(':').ifEmpty { "Internal storage" }
        else query(DocumentsContract.buildDocumentUriUsingTree(root, id), root, id).firstOrNull()?.name ?: id
    }

    fun list(root: Uri, parentId: String): List<LibraryItem> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, parentId)
        return query(children, root, parentId)
            .filter { it.isFolder || it.isPdf || it.isImage || it.isDocument }
            .map { if (it.isFolder) it.copy(childCount = countChildren(root, it.documentId)) else it }
            .sortedWith(compareByDescending<LibraryItem> { it.isFolder }.thenBy { if (it.isFolder) it.name.lowercase() else "" }
                .thenByDescending { it.date })
    }

    /** Every PDF and document in the library (a few folder levels deep), newest first. */
    fun allFiles(root: Uri, maxDepth: Int = 4): List<LibraryItem> {
        val result = ArrayList<LibraryItem>()
        fun walk(parentId: String, folderName: String?, depth: Int) {
            val children = query(DocumentsContract.buildChildDocumentsUriUsingTree(root, parentId), root, parentId)
            for (child in children) {
                when {
                    child.isFolder && depth < maxDepth -> walk(child.documentId, child.name, depth + 1)
                    child.isPdf || child.isDocument -> result += child.copy(folderName = folderName)
                }
            }
        }
        walk(rootId(root), null, 0)
        return result.sortedByDescending { it.date }
    }

    fun createFolder(root: Uri, parentId: String, name: String): Uri? =
        DocumentsContract.createDocument(resolver, documentUri(root, parentId), Document.MIME_TYPE_DIR, name.trim())

    /** Creates an empty file; the provider adds " (1)" etc. if the name is taken. */
    fun createFile(root: Uri, parentId: String, mimeType: String, name: String): Uri? =
        DocumentsContract.createDocument(resolver, documentUri(root, parentId), mimeType, name.trim())

    /** The document ID of something created in the library, e.g. to save into a new folder. */
    fun idOf(uri: Uri): String = DocumentsContract.getDocumentId(uri)

    /** Creates an empty PDF; the provider adds " (1)" etc. if the name is taken. */
    fun createPdf(root: Uri, parentId: String, name: String): Uri? {
        val fileName = name.trim().removeSuffix(".pdf") + ".pdf"
        return DocumentsContract.createDocument(resolver, documentUri(root, parentId), MIME_PDF, fileName)
    }

    /** Renames, keeping the file's extension. */
    fun rename(item: LibraryItem, newName: String): Uri? {
        val ext = item.extension
        val base = newName.trim().let { if (ext.isNotEmpty()) it.removeSuffix(".$ext") else it }
        val name = if (ext.isEmpty()) base else "$base.$ext"
        return DocumentsContract.renameDocument(resolver, item.uri, name)
    }

    fun move(root: Uri, item: LibraryItem, targetParentId: String): Uri? =
        DocumentsContract.moveDocument(resolver, item.uri, documentUri(root, item.parentId), documentUri(root, targetParentId))

    fun delete(item: LibraryItem): Boolean = DocumentsContract.deleteDocument(resolver, item.uri)

    fun documentUri(root: Uri, documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(root, documentId)

    /** Fresh details of one file or folder, e.g. after it was renamed or moved. */
    fun itemFor(root: Uri, documentId: String, parentId: String): LibraryItem? =
        query(documentUri(root, documentId), root, parentId).firstOrNull()

    /**
     * After Dock rewrote [uri] only to add or take out its searchable text: keeps showing the
     * date it had before ([before], its size and time then), so it doesn't jump to the top of
     * the lists. Any later change to the file shows as usual.
     */
    fun keepDate(uri: Uri, before: Pair<Long, Long>) {
        val after = stat(uri) ?: return
        synchronized(kept) {
            val date = shownDate(before)
            keptFor(before)?.let { old ->
                kept -= old
                keptDates.edit { remove(dateKey(old.stat)) }
            }
            kept += Kept(after, date)
            keptDates.edit { putLong(dateKey(after), date) }
        }
    }

    /** A rewritten file's size and time as Dock saw them right after writing, and the date to show for it. */
    private class Kept(val stat: Pair<Long, Long>, val date: Long)

    private val kept: MutableList<Kept> by lazy {
        keptDates.all.mapNotNullTo(ArrayList()) { (key, value) ->
            val parts = key.split('@').mapNotNull(String::toLongOrNull)
            if (parts.size == 2 && value is Long) Kept(parts[0] to parts[1], value) else null
        }
    }

    /**
     * The storage layer can settle a file's time a moment after the write returns (tens of
     * milliseconds on the emulator), so the time may be a little later than the one recorded.
     */
    private fun keptFor(stat: Pair<Long, Long>): Kept? = synchronized(kept) {
        kept.firstOrNull { it.stat.first == stat.first && stat.second - it.stat.second in -SETTLE_MS..SETTLE_MS }
    }

    private fun shownDate(stat: Pair<Long, Long>) = keptFor(stat)?.date ?: stat.second

    private fun dateKey(stat: Pair<Long, Long>) = "${stat.first}@${stat.second}"

    /** A file's size and last-modified time, to tell whether it changed; null if it's gone. */
    fun stat(uri: Uri): Pair<Long, Long>? = runCatching {
        resolver.query(uri, arrayOf(Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null
        }
    }.getOrNull()

    private fun countChildren(root: Uri, folderId: String): Int =
        resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(root, folderId),
            arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null,
        )?.use { it.count } ?: 0

    private fun query(uri: Uri, root: Uri, parentId: String): List<LibraryItem> {
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
        )
        val items = ArrayList<LibraryItem>()
        resolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (name.startsWith(".")) continue
                val lastModified = if (c.isNull(3)) 0 else c.getLong(3)
                val size = if (c.isNull(4)) 0 else c.getLong(4)
                items += LibraryItem(
                    uri = DocumentsContract.buildDocumentUriUsingTree(root, id),
                    documentId = id,
                    parentId = parentId,
                    name = name,
                    isFolder = c.getString(2) == Document.MIME_TYPE_DIR,
                    mimeType = c.getString(2).orEmpty(),
                    lastModified = lastModified,
                    size = size,
                    date = shownDate(size to lastModified),
                )
            }
        }
        return items
    }

    companion object {
        const val MIME_PDF = "application/pdf"

        /** How far a rewritten file's time may drift from the one Dock recorded, either way. */
        private const val SETTLE_MS = 3_000L
        private const val KEY_ROOT = "root_tree_uri"
        private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

        /** Where the folder picker opens when the user accepts the default location. */
        val defaultFolderUri: Uri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:Documents/Dock")
        val documentsFolderUri: Uri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:Documents")

        /**
         * Creates Documents/Dock so the picker can open right inside it. Allowed without any
         * permission on Android 11+; on older versions this may fail and the user creates the
         * folder from the picker instead.
         */
        @Suppress("DEPRECATION")
        fun ensureDefaultFolder(): Boolean = runCatching {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Dock")
            dir.isDirectory || dir.mkdirs()
        }.getOrDefault(false)
    }
}
