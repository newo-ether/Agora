package com.newoether.agora.ui.chat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.TextButton
import androidx.compose.material3.TopAppBar
import com.newoether.agora.ui.components.DialogWindowEinkPresentation
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.newoether.agora.R
import com.newoether.agora.model.InteractiveAnswer

/** Optional expanded presentation of the same sandbox used by inline chat artifacts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InteractiveAnswerViewer(answer: InteractiveAnswer, onClose: () -> Unit, onSend: suspend (String) -> Boolean) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DialogWindowEinkPresentation(LocalAgoraMotionPolicy.current.eInkMode)
        Scaffold(topBar = {
            TopAppBar(title = { Text(answer.title) }, navigationIcon = {
                TextButton(onClick = onClose) { Text(stringResource(R.string.interactive_answer_close)) }
            })
        }) { padding ->
            InteractiveAnswerSurface(answer, inline = false, modifier = Modifier.fillMaxSize().padding(padding),
                onSent = onClose, onSend = onSend)
        }
    }
}
