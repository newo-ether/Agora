package com.newoether.agora.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextDirection

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

/** The paragraph direction to give text so it reads in its own language. */
internal fun contentTextDirection(text: CharSequence?): TextDirection =
    if (isRtlContent(text)) TextDirection.Rtl else TextDirection.Ltr

/** [contentTextDirection] memoised for composition. */
@Composable
internal fun rememberContentTextDirection(text: CharSequence?): TextDirection =
    remember(text) { contentTextDirection(text) }
