package com.dock.scanner.office

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/** The file isn't a document Dock can read, or it's damaged. */
class UnreadableDocumentException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A zip-based document (Office Open XML or OpenDocument). Parts are read on demand, each
 * capped in size so a malicious "zip bomb" can't exhaust memory.
 */
internal class ZipPackage(file: File) : AutoCloseable {

    private val zip = try {
        ZipFile(file)
    } catch (e: IOException) {
        throw UnreadableDocumentException("Not a valid document", e)
    }

    private val names: Set<String> = zip.entries().asSequence().map { it.name }.toSet()

    fun has(path: String) = path in names

    fun open(path: String, limit: Long = PART_LIMIT): InputStream? {
        val entry = zip.getEntry(path) ?: return null
        return LimitedInputStream(zip.getInputStream(entry), limit)
    }

    fun bytes(path: String, limit: Long = PART_LIMIT): ByteArray? = open(path, limit)?.use { it.readBytes() }

    fun xml(path: String): XNode? = open(path)?.use { Xml.parse(it) }

    /** Relationships of [part], by id, as absolute part paths (or URLs for external targets). */
    fun relationships(part: String): Map<String, Relationship> {
        val dir = part.substringBeforeLast('/', "")
        val relsPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + part.substringAfterLast('/') + ".rels"
        val root = xml(relsPath) ?: return emptyMap()
        return root.children("Relationship").mapNotNull { rel ->
            val id = rel.attr("Id") ?: return@mapNotNull null
            val target = rel.attr("Target") ?: return@mapNotNull null
            val external = rel.attr("TargetMode") == "External"
            id to Relationship(if (external) target else resolve(dir, target), rel.attr("Type").orEmpty(), external)
        }.toMap()
    }

    override fun close() = zip.close()

    /** An [ImageStore] over this package's pictures. Must not outlive the package. */
    fun images(): ImageStore = object : ImageStore {
        override fun bytes(ref: String): ByteArray? {
            val ext = ref.substringAfterLast('.', "").lowercase()
            // EMF/WMF (old clip art) and TIFF can't be decoded on Android.
            if (ext in setOf("emf", "wmf", "tif", "tiff", "svg")) return null
            return runCatching { this@ZipPackage.bytes(ref, IMAGE_LIMIT) }.getOrNull()
        }
    }

    companion object {
        const val PART_LIMIT = 64L * 1024 * 1024
        const val IMAGE_LIMIT = 48L * 1024 * 1024

        /** Resolves a relative target like "../media/image1.png" against [dir]. */
        fun resolve(dir: String, target: String): String {
            if (target.startsWith("/")) return target.removePrefix("/")
            val parts = ArrayList<String>()
            if (dir.isNotEmpty()) parts += dir.split('/')
            for (seg in target.split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                    else -> parts += seg
                }
            }
            return parts.joinToString("/")
        }
    }
}

internal data class Relationship(val target: String, val type: String, val external: Boolean)

/** Fails once more than [limit] bytes have been read. */
internal class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) check(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) check(n.toLong())
        return n
    }

    private fun check(n: Long) {
        count += n
        if (count > limit) throw UnreadableDocumentException("Part of this document is too large to preview")
    }
}
