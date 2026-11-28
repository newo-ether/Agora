package com.newoether.agora.ui.chat.bottombar

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import com.newoether.agora.model.apiModelName
import com.newoether.agora.model.ContextBudget
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.*
import androidx.compose.material3.Icon
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.theme.chatCompactWidth
import com.newoether.agora.viewmodel.ConversationComposerController
import com.newoether.agora.viewmodel.ConversationComposerSnapshot
import com.newoether.agora.viewmodel.ConversationComposerSubmissionController
import com.newoether.agora.viewmodel.QueuedSend
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.thinkingCapabilityForSelectedModel
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.OpenAiServiceTiers
import com.newoether.agora.model.ThinkingResolution
import com.newoether.agora.util.Constants
import com.newoether.agora.data.providerDisplayName
import com.newoether.agora.data.modelDisplayName
import com.newoether.agora.data.modelProviderNameShown
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.AgoraExposedDropdownMenu
internal val CHAT_BOTTOM_BAR_OUTER_RADIUS = 28.dp
internal val CHAT_BOTTOM_BAR_OUTER_SHAPE = RoundedCornerShape(CHAT_BOTTOM_BAR_OUTER_RADIUS)
// Non-expanded bar geometry, measured from the bar's outer edge.
// The controls capsule and the send button share one height and sit 10 dp from the start/end and
// bottom edges. They are not concentric with the outer corners: the owner chose larger controls.
// Narrow windows (see CHAT_COMPACT_WIDTH_DP) shrink both to 44 dp.
internal val COMPOSER_CONTROL_HEIGHT get() = if (chatCompactWidth) 44.dp else 48.dp
internal val COMPOSER_CONTROLS_INSET = 10.dp
// The expand icon sits 18 dp from the top and end edges. The expand button's circle is a fade to
// transparent, so only its 20 dp icon is placed, not the circle.
internal val COMPOSER_CORNER_CONTENT_INSET = 18.dp
// The input text starts 18 dp from the start edge and 16 dp from the top, and ends 22 dp above the
// controls.
internal val COMPOSER_TEXT_START_INSET = 18.dp
// Narrow windows (see CHAT_COMPACT_WIDTH_DP) tighten both to 13 dp and 18 dp.
internal val COMPOSER_TEXT_TOP_INSET get() = if (chatCompactWidth) 13.dp else 16.dp
internal val COMPOSER_TEXT_CONTROLS_GAP get() = if (chatCompactWidth) 18.dp else 22.dp
internal val COMPOSER_EXPAND_BUTTON_SIZE = 40.dp
internal val COMPOSER_EXPAND_ICON_SIZE = 20.dp
// The host padding the status rows, attachment previews and expanded collapse button share.
internal val COMPOSER_HOST_SIDE_PADDING = 4.dp
internal val COMPOSER_HOST_TOP_PADDING = 8.dp
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ChatBottomBar(
    submissionController: ConversationComposerSubmissionController,
    composerOwnerId: String,
    composerController: ConversationComposerController,
    composerSnapshot: ConversationComposerSnapshot,
    onStopGeneration: () -> Unit = {},
    isLoading: Boolean,
    isCompacting: Boolean = false,
    isSwitching: Boolean = false,
    enabledModels: Set<String>,
    selectedModel: String,
    modelAliases: Map<String, String> = emptyMap(),
    modelProviderNames: Map<String, Boolean> = emptyMap(),
    customProviders: List<CustomProviderConfig> = emptyList(),
    /** User Provider list order; providers outside it (Local) sort after it. */
    providerOrder: List<String> = emptyList(),
    codeExecutionEnabled: Boolean = false,
    googleSearchEnabled: Boolean = false,
    openAiWebSearchAvailable: Boolean = false,
    openAiWebSearchEnabled: Boolean = false,
    thinkingEnabled: Boolean = true,
    thinkingLevel: String = "medium",
    thinkingBudgetEnabled: Boolean = false,
    thinkingBudgetTokens: Int = 4096,
    openAiServiceTierAvailable: Boolean = false,
    openAiServiceTierEnabled: Boolean = false,
    openAiServiceTier: String = "auto",
    webSearchEnabled: Boolean = false,
    shellEnabled: Boolean = false,
    showLowContextMode: Boolean = false,
    lowContextModeEnabled: Boolean = false,
    /** A local model whose chat template cannot express tools; its tool switches are greyed. */
    selectedModelToolIncapable: Boolean = false,
    onCodeExecutionToggle: (Boolean) -> Unit = {},
    onGoogleSearchToggle: (Boolean) -> Unit = {},
    onOpenAiWebSearchToggle: (Boolean) -> Unit = {},
    onThinkingToggle: (Boolean) -> Unit = {},
    onThinkingLevelChange: (String) -> Unit = {},
    onThinkingBudgetEnabledChange: (Boolean) -> Unit = {},
    onThinkingBudgetTokensChange: (Int) -> Unit = {},
    onOpenAiServiceTierToggle: (Boolean) -> Unit = {},
    onOpenAiServiceTierChange: (String) -> Unit = {},
    onWebSearchToggle: (Boolean) -> Unit = {},
    onShellToggle: (Boolean) -> Unit = {},
    onLowContextModeToggle: (Boolean) -> Unit = {},
    onModelSelect: (String) -> Unit,
    onAllMediaClick: ((urls: List<String>, index: Int) -> Unit)? = null,
    onFileContentClick: ((fileName: String, content: String) -> Unit)? = null,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    onPdfPreviewSelect: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    pdfViewerSelection: Set<Int> = emptySet(),
    onTogglePdfSelection: ((Int) -> Unit)? = null,
    onInitPdfSelection: ((Set<Int>) -> Unit)? = null,
    fullScreenViewerUrls: List<String>? = null,
    modifier: Modifier = Modifier,
    textFieldState: TextFieldState = rememberSaveable(saver = TextFieldState.Saver) { TextFieldState() },
    composerState: ChatComposerState = rememberChatComposerState(),
    focusRequester: FocusRequester = FocusRequester(),
    onInputFocusChanged: (Boolean) -> Unit = {},
    isExpanded: Boolean = false,
    isExpandAnimating: Boolean = false,
    onCollapse: () -> Unit = {},
    onExpand: () -> Unit = {},
    showWebSearch: Boolean = true,
    showShell: Boolean = true,
    onAdvancedClick: () -> Unit = {},
    liveVoiceButton: @Composable () -> Unit = {},
    onToolsClick: () -> Unit = {},
    compactDefaultModel: String? = null,
    compactDefaultPrompt: String = "",
    compactDefaultRetainCount: Int = 6,
    contextEstimatedTokens: Int = 0,
    contextTokenBudget: Int = ContextBudget.DEFAULT_TOKENS,
    contextCompactThresholdPercent: Int = 90,
    contextCompactEnabled: Boolean = true,
    contextSystemPromptTokens: Int = 0,
    contextToolTokens: Int = 0,
    canCompact: Boolean = false,
    onCompactClick: () -> Unit = {},
    queuedSends: List<QueuedSend> = emptyList(),
    onRemoveQueuedSend: (String) -> Unit = {},
    onSendQueuedNow: () -> Unit = {},
    isStopping: Boolean = false,
) {
    val motionPolicy = LocalAgoraMotionPolicy.current
    val scrollState = rememberScrollState()
    BackHandler(enabled = isExpanded) { onCollapse() }
    val isModelValid = selectedModel.isNotBlank() && enabledModels.contains(selectedModel)
    val modelId = ModelId.parse(selectedModel)
    val thinkingCapability = remember(selectedModel, customProviders) {
        thinkingCapabilityForSelectedModel(selectedModel, customProviders)
    }
    val displayedThinking = ThinkingResolution.resolve(
        thinkingCapability,
        thinkingEnabled,
        thinkingLevel,
        thinkingBudgetEnabled,
        thinkingBudgetTokens,
    )
    val displayedThinkingLevel = displayedThinking.effort
        ?: thinkingCapability.nearestEffort(thinkingLevel) ?: thinkingLevel
    val displayedThinkingEnabled = displayedThinking.enabled
    val displayedThinkingBudgetEnabled = displayedThinking.budgetTokens != null
    val displayedThinkingBudgetTokens = displayedThinking.budgetTokens ?: thinkingBudgetTokens
    val availableServiceTiers = OpenAiServiceTiers.availableTiers(
        modelId.modelName,
        officialProvider = modelId.providerName == Constants.PROVIDER_OPENAI,
    )
    val displayedServiceTier = OpenAiServiceTiers.mappedTier(
        openAiServiceTier,
        modelId.modelName,
        officialProvider = modelId.providerName == Constants.PROVIDER_OPENAI,
    )
    val submissionState = remember(submissionController, composerOwnerId) {
        submissionController.observeState(composerOwnerId)
    }
    DisposableEffect(submissionController, composerOwnerId) {
        onDispose { submissionController.releaseState(composerOwnerId) }
    }
    val submission by submissionState.collectAsState()
    val composer = composerState
    val context = LocalContext.current
    val haptics = LocalAgoraHaptics.current
    val activityLaunchScope = rememberCoroutineScope()
    suspend fun withOwner(ownerId: String, action: suspend () -> Unit): Boolean {
        if (submissionController.snapshot(ownerId).isFrozen) return false
        composerController.load(ownerId)
        return try {
            if (submissionController.snapshot(ownerId).isFrozen) false else {
                action()
                true
            }
        } finally {
            withContext(NonCancellable) { composerController.release(ownerId) }
        }
    }
    fun importUris(ownerId: String, uris: List<Uri>, forcedType: String? = null, emitSuccessHaptic: Boolean = true) {
        if (uris.isEmpty() || submissionController.snapshot(ownerId).isFrozen) return
        activityLaunchScope.launch {
            val (attachments, rejected) = inspectAttachmentIngress(
                context,
                uris,
                forcedType,
                composer.acceptsLocalSandboxAttachments(),
            )
            if (submissionController.snapshot(ownerId).isFrozen) return@launch
            composer.reportUnsupportedFiles(rejected)
            if (attachments.isEmpty()) return@launch
            var imported = false
            if (withOwner(ownerId) {
                for (attachment in attachments) {
                    if (submissionController.snapshot(ownerId).isFrozen) break
                    imported = composerController.importAttachment(ownerId, attachment) || imported
                }
            } && imported && emitSuccessHaptic) haptics.selection()
        }
    }
    val clipboardImageReceiver = remember(context, composerOwnerId, composer) {
        object : ReceiveContentListener {
            override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                val imageUris = mutableListOf<Uri>()
                val pastedTexts = mutableListOf<String>()
                val sandboxPaste = composer.acceptsLocalSandboxAttachments()
                val advertisesImages = transferableContent.hasMediaType(MediaType.Image)
                val remaining = transferableContent.consume { item ->
                    val text = item.text?.toString()
                    if (sandboxPaste && text != null &&
                        text.length >= com.newoether.agora.util.SANDBOX_PASTE_MIN_CHARS
                    ) {
                        pastedTexts += text
                        return@consume true
                    }
                    val uri = item.uri ?: return@consume false
                    val resolvedMime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                    val isImage = resolvedMime?.startsWith("image/") == true ||
                        (resolvedMime == null && advertisesImages)
                    if (isImage || sandboxPaste) imageUris += uri
                    isImage || sandboxPaste
                }
                importUris(composerOwnerId, imageUris, if (sandboxPaste) null else "image", emitSuccessHaptic = false)
                if (pastedTexts.isNotEmpty()) {
                    val ownerId = composerOwnerId
                    activityLaunchScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        for (text in pastedTexts) {
                            var admitted = false
                            try {
                                withContext(NonCancellable) {
                                    withOwner(ownerId) {
                                        admitted = composerController.importAttachment(
                                            ownerId, com.newoether.agora.util.sandboxPastedAttachment(text),
                                        )
                                    }
                                }
                            } finally {
                                if (!admitted) {
                                    // The clipboard still owns the original. Refuse admission visibly;
                                    // inserting it into the field would silently restore inline sending.
                                    composer.rejectedMessage = context.getString(com.newoether.agora.R.string.attachment_copy_failed_image)
                                }
                            }
                        }
                    }
                }
                return remaining
            }
        }
    }
    val showThinkingSheetState = rememberSaveable { mutableStateOf(false) }
    var showThinkingSheet by showThinkingSheetState
    val showOpenAiServiceTierSheetState = rememberSaveable { mutableStateOf(false) }
    var showOpenAiServiceTierSheet by showOpenAiServiceTierSheetState
    LaunchedEffect(fullScreenViewerUrls) {
        if (
            fullScreenViewerUrls == null &&
            composer.pdfDialogHiddenForPreview &&
            composer.pendingPdfAttachmentId != null
        ) {
            composer.showPdfPageDialog = true
            composer.pdfDialogHiddenForPreview = false
        }
    }
    LaunchedEffect(openAiServiceTierAvailable) {
        if (!openAiServiceTierAvailable) showOpenAiServiceTierSheet = false
    }
    LaunchedEffect(composerOwnerId, composerSnapshot.attachments, submission.isFrozen) {
        if (submission.isFrozen) {
            composer.resetPendingPdfState()
            composer.resetPendingVideoState()
            return@LaunchedEffect
        }
        if (composer.pendingPdfOwnerId != null && composer.pendingPdfOwnerId != composerOwnerId) {
            composer.resetPendingPdfState()
        }
        if (
            composer.pendingPdfOwnerId == composerOwnerId &&
            composer.pendingPdfAttachmentId != null &&
            composerSnapshot.attachments.none {
                it.localId == composer.pendingPdfAttachmentId &&
                    it.type == "pdf" && it.importState == AttachmentImportState.PROCESSING &&
                    it.selectedPages == null
            }
        ) composer.resetPendingPdfState()
        if (composer.pendingPdfAttachmentId == null) {
            composerSnapshot.attachments.firstOrNull {
                it.type == "pdf" && it.importState == AttachmentImportState.PROCESSING &&
                    it.selectedPages == null && (it.pageCount ?: 0) > 0
            }?.let { attachment ->
                composer.pendingPdfOwnerId = composerOwnerId
                composer.pendingPdfAttachmentId = attachment.localId
                composer.showPdfPageDialog = true
                onInitPdfSelection?.invoke((0 until minOf(attachment.pageCount ?: 0, 5)).toSet())
            }
        }
        if (composer.pendingVideoOwnerId != null && composer.pendingVideoOwnerId != composerOwnerId) {
            composer.resetPendingVideoState()
        }
        if (
            composer.pendingVideoOwnerId == composerOwnerId &&
            composer.pendingVideoAttachmentId != null &&
            composerSnapshot.attachments.none {
                it.localId == composer.pendingVideoAttachmentId &&
                    it.type == "video" && it.importState == AttachmentImportState.PROCESSING &&
                    it.frameCount == null
            }
        ) composer.resetPendingVideoState()
        if (composer.pendingVideoAttachmentId == null && composer.pendingPdfAttachmentId == null) {
            composerSnapshot.attachments.firstOrNull {
                it.type == "video" && it.importState == AttachmentImportState.PROCESSING &&
                    it.frameCount == null && it.localPath != null &&
                    it.storage != com.newoether.agora.model.AttachmentStorage.LOCAL_SANDBOX_PENDING
            }?.let { attachment ->
                composer.pendingVideoOwnerId = composerOwnerId
                composer.pendingVideoAttachmentId = attachment.localId
                composer.showVideoSliceDialog = true
            }
        }
    }

    var pendingPhotoOwnerId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingVideoPickerOwnerId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFileOwnerId by rememberSaveable { mutableStateOf<String?>(null) }
    val photoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> pendingPhotoOwnerId?.let { importUris(it, uris, "image") }; pendingPhotoOwnerId = null }
    val videoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> pendingVideoPickerOwnerId?.let { importUris(it, uris, "video") }; pendingVideoPickerOwnerId = null }
    val fileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris -> pendingFileOwnerId?.let { importUris(it, uris) }; pendingFileOwnerId = null }
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraOwnerId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraPermissionPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraPermissionOwnerId by rememberSaveable { mutableStateOf<String?>(null) }
    var internalCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var internalCameraOwnerId by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { captured ->
        val path = pendingCameraPath
        val ownerId = pendingCameraOwnerId
        pendingCameraPath = null
        pendingCameraOwnerId = null
        if (path != null && ownerId != null) composer.completeCameraCapture(
            ownerId, composerController, submissionController, path, captured,
        )
    }
    val cameraPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val path = pendingCameraPermissionPath
        val ownerId = pendingCameraPermissionOwnerId
        pendingCameraPermissionPath = null
        pendingCameraPermissionOwnerId = null
        if (granted && path != null && ownerId != null && !submissionController.snapshot(ownerId).isFrozen) {
            internalCameraPath = path
            internalCameraOwnerId = ownerId
        } else if (path != null && ownerId != null) {
            composer.completeCameraCapture(
                ownerId, composerController, submissionController, path, captured = false,
            )
            if (!granted) composer.reportCameraPreparationFailure()
        }
    }
    fun launchInternalCamera(ownerId: String, privatePath: String) {
        if (
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            internalCameraPath = privatePath
            internalCameraOwnerId = ownerId
        } else {
            pendingCameraPermissionPath = privatePath
            pendingCameraPermissionOwnerId = ownerId
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }
    val voice = rememberComposerVoice(composerOwnerId, enabled = composerSnapshot.loaded && !submission.isFrozen) {
        textFieldState.insertTranscript(it)
    }
    val latestSnapshot by rememberUpdatedState(composerSnapshot)
    val voiceCanSend = composerSnapshot.loaded && isModelValid && !isSwitching && !isStopping && !submission.isFrozen
    ChatComposerLayout(
        textFieldState = textFieldState,
        focusRequester = focusRequester,
        onInputFocusChanged = onInputFocusChanged,
        isExpanded = isExpanded,
        isExpandAnimating = isExpandAnimating,
        onExpand = onExpand,
        onCollapse = onCollapse,
        modifier = modifier,
        inputModifier = Modifier.contentReceiver(clipboardImageReceiver),
        scrollState = scrollState,
        statusContent = {
            ComposerStatusColumn(
                queuedSends = queuedSends,
                onRemoveQueuedSend = onRemoveQueuedSend,
                modifier = Modifier.zIndex(0f),
            )
        },
        attachmentContent = {
        if (composerSnapshot.attachments.isNotEmpty()) {
            AttachmentPreviewRow(
                attachments = composerSnapshot.attachments,
                editable = !submission.isFrozen,
                onRemove = { id -> activityLaunchScope.launch { withOwner(composerOwnerId) { composerController.remove(composerOwnerId, id) } } },
                onRetry = { id -> activityLaunchScope.launch { withOwner(composerOwnerId) { composerController.retry(composerOwnerId, id) } } },
                onAllMediaClick = onAllMediaClick,
                onFileContentClick = onFileContentClick,
                onPdfPagesClick = onPdfPagesClick,
            )
        }
        },
    ) {
        ComposerVoiceControls(voice = voice, controls = {
            ComposerControlGroup(voice = voice) {
                AttachmentAddMenu(
                    enabled = !submission.isFrozen,
                    onCamera = {
                        activityLaunchScope.launch {
                            val target = composer.createCameraCaptureTarget()
                            if (target == null) {
                                composer.reportCameraPreparationFailure()
                                return@launch
                            }
                            if (canLaunchSystemImageCapture(context)) {
                                pendingCameraPath = target.privatePath
                                pendingCameraOwnerId = composerOwnerId
                                runCatching { cameraLauncher.launch(target.uri) }
                                    .onFailure {
                                        pendingCameraPath = null
                                        pendingCameraOwnerId = null
                                        launchInternalCamera(composerOwnerId, target.privatePath)
                                    }
                            } else {
                                launchInternalCamera(composerOwnerId, target.privatePath)
                            }
                        }
                    },
                    onPhotos = {
                        pendingPhotoOwnerId = composerOwnerId
                        photoLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts
                                    .PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    onVideos = {
                        pendingVideoPickerOwnerId = composerOwnerId
                        videoLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts
                                    .PickVisualMedia.VideoOnly,
                            ),
                        )
                    },
                    onFiles = { pendingFileOwnerId = composerOwnerId; fileLauncher.launch("*/*") },
                )
                liveVoiceButton()
                val activeMenuState = remember { mutableStateOf<String?>(null) }
                var activeMenu by activeMenuState
                var lastModelDismissTime by remember { mutableLongStateOf(0L) }
                var lastContextDismissTime by remember { mutableLongStateOf(0L) }
                var lastToolsDismissTime by remember { mutableLongStateOf(0L) }

                val selectedProvider = providerDisplayName(
                    com.newoether.agora.model.ModelId.parse(selectedModel).providerName,
                    customProviders,
                )
                val capabilityControlsEnabled = !lowContextModeEnabled
                val displayText = when {
                    isModelValid -> modelDisplayName(selectedModel, modelAliases, customProviders, modelProviderNameShown(selectedModel, modelProviderNames))
                    enabledModels.isNotEmpty() -> stringResource(R.string.select_model)
                    else -> stringResource(R.string.no_model_selected)
                }
                
                ComposerModelSelector(
                    displayText = displayText,
                    isModelValid = isModelValid,
                    expanded = activeMenu == "model",
                    onClick = {
                        val now = System.currentTimeMillis()
                        if (activeMenu == "model") activeMenu = null
                        else if (now - lastModelDismissTime > 200) activeMenu = "model"
                    },
                    onDismissRequest = {
                        if (activeMenu == "model") {
                            activeMenu = null
                            lastModelDismissTime = System.currentTimeMillis()
                        }
                    },
                ) {
                    if (enabledModels.isEmpty()) {
                        AgoraDropdownMenuItem(
                            text = { Text(stringResource(R.string.models_no_models)) },
                            onClick = {
                                activeMenu = null
                                lastModelDismissTime = 0L // Reset to allow immediate re-open
                            },
                            enabled = false
                        )
                    } else {
                        // Grouped by provider in the user's Provider list order, then
                        // alphabetical. enabledModels is a Set whose iteration order is insertion
                        // order (i.e. whenever each model was enabled), which scrambles providers.
                        val sortedModels = remember(enabledModels, customProviders, providerOrder) {
                            val positions = providerOrder.withIndex().associate { (index, name) -> name to index }
                            enabledModels.sortedWith(
                                compareBy(
                                    {
                                        positions[
                                            providerDisplayName(
                                                com.newoether.agora.model.ModelId.parse(it).providerName,
                                                customProviders,
                                            )
                                        ] ?: Int.MAX_VALUE
                                    },
                                    {
                                        providerDisplayName(
                                            com.newoether.agora.model.ModelId.parse(it).providerName,
                                            customProviders,
                                        ).lowercase()
                                    },
                                    { com.newoether.agora.model.ModelId.parse(it).apiModelName.lowercase() },
                                )
                            )
                        }
                        sortedModels.forEach { model ->
                            ComposerModelMenuItem(
                                displayText = modelDisplayName(model, modelAliases, customProviders, modelProviderNameShown(model, modelProviderNames)),
                                selected = model == selectedModel,
                                onClick = {
                                    haptics.selection()
                                    onModelSelect(model)
                                    activeMenu = null
                                    lastModelDismissTime = 0L
                                }
                            )
                        }
                    }
                }

                ComposerContextIndicator(
                    estimatedTokens = contextEstimatedTokens,
                    tokenBudget = contextTokenBudget,
                    compactThresholdPercent = contextCompactThresholdPercent,
                    compactEnabled = contextCompactEnabled,
                    systemPromptTokens = contextSystemPromptTokens,
                    toolTokens = contextToolTokens,
                    expanded = activeMenu == "context",
                    onClick = {
                        val now = System.currentTimeMillis()
                        if (activeMenu == "context") activeMenu = null
                        else if (now - lastContextDismissTime > 200) activeMenu = "context"
                    },
                    onDismissRequest = {
                        if (activeMenu == "context") {
                            activeMenu = null
                            lastContextDismissTime = System.currentTimeMillis()
                        }
                    },
                )

                ExposedDropdownMenuBox(
                    expanded = activeMenu == "tools",
                    onExpandedChange = { }
                ) {
                    IconButton(
                        onClick = { 
                            val now = System.currentTimeMillis()
                            if (activeMenu == "tools") {
                                activeMenu = null
                            } else if (now - lastToolsDismissTime > 200) {
                                activeMenu = "tools"
                            }
                        }, 
                        modifier = Modifier.size(32.dp).menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                    ) {
                        Icon(Icons.Rounded.MoreVert, stringResource(R.string.tools), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    
                    AgoraExposedDropdownMenu(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        expanded = activeMenu == "tools",
                        onDismissRequest = {
                            if (activeMenu == "tools") {
                                activeMenu = null
                                lastToolsDismissTime = System.currentTimeMillis()
                            }
                        },
                        matchAnchorWidth = false,
                    ) {
                        ComposerToolsMenuContent(
                            activeMenuState = activeMenuState,
                            showThinkingSheetState = showThinkingSheetState,
                            showOpenAiServiceTierSheetState = showOpenAiServiceTierSheetState,
                            showLowContextMode = showLowContextMode,
                            lowContextModeEnabled = lowContextModeEnabled,
                            onLowContextModeToggle = onLowContextModeToggle,
                            thinkingEnabled = displayedThinkingEnabled,
                            thinkingLevel = displayedThinkingLevel,
                            thinkingBudgetEnabled = displayedThinkingBudgetEnabled,
                            thinkingBudgetTokens = displayedThinkingBudgetTokens,
                            onThinkingToggle = onThinkingToggle,
                            thinkingCanDisable = thinkingCapability.canDisableThinking,
                            selectedProvider = selectedProvider,
                            isModelValid = isModelValid,
                            codeExecutionEnabled = codeExecutionEnabled,
                            onCodeExecutionToggle = onCodeExecutionToggle,
                            googleSearchEnabled = googleSearchEnabled,
                            onGoogleSearchToggle = onGoogleSearchToggle,
                            capabilityControlsEnabled = capabilityControlsEnabled,
                            openAiServiceTierAvailable = openAiServiceTierAvailable,
                            openAiServiceTierEnabled = openAiServiceTierEnabled,
                            openAiServiceTier = displayedServiceTier,
                            onOpenAiServiceTierToggle = onOpenAiServiceTierToggle,
                            openAiWebSearchAvailable = openAiWebSearchAvailable,
                            openAiWebSearchEnabled = openAiWebSearchEnabled,
                            onOpenAiWebSearchToggle = onOpenAiWebSearchToggle,
                            showWebSearch = showWebSearch,
                            webSearchEnabled = webSearchEnabled,
                            onWebSearchToggle = onWebSearchToggle,
                            showShell = showShell,
                            shellEnabled = shellEnabled,
                            onShellToggle = onShellToggle,
                            toolControlsEnabled = !selectedModelToolIncapable,
                            canCompact = canCompact,
                            isCompacting = isCompacting,
                            onCompactClick = onCompactClick,
                            onAdvancedClick = onAdvancedClick,
                            onToolsClick = onToolsClick,
                        )
                    }
                }
            }
        }) {
            // While recording, send stops capture, transcribes into the draft and submits it;
            // submit() queues behind a running generation instead of interrupting it. The voice
            // state rides into the same button call so its colours fade instead of snapping.
            ComposerSendButton(
                textFieldState = textFieldState,
                ownerId = composerOwnerId,
                snapshot = composerSnapshot,
                submissionController = submissionController,
                submission = submission,
                isLoading = isLoading,
                isSwitching = isSwitching,
                isStopping = isStopping,
                isModelValid = isModelValid,
                hasQueuedSends = queuedSends.isNotEmpty(),
                onSendQueued = onSendQueuedNow,
                onStopGeneration = onStopGeneration,
                onCollapse = onCollapse,
                voiceAction = voice?.takeIf { it.active }?.let { active ->
                    ComposerVoiceSendAction(
                        isActionable = active.phase == ComposerVoicePhase.RECORDING && voiceCanSend,
                        isBusy = active.sendPending,
                        onClick = {
                            active.finishAndSend {
                                submissionController.submit(
                                    ownerId = composerOwnerId,
                                    text = textFieldState.text.toString(),
                                    attachmentIds = latestSnapshot.attachments.map { it.localId },
                                )
                            }
                        },
                    )
                },
            )
        }
    }

    ChatBottomBarOverlayHost(
        showThinkingSheet = showThinkingSheet,
        onDismissThinkingSheet = { showThinkingSheet = false },
        thinkingEnabled = displayedThinkingEnabled,
        thinkingLevel = displayedThinkingLevel,
        thinkingCapability = thinkingCapability,
        thinkingBudgetEnabled = displayedThinkingBudgetEnabled,
        thinkingBudgetTokens = displayedThinkingBudgetTokens,
        onThinkingToggle = onThinkingToggle,
        onThinkingLevelChange = onThinkingLevelChange,
        onThinkingBudgetEnabledChange = onThinkingBudgetEnabledChange,
        onThinkingBudgetTokensChange = onThinkingBudgetTokensChange,
        showOpenAiServiceTierSheet = showOpenAiServiceTierSheet,
        openAiServiceTierAvailable = openAiServiceTierAvailable,
        onDismissOpenAiServiceTierSheet = { showOpenAiServiceTierSheet = false },
        openAiServiceTierEnabled = openAiServiceTierEnabled,
        openAiServiceTier = displayedServiceTier,
        availableServiceTiers = availableServiceTiers,
        onOpenAiServiceTierToggle = onOpenAiServiceTierToggle,
        onOpenAiServiceTierChange = onOpenAiServiceTierChange,
        internalCameraPath = internalCameraPath,
        internalCameraOwnerId = internalCameraOwnerId,
        onInternalCameraCleared = { internalCameraPath = null; internalCameraOwnerId = null },
        composerOwnerId = composerOwnerId,
        composerController = composerController,
        submissionController = submissionController,
        composerSnapshot = composerSnapshot,
        composer = composer,
        pdfViewerSelection = pdfViewerSelection,
        onTogglePdfSelection = onTogglePdfSelection,
        onPdfPreviewSelect = onPdfPreviewSelect,
    )

}
