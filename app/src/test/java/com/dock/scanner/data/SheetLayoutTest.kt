package com.dock.scanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetLayoutTest {

    /** Height of a placed page as a fraction of the sheet's height. */
    private fun SheetLayout.heightOf(p: Placement, imageAspect: Float) = p.width * aspect / imageAspect

    @Test
    fun clampAllKeepsARotatedPageOnTheSheet() {
        // A landscape photo (1.5:1) laid out on landscape A4, then rotated to portrait.
        val layout = SheetLayout(Paper.A4, landscape = true, items = listOf(Placement("a", 0.5f, 0.5f, 0.92f)))
        val rotated = 1 / 1.5f
        assertTrue("precondition: unclamped it overflows", layout.heightOf(layout.items.single(), rotated) > 1f)

        val fitted = layout.clampAll { rotated }
        val p = fitted.items.single()
        val h = fitted.heightOf(p, rotated)
        assertTrue(h <= 1f + 1e-4f)
        assertTrue(p.centerY - h / 2 >= -1e-4f && p.centerY + h / 2 <= 1f + 1e-4f)
        assertTrue(p.centerX - p.width / 2 >= -1e-4f && p.centerX + p.width / 2 <= 1f + 1e-4f)
    }

    @Test
    fun clampAllLeavesPagesThatAlreadyFitOrHaveNoShapeYet() {
        val layout = SheetLayout(
            Paper.A4, landscape = false,
            items = listOf(Placement("a", 0.5f, 0.3f, 0.4f), Placement("b", 0.5f, 0.7f, 0.9f)),
        )
        val fitted = layout.clampAll { id -> if (id == "a") 1 / 1.414f else null }
        assertEquals(layout, fitted)
    }

    @Test
    fun clampFitsAThinStripInsteadOfFailing() {
        // A cropped line of text, 30 times as wide as it is tall, turned on its side on landscape A4:
        // the widest it fits is under the smallest size. That used to ask for a range with its ends swapped, and crash.
        val layout = SheetLayout(Paper.A4, landscape = true, items = listOf(Placement("a", 0.5f, 0.5f, 0.5f)))
        val strip = 1 / 30f
        val p = layout.clamp(layout.items.single(), strip)
        val h = layout.heightOf(p, strip)
        assertTrue("narrower than the usual minimum", p.width < SheetLayout.MIN_WIDTH)
        assertTrue(h <= 1f + 1e-4f)
        assertTrue(p.centerY - h / 2 >= -1e-4f && p.centerY + h / 2 <= 1f + 1e-4f)
    }

    @Test
    fun clampNeverFailsAtTheWidestFit() {
        // Exactly as tall as the sheet: rounding must not turn "fits" into an empty range, whatever the shape.
        for (paper in Paper.entries) for (landscape in listOf(false, true)) {
            val layout = SheetLayout(paper, landscape, items = emptyList())
            for (i in 1..400) {
                val aspect = i / 100f
                val p = layout.clamp(Placement("a", 0.3f, 0.9f, 1f), aspect)
                assertTrue(p.width <= 1f && p.width > 0f)
            }
        }
    }

    @Test
    fun clampNeverMakesAPageSmallerThanTheSlidersMinimum() {
        val layout = SheetLayout(Paper.A4, landscape = false, items = emptyList())
        val p = layout.clamp(Placement("a", 0.5f, 0.5f, 0.01f), 1 / 1.414f)
        assertEquals(SheetLayout.MIN_WIDTH, p.width, 1e-6f)
    }
}
