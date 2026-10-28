package com.dock.scanner.office

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Decoding for text-based formats: honours a BOM, tries UTF-8, falls back to Windows-1252. */
internal object TextDecoding {
    const val LIMIT = 16L * 1024 * 1024

    fun read(file: File): Pair<String, Boolean> {
        val truncated = file.length() > LIMIT
        val bytes = file.inputStream().use { input -> LimitedRead.readUpTo(input, LIMIT.toInt()) }
        return decode(bytes) to truncated
    }

    fun decode(bytes: ByteArray): String {
        fun b(i: Int) = bytes.getOrNull(i)?.toInt()?.and(0xFF)
        return when {
            b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            b(0) == 0xFF && b(1) == 0xFE -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            b(0) == 0xFE && b(1) == 0xFF -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: CharacterCodingException) {
                String(bytes, Charset.forName("windows-1252"))
            }
        }
    }
}

internal object LimitedRead {
    fun readUpTo(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

/** Plain text, one paragraph per line, in pages of [LINES_PER_PAGE] lines. */
internal object PlainText {
    private const val LINES_PER_PAGE = 54

    fun read(file: File): TextDocument {
        val (text, truncated) = TextDecoding.read(file)
        val style = TextStyle(size = 11f, font = FontKind.Sans)
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()
        if (lines.lastOrNull()?.isEmpty() == true && lines.size > 1) lines.removeAt(lines.lastIndex)
        if (truncated) lines += "…"
        val pages = lines.chunked(LINES_PER_PAGE).map { chunk -> chunk.map { Paragraph(listOf(TextSpan(it.trimEnd(), style))) } }
        return TextDocument(612f, 792f, Margins(54f, 54f, 54f, 54f), pages.ifEmpty { listOf(emptyList()) })
    }
}

/** CSV and TSV files, as a one-sheet workbook. */
internal object Delimited {

    fun read(file: File, tabs: Boolean): Workbook {
        val (text, truncatedFile) = TextDecoding.read(file)
        val delimiter = if (tabs) '\t' else guessDelimiter(text)
        val rows = HashMap<Int, Map<Int, Cell>>()
        val widths = HashMap<Int, Float>()
        var columns = 0
        var count = 0
        var truncated = truncatedFile
        for (fields in parse(text, delimiter)) {
            if (count >= XlsxReader.MAX_ROWS) {
                truncated = true
                break
            }
            val cells = HashMap<Int, Cell>()
            fields.take(XlsxReader.MAX_COLUMNS).forEachIndexed { c, value ->
                if (value.isNotEmpty()) {
                    val number = value.trim().replace(",", "").toDoubleOrNull() != null
                    cells[c] = Cell(value, isNumber = number)
                    widths[c] = maxOf(widths[c] ?: 64f, (value.length * 7f + 12f).coerceAtMost(320f))
                }
            }
            if (fields.size > XlsxReader.MAX_COLUMNS) truncated = true
            if (cells.isNotEmpty()) rows[count] = cells
            columns = maxOf(columns, fields.size.coerceAtMost(XlsxReader.MAX_COLUMNS))
            count++
        }
        val name = file.nameWithoutExtension.ifBlank { "Sheet" }
        return Workbook(listOf(Sheet(name, count, columns, rows, columnWidths = widths, truncated = truncated)))
    }

    /** The most common of comma, semicolon and tab in the first lines (outside quotes). */
    private fun guessDelimiter(text: String): Char {
        val sample = text.take(8192)
        val counts = mutableMapOf(',' to 0, ';' to 0, '\t' to 0)
        var quoted = false
        for (ch in sample) {
            if (ch == '"') quoted = !quoted
            if (!quoted && ch in counts) counts[ch] = counts.getValue(ch) + 1
        }
        return counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ','
    }

    /** RFC 4180: quoted fields may contain delimiters, line breaks and doubled quotes. */
    fun parse(text: String, delimiter: Char): Sequence<List<String>> = sequence {
        var i = 0
        val n = text.length
        val field = StringBuilder()
        var fields = ArrayList<String>()
        while (i < n) {
            val c = text[i]
            if (c == '"' && field.isEmpty()) {
                i++
                while (i < n) {
                    val q = text[i]
                    if (q == '"') {
                        if (i + 1 < n && text[i + 1] == '"') {
                            field.append('"')
                            i += 2
                            continue
                        }
                        i++
                        break
                    }
                    field.append(q)
                    i++
                }
                continue
            }
            when (c) {
                delimiter -> {
                    fields += field.toString()
                    field.clear()
                }
                '\r' -> Unit
                '\n' -> {
                    fields += field.toString()
                    field.clear()
                    yield(fields)
                    fields = ArrayList()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || fields.isNotEmpty()) {
            fields += field.toString()
            yield(fields)
        }
    }
}

/** Pictures held in memory (RTF embeds them in the text itself). */
internal class MemoryImages(private val images: Map<String, ByteArray>) : ImageStore {
    override fun bytes(ref: String): ByteArray? = images[ref]
}
