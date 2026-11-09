package com.newoether.agora.data.local

import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatDaoExternalImportReplaceTest {
    @Test
    fun replaceWritesOnlyTheProvidedGraphInForeignKeyOrder() = runTest {
        val dao = mockk<ChatDao>()
        val selected = ChatEntity(id = "selected", title = "Selected")
        val omitted = ChatEntity(id = "omitted", title = "Omitted")
        val run = terminalRun("run", selected.id)
        val message = message("message", selected.id, run.id)

        coEvery {
            dao.replaceImportedConversationGraph(any(), any(), any())
        } coAnswers { callOriginal() }
        coEvery { dao.deleteAllConversations() } just Runs
        coEvery { dao.upsertConversation(selected) } just Runs
        coEvery { dao.insertRun(run) } just Runs
        coEvery { dao.upsertMessage(message) } just Runs

        dao.replaceImportedConversationGraph(
            conversations = listOf(selected),
            runs = listOf(run),
            messages = listOf(message),
        )

        coVerifyOrder {
            dao.deleteAllConversations()
            dao.upsertConversation(selected)
            dao.insertRun(run)
            dao.upsertMessage(message)
        }
        coVerify(exactly = 0) { dao.upsertConversation(omitted) }
    }

    @Test
    fun invalidReplacementGraphsFailBeforeDeletingExistingData() = runTest {
        val dao = mockk<ChatDao>()
        val first = ChatEntity(id = "first", title = "First")
        val second = ChatEntity(id = "second", title = "Second")
        val firstRun = terminalRun("first-run", first.id)
        val secondRun = terminalRun("second-run", second.id)
        val invalidGraphs = listOf(
            Triple(
                listOf(first),
                listOf(terminalRun("foreign-run", "missing")),
                emptyList(),
            ),
            Triple(
                listOf(first),
                listOf(
                    firstRun.copy(
                        status = RunStatus.ACTIVE,
                        activeSlot = 1,
                        endedAt = null,
                        endReason = null,
                    ),
                ),
                emptyList(),
            ),
            Triple(
                listOf(first, second),
                listOf(secondRun, firstRun.copy(parentRunId = secondRun.id)),
                emptyList(),
            ),
            Triple(
                listOf(first, second),
                listOf(firstRun),
                listOf(message("message", second.id, firstRun.id)),
            ),
            Triple(
                listOf(first, second),
                listOf(firstRun, secondRun),
                listOf(
                    message("parent", second.id, secondRun.id),
                    message("child", first.id, firstRun.id).copy(parentId = "parent"),
                ),
            ),
        )

        coEvery {
            dao.replaceImportedConversationGraph(any(), any(), any())
        } coAnswers { callOriginal() }

        invalidGraphs.forEach { (conversations, runs, messages) ->
            expectFailure {
                dao.replaceImportedConversationGraph(conversations, runs, messages)
            }
        }

        coVerify(exactly = 0) { dao.deleteAllConversations() }
    }

    @Test
    fun writeFailureEscapesTheRoomTransactionWithoutContinuing() = runTest {
        val dao = mockk<ChatDao>()
        val selected = ChatEntity(id = "selected", title = "Selected")
        val run = terminalRun("run", selected.id)
        val message = message("message", selected.id, run.id)

        coEvery {
            dao.replaceImportedConversationGraph(any(), any(), any())
        } coAnswers { callOriginal() }
        coEvery { dao.deleteAllConversations() } just Runs
        coEvery { dao.upsertConversation(selected) } just Runs
        coEvery { dao.insertRun(run) } throws IllegalStateException("write failed")

        expectFailure {
            dao.replaceImportedConversationGraph(
                conversations = listOf(selected),
                runs = listOf(run),
                messages = listOf(message),
            )
        }

        coVerify(exactly = 0) { dao.upsertMessage(any()) }
        val daoSource = mainSource("data/local/ChatDao.kt").readText().replace("\r\n", "\n")
        assertTrue(
            "Room must own rollback for the entire external Replace operation",
            daoSource.contains(
                "@Transaction\n    suspend fun replaceImportedConversationGraph(",
            ),
        )
    }

    @Test
    fun claudeAndGptEntrypointsPreserveSelectedReplaceAndIncrementalMergeContracts() {
        val manager = mainSource("viewmodel/ImportExportManager.kt").readText().replace("\r\n", "\n")

        assertTrue(
            "Claude and GPT must filter the parsed archive to the selected subset",
            Regex("""toImportFormat\(parsed, selectedIds\)""")
                .findAll(manager)
                .count() == 2,
        )
        assertTrue(
            "Claude and GPT Replace and Merge must use the Repository graph transaction",
            Regex("""conversations\.importExternalConversationGraph\(""")
                .findAll(manager)
                .count() == 4,
        )
        assertTrue(
            "Claude and GPT Replace must select replacement twice",
            Regex("""replace = true,""").findAll(manager).count() == 2,
        )
        assertTrue(
            "External Replace must not delete conversations outside the DAO transaction",
            "conversations.deleteAllConversations()" !in manager,
        )
        assertTrue(
            "Claude and GPT Merge must retain the existing-ID incremental path",
            Regex("""val existingConvIds = conversations\.getAllConversationsList\(\)""")
                .findAll(manager)
                .count() == 2,
        )
        assertTrue(
            "Claude and GPT Merge must select incremental import twice",
            Regex("""replace = false,""").findAll(manager).count() == 2,
        )
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected replacement to fail")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun terminalRun(id: String, conversationId: String) = RunEntity(
        id = id,
        conversationId = conversationId,
        parentRunId = null,
        status = RunStatus.COMPLETED,
        activeSlot = null,
        startedAt = 1L,
        lastCheckpointAt = 2L,
        endedAt = 2L,
        endReason = RunEndReason.MODEL_COMPLETED,
    )

    private fun message(id: String, conversationId: String, runId: String) = MessageEntity(
        id = id,
        conversationId = conversationId,
        text = "Imported",
        status = MessageStatus.SUCCESS,
        participant = Participant.USER,
        timestamp = 1L,
        runId = runId,
        runSequence = 0L,
        consumedAtPass = 0,
    )

    private fun mainSource(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val sourceRoot = if (relativePath.startsWith("data/local/")) {
                "data/database/src/main/kotlin/com/newoether/agora"
            } else {
                "app/src/main/java/com/newoether/agora"
            }
            val candidate = File(directory, "$sourceRoot/$relativePath")
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("Unable to locate Agora main source: $relativePath")
    }
}
