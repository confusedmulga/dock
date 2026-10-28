package com.dock.scanner.ui.preview

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dock.scanner.office.OfficeFormat
import com.dock.scanner.office.OfficeReader
import com.dock.scanner.office.OpenedDocument
import com.dock.scanner.office.UnreadableDocumentException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

sealed interface PreviewState {
    data object Loading : PreviewState
    class Ready(val opened: OpenedDocument) : PreviewState
    data class Failed(val message: String) : PreviewState
}

/**
 * Loads a document for the preview. The file is copied into the cache first (zip and
 * compound files need random access), parsed off the main thread, and closed again when
 * the preview goes away.
 */
class DocumentPreviewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<PreviewState>(PreviewState.Loading)
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    private var loaded: Uri? = null
    private var opened: OpenedDocument? = null
    private var file: File? = null

    /** Opens [uri]; does nothing if it's already open. [name] and [mimeType] pick the reader. */
    fun load(uri: Uri, name: String?, mimeType: String?) {
        if (uri == loaded) return
        loaded = uri
        release()
        _state.value = PreviewState.Loading
        val context = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    val displayName = name ?: resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    val format = OfficeFormat.of(displayName, mimeType ?: resolver.getType(uri))
                        ?: throw UnreadableDocumentException("Dock can't preview this kind of file")
                    val dir = File(context.cacheDir, "preview").apply { mkdirs() }
                    val copy = File(dir, "${UUID.randomUUID()}.${format.extensions.first()}")
                    resolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                        ?: throw UnreadableDocumentException("This file can't be opened")
                    try {
                        copy to OfficeReader.open(copy, format)
                    } catch (e: Throwable) {
                        copy.delete()
                        throw e
                    }
                }
            }
            if (loaded != uri) {
                result.getOrNull()?.let { (copy, doc) -> doc.close(); copy.delete() }
                return@launch
            }
            result.onSuccess { (copy, doc) ->
                file = copy
                opened = doc
                _state.value = PreviewState.Ready(doc)
            }.onFailure {
                _state.value = PreviewState.Failed(
                    (it as? UnreadableDocumentException)?.message ?: "This document can't be previewed",
                )
            }
        }
    }

    private fun release() {
        opened?.close()
        opened = null
        file?.delete()
        file = null
    }

    override fun onCleared() {
        release()
    }
}
