package com.newoether.agora.viewmodel

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.*
import com.newoether.agora.model.*
import com.newoether.agora.util.AttachmentFiles
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Production SQLite/Room regression for indexed ownership and the final unlink fence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AttachmentOrphanSweeperTest {
    @get:Rule val files = TemporaryFolder()
    private lateinit var database: ChatDatabase
    private lateinit var dao: ChatDao
    private val statements = CopyOnWriteArrayList<String>()
    private var beforePayload: (() -> Unit)? = null
    private var beforeStatement: ((String) -> Unit)? = null

    @Before fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase(ChatDatabase.DB_NAME)
        database = ChatDatabase.build(context, queryCallback = { sql, _ ->
            statements += sql
            beforeStatement?.invoke(sql)
            if (sql.startsWith("SELECT SUBSTR(CAST(")) beforePayload?.invoke()
        })
        dao = database.chatDao()
        dao.upsertConversation(ChatEntity("owner", "owner", lastUpdated = 1))
        dao.insertRun(RunEntity("run", "owner", parentRunId = null, status = RunStatus.COMPLETED, activeSlot = null,
            startedAt = 1, lastCheckpointAt = 1, endedAt = 1, endReason = RunEndReason.MODEL_COMPLETED))
    }

    @After fun close() { database.close() }

    @Test fun durableAndLiveOwnershipSurviveExactReclamation() = runBlocking {
        val image = oldFile("img_owned")
        val live = Any()
        try {
            AttachmentFiles.retainLivePath(live, image.path)
            assertFalse(sweeper().deleteExact(image.path))
            assertTrue(image.exists())
            dao.insertMessage(message("m", images = listOf(image.path)))
            AttachmentFiles.releaseLivePaths(live)
            assertTrue(sweeper().deleteExact(image.path))
            assertTrue(image.exists())
            dao.deleteMessagesByIds(listOf("m"))
            assertTrue(sweeper().deleteExact(image.path))
            assertFalse(image.exists())
        } finally { AttachmentFiles.releaseLivePaths(live) }
    }

    @Test fun messageToolMetadataAndBothDraftKindsProtectFiles() = runBlocking {
        val image = oldFile("att_image")
        val metadata = oldFile("images/camera_metadata")
        val tool = oldFile("img_tool")
        val draft = oldFile("run-inputs/draft")
        val newDraft = oldFile("fork-attachments/new-draft")
        dao.insertMessage(message("m", listOf(image.path)).copy(
            attachmentMeta = Json.encodeToString(AttachmentMeta(listOf(AttachmentItem(
                originalUri = "file://${metadata.path}", type = "image",
            )))),
            toolCallJson = Json.encodeToString(listOf(MessageSegment(type = "tool",
                toolImages = listOf(artifact(tool.path))))),
        ))
        dao.updateDraft("owner", "", attachments(draft), 1)
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO new_chat_persist(id, draftText, draftAttachments) VALUES(0, '', ?)", arrayOf(attachments(newDraft)))
        sweeper().sweep()
        listOf(image, metadata, tool, draft, newDraft).forEach { assertTrue(it.path, it.exists()) }
    }

    @Test fun sweepKeepsFreshAndUnrelatedFilesAndRemovesOnlyEligibleOrphans() = runBlocking {
        val orphan = oldFile("img_orphan")
        val camera = oldFile("images/camera_orphan")
        val input = oldFile("run-inputs/orphan")
        val fresh = oldFile("vid_fresh").also { it.setLastModified(NOW - 10) }
        val unrelated = oldFile("notes.txt")
        val thumbnail = oldFile("images/thumbnail")
        sweeper().sweep()
        listOf(orphan, camera, input).forEach { assertFalse(it.exists()) }
        listOf(fresh, unrelated, thumbnail).forEach { assertTrue(it.exists()) }
    }

    @Test fun unfinishedSweepRemainsRetryableAndReclaimsAfterLiveRelease() = runBlocking {
        val image = oldFile("img_pending_sweep")
        val live = Any()
        val sweeper = sweeper()
        try {
            AttachmentFiles.retainLivePath(live, image.path)
            assertFalse(sweeper.sweep())
            assertTrue(image.exists())
            AttachmentFiles.releaseLivePaths(live)
            assertTrue(sweeper.sweep())
            assertFalse(image.exists())
            assertTrue(sweeper.sweep())
        } finally { AttachmentFiles.releaseLivePaths(live) }
    }

    @Test fun equalBasenamesAndUnavailableToolArtifactsRetainOriginalMeaning() = runBlocking {
        val held = oldFile("images/img_same")
        val orphan = oldFile("run-inputs/img_same")
        dao.insertMessage(message("m").copy(toolCallJson = Json.encodeToString(listOf(
            MessageSegment(type = "tool", toolImages = listOf(artifact(held.path), artifact(orphan.path).copy(unavailable = true))),
        ))))
        assertTrue(sweeper().deleteExact(orphan.path))
        assertFalse(orphan.exists())
        assertTrue(sweeper().deleteExact(held.path))
        assertTrue(held.exists())
    }

    @Test fun malformedCandidateFailsClosedButUnrelatedMalformedJsonDoesNotBlockCleanup() = runBlocking {
        val held = oldFile("img_broken")
        val orphan = oldFile("img_unrelated")
        dao.insertMessage(message("m").copy(toolCallJson = "bad-json:${held.name}"))
        assertTrue(runCatching { sweeper().deleteExact(held.path) }.exceptionOrNull() is kotlinx.serialization.SerializationException)
        assertTrue(held.exists())
        statements.clear()
        assertTrue(sweeper().deleteExact(orphan.path))
        assertFalse(orphan.exists())
        assertFalse("Unrelated invalid fields must not be transferred and parsed again",
            statements.any { it.startsWith("SELECT SUBSTR(CAST(") })
    }

    @Test fun sourceMutationAndDeleteRecreateRejectPreviouslyPreparedCheck() = runBlocking {
        val image = oldFile("img_race")
        val index = AttachmentReferenceIndex(database)
        val revision = index.prepareCheck(image.path)
        dao.insertMessage(message("m", listOf(image.path)))
        database.withTransaction { assertFalse(index.isCurrent(revision)) }
        val heldRevision = index.prepareCheck(image.path)
        dao.deleteMessagesByIds(listOf("m"))
        dao.insertMessage(message("m", listOf(image.path)))
        database.withTransaction { assertFalse(index.isCurrent(heldRevision)) }
        sweeper().deleteExact(image.path)
        assertTrue(image.exists())
    }

    @Test fun rollbackRestoresReferenceInvalidationAndMessageOwnership() = runBlocking {
        val image = oldFile("img_rollback")
        dao.insertMessage(message("m", listOf(image.path)))
        val index = AttachmentReferenceIndex(database)
        val revision = index.prepareCheck(image.path)
        runCatching {
            database.withTransaction {
                dao.deleteMessagesByIds(listOf("m"))
                error("rollback")
            }
        }
        database.withTransaction { assertTrue(index.isCurrent(revision)); assertTrue(index.contains(image.path)) }
        sweeper().deleteExact(image.path)
        assertTrue(image.exists())
    }

    @Test fun draftToMessageTransferRemainsAtomicForBothDraftKinds() = runBlocking {
        for (newChat in listOf(false, true)) {
            val image = oldFile("img_handoff_$newChat")
            val index = AttachmentReferenceIndex(database)
            index.refresh()
            if (newChat) database.openHelper.writableDatabase.execSQL(
                "INSERT OR REPLACE INTO new_chat_persist(id, draftText, draftAttachments) VALUES(0, '', ?)", arrayOf(attachments(image)))
            else dao.updateDraft("owner", "", attachments(image), 1)
            val revision = index.prepareCheck(image.path)
            database.withTransaction {
                dao.insertMessage(message("m-$newChat", listOf(image.path)))
                if (newChat) database.openHelper.writableDatabase.execSQL("UPDATE new_chat_persist SET draftAttachments = NULL WHERE id = 0")
                else dao.updateDraft("owner", "", null, 2)
            }
            database.withTransaction { assertFalse(index.isCurrent(revision)) }
            sweeper().deleteExact(image.path)
            assertTrue(image.exists())
        }
    }

    @Test fun payloadProcessingDoesNotOwnWriteTransactionAndForegroundWriteProgresses() = runBlocking {
        val image = oldFile("img_parallel")
        dao.insertMessage(message("m", listOf(image.path)).copy(text = "x".repeat(2 * 1024 * 1024)))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val once = AtomicBoolean()
        beforePayload = {
            if (once.compareAndSet(false, true)) {
                assertFalse(database.inTransaction())
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        val cleanup = async(Dispatchers.IO) { sweeper().deleteExact(image.path) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            withTimeout(5_000) { dao.updateDraft("owner", "foreground commit", null, 3) }
        } finally { release.countDown() }
        assertTrue(cleanup.await())
        assertTrue(image.exists())
    }

    @Test fun completedIndexDoesNotReadUnchangedPayloadsAgainAndLookupUsesPathIndex() = runBlocking {
        repeat(40) { dao.insertMessage(message("m-$it").copy(text = "x".repeat(256 * 1024))) }
        val index = AttachmentReferenceIndex(database)
        index.refresh()
        statements.clear()
        val orphan = oldFile("img_exact")
        assertTrue(sweeper().deleteExact(orphan.path))
        assertFalse(statements.any { it.startsWith("SELECT SUBSTR(CAST(") || it.contains("instr(") })
        val plan = database.openHelper.readableDatabase.query(
            "EXPLAIN QUERY PLAN SELECT 1 FROM attachment_references WHERE path = ? LIMIT 1", arrayOf(orphan.path),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(3)) } }
        assertTrue(plan.toString(), plan.any { it.contains("SEARCH") && it.contains("index_attachment_references_path") })
    }

    @Test fun cancellationResumesBootstrapAndOutsideRootNeverUnlinks() = runBlocking {
        repeat(20) { dao.insertMessage(message("m-$it")) }
        val held = oldFile("img_cancelled")
        dao.insertMessage(message("held", listOf(held.path)))
        val cleanup = async(Dispatchers.IO, start = CoroutineStart.LAZY) { sweeper().deleteExact(held.path) }
        beforePayload = { cleanup.cancel(CancellationException("stop bootstrap")) }
        cleanup.start()
        assertTrue(runCatching { cleanup.await() }.exceptionOrNull() is CancellationException)
        assertTrue(held.exists())
        beforePayload = null
        sweeper().deleteExact(held.path)
        assertTrue(held.exists())
        assertTrue(runCatching {
            AttachmentOrphanSweeper(database, File(files.root, "private")).deleteExact(held.path)
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun discardedQueueSchedulesDebtWithoutUnlinkingAnotherMessagesAttachment() = runBlocking {
        val image = oldFile("img_discarded_queue")
        val attachment = SelectedAttachment(uri = "file://${image.path}", type = "image", localPath = image.path)
        val discarded = mutableListOf<SelectedAttachment>()
        val reclamations = mutableListOf<Job>()
        var scheduled = 0
        val repository = com.newoether.agora.data.repository.ConversationRepository(
            dao, database, scheduleMaintenance = { scheduled++ },
        )
        val registry = ConversationStateRegistry { attachments ->
            discarded += attachments
            reclamations += launch { repository.deleteUnreferencedDraftAttachmentFiles(attachments) }
        }
        try {
            val state = registry.getOrCreate("queued-owner")
            state.enqueueSend(QueuedSend("queued", "text", "model", listOf(attachment), "queued-run"))
            dao.insertMessage(message("sent", listOf(image.path)))
            registry.remove("queued-owner")
            assertTrue("Queue disposal must not directly unlink", image.exists())
            assertEquals(listOf(attachment), discarded)
            reclamations.forEach { it.join() }
            assertEquals(1, scheduled)
            val debt = database.maintenanceDebtDao()
            val pending = requireNotNull(debt.getDebt(MaintenanceDebtEntity.KIND_ATTACHMENT_ORPHANS, image.path))
            assertTrue(sweeper().deleteExact(pending.identity))
            assertTrue(image.exists())
            dao.deleteMessagesByIds(listOf("sent"))
            assertTrue(sweeper().deleteExact(image.path))
            assertFalse(image.exists())
        } finally { registry.cancelAll() }
    }

    @Test fun artifactOnlyMetadataAndBothDraftsProtectFilesUntilTheirOwnersReleaseThem() = runBlocking {
        val metadata = oldFile("img_metadata_artifact_only")
        val draft = oldFile("img_draft_artifact_only")
        val freshDraft = oldFile("img_new_draft_artifact_only")
        val tool = oldFile("fork-attachments/tool_only.png")
        fun artifactDraft(file: File) = Json.encodeToString(listOf(SelectedAttachment(
            uri = "", type = "image", imageArtifacts = listOf(artifact(file.path)),
        )))
        dao.insertMessage(message("artifact-owner").copy(
            attachmentMeta = Json.encodeToString(AttachmentMeta(listOf(AttachmentItem(
                type = "image", imageArtifacts = listOf(artifact(metadata.path)),
            )))),
            toolCallJson = Json.encodeToString(listOf(MessageSegment(
                type = "tool", toolImages = listOf(artifact(tool.path)),
            ))),
        ))
        dao.updateDraft("owner", "", artifactDraft(draft), 1)
        dao.upsertNewChatPersist(NewChatPersistEntity(draftAttachments = artifactDraft(freshDraft)))
        val candidates = listOf(metadata, draft, freshDraft, tool)
        candidates.forEach { assertTrue(sweeper().deleteExact(it.path)); assertTrue(it.exists()) }
        dao.deleteMessagesByIds(listOf("artifact-owner"))
        dao.updateDraft("owner", "", null, 2)
        dao.upsertNewChatPersist(NewChatPersistEntity(draftAttachments = null))
        candidates.forEach { assertTrue(sweeper().deleteExact(it.path)); assertFalse(it.exists()) }
    }

    @Test fun draftFrameAndRenderedPathsAreIndependentDurableReferences() = runBlocking {
        val frame = oldFile("vid_frame")
        val rendered = oldFile("pdf_rendered")
        dao.updateDraft("owner", "", Json.encodeToString(listOf(SelectedAttachment(
            uri = "", type = "video", processedFrames = listOf(frame.path),
            preRenderedPaths = listOf(rendered.path),
        ))), 1)
        listOf(frame, rendered).forEach { assertTrue(sweeper().deleteExact(it.path)); assertTrue(it.exists()) }
        dao.updateDraft("owner", "", null, 2)
        listOf(frame, rendered).forEach { assertTrue(sweeper().deleteExact(it.path)); assertFalse(it.exists()) }
    }

    @Test fun largeToolPayloadAndMalformedLegacyPayloadDoNotOverflowTheCursorWindow() = runBlocking {
        val held = oldFile("img_large_tool")
        val other = oldFile("img_large_unrelated")
        val payload = Json.encodeToString(listOf(MessageSegment(
            type = "tool", toolResult = "x".repeat(3 * 1024 * 1024),
            toolImages = listOf(artifact(held.path)),
        )))
        dao.insertMessage(message("large-tool").copy(toolCallJson = payload))
        assertTrue(sweeper().deleteExact(held.path))
        assertTrue(held.exists())
        assertTrue(sweeper().deleteExact(other.path))
        assertFalse(other.exists())
        dao.insertMessage(message("large-malformed").copy(toolCallJson = payload.dropLast(1)))
        assertTrue(runCatching { sweeper().deleteExact(held.path) }.isFailure)
        assertTrue(held.exists())
    }

    @Test fun interruptedReferenceBatchesStayDirtyAndResumeAfterDatabaseReopen() = runBlocking {
        val paths = (0 until 150).map { File(files.root, "img_batch_$it").canonicalPath }
        dao.insertMessage(message("many-paths", paths))
        val refresh = async(Dispatchers.IO, start = CoroutineStart.LAZY) { AttachmentReferenceIndex(database).refresh() }
        var inserts = 0
        beforeStatement = { sql ->
            if (sql.startsWith("INSERT OR IGNORE INTO attachment_references VALUES") && ++inserts == 65) {
                refresh.cancel(CancellationException("interrupt second reference batch"))
            }
        }
        refresh.start()
        assertTrue(runCatching { refresh.await() }.exceptionOrNull() is CancellationException)
        beforeStatement = null
        database.openHelper.readableDatabase.query(
            "SELECT dirty FROM attachment_reference_owners WHERE kind = 0 AND ownerId = 'many-paths'",
        ).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM attachment_references WHERE kind = 0 AND ownerId = 'many-paths'",
        ).use { assertTrue(it.moveToFirst()); assertTrue(it.getInt(0) in 64..128) }
        database.close()
        database = ChatDatabase.build(ApplicationProvider.getApplicationContext<Application>())
        dao = database.chatDao()
        val reopened = AttachmentReferenceIndex(database)
        database.withTransaction { assertFalse(reopened.isCurrent(0)) }
        reopened.refresh()
        paths.forEach { assertTrue(reopened.contains(it)) }
        // Replacing a large owner exercises bounded deletion of its previous derived references.
        dao.upsertMessage(message("many-paths", listOf(paths.last())))
        reopened.refresh()
        assertFalse(reopened.contains(paths.first()))
        assertTrue(reopened.contains(paths.last()))
    }

    @Test fun ownerMutationDuringChunkReadCannotPublishThePreviousToken() = runBlocking {
        val previous = oldFile("img_previous")
        val current = oldFile("img_current")
        dao.insertMessage(message("changing", listOf(previous.path)))
        val once = AtomicBoolean()
        beforePayload = {
            if (once.compareAndSet(false, true)) {
                assertFalse(database.inTransaction())
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE messages SET images = ? WHERE id = 'changing'",
                    arrayOf(Json.encodeToString(listOf(current.path))),
                )
            }
        }
        val index = AttachmentReferenceIndex(database)
        index.refresh()
        assertFalse(index.contains(previous.path))
        assertTrue(index.contains(current.path))
        assertTrue(sweeper().deleteExact(current.path))
        assertTrue(current.exists())
    }

    @Test fun stalledRebuildDoesNotSerializeAnotherRebuildOrPublishObsoleteReferences() = runBlocking {
        val paths = (0 until 150).map { File(files.root, "img_concurrent_$it").canonicalPath }
        dao.insertMessage(message("concurrent", paths))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val once = AtomicBoolean()
        beforePayload = {
            if (once.compareAndSet(false, true)) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        val first = async(Dispatchers.IO) { AttachmentReferenceIndex(database).refresh() }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            withTimeout(5_000) { AttachmentReferenceIndex(database).refresh() }
        } finally { release.countDown() }
        first.await()
        val index = AttachmentReferenceIndex(database)
        paths.forEach { assertTrue(index.contains(it)) }
        val revision = index.prepareCheck(paths.first())
        database.withTransaction { assertTrue(index.isCurrent(revision)) }
    }

    @Test fun utf8ChunkBoundariesAndLargeDraftMetadataKeepExactPaths() = runBlocking {
        val image = oldFile("img_unicode")
        val payload = Json.encodeToString(listOf(SelectedAttachment(
            uri = "文😀".repeat(500_000), type = "image",
            imageArtifacts = listOf(artifact(image.path)),
        )))
        dao.updateDraft("owner", "", payload, 1)
        assertTrue(sweeper().deleteExact(image.path))
        assertTrue(image.exists())
        dao.updateDraft("owner", "", null, 2)
        assertTrue(sweeper().deleteExact(image.path))
        assertFalse(image.exists())
    }

    private fun sweeper() = AttachmentOrphanSweeper(database, files.root, now = { NOW })
    private fun message(id: String, images: List<String> = emptyList()) = MessageEntity(
        id = id, conversationId = "owner", text = "source", participant = Participant.USER,
        timestamp = 1, images = images, runId = "run", runSequence = 0,
    )
    private fun attachments(file: File) = Json.encodeToString(listOf(
        SelectedAttachment(uri = "file://${file.path}", type = "image", localPath = file.path),
    ))
    private fun artifact(path: String) = ImageArtifact(path, "image/png", 4, 40, 30)
    private fun oldFile(path: String) = File(files.root, path).also {
        it.parentFile!!.mkdirs()
        it.writeText("test")
        check(it.setLastModified(NOW - 2 * 60 * 60 * 1000L))
    }
    private companion object { const val NOW = 10_000_000L }
}
