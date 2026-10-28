package com.dock.scanner.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Reads the sample files in test resources, made by Microsoft Office from the same content
 * in every format (see the scratch script that generated them): a report with styles, lists,
 * a table and a picture; a sales sheet; a three-slide deck.
 */
class OfficeReaderTest {

    private fun open(name: String): OpenedDocument {
        val input = javaClass.getResourceAsStream("/samples/$name") ?: error("missing sample $name")
        val file = File.createTempFile("sample", "." + name.substringAfterLast('.'))
        file.deleteOnExit()
        input.use { i -> file.outputStream().use { i.copyTo(it) } }
        val format = OfficeFormat.of(name, null) ?: error("unknown format for $name")
        return OfficeReader.open(file, format)
    }

    private fun TextDocument.paragraphs(): List<Paragraph> = pages.flatten().flatMap { block ->
        when (block) {
            is Paragraph -> listOf(block)
            is Table -> block.rows.flatMap { r -> r.cells.flatMap { c -> c.blocks.filterIsInstance<Paragraph>() } }
        }
    }

    private fun Paragraph.text() = spans.filterIsInstance<TextSpan>().joinToString("") { it.text }

    private fun TextDocument.allText() = paragraphs().joinToString("\n") { it.text() }

    private fun TextDocument.span(text: String): TextSpan =
        paragraphs().flatMap { it.spans }.filterIsInstance<TextSpan>().first { it.text.trim() == text }

    // region Word

    @Test
    fun docxKeepsFormattingListsTablesAndPages() {
        val doc = open("sample.docx").document as TextDocument
        assertEquals(2, doc.pages.size)
        val heading = doc.span("Quarterly Report")
        assertTrue("heading is larger: ${heading.style}", heading.style.size > 14f)
        assertTrue(doc.span("bold").style.bold)
        assertTrue(doc.span("italic").style.italic)
        assertTrue(doc.span("underlined").style.underline)
        assertEquals(0xFFFF0000.toInt(), doc.span("red").style.color)

        val paragraphs = doc.paragraphs()
        assertEquals("•", paragraphs.first { it.text() == "Revenue grew 12%" }.marker)
        assertEquals("1.", paragraphs.first { it.text() == "First step" }.marker)
        assertEquals("2.", paragraphs.first { it.text() == "Second step" }.marker)
        assertEquals(Align.Center, paragraphs.first { it.text() == "Centered line" }.align)

        val table = doc.pages[0].filterIsInstance<Table>().single()
        assertEquals(3, table.rows.size)
        assertEquals(3, table.columnWidths.size)
        assertTrue(table.borders)
        val region = table.rows[0].cells[0].blocks.filterIsInstance<Paragraph>().single()
        assertEquals("Region", region.text())
        assertTrue((region.spans.single() as TextSpan).style.bold)

        val image = doc.pages.flatten().filterIsInstance<Paragraph>().flatMap { it.spans }.filterIsInstance<ImageSpan>().single()
        assertNotNull(doc.images?.bytes(image.ref))
        assertEquals("Second page text.", doc.pages[1].filterIsInstance<Paragraph>().first { it.text().isNotBlank() }.text())
    }

    @Test
    fun docReadsTextTablesAndPageBreaks() {
        val doc = open("sample.doc").document as TextDocument
        val text = doc.allText()
        listOf("Quarterly Report", "This paragraph has bold, italic, underlined and red text.", "Revenue grew 12%", "Centered line")
            .forEach { assertTrue("missing '$it' in:\n$text", it in text) }
        assertTrue("table row", doc.paragraphs().any { it.text().startsWith("Region\tQ1\tQ2") })
        assertEquals(2, doc.pages.size)
        assertTrue(doc.pages[1].filterIsInstance<Paragraph>().any { it.text() == "Second page text." })
    }

    @Test
    fun rtfKeepsFormattingAndPicture() {
        val doc = open("sample.rtf").document as TextDocument
        assertTrue(doc.span("bold").style.bold)
        assertTrue(doc.span("italic").style.italic)
        assertEquals(0xFFFF0000.toInt(), doc.span("red").style.color)
        assertTrue("Region" in doc.allText())
        val image = doc.paragraphs().flatMap { it.spans }.filterIsInstance<ImageSpan>().firstOrNull()
        assertNotNull("picture", image)
        assertNotNull(doc.images?.bytes(image!!.ref))
        assertTrue("Second page text." in doc.allText())
    }

    @Test
    fun odtKeepsFormattingListsAndTables() {
        val doc = open("sample.odt").document as TextDocument
        assertTrue(doc.span("bold").style.bold)
        assertTrue(doc.span("italic").style.italic)
        assertTrue(doc.span("underlined").style.underline)
        assertEquals(0xFFFF0000.toInt(), doc.span("red").style.color)
        val paragraphs = doc.paragraphs()
        assertNotNull(paragraphs.first { it.text() == "Revenue grew 12%" }.marker)
        assertEquals("1.", paragraphs.first { it.text() == "First step" }.marker)
        assertEquals(Align.Center, paragraphs.first { it.text() == "Centered line" }.align)
        assertEquals(3, doc.pages.flatten().filterIsInstance<Table>().single().rows.size)
        assertTrue("Second page text." in doc.allText())
    }

    // endregion

    // region Spreadsheets

    private fun Sheet.text(row: Int, col: Int) = rows[row]?.get(col)?.text

