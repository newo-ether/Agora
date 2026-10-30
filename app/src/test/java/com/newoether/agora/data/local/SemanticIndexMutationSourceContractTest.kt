package com.newoether.agora.data.local

import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.EmbeddingModelType
import com.newoether.agora.data.embeddingModelSemanticsChanged
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticIndexMutationSourceContractTest {
    @Test
    fun sourceMetadataAndAffectedConversationCountsShareTheOwningRoomTransaction() {
        val ledger = source("app/src/main/java/com/newoether/agora/data/local/SemanticIndexLedger.kt")
        val mutations = source("app/src/main/java/com/newoether/agora/data/local/SemanticMutationDao.kt")
        val metadata = source("app/src/main/java/com/newoether/agora/data/local/SemanticMetadataDao.kt")
        val repository = source("app/src/main/java/com/newoether/agora/data/repository/ConversationRepository.kt")

        listOf("insertMessage", "upsertMessage").forEach { operation ->
            assertTrue(mutations.contains("@Transaction\n    suspend fun $operation("))
            val write = mutations.substringAfter("suspend fun $operation(").substringBefore("\n    @")
            assertOrdered(write, "ensureMetadataState()", "${operation}Row(message)",
                "maintainSemanticSource(semanticSource(")
        }
        val checkpoint = mutations.section("suspend fun updateMessageCheckpoint(",
            "suspend fun deleteMessageRowsByIds(")
        assertOrdered(checkpoint, "getSemanticMessageIdentity(checkpoint.id)",
            "updateMessageCheckpointRow(checkpoint)", "if (changed == 1) maintainSemanticSource(")
        listOf("deleteMessagesByIds", "deleteRun").forEach { operation ->
            assertTrue(mutations.contains("@Transaction\n    suspend fun $operation("))
            val deletion = mutations.substringAfter("suspend fun $operation(").substringBefore("\n    @")
            assertOrdered(deletion, "removeSemanticSource(it)", when (operation) {
                "deleteMessagesByIds" -> "deleteMessageRowsByIds(ids)"
                else -> "deleteRunRow(runId)"
            })
        }
        assertTrue(mutations.contains("@Transaction\n    suspend fun deleteMessagesByConversation("))
        val ownerDeletion = mutations.section("suspend fun deleteMessagesByConversation(",
            "suspend fun getRunCascadeMessageIds(")
        assertOrdered(ownerDeletion, "getSemanticConversation(conversationId)",
            "deleteEmbeddingsByConversation(conversationId)", "deleteConversationSemanticSources(conversationId)",
            "advanceSemanticConversation(conversationId, -owner.eligibleCount)",
            "deleteMessageRowsByConversation(conversationId)")
        assertFalse(ownerDeletion.contains("removeSemanticSource("))
        assertFalse(ownerDeletion.contains("getSemanticConversationMessageIds("))
        assertTrue(mutations.queryFor("deleteConversationSemanticSources")
            .contains("DELETE FROM semantic_sources WHERE conversationId = :conversationId"))
        val maintain = metadata.section("suspend fun maintainSemanticSource(",
            "suspend fun removeSemanticSource(")
        assertTrue(metadata.contains("@Transaction\n    suspend fun maintainSemanticSource("))
        assertTrue(maintain.contains("previous?.sourceFingerprint != source.sourceFingerprint"))
        assertTrue(maintain.contains("previous?.eligible != source.eligible"))
        assertTrue(maintain.contains("val semanticChanged = changed && (previous?.eligible == true || source.eligible)"))
        assertOrdered(maintain, "getSemanticSource(source.messageId)",
            "if (semanticChanged)", "adjustSourceEmbeddingCounts(previous, -1)",
            "advanceSemanticConversation(previous.conversationId,",
            "advanceSemanticConversation(source.conversationId,",
            "if (changed && !legacy) deleteSourceEmbeddingRows(source.messageId)",
            "putSemanticSource(source)", "adjustSourceEmbeddingCounts(source, 1)")
        val sourceMutation = ledger.section("internal suspend fun <T> ChatDatabase.withSemanticSourceMutation(",
            "internal suspend fun <T> ChatDatabase.withSemanticGraphMutation(")
        assertTrue(sourceMutation.contains("): T = withTransaction {"))
        assertOrdered(sourceMutation, "val before =", "getSearchableMessageFingerprint(it)",
            "val result = block()", "val changed =", "getSearchableMessageFingerprint(messageId)",
            "semanticDao.invalidateSemanticSources(snapshot, changed, updatedAt)")
        val invalidation = ledger.section("private suspend fun SemanticIndexDao.invalidateSemanticSources(",
            "internal suspend fun <T> ChatDatabase.withSemanticSourceMutation(")
        assertTrue(invalidation.contains("enqueueExactWork(modelId, messageId, fingerprint, updatedAt)"))
        assertFalse(invalidation.contains("requestReconcile("))
        assertFalse(invalidation.contains("snapshot.second"))
        val mutationBoundaries = ledger.section("private suspend fun SemanticIndexDao.invalidateSemanticSources(",
            "internal suspend fun ChatDatabase.commitSemanticEmbedding(")
        assertFalse(mutationBoundaries.contains("getSearchableMessageText("))
        assertFalse(mutationBoundaries.contains("getLegacySource("))
        assertFalse(mutationBoundaries.contains("requestSemanticReconcile("))
        assertFalse(maintain.contains("requestReconcile("))

        val eligibility = mutations.section("suspend fun upsertConversation(", "suspend fun upsertMessageRow(")
        assertOrdered(eligibility, "upsertConversationRow(conversation)",
            "val eligible = conversation.taskId == null", "before.cacheEligible != eligible",
            "setSemanticConversationEligibility(conversation.id, eligible)",
            "advanceSemanticConversation(conversation.id, 0)")
        assertFalse(eligibility.contains("parentConversationId"))
        listOf("withSemanticGraphMutation", "withSemanticEligibilityMutation").forEach { boundary ->
            assertTrue(ledger.substringAfter("ChatDatabase.$boundary(")
                .substringBefore("\n}").contains("): T = withTransaction {"))
        }
        assertTrue(repository.contains("withSemanticTransaction(listOf(entity.id))"))
        assertTrue(repository.contains("withSemanticTransaction(staleMessageIds, at)"))
        assertTrue(repository.contains("withSemanticTransaction(ids) { chatDao.deleteMessagesByIds(ids) }"))
        assertTrue(repository.contains("room.withSemanticSourceMutation"))
        assertTrue(repository.contains("room.withSemanticEligibilityMutation"))
        assertTrue(repository.contains("room.withSemanticGraphMutation"))
    }

    @Test
    fun embeddingCommitRejectsStaleResultsAndModelLifecycleIsAtomic() {
        val ledger = source(
            "app/src/main/java/com/newoether/agora/data/local/SemanticIndexLedger.kt",
        )
        val chatDao = source(
            "app/src/main/java/com/newoether/agora/data/local/ChatDao.kt",
        ) + source("app/src/main/java/com/newoether/agora/data/local/ChatSearchDao.kt") +
            source("app/src/main/java/com/newoether/agora/data/local/ChatMediaReferencesDao.kt")

        val commit = ledger.section(
            "internal suspend fun ChatDatabase.commitSemanticEmbedding(",
            "internal suspend fun ChatDatabase.invalidateSemanticModel(",
        )
        assertTrue(commit.contains("): Boolean = withTransaction {"))
        assertOrdered(
            commit,
            "val ledger = semanticDao.getLedger(embedding.modelId)",
            "expectedReconcileRevision != null",
            "val currentFingerprint =",
            "if (currentFingerprint != expectedFingerprint) return@withTransaction false",
            "val work = if (completePendingWork)",
            "work?.sourceRevision != expectedWorkRevision",
            "semanticDao.upsertEmbedding(embedding)",
            "semanticDao.completeExactWork(currentWork, updatedAt)",
        )
        assertFalse(commit.contains("semanticDao.admitModel"))
        assertFalse(commit.contains("deleteEmbeddingForModelMessage"))

        val invalidation = ledger.section(
            "internal suspend fun ChatDatabase.invalidateSemanticModel(",
            "internal suspend fun ChatDatabase.deleteSemanticModel(",
        )
        assertTrue(invalidation.contains("withTransaction {"))
        assertOrdered(
            invalidation,
            "deleteEmbeddingsForModel(modelId)",
            "requestReconcile(modelId, updatedAt)",
        )
        val deletion = ledger.substringAfter(
            "internal suspend fun ChatDatabase.deleteSemanticModel(",
        )
        assertTrue(deletion.contains("withTransaction {"))
        assertOrdered(deletion, "deleteEmbeddingsForModel(modelId)", "deleteModel(modelId)")

        val membership = source("app/src/main/java/com/newoether/agora/data/local/SemanticEmbeddingDao.kt")
        assertTrue(membership.contains("@Transaction\n    suspend fun upsertEmbedding("))
        val countedCommit = membership.section("suspend fun upsertEmbedding(", "suspend fun insertEmbeddings(")
        assertOrdered(countedCommit, "findEmbeddingIdentities(", "upsertEmbeddingRow(embedding)",
            "adjustEmbeddingMembership(it, -1)", "adjustEmbeddingMembership(SemanticEmbeddingIdentity(")
        assertFalse(commit.contains("getSearchableMessageText("))
        assertFalse(commit.contains("getLegacySource("))
        assertFalse(chatDao.contains("suspend fun deleteEmbedding("))
        assertFalse(chatDao.contains("suspend fun deleteEmbeddingsByModel("))
        assertFalse(chatDao.contains("suspend fun upsertEmbeddingIfSearchable("))
        assertTrue(membership.contains("suspend fun insertEmbeddings("))
        assertTrue(membership.contains("@Transaction\n    suspend fun deleteEmbeddingsByConversation("))
        val ownerDeletion = membership.section("suspend fun deleteEmbeddingsByConversation(",
            "suspend fun deleteEmbeddingsByMessageIds(")
        assertOrdered(ownerDeletion, "deleteConversationEmbeddingRows(conversationId)",
            "clearSemanticConversationCounts(conversationId)")
        assertFalse(ownerDeletion.contains("getSemanticConversationMessageIds("))
        assertFalse(ownerDeletion.contains("forEach"))
        assertTrue(membership.queryFor("deleteConversationEmbeddingRows")
            .contains("messageId IN (SELECT id FROM messages WHERE conversationId = :conversationId)"))
    }

    @Test
    fun nativeImportFreezesModelsBeforeItsGraphTransaction() {
        val dataImporter = source(
            "app/src/main/java/com/newoether/agora/data/DataImporter.kt",
        )
        val graphImporter = source(
            "app/src/main/java/com/newoether/agora/data/NativeConversationGraphImporter.kt",
        )
        val container = source(
            "app/src/main/java/com/newoether/agora/di/AppContainer.kt",
        )

        val conversations = dataImporter.substringAfter(
            "if (convDecision != null && convDecision != ImportStrategy.SKIP) {",
        ).substringBefore("if (memDecision != null && memDecision != ImportStrategy.SKIP) {")
        assertOrdered(
            conversations,
            "val semanticSnapshot = semanticModelSnapshot(",
            "activeEmbeddingModelId.first()",
            "embeddingModels.first()",
            "conversationGraphImporter.importConversationGraph(",
            "semanticSnapshot = semanticSnapshot",
        )

        val importGraph = graphImporter.section(
            "suspend fun importConversationGraph(",
            "// Internal data classes",
        )
        assertTrue(importGraph.contains("semanticSnapshot: SemanticModelSnapshot"))
        assertTrue(importGraph.contains("database.withSemanticGraphMutation("))
        assertTrue(importGraph.contains("snapshot = semanticSnapshot"))
        assertTrue(importGraph.contains("clearMessageIds = plannedRunGraph.assignments.keys"))
        assertTrue(importGraph.contains("clearAllEmbeddings = strategy == ImportStrategy.REPLACE"))
        assertOrdered(
            importGraph,
            "database.withSemanticGraphMutation(",
            "chatDao.upsertConversationSettingsImportTransfer(settingsTransfer)",
            "scheduleMaintenance()",
        )

        val provider = container.section(
            "semanticModelSnapshotProvider = {",
            "},\n        )",
        )
        assertOrdered(
            provider,
            "settingsRepository.awaitInitialLoad()",
            "semanticModelSnapshot(",
            "activeEmbeddingModelId.value",
            "embeddingModels.value.map { it.id }",
        )
    }

    @Test
    fun durableWorkerIsTheOnlyEmbeddingGeneratorAndCarriesFingerprintLock() {
        val worker = source(
            "app/src/main/java/com/newoether/agora/service/EmbeddingCacheWorker.kt",
        )
        val rag = source(
            "app/src/main/java/com/newoether/agora/viewmodel/RagManager.kt",
        )
        val locks = source(
            "app/src/main/java/com/newoether/agora/data/EmbeddingCacheLocks.kt",
        )

        assertFalse(worker.contains("EmbeddingCacheLocks.forModel(modelId).withLock"))
        assertTrue(worker.contains("EmbeddingClient.computeEmbeddings("))
        assertTrue(worker.contains("LlamaEngine.computeEmbeddings("))
        assertTrue(worker.contains("database.commitSemanticEmbedding("))
        assertTrue(worker.contains("expectedFingerprint = candidate.fingerprint"))
        assertTrue(worker.contains("ExistingWorkPolicy.APPEND_OR_REPLACE"))

        assertFalse(rag.contains("EmbeddingClient"))
        assertFalse(rag.contains("LlamaEngine"))
        assertFalse(rag.contains("commitSemanticEmbedding("))
        assertFalse(rag.contains("indexMessageForRagNow("))
        assertFalse(rag.contains("runCacheLoop("))
        assertTrue(rag.contains("val pending = takePendingRefresh(refreshJob)"))
        assertTrue(rag.contains("pending != null && scope.isActive && cachePresentationSubscribers.isNotEmpty()"))
        assertTrue(locks.contains("EmbeddingCacheWorker] is the only embedding generator"))
        assertFalse(locks.contains("Two runners exist"))
        assertFalse(locks.contains("fun remove("))
        val reminder = rag.section(
            "private suspend fun emitUncachedReminder(",
            "// -- Embedding-model CRUD",
        )
        assertOrdered(reminder, "val shouldNotify = EmbeddingCacheLocks.forModel(modelId).withLock",
            "settings.embeddingModels.value.any { it.id == modelId }",
            "ledgerState != SemanticIndexLedgerEntity.STATE_CURRENT", "if (shouldNotify) {", "emitSnackbar(")
        assertTrue(reminder.contains("\n        }\n        if (shouldNotify) {"))
        assertFalse(reminder.contains("getEmbeddingCacheCounts("))
        assertFalse(reminder.contains("requestCacheCountRefresh("))
        assertFalse(reminder.contains("cachedByModel"))
        val manualCache = rag.section(
            "fun cacheMessagesForModel(",
            "private suspend fun admitActiveModel(",
        )
        assertOrdered(
            manualCache,
            "EmbeddingCacheLocks.forModel(modelId).withLock",
            "settings.embeddingModels.value.none { it.id == modelId }",
            "conversations.getOrAdmitSemanticLedgerState(modelId)",
            "scheduleCacheWork(modelId)",
        )
        val admission = rag.section(
            "private suspend fun admitActiveModel(",
            "/**\n     * Searchable message persistence",
        )
        assertOrdered(
            admission,
            "EmbeddingCacheLocks.forModel(modelId).withLock",
            "settings.embeddingModels.value.none { it.id == modelId }",
            "conversations.getOrAdmitSemanticLedgerState(modelId)",
            "scheduleCacheWork(modelId)",
        )
        val incremental = rag.section(
            "fun indexMessageForRag(",
            "fun resolveEmbeddingApiKey(): String? {",
        )
        assertTrue(incremental.contains("scheduleCacheWork(modelId)"))
        assertFalse(incremental.contains("computeEmbedding"))
    }

    @Test
    fun canonicalWorkerConsumesExactWorkThenOnlyDirtyConversationSourcePages() {
        val worker = source("app/src/main/java/com/newoether/agora/service/EmbeddingCacheWorker.kt")
        val ledger = source("app/src/main/java/com/newoether/agora/data/local/SemanticIndexLedger.kt")
        val metadata = source("app/src/main/java/com/newoether/agora/data/local/SemanticMetadataDao.kt")

        listOf("getIndexableMessageCount(", "getEmbeddingCountByModel(", "getUnembeddedMessagesPage(",
            "getFirstReconcileMessageIdsPage(", "getReconcileMessageIdsPage(", "getLegacySource(",
            "lastProgressPermille", "EmbeddingCacheLocks.forModel(modelId).withLock").forEach {
            assertFalse("Embedding work must not use $it", worker.contains(it))
        }
        assertTrue(worker.contains("ensureMetadataState().complete"))
        assertTrue(worker.contains("limit = model.batchSize.coerceIn(1, MAX_BATCH_SIZE)"))
        assertTrue(worker.contains("limit = RECONCILE_SCAN_PAGE_SIZE"))
        assertTrue(worker.contains("candidates.chunked(embeddingBatchSize)"))
        assertFalse(worker.contains("markSemanticEmbeddingReused"))
        assertFalse(ledger.contains("updateEmbeddingFingerprint"))
        assertTrue(worker.countToken("yield()") >= 2)

        val exact = worker.section("private suspend fun consumeExactWork(", "private suspend fun reconcileModel(")
        assertOrdered(exact, "semanticDao.getWorkCountThroughRevision(", "publishProgress(",
            "semanticDao.getWorkPage(", "work.sourceFingerprint == null", "semanticDao.completeExactWork(work",
            "semanticDao.getSearchableMessageText(work.messageId)",
            "semanticSourceFingerprint(text) == work.sourceFingerprint", "!embedCandidates(")
        val reconcile = worker.section("private suspend fun reconcileModel(", "private suspend fun embedCandidates(")
        assertOrdered(reconcile, "while (true)", "semanticDao.getLedger(model.id)?.reconcileRevision",
            "metadataDao.getDirtyConversations(", "pageLedger.sourceRevision != admittedRevision",
            "afterConversationId = conversations.last().conversationId", "for (conversation in conversations)",
            "var afterMessageId: String? = null", "metadataDao.getConversationSourceIds(",
            "afterMessageId = messageIds.last()", "semanticDao.getReconcileMessagesByIds(",
            "semanticSourceFingerprint(text)", "!embedCandidates(", "yield()",
            "metadataDao.markConversationIndexed(", "sourceRevision = conversation.sourceRevision",
            "expectedReconcileRevision = expectedReconcileRevision", "semanticDao.completeReconcile(")
        assertFalse(reconcile.contains("getReconcileMessageCount"))
        assertFalse(reconcile.contains("publishProgress("))
        assertTrue(reconcile.contains("conversation.cacheEligible && conversation.eligibleCount > 0"))
        assertTrue(reconcile.contains("if (!conversationComplete) complete = false"))
        val commit = worker.section("private suspend fun embedCandidates(", "private suspend fun resolveEmbeddingConfig(")
        assertOrdered(commit, "database.commitSemanticEmbedding(", "expectedFingerprint = candidate.fingerprint",
            "expectedWorkRevision = candidate.workRevision",
            "expectedReconcileRevision = expectedReconcileRevision",
            "completePendingWork = candidate.workRevision != null")
        assertTrue(worker.contains("ExistingWorkPolicy.APPEND_OR_REPLACE"))
        assertTrue(worker.contains("workNameFor(modelId)"))

        val workPage = ledger.queryFor("getWorkPage")
        listOf("sourceRevision <= :maxSourceRevision", "sourceRevision > :afterSourceRevision",
            "sourceRevision = :afterSourceRevision", "messageId > :afterMessageId",
            "ORDER BY sourceRevision, messageId", "LIMIT :limit").forEach {
            assertTrue(workPage.contains(it))
        }
        val dirty = metadata.queryFor("getNextDirtyConversations")
        assertTrue(dirty.contains("m.indexedRevision IS NULL OR m.indexedRevision != c.sourceRevision"))
        assertTrue(dirty.contains("ORDER BY c.conversationId LIMIT :limit"))
        val identities = metadata.queryFor("getNextConversationSourceIds")
        assertTrue(identities.contains("FROM semantic_sources WHERE conversationId = :conversationId"))
        assertTrue(identities.contains("messageId > :afterMessageId"))
        assertFalse(identities.contains(":afterMessageId IS NULL"))
        assertFalse(metadata.queryFor("getNextLegacyMessageIds").contains(":afterMessageId IS NULL"))
        assertTrue(identities.contains("ORDER BY messageId LIMIT :limit"))
        assertFalse(identities.contains("sourcePrefix"))
        val searchablePage = ledger.queryFor("getReconcileMessagesByIds")
        assertTrue(searchablePage.contains("SUBSTR(s.sourcePrefix,"))
        assertTrue(searchablePage.contains("LEFT JOIN embeddings"))
        assertTrue(searchablePage.contains("LEFT JOIN semantic_index_work"))
        assertTrue(searchablePage.contains("w.sourceRevision IS NULL OR w.sourceRevision <= :maxWorkRevision"))
        assertTrue(searchablePage.contains("s.messageId IN (:messageIds)"))
        assertTrue(searchablePage.contains("ORDER BY s.messageId"))
        listOf(searchablePage, ledger.queryFor("getSearchableMessageSource"),
            ledger.queryFor("getSearchableMessageFingerprint")).forEach { query ->
            assertFalse(query.contains("FROM messages"))
            assertFalse(query.contains("CAST("))
            assertFalse(query.contains("m.text"))
        }
        val conversationFence = metadata.queryFor("finishSemanticConversation")
        assertTrue(conversationFence.contains("sourceRevision = :sourceRevision"))
        assertTrue(conversationFence.contains("reconcileRevision = :expectedReconcileRevision"))
        listOf("markCurrentAfterExactWork", "markAfterReconcile").forEach { operation ->
            val completion = ledger.queryFor(operation)
            assertTrue(completion.contains("m.indexedRevision != c.sourceRevision"))
            assertTrue(completion.contains("semantic_metadata_state WHERE id = 0 AND complete = 1"))
        }
    }

    @Test
    fun additiveMigrationAndIndependentBootstrapKeepLegacyBodiesOffHotPaths() {
        val migration = source("app/src/main/java/com/newoether/agora/data/local/migration/Migration41To42.kt")
        val worker = source("app/src/main/java/com/newoether/agora/service/SemanticMetadataWorker.kt")
        val metadata = source("app/src/main/java/com/newoether/agora/data/local/SemanticMetadataDao.kt")
        val sourceMetadata = source("app/src/main/java/com/newoether/agora/data/local/SemanticSourceMetadata.kt")
        listOf("semantic_sources", "semantic_conversations", "semantic_model_conversations",
            "semantic_metadata_state").forEach {
            assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS $it"))
        }
        assertFalse(Regex("\\b(?:SELECT|DELETE\\s+FROM|DROP\\s+TABLE)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(migration))
        assertTrue(migration.contains("VALUES(0, 0, NULL)"))
        assertTrue(worker.contains("ExistingWorkPolicy.KEEP"))
        assertTrue(worker.contains("internal const val PAGE_SIZE = 16"))
        assertFalse(worker.contains("withTransaction"))
        assertFalse(worker.contains("getEmbeddingCacheCounts("))
        assertOrdered(worker, "val state = metadata.ensureMetadataState()", "if (state.complete) return",
            "metadata.getLegacyMessageIds(state.afterMessageId, PAGE_SIZE)",
            "if (metadata.getSemanticSource(messageId) == null)", "metadata.getLegacySource(messageId)",
            "metadata.commitLegacyPage(", "expectedAfterId = state.afterMessageId")
        assertTrue(worker.contains("catch (cancelled: CancellationException) {\n            throw cancelled"))
        val pageCommit = metadata.substringAfter("suspend fun commitLegacyPage(")
        assertOrdered(pageCommit, "state.afterMessageId != expectedAfterId",
            "getSemanticSource(source.messageId) == null", "getSemanticMessageIdentity(source.messageId) != null",
            "maintainSemanticSource(source, legacy = true)", "advanceLegacyMetadata(")
        assertTrue(sourceMetadata.contains("text.take(Constants.MAX_EMBEDDING_TEXT_LENGTH + 1)"))
        val providerInput = source("app/src/main/java/com/newoether/agora/service/EmbeddingCacheWorker.kt")
            .section("private suspend fun embedCandidates(", "private fun ReconcileIndexableMessage.toCandidate(")
        assertTrue(providerInput.contains("candidate.message.text.take(Constants.MAX_EMBEDDING_TEXT_LENGTH)"))
        val fingerprint = source("app/src/main/java/com/newoether/agora/data/local/SemanticIndexLedger.kt")
            .section("internal fun semanticSourceFingerprint(", "internal typealias SemanticModelSnapshot")
        assertTrue(fingerprint.contains("MessageDigest.getInstance(\"SHA-256\")"))
        assertTrue(fingerprint.contains("text.take(Constants.MAX_EMBEDDING_TEXT_LENGTH).toByteArray(Charsets.UTF_8)"))
    }

    @Test
    fun settingsImportReconcilesOnlySemanticModelChangesUnderTheModelLock() {
        val remote = EmbeddingModelConfig(
            id = "remote",
            name = "Remote",
            type = EmbeddingModelType.REMOTE,
            remoteModelName = "embed-v1",
            remoteBaseUrl = "https://one.test",
            remoteApiKey = "secret-one",
            batchSize = 8,
        )
        assertFalse(embeddingModelSemanticsChanged(
            remote,
            remote.copy(name = "Renamed", remoteApiKey = "secret-two", batchSize = 32),
        ))
        assertTrue(embeddingModelSemanticsChanged(
            remote,
            remote.copy(remoteModelName = "embed-v2"),
        ))
        assertTrue(embeddingModelSemanticsChanged(
            remote,
            remote.copy(remoteBaseUrl = "https://two.test"),
        ))

        val local = EmbeddingModelConfig(
            id = "local",
            name = "Local",
            type = EmbeddingModelType.LOCAL,
            localFilePath = "/models/one.gguf",
        )
        assertTrue(embeddingModelSemanticsChanged(
            local,
            local.copy(localFilePath = "/models/two.gguf"),
        ))
        assertTrue(embeddingModelSemanticsChanged(
            local,
            local.copy(type = EmbeddingModelType.REMOTE, remoteModelName = "embed-v1"),
        ))

        val importer = source(
            "app/src/main/java/com/newoether/agora/data/DataImporter.kt",
        )
        val reconciliation = importer.section(
            "private suspend fun reconcileImportedEmbeddingModels(",
            "@Serializable",
        )
        assertOrdered(
            reconciliation,
            "val previousById =",
            "val importedModelIds = settingsManager.embeddingModels.first()",
            "EmbeddingCacheLocks.forModel(modelId).withLock",
            "val current = settingsManager.embeddingModels.first()",
            "current == null -> database.deleteSemanticModel(modelId)",
            "embeddingModelSemanticsChanged(previous, current)",
            "database.invalidateSemanticModel(modelId, updatedAt)",
        )
        assertFalse(reconciliation.contains("EmbeddingCacheLocks.remove"))

        val settingsImport = importer.substringAfter(
            "if (settingsDecision != null && settingsDecision != ImportStrategy.SKIP) {",
        ).substringBefore("if (keysDecision != null && keysDecision != ImportStrategy.SKIP) {")
        assertOrdered(
            settingsImport,
            "val previousEmbeddingModels = settingsManager.embeddingModels.first()",
            "PortableSettingsArchive.restoreFromJsonObject(",
            "finally {",
            "withContext(NonCancellable)",
            "reconcileImportedEmbeddingModels(previousEmbeddingModels)",
        )
    }

    @Test
    fun headlessAutomationWakesAutoCacheForBothDurableTurnRows() {
        val engine = source(
            "app/src/main/java/com/newoether/agora/automation/TaskExecutionEngine.kt",
        )
        val generationManager = engine.section(
            "private val generationManager",
            "/**\n     * Injects [userText]",
        )
        assertTrue(generationManager.contains("GenerationManager("))
        assertTrue(
            generationManager.contains(
                "it.onMessagePersisted = ragManager::indexMessageForRag",
            ),
        )

        val admission = engine.section(
            "val persistToken = generationState.nextPersistId()",
            "bindingOutcome = withContext(NonCancellable)",
        )
        assertOrdered(
            admission,
            "acceptedInputGraphWriter.commit(",
            "runCreated = true",
            "if (userText.isNotBlank())",
            "ragManager.indexMessageForRag(userMessageId, userText)",
        )
    }

    private fun String.countToken(token: String): Int = split(token).size - 1

    private fun String.section(start: String, end: String): String {
        val startIndex = indexOf(start)
        assertTrue("Missing source boundary: $start", startIndex >= 0)
        val endIndex = indexOf(end, startIndex + start.length)
        assertTrue("Missing source boundary after $start: $end", endIndex > startIndex)
        return substring(startIndex + start.length, endIndex)
    }

    private fun String.queryFor(method: String): String {
        val methodIndex = indexOf("suspend fun $method(")
        assertTrue("Missing DAO operation: $method", methodIndex >= 0)
        val queryIndex = lastIndexOf("@Query(", methodIndex)
        assertTrue("Missing query for $method", queryIndex >= 0)
        return substring(queryIndex, methodIndex)
    }

    private fun assertOrdered(source: String, vararg values: String) {
        var previous = -1
        values.forEach { value ->
            val current = source.indexOf(value, previous + 1)
            assertTrue("Missing or out-of-order source token: $value", current > previous)
            previous = current
        }
    }

    private fun source(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            File(directory, relativePath).takeIf(File::isFile)?.let {
                return it.readText().replace("\r\n", "\n")
            }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }
}
