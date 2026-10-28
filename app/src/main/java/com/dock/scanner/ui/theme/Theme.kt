package com.dock.scanner.ui.theme

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Full fallback schemes (Android 11 and below) generated from the Dock blue #1E5AA8 with
 * Material's tonal-spot algorithm, the same one dynamic color uses on Android 12+. */
private val LightColors = lightColorScheme(
    primary = Color(0xFF415F91),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF274777),
    secondary = Color(0xFF565F71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF3E4759),
    tertiary = Color(0xFF705575),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFAD8FD),
    onTertiaryContainer = Color(0xFF573E5C),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474E),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2E3036),
    inverseOnSurface = Color(0xFFF0F0F7),
    inversePrimary = Color(0xFFAAC7FF),
    surfaceDim = Color(0xFFD9D9E0),
    surfaceBright = Color(0xFFF9F9FF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE2E2E9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAAC7FF),
    onPrimary = Color(0xFF0A305F),
    primaryContainer = Color(0xFF274777),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283141),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFFDCBCE0),
    onTertiary = Color(0xFF3F2844),
    tertiaryContainer = Color(0xFF573E5C),
    onTertiaryContainer = Color(0xFFFAD8FD),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474E),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF2E3036),
    inversePrimary = Color(0xFF415F91),
    surfaceDim = Color(0xFF111318),
    surfaceBright = Color(0xFF37393E),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
)

/** Material 3 shape scale. Components pick these up automatically; custom surfaces use them too. */
private val DockShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Colors outside the scheme on purpose. */
object DockColors {
    /** Scanned pages and sheets are paper: white in light and dark theme alike. */
    val Paper = Color.White
}

/**
 * Size-specific tracking and leading: large titles are tight (negative tracking, short
 * leading), body text sits near zero, small labels get a little extra room.
 */
private val DockTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        titleLarge = titleLarge.copy(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleMedium = titleMedium.copy(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
        titleSmall = titleSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
        bodyLarge = bodyLarge.copy(letterSpacing = 0.sp, lineHeight = 23.sp),
        bodyMedium = bodyMedium.copy(letterSpacing = 0.sp, lineHeight = 20.sp),
        bodySmall = bodySmall.copy(fontSize = 13.sp, letterSpacing = 0.1.sp),
        labelLarge = labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    )
}

/** Motion tokens: critically damped springs by default; no overshoot unless a gesture threw something. */
object DockMotion {
    /** ~0.3 s response, no bounce: the default for anything that moves. */
    fun <T> standard(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)

    /** Quick press feedback. */
    fun <T> press(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)
}

@Composable
fun DockTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = DockTypography, shapes = DockShapes, content = content)
}

/** A container color and the content color that sits on it. */
data class Accent(val container: Color, val onContainer: Color)

/**
 * Per-tool accent colors (Material "custom colors"): each tool's hue as a tonal palette,
 * tone 90/35 in light theme and 30/85 in dark, so every pair meets contrast guidelines.
 */
enum class ToolAccent(val light: Accent, val dark: Accent) {
    Scan(light = Accent(Color(0xFFD3E4FF), Color(0xFF005494)), dark = Accent(Color(0xFF004881), Color(0xFFBBD6FF))),
    Edit(light = Accent(Color(0xFFEBDCFF), Color(0xFF633BA5)), dark = Accent(Color(0xFF572E99), Color(0xFFE0CCFF))),
    Compress(light = Accent(Color(0xFFFFDAD6), Color(0xFFA90111)), dark = Accent(Color(0xFF93000D), Color(0xFFFFC7C1))),
    ToImages(light = Accent(Color(0xFFFFDDB5), Color(0xFF734A00)), dark = Accent(Color(0xFF643F00), Color(0xFFFFCB8A))),
    Merge(light = Accent(Color(0xFFFDD6FF), Color(0xFF871AA3)), dark = Accent(Color(0xFF790096), Color(0xFFF8C3FF))),
    Split(light = Accent(Color(0xFFFFDCC2), Color(0xFF7E4300)), dark = Accent(Color(0xFF6D3A00), Color(0xFFFFCAA0))),
    ImagesToPdf(light = Accent(Color(0xFF98F994), Color(0xFF006017)), dark = Accent(Color(0xFF005313), Color(0xFF8AEA87))),
    Documents(light = Accent(Color(0xFFA6EEFF), Color(0xFF00626F)), dark = Accent(Color(0xFF004F59), Color(0xFF83D2E3))),
    Resize(light = Accent(Color(0xFFDEE0FF), Color(0xFF3E4A9C)), dark = Accent(Color(0xFF253283), Color(0xFFBAC3FF))),
}

@Composable
fun toolAccent(accent: ToolAccent): Accent = if (isSystemInDarkTheme()) accent.dark else accent.light
