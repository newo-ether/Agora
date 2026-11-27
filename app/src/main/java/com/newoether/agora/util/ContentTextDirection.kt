package com.newoether.agora.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Writing direction of a text's own content, independent of the app's UI language.
 *
 * Compose resolves a paragraph's base direction from the layout direction, which
 * follows the app locale. With an English UI an Arabic message is therefore laid
 * out as an LTR paragraph: it hugs the left edge and its trailing punctuation and
 * digits land on the wrong side. These helpers look at the first strong
 * directional character of the content instead, the rule browsers and platform
 * text views use for `dir="auto"`.
 */
internal fun isRtlContent(text: CharSequence?): Boolean {
    if (text.isNullOrEmpty()) return false
    var index = 0
    while (index < text.length) {
        val codePoint = Character.codePointAt(text, index)
        when (Character.getDirectionality(codePoint)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT,
            Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
            Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE,
            -> return false

            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE,
            -> return true

            // Whitespace, punctuation, digits, emoji and other neutrals carry no
            // direction of their own, so keep looking for the first strong one.
            else -> Unit
        }
        index += Character.charCount(codePoint)
    }
    return false
}

/**
 * The direction the text's own first strong character asks for, or null when the
 * text is all digits/punctuation/emoji and carries no direction of its own.
 */
internal fun strongTextDirection(text: CharSequence?): TextDirection? {
    if (text.isNullOrEmpty()) return null
    var index = 0
    while (index < text.length) {
        val codePoint = Character.codePointAt(text, index)
        when (Character.getDirectionality(codePoint)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT,
            Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
            Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE,
            -> return TextDirection.Ltr

            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE,
            -> return TextDirection.Rtl

            else -> Unit
        }
        index += Character.charCount(codePoint)
    }
    return null
}

/** The paragraph direction to give text so it reads in its own language. */
internal fun contentTextDirection(text: CharSequence?): TextDirection =
    strongTextDirection(text) ?: TextDirection.Ltr

/**
 * Horizontal alignment matching [direction], because TextAlign.Start/End resolve
 * against the layout direction: an RTL paragraph inside an English UI would keep
 * hugging the left edge. Neutral content (direction == null) follows the layout.
 */
internal fun contentTextAlign(direction: TextDirection?, layoutRtl: Boolean): TextAlign = when {
    direction == TextDirection.Rtl -> TextAlign.Right
    direction == TextDirection.Ltr -> TextAlign.Left
    layoutRtl -> TextAlign.Right
    else -> TextAlign.Left
}

/** [contentTextAlign] for composition, resolved from the text itself. */
@Composable
internal fun rememberContentTextAlign(text: CharSequence?): TextAlign {
    val layoutRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return remember(text, layoutRtl) { contentTextAlign(strongTextDirection(text), layoutRtl) }
}

/** [contentTextDirection] memoised for composition. */
@Composable
internal fun rememberContentTextDirection(text: CharSequence?): TextDirection =
    remember(text) { contentTextDirection(text) }
