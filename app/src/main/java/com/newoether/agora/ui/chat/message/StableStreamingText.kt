package com.newoether.agora.ui.chat.message

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import com.newoether.agora.ui.components.Text

/**
 * Plain-text companion to streaming Markdown. The text/layout input is always the final,
 * undecorated value; only the tail glyph color alpha changes while new glyphs settle.
 */
@Composable
internal fun StableStreamingText(
    text: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle,
    color: Color,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    tailFadeEnabled: Boolean = true,
    tailFadeInitialAlpha: Float = 0f,
    tailFadeCodePoints: Int? = null,
    tailFadeSpatialBands: Int = 0,
) {
    val content = AnnotatedString(text)
    val renderedText = rememberStreamingGlyphFade(
        content = content,
        color = color,
        enabled = streaming && tailFadeEnabled,
        initialAlpha = tailFadeInitialAlpha,
        fadeCodePoints = tailFadeCodePoints,
        spatialBands = tailFadeSpatialBands,
    )
    Text(
        text = renderedText,
        modifier = modifier,
        style = style,
        color = color,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = overflow,
    )
}
