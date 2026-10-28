package com.dock.scanner.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dock.scanner.R
import com.dock.scanner.ui.theme.DockMotion
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** Colors of the frosted chrome, matched to braim's island. */
object GlassColors {
    val isDark: Boolean
        @Composable get() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    /** Translucent tint over the blurred backdrop. */
    val fill: Color
        @Composable get() = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.52f)
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.52f)

    /** Pill behind the selected tab: Material 3's navigation indicator role. */
    val selected: Color
        @Composable get() = MaterialTheme.colorScheme.secondaryContainer
}

/**
 * A floating frosted-glass surface: content behind it (anything marked with
 * `hazeSource(state)`) is blurred, tinted, and edged with a hairline, with a soft shadow
 * underneath. On Android 11 and below, where live blur isn't available, it falls back to
 * a near-opaque tint so text stays legible.
 */
@Composable
fun Modifier.glass(state: HazeState, shape: Shape, elevation: Dp = 14.dp): Modifier {
    val surface = MaterialTheme.colorScheme.surface
    val tint = GlassColors.fill
    val outline = MaterialTheme.colorScheme.outlineVariant
    return this
        .shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = 0.22f), spotColor = Color.Black.copy(alpha = 0.22f))
        .clip(shape)
        .hazeEffect(state) {
            backgroundColor = surface
            blurRadius = 24.dp
            noiseFactor = 0f
            tints = listOf(HazeTint(tint))
            fallbackTint = HazeTint(surface.copy(alpha = 0.94f))
        }
        .border(Dp.Hairline, outline, shape)
}

/**
 * Status-bar edge effect: content scrolling up under the status bar fades into a soft
 * progressive blur instead of colliding with the system icons. No hard divider line.
 */
@Composable
fun StatusBarBlur(state: HazeState, modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        modifier
            .fillMaxWidth()
            .height(statusBar + 20.dp)
            .hazeEffect(state) {
                backgroundColor = surface
                blurRadius = 16.dp
                noiseFactor = 0f
                tints = listOf(HazeTint(surface.copy(alpha = 0.6f)))
                progressive = HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f)
            },
    )
}

enum class MainTab(val label: String, @param:DrawableRes val icon: Int, @param:DrawableRes val activeIcon: Int) {
    Home("Home", R.drawable.ic_home_outline, R.drawable.ic_home),
    Images("Images", R.drawable.ic_image_outline, R.drawable.ic_image),
    Files("Files", R.drawable.ic_doc_outline, R.drawable.ic_doc),
    Shelf("Shelf", R.drawable.ic_folder_outline, R.drawable.ic_folder),
}

/** The floating tab island. The selected tab grows a pill with its label. */
@Composable
fun GlassIsland(
    state: HazeState,
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .glass(state, CircleShape)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MainTab.entries.forEach { tab ->
            IslandTab(tab, tab == selected, onClick = { onSelect(tab) })
        }
    }
}

@Composable
private fun IslandTab(tab: MainTab, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Feedback starts on touch-down, not on release.
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, DockMotion.press(), label = "tabScale")
    val fill by animateColorAsState(if (selected) GlassColors.selected else Color.Transparent, DockMotion.standard(), label = "tabFill")
    val horizontal by animateDpAsState(if (selected) 14.dp else 11.dp, DockMotion.standard(), label = "tabPad")
    val ink = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        Modifier
            .padding(horizontal = 2.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(fill)
            .selectable(selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = horizontal, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(if (selected) tab.activeIcon else tab.icon),
            contentDescription = if (selected) null else tab.label,
            tint = ink,
            modifier = Modifier.size(22.dp),
        )
        AnimatedVisibility(
            visible = selected,
            enter = expandHorizontally(DockMotion.standard()) + fadeIn(DockMotion.standard()),
            exit = shrinkHorizontally(DockMotion.standard()) + fadeOut(DockMotion.standard()),
        ) {
            Text(
                tab.label,
                color = ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.1.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Round frosted action button that sits beside the island (braim's pencil; here, the camera). */
@Composable
fun GlassBubble(
    state: HazeState,
    painter: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 60.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, DockMotion.press(), label = "bubbleScale")
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(size)
            .glass(state, CircleShape)
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(26.dp))
    }
}

/** Flat round chrome button for headers (search, new folder): tonal, no glass, since it scrolls with content. */
@Composable
fun ChromeButton(
    painter: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, DockMotion.press(), label = "chromeScale")
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(42.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
    }
}
