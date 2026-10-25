package com.newoether.agora.phone

import kotlin.math.roundToInt

internal fun phoneCallerAdmitted(automated: Boolean, allowAutomation: Boolean) = !automated || allowAutomation

internal fun phoneAppAllowed(
    packageName: String,
    ownPackage: String,
    mode: String,
    packages: Set<String>,
    launcher: String?,
): Boolean = when {
    packageName == ownPackage -> true
    mode == "off" -> true
    mode == "allowlist" && (packageName == launcher || packageName == "com.android.systemui") -> true
    mode == "blocklist" -> packageName !in packages
    mode == "allowlist" -> packageName in packages
    else -> false
}

internal data class PhoneScreenSpace(
    val screenshotWidth: Int,
    val screenshotHeight: Int,
    val physicalWidth: Int,
    val physicalHeight: Int,
    val rotation: Int,
) {
    init {
        require(listOf(screenshotWidth, screenshotHeight, physicalWidth, physicalHeight).all { it > 0 })
    }
    fun physical(x: Float, y: Float): Pair<Int, Int> {
        require(x.isFinite() && y.isFinite() && x >= 0 && y >= 0)
        require(x < screenshotWidth && y < screenshotHeight) { "Coordinates outside latest screenshot" }
        return (x * physicalWidth / screenshotWidth).roundToInt().coerceIn(0, physicalWidth - 1) to
            (y * physicalHeight / screenshotHeight).roundToInt().coerceIn(0, physicalHeight - 1)
    }
}

internal fun phoneInputPath(mode: String, text: String): String = when {
    mode == "accessibility" -> "set_text"
    text.any { it.code > 127 || it.code < 32 } || "%s" in text -> "clipboard"
    else -> "input_text"
}

/** Two untrusted overlay frames share Android's combined obscuring-opacity budget. */
internal fun phoneOverlayWindowAlpha(maximum: Float): Float =
    (1f - kotlin.math.sqrt(1f - maximum.coerceIn(0f, 1f)) - 0.0001f).coerceAtLeast(0f)
internal fun phoneShellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

internal val PHONE_TOOL_NAMES = listOf(
    "phone_claim", "phone_release", "phone_screenshot", "phone_ui_tree", "phone_tap",
    "phone_long_press", "phone_swipe", "phone_type", "phone_key", "phone_app_launch", "phone_app_list",
)
