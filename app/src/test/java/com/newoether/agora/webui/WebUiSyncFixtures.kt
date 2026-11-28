package com.newoether.agora.webui

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A plain-JVM session still runs the production explicit-open recovery boundary. */
internal fun ConversationRepository.stubOpenRecovery(dao: ChatDao = mockk()): ChatDao {
    every { chatDao } returns dao
    every { subagentJobs } returns MutableStateFlow(emptyMap())
    coEvery { dao.getSubagentFamilyRootId(any()) } answers { firstArg<String>() }
    coEvery { dao.getSubagentIds(any()) } returns emptyList()
    coEvery { dao.getLiveRun(any()) } returns null
    coEvery { dao.getValidatedSubagentStatus(any()) } returns null
    coEvery { withProviderContextSnapshot<Unit>(any()) } coAnswers {
        firstArg<suspend () -> Unit>().invoke()
    }
    return dao
}

internal fun topology(id: String, parent: String?, participant: Participant, timestamp: Long) =
    MessageContextTopology(
        id = id,
        conversationId = "a",
        parentId = parent,
        status = MessageStatus.SUCCESS,
        participant = participant,
        timestamp = timestamp,
        modelName = null,
        runId = "run-$id",
        runSequence = timestamp,
        consumedAtPass = null,
    )

internal fun message(
    id: String,
    parent: String?,
    participant: Participant,
    text: String,
    status: MessageStatus = MessageStatus.SUCCESS,
) = ChatMessage(
    id = id,
    parentId = parent,
    text = text,
    participant = participant,
    status = status,
    timestamp = 10,
)

internal val JsonObject.type: String get() = string("type")

internal fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

internal fun JsonObject.ids(): List<String> =
    this["messages"]!!.jsonArray.map { it.jsonObject.string("id") }

/** Session imports produce real PNG bytes for the preview decoder. */
internal fun webUiImageArtifact(path: String) = com.newoether.agora.model.ImageArtifact(
    path = path,
    mimeType = "image/png",
    sizeBytes = java.io.File(path).length(),
    width = 31,
    height = 47,
    policyVersion = 1,
)
