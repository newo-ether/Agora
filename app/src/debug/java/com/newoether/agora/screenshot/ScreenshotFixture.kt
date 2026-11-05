package com.newoether.agora.screenshot

import com.newoether.agora.AgoraApplication
import com.newoether.agora.api.DebugProvider
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import kotlinx.coroutines.runBlocking

object ScreenshotFixture {
    private const val THREE_BODY_ID = "7a294d3f-b710-4c8c-8e52-574825f3012e"
    private const val MODEL_ID = "Debug:${DebugProvider.MODEL_ID}"
    private const val BASE_TIME = 1_797_700_000_000L

    @JvmStatic
    fun seed(application: AgoraApplication, destination: String) = runBlocking {
        val container = application.awaitContainer() ?: error("Database unavailable")
        val settings = container.settingsManager

        settings.saveAppLanguage("en")
        settings.saveOnboardingCompleted(true)
        settings.saveThemeMode("DARK")
        settings.saveAmoledEnabled(false)
        settings.saveColorScheme("FOREST")
        settings.saveSchemeStyle("TONAL_SPOT")
        settings.saveDynamicColor(false)
        settings.saveReduceMotion(true)
        settings.saveSelectedModel(MODEL_ID)
        settings.saveAvailableModels(DebugProvider.PROVIDER_NAME, listOf(MODEL_ID))
        settings.saveEnabledModels(setOf(MODEL_ID))
        settings.saveModelAliases(mapOf(MODEL_ID to "GPT 5.6 Sol"))
        settings.saveModelProviderNames(mapOf(MODEL_ID to false))
        settings.saveShellEnabled(true)
        settings.saveShellDevices(
            listOf(
                ShellDeviceConfig(
                    id = "fixture-northstar",
                    name = "Northstar",
                    description = "Studio workstation",
                    serverUrl = "http://northstar.invalid:14216",
                ),
                ShellDeviceConfig(
                    id = "fixture-harbor",
                    name = "Harbor",
                    description = "Remote Linux server",
                    serverUrl = "http://harbor.invalid:14216",
                ),
            ),
        )
        settings.saveAccessActiveMemory(true)
        settings.saveAccessSavedMemories(true)

        container.memoryManager.updateActiveMemory(
            "# Active Memory\n\n- Use metric units for recipes.\n- Favorite season: autumn.",
        )
        runCatching {
            container.memoryManager.createFile(
                "garden-notes",
                "# Balcony Garden\n\nWater herbs in the morning and rotate pots weekly.",
                "Seasonal notes for a small herb garden",
            )
        }
        runCatching {
            container.memoryManager.createFile(
                "reading-list",
                "# Reading List\n\nThe Left Hand of Darkness\nThe Dispossessed\nInvisible Cities",
                "Books to read this season",
            )
        }

        val conversations = fixtureConversations()
        val runs = conversations.mapIndexed { index, conversation -> fixtureRun(conversation.id, index) }
        val messages = fixtureMessages()
        container.conversationRepository.importExternalConversationGraph(
            conversations = conversations,
            runs = runs,
            messages = messages,
            replace = true,
        )
    }

    private fun fixtureConversations(): List<ChatEntity> = listOf(
        ChatEntity(
            id = THREE_BODY_ID,
            title = "Three Body Problem Physics",
            lastUpdated = BASE_TIME + 50_000,
            dataChangedAt = BASE_TIME + 50_000,
            modelId = MODEL_ID,
        ),
        conversation("fixture-travel", "Planning a Coastal Train Trip", 40_000),
        conversation("fixture-garden", "Growing Herbs Indoors", 30_000),
        conversation("fixture-music", "Understanding Chord Progressions", 20_000),
        conversation("fixture-baking", "Sourdough Timing Guide", 10_000),
    )

    private fun conversation(id: String, title: String, offset: Long) = ChatEntity(
        id = id,
        title = title,
        lastUpdated = BASE_TIME + offset,
        dataChangedAt = BASE_TIME + offset,
        modelId = MODEL_ID,
    )

    private fun fixtureRun(conversationId: String, index: Int) = RunEntity(
        id = "run-$conversationId",
        conversationId = conversationId,
        parentRunId = null,
        status = RunStatus.COMPLETED,
        activeSlot = null,
        startedAt = BASE_TIME + index * 1_000,
        lastCheckpointAt = BASE_TIME + index * 1_000 + 500,
        endedAt = BASE_TIME + index * 1_000 + 900,
        endReason = RunEndReason.MODEL_COMPLETED,
    )

