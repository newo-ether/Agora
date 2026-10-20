package com.newoether.agora.ui.chat.message

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.dp

/** Wrap short cards, cap long cards at half the page, and retain only complete cards. */
@Composable
internal fun MarkdownSharePage(start: Int, count: Int, modifier: Modifier = Modifier,
    onEndChanged: (Int) -> Unit, content: @Composable (Int) -> Unit) {
    SubcomposeLayout(modifier) { constraints ->
        val gap = 8.dp.roundToPx()
        val cards = mutableListOf<Pair<androidx.compose.ui.layout.Placeable, Int>>()
        var height = 0
        var end = start
        for (index in start until count) {
            val card = subcompose(index) { content(index) }.single().measure(
                constraints.copy(minHeight = 0, maxHeight = ((constraints.maxHeight - gap) / 2).coerceAtLeast(0)))
            val top = height + if (cards.isEmpty()) 0 else gap
            if (cards.isNotEmpty() && top + card.height > constraints.maxHeight) break
            cards += card to top
            height = top + card.height
            end = index + 1
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            cards.forEach { (card, top) -> card.placeRelative(0, top) }
            // Publish after placement; this boundary changes the pager, never the card measurements.
            onEndChanged(end)
        }
    }
}
