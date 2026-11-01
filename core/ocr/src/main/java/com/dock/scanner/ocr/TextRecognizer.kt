package com.dock.scanner.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One recognized word and where it is on the page image, in pixels (y down). */
class OcrWord(
    val text: String,
    /** Bottom-left corner. */
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    /** Direction the text runs in: cos and sin of its angle (1 and 0 for level text). */
    val cos: Float,
    val sin: Float,
)

/** A line of text as the detector found it. */
class OcrLine(val words: List<OcrWord>, val confidence: Float) {
    val text: String get() = words.joinToString(" ") { it.text }
}

/** Everything recognized on a [width] x [height] page image, line by line in reading order. */
class OcrPage(val width: Int, val height: Int, val lines: List<OcrLine>) {
    val text: String get() = lines.joinToString("\n") { it.text }
}

/**
 * On-device text recognition with PaddleOCR's PP-OCRv5 mobile models (Apache 2.0): a DB
 * detector finds the lines of text, then a CTC recognizer reads each one. The recognizer
 * covers Latin-script languages (English, French, German, Spanish, Polish, Turkish, ...).
 *
 * Heavy: loading takes a moment and keeps both models in memory, and a page takes a second
 * or two on a phone. Use it off the main thread and [close] it when done. Not thread-safe.
 */
