package com.dock.scanner.pdf

import kotlin.math.abs

/** A rectangle on a page as fractions of its width and height (top-left origin). */
data class TextBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerY: Float get() = (top + bottom) / 2
}

/**
 * The text of one page as a reader sees it: its characters in reading order, each with its box
 * on the page as it's displayed (fractions of the page's width and height, origin at the top
 * left, the page's rotation applied), so they line up with a rendered image of the page at any
 * size.
 *
 * Words are separated by single spaces and lines by '\n'. The separators have boxes too (a space
 * spans the gap between its words, a line break is empty), so a selection highlights as one run
 * per line and copies as ordinary text.
 *
 * Indexes are into [text]; ranges are inclusive.
 */
class PdfPageText private constructor(
    /** The page's size as displayed, in points: its shape, for laying it out before it's drawn. */
    val width: Float,
    val height: Float,
    val text: String,
    private val boxes: FloatArray,
    /** Index of each line's first character. */
    private val lineStarts: IntArray,
) {
    val isEmpty: Boolean get() = text.isBlank()

    val length: Int get() = text.length

    /** Letters and digits: how much text the page really has, telling stray page numbers from a page of text. */
    val letterCount: Int get() = text.count { it.isLetterOrDigit() }

    fun box(index: Int): TextBox =
        TextBox(boxes[4 * index], boxes[4 * index + 1], boxes[4 * index + 2], boxes[4 * index + 3])

    /** The line [index] is on. */
    fun lineOf(index: Int): Int {
        var low = 0
        var high = lineStarts.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (lineStarts[mid] <= index) low = mid else high = mid - 1
        }
        return low
    }

    /** The characters of [line], its closing line break included. */
    private fun lineRange(line: Int): IntRange =
        lineStarts[line] until (if (line < lineStarts.lastIndex) lineStarts[line + 1] else text.length)

    /** The characters of [line] that are drawn: without its line break. */
    private fun visible(line: Int): IntRange = lineRange(line).let { r ->
        if (!r.isEmpty() && text[r.last] == '\n') r.first until r.last else r
    }

    private fun lineBounds(line: Int): TextBox? {
        val range = visible(line)
        if (range.isEmpty()) return null
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        for (i in range) {
            top = minOf(top, boxes[4 * i + 1])
            bottom = maxOf(bottom, boxes[4 * i + 3])
            left = minOf(left, boxes[4 * i])
            right = maxOf(right, boxes[4 * i + 2])
        }
        return TextBox(left, top, right, bottom)
    }

    /**
     * The character nearest to the point ([x], [y]) on the page, as fractions; -1 when the page
     * has no text. The line comes first (the one the point is on, or the closest), then the
     * character on it.
     */
    fun charAt(x: Float, y: Float): Int {
        var bestLine = -1
        var bestDistance = Float.MAX_VALUE
        for (line in lineStarts.indices) {
            val b = lineBounds(line) ?: continue
            // Inside the line's height counts as on it; between lines, the closer one wins.
            val distance = when {
                y < b.top -> b.top - y
                y > b.bottom -> y - b.bottom
                else -> -1f / (1f + abs(y - b.centerY))
            }
            if (distance < bestDistance) {
                bestDistance = distance
                bestLine = line
            }
        }
        if (bestLine < 0) return -1
        var best = -1
        var bestX = Float.MAX_VALUE
        for (i in visible(bestLine)) {
            val left = boxes[4 * i]
            val right = boxes[4 * i + 2]
            val distance = when {
                x < left -> left - x
                x > right -> x - right
                else -> 0f
            }
            if (distance < bestX) {
                bestX = distance
                best = i
                if (distance == 0f && text[i] != ' ') break
            }
        }
        return best
    }

    /** The word around [index]: the run of characters up to the nearest spaces or line ends. */
    fun wordAt(index: Int): IntRange {
        if (index !in text.indices) return IntRange.EMPTY
        if (text[index].isWhitespace()) return index..index
        var start = index
        var end = index
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        while (end < text.lastIndex && !text[end + 1].isWhitespace()) end++
        return start..end
    }

    /** Rectangles covering characters [start] to [end], one per line, to highlight them. */
    fun rects(start: Int, end: Int): List<TextBox> {
        if (text.isEmpty()) return emptyList()
        val from = start.coerceIn(0, text.lastIndex)
        val to = end.coerceIn(0, text.lastIndex)
        if (to < from) return emptyList()
        val result = ArrayList<TextBox>()
        for (line in lineOf(from)..lineOf(to)) {
            val range = visible(line)
            val first = maxOf(range.first, from)
            val last = minOf(range.last, to)
            if (first > last) continue
            // The height of the characters covered, not the whole line's: on a slanted line
            // (a photographed page) the whole line spans the lines above and below.
            var top = Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            for (i in first..last) {
                top = minOf(top, boxes[4 * i + 1])
                bottom = maxOf(bottom, boxes[4 * i + 3])
            }
            val left = minOf(boxes[4 * first], boxes[4 * last])
            val right = maxOf(boxes[4 * first + 2], boxes[4 * last + 2])
            if (right > left) result += TextBox(left, top, right, bottom)
        }
        return result
    }

    /** Characters [start] to [end] as text, with the line breaks between them. */
    fun textOf(start: Int, end: Int): String {
        if (text.isEmpty()) return ""
        val from = start.coerceIn(0, text.lastIndex)
        val to = end.coerceIn(0, text.lastIndex)
        return if (to < from) "" else text.substring(from, to + 1).trim()
    }

    /**
     * Every place [query] is on the page, ignoring case, and with any run of spaces in it
     * matching a space or a line break. Matches don't overlap.
     */
    fun find(query: String): List<IntRange> {
        val needle = searchable(query.trim().replace(WHITESPACE, " "))
        if (needle.isEmpty()) return emptyList()
        val haystack = searchable(text)
        val found = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) break
            found += at until at + needle.length
            from = at + needle.length
        }
        return found
    }

    /**
     * Lower case, one character for one, and line breaks as spaces, so indexes found in it are
     * indexes into the text. ([String.lowercase] can change the length, e.g. for "İ".)
     */
    private fun searchable(s: String): String = buildString(s.length) {
        for (c in s) append(if (c == '\n') ' ' else c.lowercaseChar())
    }

    /**
     * Collects a page's characters in reading order. Boxes are fractions of the page as
     * displayed; anything outside is clamped onto it.
     */
    class Builder(private val width: Float, private val height: Float) {
        private val text = StringBuilder()
        private var boxes = FloatArray(256)
        private var lines = IntArray(16)
        private var lineCount = 0
        private var lineOpen = false
        /** A space was asked for: it's added before the next character on the same line. */
        private var pendingSpace = false

        /** Adds a character that's drawn on the page (not a space or line break). */
        fun char(c: Char, left: Float, top: Float, right: Float, bottom: Float) {
            if (c == '\n' || c.isWhitespace()) {
                space()
                return
            }
            val l = left.coerceIn(0f, 1f)
            val r = right.coerceIn(0f, 1f)
            val t = minOf(top, bottom).coerceIn(0f, 1f)
            val b = maxOf(top, bottom).coerceIn(0f, 1f)
            if (!lineOpen) {
                startLine()
            } else if (pendingSpace) {
                // The space fills the gap between the two words (nothing, if they touch or overlap).
                val prev = text.length - 1
                val gapLeft = boxes[4 * prev + 2]
                val gapRight = maxOf(gapLeft, minOf(l, r))
                append(' ', gapLeft, minOf(boxes[4 * prev + 1], t), gapRight, maxOf(boxes[4 * prev + 3], b))
            }
            pendingSpace = false
            append(c, minOf(l, r), t, maxOf(l, r), b)
        }

        /** Adds the characters of [s] spread evenly over a box, e.g. a ligature or a recognized word. */
        fun spread(s: String, left: Float, top: Float, right: Float, bottom: Float) {
            val drawn = s.filterNot { it.isWhitespace() }
            if (drawn.isEmpty()) {
                if (s.isNotEmpty()) space()
                return
            }
            if (s.first().isWhitespace()) space()
            val step = (right - left) / drawn.length
            drawn.forEachIndexed { i, c -> char(c, left + i * step, top, left + (i + 1) * step, bottom) }
            if (s.last().isWhitespace()) space()
        }

        /** Separates words. Several in a row count as one; none is added at the start or end of a line. */
        fun space() {
            if (lineOpen) pendingSpace = true
        }

        /** Ends the line. An empty line adds nothing. */
        fun lineBreak() {
            if (!lineOpen) return
            val prev = text.length - 1
            append('\n', boxes[4 * prev + 2], boxes[4 * prev + 1], boxes[4 * prev + 2], boxes[4 * prev + 3])
            lineOpen = false
            pendingSpace = false
        }

        fun build(): PdfPageText {
            // The last line's break would only add a trailing newline to everything copied.
            if (lineOpen) lineOpen = false
            val length = if (text.isNotEmpty() && text.last() == '\n') text.length - 1 else text.length
            return PdfPageText(
                width, height,
                text.substring(0, length),
                boxes.copyOf(4 * length),
                lines.copyOf(lineCount).filter { it < length }.toIntArray(),
            )
        }

        private fun startLine() {
            if (lineCount == lines.size) lines = lines.copyOf(lines.size * 2)
            lines[lineCount++] = text.length
            lineOpen = true
        }

        private fun append(c: Char, l: Float, t: Float, r: Float, b: Float) {
            val i = text.length
            if (4 * i + 4 > boxes.size) boxes = boxes.copyOf(maxOf(boxes.size * 2, 4 * i + 4))
            boxes[4 * i] = l
            boxes[4 * i + 1] = t
            boxes[4 * i + 2] = r
            boxes[4 * i + 3] = b
            text.append(c)
        }
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /** A page with no text, of the given shape. */
        fun empty(width: Float = 1f, height: Float = 1.414f): PdfPageText =
            PdfPageText(width, height, "", FloatArray(0), IntArray(0))
    }
}
