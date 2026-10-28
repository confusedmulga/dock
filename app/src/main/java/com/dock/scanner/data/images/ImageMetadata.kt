package com.dock.scanner.data.images

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import kotlin.math.roundToLong

/** Writes a picture's resolution into its file, so print sizes in centimeters or inches come out right. */
object ImageMetadata {

    /**
     * Sets the JFIF density of a JPEG to [dpi], adding a JFIF header if the encoder didn't
     * write one.
     */
    fun jpegWithDpi(jpeg: ByteArray, dpi: Int): ByteArray {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return jpeg
        val d = dpi.coerceIn(1, 65_535)
        // APP0 "JFIF\0": units at +11 from the marker, then X and Y density.
        if (jpeg.size > 20 && jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE0.toByte() &&
            String(jpeg, 6, 5, Charsets.ISO_8859_1) == "JFIF\u0000"
        ) {
            val copy = jpeg.copyOf()
            copy[13] = 1 // dots per inch
            copy[14] = (d shr 8).toByte()
            copy[15] = d.toByte()
            copy[16] = (d shr 8).toByte()
            copy[17] = d.toByte()
            return copy
        }
        val app0 = byteArrayOf(
            0xFF.toByte(), 0xE0.toByte(), 0, 16,
            'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0,
            1, 1, // version 1.1
            1, (d shr 8).toByte(), d.toByte(), (d shr 8).toByte(), d.toByte(),
            0, 0, // no thumbnail
        )
        return ByteArrayOutputStream(jpeg.size + app0.size).apply {
            write(jpeg, 0, 2)
            write(app0)
            write(jpeg, 2, jpeg.size - 2)
        }.toByteArray()
    }

    /** Puts a pHYs chunk (pixels per meter) right after the PNG's header, replacing any existing one. */
    fun pngWithDpi(png: ByteArray, dpi: Int): ByteArray {
        if (png.size < 33 || String(png, 12, 4, Charsets.ISO_8859_1) != "IHDR") return png
        val perMeter = (dpi / 0.0254).roundToLong().coerceIn(1, 0xFFFFFFFFL)
        val data = ByteArray(9).also {
            putInt(it, 0, perMeter)
            putInt(it, 4, perMeter)
            it[8] = 1 // meters
        }
        val chunk = chunk("pHYs", data)
        val out = ByteArrayOutputStream(png.size + chunk.size)
        val ihdrEnd = 8 + 8 + 13 + 4 // signature, IHDR length+type, data, CRC
        out.write(png, 0, ihdrEnd)
        out.write(chunk)
        var at = ihdrEnd
        while (at + 8 <= png.size) {
            val length = readInt(png, at).toInt()
            val type = String(png, at + 4, 4, Charsets.ISO_8859_1)
            val end = (at + 12 + length).coerceAtMost(png.size)
            if (type != "pHYs") out.write(png, at, end - at)
            at = end
        }
        return out.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }.value
        return ByteArray(12 + data.size).also {
            putInt(it, 0, data.size.toLong())
            typeBytes.copyInto(it, 4)
            data.copyInto(it, 8)
            putInt(it, 8 + data.size, crc)
        }
    }

    private fun putInt(b: ByteArray, at: Int, v: Long) {
        b[at] = (v shr 24).toByte()
        b[at + 1] = (v shr 16).toByte()
        b[at + 2] = (v shr 8).toByte()
        b[at + 3] = v.toByte()
    }

    private fun readInt(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
}
