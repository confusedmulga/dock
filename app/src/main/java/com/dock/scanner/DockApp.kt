package com.dock.scanner

import android.app.Application
import android.util.Log
import kotlin.concurrent.thread
import com.dock.scanner.core.PageProcessor
import com.dock.scanner.data.DockSettings
import com.dock.scanner.data.storage.DockLibrary
import com.dock.scanner.pdf.PdfTools

class DockApp : Application() {

    val library: DockLibrary by lazy { DockLibrary(this) }
    val settings: DockSettings by lazy { DockSettings(this) }

    override fun onCreate() {
        super.onCreate()
        // Loading OpenCV takes a while; do it off the main thread so the app opens right away.
        thread(name = "opencv-loader") {
            if (!PageProcessor.initOpenCv()) Log.e("DockApp", "OpenCV native library failed to load")
        }
        PdfTools.init(this)
    }
}
