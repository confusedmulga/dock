package com.dock.scanner.ui.images

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dock.scanner.DockApp
import com.dock.scanner.data.images.ImageResizer
import com.dock.scanner.data.images.OutputFormat
import com.dock.scanner.data.images.ResizeMode
import com.dock.scanner.data.images.ResizeOptions
import com.dock.scanner.data.images.SizeUnit
import com.dock.scanner.data.storage.LibraryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class ImageInput(val uri: Uri, val name: String, val width: Int, val height: Int, val bytes: Long)

data class ResizedFile(val item: LibraryItem, val width: Int, val height: Int, val bytes: Long)

sealed interface ResizeState {
    data object Idle : ResizeState
    data class Working(val progress: Float) : ResizeState
    data class Done(val files: List<ResizedFile>, val folder: LibraryItem?) : ResizeState
    data class Failed(val message: String) : ResizeState
}

/** The form as typed: text fields stay text until resizing, so half-typed numbers like "12." are fine. */
data class ResizeForm(
    val width: String = "70",
    val height: String = "70",
    val unit: SizeUnit = SizeUnit.Percent,
    val locked: Boolean = true,
    val dpi: String = "72",
    val mode: ResizeMode = ResizeMode.Stretch,
    val format: OutputFormat = OutputFormat.Jpg,
    val quality: Int = 90,
    val background: Int = ResizeOptions.WHITE,
) {
    /** The options, or null while a field isn't a usable number. */
    fun options(): ResizeOptions? {
        val w = width.replace(',', '.').toFloatOrNull()?.takeIf { it > 0 } ?: return null
        val h = height.replace(',', '.').toFloatOrNull()?.takeIf { it > 0 } ?: if (locked) w else return null
        val d = dpi.toIntOrNull()?.takeIf { it in 1..9600 } ?: return null
        return ResizeOptions(w, h, unit, locked, d, mode, format, quality, background)
    }
}

/**
 * The image resizer behind the Images tab. Results go into the Dock folder: one picture on
 * its own, several into a new "Resized images" folder (the website zips them instead).
 */
class ImagesViewModel(app: Application) : AndroidViewModel(app) {

    private val library = (app as DockApp).library
    private val resolver = app.contentResolver

    private val _inputs = MutableStateFlow<List<ImageInput>>(emptyList())
    val inputs: StateFlow<List<ImageInput>> = _inputs.asStateFlow()

    private val _form = MutableStateFlow(ResizeForm())
    val form: StateFlow<ResizeForm> = _form.asStateFlow()

