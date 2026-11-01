package com.dock.scanner.ui.camera

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.dock.scanner.core.CornerModel
import com.dock.scanner.core.DocumentDetector
import com.dock.scanner.core.Quad
import com.dock.scanner.core.QuadSmoother
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * Runs edge detection on the camera's luminance plane. Only the visible crop is analysed,
 * so returned coordinates map 1:1 onto the preview. CameraX drops frames while this is
 * busy (KEEP_ONLY_LATEST), so slow devices just update the outline less often instead of lagging.
 *
 * The corner model gets a look too, a few times a second (see [CornerModel.pick]); between its
 * runs, its last verdict stands: whether to trust the classic outline, or its own instead.
 * [cornerModel] is asked for it on the analysis thread, the first time it's needed, since
 * loading it takes a moment.
 */
class LiveDocumentAnalyzer(
    cornerModel: () -> CornerModel?,
    private val onResult: (Quad?) -> Unit,
) : ImageAnalysis.Analyzer {

    private val cornerModel by lazy(LazyThreadSafetyMode.NONE, cornerModel)

    private val detector = DocumentDetector(workSize = 360)
    private val smoother = QuadSmoother()
    private var learned: Quad? = null
    private var learnedAt = 0L
    private var preferLearned = false
    private var luma = ByteArray(0)
    private var frame: Mat? = null

    override fun analyze(image: ImageProxy) {
        try {
            val crop = image.cropRect
            val width = crop.width()
            val height = crop.height()
            if (luma.size != width * height) {
                luma = ByteArray(width * height)
                frame?.release()
                frame = Mat(height, width, CvType.CV_8UC1)
            }
            // The Y plane always has pixelStride 1 but rows may be padded.
            val plane = image.planes[0]
            val buffer = plane.buffer
            for (row in 0 until height) {
                buffer.position((crop.top + row) * plane.rowStride + crop.left)
                buffer.get(luma, row * width, width)
            }
            val mat = frame!!
            mat.put(0, 0, luma)
            val rotation = image.imageInfo.rotationDegrees
            val classic = detector.detect(mat, rotation)
            val now = SystemClock.elapsedRealtime()
            cornerModel?.let { model ->
                if (now - learnedAt >= MODEL_INTERVAL_MS) {
                    learned = model.detect(mat, rotation)
                    learnedAt = now
                    preferLearned = learned != null && CornerModel.pick(classic, learned) === learned
                }
            }
            onResult(smoother.update(if (preferLearned) learned else classic))
        } catch (e: Exception) {
            Log.w("LiveDocumentAnalyzer", "Frame analysis failed", e)
        } finally {
            image.close()
        }
    }

    private companion object {
        /** How often the corner model runs. */
        const val MODEL_INTERVAL_MS = 250L
    }
}