class TextRecognizer private constructor(
    private val detector: OrtSession,
    private val reader: OrtSession,
    /** The recognizer's classes after "blank" (0); the class after the last is a space. */
    private val alphabet: List<String>,
) : Closeable {

    private val env = OrtEnvironment.getEnvironment()

    fun recognize(page: Bitmap): OcrPage {
        val rgba = Mat()
        Utils.bitmapToMat(page, rgba)
        val image = Mat()
        // Both models were trained on BGR images.
        Imgproc.cvtColor(rgba, image, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        try {
            val lines = findLines(image).mapNotNull { quad -> readLine(image, quad)?.let { quad to it } }
            return OcrPage(page.width, page.height, readingOrder(lines))
        } finally {
            image.release()
        }
    }

    override fun close() {
        detector.close()
        reader.close()
    }

    /** Text lines as corner quads (top-left, top-right, bottom-right, bottom-left) in image pixels. */
    private fun findLines(image: Mat): List<Array<Point>> {
        val scale = DETECT_SIDE.toDouble() / max(image.cols(), image.rows())
        val width = multipleOf32(image.cols() * scale)
        val height = multipleOf32(image.rows() * scale)
        val resized = Mat()
        Imgproc.resize(image, resized, Size(width.toDouble(), height.toDouble()))
        val input = tensor(resized, DETECT_MEAN, DETECT_STD)
        resized.release()

        // Probability that each pixel is inside text.
        val probability = Mat(height, width, CvType.CV_32F)
        infer(detector, input, longArrayOf(1, 3, height.toLong(), width.toLong())) { output, _ -> probability.put(0, 0, output) }
        val binary = Mat()
        Imgproc.threshold(probability, binary, PIXEL_THRESHOLD, 255.0, Imgproc.THRESH_BINARY)
        val mask = Mat()
        binary.convertTo(mask, CvType.CV_8U)
        binary.release()
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        mask.release()

        val sx = image.cols().toDouble() / width
        val sy = image.rows().toDouble() / height
        val quads = ArrayList<Array<Point>>()
        for (contour in contours.take(MAX_LINES)) {
            val points = MatOfPoint2f(*contour.toArray())
            val box = Imgproc.minAreaRect(points)
            points.release()
            if (min(box.size.width, box.size.height) >= 3 && score(probability, contour) >= BOX_THRESHOLD) {
                // The detector outlines a shrunken core of each line; grow it back to the text's size.
                val w = box.size.width
                val h = box.size.height
                val grow = w * h * UNCLIP_RATIO / (2 * (w + h))
                val grown = RotatedRect(box.center, Size(w + 2 * grow, h + 2 * grow), box.angle)
                if (min(grown.size.width, grown.size.height) >= 5) {
                    val corners = Array(4) { Point() }
                    grown.points(corners)
                    quads += ordered(corners.map { Point(it.x * sx, it.y * sy) })
                }
            }
            contour.release()
        }
        probability.release()
        return quads
    }

    /** Mean text probability inside [contour]: low for faint specks and noise. */
    private fun score(probability: Mat, contour: MatOfPoint): Double {
        val bounds = Imgproc.boundingRect(contour)
        val mask = Mat.zeros(bounds.height, bounds.width, CvType.CV_8U)
        val shifted = MatOfPoint(*contour.toArray().map { Point(it.x - bounds.x, it.y - bounds.y) }.toTypedArray())
        Imgproc.fillPoly(mask, listOf(shifted), Scalar(1.0))
        val region = probability.submat(bounds)
        val mean = Core.mean(region, mask).`val`[0]
        region.release()
        mask.release()
        shifted.release()
        return mean
    }

    /** Reads the line inside [quad], or null if the recognizer isn't sure it's text. */
    private fun readLine(image: Mat, quad: Array<Point>): OcrLine? {
        val (tl, tr, br, bl) = quad
        val lineWidth = max(distance(tl, tr), distance(bl, br))
        val lineHeight = max(distance(tl, bl), distance(tr, br))
        if (lineWidth < 2 || lineHeight < 2) return null

        // Straighten the line into an upright strip, then scale it to the recognizer's height.
        val from = MatOfPoint2f(tl, tr, br, bl)
        val to = MatOfPoint2f(Point(0.0, 0.0), Point(lineWidth, 0.0), Point(lineWidth, lineHeight), Point(0.0, lineHeight))
        val transform = Imgproc.getPerspectiveTransform(from, to)
        val strip = Mat()
        Imgproc.warpPerspective(image, strip, transform, Size(lineWidth, lineHeight), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        listOf(from, to, transform).forEach(Mat::release)
        val inputWidth = ceil(LINE_HEIGHT * lineWidth / lineHeight).toInt().coerceIn(16, MAX_LINE_WIDTH)
        val resized = Mat()
        Imgproc.resize(strip, resized, Size(inputWidth.toDouble(), LINE_HEIGHT.toDouble()))
        strip.release()
        val input = tensor(resized, READ_MEAN, READ_STD)
        resized.release()

        // Greedy CTC decoding: the likeliest class per time step, repeats and blanks dropped.
        val chars = ArrayList<Glyph>()
        infer(reader, input, longArrayOf(1, 3, LINE_HEIGHT.toLong(), inputWidth.toLong())) { output, shape ->
            val steps = shape[1].toInt()
            val classes = shape[2].toInt()
            var previous = 0
            for (t in 0 until steps) {
                var best = 0
                for (k in 1 until classes) if (output[t * classes + k] > output[t * classes + best]) best = k
                if (best != 0 && best != previous) {
                    val text = alphabet.getOrNull(best - 1) ?: " "
                    chars += Glyph(text, t, steps, output[t * classes + best])
                }
                previous = best
            }
        }
        if (chars.none { it.text != " " }) return null
        val confidence = chars.map { it.probability }.average().toFloat()
        if (confidence < MIN_CONFIDENCE) return null
        return OcrLine(words(chars, tl, tr, lineWidth, lineHeight), confidence)
    }

    /**
     * Splits a line at its spaces and places each word along it. Each character's time step
     * tells where it is across the strip; a word spans from its first to its last character,
     * plus half the line's usual character pitch on each side.
     */
    private fun words(chars: List<Glyph>, tl: Point, tr: Point, lineWidth: Double, lineHeight: Double): List<OcrWord> {
        val letters = chars.filter { it.text != " " }
        val steps = chars.first().steps
        val stepWidth = lineWidth / steps
        val pitch = if (letters.size > 1) (letters.last().step - letters.first().step).toDouble() / (letters.size - 1) else 3.0
        val cos = (tr.x - tl.x) / distance(tl, tr)
        val sin = (tr.y - tl.y) / distance(tl, tr)

        val words = ArrayList<OcrWord>()
        var start = 0
        while (start < chars.size) {
            if (chars[start].text == " ") {
                start++
                continue
            }
            var end = start
            while (end + 1 < chars.size && chars[end + 1].text != " ") end++
            val left = ((chars[start].step + 0.5 - pitch / 2) * stepWidth).coerceIn(0.0, lineWidth)
            val right = ((chars[end].step + 0.5 + pitch / 2) * stepWidth).coerceIn(left, lineWidth)
            // Bottom-left corner: along the top edge, then down by the line's height.
            val x = tl.x + cos * left - sin * lineHeight
            val y = tl.y + sin * left + cos * lineHeight
            val text = chars.subList(start, end + 1).joinToString("") { it.text }
            words += OcrWord(text, x.toFloat(), y.toFloat(), (right - left).toFloat(), lineHeight.toFloat(), cos.toFloat(), sin.toFloat())
            start = end + 1
        }
        return words
    }

    /** Top to bottom, and left to right within a row. */
    private fun readingOrder(lines: List<Pair<Array<Point>, OcrLine>>): List<OcrLine> {
        class Placed(val line: OcrLine, val left: Double, val center: Double, val height: Double)
        val placed = lines.map { (quad, line) ->
            Placed(line, quad.minOf { it.x }, quad.sumOf { it.y } / 4, distance(quad[0], quad[3]))
        }.sortedBy { it.center }
        val ordered = ArrayList<OcrLine>(placed.size)
        var row = ArrayList<Placed>()
        for (p in placed) {
            val first = row.firstOrNull()
            if (first != null && p.center - first.center > first.height / 2) {
                row.sortedBy { it.left }.mapTo(ordered) { it.line }
                row = ArrayList()
            }
            row += p
        }
        row.sortedBy { it.left }.mapTo(ordered) { it.line }
        return ordered
    }

    /** Runs [session] on [input] and hands [read] the first output and its shape. */
    private fun infer(session: OrtSession, input: FloatBuffer, shape: LongArray, read: (FloatArray, LongArray) -> Unit) {
        OnnxTensor.createTensor(env, input, shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val output = result[0] as OnnxTensor
                val values = FloatArray(output.floatBuffer.remaining())
                output.floatBuffer.get(values)
                read(values, output.info.shape)
            }
        }
    }

    private class Glyph(val text: String, val step: Int, val steps: Int, val probability: Float)

    companion object {
        /** Long side the page is scaled to for finding lines. */
        private const val DETECT_SIDE = 1280
        private val DETECT_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val DETECT_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private const val PIXEL_THRESHOLD = 0.3
        private const val BOX_THRESHOLD = 0.6
        private const val UNCLIP_RATIO = 1.5
        private const val MAX_LINES = 1000

        /** The recognizer reads strips this many pixels high. */
        private const val LINE_HEIGHT = 48
        private const val MAX_LINE_WIDTH = 3200
        private val READ_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
        private val READ_STD = floatArrayOf(0.5f, 0.5f, 0.5f)
        private const val MIN_CONFIDENCE = 0.5f

        /** Loads the models bundled in the app's assets. */
        fun load(context: Context, threads: Int = 4): TextRecognizer {
            check(OpenCVLoader.initLocal()) { "OpenCV failed to load" }
            val assets = context.assets
            // Errors only: the converted models trip harmless warnings on every load.
            val env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(threads)
                options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
                val detector = env.createSession(assets.open("ocr/det.ort").use { it.readBytes() }, options)
                val reader = try {
                    env.createSession(assets.open("ocr/rec.ort").use { it.readBytes() }, options)
                } catch (e: Throwable) {
                    detector.close()
                    throw e
                }
                val alphabet = assets.open("ocr/rec_dict.txt").bufferedReader().use { it.readText() }.split('\n').dropLastWhile { it.isEmpty() }
                return TextRecognizer(detector, reader, alphabet)
            }
        }

        private fun multipleOf32(value: Double) = max(32, (value / 32).roundToInt() * 32)

        private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

        /** Corners as top-left, top-right, bottom-right, bottom-left. */
        private fun ordered(corners: List<Point>): Array<Point> {
            val tl = corners.minBy { it.x + it.y }
            val br = corners.maxBy { it.x + it.y }
            val tr = corners.minBy { it.y - it.x }
            val bl = corners.maxBy { it.y - it.x }
            return arrayOf(tl, tr, br, bl)
        }

        /** HWC bytes to normalized CHW floats. */
        private fun tensor(image: Mat, mean: FloatArray, std: FloatArray): FloatBuffer {
            val plane = image.rows() * image.cols()
            val pixels = ByteArray(plane * 3)
            image.get(0, 0, pixels)
            val data = FloatArray(plane * 3)
            for (i in 0 until plane) {
                for (c in 0 until 3) {
                    data[c * plane + i] = ((pixels[i * 3 + c].toInt() and 0xFF) / 255f - mean[c]) / std[c]
                }
            }
            return FloatBuffer.wrap(data)
        }
    }
}
