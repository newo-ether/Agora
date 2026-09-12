package com.newoether.agora.data

import com.newoether.agora.data.local.TaskEntity
import com.newoether.agora.automation.LoopManager
import com.newoether.agora.automation.TaskManager
import com.newoether.agora.data.repository.ConversationRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatPromptBuilderTest {

    private fun builder(): HeartbeatPromptBuilder {
        val taskManager = mockk<TaskManager>(relaxed = true)
        every { taskManager.tasks } returns MutableStateFlow(emptyList<TaskEntity>())
        return HeartbeatPromptBuilder(
            taskManager = taskManager,
            loopManager = mockk(relaxed = true),
            conversationRepository = mockk<ConversationRepository>(relaxed = true),
            memoryManager = mockk(relaxed = true),
            taskRepository = mockk(relaxed = true),
        )
    }

    private fun sms(id: Long, address: String, preview: String) = SmsMessageData(
        id = id,
        address = address,
        date = 1_700_000_000_000L,
        preview = preview,
        body = "",
        read = false,
    )

    @Test
    fun `omits New SMS section when no pending messages`() = runBlocking {
        val prompt = builder().buildHeartbeatPrompt(customPrompt = "")
        assertFalse("## New SMS" in prompt)
    }

    @Test
    fun `includes New SMS with sender and preview`() = runBlocking {
        val prompt = builder().buildHeartbeatPrompt(
            customPrompt = "",
            pendingSms = listOf(sms(42L, "+15551234567", "Hello from Kai")),
        )
        assertTrue("## New SMS" in prompt)
        assertTrue("+15551234567" in prompt)
        assertTrue("id: 42" in prompt)
        assertTrue("Hello from Kai" in prompt)
    }

    @Test
    fun `New SMS renders placeholder for blank sender and omits empty preview`() = runBlocking {
        val prompt = builder().buildHeartbeatPrompt(
            customPrompt = "",
            pendingSms = listOf(sms(7L, "", "")),
        )
        assertTrue("(unknown sender)" in prompt)
        assertFalse(": " in prompt.substringAfter("(id: 7)"))
    }

    @Test
    fun `includes New Notifications section`() = runBlocking {
        val prompt = builder().buildHeartbeatPrompt(
            customPrompt = "",
            pendingNotifications = listOf(
                NotificationRecord(
                    id = "n1",
                    packageName = "com.whatsapp",
                    appLabel = "WhatsApp",
                    title = "T",
                    text = "B",
                    postedAt = 1_700_000_000_000L,
                    preview = "New message",
                ),
            ),
        )
        assertTrue("## New Notifications" in prompt)
        assertTrue("WhatsApp" in prompt)
        assertTrue("New message" in prompt)
    }

    @Test
    fun `omits both sections when empty and keeps custom prompt`() = runBlocking {
        val prompt = builder().buildHeartbeatPrompt(
            customPrompt = "Always be concise.",
            pendingSms = emptyList(),
            pendingNotifications = emptyList(),
        )
        assertFalse("## New SMS" in prompt)
        assertFalse("## New Notifications" in prompt)
        assertTrue("## Custom Instructions" in prompt)
        assertTrue("Always be concise." in prompt)
    }
}