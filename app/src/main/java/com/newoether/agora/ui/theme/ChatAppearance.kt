package com.newoether.agora.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * User chat appearance overrides (Settings → Appearance), parsed from the stored preference
 * strings. These layer on top of the Material color scheme without replacing it.
 */

/** Parsed chat background preference. */
sealed interface ChatBackground {
    data object Default : ChatBackground
    data class SolidColor(val color: Color) : ChatBackground
    data class Image(val path: String) : ChatBackground
    data class Video(val path: String) : ChatBackground
}

/** Parse "" / "none" / "color:#RRGGBB" / "image:<path>" / "video:<path>" into a [ChatBackground]. */
fun parseChatBackground(spec: String): ChatBackground = when {
    spec.isBlank() || spec == "none" -> ChatBackground.Default
    spec.startsWith("color:") -> parseHexColor(spec.removePrefix("color:"))
        ?.let { ChatBackground.SolidColor(it) } ?: ChatBackground.Default
    spec.startsWith("image:") -> spec.removePrefix("image:").takeIf { it.isNotBlank() }
        ?.let { ChatBackground.Image(it) } ?: ChatBackground.Default
    spec.startsWith("video:") -> spec.removePrefix("video:").takeIf { it.isNotBlank() }
        ?.let { ChatBackground.Video(it) } ?: ChatBackground.Default
    else -> ChatBackground.Default
}

/** Parse "#RRGGBB" or "#AARRGGBB" into a [Color], or null if invalid. */
fun parseHexColor(hex: String): Color? {
    val h = hex.trim().removePrefix("#")
    if (h.length != 6 && h.length != 8) return null
    val v = h.toLongOrNull(16) ?: return null
    return when (h.length) {
        6 -> Color(0xFF000000 or v)
        else -> Color(v)
    }
}

/** Format a [Color] as "#RRGGBB" for storage. */
fun Color.toHex(): String {
    val argb = (value shr 32).toInt()
    return "#%06X".format(argb and 0xFFFFFF)
}

/**
 * The user's custom user-message bubble color, or null to use the theme default
 * (primaryContainer). Provided at the app theme root so message items can read it without
 * threading it through the whole chat tree.
 */
val LocalUserBubbleColor = staticCompositionLocalOf<Color?> { null }

/** Black or white, whichever contrasts better with [bg] (WCAG relative luminance). */
fun contrastingOn(bg: Color): Color {
    val lum = 0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue
    return if (lum > 0.5f) Color(0xFF111111) else Color(0xFFF5F5F5)
}
