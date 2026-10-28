package com.dock.scanner.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfToolsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "pdftools-test").apply { deleteRecursively(); mkdirs() }

    @Before
    fun init() = PdfTools.init(context)

    /** An A4 PDF whose pages each hold a large, high-quality photo plus a line of real text. */
    private fun photoPdf(name: String, pages: Int): File {
        val file = File(dir, name)
        PDDocument().use { doc ->
            repeat(pages) { i ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                val bitmap = Bitmap.createBitmap(2480, 3508, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).apply {
                    drawPaint(Paint().apply {
                        shader = LinearGradient(0f, 0f, 2480f, 3508f, Color.rgb(250, 240, 220), Color.rgb(60, 90, 160), Shader.TileMode.CLAMP)
                    })
                    val dots = Paint(Paint.ANTI_ALIAS_FLAG)
                    for (k in 0 until 4000) {
                        dots.color = Color.rgb((k * 37) % 255, (k * 91) % 255, (k * 53) % 255)
                        drawCircle((k * 613 % 2480).toFloat(), (k * 397 % 3508).toFloat(), 6f + k % 9, dots)
                    }
                }
                bitmap.setHasAlpha(false) // an opaque photo, like a scan
                val image = JPEGFactory.createFromImage(doc, bitmap, 0.95f)
                bitmap.recycle()
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(image, 0f, 0f, PDRectangle.A4.width, PDRectangle.A4.height)
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 14f)
                    cs.newLineAtOffset(50f, 50f)
                    cs.showText("Page ${i + 1}")
                    cs.endText()
                }
            }
            doc.save(file)
        }
        return file
    }

    @Test
    fun compressShrinksImagesAndKeepsPages() {
        val input = photoPdf("photos.pdf", pages = 2)
        val output = File(dir, "photos-small.pdf")
        val result = PdfTools.compress(input, output, PdfTools.CompressSettings(dpi = 100, quality = 0.6f))
        assertTrue("expected smaller: $result", result.improved && result.savedFraction > 0.5f)
        assertEquals(2, result.imagesChanged)
        assertEquals(2, PdfTools.pageCount(output))
    }

    @Test
    fun compressLeavesAlreadySmallImagesAlone() {
        val input = photoPdf("photos.pdf", pages = 1)
        val once = File(dir, "once.pdf")
        PdfTools.compress(input, once, PdfTools.CompressSettings(dpi = 72, quality = 0.4f))
        val twice = File(dir, "twice.pdf")
        val again = PdfTools.compress(once, twice, PdfTools.CompressSettings(dpi = 200, quality = 0.9f))
        // Higher settings than the file already has must not inflate it.
        assertEquals(0, again.imagesChanged)
    }

    @Test
    fun compressToSizeFitsTheTargetAsSharplyAsPossible() {
        val input = photoPdf("photos.pdf", pages = 2)
        val target = input.length() / 5
        val output = File(dir, "target.pdf")
        val result = PdfTools.compressToSize(input, output, target)
        assertTrue("expected under $target: $result", result.reachedTarget && output.length() <= target)
        assertEquals(output.length(), result.result.compressedBytes)
        assertEquals(2, PdfTools.pageCount(output))
        // The next sharper step must have been too big, or this is the sharpest there is.
        val index = PdfTools.SIZE_STEPS.indexOf(result.settings)
        if (index > 0) {
            val sharper = File(dir, "sharper.pdf")
            assertTrue(PdfTools.compress(input, sharper, PdfTools.SIZE_STEPS[index - 1]).compressedBytes > target)
        }
    }

    @Test
    fun compressToSizeFallsBackToTheSmallestResult() {
        val input = photoPdf("photos.pdf", pages = 1)
        val output = File(dir, "tiny.pdf")
        val result = PdfTools.compressToSize(input, output, targetBytes = 1)
        assertTrue(!result.reachedTarget)
        assertEquals(PdfTools.SIZE_STEPS.last(), result.settings)
        assertTrue(output.length() in 1 until input.length())
    }

    @Test
    fun mergeAppendsInOrder() {
        val a = photoPdf("a.pdf", 2)
        val b = photoPdf("b.pdf", 1)
        val out = File(dir, "merged.pdf")
        PdfTools.merge(listOf(a, b), out)
        assertEquals(3, PdfTools.pageCount(out))
    }

    @Test
    fun splitWritesOneFilePerRange() {
        val input = photoPdf("five.pdf", 5)
        val outs = mutableListOf<File>()
        PdfTools.split(input, listOf(1..2, 5..5)) { range -> File(dir, "part_${range.first}.pdf").also(outs::add) }
        assertEquals(listOf(2, 1), outs.map { PdfTools.pageCount(it) })
    }

    @Test
    fun parsesPageRanges() {
        assertEquals(listOf(1..3, 5..5, 8..10), PdfTools.parseRanges("1-3, 5, 8-", 10))
        assertEquals(listOf(1..4), PdfTools.parseRanges("-4", 10))
        assertNull(PdfTools.parseRanges("0-2", 10))
        assertNull(PdfTools.parseRanges("3-11", 10))
        assertNull(PdfTools.parseRanges("abc", 10))
        assertNull(PdfTools.parseRanges("", 10))
    }
}
