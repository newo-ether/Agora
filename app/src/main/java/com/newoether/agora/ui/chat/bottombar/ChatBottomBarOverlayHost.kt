package com.newoether.agora.ui.chat.bottombar

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.ModelThinkingCapability
import com.newoether.agora.model.OpenAiServiceTiers
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.ui.chat.PdfPageSelectDialog
import com.newoether.agora.ui.chat.VideoSliceDialog
import com.newoether.agora.ui.common.OpenAiServiceTierControlPanel
import com.newoether.agora.ui.common.ThinkingControlPanel
import com.newoether.agora.ui.components.DialogWindowEdgeToEdge
import com.newoether.agora.ui.motion.MotionAwareModalBottomSheet as ModalBottomSheet
import com.newoether.agora.util.FileValidator
import com.newoether.agora.viewmodel.ConversationComposerController
import com.newoether.agora.viewmodel.ConversationComposerSnapshot
import com.newoether.agora.viewmodel.ConversationComposerSubmissionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatBottomBarOverlayHost(
    showThinkingSheet: Boolean,
    onDismissThinkingSheet: () -> Unit,
    thinkingEnabled: Boolean,
    thinkingLevel: String,
    thinkingCapability: ModelThinkingCapability,
    thinkingBudgetEnabled: Boolean,
    thinkingBudgetTokens: Int,
    onThinkingToggle: (Boolean) -> Unit,
    onThinkingLevelChange: (String) -> Unit,
    onThinkingBudgetEnabledChange: (Boolean) -> Unit,
    onThinkingBudgetTokensChange: (Int) -> Unit,
    showOpenAiServiceTierSheet: Boolean,
    openAiServiceTierAvailable: Boolean,
    onDismissOpenAiServiceTierSheet: () -> Unit,
    openAiServiceTierEnabled: Boolean,
    openAiServiceTier: String,
    availableServiceTiers: List<String>,
    onOpenAiServiceTierToggle: (Boolean) -> Unit,
    onOpenAiServiceTierChange: (String) -> Unit,
    internalCameraPath: String?,
    internalCameraOwnerId: String?,
    onInternalCameraCleared: () -> Unit,
    composerOwnerId: String,
    composerController: ConversationComposerController,
    submissionController: ConversationComposerSubmissionController,
    composerSnapshot: ConversationComposerSnapshot,
    composer: ChatComposerState,
    pdfViewerSelection: Set<Int>,
    onTogglePdfSelection: ((Int) -> Unit)?,
    onPdfPreviewSelect: ((List<String>, Int) -> Unit)?,
) {
    val scope = rememberCoroutineScope()

    suspend fun releaseOwner(ownerId: String) {
        withContext(NonCancellable) { composerController.release(ownerId) }
    }

    fun isFrozen(ownerId: String) = submissionController.snapshot(ownerId).isFrozen

    fun configurePdf(attachment: SelectedAttachment, selectedPages: Set<Int>) {
        composer.showPdfPageDialog = false
        val ownerId = attachmentOwner(composer, composerOwnerId, pdf = true)
        if (isFrozen(ownerId)) {
            composer.resetPendingPdfState()
            return
        }
        scope.launch {
            composerController.load(ownerId)
            try {
                if (!isFrozen(ownerId)) {
                    composerController.configurePdf(ownerId, attachment.localId, selectedPages)
                }
            } finally {
                composer.resetPendingPdfState()
                releaseOwner(ownerId)
            }
        }
    }

    fun removePending(attachment: SelectedAttachment, pdf: Boolean) {
        if (pdf) composer.showPdfPageDialog = false else composer.showVideoSliceDialog = false
        val ownerId = attachmentOwner(composer, composerOwnerId, pdf)
        if (isFrozen(ownerId)) {
            if (pdf) composer.resetPendingPdfState() else composer.resetPendingVideoState()
            return
        }
        scope.launch {
            composerController.load(ownerId)
            try {
                if (!isFrozen(ownerId)) composerController.remove(ownerId, attachment.localId)
            } finally {
                if (pdf) composer.resetPendingPdfState() else composer.resetPendingVideoState()
                releaseOwner(ownerId)
            }
        }
    }

    if (showThinkingSheet) {
        ModalBottomSheet(
            onDismissRequest = onDismissThinkingSheet,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            DialogWindowEdgeToEdge()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                ThinkingControlPanel(
                    enabled = thinkingEnabled,
                    level = thinkingLevel,
                    budgetEnabled = thinkingBudgetEnabled,
                    budgetTokens = thinkingBudgetTokens,
                    onEnabledChange = onThinkingToggle,
                    onLevelChange = onThinkingLevelChange,
                    onBudgetEnabledChange = onThinkingBudgetEnabledChange,
                    onBudgetTokensChange = onThinkingBudgetTokensChange,
                    // Exactly the options the selected model accepts: its effort levels, whether
                    // thinking can be turned off, and whether it takes a token budget.
                    availableEfforts = thinkingCapability.supportedEfforts,
                    allowDisable = thinkingCapability.canDisableThinking,
                    showBudgetControls = thinkingCapability.supportsThinkingBudget,
                    animateSections = true,
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showOpenAiServiceTierSheet && openAiServiceTierAvailable) {
        ModalBottomSheet(
            onDismissRequest = onDismissOpenAiServiceTierSheet,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            DialogWindowEdgeToEdge()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                OpenAiServiceTierControlPanel(
                    enabled = openAiServiceTierEnabled,
                    tier = openAiServiceTier,
                    onEnabledChange = onOpenAiServiceTierToggle,
                    onTierChange = onOpenAiServiceTierChange,
                    availableTiers = availableServiceTiers,
                    tierLabels = mapOf(
                        OpenAiServiceTiers.AUTO to stringResource(R.string.openai_service_tier_auto),
                        OpenAiServiceTiers.DEFAULT to stringResource(R.string.openai_service_tier_default),
                        OpenAiServiceTiers.FLEX to stringResource(R.string.openai_service_tier_flex),
                        OpenAiServiceTiers.FAST to stringResource(R.string.openai_service_tier_fast),
                        OpenAiServiceTiers.ULTRAFAST to stringResource(R.string.openai_service_tier_ultrafast),
                    ),
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (internalCameraPath != null && internalCameraOwnerId != null) {
        InternalCameraCaptureDialog(
            targetPath = internalCameraPath,
            onCaptured = {
                onInternalCameraCleared()
                composer.completeCameraCapture(
                    internalCameraOwnerId,
                    composerController,
                    submissionController,
                    internalCameraPath,
                    captured = true,
                )
            },
            onCancelled = {
                onInternalCameraCleared()
                composer.completeCameraCapture(
                    internalCameraOwnerId,
                    composerController,
                    submissionController,
                    internalCameraPath,
                    captured = false,
                )
            },
            onFailure = {
                onInternalCameraCleared()
                composer.completeCameraCapture(
                    internalCameraOwnerId,
                    composerController,
                    submissionController,
                    internalCameraPath,
                    captured = false,
                )
                composer.reportCameraPreparationFailure()
            },
        )
    }

    if (composer.rejectedMessage != null) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { composer.rejectedMessage = null },
            title = { Text(stringResource(composer.rejectedTitleRes), fontWeight = FontWeight.Bold) },
            text = { Text(composer.rejectedMessage!!) },
            confirmButton = {
                TextButton(onClick = { composer.rejectedMessage = null }) {
                    Text(stringResource(R.string.provider_close))
                }
            },
        )
    }

    val pendingPdf = composerSnapshot.attachments.firstOrNull { attachment ->
        attachment.localId == composer.pendingPdfAttachmentId
    }
    if (composer.showPdfPageDialog && pendingPdf != null) {
        val totalPages = pendingPdf.pageCount ?: 0
        val renderedPaths = pendingPdf.preRenderedPaths.orEmpty()
        val progress = composerSnapshot.pdfPreviewProgress[pendingPdf.localId] ?: (0 to totalPages)
        PdfPageSelectDialog(
            totalPages = totalPages,
            thumbnailPaths = renderedPaths,
            isLoading = renderedPaths.size < totalPages,
            renderProgress = progress,
            selectedPages = pdfViewerSelection,
            onTogglePage = { onTogglePdfSelection?.invoke(it) },
            onSelectAll = { select ->
                onTogglePdfSelection?.let { toggle ->
                    (0 until totalPages.coerceAtLeast(1)).forEach { index ->
                        if ((index in pdfViewerSelection) != select) toggle(index)
                    }
                }
            },
            onPreviewPage = { index ->
                composer.showPdfPageDialog = false
                composer.pdfDialogHiddenForPreview = true
                onPdfPreviewSelect?.invoke(renderedPaths, index)
            },
            onConfirm = { selection -> configurePdf(pendingPdf, selection.selectedPages) },
            onDismiss = { removePending(pendingPdf, pdf = true) },
        )
    }

    val pendingVideo = composerSnapshot.attachments.firstOrNull { attachment ->
        attachment.localId == composer.pendingVideoAttachmentId
    }
    if (composer.showVideoSliceDialog && pendingVideo != null) {
        val previewUri = pendingVideo.localPath
            ?.let { path -> Uri.fromFile(File(path)).toString() }
            ?: pendingVideo.uri
        VideoSliceDialog(
            videoUri = previewUri,
            durationMs = pendingVideo.videoDurationMs ?: 0L,
            onConfirm = { result ->
                composer.showVideoSliceDialog = false
                val ownerId = attachmentOwner(composer, composerOwnerId, pdf = false)
                if (isFrozen(ownerId)) {
                    composer.resetPendingVideoState()
                } else scope.launch {
                    composerController.load(ownerId)
                    try {
                        if (!isFrozen(ownerId)) {
                            composerController.configureVideo(
                                ownerId,
                                pendingVideo.localId,
                                result.frameCount,
                                result.intervalMs,
                            )
                        }
                    } finally {
                        composer.resetPendingVideoState()
                        releaseOwner(ownerId)
                    }
                }
            },
            onDismiss = { removePending(pendingVideo, pdf = false) },
        )
    }
}

internal suspend fun inspectAttachmentIngress(
    context: Context,
    uris: List<Uri>,
    forcedType: String?,
    allowLocalSandbox: Boolean,
): Pair<List<SelectedAttachment>, List<String?>> = withContext(Dispatchers.IO) {
    val rejected = mutableListOf<String?>()
    val attachments = uris.mapNotNull { uri ->
        val mimeType = FileValidator.resolveMimeType(context, uri.toString())
        FileValidator.inspectAttachment(
            source = uri.toString(),
            fileName = FileValidator.resolveFileName(context, uri),
            mimeType = mimeType,
            fileSize = FileValidator.resolveFileSize(context, uri),
            forcedType = forcedType,
            allowLocalSandbox = allowLocalSandbox,
        ).also { if (it == null) rejected += mimeType }
    }
    attachments to rejected
}

private fun attachmentOwner(
    composer: ChatComposerState,
    fallbackOwnerId: String,
    pdf: Boolean,
): String = if (pdf) {
    composer.pendingPdfOwnerId ?: fallbackOwnerId
} else {
    composer.pendingVideoOwnerId ?: fallbackOwnerId
}
