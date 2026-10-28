package com.dock.scanner.data.images

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * A still GIF writer (Android can decode GIFs but not write them). Colors are reduced to a
 * 256-color palette by median cut and dithered (Floyd-Steinberg) so photos keep smooth
 * gradients; fully transparent pixels stay transparent.
 */
object GifEncoder {

    fun encode(bitmap: Bitmap, background: Int): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return encode(pixels, bitmap.width, bitmap.height, background)
    }

    /** [pixels] are ARGB, row by row. Half-transparent pixels are blended onto [background]. */
    fun encode(pixels: IntArray, width: Int, height: Int, background: Int): ByteArray {
        val transparent = pixels.any { (it ushr 24) < 128 }
        // Blend partial transparency: GIF only has fully on or off.
        val rgb = IntArray(pixels.size) { i ->
            val p = pixels[i]
            val a = p ushr 24
            if (a == 255 || a < 128) p and 0xFFFFFF else blend(p, background, a)
        }
        val colorCount = if (transparent) 255 else 256
        val palette = Palette.medianCut(rgb, pixels, colorCount)
        val indexes = Palette.map(rgb, pixels, width, height, palette, transparentIndex = if (transparent) palette.size else -1)

        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(out, width)
        writeShort(out, height)
        out.write(0xF7) // global color table of 256 entries, 8 bits per channel
        out.write(0) // background color index
        out.write(0) // pixel aspect ratio
        for (i in 0 until 256) {
            val c = palette.getOrElse(i) { 0 }
            out.write((c shr 16) and 0xFF)
            out.write((c shr 8) and 0xFF)
            out.write(c and 0xFF)
        }
        if (transparent) {
            // Graphic control extension: the extra palette slot is transparent.
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 1, 0, 0, palette.size.toByte(), 0))
        }
        out.write(0x2C) // image descriptor
        writeShort(out, 0)
        writeShort(out, 0)
        writeShort(out, width)
        writeShort(out, height)
        out.write(0) // no local color table, not interlaced
        Lzw.write(indexes, out)
        out.write(0x3B) // trailer
        return out.toByteArray()
    }

    private fun blend(p: Int, bg: Int, a: Int): Int {
        fun ch(shift: Int) = (((p shr shift) and 0xFF) * a + ((bg shr shift) and 0xFF) * (255 - a)) / 255
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun writeShort(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }
}

internal object Palette {

    /** Up to [count] colors that represent [rgb], chosen by median cut over a sample of it. */
    fun medianCut(rgb: IntArray, argb: IntArray, count: Int): IntArray {
        val step = (rgb.size / 65_536).coerceAtLeast(1)
        val sample = ArrayList<Int>(rgb.size / step + 1)
        var i = 0
        while (i < rgb.size) {
            if ((argb[i] ushr 24) >= 128) sample += rgb[i]
            i += step
        }
        if (sample.isEmpty()) return intArrayOf(0)
        val distinct = sample.distinct()
        if (distinct.size <= count) return distinct.toIntArray()

        var boxes = mutableListOf(sample.toIntArray())
        while (boxes.size < count) {
            // Split the box with the widest color range at its median.
            val (index, channel) = boxes.withIndex()
                .filter { it.value.size > 1 }
                .map { (k, box) -> Triple(k, widest(box), range(box, widest(box))) }
                .maxByOrNull { it.third }?.let { it.first to it.second } ?: break
            val box = boxes[index]
            val sorted = box.sortedBy { (it shr channel) and 0xFF }
            val mid = sorted.size / 2
            boxes[index] = sorted.subList(0, mid).toIntArray()
            boxes.add(sorted.subList(mid, sorted.size).toIntArray())
            boxes = boxes.filter { it.isNotEmpty() }.toMutableList()
        }
        return IntArray(boxes.size) { average(boxes[it]) }
    }

    private fun widest(box: IntArray): Int = listOf(16, 8, 0).maxByOrNull { range(box, it) }!!

