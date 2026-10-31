package com.newoether.agora.ui.chat
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.newoether.agora.ui.chat.message.scaledMarkdownTextStyle
import org.junit.Assert.assertEquals
import org.junit.Test
class TextFileViewerTypographyTest {
    @Test
    fun `shared markdown scaler preserves app typography with centered one point one line height`() {
        val appStyle = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
        val scaled = scaledMarkdownTextStyle(appStyle)
        assertEquals(FontFamily.SansSerif, scaled.fontFamily)
        assertEquals(FontWeight.Medium, scaled.fontWeight)
        assertEquals(13.sp, scaled.fontSize)
        assertEquals(22.sp, scaled.lineHeight)
        assertEquals(LineHeightStyle.Alignment.Center, scaled.lineHeightStyle?.alignment)
        assertEquals(LineHeightStyle.Trim.Both, scaled.lineHeightStyle?.trim)
    }
}
