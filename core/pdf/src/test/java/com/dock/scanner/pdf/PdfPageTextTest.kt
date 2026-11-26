package com.dock.scanner.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageTextTest {

    /**
     * Two lines of words, each character 0.02 wide: "Hello world" at y 0.10..0.12 and
     * "Second line here" at y 0.20..0.22, starting at x 0.1.
     */
    private fun page(): PdfPageText = PdfPageText.Builder(595f, 842f).apply {
        line("Hello world", top = 0.10f)
        line("Second line here", top = 0.20f)
    }.build()

    private fun PdfPageText.Builder.line(words: String, top: Float) {
        var x = 0.1f
        for (c in words) {
            if (c == ' ') space() else char(c, x, top, x + 0.02f, top + 0.02f)
            x += 0.02f
        }
        lineBreak()
    }

    @Test
    fun textHasSingleSpacesAndLineBreaksAndNoTrailingBreak() {
        assertEquals("Hello world\nSecond line here", page().text)
    }

    @Test
    fun spacesAndRepeatedBreaksAreNotDoubled() {
        val text = PdfPageText.Builder(1f, 1f).apply {
            space() // nothing to separate yet
            char('a', 0f, 0f, 0.1f, 0.1f)
            space(); space()
            char('b', 0.2f, 0f, 0.3f, 0.1f)
            space()
            lineBreak(); lineBreak()
            char('c', 0f, 0.2f, 0.1f, 0.3f)
        }.build().text
        assertEquals("a b\nc", text)
    }

    @Test
    fun aSpaceSpansTheGapBetweenItsWords() {
        val text = PdfPageText.Builder(1f, 1f).apply {
            char('a', 0.1f, 0f, 0.2f, 0.1f)
            space()
            char('b', 0.5f, 0f, 0.6f, 0.1f)
        }.build()
        val space = text.box(1)
        assertEquals(0.2f, space.left, 1e-6f)
        assertEquals(0.5f, space.right, 1e-6f)
    }

    @Test
    fun charAtFindsTheCharacterUnderAPointAndTheNearestOtherwise() {
        val text = page()
        // On the "w" of "world".
        assertEquals('w', text.text[text.charAt(0.1f + 6 * 0.02f + 0.01f, 0.11f)])
        // Left of the second line, a little below it: its first character.
        assertEquals(text.text.indexOf('S'), text.charAt(0.0f, 0.25f))
        // Between the lines, closer to the first: the first line.
        assertEquals(0, text.lineOf(text.charAt(0.1f, 0.13f)))
        // Far right of the first line: its last character, not the line break.
        assertEquals('d', text.text[text.charAt(0.9f, 0.11f)])
    }

    @Test
    fun charAtOnAnEmptyPageIsMinusOne() {
        assertEquals(-1, PdfPageText.empty().charAt(0.5f, 0.5f))
        assertEquals(-1, PdfPageText.Builder(1f, 1f).build().charAt(0.5f, 0.5f))
    }

    @Test
    fun wordAtStopsAtSpacesAndLineEnds() {
        val text = page()
        val world = text.text.indexOf("world")
        assertEquals(world..world + 4, text.wordAt(world + 2))
        assertEquals("world", text.textOf(world, world + 4))
        val second = text.text.indexOf("Second")
        assertEquals(second..second + 5, text.wordAt(second))
    }

    @Test
    fun rectsGiveOneRectanglePerLineWithoutTheLineBreak() {
        val text = page()
        val start = text.text.indexOf("world")
        val end = text.text.indexOf("line") + 3
        val rects = text.rects(start, end)
        assertEquals(2, rects.size)
        // "world" ends at the end of the first line's last character, not beyond.
        assertEquals(0.1f + 11 * 0.02f, rects[0].right, 1e-5f)
        assertEquals(0.1f, rects[1].left, 1e-5f)
        assertEquals(0.20f, rects[1].top, 1e-5f)
    }

    @Test
    fun rectsOnASlantedLineOnlyReachAsHighAsTheCharactersCovered() {
        // Each word a little lower than the one before, like text on a tilted photo.
        val text = PdfPageText.Builder(1f, 1f).apply {
            for (w in 0 until 5) {
                val x = 0.1f + w * 0.15f
                val top = 0.1f + w * 0.03f
                "word".forEachIndexed { i, c -> char(c, x + i * 0.03f, top, x + (i + 1) * 0.03f, top + 0.02f) }
                space()
            }
        }.build()
        val last = text.text.lastIndexOf("word")
        val rect = text.rects(last, last + 3).single()
        assertEquals(0.22f, rect.top, 1e-5f)
        assertEquals(0.24f, rect.bottom, 1e-5f)
    }

    @Test
    fun textOfKeepsLineBreaks() {
        val text = page()
        assertEquals("world\nSecond", text.textOf(text.text.indexOf("world"), text.text.indexOf("Second") + 5))
    }

    @Test
    fun findIgnoresCaseAndMatchesAcrossLineBreaks() {
        val text = page()
        assertEquals(listOf(text.text.indexOf("world")..text.text.indexOf("world") + 4), text.find("WORLD"))
        // "world second" spans the line break.
        val across = text.find("  world   second ")
        assertEquals(1, across.size)
        assertEquals("world\nSecond", text.textOf(across[0].first, across[0].last))
        assertTrue(text.find("missing").isEmpty())
        assertTrue(text.find("   ").isEmpty())
    }

    @Test
    fun findReturnsEveryMatchWithoutOverlaps() {
        val text = PdfPageText.Builder(1f, 1f).apply {
            "aaaa".forEachIndexed { i, c -> char(c, i * 0.1f, 0f, i * 0.1f + 0.1f, 0.1f) }
        }.build()
        assertEquals(listOf(0..1, 2..3), text.find("aa"))
    }

    @Test
    fun spreadSharesABoxBetweenItsCharacters() {
        val text = PdfPageText.Builder(1f, 1f).apply { spread("fi", 0.2f, 0f, 0.4f, 0.1f) }.build()
        assertEquals("fi", text.text)
        assertEquals(0.3f, text.box(0).right, 1e-6f)
        assertEquals(0.3f, text.box(1).left, 1e-6f)
    }

    @Test
    fun boxesOffThePageAreClampedOntoIt() {
        val box = PdfPageText.Builder(1f, 1f).apply { char('x', -0.5f, -0.1f, 1.5f, 1.2f) }.build().box(0)
        assertEquals(TextBox(0f, 0f, 1f, 1f), box)
    }

    @Test
    fun letterCountIgnoresSpacesAndPunctuation() {
        assertEquals(24, page().letterCount)
    }
}
