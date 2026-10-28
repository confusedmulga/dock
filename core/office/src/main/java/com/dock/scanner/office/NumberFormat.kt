package com.dock.scanner.office

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Spreadsheet number formats ("#,##0.00", "0%", "d-mmm-yy", "$#,##0;[Red]-$#,##0", ...),
 * turning a cell's stored number into the text the spreadsheet app shows.
 */
internal object NumberFormat {

    /** The day after 31 December 9999, the last date spreadsheets show. */
    private const val LAST_DATE = 2_958_466.0
    private const val MAX_FRACTION_DIGITS = 5

    /** Formats every spreadsheet app knows by id, so files don't spell them out. */
    val BUILTIN = mapOf(
        0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00",
        5 to "\$#,##0;(\$#,##0)", 6 to "\$#,##0;[Red](\$#,##0)", 7 to "\$#,##0.00;(\$#,##0.00)", 8 to "\$#,##0.00;[Red](\$#,##0.00)",
        9 to "0%", 10 to "0.00%", 11 to "0.00E+00", 12 to "# ?/?", 13 to "# ??/??",
        14 to "m/d/yyyy", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy", 18 to "h:mm AM/PM", 19 to "h:mm:ss AM/PM",
        20 to "h:mm", 21 to "h:mm:ss", 22 to "m/d/yyyy h:mm",
        37 to "#,##0 ;(#,##0)", 38 to "#,##0 ;[Red](#,##0)", 39 to "#,##0.00;(#,##0.00)", 40 to "#,##0.00;[Red](#,##0.00)",
        45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mm:ss.0", 48 to "##0.0E+0", 49 to "@",
    )

    fun format(value: Double, code: String, date1904: Boolean = false): String {
        if (value.isNaN() || value.isInfinite()) return "#NUM!"
        // Format codes come from the file: one this can't apply mustn't take the whole sheet down.
        return runCatching { formatWith(value, code, date1904) }.getOrElse { general(value) }
    }

    private fun formatWith(value: Double, code: String, date1904: Boolean): String {
        val sections = split(code)
        val (section, signHandled) = when {
            sections.size >= 3 && value == 0.0 -> sections[2] to true
            sections.size >= 2 && value < 0 -> sections[1] to true
            else -> sections.first() to false
        }
        val clean = stripConditions(section)
        if (clean.isBlank() && sections.size > 1) return "" // e.g. ";;;" hides the value
        val v = if (signHandled && value < 0) -value else value
        return when {
            clean.trim().equals("General", ignoreCase = true) || clean.trim() == "@" || clean.isBlank() -> general(value)
            isDateSection(clean) -> date(value, clean, date1904)
            else -> number(v, clean, negativeSign = !signHandled)
        }
    }

    fun isDate(code: String): Boolean = isDateSection(stripConditions(split(code).first()))

    // region Parsing helpers

