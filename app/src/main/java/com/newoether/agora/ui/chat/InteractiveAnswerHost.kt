package com.newoether.agora.ui.chat

import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.newoether.agora.ui.components.DialogWindowEinkPresentation
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.settings.SkillEditorPage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.ui.chat.message.LocalInteractiveConversationId
import com.newoether.agora.ui.chat.message.LocalSendInteractiveAnswer
import com.newoether.agora.ui.chat.message.LocalOpenInteractiveAnswer
import com.newoether.agora.viewmodel.accessTemplateFile
import com.newoether.agora.ui.chat.message.LocalTemplateFileAccess
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.hasInteractiveAnswer
import com.newoether.agora.viewmodel.verifyInteractiveAnswer

private data class OpenInteractiveAnswer(val conversationId: String, val messageId: String, val callId: String, val answer: InteractiveAnswer, val linkedSkill: String?, val confirmPrompt: Boolean, val resourceFiles: List<String>, val sourceDirectory: String?, val sourceFolderId: String?, val referencePrefix: String, val sourceFilesAuto: Boolean)

/** One viewer for the selected chat; branch/selection changes revoke its originating artifact. */
@Composable
internal fun InteractiveAnswerHost(viewModel: ChatViewModel, content: @Composable () -> Unit) {
    var selected by remember { mutableStateOf<OpenInteractiveAnswer?>(null) }
    var editing by remember { mutableStateOf<OpenInteractiveAnswer?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val conversationId by viewModel.currentConversationId.collectAsState()
    val messages by viewModel.messages.collectAsState()
    LaunchedEffect(conversationId, messages, selected) {
        selected?.let {
            if (!viewModel.hasInteractiveAnswer(it.conversationId, it.messageId)) { selected = null; editing = null }
        }
    }
    val originatingConversationId = conversationId
    CompositionLocalProvider(
        LocalInteractiveConversationId provides originatingConversationId,
        LocalTemplateFileAccess provides { messageId, callId, answer, request ->
            viewModel.accessTemplateFile(checkNotNull(originatingConversationId), messageId, callId, answer, request)
        },
        LocalSendInteractiveAnswer provides { messageId, callId, answer, text ->
            originatingConversationId?.let { id -> viewModel.sendInteractiveFollowup(id, messageId, callId, answer, text.text, text.sourceFiles) } ?: false
        },
        LocalOpenInteractiveAnswer provides { messageId, callId, answer, linkedSkill ->
            conversationId?.let { id ->
                scope.launch {
                    try {
                        if (!viewModel.verifyInteractiveAnswer(id, messageId, callId, answer)) return@launch
                        val template = viewModel.messagePayloadHydration.loadMessages(id, listOf(messageId))
                            .singleOrNull()?.segments?.firstOrNull { it.toolCallId == callId }
                            ?.let { com.newoether.agora.templates.interactiveTemplateOf(it) }
                        if (viewModel.hasInteractiveAnswer(id, messageId)) selected = OpenInteractiveAnswer(id, messageId, callId, answer, linkedSkill,
                            confirmPrompt = template == null, resourceFiles = template?.resourceFiles.orEmpty(), sourceDirectory = template?.sourceDirectory, sourceFolderId = template?.sourceFolderId, referencePrefix = template?.referencePrefix.orEmpty(), sourceFilesAuto = template?.sourceFilesAuto ?: true)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (failure: Exception) { viewModel.emitSnackbar(failure.message.orEmpty()) }
                }
            }
        },
    ) { content() }
    selected?.takeIf { editing == null }?.let { request ->
        InteractiveAnswerViewer(request.answer, onFileAccess = { file ->
            viewModel.accessTemplateFile(request.conversationId, request.messageId, request.callId, request.answer, file)
        }, onClose = { selected = null }, onSave = {
            if (request.answer.skillDraft != null) {
                if (viewModel.verifyInteractiveAnswer(request.conversationId, request.messageId, request.callId, request.answer)) editing = request
                false
            } else saveExpandedTemplate(viewModel, request)
        }, confirmPrompt = request.confirmPrompt, promptPrefix = com.newoether.agora.templates.templateSkillPrefix(request.linkedSkill), onSend = { text ->
            viewModel.sendInteractiveFollowup(request.conversationId, request.messageId, request.callId, request.answer, text.text, text.sourceFiles)
        })
    }
    editing?.let { request ->
        val draft = request.answer.skillDraft ?: return@let
        Dialog(onDismissRequest = { if (!saving) editing = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            DialogWindowEinkPresentation(LocalAgoraMotionPolicy.current.eInkMode)
            SkillEditorPage(viewModel, draft, draft.description, saving,
                onBack = { if (!saving) editing = null }, onSave = { document, description ->
                    if (!saving) {
                        saving = true
                        scope.launch {
                            try {
                                check(viewModel.verifyInteractiveAnswer(request.conversationId, request.messageId, request.callId, request.answer)) {
                                    viewModel.getApplication<android.app.Application>().getString(com.newoether.agora.R.string.interactive_answer_unavailable)
                                }
                                withContext(Dispatchers.IO) {
                                    viewModel.skillManager.saveDocument(document, description)
                                }
                                editing = null; selected = null
                                viewModel.emitSnackbar(viewModel.getApplication<android.app.Application>().getString(com.newoether.agora.R.string.skill_saved))
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (failure: Exception) { viewModel.emitSnackbar(failure.message.orEmpty()) }
                            finally { saving = false }
                        }
                    }
                })
        }
    }

}

private suspend fun saveExpandedTemplate(viewModel: ChatViewModel, request: OpenInteractiveAnswer): Boolean {
    if (!viewModel.verifyInteractiveAnswer(request.conversationId, request.messageId, request.callId, request.answer)) return false
    return try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            viewModel.interactiveTemplates.save(com.newoether.agora.templates.InteractiveTemplate(
                name = request.answer.title, description = request.answer.description,
                answer = request.answer.copy(jsExpressions = request.answer.jsExpressions.filterNot {
                    it.startsWith("globalThis.agoraSourceFiles =") || it.startsWith("globalThis.agoraReferences =") || it.startsWith("globalThis.agoraWorkflowInput =")
                }), sourceDirectory = request.sourceDirectory,
                resourceFiles = request.resourceFiles.takeIf { request.sourceDirectory == null }.orEmpty(),
                sourceFolderId = request.sourceFolderId, referencePrefix = request.referencePrefix, sourceFilesAuto = request.sourceFilesAuto,
            ))
        }
        viewModel.emitSnackbar(viewModel.getApplication<android.app.Application>().getString(com.newoether.agora.R.string.template_saved))
        true
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (failure: Exception) { viewModel.emitSnackbar(failure.message.orEmpty()); false }
}
