package com.dock.scanner.office

import java.nio.charset.Charset

/**
 * Reads Excel 97-2003 workbooks (.xls, BIFF8): cell values with their number formats, bold
 * and italic fonts, column widths, row heights and merged cells.
 */
internal class XlsReader(private val cfb: Cfb) {

    private class Font(val size: Float, val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean, val color: Int?)
    private class Xf(val font: Int, val format: Int, val align: CellAlign, val wrap: Boolean)

    private lateinit var stream: Bytes
    private val strings = ArrayList<String>()
    private val formats = HashMap<Int, String>(NumberFormat.BUILTIN)
    private val fonts = ArrayList<Font>()
    private val xfs = ArrayList<Xf>()
    private var date1904 = false
    private var codepage: Charset = Charset.forName("windows-1252")

    fun read(): Workbook {
        val data = cfb.stream("Workbook") ?: cfb.stream("Book") ?: throw UnreadableDocumentException("This isn't an Excel workbook")
        stream = Bytes(data)
        if (stream.u16(0) != BOF || stream.u16(4) < 0x0600) throw UnreadableDocumentException("This Excel file is too old to preview")

        // Workbook globals: everything up to the first EOF.
        val sheets = ArrayList<Pair<String, Int>>()
        var at = 0
        while (at + 4 <= stream.size) {
            val type = stream.u16(at)
            val len = stream.u16(at + 2)
            val body = at + 4
            when (type) {
                FILEPASS -> throw UnreadableDocumentException("This workbook is password-protected")
                DATEMODE -> date1904 = stream.u16(body) == 1
                CODEPAGE -> stream.u16(body).takeIf { it == 874 || it in 1250..1258 }?.let { runCatching { codepage = Charset.forName("windows-$it") } }
                FORMAT -> formats[stream.u16(body)] = xlString(body + 2, len - 2, lengthBytes = 2)
                FONT -> fonts += Font(
                    size = stream.u16(body) / 20f,
                    bold = stream.u16(body + 6) >= 700,
                    italic = stream.u16(body + 2) and 0x02 != 0,
                    underline = stream.u8(body + 10) != 0,
                    strike = stream.u16(body + 2) and 0x08 != 0,
                    color = stream.u16(body + 4).let { if (it in 8..63) Colors.INDEXED[it] else null }?.takeIf { it != Colors.BLACK },
                )
                XF -> {
                    val alignByte = stream.u8(body + 6)
                    xfs += Xf(
                        font = stream.u16(body),
                        format = stream.u16(body + 2),
                        align = when (alignByte and 0x07) {
                            1 -> CellAlign.Left
                            2, 6 -> CellAlign.Center
                            3 -> CellAlign.Right
                            else -> CellAlign.General
                        },
                        wrap = alignByte and 0x08 != 0,
                    )
                }
                BOUNDSHEET -> {
                    val pos = stream.i32(body)
                    val hidden = stream.u8(body + 4) and 0x03 != 0
                    val kind = stream.u8(body + 5)
                    val name = xlString(body + 6, len - 6, lengthBytes = 1)
                    if (!hidden && kind == 0) sheets += name to pos
                }
                SST -> readSst(at)
                EOF -> break
            }
            at = body + len
        }
        val result = sheets.mapNotNull { (name, pos) -> runCatching { readSheet(name, pos) }.getOrNull() }
        if (result.isEmpty()) throw UnreadableDocumentException("This workbook has no sheets to show")
        return Workbook(result)
    }

    /** Reads an XLUnicodeString: length, a flags byte (1 = UTF-16), then at most [max] bytes in all. */
    private fun xlString(at: Int, max: Int, lengthBytes: Int): String {
        val count = if (lengthBytes == 2) stream.u16(at) else stream.u8(at)
        val flags = stream.u8(at + lengthBytes)
        val start = at + lengthBytes + 1
        val wide = flags and 0x01 != 0
        val bytes = (if (wide) count * 2 else count).coerceAtMost(max - lengthBytes - 1)
        val len = bytes.coerceAtMost(stream.size - start).coerceAtLeast(0)
        return if (wide) String(stream.data, start, len, Charsets.UTF_16LE) else String(stream.data, start, len, codepage)
    }