    /** Splits on ';' outside quotes, brackets and escapes. */
    private fun split(code: String): List<String> {
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        var quoted = false
        var bracket = false
        while (i < code.length) {
            val c = code[i]
            when {
                c == '\\' && i + 1 < code.length -> {
                    sb.append(c).append(code[i + 1]); i++
                }
                c == '"' -> { quoted = !quoted; sb.append(c) }
                !quoted && c == '[' -> { bracket = true; sb.append(c) }
                !quoted && c == ']' -> { bracket = false; sb.append(c) }
                !quoted && !bracket && c == ';' -> { parts += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        parts += sb.toString()
        return parts
    }

    /** Removes [Red], [>=100] and locale tags; keeps currency symbols like [$€-407] as "€" and elapsed-time [h]. */
    private fun stripConditions(section: String): String =
        Regex("""\[([^\]]*)]""").replace(section) { m ->
            val inner = m.groupValues[1]
            when {
                inner.startsWith("$") -> "\"" + inner.removePrefix("$").substringBefore('-') + "\""
                inner.lowercase().let { it == "h" || it == "hh" || it == "m" || it == "mm" || it == "s" || it == "ss" } -> m.value
                else -> ""
            }
        }

    /** Literal text and format characters, with quotes, escapes and padding resolved. */
    private sealed interface Token {
        data class Literal(val text: String) : Token
        data class Code(val c: Char) : Token
    }

    private fun tokens(section: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < section.length) {
            val c = section[i]
            when {
                c == '"' -> {
                    val end = section.indexOf('"', i + 1).let { if (it < 0) section.length else it }
                    out += Token.Literal(section.substring(i + 1, end))
                    i = end
                }
                c == '\\' && i + 1 < section.length -> { out += Token.Literal(section[i + 1].toString()); i++ }
                c == '_' && i + 1 < section.length -> { out += Token.Literal(" "); i++ } // space the width of the next char
                c == '*' && i + 1 < section.length -> i++ // fill character: nothing to fill in a preview
                c in "0#?.,%Ee+-/ " || c.isLetter() || c == '[' || c == ']' || c == ':' -> out += Token.Code(c)
                else -> out += Token.Literal(c.toString())
            }
            i++
        }
        return out
    }

    private fun isDateSection(section: String): Boolean {
        val codes = tokens(section).filterIsInstance<Token.Code>().map { it.c.lowercaseChar() }
        return codes.any { it in "ymdhs" } && codes.none { it == '0' || it == '#' || it == '?' } ||
            (codes.any { it in "ymdhs" } && codes.contains(':'))
    }

    // endregion

    // region General

    fun general(value: Double): String {
        if (value == floor(value) && abs(value) < 1e15) return value.toLong().toString()
        val a = abs(value)
        if (a >= 1e11 || a < 1e-9) {
            return String.format(Locale.US, "%.5E", value)
                .replace(Regex("""\.?0+E"""), "E")
                .replace(Regex("E([+-])0*(\\d)"), "E$1$2")
                .let { if ("E-" in it || "E+" in it) it else it.replace("E", "E+") }
        }
        return BigDecimal(value).round(MathContext(11, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString()
    }

    // endregion

    // region Numbers

    private fun number(value: Double, section: String, negativeSign: Boolean): String {
        val toks = tokens(section)
        val codes = toks.filterIsInstance<Token.Code>()
        val percent = codes.count { it.c == '%' }
        var v = value
        repeat(percent) { v *= 100 }

        // Split into the literal prefix, the number pattern, and the literal suffix.
        val first = toks.indexOfFirst { it is Token.Code && it.c in "0#?" }
        if (first < 0) return toks.joinToString("") { if (it is Token.Literal) it.text else if ((it as Token.Code).c == '%') "%" else "" }
        val last = toks.indexOfLast { it is Token.Code && it.c in "0#?" }
        val prefix = literalText(toks.subList(0, first))
        var patternEnd = last + 1
        // Scale commas right after the digits ("#,##0," = thousands) belong to the pattern.
        while (patternEnd < toks.size && (toks[patternEnd] as? Token.Code)?.c == ',') patternEnd++
        val pattern = toks.subList(first, patternEnd).filterIsInstance<Token.Code>().map { it.c }.joinToString("")
        val suffix = literalText(toks.subList(patternEnd, toks.size))

        val body = when {
            'E' in pattern.uppercase() -> scientific(v, pattern)
            '/' in pattern -> fraction(v, pattern)
            else -> decimal(v, pattern)
        }
        val negative = value < 0 && negativeSign && body.any { it in '1'..'9' }
        return (if (negative) "-" else "") + prefix + body.removePrefix("-") + suffix
    }

    private fun literalText(toks: List<Token>): String = toks.joinToString("") {
        when (it) {
            is Token.Literal -> it.text
            is Token.Code -> when (it.c) {
                '%' -> "%"
                ' ' -> " "
                '-', '+', '(', ')', ':', '/' -> it.c.toString()
                else -> if (it.c.isLetter()) it.c.toString() else ""
            }
        }
    }

    private fun decimal(value: Double, pattern: String): String {
        var p = pattern
        var scale = 0
        while (p.endsWith(',')) { scale++; p = p.dropLast(1) }
        var v = abs(value)
        repeat(scale) { v /= 1000 }
        val intPart = p.substringBefore('.')
        val fracPart = if ('.' in p) p.substringAfter('.').filter { it in "0#?" } else ""
        val grouping = ',' in intPart
        val minInt = intPart.count { it == '0' }
        val minFrac = fracPart.count { it == '0' }
        val maxFrac = fracPart.length

        // Excel works to 15 significant digits, so 2.675 (stored as 2.67499999...) shows as 2.68.
        val rounded = BigDecimal(v).round(MathContext(15, RoundingMode.HALF_EVEN)).setScale(maxFrac, RoundingMode.HALF_UP)
        var digits = rounded.toPlainString()
        var whole = digits.substringBefore('.')
        var frac = if ('.' in digits) digits.substringAfter('.') else ""
        frac = frac.trimEnd('0').padEnd(minFrac, '0')
        if (whole == "0" && minInt == 0) whole = ""
        whole = whole.padStart(minInt, '0')
        if (grouping) whole = group(whole)
        digits = if (frac.isEmpty()) whole else "$whole.$frac"
        if (digits.isEmpty()) digits = "0".takeIf { minInt > 0 || minFrac > 0 } ?: ""
        return digits.ifEmpty { if (maxFrac > 0) "." else "" }
    }

    private fun group(digits: String): String {
        if (digits.length <= 3) return digits
        val sb = StringBuilder()
        val start = digits.length % 3
        if (start > 0) sb.append(digits, 0, start)
        var i = start
        while (i < digits.length) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(digits, i, i + 3)
            i += 3
        }
        return sb.toString()
    }

    private fun scientific(value: Double, pattern: String): String {
        val mantissaPattern = pattern.substringBefore('E').substringBefore('e')
        val decimals = mantissaPattern.substringAfter('.', "").count { it in "0#?" }
        val expDigits = pattern.substringAfter('E', pattern.substringAfter('e')).count { it == '0' }.coerceAtLeast(1)
        if (value == 0.0) return "0" + (if (decimals > 0) "." + "0".repeat(decimals) else "") + "E+" + "0".repeat(expDigits)
        val s = String.format(Locale.US, "%.${decimals}E", abs(value))
        val mantissa = s.substringBefore('E')
        val exp = s.substringAfter('E').toInt()
        return mantissa + "E" + (if (exp < 0) "-" else "+") + abs(exp).toString().padStart(expDigits, '0')
    }

    private fun fraction(value: Double, pattern: String): String {
        val denPattern = pattern.substringAfter('/')
        val fixedDen = denPattern.filter { it.isDigit() }.toIntOrNull()?.takeIf { it > 0 }
        // Every denominator up to the limit is tried, so cap the digits a file can ask for.
        val digits = denPattern.count { it in "0#?" }.coerceIn(1, MAX_FRACTION_DIGITS)
        val maxDen = if (fixedDen != null) fixedDen else (Math.pow(10.0, digits.toDouble()) - 1).toInt()
        val v = abs(value)
        val hasWhole = pattern.substringBefore('/').trim().contains(' ')
        val whole = if (hasWhole) floor(v).toLong() else 0L
        val frac = v - whole
        var bestNum = 0L
        var bestDen = 1L
        if (fixedDen != null) {
            bestNum = (frac * fixedDen).roundToLong()
            bestDen = fixedDen.toLong()
        } else {
            var bestErr = Double.MAX_VALUE
            for (den in 1..maxDen) {
                val num = (frac * den).roundToLong()
                val err = abs(frac - num.toDouble() / den)
                if (err < bestErr - 1e-12) { bestErr = err; bestNum = num; bestDen = den.toLong() }
            }
        }
        return when {
            bestNum == 0L -> whole.toString()
            hasWhole && whole > 0 -> "$whole $bestNum/$bestDen"
            else -> "${bestNum + whole * bestDen}/$bestDen"
        }
    }

    // endregion

    // region Dates

    /** Spreadsheet day number to date-time; Excel counts 1900 as a leap year, which is copied here. */
    fun toDateTime(serial: Double, date1904: Boolean): LocalDateTime {
        val days = floor(serial).toLong()
        val millis = ((serial - days) * 86_400_000).roundToLong()
        val base = when {
            date1904 -> LocalDateTime.of(1904, 1, 1, 0, 0)
            days >= 61 -> LocalDateTime.of(1899, 12, 30, 0, 0)
            else -> LocalDateTime.of(1899, 12, 31, 0, 0)
        }
        return base.plusDays(days).plusNanos(millis * 1_000_000)
    }

    private fun date(value: Double, section: String, date1904: Boolean): String {
        // Before the first day or after 31 Dec 9999: spreadsheet apps show hashes.
        if (value < 0 || value >= LAST_DATE) return "#".repeat(8)
        val toks = tokens(section)
        val codes = toks.filterIsInstance<Token.Code>()
        val twelveHour = Regex("(?i)am/pm|a/p").containsMatchIn(codes.joinToString("") { it.c.toString() })
        val showsFraction = Regex("""s\.0""", RegexOption.IGNORE_CASE).containsMatchIn(section)
        val dt = toDateTime(if (showsFraction) value else value + 0.5 / 86_400, date1904).let {
            if (showsFraction) it else it.withNano(0)
        }
        val locale = Locale.getDefault()

        // Group consecutive identical letters into runs, keeping literals between them.
        data class Part(val code: String?, val literal: String?)
        val parts = ArrayList<Part>()
        var i = 0
        while (i < toks.size) {
            when (val t = toks[i]) {
                is Token.Literal -> { parts += Part(null, t.text); i++ }
                is Token.Code -> {
                    val c = t.c
                    if (c == '[') {
                        val close = toks.subList(i, toks.size).indexOfFirst { it is Token.Code && it.c == ']' }
                        if (close > 0) {
                            parts += Part("[" + toks.subList(i + 1, i + close).joinToString("") { (it as? Token.Code)?.c?.toString() ?: "" } + "]", null)
                            i += close + 1
                            continue
                        }
                    }
                    val lower = c.lowercaseChar()
                    if (lower == 'a' && codesAt(toks, i, "AM/PM")) {
                        parts += Part("AM/PM", null); i += 5; continue
                    }
                    if (lower == 'a' && codesAt(toks, i, "A/P")) {
                        parts += Part("A/P", null); i += 3; continue
                    }
                    if (lower in "ymdhs") {
                        var j = i
                        while (j < toks.size && (toks[j] as? Token.Code)?.c?.lowercaseChar() == lower) j++
                        parts += Part(lower.toString().repeat(j - i), null)
                        i = j
                        continue
                    }
                    if (c == '.' && i + 1 < toks.size && (toks[i + 1] as? Token.Code)?.c == '0') {
                        var j = i + 1
                        while (j < toks.size && (toks[j] as? Token.Code)?.c == '0') j++
                        parts += Part("." + "0".repeat(j - i - 1), null)
                        i = j
                        continue
                    }
                    parts += Part(null, c.toString())
                    i++
                }
            }
        }

        val totalHours = floor(value * 24).toLong()
        return buildString {
            parts.forEachIndexed { index, part ->
                val code = part.code
                if (code == null) { append(part.literal); return@forEachIndexed }
                // "m" means minutes right after hours or right before seconds.
                fun minutes(): Boolean {
                    val prev = parts.subList(0, index).lastOrNull { it.code != null }?.code
                    val next = parts.subList(index + 1, parts.size).firstOrNull { it.code != null }?.code
                    return prev?.startsWith("h") == true || prev?.startsWith("[h") == true || next?.startsWith("s") == true
                }
                when {
                    code.startsWith("[") -> when (code.lowercase().trim('[', ']').first()) {
                        'h' -> append(totalHours)
                        'm' -> append(floor(value * 1440).toLong())
                        's' -> append(floor(value * 86_400).toLong())
                    }
                    code == "AM/PM" -> append(if (dt.hour < 12) "AM" else "PM")
                    code == "A/P" -> append(if (dt.hour < 12) "A" else "P")
                    code.startsWith(".") -> append("." + (dt.nano / 1_000_000).toString().padStart(3, '0').take(code.length - 1))
                    code[0] == 'y' -> append(if (code.length <= 2) (dt.year % 100).toString().padStart(2, '0') else dt.year.toString())
                    code[0] == 'm' && code.length <= 2 && minutes() -> append(dt.minute.toString().padStart(code.length, '0'))
                    code[0] == 'm' -> append(
                        when (code.length) {
                            1 -> dt.monthValue.toString()
                            2 -> dt.monthValue.toString().padStart(2, '0')
                            3 -> dt.month.getDisplayName(DateTextStyle.SHORT, locale)
                            5 -> dt.month.getDisplayName(DateTextStyle.NARROW, locale)
                            else -> dt.month.getDisplayName(DateTextStyle.FULL, locale)
                        },
                    )
                    code[0] == 'd' -> append(
                        when (code.length) {
                            1 -> dt.dayOfMonth.toString()
                            2 -> dt.dayOfMonth.toString().padStart(2, '0')
                            3 -> dt.dayOfWeek.getDisplayName(DateTextStyle.SHORT, locale)
                            else -> dt.dayOfWeek.getDisplayName(DateTextStyle.FULL, locale)
                        },
                    )
                    code[0] == 'h' -> {
                        val h = if (twelveHour) (dt.hour % 12).let { if (it == 0) 12 else it } else dt.hour
                        append(h.toString().padStart(code.length.coerceAtMost(2), '0'))
                    }
                    code[0] == 's' -> append(dt.second.toString().padStart(code.length.coerceAtMost(2), '0'))
                }
            }
        }
    }

    /** Whether the format characters from [index] spell [word] (ignoring case). */
    private fun codesAt(toks: List<Token>, index: Int, word: String): Boolean =
        word.indices.all { k -> (toks.getOrNull(index + k) as? Token.Code)?.c?.equals(word[k], ignoreCase = true) == true }

    // endregion
}
