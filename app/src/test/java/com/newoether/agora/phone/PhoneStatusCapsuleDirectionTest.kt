package com.newoether.agora.phone

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A phone overlay label must read by its own content, not by the ambient UI language. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-mdpi")
class PhoneStatusCapsuleDirectionTest {
    @get:Rule val compose = createComposeRule()

    private fun renderedTextDirection(text: String, layoutDirection: LayoutDirection): TextDirection {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                MaterialTheme { PhoneStatusCapsule(text) }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(
            SemanticsActions.GetTextLayoutResult,
        ) { it(layouts) }
        return layouts.single().layoutInput.style.textDirection
    }

    @Test fun arabicStatusIsRtlEvenWhenTheUiLayoutIsLtr() {
        assertEquals(TextDirection.Rtl, renderedTextDirection("جارٍ التفكير", LayoutDirection.Ltr))
    }

    @Test fun englishStatusIsLtrEvenWhenTheUiLayoutIsRtl() {
        assertEquals(TextDirection.Ltr, renderedTextDirection("Thinking", LayoutDirection.Rtl))
    }
}
