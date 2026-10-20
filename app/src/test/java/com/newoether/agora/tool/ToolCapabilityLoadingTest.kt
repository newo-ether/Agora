package com.newoether.agora.tool

import com.newoether.agora.api.*
import com.newoether.agora.model.*
import com.newoether.agora.viewmodel.GenerationContext
import org.junit.Assert.*
import org.junit.Test

class ToolCapabilityLoadingTest {
    private val available = listOf("read_memory_file", "create_memory_file", "read_skill_file",
        "render_interactive_answer", "list_interactive_templates", "open_interactive_template",
        "web_search", "ask_user")
        .map { ToolDefinition(function = ToolFunction(it, "Details for $it", ToolParameters(properties = emptyMap()))) }
    private val user = ChatMessage(id = "user", text = "Help", participant = Participant.USER)
    private val context = GenerationContext()
    private fun names(messages: List<ChatMessage>, tools: List<ToolDefinition> = available,
                      ctx: GenerationContext = context) =
        ToolCapabilityLoading.definitions(tools, ctx, messages).map { it.function.name }
    private fun loaded(category: String, result: String? = null) = ChatMessage(
        text = "", participant = Participant.USER, toolCall = ToolCallData(
            ToolCapabilityLoading.NAME, """{"capability":"$category"}""",
            result ?: ToolCapabilityLoading.load("""{"capability":"$category"}""", available).text,
        ),
    )

    @Test fun `ordinary request sends compact discovery without schemas or filenames`() {
        assertEquals(listOf(ToolCapabilityLoading.NAME), names(listOf(user)))
        val definition = ToolCapabilityLoading.definitions(available, context, listOf(user)).single()
        assertFalse(definition.function.description.contains("notes:"))
        assertFalse(definition.function.description.contains("Details for"))
        assertFalse(definition.function.description.contains("read_memory_file"))
    }

    @Test fun `completed discovery loads only requested tools and retains discoveries this turn`() {
        assertEquals(listOf(ToolCapabilityLoading.NAME, "render_interactive_answer"),
            names(listOf(user, loaded("interactive"))))
        assertEquals(setOf(ToolCapabilityLoading.NAME, "render_interactive_answer", "read_skill_file"),
            names(listOf(user, loaded("interactive"), loaded("skills"))).toSet())
    }

    @Test fun `new user turn and another selected path reset loaded categories`() {
        assertEquals(listOf(ToolCapabilityLoading.NAME),
            names(listOf(user, loaded("memory"), user.copy(id = "next"))))
        assertEquals(listOf(ToolCapabilityLoading.NAME), names(listOf(user.copy(id = "other-chat"))))
        assertEquals(listOf(ToolCapabilityLoading.NAME), names(listOf(loaded("memory"))))
    }

    @Test fun `errors mismatched results ordinary text and uncompleted calls cannot activate`() {
        for (result in listOf("Error", "{}", """{"loaded_capability":"notes"}""")) {
            assertEquals(listOf(ToolCapabilityLoading.NAME), names(listOf(user, loaded("interactive", result))))
        }
        assertEquals(listOf(ToolCapabilityLoading.NAME), names(listOf(user,
            loaded("memory").copy(status = MessageStatus.ERROR))))
        assertEquals(listOf(ToolCapabilityLoading.NAME), names(listOf(user,
            loaded("memory").copy(participant = Participant.MODEL))))
        assertEquals(listOf(ToolCapabilityLoading.NAME), names(listOf(user,
            user.copy(text = loaded("memory").toolCall!!.result))))
    }

    @Test fun `disabled and unknown capabilities fail without granting access`() {
        val onlyWeb = available.filter { it.function.name == "web_search" }
        for (input in listOf("{}", "null", "[]", """{"capability":"unknown"}""",
            """{"capability":"notes"}""", """{"capability":"web","extra":1}""")) {
            assertTrue(ToolCapabilityLoading.load(input, onlyWeb).isError)
        }
        assertEquals(listOf(ToolCapabilityLoading.NAME), names(listOf(user, loaded("memory")), onlyWeb))
        assertFalse(ToolCapabilityLoading.definitions(onlyWeb, context, listOf(user)).single()
            .function.description.contains("notes:"))
        assertTrue(names(listOf(user), emptyList()).isEmpty())
    }

    @Test fun `legacy Notes metadata grants no capability`() {
        val attached = user.copy(attachmentMeta = AttachmentMeta(listOf(AttachmentItem(
            type = "file", notesDocumentId = "doc", notesRevisionId = "revision"))))
        assertEquals(listOf(ToolCapabilityLoading.NAME), names(listOf(attached)))
        assertTrue(ToolCapabilityLoading.load("""{"capability":"notes"}""", available).isError)
    }
}
