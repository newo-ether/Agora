package com.newoether.agora.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.runtime.remember
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy

/*
 * Every dropdown in the app goes through these wrappers, which own the menu geometry: a 24dp menu
 * corner and a 24dp item highlight corner (a capsule at the 48dp item height). Each item's ripple is
 * inset by DROPDOWN_ITEM_INSET from the menu's sides; Material already leaves the same 8dp above the
 * first and below the last item.
 */

/** Corner radius shared by every menu container and every item highlight. */
internal val DROPDOWN_CORNER = 24.dp

internal val DROPDOWN_MENU_SHAPE = RoundedCornerShape(DROPDOWN_CORNER)

internal val DROPDOWN_ITEM_SHAPE = RoundedCornerShape(DROPDOWN_CORNER)

/** Gap between a menu's edge and its items' highlight; equals Material's fixed vertical padding. */
internal val DROPDOWN_ITEM_INSET = 8.dp

/** Material's horizontal item padding (12dp) less the inset, so item text stays where it was. */
private val DROPDOWN_ITEM_CONTENT_PADDING = PaddingValues(horizontal = 12.dp - DROPDOWN_ITEM_INSET)

@Composable
fun AgoraDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    containerColor: Color = MenuDefaults.containerColor,
    tonalElevation: Dp = MenuDefaults.TonalElevation,
    shadowElevation: Dp = MenuDefaults.ShadowElevation,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (com.newoether.agora.ui.motion.LocalAgoraMotionPolicy.current.eInkMode) {
        if (expanded) EinkDropdown(onDismissRequest, modifier, offset, scrollState, properties, content)
        return
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        scrollState = scrollState,
        properties = properties,
        shape = DROPDOWN_MENU_SHAPE,
        containerColor = containerColor,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        border = border,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExposedDropdownMenuBoxScope.AgoraExposedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    matchAnchorWidth: Boolean = true,
    containerColor: Color = MenuDefaults.containerColor,
    tonalElevation: Dp = MenuDefaults.TonalElevation,
    shadowElevation: Dp = MenuDefaults.ShadowElevation,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (com.newoether.agora.ui.motion.LocalAgoraMotionPolicy.current.eInkMode) {
        if (expanded) EinkDropdown(onDismissRequest, modifier, DpOffset.Zero, scrollState,
            PopupProperties(focusable = true), content)
        return
    }
    ExposedDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        scrollState = scrollState,
        matchAnchorWidth = matchAnchorWidth,
        shape = DROPDOWN_MENU_SHAPE,
        containerColor = containerColor,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        border = border,
        content = content,
    )
}

/**
 * A dropdown item whose highlight is inset from the menu sides and clipped to a capsule. Use only
 * inside [AgoraDropdownMenu] or [AgoraExposedDropdownMenu].
 */
@Composable
fun AgoraDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    colors: MenuItemColors = MenuDefaults.itemColors(),
    contentPadding: PaddingValues = DROPDOWN_ITEM_CONTENT_PADDING,
    interactionSource: MutableInteractionSource? = null,
) {
    val eInk = LocalAgoraMotionPolicy.current.eInkMode
    val source = interactionSource ?: remember { MutableInteractionSource() }
    DropdownMenuItem(
        text = text,
        onClick = onClick,
        // Padding and clip come before DropdownMenuItem's own clickable, so the ripple is both
        // inset and clipped to the item shape.
        modifier = modifier
            .padding(horizontal = DROPDOWN_ITEM_INSET)
            .clip(DROPDOWN_ITEM_SHAPE).einkFeedback(source, enabled),
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
        colors = if (eInk) MenuDefaults.itemColors(textColor = Color.Black, leadingIconColor = Color.Black,
            trailingIconColor = Color.Black, disabledTextColor = Color.Black,
            disabledLeadingIconColor = Color.Black, disabledTrailingIconColor = Color.Black) else colors,
        contentPadding = contentPadding,
        interactionSource = source,
    )
}
