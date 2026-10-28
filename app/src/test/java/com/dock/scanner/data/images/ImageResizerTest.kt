package com.dock.scanner.data.images

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

class ImageResizerTest {

    @Test
    fun percentKeepsEachPicturesShape() {
        val o = ResizeOptions(width = 50f, height = 50f, unit = SizeUnit.Percent)
        assertEquals(1500 to 1000, ImageResizer.targetSize(3000, 2000, o))
        assertEquals(1200 to 1600, ImageResizer.targetSize(2400, 3200, o))
    }

    @Test
    fun lockedPixelsFollowTheWidth() {
        val o = ResizeOptions(width = 1000f, height = 1f, unit = SizeUnit.Pixels, locked = true)
        assertEquals(1000 to 667, ImageResizer.targetSize(3000, 2000, o))
    }

    @Test
    fun unlockedUsesBothSides() {
        val o = ResizeOptions(width = 800f, height = 600f, unit = SizeUnit.Pixels, locked = false)
        assertEquals(800 to 600, ImageResizer.targetSize(3000, 2000, o))
    }

    @Test
    fun physicalUnitsUseTheResolution() {
        val inches = ResizeOptions(width = 4f, height = 6f, unit = SizeUnit.Inches, locked = false, dpi = 300)
        assertEquals(1200 to 1800, ImageResizer.targetSize(3000, 2000, inches))
        val cm = ResizeOptions(width = 2.54f, height = 2.54f, unit = SizeUnit.Centimeters, locked = false, dpi = 96)
        assertEquals(96 to 96, ImageResizer.targetSize(3000, 2000, cm))
    }

    @Test
    fun sizeIsCapped() {
        val o = ResizeOptions(width = 100_000f, height = 10f, unit = SizeUnit.Pixels, locked = false)
        assertEquals(ImageResizer.MAX_SIDE, ImageResizer.targetSize(10, 10, o).first)
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u32(b: ByteArray, i: Int) = (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()

    @Test
    fun jpegDensityIsSetInAnExistingHeader() {
        val jfif = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16,
            'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0, 1, 1, 0, 0, 1, 0, 1, 0, 0,
            0xFF.toByte(), 0xD9.toByte(),
        )
        val out = ImageMetadata.jpegWithDpi(jfif, 300)
        assertEquals(jfif.size, out.size)
        assertEquals(1, u8(out, 13))
        assertEquals(300, (u8(out, 14) shl 8) or u8(out, 15))
        assertEquals(300, (u8(out, 16) shl 8) or u8(out, 17))
    }

    @Test
    fun jpegWithoutHeaderGetsOne() {
        val bare = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte(), 0, 2, 0xFF.toByte(), 0xD9.toByte())
        val out = ImageMetadata.jpegWithDpi(bare, 150)
        assertEquals(bare.size + 18, out.size)
        assertEquals("JFIF\u0000", String(out, 6, 5, Charsets.ISO_8859_1))
        assertEquals(150, (u8(out, 14) shl 8) or u8(out, 15))
        assertEquals(0xDB, u8(out, 21)) // the original segments follow
    }

