package com.newoether.agora.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant
import com.materialkolor.hct.Hct

enum class SchemeStyle { TONAL_SPOT, EXPRESSIVE, VIBRANT, NEUTRAL }

enum class ColorSchemePreset { GEMINI, APPLE, MIDNIGHT, NORDIC, FOREST, SUNSET, ROSE, LAVENDER, SLATE, OCEAN }

val GeminiAuroraGradient = Brush.linearGradient(
    colors = listOf(
        Color(0xFF4285F4), // Google Electric Blue
        Color(0xFF9B72CF), // Gemini Purple
        Color(0xFFD96570), // Gemini Coral Pink
        Color(0xFF13B5EA), // Gemini Cyan
    )
)

private val seedColors = mapOf(
    ColorSchemePreset.GEMINI   to 0xFF4285F4,
    ColorSchemePreset.APPLE    to 0xFF007AFF,
    ColorSchemePreset.MIDNIGHT to 0xFF1A237E,
    ColorSchemePreset.NORDIC   to 0xFF546E7A,
    ColorSchemePreset.FOREST   to 0xFF2E7D32,
    ColorSchemePreset.SUNSET   to 0xFFE65100,
    ColorSchemePreset.ROSE     to 0xFFAD1457,
    ColorSchemePreset.LAVENDER to 0xFF7B1FA2,
    ColorSchemePreset.SLATE    to 0xFF455A64,
    ColorSchemePreset.OCEAN    to 0xFF0277BD,
)

val GeminiLightColorScheme = lightColorScheme(
    primary = Color(0xFF0B57D0),            // Google Intelligence Blue
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E3FD),   // Soft Google Blue Container
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF7C3AED),          // Vivid Gemini Purple
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDE9FE), // Soft Lavender Container
    onSecondaryContainer = Color(0xFF2E1065),
    tertiary = Color(0xFFD96570),           // Gemini Coral Pink
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE7EA),
    onTertiaryContainer = Color(0xFF5B0015),
    error = Color(0xFFB3261E),              // Google Red
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF0F4F9),         // Official Gemini Canvas Light
    onBackground = Color(0xFF1F1F1F),        // Charcoal Text
    surface = Color(0xFFFFFFFF),            // Pure White Card
    onSurface = Color(0xFF1F1F1F),           // Crisp Text
    surfaceVariant = Color(0xFFE9EEF6),     // Gemini Input & Surface Variant
    onSurfaceVariant = Color(0xFF444746),   // Secondary Text
    outline = Color(0xFFC4C7C5),            // Subtle Divider
    outlineVariant = Color(0xFFE1E3E1),
)

val GeminiDarkColorScheme = darkColorScheme(
    primary = Color(0xFF4285F4),            // Google Electric Blue
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF1E293B),   // Dark Indigo-slate Container
    onPrimaryContainer = Color(0xFFD0E2FF),
    secondary = Color(0xFF9B72CF),          // Gemini Violet / Purple
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF2C243B), // Deep Purple glow container
    onSecondaryContainer = Color(0xFFE9D5FF),
    tertiary = Color(0xFFD96570),           // Gemini Coral / Rose Pink
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF381E24),
    onTertiaryContainer = Color(0xFFFFD8DF),
    error = Color(0xFFF28B82),              // Google Soft Red
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF131314),         // Official Gemini Canvas Obsidian
    onBackground = Color(0xFFE3E3E3),        // Pearl White Text
    surface = Color(0xFF1E1F20),            // Official Gemini Card Surface
    onSurface = Color(0xFFE3E3E3),           // Pearl White
    surfaceVariant = Color(0xFF282A2C),     // Elevated Surface / Input Bar
    onSurfaceVariant = Color(0xFFC4C7C5),   // Secondary Muted Label
    outline = Color(0xFF3C4043),            // Subtle Dark Divider
    outlineVariant = Color(0xFF282A2C),
)

val AppleLightColorScheme = lightColorScheme(
    primary = Color(0xFF007AFF),            // Apple System Blue
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE5F1FF),   // Subtle iOS Blue tint
    onPrimaryContainer = Color(0xFF0040DD),
    secondary = Color(0xFF5856D6),          // Apple Indigo
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEBEBF5), // Apple System Gray 4
    onSecondaryContainer = Color(0xFF1C1C1E),
    tertiary = Color(0xFF34C759),           // Apple System Green
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE8F9EE),
    onTertiaryContainer = Color(0xFF0E6224),
    error = Color(0xFFFF3B30),              // Apple System Red
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFEBEA),
    onErrorContainer = Color(0xFF8B0000),
    background = Color(0xFFF2F2F7),         // iOS System Grouped Background
    onBackground = Color(0xFF000000),        // iOS Primary Label
    surface = Color(0xFFFFFFFF),            // iOS Pure White Card Surface
    onSurface = Color(0xFF1C1C1E),           // iOS Primary Text
    surfaceVariant = Color(0xFFE5E5EA),     // iOS System Gray 5
    onSurfaceVariant = Color(0xFF8E8E93),   // iOS Secondary Label
    outline = Color(0xFFD1D1D6),            // iOS Separator Line
    outlineVariant = Color(0xFFE5E5EA),
)