    private fun fixtureMessages(): List<MessageEntity> {
        val messages = mutableListOf<MessageEntity>()
        messages += MessageEntity(
            id = "three-body-user",
            conversationId = THREE_BODY_ID,
            text = "What are the equations that describes the physics of a three body system? Answer briefly.",
            participant = Participant.USER,
            timestamp = BASE_TIME,
            runId = "run-$THREE_BODY_ID",
            runSequence = 0,
            consumedAtPass = 0,
        )
        messages += MessageEntity(
            id = "three-body-model",
            conversationId = THREE_BODY_ID,
            parentId = "three-body-user",
            text = THREE_BODY_ANSWER,
            thoughts = "We need answer briefly with Newtonian equations. Give the coupled second-order vector equations, then the compact summation form and define the symbols.",
            thoughtTitle = "Reasoned for 6 seconds",
            tokenCount = 292,
            inputTokenCount = 27,
            cachedInputTokenCount = 0,
            outputTokenCount = 265,
            reasoningTokenCount = 48,
            generationDurationMs = 4_800,
            timeToFirstTokenMs = 720,
            totalGenerationMs = 5_520,
            status = MessageStatus.SUCCESS,
            participant = Participant.MODEL,
            timestamp = BASE_TIME + 1_000,
            thoughtTimeMs = 6_000,
            modelName = MODEL_ID,
            runId = "run-$THREE_BODY_ID",
            runSequence = 1,
        )
        messages += simplePair(
            "fixture-travel",
            "How should I plan a scenic train trip along the coast?",
            "Choose two or three base towns, reserve the longest rail segments first, and leave one flexible day for weather.",
        )
        messages += simplePair(
            "fixture-garden",
            "Which herbs grow well on a bright kitchen windowsill?",
            "Basil, chives, mint, and parsley do well with several hours of light and evenly moist soil.",
        )
        messages += simplePair(
            "fixture-music",
            "Why does a ii-V-I progression sound resolved?",
            "The roots move by fifths while the guide tones lead smoothly into the tonic chord.",
        )
        messages += simplePair(
            "fixture-baking",
            "How can I tell when sourdough bulk fermentation is complete?",
            "Look for moderate volume growth, a domed surface, visible bubbles, and dough that feels elastic rather than dense.",
        )
        return messages
    }

    private fun simplePair(conversationId: String, question: String, answer: String): List<MessageEntity> =
        listOf(
            MessageEntity(
                id = "$conversationId-user",
                conversationId = conversationId,
                text = question,
                participant = Participant.USER,
                timestamp = BASE_TIME - 1_000,
                runId = "run-$conversationId",
                runSequence = 0,
                consumedAtPass = 0,
            ),
            MessageEntity(
                id = "$conversationId-model",
                conversationId = conversationId,
                parentId = "$conversationId-user",
                text = answer,
                tokenCount = 80,
                inputTokenCount = 20,
                outputTokenCount = 60,
                status = MessageStatus.SUCCESS,
                participant = Participant.MODEL,
                timestamp = BASE_TIME,
                modelName = MODEL_ID,
                runId = "run-$conversationId",
                runSequence = 1,
            ),
        )

    private val THREE_BODY_ANSWER = """
        In Newtonian gravity, for masses \(m_1,m_2,m_3\) at positions \(\mathbf r_1,\mathbf r_2,\mathbf r_3\):

        \[
        \ddot{\mathbf r}_1=Gm_2\frac{\mathbf r_2-\mathbf r_1}{|\mathbf r_2-\mathbf r_1|^3}+Gm_3\frac{\mathbf r_3-\mathbf r_1}{|\mathbf r_3-\mathbf r_1|^3}
        \]

        \[
        \ddot{\mathbf r}_2=Gm_1\frac{\mathbf r_1-\mathbf r_2}{|\mathbf r_1-\mathbf r_2|^3}+Gm_3\frac{\mathbf r_3-\mathbf r_2}{|\mathbf r_3-\mathbf r_2|^3}
        \]

        \[
        \ddot{\mathbf r}_3=Gm_1\frac{\mathbf r_1-\mathbf r_3}{|\mathbf r_1-\mathbf r_3|^3}+Gm_2\frac{\mathbf r_2-\mathbf r_3}{|\mathbf r_2-\mathbf r_3|^3}
        \]

        Compactly,

        \[
        \ddot{\mathbf r}_i=G\sum_{j\ne i}m_j\frac{\mathbf r_j-\mathbf r_i}{|\mathbf r_j-\mathbf r_i|^3},\qquad i=1,2,3.
        \]

        These coupled nonlinear differential equations generally require numerical integration.
    """.trimIndent()
}
