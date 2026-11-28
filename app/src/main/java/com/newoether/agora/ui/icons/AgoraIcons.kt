package com.newoether.agora.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/*
 * Hand-drawn pair for local (on-device) models and the model-search method.
 *
 * Model Search keeps the settings-list geometry:
 *
 *  1. Material Rounded `layers`, scaled 0.85 and translated by (-2.407, 3.634) so the
 *     second element has room at the top right.
 *  2. The `Icons.Rounded.ManageSearch` lens, shifted down by 2.5 so its handle tip lands on
 *     the layers' bottom edge (21.09): circle of radius 5 centred at (14, 13.5).
 *
 * The local-model icon is built the other way round: `layers` at full size, no scale and
 * no offset, plus a badge centred at (19, 19) in the bottom-right corner: a solid radius-4
 * disc with a downward arrow punched out of it.
 *
 * In both icons a moat is clipped out of the layers so the second element keeps a 2dp gap:
 * radius 7 around the radius-5 lens of Model Search, radius 6 around the radius-4 badge of the
 * local model.
 */

private const val LAYERS_PATH =
    "M12.6 18.06c-.36.28-.87.28-1.23 0l-6.15-4.78c-.36-.28-.86-.28-1.22 0-.51.4-.51 1.17 0 " +
        "1.57l6.76 5.26c.72.56 1.73.56 2.46 0l6.76-5.26c.51-.4.51-1.17 0-1.57l-.01-.01c-.36-.28" +
        "-.86-.28-1.22 0l-6.15 4.79zm.63-3.02l6.76-5.26c.51-.4.51-1.18 0-1.58l-6.76-5.26c-.72" +
        "-.56-1.73-.56-2.46 0L4.01 8.21c-.51.4-.51 1.18 0 1.58l6.76 5.26c.72.56 1.74.56 2.46-.01z"

private const val LENS_PATH =
    "M19.88,18.29l-3.12-3.12c-0.86,0.56-1.89,0.88-3,0.82 " +
        "c-2.37-0.11-4.4-1.96-4.72-4.31C8.6,8.33,11.49,5.5,14.87,6.07c1.95,0.33,3.57,1.85,4," +
        "3.78c0.33,1.46,0.01,2.82-0.7,3.9l3.13,3.13 " +
        "c0.39,0.39,0.39,1.02,0,1.41l0,0C20.91,18.68,20.27,18.68,19.88,18.29z M17,11c0-1.65" +
        "-1.35-3-3-3s-3,1.35-3,3s1.35,3,3,3 S17,12.65,17,11z"

/**
 * Full-canvas rectangle with a reversed circle punched out, filled with the non-zero rule.
 * Clipping the layers with this keeps the moat without a mask, which `ImageVector` cannot
 * express. Centre and radius are per icon, so one shape can keep its own gap.
 */
private fun moatClipPath(centerX: Float, centerY: Float, radius: Float): String {
    val left = centerX - radius
    val right = centerX + radius
    return "M0 0H24V24H0Z" +
        "M$right ${centerY}A$radius $radius 0 1 0 $left ${centerY}A$radius $radius 0 1 0 $right ${centerY}Z"
}

/** Solid disc at (19, 19) with the download arrow punched out of it. */
private const val LOCAL_BADGE_PATH =
    "M23 19A4 4 0 1 1 15 19A4 4 0 1 1 23 19Z" +
        "M18.2 17H19.8V18.6H21L19 21.4L17 18.6H18.2Z"

private const val LAYERS_SCALE = 0.85f
private const val LAYERS_TRANSLATION_X = -2.407f
private const val LAYERS_TRANSLATION_Y = 3.634f

private fun modelIcon(
    name: String,
    moatCenterX: Float,
    moatCenterY: Float,
    moatRadius: Float,
    layersScaled: Boolean,
    secondElementPath: String,
    secondElementFill: PathFillType,
    secondElementOffsetY: Float = 0f,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    group(
        clipPathData = addPathNodes(
            moatClipPath(moatCenterX, moatCenterY + secondElementOffsetY, moatRadius)
        )
    ) {
        if (layersScaled) {
            group(
                scaleX = LAYERS_SCALE,
                scaleY = LAYERS_SCALE,
                translationX = LAYERS_TRANSLATION_X,
                translationY = LAYERS_TRANSLATION_Y,
            ) {
                addPath(pathData = addPathNodes(LAYERS_PATH), fill = SolidColor(Color.Black))
            }
        } else {
            addPath(pathData = addPathNodes(LAYERS_PATH), fill = SolidColor(Color.Black))
        }
    }
    group(translationY = secondElementOffsetY) {
        addPath(
            pathData = addPathNodes(secondElementPath),
            pathFillType = secondElementFill,
            fill = SolidColor(Color.Black),
        )
    }
}.build()

/** Local (on-device) model, also used for the "import model" actions. */
internal val LocalModelIcon: ImageVector by lazy {
    modelIcon(
        name = "local_model",
        moatCenterX = 19f,
        moatCenterY = 19f,
        moatRadius = 6f,
        layersScaled = false,
        secondElementPath = LOCAL_BADGE_PATH,
        secondElementFill = PathFillType.EvenOdd,
    )
}

/** Model Search method; the lens is shared with `Icons.Rounded.ManageSearch`. */
internal val ModelSearchIcon: ImageVector by lazy {
    modelIcon(
        name = "model_search",
        moatCenterX = 14f,
        moatCenterY = 11f,
        moatRadius = 7f,
        layersScaled = true,
        secondElementPath = LENS_PATH,
        secondElementFill = PathFillType.NonZero,
        secondElementOffsetY = 2.5f,
    )
}
