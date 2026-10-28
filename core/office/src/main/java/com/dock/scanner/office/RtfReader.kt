package com.dock.scanner.office

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

/**
 * Rich Text Format: paragraphs, bold/italic/underline/strike, sizes, colors, highlights,
 * alignment, indents, page breaks and embedded PNG/JPEG pictures. Table cells become tabs.
 */
internal object RtfReader {

    fun read(file: File): TextDocument {
        val bytes = file.inputStream().use { LimitedRead.readUpTo(it, TextDecoding.LIMIT.toInt()) }
        // The control text is ASCII; characters above 127 arrive as \'hh escapes or \u.
        val rtf = String(bytes, Charsets.ISO_8859_1)
        if (!rtf.startsWith("{\\rtf")) throw UnreadableDocumentException("This isn't a rich text file")
        return Parser(rtf).parse()
    }

    /** Formatting and destination of one {group}; nested groups start from a copy. */
    private data class State(
        var bold: Boolean = false,
        var italic: Boolean = false,
        var underline: Boolean = false,
        var strike: Boolean = false,
        var size: Float = 12f,
        var color: Int = 0,
        var highlight: Int = 0,
        var font: Int = -1,
        var baseline: Baseline = Baseline.Normal,
        var caps: Boolean = false,
        var hidden: Boolean = false,
        /** Inside a destination whose text isn't shown (headers, field codes, metadata...). */
        var skip: Boolean = false,
        var destination: Destination = Destination.Text,
        /** How many characters follow each \u as a fallback for old readers. */
        var ucSkip: Int = 1,
        var ucPending: Int = 0,
    )

    private enum class Destination { Text, FontTable, FontEntry, ColorTable, Picture }

    private val SKIPPED = setOf(
        "stylesheet", "info", "header", "headerl", "headerr", "headerf", "footer", "footerl", "footerr", "footerf",
        "footnote", "fldinst", "object", "datastore", "themedata", "colorschememapping", "latentstyles", "listtable",
        "listoverridetable", "rsidtbl", "generator", "xmlnstbl", "mmathPr", "pgdsctbl", "nonshppict", "bkmkstart",
        "bkmkend", "annotation", "atnid", "atnauthor", "atndate", "comment", "filetbl", "revtbl", "pnseclvl",
        "userprops", "docvar", "xe", "tc", "template", "wgrffmtfilter", "panose", "falt", "listpicture", "blipuid",
        "fontemb", "fontfile", "shpinst", "sp", "sn", "sv", "protusertbl", "ftnsep", "ftnsepc", "aftnsep",
    )

    private class Parser(private val s: String) {
        private val colors = ArrayList<Int>()
        private val fonts = HashMap<Int, String>()
        private val images = HashMap<String, ByteArray>()
        private var codepage: Charset = Charset.forName("windows-1252")

        private val pages = mutableListOf(mutableListOf<Block>())
        private val spans = ArrayList<Span>()
        private val text = StringBuilder()
        private var textStyle: TextStyle? = null
        private var align = Align.Start
        private var indent = 0f
        private var firstLine = 0f
        private var spaceBefore = 0f
        private var spaceAfter = 0f

        private var state = State()
        private val stack = ArrayDeque<State>()
        private val pendingBytes = ByteArrayOutputStream()

        // Color table entry being read.
        private var red = 0
        private var green = 0
        private var blue = 0
        private var colorSet = false

        // Font table entry being read, and the group depth it started at.
        private var fontIndex = -1
        private val fontName = StringBuilder()
        private var fontDepth = -1

        // Picture being read, and the group depth it started at.
        private var pictHex: StringBuilder? = null
        private var pictType: String? = null
        private var pictDepth = -1
        private var picw = 0f
        private var pich = 0f
        private var picwGoal = 0f
        private var pichGoal = 0f

        fun parse(): TextDocument {
            var i = 0
            while (i < s.length) {
                when (val c = s[i]) {
                    '{' -> {
                        flushBytes()
                        stack.addLast(state)
                        state = state.copy(ucPending = 0)
                        i++
                    }
                    '}' -> {
                        flushBytes()
                        endGroup()
                        state = stack.removeLastOrNull() ?: State()
                        i++
                    }
                    '\\' -> i = control(i)
                    '\r', '\n' -> i++
                    else -> {
                        flushBytes()
                        plain(c)
                        i++
                    }
                }
            }
            flushBytes()
            endParagraph()
            val content = pages.filter { it.isNotEmpty() }.ifEmpty { listOf(emptyList<Block>()) }
            return TextDocument(612f, 792f, Margins(72f, 72f, 72f, 72f), content, MemoryImages(images))
        }