    /**
     * [date] differs by format: Excel stores the code "yyyy/mm/dd" (its "/" shows as the
     * computer's date separator), while ODS and CSV store the text Excel displayed.
     */
    private fun checkSales(book: Workbook, date: String = "2026/01/15") {
        val sales = book.sheets.first { it.name == "Sales" }
        assertEquals("Item", sales.text(0, 0))
        assertEquals("Apples", sales.text(1, 0))
        assertEquals("$1.25", sales.text(1, 1))
        assertEquals("40", sales.text(1, 2))
        assertEquals("50.00", sales.text(1, 3))
        assertEquals(date, sales.text(1, 4))
        assertEquals("25.0%", sales.text(1, 5))
        assertEquals("12.5%", sales.text(3, 5))
        assertEquals("Merged note across columns", sales.text(5, 0))
        assertTrue(sales.columnCount >= 6)
        assertTrue(sales.rows[1]!![1]!!.isNumber)
        val notes = book.sheets.first { it.name == "Notes" }
        assertEquals("Second sheet", notes.text(0, 0))
        assertEquals("42", notes.text(1, 1))
    }

    @Test
    fun xlsxFormatsNumbersAndStyles() {
        val book = open("sample.xlsx").document as Workbook
        checkSales(book)
        val sales = book.sheets.first { it.name == "Sales" }
        val header = sales.rows[0]!![0]!!.style
        assertTrue(header.bold)
        assertNotNull(header.fill)
        assertEquals(listOf(CellRange(5, 0, 5, 3)), sales.merges)
        assertTrue("wide first column", sales.columnWidth(0) > sales.columnWidth(1))
    }

    @Test
    fun xlsReadsTheSameValues() {
        val book = open("sample.xls").document as Workbook
        checkSales(book)
        assertTrue(book.sheets.first { it.name == "Sales" }.rows[0]!![0]!!.style.bold)
    }

    @Test
    fun odsReadsTheSameValues() = checkSales(open("sample.ods").document as Workbook, date = "2026-01-15")

    @Test
    fun csvReadsCells() {
        val sheet = (open("sample.csv").document as Workbook).sheets.single()
        assertEquals("Apples", sheet.text(1, 0))
        assertEquals("$1.25", sheet.text(1, 1))
        assertEquals("Merged note across columns", sheet.text(5, 0))
    }

    // endregion

    // region Presentations

    private fun Presentation.slideText(i: Int): String = slides[i].elements.flatMap { e ->
        when (e) {
            is ShapeElement -> e.text?.paragraphs.orEmpty().map { p -> p.spans.joinToString("") { it.text } }
            else -> emptyList()
        }
    }.joinToString("\n")

    @Test
    fun pptxPlacesShapesTextAndPictures() {
        val pres = open("sample.pptx").document as Presentation
        assertEquals(3, pres.slides.size)
        assertTrue("Dock Preview Test" in pres.slideText(0))
        assertTrue("Slides rendered offline" in pres.slideText(0))
        val agenda = pres.slides[1].elements.filterIsInstance<ShapeElement>().mapNotNull { it.text }.flatMap { it.paragraphs }
        val intro = agenda.first { p -> p.spans.joinToString("") { it.text } == "Introduction" }
        assertNotNull("bulleted", intro.bullet)
        val title = agenda.first { p -> p.spans.joinToString("") { it.text } == "Agenda" }
        assertTrue("title is bigger", title.spans.first().style.size > intro.spans.first().style.size)

        val shapes = pres.slides[2].elements
        val green = shapes.filterIsInstance<ShapeElement>().first { it.text?.paragraphs?.any { p -> p.spans.any { s -> s.text == "Green box" } } == true }
        assertEquals(Colors.rgb(146, 208, 80), green.fill)
        assertTrue(shapes.filterIsInstance<ShapeElement>().any { it.geometry == Geometry.Ellipse && it.fill == Colors.rgb(255, 0, 0) })
        val picture = shapes.filterIsInstance<PictureElement>().single()
        assertNotNull(pres.images?.bytes(picture.ref))
        // Every element sits on the slide.
        pres.slides.flatMap { it.elements }.forEach {
            assertTrue("inside: ${it.frame}", it.frame.x >= 0 && it.frame.x + it.frame.width <= pres.width + 1)
        }
    }

    @Test
    fun pptReadsSlidesTextAndPictures() {
        val pres = open("sample.ppt").document as Presentation
        assertEquals(3, pres.slides.size)
        assertTrue(pres.slideText(0), "Dock Preview Test" in pres.slideText(0))
        assertTrue("Introduction" in pres.slideText(1))
        assertTrue("Green box" in pres.slideText(2))
        val picture = pres.slides[2].elements.filterIsInstance<PictureElement>().single()
        assertNotNull(pres.images?.bytes(picture.ref))
        assertTrue(pres.slides[2].elements.filterIsInstance<ShapeElement>().any { it.fill == Colors.rgb(146, 208, 80) })
    }

    @Test
    fun odpReadsSlidesAndText() {
        val pres = open("sample.odp").document as Presentation
        assertEquals(3, pres.slides.size)
        assertTrue("Dock Preview Test" in pres.slideText(0))
        assertTrue("Introduction" in pres.slideText(1))
        assertTrue(pres.slides[2].elements.any { it is PictureElement })
        assertTrue("oval", pres.slides[2].elements.any { it is ShapeElement && it.geometry == Geometry.Ellipse })
    }

    // endregion

    @Test
    fun detectsFormats() {
        assertEquals(OfficeFormat.DOCX, OfficeFormat.of("Report.DOCX", null))
        assertEquals(OfficeFormat.XLS, OfficeFormat.of("x", "application/vnd.ms-excel"))
        assertEquals(OfficeFormat.PPTX, OfficeFormat.of("deck.pptx", "application/octet-stream"))
        assertEquals(null, OfficeFormat.of("photo.jpg", "image/jpeg"))
    }
}
