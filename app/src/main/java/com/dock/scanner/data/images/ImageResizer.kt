package com.dock.scanner.data.images

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How width and height are given. Physical units use the resolution (DPI). */
enum class SizeUnit(val label: String) { Percent("Percent"), Pixels("Pixels"), Centimeters("Centimeters"), Inches("Inches") }

/** What happens when the new size has a different shape than the picture. */
enum class ResizeMode(val label: String) {
    /** Squeezes the whole picture into the new size. */
    Stretch("Stretch"),

    /** Fills the new size and trims what sticks out. */
    Crop("Crop"),

    /** Fits the whole picture inside, filling the rest with the background color. */
    Fit("Fit"),
}

enum class OutputFormat(val label: String, val extension: String, val mimeType: String) {
    Jpg("JPG", "jpg", "image/jpeg"),
    Png("PNG", "png", "image/png"),
    Gif("GIF", "gif", "image/gif"),
}

data class ResizeOptions(
    val width: Float = 70f,
    val height: Float = 70f,
    val unit: SizeUnit = SizeUnit.Percent,
    /** Keep the picture's proportions; [height] then follows [width]. */
    val locked: Boolean = true,
    val dpi: Int = 72,
    val mode: ResizeMode = ResizeMode.Stretch,
    val format: OutputFormat = OutputFormat.Jpg,
    /** JPEG quality, 1..100. */
    val quality: Int = 90,
    val background: Int = WHITE,
) {
    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}

class ResizedImage(val bytes: ByteArray, val width: Int, val height: Int)

/**
 * Resizes pictures the way the reduceimages.com resizer does: a new size as a percentage,
 * in pixels or in physical units at a resolution, kept in proportion or not (then
 * stretched, cropped or fitted), saved as JPG, PNG or GIF with a quality and a background
 * for transparency and fitting, and the resolution written into the file.
 */
object ImageResizer {

    /** Upper bound on output size, so a typo like 70000 px can't exhaust memory. */
    const val MAX_SIDE = 12_000
    private const val MAX_PIXELS = 80_000_000L

    /** The output size for a picture of [srcW] x [srcH] pixels. */
    fun targetSize(srcW: Int, srcH: Int, o: ResizeOptions): Pair<Int, Int> {
        fun px(value: Float, original: Int): Float = when (o.unit) {
            SizeUnit.Percent -> original * value / 100f
            SizeUnit.Pixels -> value
            SizeUnit.Centimeters -> value / 2.54f * o.dpi
            SizeUnit.Inches -> value * o.dpi
        }
        val w = px(o.width, srcW)
        val h = if (o.locked) {
            // Several pictures at once: each keeps its own proportions.
            if (o.unit == SizeUnit.Percent) srcH * o.width / 100f else w * srcH / srcW
        } else {
            px(o.height, srcH)
        }
        return w.roundToInt().coerceIn(1, MAX_SIDE) to h.roundToInt().coerceIn(1, MAX_SIDE)
    }

    /** Pixel size of a picture, after its EXIF rotation. */
    fun size(resolver: ContentResolver, uri: Uri): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Unsupported image")
        val rotated = rotation(resolver, uri) % 180 != 0
        return if (rotated) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
    }

    private fun rotation(resolver: ContentResolver, uri: Uri): Int =
        runCatching { resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0 }.getOrDefault(0)

    fun resize(resolver: ContentResolver, uri: Uri, o: ResizeOptions): ResizedImage {
        val (srcW, srcH) = size(resolver, uri)
        val (tw, th) = targetSize(srcW, srcH, o)
        if (tw.toLong() * th > MAX_PIXELS) throw IOException("The new size is too large")

        // Decode no larger than needed: at least the area of the target, at most 2x.
        val keepsWhole = o.locked || o.mode != ResizeMode.Crop
        val needW = if (keepsWhole) tw else max(tw, (th.toFloat() * srcW / srcH).roundToInt())
        var sample = 1
        while (srcW / (sample * 2) >= needW * 2 && srcH / (sample * 2) >= th) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Could not read the image")
        val degrees = rotation(resolver, uri)
        val source = if (degrees == 0) decoded else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
                .also { if (it !== decoded) decoded.recycle() }
        }

        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // JPG and GIF can't keep partial transparency, and "fit" fills its margins.
        val opaque = o.format == OutputFormat.Jpg
        if (opaque || (!o.locked && o.mode == ResizeMode.Fit)) canvas.drawColor(o.background)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        val sw = source.width.toFloat()
        val sh = source.height.toFloat()
        val mode = if (o.locked) ResizeMode.Stretch else o.mode
        when (mode) {
            ResizeMode.Stretch -> canvas.drawBitmap(source, null, RectF(0f, 0f, tw.toFloat(), th.toFloat()), paint)
            ResizeMode.Crop -> {
                val scale = max(tw / sw, th / sh)
                val cw = tw / scale
                val ch = th / scale
                val left = ((sw - cw) / 2).roundToInt()
                val top = ((sh - ch) / 2).roundToInt()
                canvas.drawBitmap(source, Rect(left, top, left + cw.roundToInt(), top + ch.roundToInt()), RectF(0f, 0f, tw.toFloat(), th.toFloat()), paint)
            }
            ResizeMode.Fit -> {
                val scale = min(tw / sw, th / sh)
                val dw = sw * scale
                val dh = sh * scale
                val left = (tw - dw) / 2
                val top = (th - dh) / 2
                canvas.drawBitmap(source, null, RectF(left, top, left + dw, top + dh), paint)
            }
        }
        source.recycle()

        try {
            val bytes = when (o.format) {
                OutputFormat.Jpg -> ByteArrayOutputStream().use {
                    out.compress(Bitmap.CompressFormat.JPEG, o.quality.coerceIn(1, 100), it)
                    ImageMetadata.jpegWithDpi(it.toByteArray(), o.dpi)
                }
                OutputFormat.Png -> ByteArrayOutputStream().use {
                    out.compress(Bitmap.CompressFormat.PNG, 100, it)
                    ImageMetadata.pngWithDpi(it.toByteArray(), o.dpi)
                }
                OutputFormat.Gif -> GifEncoder.encode(out, o.background)
            }
            return ResizedImage(bytes, tw, th)
        } finally {
            out.recycle()
        }
    }
}
