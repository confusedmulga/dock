package com.dock.scanner.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import kotlin.math.max

object ImageImport {

    /**
     * Copies a picked image (any format the platform decodes, e.g. HEIC/PNG) into [dest] as a
     * JPEG, so the processing pipeline only ever sees one kind of input.
     *
     * At [ScanQuality.Original] a JPEG is copied byte for byte (the pipeline honours its EXIF
     * orientation), and other formats are converted at full size and top quality. Otherwise the
     * image is made upright and no larger than 4096 pixels.
     */
    fun copyAsUprightJpeg(resolver: ContentResolver, uri: Uri, dest: File, quality: ScanQuality = ScanQuality.Standard) {
        val original = quality == ScanQuality.Original
        if (original && isJpeg(resolver, uri)) {
            resolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                ?: throw IOException("Could not read image")
            return
        }
        val maxSide = if (original) quality.maxSide else 4096
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Unsupported image")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val decoded = resolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Could not decode image")

        val degrees = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        val upright = if (degrees == 0) decoded else {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { decoded.recycle() }
        }
        try {
            dest.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, if (original) 100 else 92, it) }
        } finally {
            upright.recycle()
        }
    }

    /** True when the file starts with a JPEG's SOI marker, whatever its name or reported type. */
    private fun isJpeg(resolver: ContentResolver, uri: Uri): Boolean = runCatching {
        resolver.openInputStream(uri)?.use { val b = ByteArray(3); it.read(b) == 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() }
    }.getOrNull() == true
}