    private fun range(box: IntArray, shift: Int): Int {
        var lo = 255
        var hi = 0
        for (c in box) {
            val v = (c shr shift) and 0xFF
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        return hi - lo
    }

    private fun average(box: IntArray): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        for (c in box) {
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
        }
        val n = box.size
        return ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /** Palette indexes for every pixel, with Floyd-Steinberg error diffusion. */
    fun map(rgb: IntArray, argb: IntArray, width: Int, height: Int, palette: IntArray, transparentIndex: Int): ByteArray {
        // Nearest palette entry for each 5-bit-per-channel color, found once.
        val lookup = IntArray(32 * 32 * 32) { -1 }
        fun nearest(r: Int, g: Int, b: Int): Int {
            val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            val cached = lookup[key]
            if (cached >= 0) return cached
            var best = 0
            var bestDistance = Int.MAX_VALUE
            for (k in palette.indices) {
                val c = palette[k]
                val dr = ((c shr 16) and 0xFF) - r
                val dg = ((c shr 8) and 0xFF) - g
                val db = (c and 0xFF) - b
                val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
                if (d < bestDistance) {
                    bestDistance = d
                    best = k
                }
            }
            lookup[key] = best
            return best
        }

        val out = ByteArray(rgb.size)
        // Error carried to the current and next row, per channel.
        var current = IntArray((width + 2) * 3)
        var next = IntArray((width + 2) * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (transparentIndex >= 0 && (argb[i] ushr 24) < 128) {
                    out[i] = transparentIndex.toByte()
                    continue
                }
                val c = rgb[i]
                val e = (x + 1) * 3
                val r = (((c shr 16) and 0xFF) + current[e] / 16).coerceIn(0, 255)
                val g = (((c shr 8) and 0xFF) + current[e + 1] / 16).coerceIn(0, 255)
                val b = ((c and 0xFF) + current[e + 2] / 16).coerceIn(0, 255)
                val k = nearest(r, g, b)
                out[i] = k.toByte()
                val p = palette[k]
                val er = r - ((p shr 16) and 0xFF)
                val eg = g - ((p shr 8) and 0xFF)
                val eb = b - (p and 0xFF)
                for ((dx, weight, row) in DIFFUSION) {
                    val t = e + dx * 3
                    val target = if (row == 0) current else next
                    target[t] += er * weight
                    target[t + 1] += eg * weight
                    target[t + 2] += eb * weight
                }
            }
            val swap = current
            current = next
            next = swap
            next.fill(0)
        }
        return out
    }

    /** Floyd-Steinberg: (dx, weight in sixteenths, 0 = this row / 1 = next). */
    private val DIFFUSION = listOf(Triple(1, 7, 0), Triple(-1, 3, 1), Triple(0, 5, 1), Triple(1, 1, 1))
}

/** GIF's variable-length LZW, 8-bit pixels. */
internal object Lzw {
    private const val MAX_CODES = 4096

    fun write(indexes: ByteArray, out: ByteArrayOutputStream) {
        val minCodeSize = 8
        out.write(minCodeSize)
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        val blocks = BlockWriter(out)
        var codeSize = minCodeSize + 1
        var nextCode = eoi + 1
        // Dictionary: (prefix code << 8 | byte) -> code.
        val table = HashMap<Int, Int>(MAX_CODES * 2)

        blocks.bits(clear, codeSize)
        if (indexes.isEmpty()) {
            blocks.bits(eoi, codeSize)
            blocks.finish()
            return
        }
        var prefix = indexes[0].toInt() and 0xFF
        for (i in 1 until indexes.size) {
            val k = indexes[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            val existing = table[key]
            if (existing != null) {
                prefix = existing
                continue
            }
            blocks.bits(prefix, codeSize)
            if (nextCode < MAX_CODES) {
                table[key] = nextCode
                if (nextCode == (1 shl codeSize) && codeSize < 12) codeSize++
                nextCode++
            } else {
                blocks.bits(clear, codeSize)
                table.clear()
                codeSize = minCodeSize + 1
                nextCode = eoi + 1
            }
            prefix = k
        }
        blocks.bits(prefix, codeSize)
        blocks.bits(eoi, codeSize)
        blocks.finish()
    }

    /** Packs codes into bytes, least significant bit first, in sub-blocks of up to 255 bytes. */
    private class BlockWriter(private val out: ByteArrayOutputStream) {
        private val block = ByteArray(255)
        private var size = 0
        private var buffer = 0
        private var count = 0

        fun bits(code: Int, width: Int) {
            buffer = buffer or (code shl count)
            count += width
            while (count >= 8) {
                byte(buffer and 0xFF)
                buffer = buffer ushr 8
                count -= 8
            }
        }

        private fun byte(b: Int) {
            block[size++] = b.toByte()
            if (size == 255) flush()
        }

        private fun flush() {
            if (size == 0) return
            out.write(size)
            out.write(block, 0, size)
            size = 0
        }

        fun finish() {
            if (count > 0) byte(buffer and 0xFF)
            buffer = 0
            count = 0
            flush()
            out.write(0) // block terminator
        }
    }
}