val AppleDarkColorScheme = darkColorScheme(
    primary = Color(0xFF0A84FF),            // Apple Dark Electric Blue
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF003882),
    onPrimaryContainer = Color(0xFFD0E2FF),
    secondary = Color(0xFF5E5CE6),          // Apple Dark Indigo
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF2C2C2E), // Apple Dark Gray 5
    onSecondaryContainer = Color(0xFFEBEBF5),
    tertiary = Color(0xFF30D158),           // Apple Dark Green
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF0B461B),
    onTertiaryContainer = Color(0xFFA6F5B9),
    error = Color(0xFFFF453A),              // Apple Dark Red
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF680005),
    onErrorContainer = Color(0xFFFFD2CC),
    background = Color(0xFF000000),         // iOS Pure OLED Black
    onBackground = Color(0xFFFFFFFF),        // iOS Primary Text
    surface = Color(0xFF1C1C1E),            // iOS Elevated Card Gray 6
    onSurface = Color(0xFFF2F2F7),           // iOS Primary Text
    surfaceVariant = Color(0xFF2C2C2E),     // iOS Secondary Container
    onSurfaceVariant = Color(0xFF8E8E93),   // iOS Secondary Label
    outline = Color(0xFF38383A),            // iOS Dark Separator
    outlineVariant = Color(0xFF2C2C2E),
)

fun colorSchemeForPreset(
    preset: ColorSchemePreset,
    style: SchemeStyle = SchemeStyle.TONAL_SPOT,
    isDark: Boolean = false
): ColorScheme {
    return when (preset) {
        ColorSchemePreset.GEMINI -> if (isDark) GeminiDarkColorScheme else GeminiLightColorScheme
        ColorSchemePreset.APPLE -> if (isDark) AppleDarkColorScheme else AppleLightColorScheme
        else -> {
            val seedArgb = seedColors[preset]!!.toInt()
            val hct = Hct.fromInt(seedArgb)
            val scheme: DynamicScheme = when (style) {
                SchemeStyle.TONAL_SPOT -> SchemeTonalSpot(hct, isDark, 0.0)
                SchemeStyle.EXPRESSIVE -> SchemeExpressive(hct, isDark, 0.0)
                SchemeStyle.VIBRANT   -> SchemeVibrant(hct, isDark, 0.0)
                SchemeStyle.NEUTRAL   -> SchemeNeutral(hct, isDark, 0.0)
            }
            scheme.toColorScheme()
        }
    }
}

private fun DynamicScheme.toColorScheme(): ColorScheme {
    val c = { argb: Int -> Color(argb) }
    return if (isDark) darkColorScheme(
        primary = c(primary), onPrimary = c(onPrimary),
        primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
        secondary = c(secondary), onSecondary = c(onSecondary),
        secondaryContainer = c(secondaryContainer), onSecondaryContainer = c(onSecondaryContainer),
        tertiary = c(tertiary), onTertiary = c(onTertiary),
        tertiaryContainer = c(tertiaryContainer), onTertiaryContainer = c(onTertiaryContainer),
        error = c(error), onError = c(onError),
        errorContainer = c(errorContainer), onErrorContainer = c(onErrorContainer),
        background = Color(0xFF131314),
        onBackground = Color(0xFFE3E3E3),
        surface = Color(0xFF1E1F20),
        onSurface = Color(0xFFE3E3E3),
        surfaceVariant = Color(0xFF282A2C),
        onSurfaceVariant = Color(0xFFC4C7C5),
        outline = Color(0xFF3C4043),
        outlineVariant = Color(0xFF282A2C),
    ) else lightColorScheme(
        primary = c(primary), onPrimary = c(onPrimary),
        primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
        secondary = c(secondary), onSecondary = c(onSecondary),
        secondaryContainer = c(secondaryContainer), onSecondaryContainer = c(onSecondaryContainer),
        tertiary = c(tertiary), onTertiary = c(onTertiary),
        tertiaryContainer = c(tertiaryContainer), onTertiaryContainer = c(onTertiaryContainer),
        error = c(error), onError = c(onError),
        errorContainer = c(errorContainer), onErrorContainer = c(onErrorContainer),
        background = Color(0xFFF0F4F9),
        onBackground = Color(0xFF1F1F1F),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1F1F1F),
        surfaceVariant = Color(0xFFE9EEF6),
        onSurfaceVariant = Color(0xFF444746),
        outline = Color(0xFFC4C7C5),
        outlineVariant = Color(0xFFE1E3E1),
    )
}
