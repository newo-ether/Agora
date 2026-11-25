package com.newoether.agora.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.newoether.agora.R

/**
 * Wraps a page's back action for pages with an explicit Save button.
 *
 * The returned lambda leaves immediately when nothing changed. When the page
 * holds unsaved edits it shows the "Unsaved changes" dialog instead, and only
 * calls [onBack] after the user picks Discard. Use the returned lambda for the
 * top-bar back button.
 *
 * This also registers the system BackHandler, so call sites must not add their
 * own. Once [onBack] has run, the handler disables itself: the page stays
 * composed during its exit animation with the same unsaved draft, and a second
 * back press there must reach the parent instead of reopening the dialog.
 * [enabled] gates only the system BackHandler.
 */
@Composable
fun rememberUnsavedChangesBack(
    hasUnsavedChanges: Boolean,
    onBack: () -> Unit,
    enabled: Boolean = true,
): () -> Unit {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var left by remember { mutableStateOf(false) }
    val currentHasUnsavedChanges by rememberUpdatedState(hasUnsavedChanges)
    val currentOnBack by rememberUpdatedState(onBack)

    fun leave() {
        if (left) return
        left = true
        currentOnBack()
    }

    if (showDialog && !left) {
        UnsavedChangesDialog(
            onDiscard = {
                showDialog = false
                leave()
            },
            onKeepEditing = { showDialog = false },
        )
    }

    val guardedBack = remember {
        {
            if (!left) {
                if (currentHasUnsavedChanges) showDialog = true else leave()
            }
        }
    }
    BackHandler(enabled = enabled && !left, onBack = guardedBack)
    return guardedBack
}

@Composable
fun UnsavedChangesDialog(
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onKeepEditing,
        title = { Text(stringResource(R.string.unsaved_changes_title), fontWeight = FontWeight.Bold) },
        text = { Text(stringResource(R.string.unsaved_changes_message)) },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.unsaved_changes_discard))
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) {
                Text(stringResource(R.string.unsaved_changes_keep_editing))
            }
        },
    )
}
