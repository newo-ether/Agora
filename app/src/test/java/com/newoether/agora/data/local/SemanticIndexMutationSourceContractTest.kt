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
    fun sourceEligibilityAndGraphChangesShareTheOwningRoomTransaction() {
        val ledger = source(
            "app/src/main/java/com/newoether/agora/data/local/SemanticIndexLedger.kt",
        )
        val repository = source(
            "app/src/main/java/com/newoether/agora/data/repository/ConversationRepository.kt",
        )

        val sourceMutation = ledger.section(
            "internal suspend fun <T> ChatDatabase.withSemanticSourceMutation(",
            "internal suspend fun <T> ChatDatabase.withSemanticGraphMutation(",
        )
        assertTrue(sourceMutation.contains("): T = withTransaction {"))
        assertOrdered(
            sourceMutation,
            "val before =",
            "val result = block()",
            "val changed =",
            "semanticDao.invalidateSemanticSources(snapshot, changed, updatedAt)",
        )
        val invalidation = ledger.section(
            "private suspend fun SemanticIndexDao.invalidateSemanticSources(",
            "internal suspend fun <T> ChatDatabase.withSemanticSourceMutation(",
        )
        assertOrdered(
            invalidation,
            "deleteEmbeddingsForMessages(sources.keys.toList())",
            "enqueueExactWork(activeModelId, messageId, fingerprint, updatedAt)",
            ".filter { it != activeModelId }",
            "requestReconcile(modelId, updatedAt)",
        )

        val eligibilityMutation = ledger.section(
            "internal suspend fun <T> ChatDatabase.withSemanticEligibilityMutation(",
            "internal suspend fun ChatDatabase.commitSemanticEmbedding(",
        )
        assertTrue(eligibilityMutation.contains("): T = withTransaction {"))
        assertOrdered(
            eligibilityMutation,
            "val before =",
            "val result = block()",
            "val after =",
            "if (before != null && after != null && before != after)",
            "requestSemanticReconcile(snapshot, updatedAt)",
        )
        assertTrue(eligibilityMutation.contains("deleteEmbeddingsByConversation(conversationId)"))

        val graphMutation = ledger.section(
            "internal suspend fun <T> ChatDatabase.withSemanticGraphMutation(",
            "internal suspend fun <T> ChatDatabase.withSemanticEligibilityMutation(",
        )
        assertTrue(graphMutation.contains("): T = withTransaction {"))
        assertOrdered(
            graphMutation,
            "val result = block()",
            "if (clearAllEmbeddings)",
            "semanticDao.requestSemanticReconcile(snapshot, updatedAt)",
        )

        assertTrue(repository.contains("withSemanticTransaction(listOf(entity.id))"))
        assertTrue(repository.contains("withSemanticTransaction(staleMessageIds, at)"))
        assertTrue(repository.contains("withSemanticTransaction(ids) { chatDao.deleteMessagesByIds(ids) }"))
        assertTrue(repository.contains("suspend fun upsertConversation(entity: ChatEntity) = withSemanticTransaction("))
        assertTrue(repository.contains("conversationId = entity.id"))
        assertTrue(repository.contains("suspend fun deleteConversation(id: String)"))
        assertTrue(repository.contains("suspend fun createForkGraph("))
        assertTrue(repository.contains(") = withSemanticTransaction {"))
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
        ) + "\n" + source(
            "app/src/main/java/com/newoether/agora/data/local/ChatEmbeddingDao.kt",
        )

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

        assertFalse(chatDao.contains("suspend fun upsertEmbedding("))
        assertFalse(chatDao.contains("suspend fun deleteEmbedding("))
        assertFalse(chatDao.contains("suspend fun deleteEmbeddingsByModel("))
        assertFalse(chatDao.contains("suspend fun upsertEmbeddingIfSearchable("))
        assertTrue(chatDao.contains("suspend fun insertEmbeddings("))
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
        assertTrue(rag.contains("if (takePendingRefresh(refreshJob) != null)"))
        assertTrue(locks.contains("EmbeddingCacheWorker] is the only embedding generator"))
        assertFalse(locks.contains("Two runners exist"))
        assertFalse(locks.contains("fun remove("))
        val reminder = rag.section(
            "private suspend fun emitUncachedReminder(",
            "// -- Embedding-model CRUD",
        )
        assertOrdered(
            reminder,
            "EmbeddingCacheLocks.forModel(modelId).withLock",
            "settings.embeddingModels.value.none { it.id == modelId }",
            "emitSnackbar(",
        )
        val manualCache = rag.section(
            "fun cacheMessagesForModel(",
            "private suspend fun admitActiveModel(",
        )
        assertOrdered(
            manualCache,
            "EmbeddingCacheLocks.forModel(modelId).withLock",
            "settings.embeddingModels.value.find { it.id == modelId }",
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
    fun canonicalWorkerConsumesBoundedLedgerWorkAndFullReconcilePages() {
        val worker = source(
            "app/src/main/java/com/newoether/agora/service/EmbeddingCacheWorker.kt",
        )
        val ledger = source(
            "app/src/main/java/com/newoether/agora/data/local/SemanticIndexLedger.kt",
        )

        assertFalse(worker.contains("getIndexableMessageCount("))
        assertFalse(worker.contains("getEmbeddingCountByModel("))
        assertFalse(worker.contains("lastProgressPermille"))
        assertFalse(worker.contains("getUnembeddedMessagesPage("))
        assertFalse(worker.contains("EmbeddingCacheLocks.forModel(modelId).withLock"))
        assertTrue(worker.contains("SemanticIndexLedgerEntity.STATE_PENDING"))
        assertTrue(worker.contains("SemanticIndexLedgerEntity.STATE_NEEDS_RECONCILE"))
        assertTrue(worker.contains("limit = model.batchSize.coerceIn(1, MAX_BATCH_SIZE)"))
        assertTrue(worker.contains("limit = RECONCILE_SCAN_PAGE_SIZE"))
        assertTrue(worker.contains("candidates.chunked(embeddingBatchSize)"))
        assertFalse(worker.contains("markSemanticEmbeddingReused"))
        assertFalse(ledger.contains("updateEmbeddingFingerprint"))
        assertTrue(worker.countToken("yield()") >= 2)

        val exact = worker.section(
            "private suspend fun consumeExactWork(",
            "private suspend fun reconcileModel(",
        )
        assertOrdered(
            exact,
            "semanticDao.getWorkCountThroughRevision(",
            "publishProgress(",
            "semanticDao.getWorkPage(",
            "work.sourceFingerprint == null",
            "semanticDao.completeExactWork(work",
            "semanticDao.getSearchableMessageText(work.messageId)",
            "semanticSourceFingerprint(text) == work.sourceFingerprint",
            "embedCandidates(model, remoteConfig, candidates, database)",
        )
        val reconcile = worker.section(
            "private suspend fun reconcileModel(",
            "private suspend fun embedCandidates(",
        )
        assertOrdered(
            reconcile,
            "semanticDao.getReconcileMessageCount(",
            "publishProgress(",
            "while (processed < workTotal)",
            "semanticDao.getReconcileMessagesPage(",
            "semanticSourceFingerprint(row.text)",
            "!embedCandidates(",
            "processed += page.size",
        )
        assertTrue(
            reconcile.substringAfter("while (processed < workTotal)")
                .contains("return semanticDao.completeReconcile("),
        )
        val commit = worker.section(
            "private suspend fun embedCandidates(",
            "private suspend fun resolveEmbeddingConfig(",
        )
        assertOrdered(
            commit,
            "database.commitSemanticEmbedding(",
            "expectedFingerprint = candidate.fingerprint",
            "expectedWorkRevision = candidate.workRevision",
            "expectedReconcileRevision = expectedReconcileRevision",
            "completePendingWork = candidate.workRevision != null",
        )
        assertTrue(worker.contains("ExistingWorkPolicy.APPEND_OR_REPLACE"))
        assertTrue(worker.contains("workNameFor(modelId)"))

        val workPage = ledger.substringBefore("suspend fun getWorkPage(")
            .substringAfterLast("@Query(")
        assertTrue(workPage.contains("sourceRevision <= :maxSourceRevision"))
        assertTrue(workPage.contains("sourceRevision > :afterSourceRevision"))
        assertTrue(workPage.contains("sourceRevision = :afterSourceRevision"))
        assertTrue(workPage.contains("messageId > :afterMessageId"))
        assertTrue(workPage.contains("ORDER BY sourceRevision, messageId"))
        assertTrue(workPage.contains("LIMIT :limit"))

        val searchablePage = ledger.substringBefore("suspend fun getReconcileMessagesPage(")
            .substringAfterLast("@Query(")
        assertTrue(searchablePage.contains("SELECT m.id, m.text"))
        assertTrue(searchablePage.contains("LEFT JOIN embeddings"))
        assertTrue(searchablePage.contains("LEFT JOIN semantic_index_work"))
        assertTrue(
            searchablePage.contains(
                "w.sourceRevision IS NULL OR w.sourceRevision <= :maxWorkRevision",
            ),
        )
        assertTrue(searchablePage.contains(":afterId IS NULL OR m.id > :afterId"))
        assertTrue(searchablePage.contains("ORDER BY m.id"))
        assertTrue(searchablePage.contains("LIMIT :limit"))
        assertFalse(searchablePage.contains("NOT EXISTS"))
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
            "private val generationManager = GenerationManager(",
            "/**\n     * Injects [userText]",
        )
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

    private fun String.section(start: String, end: String): String =
        substringAfter(start).substringBefore(end)

    private fun assertOrdered(source: String, vararg values: String) {
        var previous = -1
        values.forEach { value ->
            val current = source.indexOf(value)
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
