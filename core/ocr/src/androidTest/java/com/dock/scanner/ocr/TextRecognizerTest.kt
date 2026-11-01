package com.dock.scanner.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextRecognizerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val lines = listOf(
        "Invoice No. 2026-0417, due 14 March 2026",
        "Laptop stand, aluminium   2   $51.25",
        "Grüße aus Zürich, façade, señor",
    )

    /** A page-like image: dark serif text on white, 48 px type (about 11 pt at 300 dpi). */
    private fun page(): Bitmap = Bitmap.createBitmap(1700, 900, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(25, 25, 25)
            textSize = 48f
            typeface = Typeface.SERIF
        }
        val canvas = Canvas(this)
        lines.forEachIndexed { i, line -> canvas.drawText(line, 120f, 200f + i * 120f, paint) }
    }

    @Test
    fun readsPrintedText() {
        val result = TextRecognizer.load(context).use { it.recognize(page()) }
        val read = result.lines.map { it.text.replace(Regex(" +"), " ") }
        assertEquals(lines.map { it.replace(Regex(" +"), " ") }, read)
    }

    @Test
    fun placesWordsWhereTheyAre() {
        val result = TextRecognizer.load(context).use { it.recognize(page()) }
        val invoice = result.lines.first().words.first()
        assertEquals("Invoice", invoice.text)
        // Drawn from x = 120 with its baseline at y = 200; the box reaches a little past both.
        assertTrue("left ${invoice.x}", invoice.x in 100f..130f)
        assertTrue("bottom ${invoice.y}", invoice.y in 200f..230f)
        assertTrue("width ${invoice.width}", invoice.width in 140f..210f)
        assertEquals(1f, invoice.cos, 0.01f)
    }
}
