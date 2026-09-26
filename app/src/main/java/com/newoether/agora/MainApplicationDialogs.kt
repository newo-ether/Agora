package com.newoether.agora

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.newoether.agora.data.claimSubmissionMessage
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.ui.settings.RatingForm
import com.newoether.agora.util.CrashReporter
import com.newoether.agora.util.SubmissionMessage
import com.newoether.agora.ui.components.SubmissionMessageDialog
import java.io.IOException
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainApplicationDialogs(
    viewModel: ChatViewModel,
    settingsManager: SettingsManager,
    snackbarHostState: SnackbarHostState,
    snackbarVersionState: MutableIntState,
) {
    var snackbarVersion by snackbarVersionState
    val ratingScope = rememberCoroutineScope()
    var submissionMessage by remember { mutableStateOf<SubmissionMessage?>(null) }
    submissionMessage?.let { message ->
        SubmissionMessageDialog(message) { submissionMessage = null }
    }

    // Update dialog
    val updateDialogData by viewModel.updateDialogData.collectAsState()
    if (updateDialogData != null) {
        val info = updateDialogData!!
        val ctx = LocalContext.current
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { viewModel.dismissUpdateDialog() },
            icon = { Icon(Icons.Default.Download, null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary) },
            title = {
                Text(
                    text = stringResource(R.string.about_update_available, info.version),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.about_available_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (info.body.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Column {
                            // Lightweight markdown render of the release notes, kept on the
                            // shared type scale: '## ' → bold section label, '- ' → indented
                            // bullet, blank line → vertical gap, everything else → paragraph.
                            info.body.split("\n").forEach { line ->
                                when {
                                    line.startsWith("## ") -> Text(
                                        text = line.removePrefix("## "),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
                                    )
                                    line.startsWith("- ") -> Text(
                                        text = "•  ${line.removePrefix("- ")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 3.dp, start = 2.dp)
                                    )
                                    line.isBlank() -> Spacer(modifier = Modifier.height(4.dp))
                                    else -> Text(
                                        text = line,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.url)))
                    viewModel.dismissUpdateDialog()
                }) { Text(stringResource(R.string.about_view_release)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUpdateDialog() }) {
                    Text(stringResource(R.string.about_later))
                }
            }
        )
    }

    // Remote shell confirmations are answered in the chat screen's interaction bar, not here:
    // a modal dialog hid the conversation the command belongs to. While the chat screen is not on
    // screen the same prompt is re-surfaced as a notification.

    // Crash report — opt-in, shown once on the first launch after an unexpected exit
    val crashContext = LocalContext.current
    var pendingCrash by remember { mutableStateOf<Pair<String, String>?>(null) }
    val crashSubmittedMsg = stringResource(R.string.crash_submitted)
    LaunchedEffect(Unit) {
        pendingCrash = withContext(Dispatchers.IO) {
            CrashReporter.pendingReport(crashContext)?.let { report ->
                val trace = runCatching {
                    org.json.JSONObject(report).optString("trace", "")
                }.getOrDefault("")
                report to trace
            }
        }
    }
    fun dismissPendingCrash() {
        pendingCrash = null
        ratingScope.launch(Dispatchers.IO) { CrashReporter.clear(crashContext) }
    }
    pendingCrash?.let { (report, trace) ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = ::dismissPendingCrash,
            icon = { Icon(Icons.Default.BugReport, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.crash_title), fontWeight = FontWeight.Bold) },
            text = {
                val clipboard = LocalClipboardManager.current
                Column {
                    Text(
                        stringResource(R.string.crash_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(14.dp))
                    // Privacy reassurance as a distinct fine-print block, not just smaller text.
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                null,
                                modifier = Modifier.size(15.dp).padding(top = 1.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.crash_privacy_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (trace.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                stringResource(R.string.crash_log_label),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(trace)) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    stringResource(R.string.copy),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.heightIn(max = 200.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = viewModel.displayText(trace),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily(Font(R.font.jetbrains_mono_regular)),
                                        lineHeight = 16.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingCrash = null
                    ratingScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            CrashReporter.submit(report, crashContext.packageName).also {
                                if (it.accepted) CrashReporter.clear(crashContext)
                            }
                        }
                        val message = try {
                            result.message?.takeIf { claimSubmissionMessage(crashContext, it.id) }
                        } catch (_: IOException) {
                            null
                        }
                        if (message != null) {
                            submissionMessage = message
                        } else if (result.accepted) {
                            try {
                                snackbarHostState.showSnackbar(crashSubmittedMsg)
                            } finally {
                                snackbarVersion++
                            }
                        }
                    }
                }) { Text(stringResource(R.string.crash_submit)) }
            },
            dismissButton = {
                TextButton(onClick = ::dismissPendingCrash) {
                    Text(stringResource(R.string.crash_dismiss))
                }
            }
        )
    }

    // Rating prompt — read from flow directly to avoid collectAsState initial-value race
    var showRatingPrompt by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        val firstLaunch = settingsManager.firstLaunchTime.first()
        if (firstLaunch == null) {
            settingsManager.saveFirstLaunchTime(now)
        }

        val submitted = settingsManager.ratingPromptSubmitted.first()
        val dismissed = settingsManager.ratingPromptDismissed.first()
        val msgCount = settingsManager.totalMessagesSent.first()
        if (!submitted && !dismissed && firstLaunch != null && msgCount >= 3) {
            val daysElapsed = (now - firstLaunch) / (1000 * 60 * 60 * 24)
            if (daysElapsed >= 7) {
                showRatingPrompt = true
            }
        }
    }

    if (showRatingPrompt) {
        Dialog(
            onDismissRequest = {
                showRatingPrompt = false
                ratingScope.launch {
                    settingsManager.saveRatingPromptDismissed(true)
                }
            }
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    RatingForm(
                        onSubmitted = { message ->
                            submissionMessage = message
                            showRatingPrompt = false
                            ratingScope.launch {
                                settingsManager.saveRatingPromptSubmitted(true)
                            }
                        }
                    )
                }
            }
        }
    }
}