    @Test
    fun pngGetsAPhysChunkAfterTheHeader() {
        fun chunk(type: String, data: ByteArray): ByteArray {
            val t = type.toByteArray(Charsets.ISO_8859_1)
            val crc = CRC32().apply { update(t); update(data) }.value
            return byteArrayOf(0, 0, 0, data.size.toByte()) + t + data +
                byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte())
        }
        val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        val png = signature + chunk("IHDR", ByteArray(13)) + chunk("pHYs", ByteArray(9)) + chunk("IEND", ByteArray(0))
        val out = ImageMetadata.pngWithDpi(png, 72)
        val at = 33 // right after IHDR
        assertEquals("pHYs", String(out, at + 4, 4, Charsets.ISO_8859_1))
        assertEquals(2835L, u32(out, at + 8)) // 72 dpi in pixels per meter
        assertEquals(CRC32().apply { update(out, at + 4, 13) }.value, u32(out, at + 17))
        // The old pHYs is gone; IEND is still last.
        assertEquals(png.size, out.size)
        assertEquals("IEND", String(out, out.size - 8, 4, Charsets.ISO_8859_1))
    }

    @Test
    fun gifDecodesBackToThePicture() {
        val w = 300
        val h = 200
        // A gradient with far more than 256 colors, so the palette and dithering are exercised.
        val pixels = IntArray(w * h) { i -> (0xFF shl 24) or ((i % w * 255 / w) shl 16) or ((i / w * 255 / h) shl 8) or 128 }
        val decoded = TestGif.decode(GifEncoder.encode(pixels, w, h, ResizeOptions.WHITE))
        assertEquals(w to h, decoded.width to decoded.height)
        var error = 0L
        for (i in pixels.indices) {
            val a = pixels[i]
            val b = decoded.pixels[i]
            error += kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) + kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        }
        assertTrue("average error ${error / pixels.size}", error / pixels.size < 12)
    }

    @Test
    fun gifKeepsTransparency() {
        val pixels = IntArray(16 * 16) { i -> if (i % 2 == 0) 0 else 0xFFFF0000.toInt() }
        val decoded = TestGif.decode(GifEncoder.encode(pixels, 16, 16, ResizeOptions.WHITE))
        assertEquals(0, decoded.pixels[0] ushr 24)
        assertEquals(0xFFFF0000.toInt(), decoded.pixels[1])
    }

    @Test
    fun gifHandlesLargeAndFlatImages() {
        // Long runs fill the LZW table and force clear codes.
        val pixels = IntArray(640 * 480) { i -> if ((i / 7) % 3 == 0) 0xFF0000FF.toInt() else (0xFF000000.toInt() or (i * 2654435761L).toInt() and 0xFFFFFF) }
        val decoded = TestGif.decode(GifEncoder.encode(pixels, 640, 480, ResizeOptions.WHITE))
        assertEquals(640 * 480, decoded.pixels.size)
        assertEquals(0xFF0000FF.toInt(), decoded.pixels[0])
    }
}

/** A minimal GIF decoder (single frame, global palette), just enough to check the encoder. */
private object TestGif {
    class Image(val width: Int, val height: Int, val pixels: IntArray)

    fun decode(b: ByteArray): Image {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        require(String(b, 0, 6, Charsets.US_ASCII) == "GIF89a")
        val width = u16(6)
        val height = u16(8)
        val packed = u8(10)
        val tableSize = 2 shl (packed and 7)
        val palette = IntArray(tableSize) { k -> (0xFF shl 24) or (u8(13 + k * 3) shl 16) or (u8(14 + k * 3) shl 8) or u8(15 + k * 3) }
        var at = 13 + tableSize * 3
        var transparent = -1
        while (u8(at) == 0x21) {
            if (u8(at + 1) == 0xF9 && u8(at + 3) and 1 == 1) transparent = u8(at + 6)
            at += 2
            while (u8(at) != 0) at += u8(at) + 1
            at++
        }
        require(u8(at) == 0x2C)
        at += 10
        val minCode = u8(at++)
        val data = java.io.ByteArrayOutputStream()
        while (u8(at) != 0) {
            data.write(b, at + 1, u8(at))
            at += u8(at) + 1
        }
        val bytes = data.toByteArray()
        val clear = 1 shl minCode
        val eoi = clear + 1
        val prefixes = IntArray(4096)
        val suffixes = IntArray(4096)
        val firsts = IntArray(4096)
        for (k in 0 until clear) { suffixes[k] = k; firsts[k] = k; prefixes[k] = -1 }
        var size = minCode + 1
        var next = eoi + 1
        var previous = -1
        var bitPos = 0
        val out = IntArray(width * height)
        var count = 0
        val stack = IntArray(4097)
        while (bitPos + size <= bytes.size * 8) {
            var code = 0
            for (k in 0 until size) {
                val bit = (bytes[(bitPos + k) / 8].toInt() shr ((bitPos + k) % 8)) and 1
                code = code or (bit shl k)
            }
            bitPos += size
            if (code == clear) { size = minCode + 1; next = eoi + 1; previous = -1; continue }
            if (code == eoi) break
            val entry = if (code < next) code else previous
            var sp = 0
            var c = entry
            while (c >= 0) { stack[sp++] = suffixes[c]; c = prefixes[c] }
            val first = stack[sp - 1]
            while (sp > 0 && count < out.size) {
                val index = stack[--sp]
                out[count++] = if (index == transparent) 0 else palette[index]
            }
            if (code >= next && count < out.size) out[count++] = if (first == transparent) 0 else palette[first]
            if (previous >= 0 && next < 4096) {
                prefixes[next] = previous
                suffixes[next] = first
                firsts[next] = firsts[previous]
                next++
                if (next == (1 shl size) && size < 12) size++
            }
            previous = code
        }
        require(count == out.size) { "decoded $count of ${out.size} pixels" }
        return Image(width, height, out)
    }
}
