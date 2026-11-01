package com.dock.scanner.core

import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Minimal PDF writer that embeds JPEG files untouched (DCTDecode).
 *
 * Android's PdfDocument re-encodes bitmaps losslessly and produces very large files;
 * passing the JPEG bytes straight through keeps PDFs small and export fast. A page can hold
 * several images at arbitrary positions (e.g. an ID card's front and back on one A4 sheet),
 * placed as vectors, so nothing is re-rasterized or loses sharpness.
 *
 * @param info extra document information entries (keys are PDF names, e.g. "DockOCR").
 */
class JpegPdfWriter(private val producer: String = "Dock", private val info: Map<String, String> = emptyMap()) {

    data class JpegInfo(val width: Int, val height: Int, val components: Int)

    /** An image placed on a page, in PDF points with the origin at the page's bottom-left. */
    class PlacedImage(val jpeg: File, val x: Float, val y: Float, val width: Float, val height: Float)

    class PageSpec(val widthPt: Float, val heightPt: Float, val images: List<PlacedImage>)

    /** Page size in PDF points (1/72 inch) for an image. Default: short side = A4 width. */
    fun interface PageSizer {
        fun size(info: JpegInfo): Pair<Float, Float>
    }

    /** One full-bleed image per page, each page sized by [sizer]. */
    fun write(jpegs: List<File>, out: OutputStream, sizer: PageSizer = FIT_A4_WIDTH) =
        writePages(jpegs.map { singleImagePage(it, sizer) }, out)

    fun writePages(pages: List<PageSpec>, out: OutputStream) {
        require(pages.isNotEmpty()) { "A PDF needs at least one page" }
        val o = CountingOutputStream(BufferedOutputStream(out, 64 * 1024))
        val offsets = HashMap<Int, Long>()
        var nextObject = 4 // 1 = catalog, 2 = page tree, 3 = info
        val pageObjects = ArrayList<Int>()

        fun begin(n: Int) {
            offsets[n] = o.count
            o.ascii("$n 0 obj\n")
        }

        o.ascii("%PDF-1.4\n")
        o.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

        begin(1); o.ascii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        val extra = info.entries.joinToString("") { (key, value) -> " /$key (${escape(value)})" }
        begin(3); o.ascii("<< /Producer (${escape(producer)}) /CreationDate (D:${timestamp()}Z)$extra >>\nendobj\n")

        for (page in pages) {
            val pageObj = nextObject++
            val contentObj = nextObject++
            val imageObjs = page.images.map { nextObject++ }
            pageObjects += pageObj

            val xObjects = imageObjs.withIndex().joinToString(" ") { (i, obj) -> "/Im$i $obj 0 R" }
            begin(pageObj)
            o.ascii(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${num(page.widthPt)} ${num(page.heightPt)}] " +
                    "/Resources << /XObject << $xObjects >> /ProcSet [/PDF /ImageB /ImageC] >> " +
                    "/Contents $contentObj 0 R >>\nendobj\n",
            )

            // Each image: scale the unit square to its size and move it into place.
            val content = page.images.withIndex().joinToString("") { (i, img) ->
                "q\n${num(img.width)} 0 0 ${num(img.height)} ${num(img.x)} ${num(img.y)} cm\n/Im$i Do\nQ\n"
            }
            begin(contentObj)
            o.ascii("<< /Length ${content.length} >>\nstream\n${content}endstream\nendobj\n")

            page.images.forEachIndexed { i, img ->
                val bytes = img.jpeg.readBytes()
                val info = readJpegInfo(bytes)
                val colorSpace = when (info.components) {
                    1 -> "/DeviceGray"
                    3 -> "/DeviceRGB"
                    4 -> "/DeviceCMYK /Decode [1 0 1 0 1 0 1 0]"
                    else -> throw IllegalArgumentException("Unsupported JPEG with ${info.components} components")
                }
                begin(imageObjs[i])
                o.ascii(
                    "<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} " +
                        "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode /Length ${bytes.size} >>\nstream\n",
                )
                o.write(bytes)
                o.ascii("\nendstream\nendobj\n")
            }
        }

        // The page tree goes last, once every page object number is known.
        begin(2)
        o.ascii("<< /Type /Pages /Kids [${pageObjects.joinToString(" ") { "$it 0 R" }}] /Count ${pages.size} >>\nendobj\n")

        val total = nextObject - 1
        val xref = o.count
        val sb = StringBuilder("xref\n0 ${total + 1}\n0000000000 65535 f \n")
        for (n in 1..total) sb.append(String.format(Locale.US, "%010d 00000 n \n", offsets.getValue(n)))
        sb.append("trailer\n<< /Size ${total + 1} /Root 1 0 R /Info 3 0 R >>\nstartxref\n$xref\n%%EOF\n")
        o.ascii(sb.toString())
        o.flush()
    }

    private fun singleImagePage(jpeg: File, sizer: PageSizer): PageSpec {
        val (w, h) = sizer.size(readJpegInfo(jpeg))
        return PageSpec(w, h, listOf(PlacedImage(jpeg, 0f, 0f, w, h)))
    }

    private fun num(v: Float): String =
        String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        fun ascii(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))
    }

    companion object {
        const val A4_WIDTH_PT = 595.28f

        val FIT_A4_WIDTH = PageSizer { info ->
            val scale = A4_WIDTH_PT / minOf(info.width, info.height)
            info.width * scale to info.height * scale
        }

        /** Reads only the start of the file; the frame header sits before the image data. */
        fun readJpegInfo(file: File): JpegInfo = file.inputStream().use { input ->
            val head = ByteArray(minOf(file.length(), 256L * 1024).toInt())
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            readJpegInfo(head)
        }

        /** Reads dimensions and component count from a JPEG's SOF marker. */
        fun readJpegInfo(bytes: ByteArray): JpegInfo {
            fun u8(i: Int) = bytes[i].toInt() and 0xFF
            fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)

            require(bytes.size > 4 && u8(0) == 0xFF && u8(1) == 0xD8) { "Not a JPEG" }
            var i = 2
            while (i + 3 < bytes.size) {
                if (u8(i) != 0xFF) { i++; continue }
                val marker = u8(i + 1)
                when {
                    marker == 0xFF -> { i++; continue } // fill byte
                    marker == 0x01 || marker in 0xD0..0xD7 -> { i += 2; continue }
                    marker == 0xD9 || marker == 0xDA -> break // EOI / start of scan before any SOF
                }
                val length = u16(i + 2)
                val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                if (isSof) {
                    return JpegInfo(width = u16(i + 7), height = u16(i + 5), components = u8(i + 9))
                }
                i += 2 + length
            }
            throw IllegalArgumentException("JPEG has no frame header")
        }
    }
}
