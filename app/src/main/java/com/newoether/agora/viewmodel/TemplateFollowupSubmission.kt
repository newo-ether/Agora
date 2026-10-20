package com.newoether.agora.viewmodel

import com.newoether.agora.model.*
import com.newoether.agora.templates.*
import com.newoether.agora.skills.SkillTemplateSource
import com.newoether.agora.skills.bindSkillTemplate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Cross-view exclusion for one in-flight gesture; selected graph remains the durable replay fence. */
private val submittingInteractiveCards = ConcurrentHashMap.newKeySet<String>()

internal suspend fun ChatViewModel.submitTemplateFollowup(
    conversationId: String, messageId: String, callId: String, answer: InteractiveAnswer,
    text: String, sourceFiles: List<String>, generation: MessageGenerationController, origin: ChatClient,
    captureWorkspace: () -> NewChatWorkspaceSnapshot,
    open: suspend (InteractiveTemplate, ForegroundSendTarget) -> LocalComposerResult,
): Boolean {
    val key = "$conversationId/$messageId/$callId"
    if (!submittingInteractiveCards.add(key)) return false
    val references = TemplateReferenceAttachments(getApplication<android.app.Application>()) { getApplication<android.app.Application>().filesDir }
    try {
        if (text.isBlank() || text.length > 4000 || !verifyInteractiveAnswer(conversationId, messageId, callId, answer)) return false
        val segments = messagePayloadHydration.loadMessages(conversationId, listOf(messageId)).singleOrNull()?.segments.orEmpty()
        val template = segments.firstOrNull { it.toolCallId == callId }?.let(::interactiveTemplateOf)
        val flow = template?.skillFlow
        require(template == null || flow != null) { "Run this template through a Skill; library previews cannot start an AI request" }
        fun current(): Boolean {
            if (!hasInteractiveAnswer(conversationId, messageId)) return false
            if (flow == null) return true
            val path = messages.value
            val index = path.indexOfFirst { it.id == messageId }
            if (index < 0) return false
            return path.drop(index + 1).all { it.participant == Participant.MODEL && it.runId == path[index].runId }
        }
        if (!current()) return false
        require(sourceFiles.size <= (if (template?.sourceFolderId != null) 16 else 1) &&
            sourceFiles.distinct().size == sourceFiles.size && sourceFiles.all {
                template != null && if (template.sourceFolderId != null) isTemplateFilePath(it) else it in template.resourceFiles
            }) { "Invalid selected files" }
        require(template?.sourceDirectory == null || sourceFiles.size == 1) { "Select one source file" }
        val target = generation.captureForegroundSendTarget(conversationId, currentConversationId.value,
            isNewChatMode.value, newChatEntryId.value, currentActiveModel.value, captureWorkspace) ?: return false
        val prompt = if (flow != null) {
            check(settings.accessSkills.value) { "Skill access is disabled" }
            flow.validate()
            val name = flow.target()
            val input = listOf(flow.input, text).filter(String::isNotBlank).joinToString("\n\n")
            require(input.length <= 8000) { "Workflow input is too long" }
            val prior = flow.sources + if (flow.sourcePath != null) listOf(SkillTemplateSource(flow.sourcePath, flow.sourceDirectory)) else emptyList()
            val sources = (prior + sourceFiles.map { SkillTemplateSource(it, template.sourceDirectory, template.sourceFolderId) }).distinct()
            require(sources.size <= 16) { "Too many selected references" }
            if (flow.remainingTemplates.isNotEmpty()) {
                val next = withContext(Dispatchers.IO) {
                    bindSkillTemplate(TemplateSources(sandboxManager, templateSourceContext(), interactiveTemplates.androidSources).prepare(
                        interactiveTemplates.get(flow.remainingTemplates.first())), flow.copy(input = input,
                        remainingTemplates = flow.remainingTemplates.drop(1), sources = sources,
                        sourceDirectory = null, sourcePath = null))
                }
                if (!current() || !verifyInteractiveAnswer(conversationId, messageId, callId, answer)) return false
                return open(next, target).acceptance != null
            }
            val document = if (name == null) null else withContext(Dispatchers.IO) { skillManager.document(name) }
            if (name != null) {
                flow.checkTarget(name)
                if (flow.phase == "after") {
                    val before = prepareSkillTemplate(checkNotNull(document), input, flow.parent, flow.ancestors)
                    if (before != null) {
                        if (!current() || !verifyInteractiveAnswer(conversationId, messageId, callId, answer)) return false
                        return open(before.copy(skillFlow = before.skillFlow?.copy(sources = sources)), target).acceptance != null
                    }
                }
            }
            val prepared = if (document == null) input else skillInvocationPrompt(document, input, flow.parent)
            var sourceBytes = 0
            sources.forEach { selected ->
                if (selected.folderId != null) withContext(Dispatchers.IO) {
                    references.addNative(checkNotNull(interactiveTemplates.androidSources), selected.folderId, selected.path)
                } else {
                    val source = withContext(Dispatchers.IO) {
                        if (selected.directory != null) TemplateSources(sandboxManager, templateSourceContext(), interactiveTemplates.androidSources).read(
                            template.copy(sourceDirectory = selected.directory, sourceFolderId = null, resourceFiles = listOf(selected.path)), selected.path)
                        else skillManager.readFile(selected.path)
                    }
                    sourceBytes += source.toByteArray().size
                    require(sourceBytes <= 128000) { "Selected references are too large" }
                    withContext(Dispatchers.IO) { references.add(selected.path, source) }
                }
            }
            prepared
        } else withContext(Dispatchers.IO) { resolveTemplatePrompt(template, text, sourceFiles, settings.accessSkills.value, skillManager::readFile) }
        if (!current()) return false
        val attachments = references.attachments
        val admission = generation.prepareForegroundSend(target, ConversationComposerSnapshot(text = prompt, attachments = attachments, loaded = true), origin) ?: return false
        if (!current() || !verifyInteractiveAnswer(conversationId, messageId, callId, answer)) return false
        references.handOffForSend()
        return generation.sendMessage(admission, prompt, attachments, {}, origin) != null
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { emitSnackbar(failure.message.orEmpty()); return false }
    finally {
        withContext(NonCancellable + Dispatchers.IO) { references.close() }
        submittingInteractiveCards.remove(key)
    }
}
