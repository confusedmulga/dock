package com.dock.scanner.pdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfTextLayerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "textlayer-test").apply { deleteRecursively(); mkdirs() }

    @Before
    fun init() = PdfTools.init(context)

    /** An A4 page with a gray box and no text, turned by [rotation] degrees. */
    private fun scanPdf(rotation: Int): File {
        val file = File(dir, "scan_$rotation.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4).apply { this.rotation = rotation }
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.setNonStrokingColor(0.8f)
                cs.addRect(50f, 50f, 200f, 100f)
                cs.fill()
            }
            doc.documentInformation.producer = "Dock"
            doc.save(file)
        }
        return file
    }

    private fun word(text: String, x: Float, y: Float, width: Float, height: Float = 40f) =
        PdfTextLayer.Word(text, x, y, width, height, 1f, 0f)

    /** Where each text run starts, as PDFBox sees it on the page as displayed (origin top-left). */
    private fun firstPositions(file: File): List<Pair<String, TextPosition>> {
        val found = ArrayList<Pair<String, TextPosition>>()
        PDDocument.load(file).use { doc ->
            object : PDFTextStripper() {
                override fun writeString(text: String, positions: MutableList<TextPosition>) {
                    found += text to positions.first()
                }
            }.getText(doc)
        }
        return found
    }

    @Test
    fun addsSearchableTextAndTakesItOutAgain() {
        val input = scanPdf(0)
        assertEquals(listOf(0), PdfTools.pagesWithoutText(input))

        // A 2 px per point image of the page.
        val text = PdfTextLayer.PageText(1190, 1684, listOf(listOf(word("Hello", 200f, 400f, 200f), word("world", 420f, 400f, 210f))))
        val withText = File(dir, "with.pdf")
        PdfTools.addTextLayer(input, withText, mapOf(0 to text))

        PDDocument.load(withText).use { doc ->
            assertEquals("Hello world", PDFTextStripper().getText(doc).trim())
            assertEquals(PdfTextLayer.Info(madeByDock = true, hasLayer = true, choice = true), PdfTextLayer.info(doc))
        }
        val (_, hello) = firstPositions(withText).first()
        assertEquals(100f, hello.x, 1f)
        assertEquals(200f, hello.y, 1f)

        val without = File(dir, "without.pdf")
        PdfTools.removeTextLayer(withText, without)
        PDDocument.load(without).use { doc ->
            assertEquals("", PDFTextStripper().getText(doc).trim())
            assertEquals(PdfTextLayer.Info(madeByDock = true, hasLayer = false, choice = false), PdfTextLayer.info(doc))
            // Only the page's own drawing is left.
            val contents = doc.getPage(0).cosObject.getDictionaryObject(COSName.CONTENTS)
            assertTrue(contents !is COSArray || contents.size() == 1)
            assertFalse(doc.getPage(0).resources.fontNames.any { it.name == "DockOCR" })
        }
    }

    @Test
    fun wordsLandWhereTheyWereSeenOnRotatedPages() {
        for (rotation in listOf(90, 180, 270)) {
            val input = scanPdf(rotation)
            // PdfRenderer draws turned pages turned: landscape for 90 and 270.
            val (w, h) = if (rotation % 180 == 0) 1190 to 1684 else 1684 to 1190
            val text = PdfTextLayer.PageText(w, h, listOf(listOf(word("Rotated", 200f, 400f, 300f))))
            val output = File(dir, "rotated_$rotation.pdf")
            PdfTools.addTextLayer(input, output, mapOf(0 to text))
            val (found, start) = firstPositions(output).first()
            assertEquals("Rotated", found)
            assertEquals("x at $rotation°", 100f, start.x, 1f)
            assertEquals("y at $rotation°", 200f, start.y, 1f)
        }
    }

    @Test
    fun skipsPagesThatAlreadyHaveText() {
        val file = File(dir, "text.pdf")
        PDDocument().use { doc ->
            repeat(2) { i ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                if (i == 0) {
                    PDPageContentStream(doc, page).use { cs ->
                        cs.beginText()
                        cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12f)
                        cs.newLineAtOffset(50f, 700f)
                        cs.showText("This page was typed, so it already has plenty of text.")
                        cs.endText()
                    }
                }
            }
            doc.save(file)
        }
        assertEquals(listOf(1), PdfTools.pagesWithoutText(file))
    }
}