        private fun endGroup() {
            val depth = stack.size
            if (state.destination == Destination.FontEntry && depth == fontDepth) {
                if (fontIndex >= 0) fonts[fontIndex] = fontName.toString().trim().trimEnd(';').trim()
                fontName.clear()
                fontDepth = -1
            }
            if (state.destination == Destination.Picture && depth == pictDepth) {
                finishPicture()
                pictDepth = -1
            }
        }

        private fun control(start: Int): Int {
            var i = start + 1
            if (i >= s.length) return i
            val c = s[i]
            if (!c.isLetter()) {
                when (c) {
                    '\'' -> {
                        s.substring(i + 1, minOf(i + 3, s.length)).toIntOrNull(16)?.let(::byte)
                        return i + 3
                    }
                    '*' -> {
                        // An optional destination: skip it unless it's one we read anyway.
                        if (!s.startsWith("\\shppict", i + 1)) state.skip = true
                        return i + 1
                    }
                    '~' -> plain(' ')
                    '_' -> plain('‑')
                    '\\', '{', '}' -> plain(c)
                    '\n', '\r' -> endParagraph()
                }
                return i + 1
            }
            val wordStart = i
            while (i < s.length && s[i].isLetter()) i++
            val word = s.substring(wordStart, i)
            var param: Int? = null
            if (i < s.length && (s[i] == '-' || s[i].isDigit())) {
                val numStart = i
                i++
                while (i < s.length && s[i].isDigit()) i++
                param = s.substring(numStart, i).toIntOrNull()
            }
            if (i < s.length && s[i] == ' ') i++
            if (word == "bin" && param != null) return i + param // raw binary data
            flushBytes()
            word(word, param)
            return i
        }

        private fun word(word: String, p: Int?) {
            if (state.skip) return
            if (word in SKIPPED) {
                state.skip = true
                return
            }
            when (state.destination) {
                Destination.ColorTable -> {
                    when (word) {
                        "red" -> red = p ?: 0
                        "green" -> green = p ?: 0
                        "blue" -> blue = p ?: 0
                        else -> return
                    }
                    colorSet = true
                    return
                }
                Destination.FontTable, Destination.FontEntry -> {
                    if (word == "f") {
                        state.destination = Destination.FontEntry
                        fontIndex = p ?: -1
                        fontName.clear()
                        fontDepth = stack.size
                    }
                    return
                }
                Destination.Picture -> {
                    when (word) {
                        "pngblip" -> pictType = "png"
                        "jpegblip" -> pictType = "jpg"
                        "picw" -> picw = (p ?: 0).toFloat()
                        "pich" -> pich = (p ?: 0).toFloat()
                        "picwgoal" -> picwGoal = (p ?: 0) / 20f
                        "pichgoal" -> pichGoal = (p ?: 0) / 20f
                    }
                    return
                }
                Destination.Text -> Unit
            }
            when (word) {
                "fonttbl" -> state.destination = Destination.FontTable
                "colortbl" -> state.destination = Destination.ColorTable
                "ansicpg" -> p?.let { runCatching { codepage = Charset.forName("windows-$it") } }
                "pict" -> {
                    state.destination = Destination.Picture
                    pictHex = StringBuilder()
                    pictType = null
                    picwGoal = 0f
                    pichGoal = 0f
                    picw = 0f
                    pich = 0f
                    pictDepth = stack.size
                }
                "par", "sect" -> endParagraph()
                "line" -> plain('\n')
                "tab", "cell" -> plain('\t')
                "row" -> endParagraph()
                "page" -> {
                    endParagraph()
                    if (pages.last().isNotEmpty()) pages += mutableListOf<Block>()
                }
                "pard" -> {
                    align = Align.Start
                    indent = 0f
                    firstLine = 0f
                    spaceBefore = 0f
                    spaceAfter = 0f
                }
                "plain" -> {
                    state.bold = false
                    state.italic = false
                    state.underline = false
                    state.strike = false
                    state.size = 12f
                    state.color = 0
                    state.highlight = 0
                    state.baseline = Baseline.Normal
                    state.caps = false
                    state.hidden = false
                }
                "b" -> state.bold = p != 0
                "i" -> state.italic = p != 0
                "ul" -> state.underline = p != 0
                "uld", "uldb", "ulw", "ulwave", "uldash", "ulth", "uldashd", "ulhwave" -> state.underline = true
                "ulnone" -> state.underline = false
                "strike", "striked" -> state.strike = p != 0
                "caps" -> state.caps = p != 0
                "v" -> state.hidden = p != 0
                "fs" -> state.size = (p ?: 24) / 2f
                "cf" -> state.color = p ?: 0
                "highlight", "cb", "chcbpat" -> state.highlight = p ?: 0
                "f" -> state.font = p ?: -1
                "super" -> state.baseline = Baseline.Superscript
                "sub" -> state.baseline = Baseline.Subscript
                "nosupersub" -> state.baseline = Baseline.Normal
                "qc" -> align = Align.Center
                "qr" -> align = Align.End
                "qj" -> align = Align.Justify
                "ql" -> align = Align.Start
                "li" -> indent = (p ?: 0) / 20f
                "fi" -> firstLine = (p ?: 0) / 20f
                "sb" -> spaceBefore = (p ?: 0) / 20f
                "sa" -> spaceAfter = (p ?: 0) / 20f
                "uc" -> state.ucSkip = p ?: 1
                "u" -> p?.let {
                    plain((if (it < 0) it + 65_536 else it).toChar())
                    state.ucPending = state.ucSkip
                }
                "emdash" -> plain('—')
                "endash" -> plain('–')
                "bullet" -> plain('•')
                "lquote" -> plain('‘')
                "rquote" -> plain('’')
                "ldblquote" -> plain('“')
                "rdblquote" -> plain('”')
            }
        }

