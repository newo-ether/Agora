package com.newoether.agora.ui.motion

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch

/** Immediate outline feedback; no wave, intermediate alpha, or layout mutation. */
internal object EinkPressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        object : Modifier.Node(), DrawModifierNode {
            private val presses = mutableSetOf<PressInteraction.Press>()
            override fun onAttach() {
                coroutineScope.launch {
                    interactionSource.interactions.collect { interaction ->
                        when (interaction) {
                            is PressInteraction.Press -> presses.add(interaction)
                            is PressInteraction.Release -> presses.remove(interaction.press)
                            is PressInteraction.Cancel -> presses.remove(interaction.press)
                        }
                        invalidateDraw()
                    }
                }
            }
            override fun ContentDrawScope.draw() {
                drawContent()
                if (presses.isNotEmpty()) {
                    drawRoundRect(Color.Black, cornerRadius = CornerRadius(4.dp.toPx()),
                        style = Stroke(2.dp.toPx()))
                }
            }
        }
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = javaClass.hashCode()
}