    private val _state = MutableStateFlow<ResizeState>(ResizeState.Idle)
    val state: StateFlow<ResizeState> = _state.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _loading.value = true
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        var name = "Image"
                        var size = 0L
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                            if (c.moveToFirst()) {
                                c.getString(0)?.let { name = it }
                                if (!c.isNull(1)) size = c.getLong(1)
                            }
                        }
                        val (w, h) = ImageResizer.size(resolver, uri)
                        ImageInput(uri, name, w, h, size)
                    }.getOrNull()
                }
            }
            val first = _inputs.value.isEmpty()
            _inputs.update { list -> list + added.filter { new -> list.none { it.uri == new.uri } } }
            if (added.size < uris.size) _state.value = ResizeState.Failed("Some files couldn't be read as images")
            else if (_state.value !is ResizeState.Working) _state.value = ResizeState.Idle
            // Sizes in pixels or physical units start from the first picture, as on the website.
            if (first && _form.value.unit != SizeUnit.Percent) setUnit(_form.value.unit)
            _loading.value = false
        }
    }

    fun remove(input: ImageInput) {
        _inputs.update { list -> list.filterNot { it.uri == input.uri } }
        if (_state.value !is ResizeState.Working) _state.value = ResizeState.Idle
    }

    fun clear() {
        _inputs.value = emptyList()
        if (_state.value !is ResizeState.Working) _state.value = ResizeState.Idle
    }

    fun edit(change: (ResizeForm) -> ResizeForm) {
        _form.update(change)
        if (_state.value is ResizeState.Done || _state.value is ResizeState.Failed) _state.value = ResizeState.Idle
    }

    /** Switching units resets the size: 70% in percent, else the first picture's own size. */
    fun setUnit(unit: SizeUnit) = edit { form ->
        val first = _inputs.value.firstOrNull()
        val dpi = form.dpi.toIntOrNull()?.coerceAtLeast(1) ?: 72
        fun fmt(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else ((v * 100).roundToInt() / 100f).toString()
        val (w, h) = when {
            unit == SizeUnit.Percent -> "70" to "70"
            first == null -> form.width to form.height
            unit == SizeUnit.Pixels -> first.width.toString() to first.height.toString()
            unit == SizeUnit.Centimeters -> fmt(first.width / (dpi / 2.54f)) to fmt(first.height / (dpi / 2.54f))
            else -> fmt(first.width.toFloat() / dpi) to fmt(first.height.toFloat() / dpi)
        }
        form.copy(unit = unit, width = w, height = h)
    }

    /** With the lock closed, editing one side recalculates the other from the first picture's proportions. */
    fun setWidth(text: String) = edit { form ->
        form.copy(width = text, height = if (form.locked) other(text, form, fromWidth = true) ?: form.height else form.height)
    }

    fun setHeight(text: String) = edit { form ->
        form.copy(height = text, width = if (form.locked) other(text, form, fromWidth = false) ?: form.width else form.width)
    }

    fun toggleLock() = edit { form ->
        val locked = !form.locked
        form.copy(locked = locked, height = if (locked) other(form.width, form, fromWidth = true) ?: form.height else form.height)
    }

    private fun other(text: String, form: ResizeForm, fromWidth: Boolean): String? {
        val v = text.replace(',', '.').toFloatOrNull() ?: return null
        if (form.unit == SizeUnit.Percent) return text
        val first = _inputs.value.firstOrNull() ?: return null
        val ratio = if (fromWidth) first.height.toFloat() / first.width else first.width.toFloat() / first.height
        val result = v * ratio
        return if (form.unit == SizeUnit.Pixels) result.roundToInt().toString() else ((result * 100).roundToInt() / 100f).toString()
    }

    fun resize() {
        val inputs = _inputs.value
        if (inputs.isEmpty() || _state.value is ResizeState.Working) return
        val options = _form.value.options() ?: run {
            _state.value = ResizeState.Failed("Enter a width, height and resolution")
            return
        }
        val root = library.rootUri() ?: run {
            _state.value = ResizeState.Failed("Choose a storage folder first")
            return
        }
        _state.value = ResizeState.Working(0f)
        viewModelScope.launch {
            _state.value = try {
                withContext(Dispatchers.IO) {
                    val rootId = library.rootId(root)
                    val folder = if (inputs.size > 1) {
                        val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date())
                        val uri = library.createFolder(root, rootId, "Resized images $stamp") ?: error("Couldn't create a folder")
                        library.itemFor(root, library.idOf(uri), rootId)
                    } else {
                        null
                    }
                    val parent = folder?.documentId ?: rootId
                    val files = inputs.mapIndexed { i, input ->
                        val result = ImageResizer.resize(resolver, input.uri, options)
                        // Same name, new extension, like the website's downloads.
                        val name = input.name.substringBeforeLast('.', input.name) + "." + options.format.extension
                        val uri = library.createFile(root, parent, options.format.mimeType, name) ?: error("Couldn't save $name")
                        resolver.openOutputStream(uri, "wt")!!.use { it.write(result.bytes) }
                        _state.value = ResizeState.Working((i + 1f) / inputs.size)
                        val item = library.itemFor(root, library.idOf(uri), parent) ?: error("Couldn't save $name")
                        ResizedFile(item, result.width, result.height, result.bytes.size.toLong())
                    }
                    ResizeState.Done(files, folder)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                ResizeState.Failed("That size is too large for this phone")
            } catch (e: Exception) {
                ResizeState.Failed(e.message ?: "Couldn't resize the images")
            }
        }
    }
}
