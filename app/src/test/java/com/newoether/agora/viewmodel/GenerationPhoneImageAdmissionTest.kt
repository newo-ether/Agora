package com.newoether.agora.viewmodel

import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.resolveRequest
import com.newoether.agora.api.util.contextWindowUsage
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.phone.PHONE_MAX_SCREENSHOTS_UNLIMITED
import com.newoether.agora.phone.PhoneUseSettings
import com.newoether.agora.util.Constants
import io.mockk.every
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationPhoneImageAdmissionTest {
    @Test
    fun admissionFreezesNondefaultLimitForResolverAndCompactWhileNextRunReadsNewSetting() = runTest {
        val fixture = RequestBuilderFixture(Constants.PROVIDER_OPENAI, false)
        val settings = MutableStateFlow(PhoneUseSettings(maxScreenshots = 2))
        every { fixture.settings.phoneUse } returns settings
        val images = (1..3).map { image("phone-$it") }
        val history = listOf(user("prompt")) + round("history", listOf(call("capture", images)))
        val original = history.toList()
        val admitted = fixture.builder.captureAdmissionSnapshot("conversation", "run", fixture.modelId)
        val requestConfig = providerConfigFor(admitted)
        val first = requestConfig.resolveRequest(history)

        assertEquals(2, admitted.config.phoneMaxScreenshots)
        assertEquals(2, admitted.automaticCompact.mainPhoneMaxScreenshots)
        assertEquals(2, admitted.automaticCompact.generationConfig.phoneMaxScreenshots)
        assertEquals(images.takeLast(2).map { it.path }.toSet(), visibleImages(first.messages))

        settings.value = settings.value.copy(maxScreenshots = 1)
        repeat(2) {
            assertEquals(first, requestConfig.resolveRequest(history))
        }
        val next = fixture.builder.captureAdmissionSnapshot("conversation", "next-run", fixture.modelId)
        val preview = fixture.builder.captureContextProjectionSnapshot("conversation", fixture.modelId)

        assertEquals(1, next.config.phoneMaxScreenshots)
        assertEquals(1, next.automaticCompact.mainPhoneMaxScreenshots)
        assertEquals(1, next.automaticCompact.generationConfig.phoneMaxScreenshots)
        assertEquals(1, preview.config.phoneMaxScreenshots)
        assertEquals(setOf(images.last().path), visibleImages(providerConfigFor(next).resolveRequest(history).messages))
        assertEquals(original, history)
        assertEquals(images, history[1].toolCall!!.resultImages)
    }

    @Test
    fun unlimitedAdmissionRetainsAllScreenshotsInContinuationsAfterSettingChanges() = runTest {
        val fixture = RequestBuilderFixture(Constants.PROVIDER_OPENAI, false)
        val settings = MutableStateFlow(PhoneUseSettings(maxScreenshots = PHONE_MAX_SCREENSHOTS_UNLIMITED))
        every { fixture.settings.phoneUse } returns settings
        val screenshots = (1..25).map { image("unlimited-$it") }
        val paths = screenshots.map { it.path }
        val history = listOf(user("prompt")) + round("unlimited-history", listOf(call("before", screenshots.take(21))))
        val continuation = history + round("unlimited-next", listOf(call("after", screenshots.drop(21))))
        val admitted = fixture.builder.captureAdmissionSnapshot("conversation", "run", fixture.modelId)
        val config = providerConfigFor(admitted)

        assertEquals(PHONE_MAX_SCREENSHOTS_UNLIMITED, admitted.config.phoneMaxScreenshots)
        assertEquals(PHONE_MAX_SCREENSHOTS_UNLIMITED, admitted.automaticCompact.mainPhoneMaxScreenshots)
        assertEquals(PHONE_MAX_SCREENSHOTS_UNLIMITED, admitted.automaticCompact.generationConfig.phoneMaxScreenshots)
        assertEquals(paths.take(21).toSet(), visibleImages(config.resolveRequest(history).messages))

        settings.value = settings.value.copy(maxScreenshots = 16)
        val continued = config.resolveRequest(continuation)
        assertEquals(paths.toSet(), visibleImages(continued.messages))
        assertToolProtocolPreserved(continuation, continued.messages)
        assertTrue(visibleImages(config.copy(includeImages = false).resolveRequest(continuation).messages).isEmpty())

        val next = fixture.builder.captureAdmissionSnapshot("conversation", "next-run", fixture.modelId)
        val preview = fixture.builder.captureContextProjectionSnapshot("conversation", fixture.modelId)
        assertEquals(16, next.config.phoneMaxScreenshots)
        assertEquals(16, preview.config.phoneMaxScreenshots)
        assertEquals(paths.takeLast(16).toSet(), visibleImages(providerConfigFor(next).resolveRequest(continuation).messages))
        assertEquals(screenshots.take(21), history[1].toolCall!!.resultImages)
    }

    @Test
    fun continuationRecountsNewestPhoneImagesAndPreservesOtherImagesPairingAndSourceHistory() = runTest {
        val fixture = RequestBuilderFixture(Constants.PROVIDER_OPENAI, false)
        val settings = MutableStateFlow(PhoneUseSettings(maxScreenshots = 1))
        every { fixture.settings.phoneUse } returns settings
        val admitted = fixture.builder.captureAdmissionSnapshot("conversation", "run", fixture.modelId)
        val requestConfig = providerConfigFor(admitted)
        val attachment = image("user-attachment")
        val generated = image("generated")
        val oldPhone = image("old-phone")
        val newPhone = image("new-phone")
        val otherTool = image("file-image")
        val remotePhone = image("remote-phone")
        val history = listOf(
            user("attachment", attachment),
            ChatMessage(id = "generated", text = "created image", participant = Participant.MODEL,
                images = listOf(generated.path), imageArtifacts = mapOf(generated.path to generated)),
            user("inspect"),
        ) + round("old", listOf(
            call("phone-old", listOf(oldPhone)),
            call("file", listOf(otherTool), "file_view_image"),
            call("remote", listOf(remotePhone), "mcp_phone_screenshot_ab1234"),
        ))
        val originalHistory = history.toList()
        val first = requestConfig.resolveRequest(history)
        val unaffected = setOf(attachment.path, generated.path, otherTool.path, remotePhone.path)
        assertEquals(unaffected + oldPhone.path, visibleImages(first.messages))

        settings.value = settings.value.copy(maxScreenshots = 16)
        val continuation = history + round("next", listOf(call("phone-next", listOf(newPhone), "phone_tap")))
        val originalContinuation = continuation.toList()
        val continued = requestConfig.resolveRequest(continuation)

        assertEquals(unaffected + newPhone.path, visibleImages(continued.messages))
        assertFalse(visibleImages(continued.messages).contains(oldPhone.path))
        assertToolProtocolPreserved(continuation, continued.messages)
        assertEquals(originalHistory, history)
        assertEquals(originalContinuation, continuation)
        assertEquals(listOf(oldPhone), history.first { it.id == "result_old-1" }.toolCall!!.resultImages)
        assertEquals(listOf(newPhone), continuation.last().toolCall!!.resultImages)
        assertEquals(first, requestConfig.resolveRequest(history))

        val next = fixture.builder.captureAdmissionSnapshot("conversation", "next-run", fixture.modelId)
        val widened = providerConfigFor(next).resolveRequest(continuation)
        assertEquals(unaffected + oldPhone.path + newPhone.path, visibleImages(widened.messages))
        assertToolProtocolPreserved(continuation, widened.messages)
        assertEquals(originalContinuation, continuation)
    }

    @Test
    fun automaticCompactThresholdUsesTheSameFrozenImageBudgetAsActualDispatch() = runTest {
        val fixture = RequestBuilderFixture(Constants.PROVIDER_OPENAI, false)
        val settings = MutableStateFlow(PhoneUseSettings(maxScreenshots = 1))
        every { fixture.settings.phoneUse } returns settings
        val screenshots = (1..25).map { image("accounting-$it") }
        val path = listOf(user("old")) + round("accounting", listOf(call("capture", screenshots))) +
            user("current")
        val original = path.toList()
        val admitted = fixture.builder.captureAdmissionSnapshot("conversation", "run", fixture.modelId)
        val dispatched = providerConfigFor(admitted).resolveRequest(path)
        val projectedUsage = contextWindowUsage(dispatched.messages, tokenBudget = Int.MAX_VALUE).estimatedTokenCount
        val threshold = projectedUsage + 1

        suspend fun needed(snapshot: GenerationAdmissionSnapshot, limit: Int = threshold): Boolean {
            val compact = snapshot.automaticCompact
            val prompt = compact.mainPromptTemplate?.let {
                resolveGenerationPromptTemplate(it, compact.mainModelId.orEmpty())
            }
            return automaticCompactNeeded(
                path = path,
                contextLimit = limit,
                retainLogicalMessages = 1,
                userPrepend = prompt?.userPrepend ?: compact.userPrepend,
                userPostpend = prompt?.userPostpend ?: compact.userPostpend,
                assistantPrepend = prompt?.assistantPrepend ?: compact.assistantPrepend,
                assistantPostpend = prompt?.assistantPostpend ?: compact.assistantPostpend,
                phoneMaxScreenshots = compact.mainPhoneMaxScreenshots,
            )
        }

        assertTrue(needed(admitted, projectedUsage))
        assertFalse(needed(admitted))
        settings.value = settings.value.copy(maxScreenshots = PHONE_MAX_SCREENSHOTS_UNLIMITED)
        assertFalse(needed(admitted))
        assertEquals(dispatched, providerConfigFor(admitted).resolveRequest(path))
        val next = fixture.builder.captureAdmissionSnapshot("conversation", "next-run", fixture.modelId)
        val nextRequest = providerConfigFor(next).resolveRequest(path)
        assertEquals(screenshots.map { it.path }.toSet(), visibleImages(nextRequest.messages))
        assertTrue(contextWindowUsage(nextRequest.messages, tokenBudget = Int.MAX_VALUE).estimatedTokenCount >= threshold)
        assertTrue(needed(next))
        assertEquals(original, path)
    }

    private fun providerConfigFor(snapshot: GenerationAdmissionSnapshot) = ProviderConfig(
        apiKey = "key",
        modelId = snapshot.config.modelId,
        maxContextWindow = 1_000_000,
        includeImages = true,
        requestResolver = snapshot.config.requestResolver,
    )

    private fun visibleImages(messages: List<ChatMessage>): Set<String> =
        messages.flatMap { it.images }.toSet()

    private fun assertToolProtocolPreserved(source: List<ChatMessage>, projected: List<ChatMessage>) {
        source.filter { it.toolCall != null }.forEach { before ->
            val after = projected.single { it.id == before.id }
            assertEquals(before.participant, after.participant)
            assertEquals(before.parentId, after.parentId)
            val request = before.id.startsWith(Constants.TOOL_MSG_PREFIX)
            if (!request || before.segments.orEmpty().count { it.type == "tool" } == 1) {
                assertNotNull(after.toolCall)
                assertEquals(before.toolCall!!.copy(resultImages = emptyList()),
                    after.toolCall!!.copy(resultImages = emptyList()))
            }
            before.segments?.filter { it.type == "tool" }?.let { calls ->
                assertEquals(
                    calls.map { listOf(it.toolName, it.toolArgs, it.toolCallId, it.signature) },
                    after.segments.orEmpty().filter { it.type == "tool" }
                        .map { listOf(it.toolName, it.toolArgs, it.toolCallId, it.signature) },
                )
            }
            if (!request) assertEquals(before.text, after.text)
        }
        val sourceIds = source.map { it.id }.toSet()
        assertEquals(source.map { it.id }, projected.filter { it.id in sourceIds }.map { it.id })
    }

    private fun user(id: String, image: ImageArtifact? = null) = ChatMessage(
        id = id,
        text = "request:$id",
        participant = Participant.USER,
        images = listOfNotNull(image?.path),
        imageArtifacts = image?.let { mapOf(it.path to it) }.orEmpty(),
    )

    private fun image(id: String) = ImageArtifact(
        path = "/private/$id.png", mimeType = "image/png", sizeBytes = 80L,
        width = 1024, height = 1024, policyVersion = 1,
    )

    private fun call(id: String, images: List<ImageArtifact>, name: String = "phone_screenshot") =
        ToolCallData(toolName = name, arguments = "{\"screenshot\":true}", result = "result:$id",
            signature = "signature:$id", toolCallId = id, resultImages = images,
            resultText = "display:$id", structuredResult = "{\"ok\":true}")

    private fun round(id: String, calls: List<ToolCallData>): List<ChatMessage> {
        var next = 0
        return GenerationToolRoundBuilder(newId = { "$id-${next++}" }, nowMs = { 100L }).build(
            previousMessageId = "parent", conversationId = "conversation", runId = "run-$id",
            modelName = "model", providerName = Constants.PROVIDER_OPENAI, calls = calls,
            completedSegments = calls.map { call ->
                MessageSegment(type = "tool", toolName = call.toolName, toolArgs = call.arguments,
                    toolResult = call.result, toolCallId = call.toolCallId, signature = call.signature,
                    toolImages = call.resultImages, toolResultText = call.resultText,
                    toolStructuredResult = call.structuredResult)
            },
        ).pathMessages
    }
}
