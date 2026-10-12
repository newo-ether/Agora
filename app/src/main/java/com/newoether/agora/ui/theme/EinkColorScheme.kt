package com.newoether.agora.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** UI roles only: decoded images, documents and user content keep their original colours. */
internal fun eInkColorScheme(): ColorScheme = lightColorScheme(
    primary = Color.Black, onPrimary = Color.White,
    primaryContainer = Color.White, onPrimaryContainer = Color.Black,
    secondary = Color.Black, onSecondary = Color.White,
    secondaryContainer = Color.White, onSecondaryContainer = Color.Black,
    tertiary = Color.Black, onTertiary = Color.White,
    tertiaryContainer = Color.White, onTertiaryContainer = Color.Black,
    background = Color.White, onBackground = Color.Black,
    surface = Color.White, onSurface = Color.Black,
    surfaceVariant = Color.White, onSurfaceVariant = Color.Black,
    surfaceTint = Color.White,
    inverseSurface = Color.Black, inverseOnSurface = Color.White, inversePrimary = Color.White,
    error = Color.Black, onError = Color.White,
    errorContainer = Color.White, onErrorContainer = Color.Black,
    outline = Color.Black, outlineVariant = Color.Black,
    scrim = Color.White,
    surfaceBright = Color.White, surfaceDim = Color.White,
    surfaceContainer = Color.White, surfaceContainerLow = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    primaryFixed = Color.White, primaryFixedDim = Color.White,
    onPrimaryFixed = Color.Black, onPrimaryFixedVariant = Color.Black,
    secondaryFixed = Color.White, secondaryFixedDim = Color.White,
    onSecondaryFixed = Color.Black, onSecondaryFixedVariant = Color.Black,
    tertiaryFixed = Color.White, tertiaryFixedDim = Color.White,
    onTertiaryFixed = Color.Black, onTertiaryFixedVariant = Color.Black,
)
