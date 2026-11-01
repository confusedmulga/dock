package com.dock.scanner.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * A learned page-corner finder for the scenes [DocumentDetector] gets wrong: a page on a desk
 * of nearly the same color, shadows, busy backgrounds. It's DocAligner's lcnet100 heatmap model
 * (DocsaidLab, Apache 2.0): the image is shrunk to 256 x 256 and the model returns one heatmap
 * per corner. Grayscale works as well as color, so it reads the same frames as the classic
 * detector. About 30 ms per image on a desktop CPU.
 *
 * A second opinion rather than a replacement ([pick]): on most photos both agree to within a
 * percent, and the classic outline follows the paper's edges more exactly.
 * Thread-safe (ONNX Runtime sessions can run concurrently).
 */
class CornerModel private constructor(private val session: OrtSession) : Closeable {

    private val env = OrtEnvironment.getEnvironment()

    /**
     * @param gray single-channel 8-bit image, any size. Not modified.
     * @param rotationDegrees clockwise rotation that makes [gray] upright (0/90/180/270).
     * @return corners normalized to the upright image, or null when the model isn't sure there's a page.
     */
    fun detect(gray: Mat, rotationDegrees: Int = 0): Quad? {
        val small = Mat()
        val upright = Mat()
        val heat = try {
            // Squashing to a square first is the same as rotating first: the output is square either way.
            Imgproc.resize(gray, small, Size(INPUT.toDouble(), INPUT.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            DocumentDetector.rotate(small, upright, rotationDegrees)
            val pixels = ByteArray(INPUT * INPUT)
            upright.get(0, 0, pixels)
            // The model was trained on color: the gray value goes into all three channels.
            val plane = INPUT * INPUT
            val input = FloatArray(plane * 3)
            for (i in 0 until plane) {
                val v = (pixels[i].toInt() and 0xFF) / 255f
                input[i] = v
                input[plane + i] = v
                input[2 * plane + i] = v
            }
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong())).use { tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                    val output = (result[0] as OnnxTensor).floatBuffer
                    FloatArray(output.remaining()).also { output.get(it) }
                }
            }
        } finally {
            small.release()
            upright.release()
        }
        val corners = (0 until 4).map { c -> peak(heat, c * HEAT * HEAT) ?: return null }
        val quad = Quad.fromUnordered(corners)
        return quad.takeIf { it.isConvex() && area(it) >= MIN_AREA }
    }

    /**
     * Where one corner's heatmap (at [offset]) is hottest: the heat-weighted center of its
     * largest blob above the threshold, or null if the model saw no such corner.
     */
    private fun peak(heat: FloatArray, offset: Int): Point2? {
        val map = Mat(HEAT, HEAT, CvType.CV_32F)
        val mask = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            map.put(0, 0, heat.copyOfRange(offset, offset + HEAT * HEAT))
            Imgproc.threshold(map, mask, THRESHOLD, 255.0, Imgproc.THRESH_BINARY)
            mask.convertTo(mask, CvType.CV_8U)
            val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids)
            if (count < 2) return null
            val largest = (1 until count).maxBy { stats.get(it, Imgproc.CC_STAT_AREA)[0] }
            val ids = IntArray(HEAT * HEAT)
            labels.get(0, 0, ids)
            var sum = 0.0
            var sx = 0.0
            var sy = 0.0
            var best = 0f
            for (i in ids.indices) {
                if (ids[i] != largest) continue
                val v = heat[offset + i]
                sum += v
                sx += v * (i % HEAT)
                sy += v * (i / HEAT)
                if (v > best) best = v
            }
            if (best < MIN_PEAK) return null
            return Point2(((sx / sum + 0.5) / HEAT).toFloat(), ((sy / sum + 0.5) / HEAT).toFloat()).clamped()
        } finally {
            listOf(map, mask, labels, stats, centroids).forEach(Mat::release)
        }
    }

    override fun close() = session.close()

    companion object {
        private const val INPUT = 256
        private const val HEAT = 128
        private const val THRESHOLD = 0.3
        private const val MIN_PEAK = 0.5f

        /** Same floor as [DocumentDetector]: smaller outlines are more likely noise than a page. */
        private const val MIN_AREA = 0.08f

        /** Corners further apart than this (fraction of the image) mean the two detectors disagree. */
        private const val DISAGREEMENT = 0.05f

        /**
         * Chooses between the classic detector's outline and the model's. When both found a page
         * and they disagree, it's the classic one that's been fooled (in testing: a light desk
         * pulled two corners to the frame's edge), so the model wins; when they agree, the classic
         * outline is the sharper of the two.
         */
        fun pick(classic: Quad?, learned: Quad?): Quad? = when {
            learned == null -> classic
            classic == null -> learned
            classic.maxCornerDistance(learned) > DISAGREEMENT -> learned
            else -> classic
        }

        /** Loads the model bundled in the app's assets. */
        fun load(context: Context): CornerModel {
            check(PageProcessor.initOpenCv()) { "OpenCV failed to load" }
            val env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
            val model = context.assets.open("scanner/corners.ort").use { it.readBytes() }
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
                return CornerModel(env.createSession(model, options))
            }
        }

        /** Shoelace area of a normalized quad, as a fraction of the image. */
        private fun area(q: Quad): Float {
            val p = q.corners
            var twice = 0f
            for (i in p.indices) {
                val a = p[i]
                val b = p[(i + 1) % 4]
                twice += a.x * b.y - b.x * a.y
            }
            return abs(twice) / 2
        }
    }
}
