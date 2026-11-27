package com.newoether.agora.ui.chat.interaction

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/*
 * Material Symbols Outlined `collapse_content` and `expand_content` (24dp, weight 400, fill 0,
 * grade 0), taken from the official fonts.gstatic.com Compose export. They are not part of the
 * material-icons-extended artifact, so the two paths live here.
 */

/** Folds the interaction card into its capsule. */
internal val CollapseContentIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "collapse_content",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = true,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(11f, 13f)
            verticalLineToRelative(6f)
            horizontalLineTo(9f)
            verticalLineTo(15f)
            horizontalLineTo(5f)
            verticalLineTo(13f)
            horizontalLineToRelative(6f)
            close()
            moveTo(15f, 5f)
            verticalLineTo(9f)
            horizontalLineToRelative(4f)
            verticalLineToRelative(2f)
            horizontalLineTo(13f)
            verticalLineTo(5f)
            horizontalLineToRelative(2f)
            close()
        }
    }.build()
}

/** Opens the capsule back into the full interaction card. */
internal val ExpandContentIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "expand_content",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = true,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(5f, 19f)
            verticalLineTo(13f)
            horizontalLineTo(7f)
            verticalLineToRelative(4f)
            horizontalLineToRelative(4f)
            verticalLineToRelative(2f)
            horizontalLineTo(5f)
            close()
            moveTo(17f, 11f)
            verticalLineTo(7f)
            horizontalLineTo(13f)
            verticalLineTo(5f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(6f)
            horizontalLineTo(17f)
            close()
        }
    }.build()
}