    /**
     * The shared string table. Strings can be split across CONTINUE records, and where the
     * split falls inside a string's characters, the next record restates whether they're
     * one or two bytes wide.
     */
    private fun readSst(recordAt: Int) {
        val chunks = ArrayList<IntRange>() // byte ranges of the SST record and its CONTINUEs
        var at = recordAt
        val firstLen = stream.u16(at + 2)
        chunks += (at + 4) until (at + 4 + firstLen)
        at += 4 + firstLen
        while (at + 4 <= stream.size && stream.u16(at) == CONTINUE) {
            val len = stream.u16(at + 2)
            chunks += (at + 4) until (at + 4 + len)
            at += 4 + len
        }
        val reader = ChunkReader(chunks)
        reader.i32() // total number of uses
        val unique = reader.i32()
        repeat(unique.coerceAtMost(2_000_000)) {
            if (reader.done) return
            val count = reader.u16()
            val flags = reader.u8()
            var wide = flags and 0x01 != 0
            val runs = if (flags and 0x08 != 0) reader.u16() else 0
            val ext = if (flags and 0x04 != 0) reader.i32() else 0
            val sb = StringBuilder(count)
            var left = count
            while (left > 0 && !reader.done) {
                if (reader.atChunkStart() && sb.isNotEmpty()) wide = reader.u8() and 0x01 != 0
                val available = reader.remainingInChunk() / if (wide) 2 else 1
                val take = minOf(left, available.coerceAtLeast(1))
                for (k in 0 until take) {
                    sb.append(if (wide) reader.u16().toChar() else String(byteArrayOf(reader.u8().toByte()), codepage))
                }
                left -= take
            }
            reader.skip(runs * 4 + ext)
            strings += sb.toString()
        }
    }

    /** Reads sequentially across the SST's record chunks. */
    private inner class ChunkReader(private val chunks: List<IntRange>) {
        private var chunk = 0
        private var pos = chunks.firstOrNull()?.first ?: 0

        val done get() = chunk >= chunks.size

        /** At the start of a CONTINUE record (not the SST record itself). */
        fun atChunkStart(): Boolean {
            normalize()
            return chunk in 1 until chunks.size && pos == chunks[chunk].first
        }

        fun remainingInChunk(): Int {
            normalize()
            return if (done) 0 else chunks[chunk].last + 1 - pos
        }

        private fun normalize() {
            while (chunk < chunks.size && pos > chunks[chunk].last) {
                chunk++
                if (chunk < chunks.size) pos = chunks[chunk].first
            }
        }

        fun u8(): Int {
            normalize()
            if (done) return 0
            return stream.u8(pos++)
        }

        fun u16(): Int = u8() or (u8() shl 8)
        fun i32(): Int = u16() or (u16() shl 16)

        fun skip(n: Int) {
            repeat(n) { u8() }
        }
    }

    private fun readSheet(name: String, start: Int): Sheet {
        val rows = HashMap<Int, HashMap<Int, Cell>>()
        val widths = HashMap<Int, Float>()
        val heights = HashMap<Int, Float>()
        val hiddenCols = HashSet<Int>()
        val merges = ArrayList<CellRange>()
        var defaultWidth = 64f
        var maxRow = -1
        var maxCol = -1
        var truncated = false
        var pendingFormula: Pair<Int, Int>? = null
        var pendingXf = 0

        fun put(row: Int, col: Int, xf: Int, text: String, number: Boolean) {
            if (row >= XlsxReader.MAX_ROWS || col >= XlsxReader.MAX_COLUMNS) {
                truncated = true
                return
            }
            rows.getOrPut(row) { HashMap() }[col] = Cell(text, style(xf), number)
            maxRow = maxOf(maxRow, row)
            maxCol = maxOf(maxCol, col)
        }

        fun number(row: Int, col: Int, xf: Int, value: Double) {
            val format = formats[xfs.getOrNull(xf)?.format ?: 0] ?: "General"
            put(row, col, xf, NumberFormat.format(value, format, date1904), number = true)
        }

        var at = start
        if (stream.u16(at) != BOF) throw UnreadableDocumentException("Missing sheet")
        at += 4 + stream.u16(at + 2)
        while (at + 4 <= stream.size) {
            val type = stream.u16(at)
            val len = stream.u16(at + 2)
            val b = at + 4
            when (type) {
                EOF -> break
                DEFCOLWIDTH -> defaultWidth = XlsxReader.charsToPx(stream.u16(b) + 0.71f)
                COLINFO -> {
                    val first = stream.u16(b)
                    val last = stream.u16(b + 2).coerceAtMost(XlsxReader.MAX_COLUMNS)
                    val w = XlsxReader.charsToPx(stream.u16(b + 4) / 256f)
                    val hidden = stream.u16(b + 8) and 0x01 != 0
                    for (c in first..last) {
                        widths[c] = w
                        if (hidden) hiddenCols += c
                    }
                }
                ROW -> {
                    val h = stream.u16(b + 6)
                    if (h and 0x8000 == 0) heights[stream.u16(b)] = (h and 0x7FFF) / 20f * 4 / 3
                }
                LABELSST -> {
                    val text = strings.getOrNull(stream.i32(b + 6)).orEmpty()
                    put(stream.u16(b), stream.u16(b + 2), stream.u16(b + 4), text, number = false)
                }
                LABEL, RSTRING -> put(stream.u16(b), stream.u16(b + 2), stream.u16(b + 4), xlString(b + 6, len - 6, lengthBytes = 2), number = false)
                NUMBER -> number(stream.u16(b), stream.u16(b + 2), stream.u16(b + 4), stream.f64(b + 6))
                RK -> number(stream.u16(b), stream.u16(b + 2), stream.u16(b + 4), rk(stream.i32(b + 6)))
                MULRK -> {
                    val row = stream.u16(b)
                    val first = stream.u16(b + 2)
                    val count = (len - 6) / 6
                    for (k in 0 until count) {
                        val off = b + 4 + k * 6
                        number(row, first + k, stream.u16(off), rk(stream.i32(off + 2)))
                    }
                }
                BOOLERR -> {
                    val value = stream.u8(b + 6)
                    val text = if (stream.u8(b + 7) == 0) (if (value != 0) "TRUE" else "FALSE") else ERRORS[value] ?: "#ERR"
                    put(stream.u16(b), stream.u16(b + 2), stream.u16(b + 4), text, number = false)
                }
                FORMULA -> {
                    val row = stream.u16(b)
                    val col = stream.u16(b + 2)
                    val xf = stream.u16(b + 4)
                    if (stream.u16(b + 12) == 0xFFFF) {
                        when (stream.u8(b + 6)) {
                            0 -> { pendingFormula = row to col; pendingXf = xf } // the text follows in a STRING record
                            1 -> put(row, col, xf, if (stream.u8(b + 8) != 0) "TRUE" else "FALSE", number = false)
                            2 -> put(row, col, xf, ERRORS[stream.u8(b + 8)] ?: "#ERR", number = false)
                        }
                    } else {
                        number(row, col, xf, stream.f64(b + 6))
                    }
                }
                STRING -> pendingFormula?.let { (row, col) ->
                    put(row, col, pendingXf, xlString(b, len, lengthBytes = 2), number = false)
                    pendingFormula = null
                }
                MERGECELLS -> {
                    val count = stream.u16(b)
                    for (k in 0 until count) {
                        val off = b + 2 + k * 8
                        merges += CellRange(stream.u16(off), stream.u16(off + 4), stream.u16(off + 2), stream.u16(off + 6))
                    }
                }
            }
            at = b + len
        }
        for (m in merges) {
            maxRow = maxOf(maxRow, m.lastRow.coerceAtMost(XlsxReader.MAX_ROWS - 1))
            maxCol = maxOf(maxCol, m.lastColumn.coerceAtMost(XlsxReader.MAX_COLUMNS - 1))
        }
        return Sheet(name, maxRow + 1, maxCol + 1, rows, widths, defaultWidth, heights, 20f, merges, hiddenCols, emptySet(), truncated)
    }

