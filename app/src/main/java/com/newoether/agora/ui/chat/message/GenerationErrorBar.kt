package com.newoether.agora.ui.chat.message

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.chat.StreamingTailAnchorHeight
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.util.NoAutoScrollSelectionContainer
import com.newoether.agora.viewmodel.normalizePersistedGenerationErrorText
import com.newoether.agora.ui.motion.animateColorAsState
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.TextButton

/** Shared neutral text presentation for terminal generation information. */
@Composable
internal fun GenerationTerminalText(
    text: String,
    modifier: Modifier = Modifier,
    selectable: Boolean = false,
    fillWidth: Boolean = false,
    normalizeError: Boolean = false,
) {
    val context = LocalContext.current
    val displayText = if (normalizeError) {
        normalizePersistedGenerationErrorText(context, text)
    } else {
        text
    }
    val textContent: @Composable () -> Unit = {
        Text(
            text = displayText,
            style = ChatType.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        )
    }
    Box(
        modifier = Modifier
            .heightIn(min = StreamingTailAnchorHeight)
            .then(modifier)
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier),
    ) {
        if (selectable) {
            NoAutoScrollSelectionContainer {
                textContent()
            }
        } else {
            textContent()
        }
    }
}

/** Shared presentation for a caller-owned generation error value and optional Local help. */
@Composable
internal fun GenerationErrorBar(
    errorText: String,
    modifier: Modifier = Modifier,
    precededByCard: Boolean = false,
    showLocalContextHelp: Boolean = false,
    topPadding: Dp = if (precededByCard) 12.dp else 8.dp,
) {
    var showHelpDialog by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                top = topPadding,
                bottom = 4.dp,
            ),
    ) {
        GenerationTerminalText(
            text = errorText,
            selectable = true,
            fillWidth = true,
            normalizeError = true,
        )
        if (showLocalContextHelp) {
            LocalContextHelpAction(onClick = { showHelpDialog = true })
        }
    }
    if (showHelpDialog) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showHelpDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.local_model_limitations_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = { Text(stringResource(R.string.local_model_limitations_body)) },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }
}

@Composable
private fun LocalContextHelpAction(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val linkColor = MaterialTheme.colorScheme.primary
    val animatedColor by animateColorAsState(
        targetValue = if (isPressed) {
            linkColor.copy(alpha = MarkdownLinkPressedAlpha)
        } else {
            linkColor
        },
        animationSpec = tween(
            durationMillis = MarkdownLinkPressAnimationMillis,
            easing = FastOutSlowInEasing,
        ),
        label = "localContextHelpPressColor",
    )
    Text(
        text = stringResource(R.string.learn_more),
        style = ChatType.body,
        color = animatedColor,
        modifier = Modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(top = 4.dp),
    )
}

@Composable
internal fun StoppedGenerationBar(
    precededByCard: Boolean = false,
) {
    GenerationTerminalText(
        text = stringResource(R.string.generation_stopped),
        modifier = Modifier.padding(
            top = if (precededByCard) 12.dp else 8.dp,
        ),
    )
}
