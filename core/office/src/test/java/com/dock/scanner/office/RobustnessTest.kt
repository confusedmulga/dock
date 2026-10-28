package com.dock.scanner.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Files that are damaged, hand-crafted or just unusual: each must open, or fail with
 * [UnreadableDocumentException], and never hang.
 */
class RobustnessTest {

    private fun temp(ext: String, write: (File) -> Unit): File =
        File.createTempFile("robust", ".$ext").also { it.deleteOnExit(); write(it) }

    private fun zip(ext: String, parts: Map<String, String>): File = temp(ext) { f ->
        ZipOutputStream(f.outputStream()).use { z ->
            parts.forEach { (name, text) ->
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
    }

    private fun TextDocument.paragraphs(): List<Paragraph> = pages.flatten().flatMap { block ->
        when (block) {
            is Paragraph -> listOf(block)
            is Table -> block.rows.flatMap { r -> r.cells.flatMap { c -> c.blocks.filterIsInstance<Paragraph>() } }
        }
    }

    private fun Paragraph.text() = spans.filterIsInstance<TextSpan>().joinToString("") { it.text }

    // region Word 97-2003

    /** A minimal compound file: 512-byte sectors, one sector per stream, no mini stream. */
    private fun compoundFile(streams: List<Pair<String, ByteArray>>): ByteArray {
        val sector = 512
        val out = ByteBuffer.allocate(sector * (3 + streams.size)).order(ByteOrder.LITTLE_ENDIAN)
        byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()).forEach(out::put)
        out.putShort(0x18, 0x3E).putShort(0x1A, 3).putShort(0x1C, 0xFFFE.toShort()).putShort(0x1E, 9).putShort(0x20, 6)
        out.putInt(0x2C, 1) // one FAT sector
        out.putInt(0x30, 1) // directory in sector 1
        out.putInt(0x38, 0) // no mini stream: every stream lives in regular sectors
        out.putInt(0x3C, -2).putInt(0x44, -2)
        out.putInt(0x4C, 0) // the FAT is sector 0
        for (i in 1 until 109) out.putInt(0x4C + i * 4, -1)
        val fat = sector // sector 0 starts after the header
        out.putInt(fat, -3).putInt(fat + 4, -2)
        streams.indices.forEach { out.putInt(fat + (2 + it) * 4, -2) }
        for (i in 2 + streams.size until 128) out.putInt(fat + i * 4, -1)
        val dir = 2 * sector
        fun entry(index: Int, name: String, type: Int, start: Int, size: Int) {
            val at = dir + index * 128
            name.toByteArray(Charsets.UTF_16LE).forEachIndexed { k, b -> out.put(at + k, b) }
            out.putShort(at + 0x40, ((name.length + 1) * 2).toShort())
            out.put(at + 0x42, type.toByte())
            out.putInt(at + 0x44, -1).putInt(at + 0x48, -1).putInt(at + 0x4C, -1)
            out.putInt(at + 0x74, start).putInt(at + 0x78, size)
        }
        entry(0, "Root Entry", 5, -2, 0)
        streams.forEachIndexed { k, (name, bytes) ->
            entry(k + 1, name, 2, 2 + k, bytes.size)
            bytes.forEachIndexed { i, b -> out.put((3 + k) * sector + i, b) }
        }
        return out.array()
    }

    /** A .doc whose text is "Hello"; [clxPrefix] goes in front of its piece table. */
    private fun doc(clxPrefix: ByteArray = ByteArray(0)): File {
        val text = "Hello\r"
        val word = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        word.putShort(0, 0xA5EC.toShort())
        word.putShort(32, 14) // csw
        word.putShort(62, 22) // cslw
        word.putInt(76, text.length) // ccpText
        word.putShort(152, 93) // cbRgFcLcb
        val textAt = 440
        text.forEachIndexed { i, c -> word.put(textAt + i, c.code.toByte()) }
        val pieces = ByteBuffer.allocate(21).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x02).putInt(16).putInt(0).putInt(text.length)
            .putShort(0).putInt((textAt * 2) or 0x40000000).putShort(0)
            .array()
        val clx = clxPrefix + pieces
        word.putInt(418, 0) // fcClx
        word.putInt(422, clx.size) // lcbClx
        val bytes = compoundFile(listOf("WordDocument" to word.array(), "0Table" to clx.copyOf(512)))
        return temp("doc") { it.writeBytes(bytes) }
    }

    @Test
    fun docBuiltHereIsReadable() {
        val opened = OfficeReader.open(doc(), OfficeFormat.DOC)
        assertEquals("Hello", (opened.document as TextDocument).paragraphs().single().text())
    }

