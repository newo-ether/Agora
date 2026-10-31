package com.newoether.agora.phone

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Material Symbols Outlined mobile_hand, official 24 dp / weight 400 / fill 0 export.
internal const val PHONE_USE_ICON_PATH =
    "M6 20V4V6v7v7Z" +
    "M15.93 23Q15.28 23 14.7 22.74T13.7 22L8.65 15.95L9.78 14.8q.45-.45 1.1-.56q.65-.11 1.23.19L15 15.88V7h2q1.65 0 2.83 1.17T21 11v8q0 1.65-1.17 2.82T17 23H15.93Z" +
    "M6 22Q5.18 22 4.59 21.41T4 20V4Q4 3.17 4.59 2.59T6 2h9q.83 0 1.41.59T17 4V7H15V4H6V20h6.03l1.68 2H6Z" +
    "M11.21 6.71Q11.5 6.43 11.5 6T11.21 5.29T10.5 5Q10.08 5 9.79 5.29T9.5 6T9.79 6.71T10.5 7T11.21 6.71Z" +
    "M15.93 21H17q.82 0 1.41-.57T19 19V11Q19 10.17 18.41 9.59T17 9V19.13L11.7 16.45l3.45 4.2q.15.18.35.26T15.93 21Z"

internal val PhoneUseIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "mobile_hand",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = addPathNodes(PHONE_USE_ICON_PATH),
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.NonZero,
        )
    }.build()
}
