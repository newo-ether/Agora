package com.newoether.agora.viewmodel

import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.AttachmentFiles
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** Owns durable attachment import sessions independently for every composer draft owner. */
internal class ConversationComposerController(
    private val scope: CoroutineScope,
    private val drafts: ComposerDraftController,
    private val processor: AttachmentImportProcessor,
    private val sandboxHomeDir: () -> File? = { null },
) {
    private val sessionsMutex = Mutex()
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, ComposerOwnerSession>()
    private var selectionOrder = 0L
    @Volatile
    private var selectedOwnerId: String? = null

    private val processingQueue = ComposerAttachmentProcessingQueue { selectedOwnerId }
    init {
        scope.coroutineContext[Job]?.invokeOnCompletion {
            sessions.values.forEach(AttachmentFiles::releaseLivePaths)
        }
    }

    /** Admits one exact owner until the matching [release] call. */
    suspend fun load(ownerId: String): ConversationComposerSnapshot = load(ownerId, selected = false)

    /** Admits the exact UI-selected owner and prioritizes its queued attachment work. */
    suspend fun loadSelected(ownerId: String): ConversationComposerSnapshot =
        load(ownerId, selected = true)

    private suspend fun load(
        ownerId: String,
        selected: Boolean,
    ): ConversationComposerSnapshot {
        val session = withContext(NonCancellable) {
            sessionsMutex.withLock {
                sessions.getOrPut(ownerId, ::ComposerOwnerSession).also {
                    it.retainCount += 1
                    if (selected) {
                        it.selectedRetainCount += 1
                        selectionOrder += 1L
                        it.selectionOrder = selectionOrder
                        selectedOwnerId = ownerId
                    }
                    it.commandCount += 1
                }
            }
        }
        var admitted = false
        return try {
            if (selected) processingQueue.refreshProcessingPriority()
            ensureLoaded(ownerId, session).also { admitted = true }
        } finally {
            withContext(NonCancellable) {
                if (!admitted) releaseRetain(ownerId, session, selected)
                releaseCommand(ownerId, session)
            }
        }
    }

    suspend fun release(ownerId: String) = release(ownerId, selected = false)

    suspend fun releaseSelected(ownerId: String) = release(ownerId, selected = true)

    private suspend fun release(ownerId: String, selected: Boolean) =
        withContext(NonCancellable) {
            val session = sessionsMutex.withLock {
                sessions[ownerId]
            } ?: return@withContext
            releaseRetain(ownerId, session, selected)
            evictIfInactive(ownerId, session)
        }

    suspend fun state(
        ownerId: String,
    ): kotlinx.coroutines.flow.StateFlow<ConversationComposerSnapshot> = sessionsMutex.withLock {
        checkNotNull(sessions[ownerId]) { "Composer owner is not admitted" }.state
    }

    suspend fun freezeSubmission(
        ownerId: String, requestId: Long, text: String, attachmentIds: List<String>,
    ): ConversationComposerSnapshot? = withSession(ownerId) { session ->
        ensureLoaded(ownerId, session)
        withContext(NonCancellable) {
            session.mutex.withLock {
                if (session.frozenSubmissionId != null) return@withLock null
                if (session.state.value.attachments.map { it.localId } != attachmentIds) {
                    return@withLock null
                }
                val current = session.state.value
                val revisionBeforeFreeze = session.durable.revision
                val result = drafts.persist(
                    ownerId, revisionBeforeFreeze, current.text, session.durable.attachments,
                )
                if (!result.succeeded) return@withLock null
                if (!result.matchesRequested) {
                    reloadAndMergeLocked(ownerId, session)
                    return@withLock null
                }
                session.durable = session.durable.copy(text = current.text, revision = result.revision)
                session.publish(current.copy(revision = result.revision))
                session.frozenSubmissionId = requestId
                // Preparation uses tap-time text. If newer text was already visible, accepted clear
                // compares against the pre-persist revision and therefore preserves that edit.
                session.state.value.copy(
                    text = text,
                    revision = if (current.text != text) revisionBeforeFreeze else result.revision,
                )
            }
        }
    }

    suspend fun releaseSubmission(ownerId: String, requestId: Long): Boolean =
        withSession(ownerId) { session ->
            session.mutex.withLock {
                if (session.frozenSubmissionId != requestId) return@withLock false
                session.frozenSubmissionId = null
                true
            }
        }
    private suspend fun <T> withSession(
        ownerId: String,
        block: suspend (ComposerOwnerSession) -> T,
    ): T {
        val session = sessionsMutex.withLock {
            checkNotNull(sessions[ownerId]) { "Composer owner is not admitted" }.also {
                it.commandCount += 1
            }
        }
        return try {
            block(session)
        } finally {
            withContext(NonCancellable) {
                releaseCommand(ownerId, session)
            }
        }
    }
    private suspend fun releaseRetain(
        ownerId: String,
        session: ComposerOwnerSession,
        selected: Boolean = false,
    ) {
        var priorityChanged = false
        sessionsMutex.withLock {
            if (sessions[ownerId] !== session) return@withLock
            check(session.retainCount > 0) { "Composer owner is not retained" }
            session.retainCount -= 1
            if (selected) {
                check(session.selectedRetainCount > 0) { "Composer owner is not selected" }
                session.selectedRetainCount -= 1
                selectedOwnerId = sessions.entries
                    .filter { it.value.selectedRetainCount > 0 }
                    .maxByOrNull { it.value.selectionOrder }
                    ?.key
                priorityChanged = true
            }
        }
        if (priorityChanged) processingQueue.refreshProcessingPriority()
    }
    private suspend fun releaseCommand(ownerId: String, session: ComposerOwnerSession) {
        sessionsMutex.withLock {
            if (sessions[ownerId] !== session) return@withLock
            check(session.commandCount > 0) { "Composer command is not pinned" }
            session.commandCount -= 1
        }
        evictIfInactive(ownerId, session)
    }
    private suspend fun evictIfInactive(ownerId: String, session: ComposerOwnerSession) {
        sessionsMutex.withLock {
            if (
                sessions[ownerId] !== session ||
                session.retainCount != 0 ||
                session.commandCount != 0 ||
                session.jobCount.get() != 0 ||
                session.frozenSubmissionId != null
            ) {
                return@withLock
            }
            sessions.remove(ownerId)
            AttachmentFiles.releaseLivePaths(session)
            drafts.evictCached(ownerId)
        }
    }
    private suspend fun ensureLoaded(
        ownerId: String,
        session: ComposerOwnerSession,
    ): ConversationComposerSnapshot = session.mutex.withLock {
        if (session.state.value.loaded) return@withLock session.state.value
        val loaded = drafts.load(ownerId).toSnapshot()
        session.durable = loaded
        session.publish(loaded)
        loaded.attachments
            .filter { it.shouldStartProcessingJob() }
            .forEach { attachment ->
                val generation = session.nextGeneration(attachment.localId)
                session.registerJobLocked(
                    attachmentId = attachment.localId,
                    generation = generation,
                    job = if (attachment.localPath.isNullOrBlank()) {
                        stagingJob(ownerId, session, attachment, generation)
                    } else processingJob(ownerId, session, attachment, generation),
                )
            }
        session.state.value
    }

    suspend fun importAttachment(ownerId: String, attachment: SelectedAttachment): Boolean =
        withSession(ownerId) { session ->
            ensureLoaded(ownerId, session)
            session.importAttachment(attachment) { processing, generation ->
                stagingJob(ownerId, session, processing, generation)
            }
        }

    /** Atomically imports an already copied attachment through this draft's existing owner. */
    suspend fun attachPrepared(ownerId: String, attachment: SelectedAttachment): Boolean =
        withSession(ownerId) { session ->
            ensureLoaded(ownerId, session)
            session.attachPrepared(drafts, ownerId, attachment) { reloadAndMergeLocked(ownerId, session) }
        }

    suspend fun attachPreparedMany(ownerId:String, attachments:List<SelectedAttachment>):Boolean =
        withSession(ownerId) { session ->
            ensureLoaded(ownerId,session)
            session.attachPreparedMany(drafts, ownerId, attachments,
                reload = { reloadAndMergeLocked(ownerId, session) },
                processingJob = { attachment, generation -> processingJob(ownerId, session, attachment, generation) })
        }

    suspend fun updateText(ownerId: String, text: String) = withSession(ownerId) { session ->
        ensureLoaded(ownerId, session)
        session.mutex.withLock {
            session.publish(session.state.value.copy(text = text))
        }
    }

    suspend fun appendText(ownerId: String, text: String): String = withSession(ownerId) { session ->
        ensureLoaded(ownerId, session)
        session.mutex.withLock {
            val current = session.state.value
            val combined = if (current.text.isEmpty()) text else current.text + "\n\n" + text
            session.publish(current.copy(text = combined))
            combined
        }
    }

    suspend fun persistText(ownerId: String, text: String): Boolean =
        withSession(ownerId) { session ->
            ensureLoaded(ownerId, session)
            withContext(NonCancellable) {
                session.mutex.withLock {
                    val current = session.state.value
                    if (current.text != text) return@withLock false
                    val result = drafts.persist(
                        conversationId = ownerId,
                        expectedRevision = session.durable.revision,
                        text = text,
                        attachments = session.durable.attachments,
                    )
                    if (!result.succeeded) return@withLock false
                    if (!result.matchesRequested) {
                        reloadAndMergeLocked(ownerId, session)
                        return@withLock false
                    }
                    session.durable = session.durable.copy(text = text, revision = result.revision)
                    session.publish(session.state.value.copy(revision = result.revision))
                    true
                }
            }
        }

    suspend fun configurePdf(
        ownerId: String, attachmentId: String, selectedPages: Set<Int>,
    ): Boolean = configureAttachment(ownerId, attachmentId, expectedType = "pdf") { attachment ->
        attachment.copy(selectedPages = selectedPages)
    }

    suspend fun configureVideo(
        ownerId: String,
        attachmentId: String,
        frameCount: Int,
        intervalMs: Long,
    ): Boolean = configureAttachment(ownerId, attachmentId, expectedType = "video") { attachment ->
        attachment.copy(frameCount = frameCount, sliceIntervalMs = intervalMs)
    }
    suspend fun clearAccepted(
        ownerId: String, reclaimAttachments: Boolean = true, submissionId: Long? = null,
        acceptedRevision: Long? = null, acceptedText: String? = null,
        acceptedAttachmentIds: Set<String>? = null,
        expectedWorkspace: com.newoether.agora.data.local.NewChatPersistEntity? = null,
    ): DraftClearResult = withSession(ownerId) { session ->
        ensureLoaded(ownerId, session)
        withContext(NonCancellable) {
            session.mutex.withLock {
                fun failure() = DraftClearResult(
                    emptyList(), session.durable.revision, false, session.state.value.text,
                    session.state.value.attachments,
                )
                if (session.frozenSubmissionId != null &&
                    session.frozenSubmissionId != submissionId) return@withLock failure()
                val current = session.state.value
                if (current.text != session.durable.text) {
                    val persisted = drafts.persist(
                        ownerId, session.durable.revision, current.text,
                        session.durable.attachments,
                    )
                    if (!persisted.succeeded || !persisted.matchesRequested) {
                        if (persisted.succeeded) reloadAndMergeLocked(ownerId, session)
                        return@withLock failure()
                    }
                    session.durable = session.durable.copy(text = current.text, revision = persisted.revision)
                    session.publish(current.copy(revision = persisted.revision))
                }
                val result = drafts.clearAccepted(
                    ownerId, reclaimAttachments, acceptedRevision, acceptedText,
                    acceptedAttachmentIds, expectedWorkspace,
                )
                if (!result.succeeded) return@withLock result
                session.jobs.values.forEach(Job::cancel)
                session.jobs.clear()
                session.generations.clear()
                session.transientAttachmentIds.clear()
                val projectionVersion = session.state.value.textProjectionVersion +
                    if (session.state.value.text != result.remainingText) 1L else 0L
                session.durable = ConversationComposerSnapshot(
                    text = result.remainingText,
                    attachments = result.remainingAttachments,
                    revision = result.revision,
                    textProjectionVersion = projectionVersion,
                    textProjectionExpectedText = acceptedText,
                    loaded = true,
                )
                session.publish(session.durable)
                result
            }
        }
    }
    suspend fun retry(ownerId: String, attachmentId: String): Boolean =
        withSession(ownerId) { session ->
            ensureLoaded(ownerId, session)
            session.mutex.withLock {
                if (session.frozenSubmissionId != null) return@withLock false
                val failed = session.state.value.attachments
                    .firstOrNull { it.localId == attachmentId }
                    ?.takeIf { it.importState == AttachmentImportState.FAILED }
                    ?: return@withLock false
                val processing = failed.asProcessing()
                val generation = session.nextGeneration(attachmentId)
                session.transientAttachmentIds += attachmentId
                session.publish(session.state.value.replaceAttachment(processing))
                session.registerJobLocked(
                    attachmentId = attachmentId,
                    generation = generation,
                    job = stagingJob(ownerId, session, processing, generation),
                )
                true
            }
        }

    suspend fun remove(ownerId: String, attachmentId: String): Boolean =
        withSession(ownerId) { session ->
            ensureLoaded(ownerId, session)
            withContext(NonCancellable) {
                session.mutex.withLock {
                    if (session.frozenSubmissionId != null) return@withLock false
                    val removed = session.state.value.attachments
                        .firstOrNull { it.localId == attachmentId }
                        ?: return@withLock false
                    while (true) {
                        val current = session.state.value
                        if (current.attachments.none { it.localId == attachmentId }) {
                            session.completeRemovalLocked(attachmentId)
                            return@withLock true
                        }
                        val wasDurable = session.durable.attachments.any {
                            it.localId == attachmentId
                        }
                        val requested = session.durable.copy(
                            text = current.text,
                            attachments = session.durable.attachments.filterNot {
                                it.localId == attachmentId
                            },
                        )
                        val result = drafts.persist(
                            conversationId = ownerId,
                            expectedRevision = session.durable.revision,
                            text = requested.text,
                            attachments = requested.attachments,
                        )
                        if (!result.succeeded) return@withLock false
                        if (!result.matchesRequested) {
                            reloadAndMergeLocked(ownerId, session)
                            continue
                        }
                        session.durable = requested.copy(revision = result.revision)
                        session.publish(current.copy(
                            attachments = current.attachments.filterNot {
                                it.localId == attachmentId
                            },
                            pdfPreviewProgress = current.pdfPreviewProgress - attachmentId,
                            revision = result.revision,
                        ))
                        session.completeRemovalLocked(attachmentId)
                        if (!wasDurable) drafts.reclaimAttachments(listOf(removed))
                        return@withLock true
                    }
                    false
                }
            }
        }

    suspend fun awaitProcessing(ownerId: String, attachmentIds: Set<String>? = null) =
        withSession(ownerId) { session ->
            ensureLoaded(ownerId, session)
            while (true) {
                val (jobs, processingRemains) = session.mutex.withLock {
                    val selected: (String) -> Boolean = { id ->
                        attachmentIds == null || id in attachmentIds
                    }
                    session.jobs.filterKeys(selected).values.toList() to
                        session.state.value.attachments.any { attachment ->
                            selected(attachment.localId) && attachment.shouldStartProcessingJob()
                        }
                }
                if (jobs.isEmpty() && !processingRemains) return@withSession
                if (jobs.isEmpty()) yield() else jobs.joinAll()
            }
        }
    private suspend fun configureAttachment(
        ownerId: String,
        attachmentId: String,
        expectedType: String,
        configure: (SelectedAttachment) -> SelectedAttachment,
    ): Boolean = withSession(ownerId) { session ->
        ensureLoaded(ownerId, session)
        withContext(NonCancellable) {
            var configuredBeforeStagingCompleted = false
            val pending = session.mutex.withLock {
                if (session.frozenSubmissionId != null) return@withLock null
                val current = session.state.value.attachments
                    .firstOrNull { it.localId == attachmentId }
                    ?.takeIf {
                        it.type == expectedType &&
                            it.importState == AttachmentImportState.PROCESSING
                    }
                    ?: return@withLock null
                val configured = configure(current)
                session.publish(session.state.value
                    .replaceAttachment(configured)
                    .copy(
                        pdfPreviewProgress = session.state.value.pdfPreviewProgress - attachmentId,
                    ))
                if (attachmentId in session.transientAttachmentIds) {
                    configuredBeforeStagingCompleted = true
                    null
                } else {
                    val generation = session.nextGeneration(attachmentId)
                    session.jobs[attachmentId]?.cancel()
                    configured to generation
                }
            }
            if (configuredBeforeStagingCompleted) return@withContext true
            val (configured, generation) = pending ?: return@withContext false
            val durable = persistReplacement(
                ownerId = ownerId,
                session = session,
                attachmentId = attachmentId,
                generation = generation,
                replacement = configured,
            ) ?: return@withContext false
            session.mutex.withLock {
                session.registerJobLocked(
                    attachmentId = attachmentId,
                    generation = generation,
                    job = processingJob(ownerId, session, durable, generation),
                )
            }
            true
        }
    }
    private suspend fun currentProcessingAttachment(
        session: ComposerOwnerSession,
        attachmentId: String,
        generation: Long,
    ): SelectedAttachment? = session.mutex.withLock {
        session.state.value.attachments.firstOrNull { attachment ->
            attachment.localId == attachmentId &&
                session.generations[attachmentId] == generation &&
                attachment.shouldStartProcessingJob()
        }
    }
    private fun stagingJob(
        ownerId: String,
        session: ComposerOwnerSession,
        source: SelectedAttachment,
        generation: Long,
    ): Job = scope.launch(start = CoroutineStart.LAZY) {
        val outputOwner = currentCoroutineContext()[Job]!!
        try {
            processingQueue.withProcessingPermit(ownerId, source.localId, generation) {
                when (val staged = processor.stage(source, outputOwner)) {
                    is AttachmentImportProcessor.StageResult.Success -> {
                        val durable = persistReplacement(
                            ownerId = ownerId,
                            session = session,
                            attachmentId = source.localId,
                            generation = generation,
                            replacement = staged.attachment,
                            obsoleteTransient = source,
                            createdPaths = staged.createdPaths,
                            outputOwner = outputOwner,
                        )
                        if (durable != null) {
                            currentProcessingAttachment(
                                session = session,
                                attachmentId = source.localId,
                                generation = generation,
                            )?.let { configured ->
                                runProcessing(ownerId, session, configured, generation, outputOwner)
                            }
                        }
                    }
                    AttachmentImportProcessor.StageResult.TooLarge ->
                        persistFailure(ownerId, session, source.localId, generation)
                    is AttachmentImportProcessor.StageResult.Failure -> {
                        val replacement = staged.attachment
                        if (replacement == null) {
                            persistFailure(ownerId, session, source.localId, generation)
                        } else {
                            persistReplacement(
                                ownerId = ownerId,
                                session = session,
                                attachmentId = source.localId,
                                generation = generation,
                                replacement = replacement,
                                obsoleteTransient = source,
                                createdPaths = staged.createdPaths,
                                outputOwner = outputOwner,
                            )
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            DebugLog.w("ChatViewModel", "Attachment staging failed", failure)
            persistFailure(ownerId, session, source.localId, generation)
        } finally {
            finishJob(ownerId, session, source.localId, currentCoroutineContext()[Job]!!)
        }
    }
    private fun processingJob(
        ownerId: String,
        session: ComposerOwnerSession,
        attachment: SelectedAttachment,
        generation: Long,
    ): Job = scope.launch(start = CoroutineStart.LAZY) {
        try {
            processingQueue.withProcessingPermit(ownerId, attachment.localId, generation) {
                runProcessing(ownerId, session, attachment, generation, currentCoroutineContext()[Job]!!)
            }
        } finally {
            finishJob(
                ownerId,
                session,
                attachment.localId,
                currentCoroutineContext()[Job]!!,
            )
        }
    }
    private suspend fun runProcessing(
        ownerId: String,
        session: ComposerOwnerSession,
        attachment: SelectedAttachment,
        generation: Long,
        outputOwner: Any,
    ) {
        if (attachment.shouldPreparePdfPreview()) {
            runPdfPreview(ownerId, session, attachment, generation, outputOwner)
            return
        }
        when (val result = processor.process(attachment, sandboxHomeDir(), outputOwner)) {
            is AttachmentImportProcessor.ProcessResult.Ready ->
                persistReplacement(
                    ownerId = ownerId,
                    session = session,
                    attachmentId = attachment.localId,
                    generation = generation,
                    replacement = result.attachment,
                    createdPaths = result.createdPaths,
                    outputOwner = outputOwner,
                )
            is AttachmentImportProcessor.ProcessResult.Failure ->
                persistFailure(ownerId, session, attachment.localId, generation)
        }
    }
    private suspend fun runPdfPreview(
        ownerId: String,
        session: ComposerOwnerSession,
        attachment: SelectedAttachment,
        generation: Long,
        outputOwner: Any,
    ) {
        updatePdfPreviewProgress(
            session = session,
            attachmentId = attachment.localId,
            generation = generation,
            current = 0,
            total = attachment.pageCount ?: 0,
        )
        when (
            val result = processor.preparePdfPreview(attachment, outputOwner) { current, total ->
                updatePdfPreviewProgress(
                    session = session,
                    attachmentId = attachment.localId,
                    generation = generation,
                    current = current,
                    total = total,
                )
            }
        ) {
            is AttachmentImportProcessor.ProcessResult.Ready ->
                persistReplacement(
                    ownerId = ownerId,
                    session = session,
                    attachmentId = attachment.localId,
                    generation = generation,
                    replacement = result.attachment,
                    createdPaths = result.createdPaths,
                    outputOwner = outputOwner,
                )
            is AttachmentImportProcessor.ProcessResult.Failure ->
                persistFailure(ownerId, session, attachment.localId, generation)
        }
    }
    private suspend fun updatePdfPreviewProgress(
        session: ComposerOwnerSession,
        attachmentId: String,
        generation: Long,
        current: Int,
        total: Int,
    ) {
        session.mutex.withLock {
            if (session.generations[attachmentId] != generation) return@withLock
            val attachment = session.state.value.attachments
                .firstOrNull { it.localId == attachmentId }
                ?.takeIf { it.shouldPreparePdfPreview() }
                ?: return@withLock
            session.publish(session.state.value.copy(
                pdfPreviewProgress = session.state.value.pdfPreviewProgress +
                    (attachment.localId to (current to total)),
            ))
        }
    }
    private suspend fun persistFailure(
        ownerId: String,
        session: ComposerOwnerSession,
        attachmentId: String,
        generation: Long,
    ) {
        val current = session.mutex.withLock {
            session.state.value.attachments.firstOrNull { it.localId == attachmentId }
        } ?: return
        persistReplacement(
            ownerId = ownerId,
            session = session,
            attachmentId = attachmentId,
            generation = generation,
            replacement = current.copy(importState = AttachmentImportState.FAILED),
        )
    }
    private suspend fun persistReplacement(
        ownerId: String,
        session: ComposerOwnerSession,
        attachmentId: String,
        generation: Long,
        replacement: SelectedAttachment,
        obsoleteTransient: SelectedAttachment? = null,
        createdPaths: List<String> = emptyList(),
        outputOwner: Any? = null,
    ): SelectedAttachment? = withContext(NonCancellable) {
        var committed = false
        try {
            session.mutex.withLock {
                while (true) {
                    if (session.generations[attachmentId] != generation) return@withLock null
                    val current = session.state.value
                    val currentAttachment = current.attachments
                        .firstOrNull { it.localId == attachmentId }
                        ?: return@withLock null
                    val effectiveReplacement = replacement.withProcessingConfiguration(
                        currentAttachment,
                    )
                    val wasDurable = session.durable.attachments.any {
                        it.localId == attachmentId
                    }
                    val requestedAttachments = durableAttachmentProjection(
                        durable = session.durable,
                        visible = session.state.value,
                        attachmentId = attachmentId,
                        replacement = effectiveReplacement,
                    )
                    val result = drafts.persist(
                        conversationId = ownerId,
                        expectedRevision = session.durable.revision,
                        text = current.text,
                        attachments = requestedAttachments,
                    )
                    if (!result.succeeded) {
                        session.publish(current.replaceAttachment(
                            currentAttachment.copy(importState = AttachmentImportState.FAILED),
                        ))
                        return@withLock null
                    }
                    if (!result.matchesRequested) {
                        reloadAndMergeLocked(ownerId, session)
                        continue
                    }
                    committed = true
                    session.durable = ConversationComposerSnapshot(
                        text = current.text,
                        attachments = requestedAttachments,
                        revision = result.revision,
                        textProjectionVersion = current.textProjectionVersion,
                        loaded = true,
                    )
                    session.transientAttachmentIds -= attachmentId
                    session.publish(current.replaceAttachment(effectiveReplacement).copy(
                        pdfPreviewProgress = current.pdfPreviewProgress - attachmentId,
                        revision = result.revision,
                    ))
                    outputOwner?.let { owner -> createdPaths.forEach { AttachmentFiles.releaseLivePath(owner, it) } }
                    if (!wasDurable && obsoleteTransient != null) {
                        drafts.reclaimAttachments(listOf(obsoleteTransient))
                    }
                    return@withLock effectiveReplacement
                }
                null
            }
        } finally {
            if (!committed && outputOwner == null) AttachmentFiles.deleteUnownedPaths(createdPaths)
        }
    }
    private suspend fun reloadAndMergeLocked(ownerId: String, session: ComposerOwnerSession) {
        val current = session.state.value
        val loadedDraft = drafts.load(ownerId).toSnapshot()
        val textProjectionVersion = current.textProjectionVersion +
            if (loadedDraft.text != current.text) 1L else 0L
        val loaded = loadedDraft.copy(textProjectionVersion = textProjectionVersion)
        val remaining = loaded.attachments.associateByTo(linkedMapOf()) { it.localId }
        val merged = buildList {
            session.state.value.attachments.forEach { attachment ->
                if (attachment.localId in session.transientAttachmentIds) {
                    add(attachment)
                } else {
                    remaining.remove(attachment.localId)?.let(::add)
                }
            }
            addAll(remaining.values)
        }
        session.durable = loaded
        session.publish(loaded.copy(attachments = merged))
    }
    private suspend fun finishJob(
        ownerId: String,
        session: ComposerOwnerSession,
        attachmentId: String,
        job: Job,
    ) = withContext(NonCancellable) {
        AttachmentFiles.deleteUnownedPaths(AttachmentFiles.releaseLivePaths(job))
        session.mutex.withLock {
            if (session.jobs[attachmentId] === job) {
                session.jobs.remove(attachmentId)
            }
        }
        check(session.jobCount.decrementAndGet() >= 0) { "Composer job count underflow" }
        evictIfInactive(ownerId, session)
    }
}
