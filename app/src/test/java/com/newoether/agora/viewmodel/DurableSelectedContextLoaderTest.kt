package com.newoether.agora.viewmodel

import com.newoether.agora.api.util.projectGenerationStatusesForApi
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.ProviderContextTopologySnapshot
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DurableSelectedContextLoaderTest {
    @Test
    fun `selected Provider path matches the full snapshot oracle without reading wide branches`() =
        runTest {
            val root = message("root", null, Participant.USER, "root", 0, "root-run")
            val selected = message("selected", root.id, Participant.MODEL, "answer", 1, "run-a")
            val unselected = message(
                "unselected",
                root.id,
                Participant.MODEL,
                "x".repeat(1_000_000),
                2,
                "run-b",
            )
            val tool = message(
                "${Constants.TOOL_MSG_PREFIX}call",
                selected.id,
                Participant.MODEL,
                "",
                2,
                "run-a",
            ).copy(
                toolCallJson = Json.encodeToString(
                    listOf(
                        MessageSegment(
                            type = "tool",
                            toolName = "shell",
                            toolArgs = """{"command":"echo ok"}""",
                            toolCallId = "call-1",
                        ),
                    ),
                ),
            )
            val result = message(
                "${Constants.RESULT_MSG_PREFIX}call",
                tool.id,
                Participant.USER,
                "ok",
                3,
                "run-a",
            ).copy(
                toolCallJson = Json.encodeToString(
                    listOf(
                        MessageSegment(
                            type = "tool",
                            toolName = "shell",
                            toolArgs = "{}",
                            toolResult = "ok",
                            toolCallId = "call-1",
                        ),
                    ),
                ),
            )
            val entities = listOf(root, selected, unselected, tool, result)
            val snapshot = snapshot(
                entities,
                selectedBranchesJson = Json.encodeToString(mapOf(root.id to selected.id)),
            )
            val requestedIds = mutableListOf<List<String>>()
            val loader = loader(snapshot, entities, requestedIds) { it }

            val loaded = loader.load(
                DurableSelectedContextRequest(
                    conversationId = CONVERSATION_ID,
                    followSelectedBranch = true,
                    includeStoredTranscriptions = false,
                ),
            )
            val oracle = projectProviderMessages(
                ApiPathAssembler.assemble(
                    ancestorPath = listOf(root, selected),
                    allMessages = entities,
                ),
                includeStoredTranscriptions = false,
            ).let { messages ->
                projectGenerationStatusesForApi(messages) { it }
            }

            assertEquals(oracle, loaded.messages)
            assertEquals(
                listOf(root.id, tool.id, result.id, selected.id),
                loaded.messages.map { it.id },
            )
            assertFalse(requestedIds.flatten().contains(unselected.id))
        }

    @Test
    fun `selected fallback preserves full snapshot ordering for equal timestamps`() {
        val root = message("root", null, Participant.USER, "root", 0, "root-run")
        val first = message("z-first", root.id, Participant.MODEL, "first", 1, "run-a")
        val second = message("a-second", root.id, Participant.MODEL, "second", 1, "run-b")
        val entities = listOf(root, first, second)
        val currentSnapshot = snapshot(entities)
        val oracle = ConversationUiState.resolvePath(
            allMessages = entities.map { it.toUiChatMessage { text -> text } },
            streamingMsg = null,
            selectedChildren = emptyMap(),
        ).map { it.id }

        assertEquals(oracle, selectedVisibleContextMessageIds(currentSnapshot))
        assertEquals(listOf(root.id, second.id), oracle)
    }

    @Test
    fun `materialization keeps the complete canonical path for the sole Provider rollout`() =
        runTest {
            val entities = buildList {
                var parentId: String? = null
                repeat(160) { index ->
                    val entity = message(
                        id = "message-$index",
                        parentId = parentId,
                        participant = if (index % 2 == 0) Participant.USER else Participant.MODEL,
                        text = "payload-$index",
                        sequence = index.toLong(),
                        runId = "run-$index",
                    )
                    add(entity)
                    parentId = entity.id
                }
            }
            val requestedIds = mutableListOf<List<String>>()
            val loaded = loader(snapshot(entities), entities, requestedIds) { it }.load(
                DurableSelectedContextRequest(
                    conversationId = CONVERSATION_ID,
                    anchorMessageId = entities.last().id,
                    includeStoredTranscriptions = false,
                ),
            )

            assertEquals(entities.map { it.id }, loaded.entities.map { it.id })
            assertEquals(entities.map { it.id }, requestedIds.flatten())
        }

    @Test
    fun `blank successful Compact is the exact boundary and older payload is not read`() = runTest {
        val old = message("old", null, Participant.USER, "old", 0, "run-old")
        val compact = message(
            "${Constants.COMPACT_MSG_PREFIX}boundary",
            old.id,
            Participant.MODEL,
            "",
            1,
            "run-compact",
        )
        val user = message("user", compact.id, Participant.USER, "question", 2, "run-user")
        val model = message("model", user.id, Participant.MODEL, "answer", 3, "run-model")
        val entities = listOf(old, compact, user, model)
        val requestedIds = mutableListOf<List<String>>()

        val loaded = loader(snapshot(entities), entities, requestedIds) { it }.load(
            DurableSelectedContextRequest(
                conversationId = CONVERSATION_ID,
                anchorMessageId = model.id,
                includeStoredTranscriptions = false,
            ),
        )

        assertEquals(listOf(compact.id, user.id, model.id), loaded.messages.map { it.id })
        assertFalse(requestedIds.flatten().contains(old.id))
    }

    @Test
    fun `failed Compact is ignored and the earlier successful Compact remains the boundary`() =
        runTest {
            val old = message("old", null, Participant.USER, "old", 0, "run-old")
            val successful = message(
                "${Constants.COMPACT_MSG_PREFIX}success",
                old.id,
                Participant.MODEL,
                "summary",
                1,
                "run-success",
            )
            val failed = message(
                "${Constants.COMPACT_MSG_PREFIX}failed",
                successful.id,
                Participant.MODEL,
                "partial",
                2,
                "run-failed",
            ).copy(status = MessageStatus.ERROR)
            val user = message("user", failed.id, Participant.USER, "question", 3, "run-user")
            val entities = listOf(old, successful, failed, user)
            val requestedIds = mutableListOf<List<String>>()

            val loaded = loader(snapshot(entities), entities, requestedIds) { it }.load(
                DurableSelectedContextRequest(
                    conversationId = CONVERSATION_ID,
                    anchorMessageId = user.id,
                    includeStoredTranscriptions = false,
                ),
            )

            assertEquals(
                listOf(successful.id, failed.id, user.id),
                loaded.entities.map { it.id },
            )
            assertFalse(requestedIds.flatten().contains(old.id))
        }

    @Test
    fun `terminal error after a tool round uses the formatter in Provider context`() =
        runTest {
            val rawErrorDetail = "Raw provider failure: INSUFFICIENT_BALANCE"
            val displayedErrorDetail = "Displayed provider failure"
            val root = message("root", null, Participant.USER, "question", 0, "root-run")
            val failed = message(
                "failed",
                root.id,
                Participant.MODEL,
                "partial answer",
                1,
                "failed-run",
            ).copy(
                status = MessageStatus.ERROR,
                toolCallJson = Json.encodeToString(
                    listOf(
                        MessageSegment(
                            type = "tool",
                            toolName = "shell",
                            toolArgs = "{}",
                            toolCallId = "call-1",
                        ),
                        MessageSegment(type = "answer", content = "partial answer"),
                        MessageSegment(type = "error", content = rawErrorDetail),
                    ),
                ),
            )
            val tool = message(
                "${Constants.TOOL_MSG_PREFIX}call",
                failed.id,
                Participant.MODEL,
                "",
                2,
                failed.runId,
            ).copy(
                toolCallJson = Json.encodeToString(
                    listOf(
                        MessageSegment(
                            type = "tool",
                            toolName = "shell",
                            toolArgs = "{}",
                            toolCallId = "call-1",
                        ),
                    ),
                ),
            )
            val result = message(
                "${Constants.RESULT_MSG_PREFIX}call",
                tool.id,
                Participant.USER,
                "ok",
                3,
                failed.runId,
            ).copy(
                toolCallJson = Json.encodeToString(
                    listOf(
                        MessageSegment(
                            type = "tool",
                            toolName = "shell",
                            toolArgs = "{}",
                            toolResult = "ok",
                            toolCallId = "call-1",
                        ),
                    ),
                ),
            )
            val followUp = message(
                "follow-up",
                failed.id,
                Participant.USER,
                "continue",
                4,
                "follow-up-run",
            )
            val entities = listOf(root, failed, tool, result, followUp)

            val loaded = loader(
                snapshot = snapshot(entities),
                entities = entities,
                requestedIds = mutableListOf(),
                generationErrorFormatter = { displayedErrorDetail },
            ).load(
                DurableSelectedContextRequest(
                    conversationId = CONVERSATION_ID,
                    anchorMessageId = followUp.id,
                    includeStoredTranscriptions = false,
                ),
            )

            assertEquals(
                listOf(root.id, tool.id, result.id, failed.id, followUp.id),
                loaded.messages.map { it.id },
            )
            val projectedFailure = loaded.messages.single { it.id == failed.id }
            assertEquals(Participant.MODEL, projectedFailure.participant)
            assertEquals(MessageStatus.SUCCESS, projectedFailure.status)
            assertTrue(projectedFailure.text.startsWith("partial answer"))
            assertEquals(
                1,
                Regex(Regex.escape(displayedErrorDetail)).findAll(projectedFailure.text).count(),
            )
            assertFalse(projectedFailure.text.contains(rawErrorDetail))
            assertTrue(projectedFailure.text.contains("<generation_interrupted reason=\"error\">"))
            val retainedSegments = Json.decodeFromString<List<MessageSegment>>(
                requireNotNull(loaded.entities.single { it.id == failed.id }.toolCallJson),
            )
            assertEquals(listOf("answer", "error"), retainedSegments.map { it.type })
        }

    @Test
    fun `branch switch rebuilds the selected path from the new immutable snapshot`() = runTest {
        val root = message("root", null, Participant.USER, "root", 0, "root-run")
        val first = message("first", root.id, Participant.MODEL, "first", 1, "run-a")
        val second = message("second", root.id, Participant.MODEL, "second", 2, "run-b")
        val entities = listOf(root, first, second)
        val dao = mockk<ChatDao>()
        coEvery { dao.getProviderContextTopologySnapshot(CONVERSATION_ID) } returnsMany listOf(
            snapshot(entities, Json.encodeToString(mapOf(root.id to first.id))),
            snapshot(entities, Json.encodeToString(mapOf(root.id to second.id))),
        )
        val byId = entities.associateBy(MessageEntity::id)
        coEvery { dao.getMessagesByIds(any()) } answers {
            firstArg<List<String>>().mapNotNull(byId::get)
        }
        val loader = DurableSelectedContextLoader(
            conversations = ConversationRepository(dao, database = null),
            generationErrorFormatter = { it },
        )
        val request = DurableSelectedContextRequest(
            conversationId = CONVERSATION_ID,
            followSelectedBranch = true,
            includeStoredTranscriptions = false,
        )

        val before = loader.load(request)
        val after = loader.load(request)

        assertEquals(listOf(root.id, first.id), before.entities.map { it.id })
        assertEquals(listOf(root.id, second.id), after.entities.map { it.id })
    }

    @Test
    fun `missing selected payload row fails closed`() = runTest {
        val entity = message("latest", null, Participant.USER, "small", 0, "run")
        val loader = loader(snapshot(listOf(entity)), emptyList(), mutableListOf()) { it }

        try {
            loader.load(
                DurableSelectedContextRequest(
                    conversationId = CONVERSATION_ID,
                    anchorMessageId = entity.id,
                    includeStoredTranscriptions = false,
                ),
            )
            fail("Expected an immutable snapshot failure")
        } catch (error: DurableContextLoadException) {
            assertTrue(error.message.orEmpty().contains("changed"))
        }
    }

    private fun loader(
        snapshot: ProviderContextTopologySnapshot,
        entities: List<MessageEntity>,
        requestedIds: MutableList<List<String>>,
        generationErrorFormatter: (String) -> String,
    ): DurableSelectedContextLoader {
        val dao = mockk<ChatDao>()
        coEvery { dao.getProviderContextTopologySnapshot(CONVERSATION_ID) } returns snapshot
        val byId = entities.associateBy(MessageEntity::id)
        coEvery { dao.getMessagesByIds(any()) } answers {
            firstArg<List<String>>().also { requestedIds += it }.mapNotNull(byId::get)
        }
        return DurableSelectedContextLoader(
            conversations = ConversationRepository(dao, database = null),
            generationErrorFormatter = generationErrorFormatter,
        )
    }

    private fun snapshot(
        entities: List<MessageEntity>,
        selectedBranchesJson: String? = null,
    ) = ProviderContextTopologySnapshot(
        selectedBranchesJson = selectedBranchesJson,
        messages = entities.sortedBy(MessageEntity::timestamp).map(::topology),
    )

    private fun topology(entity: MessageEntity) = MessageContextTopology(
        id = entity.id,
        conversationId = entity.conversationId,
        parentId = entity.parentId,
        status = entity.status,
        participant = entity.participant,
        timestamp = entity.timestamp,
        modelName = entity.modelName,
        runId = entity.runId,
        runSequence = entity.runSequence,
        consumedAtPass = entity.consumedAtPass,
    )

    private fun message(
        id: String,
        parentId: String?,
        participant: Participant,
        text: String,
        sequence: Long,
        runId: String,
    ) = MessageEntity(
        id = id,
        conversationId = CONVERSATION_ID,
        parentId = parentId,
        text = text,
        status = MessageStatus.SUCCESS,
        participant = participant,
        timestamp = sequence,
        modelName = "provider:model",
        runId = runId,
        runSequence = sequence,
    )

    private companion object {
        const val CONVERSATION_ID = "conversation"
    }
}