        /** A byte from a \'hh escape, decoded with the document's code page. */
        private fun byte(b: Int) {
            if (state.skip) return
            if (state.ucPending > 0) {
                state.ucPending--
                return
            }
            pendingBytes.write(b)
        }

        private fun plain(c: Char) {
            if (state.skip) return
            when (state.destination) {
                Destination.ColorTable -> {
                    if (c == ';') {
                        colors += if (colorSet) Colors.rgb(red, green, blue) else 0
                        red = 0
                        green = 0
                        blue = 0
                        colorSet = false
                    }
                    return
                }
                Destination.FontEntry -> {
                    fontName.append(c)
                    return
                }
                Destination.FontTable -> return
                Destination.Picture -> {
                    if (c.isLetterOrDigit()) pictHex?.append(c)
                    return
                }
                Destination.Text -> Unit
            }
            if (state.hidden) return
            if (state.ucPending > 0) {
                state.ucPending--
                return
            }
            val style = currentStyle()
            if (style != textStyle) {
                flushText()
                textStyle = style
            }
            text.append(c)
        }

        private fun flushBytes() {
            if (pendingBytes.size() == 0) return
            val decoded = String(pendingBytes.toByteArray(), codepage)
            pendingBytes.reset()
            val pending = state.ucPending
            state.ucPending = 0 // already applied while collecting the bytes
            decoded.forEach(::plain)
            state.ucPending = pending
        }

        private fun currentStyle() = TextStyle(
            size = state.size,
            bold = state.bold,
            italic = state.italic,
            underline = state.underline,
            strike = state.strike,
            color = colors.getOrNull(state.color)?.takeIf { it != 0 },
            background = colors.getOrNull(state.highlight)?.takeIf { it != 0 && state.highlight != 0 },
            font = fontKind(fonts[state.font]),
            baseline = state.baseline,
            allCaps = state.caps,
        )

        private fun flushText() {
            val style = textStyle ?: return
            if (text.isNotEmpty()) spans += TextSpan(text.toString(), style)
            text.clear()
        }

        private fun endParagraph() {
            flushText()
            val content = spans.toList().ifEmpty { listOf(TextSpan("", currentStyle())) }
            pages.last() += Paragraph(content, align, indent, firstLine, spaceBefore, spaceAfter)
            spans.clear()
            textStyle = null
        }

        private fun finishPicture() {
            val hex = pictHex ?: return
            pictHex = null
            val type = pictType ?: return
            if (hex.length < 2) return
            val bytes = ByteArray(hex.length / 2) { k ->
                ((Character.digit(hex[k * 2], 16) shl 4) or Character.digit(hex[k * 2 + 1], 16)).toByte()
            }
            val ref = "image${images.size + 1}.$type"
            images[ref] = bytes
            flushText()
            val w = picwGoal.takeIf { it > 0 } ?: picw.takeIf { it > 0 }?.times(0.75f) ?: 200f
            val h = pichGoal.takeIf { it > 0 } ?: pich.takeIf { it > 0 }?.times(0.75f) ?: 150f
            spans += ImageSpan(ref, w, h)
        }
    }
}
