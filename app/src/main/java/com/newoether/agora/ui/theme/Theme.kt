package com.newoether.agora.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import java.io.File

enum class ThemeMode { LIGHT, DARK, FOLLOW_DEVICE }

/**
 * Returns the effective [FontFamily] for non-mono typography based on the font preference.
 */
@Composable
private fun effectiveFontFamily(
    fontPreference: String,
    customFontPath: String
): FontFamily = remember(fontPreference, customFontPath) {
    when (fontPreference) {
        "system" -> FontFamily.Default
        "custom" -> {
            val file = File(customFontPath)
            if (file.exists()) {
                try {
                    FontFamily(
                        Font(file, FontWeight.ExtraLight),
                        Font(file, FontWeight.Light),
                        Font(file, FontWeight.Normal),
                        Font(file, FontWeight.Medium),
                        Font(file, FontWeight.Bold),
                    )
                } catch (_: Exception) {
                    OutfitFamily
                }
            } else OutfitFamily
        }
        else -> OutfitFamily
    }
}

/**
 * Builds the [Typography] with the given [FontFamily] replacing all non-mono styles.
 */
private fun typographyWithFont(family: FontFamily): Typography {
    fun TextStyle.withFamily(f: FontFamily) = copy(fontFamily = f)
    return Typography.copy(
        displayLarge = Typography.displayLarge.withFamily(family),
        displayMedium = Typography.displayMedium.withFamily(family),
        displaySmall = Typography.displaySmall.withFamily(family),
        headlineLarge = Typography.headlineLarge.withFamily(family),
        headlineMedium = Typography.headlineMedium.withFamily(family),
        headlineSmall = Typography.headlineSmall.withFamily(family),
        titleLarge = Typography.titleLarge.withFamily(family),
        titleMedium = Typography.titleMedium.withFamily(family),
        titleSmall = Typography.titleSmall.withFamily(family),
        bodyLarge = Typography.bodyLarge.withFamily(family),
        bodyMedium = Typography.bodyMedium.withFamily(family),
        bodySmall = Typography.bodySmall.withFamily(family),
        labelLarge = Typography.labelLarge.withFamily(family),
        labelMedium = Typography.labelMedium.withFamily(family),
        labelSmall = Typography.labelSmall.withFamily(family),
    )
}

@Composable
fun AgoraTheme(
    themeMode: ThemeMode = ThemeMode.FOLLOW_DEVICE,
    colorSchemePreset: ColorSchemePreset = ColorSchemePreset.GEMINI,
    schemeStyle: SchemeStyle = SchemeStyle.TONAL_SPOT,
    dynamicColor: Boolean = false,
    fontPreference: String = "app_default",
    customFontPath: String = "",
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.FOLLOW_DEVICE -> systemDark
    }

    val colorScheme = remember(colorSchemePreset, schemeStyle, darkTheme) {
        colorSchemeForPreset(colorSchemePreset, schemeStyle, darkTheme)
    }

    val fontFamily = effectiveFontFamily(fontPreference, customFontPath)
    chatFontFamily = fontFamily
    val typography = remember(fontFamily) { typographyWithFont(fontFamily) }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}
