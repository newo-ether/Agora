package com.newoether.agora.ui.chat.message

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.TextButton

/** Saving is available only after explicitly expanding an interface. */
@Composable
internal fun InteractiveAnswerActions(onExpand: () -> Unit) {
    TextButton(onClick = onExpand) { Text(stringResource(R.string.interactive_answer_open)) }
}
