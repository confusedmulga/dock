package com.dock.scanner

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dock.scanner.data.storage.DockLibrary
import com.dock.scanner.ui.components.GlassBubble
import com.dock.scanner.ui.components.shareDocument
import com.dock.scanner.ui.preview.DocumentPreviewModel
import com.dock.scanner.ui.preview.PreviewContent
import com.dock.scanner.ui.preview.PreviewFrame
import com.dock.scanner.ui.theme.DockTheme
import com.dock.scanner.ui.viewer.ZoomablePdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opens documents and PDFs from other apps (a file manager, an email, a chat) in Dock's
 * preview, like Quick Look: read-only, with Share and Save to Dock.
 */
class PreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri = intent.data?.takeIf(::isOpenable) ?: run {
            Toast.makeText(this, "Dock can't open this file", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val mime = intent.type ?: contentResolver.getType(uri)
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
        val isPdf = mime == DockLibrary.MIME_PDF || name.endsWith(".pdf", ignoreCase = true)

        setContent {
            DockTheme {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    val model: DocumentPreviewModel = viewModel()
                    if (!isPdf) LaunchedEffect(uri) { model.load(uri, name, mime) }
                    val state by model.state.collectAsStateWithLifecycle()
                    PreviewFrame(
                        title = name.substringBeforeLast('.'),
                        onBack = ::finish,
                        onShare = { shareDocument(this, uri, name, mime ?: "application/octet-stream") },
                        actions = { haze ->
                            GlassBubble(haze, painterResource(R.drawable.ic_folder_add), "Save to Dock", { saveToDock(uri, name, mime) }, size = 48.dp)
                            Spacer(Modifier.width(10.dp))
                        },
                    ) { padding ->
                        if (isPdf) ZoomablePdf(uri, Modifier.fillMaxSize()) else PreviewContent(state, padding)
                    }
                }
            }
        }
    }

    /**
     * Only files another app shares through a content provider. This activity is exported and
     * reads with Dock's own access, so a file:// path (which could point into Dock's private
     * storage) or a link into Dock's own provider would let any app read Dock's files through it.
     */
    private fun isOpenable(uri: Uri): Boolean =
        uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority?.startsWith(packageName) != true

    /** Copies the file into the Dock folder, so it shows up in Files and on the Shelf. */
    private fun saveToDock(uri: Uri, name: String, mime: String?) {
        val library = (application as DockApp).library
        val root = library.rootUri()
        if (root == null) {
            Toast.makeText(this, "Open Dock once to choose where your files are kept", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val target = library.createFile(root, library.rootId(root), mime ?: "application/octet-stream", name)
                        ?: error("create failed")
                    contentResolver.openInputStream(uri)!!.use { input ->
                        contentResolver.openOutputStream(target, "wt")!!.use { input.copyTo(it) }
                    }
                }.isSuccess
            }
            Toast.makeText(this@PreviewActivity, if (ok) "Saved to Dock" else "Couldn't save the file", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        /** Opens [uri] (a document or PDF this app can read) in the preview. */
        fun intent(context: Context, uri: Uri, mimeType: String?): Intent =
            Intent(context, PreviewActivity::class.java)
                .setDataAndType(uri, mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
