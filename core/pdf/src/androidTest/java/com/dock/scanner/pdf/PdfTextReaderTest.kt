package com.dock.scanner.pdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfTextReaderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "textreader-test").apply { deleteRecursively(); mkdirs() }

    @Before
    fun init() = PdfTools.init(context)

    /** One A4 page per entry, each with its label as a line of 20 pt text at (100, 700) from the bottom left. */
    private fun textPdf(name: String, labels: List<String>, rotation: Int = 0): File {
        val file = File(dir, name)
        PDDocument().use { doc ->
            for (label in labels) {
                val page = PDPage(PDRectangle.A4).apply { this.rotation = rotation }
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 20f)
                    cs.newLineAtOffset(100f, 700f)
                    cs.showText(label)
                    cs.newLineAtOffset(0f, -40f)
                    cs.showText("Second line")
                    cs.endText()
                }
            }
            doc.save(file)
        }
        return file
    }

    @Test
    fun readsWordsLinesAndWhereTheyAre() {
        val file = textPdf("one.pdf", listOf("Hello world"))
        PdfTextReader(file).use { reader ->
            val page = reader.page(0)
            assertEquals("Hello world\nSecond line", page.text)
            assertEquals(PDRectangle.A4.width, page.width, 0.01f)
            val h = page.box(0)
            // 100 pt from the left of a 595 pt page; the baseline 700 pt up an 842 pt page.
            assertEquals(100f / 595.28f, h.left, 0.005f)
            val baseline = (842f - 700f) / 841.89f
            assertTrue("top above the baseline: $h", h.top < baseline && h.top > baseline - 25f / 841.89f)
            assertTrue("bottom just below it: $h", h.bottom > baseline && h.bottom < baseline + 8f / 841.89f)
            // The second line is lower down.
            assertTrue(page.box(page.text.indexOf('S')).top > h.bottom)
        }
    }

    @Test
    fun positionsFollowThePagesRotation() {
        // Turned a quarter clockwise: the page shows 842 wide and 595 high, and text written
        // near the top left of the unturned page appears near the top right.
        val file = textPdf("turned.pdf", listOf("Turned"), rotation = 90)
        PdfTextReader(file).use { reader ->
            val page = reader.page(0)
            assertEquals(PDRectangle.A4.height, page.width, 0.01f)
            assertEquals(PDRectangle.A4.width, page.height, 0.01f)
            val t = page.box(0)
            // Display x is the page's y (700 of 842); display y is its x (100 of 595).
            assertEquals(700f / 841.89f, (t.left + t.right) / 2, 0.08f)
            assertEquals(100f / 595.28f, t.bottom, 0.03f)
        }
    }

    @Test
    fun aPageWithoutTextIsEmpty() {
        val file = File(dir, "blank.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.LETTER))
            doc.save(file)
        }
        PdfTextReader(file).use { reader ->
            assertTrue(reader.page(0).isEmpty)
            assertEquals(PDRectangle.LETTER.width, reader.page(0).width, 0.01f)
        }
    }

    @Test
    fun readsDocksInvisibleTextLayerAtTheWordsPlace() {
        val file = File(dir, "layer.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            // On a 1000 x 1414 px image of the page: "Receipt" with its bottom-left at (100, 300), 200 x 40 px.
            PdfTextLayer.apply(doc, mapOf(0 to PdfTextLayer.PageText(1000, 1414, listOf(listOf(PdfTextLayer.Word("Receipt", 100f, 300f, 200f, 40f, 1f, 0f))))))
            doc.save(file)
        }
        PdfTextReader(file).use { reader ->
            val page = reader.page(0)
            assertEquals("Receipt", page.text.trim())
            val rect = page.rects(0, 6).single()
            assertEquals(0.1f, rect.left, 0.01f)
            assertEquals(0.3f, rect.right, 0.01f)
            assertEquals(260f / 1414f, rect.top, 0.01f)
            assertEquals(300f / 1414f, rect.bottom, 0.01f)
        }
    }

    @Test
    fun reorderMovesPagesAndKeepsTheirText() {
        val file = textPdf("three.pdf", listOf("One", "Two", "Three"))
        val out = File(dir, "reordered.pdf")
        PdfTools.reorder(file, out, listOf(2, 0, 1))
        PdfTextReader(out).use { reader ->
            assertEquals(3, reader.pageCount)
            assertEquals(listOf("Three", "One", "Two"), (0 until 3).map { reader.page(it).text.lineSequence().first() })
        }
    }

    @Test
    fun reorderKeepsWhatPagesInheritedFromTheirBranch() {
        // Two pages under an intermediate node that holds their rotation and media box.
        val file = File(dir, "nested.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            doc.addPage(PDPage(PDRectangle.A4))
            val root = doc.pages.cosObject
            val pages = doc.pages.toList()
            val branch = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.PAGES)
                setItem(COSName.PARENT, root)
                setInt(COSName.ROTATE, 90)
                setItem(COSName.MEDIA_BOX, PDRectangle.A5.cosArray)
                setItem(COSName.KIDS, COSArray().apply { pages.forEach { add(it.cosObject) } })
                setInt(COSName.COUNT, 2)
            }
            pages.forEach {
                it.cosObject.removeItem(COSName.MEDIA_BOX)
                it.cosObject.setItem(COSName.PARENT, branch)
            }
            root.setItem(COSName.KIDS, COSArray().apply { add(branch) })
            doc.save(file)
        }
        val out = File(dir, "nested-reordered.pdf")
        PdfTools.reorder(file, out, listOf(1, 0))
        PDDocument.load(out).use { doc ->
            assertEquals(2, doc.numberOfPages)
            doc.pages.forEach { page ->
                assertEquals(90, page.rotation)
                assertEquals(PDRectangle.A5.width, page.mediaBox.width, 0.01f)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun reorderRejectsAnOrderThatLosesPages() {
        val file = textPdf("two.pdf", listOf("A", "B"))
        PdfTools.reorder(file, File(dir, "bad.pdf"), listOf(0, 0))
    }
}
