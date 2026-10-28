package com.dock.scanner.office

import kotlin.math.abs
import kotlin.math.roundToInt

internal object Colors {

    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    /** "FF0000" (RGB) or "FFFF0000" (ARGB, as spreadsheets write it); null for "auto" or junk. */
    fun hex(value: String?): Int? {
        val v = value?.trim()?.removePrefix("#") ?: return null
        return when (v.length) {
            6 -> v.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
            // Spreadsheets often write an alpha of 00 and mean opaque.
            8 -> v.toLongOrNull(16)?.let { (0xFF000000 or (it and 0xFFFFFF)).toInt() }
            else -> null
        }
    }

    fun rgb(r: Int, g: Int, b: Int, a: Int = 255): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
    fun alpha(c: Int) = (c ushr 24) and 0xFF

    /** Colors named by Word's text highlighter. */
    val HIGHLIGHTS = mapOf(
        "yellow" to 0xFFFFFF00.toInt(), "green" to 0xFF00FF00.toInt(), "cyan" to 0xFF00FFFF.toInt(),
        "magenta" to 0xFFFF00FF.toInt(), "blue" to 0xFF0000FF.toInt(), "red" to 0xFFFF0000.toInt(),
        "darkBlue" to 0xFF000080.toInt(), "darkCyan" to 0xFF008080.toInt(), "darkGreen" to 0xFF008000.toInt(),
        "darkMagenta" to 0xFF800080.toInt(), "darkRed" to 0xFF800000.toInt(), "darkYellow" to 0xFF808000.toInt(),
        "darkGray" to 0xFF808080.toInt(), "lightGray" to 0xFFC0C0C0.toInt(), "black" to BLACK, "white" to WHITE,
    )

    /** DrawingML preset colors that turn up in real files. */
    private val PRESETS = mapOf(
        "black" to BLACK, "white" to WHITE, "red" to 0xFFFF0000.toInt(), "green" to 0xFF008000.toInt(),
        "blue" to 0xFF0000FF.toInt(), "yellow" to 0xFFFFFF00.toInt(), "gray" to 0xFF808080.toInt(),
        "grey" to 0xFF808080.toInt(), "orange" to 0xFFFFA500.toInt(), "purple" to 0xFF800080.toInt(),
        "darkGray" to 0xFFA9A9A9.toInt(), "lightGray" to 0xFFD3D3D3.toInt(), "navy" to 0xFF000080.toInt(),
    )

    /** The legacy 64-color palette spreadsheets refer to by index. */
    val INDEXED: IntArray = intArrayOf(
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
        0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
        0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
        0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
        0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
        0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333,
    ).map { it or 0xFF000000.toInt() }.toIntArray()

    fun preset(name: String?): Int? = name?.let { PRESETS[it] }

    // region HSL

    private fun toHsl(c: Int): FloatArray {
        val r = red(c) / 255f
        val g = green(c) / 255f
        val b = blue(c) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2
        if (max == min) return floatArrayOf(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2 - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + (if (g < b) 6 else 0))
            g -> ((b - r) / d + 2)
            else -> ((r - g) / d + 4)
        } / 6
        return floatArrayOf(h, s, l)
    }

    private fun fromHsl(h: Float, s: Float, l: Float, alpha: Int): Int {
        if (s == 0f) {
            val v = (l * 255).roundToInt()
            return rgb(v, v, v, alpha)
        }
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun hue(t0: Float): Float {
            var t = t0
            if (t < 0) t += 1
            if (t > 1) t -= 1
            return when {
                t < 1f / 6 -> p + (q - p) * 6 * t
                t < 1f / 2 -> q
                t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
                else -> p
            }
        }
        return rgb(
            (hue(h + 1f / 3) * 255).roundToInt(),
            (hue(h) * 255).roundToInt(),
            (hue(h - 1f / 3) * 255).roundToInt(),
            alpha,
        )
    }

    /** DrawingML luminance modulation/offset, fractions of 1. */
    fun lum(c: Int, mod: Float, off: Float): Int {
        val (h, s, l) = toHsl(c)
        return fromHsl(h, s, (l * mod + off).coerceIn(0f, 1f), alpha(c))
    }

    fun satMod(c: Int, mod: Float): Int {
        val (h, s, l) = toHsl(c)
        return fromHsl(h, (s * mod).coerceIn(0f, 1f), l, alpha(c))
    }

    /** Spreadsheet tint: -1 (darker) .. 1 (lighter), applied to luminance. */
    fun excelTint(c: Int, tint: Float): Int {
        if (abs(tint) < 0.001f) return c
        val (h, s, l) = toHsl(c)
        val newL = if (tint < 0) l * (1 + tint) else l * (1 - tint) + tint
        return fromHsl(h, s, newL.coerceIn(0f, 1f), alpha(c))
    }

    // endregion

    /** DrawingML tint: keeps [amount] of the color and fills the rest with white. */
    fun tint(c: Int, amount: Float): Int = rgb(
        (red(c) * amount + 255 * (1 - amount)).roundToInt(),
        (green(c) * amount + 255 * (1 - amount)).roundToInt(),
        (blue(c) * amount + 255 * (1 - amount)).roundToInt(),
        alpha(c),
    )

    /** DrawingML shade: keeps [amount] of the color, toward black. */
    fun shade(c: Int, amount: Float): Int =
        rgb((red(c) * amount).roundToInt(), (green(c) * amount).roundToInt(), (blue(c) * amount).roundToInt(), alpha(c))

    fun withAlpha(c: Int, alpha: Float): Int = (c and 0xFFFFFF) or ((alpha * 255).roundToInt().coerceIn(0, 255) shl 24)
}

