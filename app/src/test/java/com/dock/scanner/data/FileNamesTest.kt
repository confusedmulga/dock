package com.dock.scanner.data

import com.dock.scanner.data.storage.withoutExtension
import com.dock.scanner.ui.components.fileNameProblem
import com.dock.scanner.ui.components.isValidFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {

    @Test
    fun aTypedExtensionIsDroppedInAnyCase() {
        // "Report.PDF" used to be saved as "Report.PDF.pdf".
        assertEquals("Report", "Report.PDF".withoutExtension("pdf"))
        assertEquals("Report", "Report.pdf".withoutExtension("pdf"))
        assertEquals("Report", "Report".withoutExtension("pdf"))
        assertEquals("Notes.docx", "Notes.docx".withoutExtension("pdf"))
    }

    @Test
    fun aNameThatIsOnlyTheExtensionIsKept() {
        // Nothing would be left of it: the name rules turn it down instead.
        assertEquals(".pdf", ".pdf".withoutExtension("pdf"))
    }

    @Test
    fun namesThatWouldBeHiddenOrRejectedAreTurnedDown() {
        // The library hides names that start with a dot, so the file would vanish from the lists.
        assertFalse(isValidFileName(".hidden"))
        assertFalse(isValidFileName("  .hidden"))
        assertNotNull(fileNameProblem(".hidden"))
        assertFalse(isValidFileName("a/b"))
        assertFalse(isValidFileName("what?"))
        assertFalse(isValidFileName("   "))
    }

    @Test
    fun ordinaryNamesPassAndAnEmptyFieldShowsNoError() {
        assertTrue(isValidFileName("Scan 2026-11-15 18.32"))
        assertTrue(isValidFileName("Bill (March) v1.2"))
        // Still typing: the button is off, but there's nothing to complain about yet.
        assertNull(fileNameProblem(""))
    }
}
