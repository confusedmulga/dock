package com.dock.scanner.data

import android.content.Context
import androidx.core.content.edit

/**
 * How much detail a scan keeps.
 *
 * @param maxSide longest side of a finished page, in pixels.
 * @param jpegQuality JPEG quality (0-100) for finished pages.
 * @param pdfDpi resolution pages of an opened PDF are rendered at for editing.
 */
enum class ScanQuality(
    val label: String,
    val description: String,
    val maxSide: Int,
    val jpegQuality: Int,
    val pdfDpi: Int,
) {
    Standard(
        "Standard",
        "Sharp, print-ready pages (300 dpi on A4), compressed to keep PDFs small.",
        maxSide = 2480,
        jpegQuality = 85,
        pdfDpi = 200,
    ),
    Original(
        "Original",
        "Keeps the photo's full resolution and detail, with no extra compression. PDFs are several times larger.",
        // Far beyond any phone photo; only there so a huge imported image can't exhaust memory.
        maxSide = 8192,
        jpegQuality = 100,
        pdfDpi = 300,
    );

    /** Largest page side when an opened PDF is rendered for editing. */
    val pdfMaxSide: Int get() = if (this == Original) 4200 else 2800
}

/** How the camera frames a page. */
enum class ScanMode(val label: String) {
    /** Finds the page's edges, live and in the photo, and crops to them. */
    AutoEdges("Auto edges"),

    /** Keeps the whole 4:3 frame; the page can still be cropped later. */
    Standard("Standard"),
}

/** Preferences that outlive a session. */
class DockSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var scanQuality: ScanQuality
        get() = prefs.getString(KEY_SCAN_QUALITY, null)
            ?.let { name -> ScanQuality.entries.firstOrNull { it.name == name } }
            ?: ScanQuality.Standard
        set(value) = prefs.edit { putString(KEY_SCAN_QUALITY, value.name) }

    var scanMode: ScanMode
        get() = prefs.getString(KEY_SCAN_MODE, null)
            ?.let { name -> ScanMode.entries.firstOrNull { it.name == name } }
            ?: ScanMode.AutoEdges
        set(value) = prefs.edit { putString(KEY_SCAN_MODE, value.name) }

    private companion object {
        const val KEY_SCAN_QUALITY = "scan_quality"
        const val KEY_SCAN_MODE = "scan_mode"
    }
}