/** A document theme: its palette and its heading/body fonts. */
internal class Theme(val colors: Map<String, Int>, val majorFont: String?, val minorFont: String?) {

    /** Spreadsheet theme colors by index: lt1, dk1, lt2, dk2, accent1-6, hlink, folHlink. */
    fun indexed(index: Int): Int? = SPREADSHEET_ORDER.getOrNull(index)?.let { colors[it] }

    companion object {
        private val SPREADSHEET_ORDER = listOf("lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")

        /** Office's default palette, for files without a theme. */
        val Default = Theme(
            mapOf(
                "dk1" to Colors.BLACK, "lt1" to Colors.WHITE, "dk2" to 0xFF0E2841.toInt(), "lt2" to 0xFFE8E8E8.toInt(),
                "accent1" to 0xFF156082.toInt(), "accent2" to 0xFFE97132.toInt(), "accent3" to 0xFF196B24.toInt(),
                "accent4" to 0xFF0F9ED5.toInt(), "accent5" to 0xFFA02B93.toInt(), "accent6" to 0xFF4EA72E.toInt(),
                "hlink" to 0xFF467886.toInt(), "folHlink" to 0xFF96607D.toInt(),
            ),
            majorFont = null,
            minorFont = null,
        )

        fun parse(root: XNode?): Theme {
            val elements = root?.child("a:themeElements") ?: return Default
            val scheme = elements.child("a:clrScheme")
            val colors = HashMap(Default.colors)
            scheme?.children?.forEach { slot ->
                val key = slot.name.removePrefix("a:")
                val color = slot.child("a:srgbClr")?.attr("val")?.let(Colors::hex)
                    ?: slot.child("a:sysClr")?.let { Colors.hex(it.attr("lastClr")) ?: if (it.attr("val") == "window") Colors.WHITE else Colors.BLACK }
                if (color != null) colors[key] = color
            }
            val fonts = elements.child("a:fontScheme")
            return Theme(
                colors,
                majorFont = fonts?.path("a:majorFont", "a:latin")?.attr("typeface"),
                minorFont = fonts?.path("a:minorFont", "a:latin")?.attr("typeface"),
            )
        }
    }
}

/**
 * Reads a DrawingML color from [parent], the element that contains one (a:solidFill,
 * a:fontRef, ...). [colorMap] maps bg1/tx1/bg2/tx2 to theme slots; [placeholder] is what
 * "phClr" stands for in shape styles.
 */
internal fun drawingColor(parent: XNode?, theme: Theme, colorMap: Map<String, String> = DEFAULT_COLOR_MAP, placeholder: Int? = null): Int? {
    parent ?: return null
    val el = parent.children.firstOrNull { it.name in COLOR_ELEMENTS } ?: return null
    var c: Int = when (el.name) {
        "a:srgbClr" -> Colors.hex(el.attr("val"))
        "a:schemeClr" -> {
            val v = el.attr("val").orEmpty()
            if (v == "phClr") placeholder else theme.colors[colorMap[v] ?: v]
        }
        "a:sysClr" -> Colors.hex(el.attr("lastClr")) ?: if (el.attr("val") == "window") Colors.WHITE else Colors.BLACK
        "a:prstClr" -> Colors.preset(el.attr("val"))
        "a:scrgbClr" -> {
            fun ch(name: String) = ((el.attr(name)?.toFloatOrNull() ?: 0f) / 100_000f * 255).roundToInt()
            Colors.rgb(ch("r"), ch("g"), ch("b"))
        }
        else -> null
    } ?: return null
    var lumMod = 1f
    var lumOff = 0f
    for (m in el.children) {
        val v = (m.attr("val")?.toFloatOrNull() ?: continue) / 100_000f
        when (m.name) {
            "a:lumMod" -> lumMod = v
            "a:lumOff" -> lumOff = v
            "a:tint" -> c = Colors.tint(c, v)
            "a:shade" -> c = Colors.shade(c, v)
            "a:satMod" -> c = Colors.satMod(c, v)
            "a:alpha" -> c = Colors.withAlpha(c, v)
        }
    }
    if (lumMod != 1f || lumOff != 0f) c = Colors.lum(c, lumMod, lumOff)
    return c
}

private val COLOR_ELEMENTS = setOf("a:srgbClr", "a:schemeClr", "a:sysClr", "a:prstClr", "a:scrgbClr")

internal val DEFAULT_COLOR_MAP = mapOf(
    "bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2",
    "accent1" to "accent1", "accent2" to "accent2", "accent3" to "accent3",
    "accent4" to "accent4", "accent5" to "accent5", "accent6" to "accent6",
    "hlink" to "hlink", "folHlink" to "folHlink",
)

/** Picks a generic family for a font name, since the document's own fonts aren't on the phone. */
internal fun fontKind(name: String?): FontKind {
    val n = name?.lowercase() ?: return FontKind.Sans
    return when {
        listOf("mono", "courier", "consolas", "menlo", "code", "typewriter", "lucida console").any { it in n } -> FontKind.Mono
        listOf("times", "serif", "cambria", "georgia", "garamond", "book antiqua", "palatino", "baskerville", "minion", "constantia", "century schoolbook", "bookman")
            .any { it in n } && "sans" !in n -> FontKind.Serif
        else -> FontKind.Sans
    }
}
