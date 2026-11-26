package com.dock.scanner.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** Menus round off like the rest of the app's surfaces; their rows' press highlight is rounded and inset to match. */
private val MenuShape = RoundedCornerShape(20.dp)
private val MenuItemShape = RoundedCornerShape(14.dp)

/** Material's drop-down menu with Dock's rounded corners. Fill it with [DockMenuItem]s. */
@Composable
fun DockDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.padding(horizontal = 6.dp),
        offset = offset,
        shape = MenuShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp,
        content = content,
    )
}

/** A row of a [DockDropdownMenu]. */
@Composable
fun DockMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    DropdownMenuItem(
        text = text,
        onClick = onClick,
        modifier = modifier.clip(MenuItemShape),
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 14.dp),
        colors = MenuDefaults.itemColors(),
    )
}

/** A hairline between groups of a [DockDropdownMenu]'s rows. */
@Composable
fun DockMenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
}
