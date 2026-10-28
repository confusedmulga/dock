package com.dock.scanner.office

import java.nio.charset.Charset

/**
 * Reads the text of Word 97-2003 documents (.doc) from the piece table (MS-DOC): paragraphs,
 * line and page breaks, tables as tab-separated rows, and field results. Character
 * formatting lives in a separate, much more involved structure and isn't shown.
 */
internal class DocReader(private val cfb: Cfb) {

    fun read(): TextDocument {
        val word = Bytes(cfb.stream("WordDocument") ?: throw UnreadableDocumentException("This isn't a Word document"))
        if (word.u16(0) != 0xA5EC) throw UnreadableDocumentException("This Word file is too old to preview")
        val flags = word.u16(0x0A)
        if (flags and 0x0100 != 0) throw UnreadableDocumentException("This document is password-protected")
        val table = Bytes(cfb.stream(if (flags and 0x0200 != 0) "1Table" else "0Table") ?: throw UnreadableDocumentException("This Word file is damaged"))

        // FIB: a fixed base, then arrays whose lengths are stored in front of them.
        val csw = word.u16(32)
        val rgLw = 34 + csw * 2 + 2
        val cslw = word.u16(34 + csw * 2)
        val ccpText = word.i32(rgLw + 3 * 4)
        val rgFcLcb = rgLw + cslw * 4 + 2
        val fcClx = word.i32(rgFcLcb + 33 * 8)
        val lcbClx = word.i32(rgFcLcb + 33 * 8 + 4)

        val text = pieceText(word, table, fcClx, lcbClx, ccpText)
        return TextDocument(612f, 792f, Margins(72f, 72f, 72f, 72f), paginate(text))
    }

    private fun pieceText(word: Bytes, table: Bytes, fcClx: Int, lcbClx: Int, ccpText: Int): String {
        // Skip property modifiers (0x01) to the piece table (0x02).
        var at = fcClx
        val end = fcClx + lcbClx
        while (at < end && table.u8(at) == 0x01) at += 3 + table.i16(at + 1)
        if (table.u8(at) != 0x02) throw UnreadableDocumentException("This Word file is damaged")
        val lcb = table.i32(at + 1)
        val plc = at + 5
        val pieces = (lcb - 4) / 12
        val cp1252 = Charset.forName("windows-1252")
        val sb = StringBuilder()
        for (i in 0 until pieces) {
            val cpStart = table.i32(plc + i * 4)
            val cpEnd = table.i32(plc + (i + 1) * 4)
            if (cpStart >= ccpText) break
            val count = minOf(cpEnd, ccpText) - cpStart
            if (count <= 0) continue
            val pcd = plc + (pieces + 1) * 4 + i * 8
            val fcRaw = table.i32(pcd + 2)
            val compressed = fcRaw and 0x40000000 != 0
            val fc = fcRaw and 0x3FFFFFFF
            if (compressed) {
                val start = fc / 2
                val len = count.coerceAtMost(word.size - start).coerceAtLeast(0)
                sb.append(String(word.data, start, len, cp1252))
            } else {
                val len = (count * 2).coerceAtMost(word.size - fc).coerceAtLeast(0)
                sb.append(String(word.data, fc, len, Charsets.UTF_16LE))
            }
        }
        return sb.toString()
    }

    /** Turns Word's control characters into paragraphs and pages. */
    private fun paginate(raw: String): List<List<Block>> {
        val style = TextStyle(size = 11f)
        val pages = mutableListOf(mutableListOf<Block>())
        val line = StringBuilder()
        // Fields are "\u0013 code \u0014 result \u0015": show only the result.
        val fields = ArrayDeque<Boolean>() // true while inside a field's code
        fun paragraph() {
            pages.last() += Paragraph(listOf(TextSpan(line.toString(), style)), spaceAfter = 6f)
            line.clear()
        }
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when (c) {
                '\u0013' -> fields.addLast(true)
                '\u0014' -> if (fields.isNotEmpty()) { fields.removeLast(); fields.addLast(false) }
                '\u0015' -> fields.removeLastOrNull()
                else -> if (fields.none { it }) when (c) {
                    '\r' -> paragraph()
                    '\u000B' -> line.append('\n')
                    '\u000C' -> {
                        if (line.isNotEmpty()) paragraph()
                        if (pages.last().isNotEmpty()) pages += mutableListOf<Block>()
                    }
                    '\u0007' -> {
                        // Two cell marks in a row end a table row.
                        if (i + 1 < raw.length && raw[i + 1] == '\u0007') {
                            paragraph()
                            i++
                        } else {
                            line.append('\t')
                        }
                    }
                    '\u001E' -> line.append('‑')
                    '\u0001', '\u0008', '\u0005', '\u0002', '\u001F' -> Unit
                    else -> if (c >= ' ' || c == '\t') line.append(c)
                }
            }
            i++
        }
        if (line.isNotEmpty()) paragraph()
        return pages.filter { it.isNotEmpty() }.ifEmpty { listOf(emptyList()) }
    }
}
