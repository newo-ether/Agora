package com.newoether.agora.ui.chat.bottombar

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.ui.common.AgoraHaptics
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.util.AttachmentFiles
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.FileValidator
import com.newoether.agora.viewmodel.ConversationComposerController
import com.newoether.agora.viewmodel.ConversationComposerSubmissionController
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CameraCaptureTarget(
    val uri: Uri,
    val privatePath: String,
)

/** UI-only coordination for attachment dialogs, camera capture, and rejection feedback. */
class ChatComposerState(
    private val context: Context,
    private val haptics: AgoraHaptics,
    private val scope: CoroutineScope,
    private val sandboxEnabled: () -> Boolean = { false },
    private val isSandboxFlavor: Boolean = false,
) {
    var showPdfPageDialog by mutableStateOf(false)
    var pdfDialogHiddenForPreview by mutableStateOf(false)
    var pendingPdfOwnerId by mutableStateOf<String?>(null)
    var pendingPdfAttachmentId by mutableStateOf<String?>(null)

    var showVideoSliceDialog by mutableStateOf(false)
    var pendingVideoOwnerId by mutableStateOf<String?>(null)
    var pendingVideoAttachmentId by mutableStateOf<String?>(null)

    private var rejectionMessageState by mutableStateOf<String?>(null)
    private var rejectionTitleState by mutableIntStateOf(
        com.newoether.agora.R.string.file_unsupported_title,
    )
    var rejectedMessage: String?
        get() = rejectionMessageState
        set(value) {
            rejectionMessageState = value
            rejectionTitleState = com.newoether.agora.R.string.file_unsupported_title
        }
    val rejectedTitleRes: Int
        get() = rejectionTitleState

    fun resetPendingPdfState() {
        showPdfPageDialog = false
        pdfDialogHiddenForPreview = false
        pendingPdfOwnerId = null
        pendingPdfAttachmentId = null
    }

    fun resetPendingVideoState() {
        showVideoSliceDialog = false
        pendingVideoOwnerId = null
        pendingVideoAttachmentId = null
    }

    /**
     * Creates the camera output inside Agora's private files directory and exposes only that path
     * through FileProvider. The system camera writes the full-resolution image directly.
     */
    suspend fun createCameraCaptureTarget(): CameraCaptureTarget? =
        withContext(Dispatchers.IO) {
            var target: java.io.File? = null
            try {
                val directory = java.io.File(context.filesDir, "images")
                check(directory.exists() || directory.mkdirs()) {
                    "Unable to create private image directory"
                }
                target = java.io.File(directory, "camera_${UUID.randomUUID()}.jpg")
                check(target.createNewFile()) { "Unable to create camera target" }
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    target,
                )
                CameraCaptureTarget(uri = uri, privatePath = target.absolutePath)
            } catch (error: Exception) {
                target?.let { runCatching { it.delete() } }
                DebugLog.e("ChatComposer", "Unable to prepare camera capture", error)
                null
            }
        }

    /** Commits a successful private camera target through the canonical attachment owner. */
    internal fun completeCameraCapture(
        ownerId: String,
        controller: ConversationComposerController,
        submissions: ConversationComposerSubmissionController,
        privatePath: String,
        captured: Boolean,
    ) {
        scope.launch {
            val (attachment, errorRes) = withContext(Dispatchers.IO) {
                val file = privateCameraFile(privatePath)
                val sandbox = acceptsLocalSandboxAttachments()
                when {
                    file == null || !captured || !file.isFile || file.length() <= 0L -> {
                        file?.let { runCatching { it.delete() } }
                        null to null
                    }
                    file.length() > AttachmentFiles.MAX_ATTACHMENT_BYTES -> {
                        runCatching { file.delete() }
                        null to com.newoether.agora.R.string.file_too_large
                    }
                    else -> SelectedAttachment(
                        uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file,
                        ).toString(),
                        type = "image",
                        storage = if (sandbox) {
                            com.newoether.agora.model.AttachmentStorage.LOCAL_SANDBOX_PENDING
                        } else com.newoether.agora.model.AttachmentStorage.APP_PRIVATE,
                        fileName = file.name,
                        mimeType = "image/jpeg",
                        fileSize = file.length(),
                        localPath = file.absolutePath,
                    ) to null
                }
            }
            if (attachment != null && !submissions.snapshot(ownerId).isFrozen) {
                runCatching {
                    controller.load(ownerId)
                    try {
                        if (submissions.snapshot(ownerId).isFrozen) {
                            false
                        } else {
                            controller.importAttachment(ownerId, attachment)
                        }
                    } finally {
                        withContext(NonCancellable) { controller.release(ownerId) }
                    }
                }
                    .onSuccess { imported ->
                        if (imported) {
                            haptics.selection()
                        } else {
                            withContext(NonCancellable + Dispatchers.IO) {
                                attachment.localPath?.let { path ->
                                    runCatching { java.io.File(path).delete() }
                                }
                            }
                        }
                    }
                    .onFailure { failure ->
                        withContext(NonCancellable + Dispatchers.IO) {
                            attachment.localPath?.let { path ->
                                runCatching { java.io.File(path).delete() }
                            }
                        }
                        if (failure is CancellationException) throw failure
                        rejectedMessage = context.getString(
                            com.newoether.agora.R.string.attachment_copy_failed_image,
                        )
                    }
            } else if (attachment != null) {
                withContext(NonCancellable + Dispatchers.IO) {
                    attachment.localPath?.let { path -> runCatching { java.io.File(path).delete() } }
                }
            } else if (errorRes != null) {
                rejectedMessage = context.getString(errorRes)
            }
        }
    }

    private fun privateCameraFile(path: String): java.io.File? = runCatching {
        val directory = java.io.File(context.filesDir, "images").canonicalFile
        java.io.File(path).canonicalFile.takeIf { file ->
            file.parentFile == directory && file.name.startsWith("camera_")
        }
    }.getOrNull()

    fun acceptsLocalSandboxAttachments(): Boolean = isSandboxFlavor && sandboxEnabled()

    fun reportUnsupportedFiles(mimeTypes: List<String?>) {
        if (mimeTypes.isEmpty()) return
        mimeTypes.distinct().forEach { appendRejection(unsupportedFileMessage(it)) }
    }

    fun reportCameraPreparationFailure() {
        rejectionTitleState = com.newoether.agora.R.string.camera
        rejectionMessageState = context.getString(
            com.newoether.agora.R.string.attachment_copy_failed_image,
        )
    }

    private fun unsupportedFileMessage(mimeType: String?): String {
        val error = if (mimeType == null) {
            FileValidator.Error.UNKNOWN_TYPE
        } else {
            FileValidator.Error.UNSUPPORTED_TYPE
        }
        val original = FileValidator.errorMessage(context, error, mimeType)
        return if (isSandboxFlavor) {
            "$original\n\n${context.getString(com.newoether.agora.R.string.file_sandbox_required)}"
        } else {
            original
        }
    }

    private fun appendRejection(message: String) {
        val existing = rejectionMessageState
        rejectionTitleState = com.newoether.agora.R.string.file_unsupported_title
        rejectionMessageState = when {
            existing.isNullOrBlank() -> message
            message in existing.lines() -> existing
            else -> "$existing\n$message"
        }
    }
}

@Composable
fun rememberChatComposerState(
    sandboxEnabled: () -> Boolean = { false },
    isSandboxFlavor: Boolean = false,
): ChatComposerState {
    val context = LocalContext.current
    val haptics = LocalAgoraHaptics.current
    val scope = rememberCoroutineScope()
    val latestSandboxEnabled = rememberUpdatedState(sandboxEnabled)
    return remember(context, haptics, scope, isSandboxFlavor) {
        ChatComposerState(
            context = context,
            haptics = haptics,
            scope = scope,
            sandboxEnabled = { latestSandboxEnabled.value() },
            isSandboxFlavor = isSandboxFlavor,
        )
    }
}
