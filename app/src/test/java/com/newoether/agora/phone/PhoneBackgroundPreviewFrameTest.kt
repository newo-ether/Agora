package com.newoether.agora.phone

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhoneBackgroundPreviewFrameTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun coveredPreviewStopsCollectingAndKeepsItsLastFrame() {
        val first = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val frames = MutableStateFlow<Bitmap?>(first)
        val covered = MutableStateFlow(false)
        val screen = mockk<PhoneVirtualScreen> { every { frame } returns frames }
        var shown: Bitmap? = null
        compose.setContent { shown = retainedFrame(screen, covered) }
        compose.waitForIdle()
        assertEquals(first, shown)
        assertEquals(1, frames.subscriptionCount.value)

        compose.runOnIdle { covered.value = true }
        compose.waitForIdle()
        assertEquals(0, frames.subscriptionCount.value)
        frames.value = second
        compose.waitForIdle()
        assertEquals(first, shown)

        compose.runOnIdle { covered.value = false }
        compose.waitForIdle()
        assertEquals(second, shown)
        assertEquals(1, frames.subscriptionCount.value)
    }
}