    @Test(timeout = 10_000)
    fun docWithANegativePropertySizeFailsInsteadOfHanging() {
        // A Prc whose cbGrpprl is -3: skipping it would land on the same byte forever.
        val file = doc(clxPrefix = byteArrayOf(0x01, 0xFD.toByte(), 0xFF.toByte()))
        assertThrows(UnreadableDocumentException::class.java) { OfficeReader.open(file, OfficeFormat.DOC) }
    }

    // endregion

    // region RTF

    @Test(timeout = 10_000)
    fun rtfWithANegativeBinaryLengthFinishes() {
        // "\bin-7 " is seven characters: jumping back by its length lands on the backslash again.
        val file = temp("rtf") { it.writeText("{\\rtf1\\ansi Before\\bin-7 After\\par}") }
        val doc = OfficeReader.open(file, OfficeFormat.RTF).document as TextDocument
        assertTrue(doc.paragraphs().joinToString { it.text() }.contains("Before"))
    }

    // endregion

    // region Number formats

    @Test
    fun decimalsRoundLikeExcel() {
        Locale.setDefault(Locale.US)
        // These are stored just below the half (2.675 is 2.67499999...), but Excel works to 15 digits.
        assertEquals("2.68", NumberFormat.format(2.675, "0.00"))
        assertEquals("1.01", NumberFormat.format(1.005, "0.00"))
        assertEquals("-2.68", NumberFormat.format(-2.675, "#,##0.00"))
        assertEquals("1,234.568", NumberFormat.format(1234.5675, "#,##0.000"))
        assertEquals("1235", NumberFormat.format(1234.5, "0"))
    }

    @Test(timeout = 5_000)
    fun fractionsWithLongDenominatorsFinish() {
        assertEquals("3/10", NumberFormat.format(0.3, "?/??????????"))
    }

    @Test
    fun datesOutOfRangeShowHashesInsteadOfFailing() {
        assertEquals("########", NumberFormat.format(3_000_000.0, "yyyy-mm-dd"))
        assertEquals("########", NumberFormat.format(1e300, "yyyy-mm-dd"))
    }

    // endregion

    // region Excel

    @Test(timeout = 60_000)
    fun hugeSheetsAreCutShortInsteadOfFailing() {
        val rels = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val file = temp("xlsx") { f ->
            ZipOutputStream(f.outputStream().buffered()).use { z ->
                fun part(name: String, text: String) {
                    z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry()
                }
                part(
                    "xl/workbook.xml",
                    """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="$rels"><sheets><sheet name="Big" sheetId="1" r:id="rId1"/></sheets></workbook>""",
                )
                part(
                    "xl/_rels/workbook.xml.rels",
                    """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$rels/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
                )
                // About 3.5 KB of formatting-only cells per row: past the 64 MB part limit well before the row limit.
                z.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                val w = z.bufferedWriter()
                w.write("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
                val padding = (1..220).joinToString("") { """<c r="B1" s="1"/>""" }
                for (r in 1..XlsxReader.MAX_ROWS + 500) w.write("""<row r="$r"><c r="A$r"><v>$r</v></c>$padding</row>""")
                w.write("</sheetData></worksheet>")
                w.flush()
                z.closeEntry()
            }
        }
        val sheet = (OfficeReader.open(file, OfficeFormat.XLSX).document as Workbook).sheets.single()
        assertTrue(sheet.truncated)
        assertEquals(XlsxReader.MAX_ROWS, sheet.rowCount)
        assertEquals("20000", sheet.rows[XlsxReader.MAX_ROWS - 1]?.get(0)?.text)
    }

    // endregion

    // region Word

    @Test
    fun aPageBreakWordRenderedInsideATableAddsNoBlankLine() {
        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val file = zip(
            "docx",
            mapOf(
                "word/document.xml" to """<w:document xmlns:w="$w"><w:body><w:tbl><w:tblGrid><w:gridCol w:w="2000"/></w:tblGrid>""" +
                    """<w:tr><w:tc><w:p><w:r><w:lastRenderedPageBreak/><w:t>Cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>""",
            ),
        )
        val doc = OfficeReader.open(file, OfficeFormat.DOCX).document as TextDocument
        assertEquals("Cell", doc.paragraphs().single().text())
    }

    // endregion

    @Test
    fun xmlWithADoctypeIsRefused() {
        // Office files never declare one; refusing it rules out entity-expansion ("billion laughs") bombs.
        val xml = """<?xml version="1.0"?><!DOCTYPE a [<!ENTITY x "boom">]><a>&x;</a>"""
        assertThrows(Exception::class.java) { Xml.parse(xml.byteInputStream()) }
    }
}
