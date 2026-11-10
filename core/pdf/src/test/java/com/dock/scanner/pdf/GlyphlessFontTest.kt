package com.dock.scanner.pdf

import org.junit.Assert.assertNotNull
import org.junit.Test

class GlyphlessFontTest {

    /**
     * R8 renames PdfTextLayer in release builds, so a path relative to the class stops resolving
     * there, and only there: OCR then fails after reading the last page. The font has to be found
     * by its full path, which doesn't depend on what any class is called.
     */
    @Test
    fun fontIsFoundByItsFullPath() {
        assertNotNull(ClassLoader.getSystemClassLoader().getResourceAsStream(PdfTextLayer.GLYPHLESS_FONT))
    }
}
