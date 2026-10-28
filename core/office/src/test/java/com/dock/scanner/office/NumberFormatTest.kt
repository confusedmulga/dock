package com.dock.scanner.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class NumberFormatTest {

    private fun f(v: Double, code: String) = NumberFormat.format(v, code)

    @Test
    fun numbers() {
        Locale.setDefault(Locale.US)
        assertEquals("1,234.50", f(1234.5, "#,##0.00"))
        assertEquals("1235", f(1234.5, "0"))
        assertEquals("0.50", f(0.5, "0.00"))
        assertEquals(".5", f(0.5, "#.##"))
        assertEquals("26%", f(0.256, "0%"))
        assertEquals("25.6%", f(0.256, "0.0%"))
        assertEquals("$1,234.50", f(1234.5, "\$#,##0.00"))
        assertEquals("(1,235)", f(-1234.5, "#,##0;(#,##0)"))
        assertEquals("-1,235", f(-1234.5, "#,##0"))
        assertEquals("1.23E+04", f(12345.0, "0.00E+00"))
        assertEquals("€12.00", f(12.0, "[\$€-407]#,##0.00"))
        assertEquals("12 kg", f(12.0, "0 \"kg\""))
        assertEquals("1,235K", f(1234567.0, "#,##0,\"K\""))
        assertEquals("zero", f(0.0, "0;-0;\"zero\""))
        assertEquals("1 1/2", f(1.5, "# ?/?"))
    }

    @Test
    fun general() {
        assertEquals("42", NumberFormat.general(42.0))
        assertEquals("0.3", NumberFormat.general(0.1 + 0.2))
        assertEquals("-3.5", NumberFormat.general(-3.5))
        assertTrue(NumberFormat.general(1.5e20).contains("E+"))
    }

    @Test
    fun dates() {
        Locale.setDefault(Locale.US)
        // 2023-03-15 is day 45000 in the 1900 system.
        assertEquals("2023-03-15", f(45000.0, "yyyy-mm-dd"))
        assertEquals("15-Mar-23", f(45000.0, "d-mmm-yy"))
        assertEquals("3/15/2023", f(45000.0, "m/d/yyyy"))
        assertEquals("Wednesday, March 15, 2023", f(45000.0, "dddd, mmmm d, yyyy"))
        assertEquals("6:30 PM", f(45000.0 + 18.5 / 24, "h:mm AM/PM"))
        assertEquals("18:30:00", f(45000.0 + 18.5 / 24, "hh:mm:ss"))
        assertEquals("36:00:00", f(1.5, "[h]:mm:ss"))
        assertTrue(NumberFormat.isDate("yyyy-mm-dd"))
        assertTrue(!NumberFormat.isDate("#,##0.00"))
    }
}
