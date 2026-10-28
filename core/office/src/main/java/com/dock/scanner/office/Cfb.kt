package com.dock.scanner.office

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The OLE compound file container behind Word 97-2003, Excel 97-2003 and PowerPoint 97-2003
 * files (MS-CFB): a little FAT file system inside one file. Only reading named streams is
 * supported, which is all the readers need.
 */
internal class Cfb(file: File) : Closeable {

    private val data: ByteArray
    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Long
    private val fat: IntArray
    private val miniFat: IntArray
    private val entries: List<Entry>
    private val miniStream: ByteArray

    private class Entry(val name: String, val type: Int, val start: Int, val size: Long)

    init {
        if (file.length() > MAX_FILE) throw UnreadableDocumentException("This document is too large to preview")
        data = file.readBytes()
        if (!hasSignature(data)) throw UnreadableDocumentException("This isn't an Office 97-2003 file")
        val h = ByteBuffer.wrap(data, 0, 512).order(ByteOrder.LITTLE_ENDIAN)
        sectorSize = 1 shl h.getShort(0x1E).toInt()
        miniSectorSize = 1 shl h.getShort(0x20).toInt()
        if (sectorSize !in setOf(512, 4096) || miniSectorSize != 64) throw UnreadableDocumentException("This file is damaged")
        val fatSectors = h.getInt(0x2C)
        val firstDir = h.getInt(0x30)
        miniCutoff = h.getInt(0x38).toLong() and 0xFFFFFFFFL
        val firstMiniFat = h.getInt(0x3C)
        val firstDifat = h.getInt(0x44)

        // The FAT's own sectors are listed in the header, then in a chain of DIFAT sectors.
        val fatSectorList = ArrayList<Int>()
        for (i in 0 until 109) {
            val s = h.getInt(0x4C + i * 4)
            if (s >= 0) fatSectorList += s
        }
        var difat = firstDifat
        var guard = 0
        while (difat >= 0 && guard++ < 10_000) {
            val per = sectorSize / 4 - 1
            for (i in 0 until per) {
                val s = int(sectorOffset(difat) + i * 4)
                if (s >= 0) fatSectorList += s
            }
            difat = int(sectorOffset(difat) + per * 4)
        }
        val fatCount = minOf(fatSectorList.size, fatSectors.coerceAtLeast(fatSectorList.size))
        fat = IntArray(fatCount * sectorSize / 4)
        for ((k, s) in fatSectorList.take(fatCount).withIndex()) {
            val off = sectorOffset(s)
            for (i in 0 until sectorSize / 4) fat[k * sectorSize / 4 + i] = int(off + i * 4)
        }

        val dir = chain(firstDir)
        entries = (0 until dir.size / 128).map { i ->
            val b = ByteBuffer.wrap(dir, i * 128, 128).order(ByteOrder.LITTLE_ENDIAN)
            val nameLen = (b.getShort(i * 128 + 0x40).toInt() and 0xFFFF).coerceIn(0, 64)
            val name = String(dir, i * 128, (nameLen - 2).coerceAtLeast(0), Charsets.UTF_16LE)
            Entry(
                name = name,
                type = dir[i * 128 + 0x42].toInt(),
                start = b.getInt(i * 128 + 0x74),
                size = b.getLong(i * 128 + 0x78).let { if (sectorSize == 512) it and 0xFFFFFFFFL else it },
            )
        }
        val miniFatBytes = if (firstMiniFat >= 0) chain(firstMiniFat) else ByteArray(0)
        miniFat = IntArray(miniFatBytes.size / 4) { ByteBuffer.wrap(miniFatBytes).order(ByteOrder.LITTLE_ENDIAN).getInt(it * 4) }
        val root = entries.firstOrNull { it.type == 5 }
        miniStream = if (root != null && root.start >= 0) chain(root.start, root.size) else ByteArray(0)
    }

    fun has(name: String): Boolean = entries.any { it.type == 2 && it.name.equals(name, ignoreCase = true) }

    /** A stream's bytes, or null if there's no stream of that name. */
    fun stream(name: String): ByteArray? {
        val e = entries.firstOrNull { it.type == 2 && it.name.equals(name, ignoreCase = true) } ?: return null
        if (e.size > MAX_FILE) throw UnreadableDocumentException("This document is too large to preview")
        return if (e.size < miniCutoff) miniChain(e.start, e.size) else chain(e.start, e.size)
    }

    private fun sectorOffset(sector: Int): Int = (sector + 1) * sectorSize

    private fun int(offset: Int): Int {
        if (offset < 0 || offset + 4 > data.size) return END
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun chain(start: Int, size: Long = -1): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var steps = 0
        while (s >= 0 && s < fat.size && steps++ < fat.size) {
            val off = sectorOffset(s)
            if (off + sectorSize > data.size) {
                if (off < data.size) out.write(data, off, data.size - off)
                break
            }
            out.write(data, off, sectorSize)
            if (size >= 0 && out.size() >= size) break
            s = fat[s]
        }
        val bytes = out.toByteArray()
        return if (size in 0 until bytes.size) bytes.copyOf(size.toInt()) else bytes
    }

    private fun miniChain(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var steps = 0
        while (s >= 0 && s < miniFat.size && steps++ < miniFat.size) {
            val off = s * miniSectorSize
            if (off + miniSectorSize > miniStream.size) break
            out.write(miniStream, off, miniSectorSize)
            if (out.size() >= size) break
            s = miniFat[s]
        }
        val bytes = out.toByteArray()
        return if (size < bytes.size) bytes.copyOf(size.toInt()) else bytes
    }

    override fun close() = Unit

    companion object {
        private const val END = -2
        private const val MAX_FILE = 200L * 1024 * 1024
        private val SIGNATURE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

        private fun hasSignature(bytes: ByteArray) = bytes.size >= 512 && SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }

        fun isCompoundFile(file: File): Boolean = runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(8)
                input.read(head) == 8 && SIGNATURE.indices.all { head[it] == SIGNATURE[it] }
            }
        }.getOrDefault(false)
    }
}

/** Little-endian reads from a byte array, bounds-checked. */
internal class Bytes(val data: ByteArray) {
    fun u8(at: Int): Int = if (at in data.indices) data[at].toInt() and 0xFF else 0
    fun u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)
    fun i16(at: Int): Int = u16(at).toShort().toInt()
    fun i32(at: Int): Int = u16(at) or (u16(at + 2) shl 16)
    fun u32(at: Int): Long = i32(at).toLong() and 0xFFFFFFFFL
    fun f64(at: Int): Double = if (at + 8 <= data.size) ByteBuffer.wrap(data, at, 8).order(ByteOrder.LITTLE_ENDIAN).double else 0.0
    val size: Int get() = data.size
}
