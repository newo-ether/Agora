package com.newoether.agora.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class EinkColorSchemeTest {
    @Test
    fun allPaperSurfacesAndSemanticTextRemainAtTheTwoContrastEndpoints() {
        val scheme = eInkColorScheme()
        listOf(scheme.background, scheme.surface, scheme.surfaceVariant, scheme.surfaceContainer,
            scheme.surfaceContainerLow, scheme.surfaceContainerLowest, scheme.surfaceContainerHigh,
            scheme.surfaceContainerHighest, scheme.primaryContainer, scheme.secondaryContainer,
            scheme.tertiaryContainer, scheme.errorContainer).forEach { assertEquals(Color.White, it) }
        listOf(scheme.onBackground, scheme.onSurface, scheme.onSurfaceVariant, scheme.primary,
            scheme.secondary, scheme.tertiary, scheme.error, scheme.outline, scheme.outlineVariant,
            scheme.onPrimaryContainer, scheme.onSecondaryContainer, scheme.onErrorContainer)
            .forEach { assertEquals(Color.Black, it) }
        assertEquals(Color.White, scheme.onPrimary)
        assertEquals(Color.Black, scheme.inverseSurface)
        assertEquals(Color.White, scheme.inverseOnSurface)
    }
}
