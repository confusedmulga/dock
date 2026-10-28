package com.dock.scanner.core

import androidx.exifinterface.media.ExifInterface
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Turns a captured photo into a finished page image: crop + perspective fix, filter, rotate. */
object PageProcessor {

    private const val EDGE_TRIM = 0.004
    private const val FULL_COLOR_QUALITY = 95

    private val openCvLoaded: Boolean by lazy { OpenCVLoader.initLocal() }

    /**
     * Loads OpenCV's native library (once; later calls return immediately). Safe to call from
     * any thread, and blocks until loading finishes if another thread started it.
     */
    fun initOpenCv(): Boolean = openCvLoaded

    /**
     * Upright pixel size of a JPEG, honouring its EXIF orientation. The size comes from the
     * image itself: EXIF size tags go stale when an app edits a photo without updating them.
     */
    fun uprightSize(file: File): Pair<Int, Int> {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) throw IOException("Cannot read ${file.name}")
        val (width, height) = opts.outWidth to opts.outHeight
        return if (ExifInterface(file).rotationDegrees % 180 == 0) width to height else height to width
    }

    /** Auto-detects the document in a photo file. Decodes at 1/4 resolution, so it is fast. */
    fun detect(file: File, detector: DocumentDetector): Quad? {
        initOpenCv()
        val gray = loadUpright(file, Imgcodecs.IMREAD_REDUCED_GRAYSCALE_4)
        try {
            return detector.detect(gray)
        } finally {
            gray.release()
        }
    }

    /** Renders the page and returns its pixel size (width to height). */
    fun process(
        source: File,
        quad: Quad,
        filter: PageFilter,
        rotation: Int,
        output: File,
        maxSide: Int = 2480,
        jpegQuality: Int = 85,
    ): Pair<Int, Int> {
        initOpenCv()
        val src = loadUpright(source, Imgcodecs.IMREAD_COLOR)
        val warped = warp(src, quad, maxSide)
        src.release()
        val filtered = ImageFilters.apply(warped, filter)
        warped.release()
        val rotated = Mat()
        DocumentDetector.rotate(filtered, rotated, rotation)
        filtered.release()

        // OpenCV picks the encoder from the extension, so the temp name must still end in .jpg.
        val size = rotated.cols() to rotated.rows()
        val tmp = File(output.parentFile, "tmp_" + output.name)
        // At the top qualities, keep full color resolution as well (no chroma subsampling).
        val params = if (jpegQuality >= FULL_COLOR_QUALITY) {
            MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, jpegQuality, Imgcodecs.IMWRITE_JPEG_SAMPLING_FACTOR, Imgcodecs.IMWRITE_JPEG_SAMPLING_FACTOR_444)
        } else {
            MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, jpegQuality)
        }
        val ok = Imgcodecs.imwrite(tmp.absolutePath, rotated, params)
        params.release()
        rotated.release()
        if (!ok || !tmp.renameTo(output)) {
            tmp.delete()
            throw IOException("Could not write ${output.name}")
        }
        return size
    }

    private fun warp(src: Mat, quad: Quad, maxSide: Int): Mat {
        val w = src.cols()
        val h = src.rows()
        val (rawW, rawH) = quad.warpedSize(w, h)
        val scale = min(1.0, maxSide.toDouble() / max(rawW, rawH))
        val outW = (rawW * scale).roundToInt().coerceAtLeast(1).toDouble()
        val outH = (rawH * scale).roundToInt().coerceAtLeast(1).toDouble()

        val srcPts = MatOfPoint2f(*quad.corners.map { Point(it.x * w.toDouble(), it.y * h.toDouble()) }.toTypedArray())
        // Map the quad slightly beyond the output so the outermost pixels are dropped: a
        // corner placed a hair off the paper otherwise leaves a dark sliver along the edge.
        // A full-frame quad is a page imported from a PDF, not a photo: keep every pixel.
        val m = if (quad == Quad.FULL) 0.0 else EDGE_TRIM * max(outW, outH)
        val dstPts = MatOfPoint2f(Point(-m, -m), Point(outW + m, -m), Point(outW + m, outH + m), Point(-m, outH + m))
        val transform = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val out = Mat()
        Imgproc.warpPerspective(
            src, out, transform, Size(outW, outH), Imgproc.INTER_CUBIC, org.opencv.core.Core.BORDER_REPLICATE,
        )
        srcPts.release()
        dstPts.release()
        transform.release()
        return out
    }

    /** Decodes ignoring EXIF, then rotates explicitly, so behaviour doesn't depend on the codec build. */
    private fun loadUpright(file: File, flags: Int): Mat {
        val raw = Imgcodecs.imread(file.absolutePath, flags or Imgcodecs.IMREAD_IGNORE_ORIENTATION)
        if (raw.empty()) throw IOException("Cannot decode ${file.name}")
        val degrees = ExifInterface(file).rotationDegrees
        if (degrees == 0) return raw
        val out = Mat()
        DocumentDetector.rotate(raw, out, degrees)
        raw.release()
        return out
    }
}
