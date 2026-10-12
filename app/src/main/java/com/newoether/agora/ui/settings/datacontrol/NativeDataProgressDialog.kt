package com.newoether.agora.ui.settings.datacontrol

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.Text

@Composable
internal fun NativeDataProgressDialog(title: String) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = { },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth().height(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                MotionAwareCircularProgressIndicator()
            }
        },
        confirmButton = { },
    )
}
