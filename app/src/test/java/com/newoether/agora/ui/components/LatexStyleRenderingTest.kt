package com.newoether.agora.ui.components

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.scilab.forge.jlatexmath.DefaultTeXFont
import org.scilab.forge.jlatexmath.TeXConstants

/** Inline math is TeX text style and centred on its math axis; display math stays in display style. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LatexStyleRenderingTest {

    @Before
    fun initLatex() {
        ru.noties.jlatexmath.JLatexMathAndroid.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun textStyleIsCompactAndDisplayStyleIsLoose() {
        val latex = "\\sum_{i=1}^{n} \\frac{i}{2}"
        val inline = requireNotNull(renderLatexToBitmap(latex, textSize = 60f, display = false)) {
            "inline formula did not render"
        }
        val display = requireNotNull(renderLatexToBitmap(latex, textSize = 60f, display = true)) {
            "display formula did not render"
        }

        assertTrue(
            "display height ${display.height} should exceed text height ${inline.height}",
            display.height > inline.height,
        )
    }

    @Test
    fun inlineFormulaCentresItsMathAxisInTheBitmap() {
        val icon = latexIcon("x_{i}", textSize = 60f, color = 0, display = false)
        val padding = latexVerticalPadding(icon, textSize = 60f, height = icon.iconHeight, display = false)
        val axisY = icon.baseLine * icon.iconHeight -
            DefaultTeXFont(60f).getAxisHeight(TeXConstants.STYLE_TEXT) * 60f
        val paddedHeight = icon.iconHeight + padding.top + padding.bottom

        assertEquals(
            "axis ${axisY + padding.top} in $paddedHeight",
            paddedHeight / 2f,
            axisY + padding.top,
            1f,
        )

        val bitmap = requireNotNull(renderLatexToBitmap("x_{i}", textSize = 60f, display = false))
        assertEquals(paddedHeight, bitmap.height)
    }
}