    private fun style(xfIndex: Int): CellStyle {
        val xf = xfs.getOrNull(xfIndex) ?: return CellStyle.Default
        // Font index 4 doesn't exist in BIFF, so indexes above it are one off.
        val font = fonts.getOrNull(if (xf.font >= 4) xf.font - 1 else xf.font)
        return CellStyle(
            bold = font?.bold ?: false,
            italic = font?.italic ?: false,
            underline = font?.underline ?: false,
            strike = font?.strike ?: false,
            size = font?.size ?: 10f,
            color = font?.color,
            align = xf.align,
            wrap = xf.wrap,
        )
    }

    companion object {
        private const val BOF = 0x0809
        private const val EOF = 0x000A
        private const val FILEPASS = 0x002F
        private const val DATEMODE = 0x0022
        private const val CODEPAGE = 0x0042
        private const val FORMAT = 0x041E
        private const val FONT = 0x0031
        private const val XF = 0x00E0
        private const val BOUNDSHEET = 0x0085
        private const val SST = 0x00FC
        private const val CONTINUE = 0x003C
        private const val DEFCOLWIDTH = 0x0055
        private const val COLINFO = 0x007D
        private const val ROW = 0x0208
        private const val LABELSST = 0x00FD
        private const val LABEL = 0x0204
        private const val RSTRING = 0x00D6
        private const val NUMBER = 0x0203
        private const val RK = 0x027E
        private const val MULRK = 0x00BD
        private const val BOOLERR = 0x0205
        private const val FORMULA = 0x0006
        private const val STRING = 0x0207
        private const val MERGECELLS = 0x00E5

        private val ERRORS = mapOf(0x00 to "#NULL!", 0x07 to "#DIV/0!", 0x0F to "#VALUE!", 0x17 to "#REF!", 0x1D to "#NAME?", 0x24 to "#NUM!", 0x2A to "#N/A")

        /** A compressed number: a 30-bit integer or the top of a double, optionally divided by 100. */
        fun rk(value: Int): Double {
            val v = if (value and 0x02 != 0) (value shr 2).toDouble()
            else java.lang.Double.longBitsToDouble((value.toLong() and 0xFFFFFFFCL) shl 32)
            return if (value and 0x01 != 0) v / 100 else v
        }
    }
}
