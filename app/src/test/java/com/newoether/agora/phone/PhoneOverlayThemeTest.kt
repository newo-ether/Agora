package com.newoether.agora.phone

import android.app.Application
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.ui.theme.AgoraTheme
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.ui.theme.ColorSchemePreset
import com.newoether.agora.ui.theme.ThemeMode
import com.newoether.agora.ui.theme.chatCompactWidth
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhoneOverlayThemeTest {
    @get:Rule val compose = createComposeRule()
    private val mode = MutableStateFlow("DARK")
    private val preset = MutableStateFlow("FOREST")
    private val settings = mockk<SettingsRepository> {
        every { themeMode } returns mode
        every { colorScheme } returns preset
        every { schemeStyle } returns MutableStateFlow("TONAL_SPOT")
        every { dynamicColor } returns MutableStateFlow(false)
        every { amoledEnabled } returns MutableStateFlow(false)
        every { fontPreference } returns MutableStateFlow("app_default")
        every { customFontPath } returns MutableStateFlow("")
        every { reduceMotion } returns MutableStateFlow(false)
    }

    @Test fun transparentOverlayInheritsTheSameContentColorAndIndicationAsTheActivitySurface() {
        var activityColor = Color.Unspecified
        var overlayColor = Color.Unspecified
        var expectedColor = Color.Unspecified
        var activityIndication: Any? = null
        var overlayIndication: Any? = null
        compose.setContent {
            val themeMode by mode.collectAsState()
            val colorPreset by preset.collectAsState()
            Column {
                AgoraTheme(
                    themeMode = ThemeMode.valueOf(themeMode),
                    colorSchemePreset = ColorSchemePreset.valueOf(colorPreset),
                ) {
                    Surface(Modifier.size(32.dp), color = MaterialTheme.colorScheme.background) {
                        activityColor = LocalContentColor.current
                        activityIndication = LocalIndication.current
                    }
                }
                PhoneOverlayTheme(settings) {
                    overlayColor = LocalContentColor.current
                    expectedColor = MaterialTheme.colorScheme.onSurface
                    overlayIndication = LocalIndication.current
                    Box(Modifier.size(32.dp))
                }
            }
        }
        fun assertMatchingContent() = compose.runOnIdle {
            assertEquals(expectedColor, overlayColor)
            assertEquals(activityColor, overlayColor)
            assertEquals(activityIndication, overlayIndication)
        }
        assertMatchingContent()
        val darkColor = overlayColor
        assertNotEquals(Color.Black, darkColor)
        compose.runOnIdle { mode.value = "LIGHT" }
        assertMatchingContent()
        assertNotEquals(darkColor, overlayColor)
        compose.runOnIdle { preset.value = ColorSchemePreset.entries.last().name }
        assertMatchingContent()
        compose.runOnIdle { mode.value = "DARK" }
        assertMatchingContent()
    }

    @Test fun narrowOverlayDoesNotSwitchMainActivityCompactWidthTier() {
        var showNarrowOverlay by mutableStateOf(false)
        var activityCompact: Boolean? = null
        var activityBodyLineHeight = 0.sp
        var overlayCompact: Boolean? = null
        var overlayBodyLineHeight = 0.sp

        compose.setContent {
            val baseWindow = LocalWindowInfo.current
            val unitDensity = Density(density = 1f, fontScale = 1f)
            val mainWindow = object : WindowInfo by baseWindow {
                override val containerSize: IntSize = IntSize(412, 915)
            }
            val narrowOverlayWindow = object : WindowInfo by baseWindow {
                override val containerSize: IntSize = IntSize(172, 360)
            }
            CompositionLocalProvider(LocalDensity provides unitDensity) {
                Column {
                    CompositionLocalProvider(LocalWindowInfo provides mainWindow) {
                        AgoraTheme {
                            activityCompact = chatCompactWidth
                            activityBodyLineHeight = ChatType.body.lineHeight
                            Box(Modifier.size(32.dp))
                        }
                    }
                    if (showNarrowOverlay) {
                        CompositionLocalProvider(LocalWindowInfo provides narrowOverlayWindow) {
                            PhoneOverlayTheme(settings) {
                                overlayCompact = chatCompactWidth
                                overlayBodyLineHeight = ChatType.body.lineHeight
                                Box(Modifier.size(32.dp))
                            }
                        }
                    }
                }
            }
        }

        compose.runOnIdle {
            assertFalse(activityCompact!!)
            assertEquals(24.sp, activityBodyLineHeight)
        }

        compose.runOnIdle { showNarrowOverlay = true }
        compose.runOnIdle {
            assertTrue(overlayCompact!!)
            assertEquals(23.sp, overlayBodyLineHeight)
            assertFalse(activityCompact!!)
            assertEquals(24.sp, activityBodyLineHeight)
        }

        compose.runOnIdle { showNarrowOverlay = false }
        compose.runOnIdle {
            assertFalse(activityCompact!!)
            assertEquals(24.sp, activityBodyLineHeight)
        }
    }
}
