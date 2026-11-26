package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiResponseTool
import com.newoether.agora.api.OpenAiResponsesRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ChatGptProviderTest {
    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun requestBecomesStatelessAndDropsUnsupportedFields() {
        val request = OpenAiResponsesRequest(
            model = "gpt-5",
            input = listOf(
                obj("""{"type":"message","role":"system","content":[{"type":"input_text","text":"Be brief."}]}"""),
                obj("""{"type":"message","role":"user","id":"msg_1","content":[{"type":"input_text","text":"hi"}]}"""),
                obj("""{"type":"reasoning","id":"rs_1","summary":[]}"""),
                obj("""{"type":"reasoning","id":"rs_2","encrypted_content":"abc","summary":[]}"""),
                obj("""{"type":"function_call","id":"fc_1","call_id":"c1","name":"search","arguments":"{}"}"""),
            ),
            tools = listOf(OpenAiResponseTool(name = "search"), OpenAiResponseTool(type = "web_search")),
            temperature = 0.5f,
            serviceTier = "priority",
        )
        val out = chatGptResponsesRequest(request)

        assertEquals("Be brief.", out.instructions)
        assertEquals(false, out.store)
        assertEquals(listOf("reasoning.encrypted_content"), out.include)
        assertNull(out.temperature)
        assertNull(out.serviceTier)
        assertEquals(listOf("message", "reasoning", "function_call"), out.input.map { (it["type"] as JsonPrimitive).content })
        out.input.forEach { assertFalse(it.containsKey("id")) }
        assertEquals(JsonPrimitive(CHATGPT_TOOL_NAMESPACE), out.input.last()["namespace"])

        val tools = out.tools!!
        assertEquals(listOf("namespace", "web_search"), tools.map { it.type })
        assertEquals(listOf("search"), tools.first().tools!!.map { it.name })
    }

    @Test
    fun requestWithoutToolsHasNoToolList() {
        val out = chatGptResponsesRequest(OpenAiResponsesRequest(model = "gpt-5", input = emptyList()))
        assertNull(out.tools)
        assertNull(out.instructions)
    }

    @Test
    fun modelListKeepsOnlyListedSlugs() {
        val root = obj(
            """{"models":[{"slug":"gpt-5","visibility":"list"},{"slug":"internal","visibility":"hide"},{"slug":"gpt-5-mini"},{"slug":"gpt-5"}]}""",
        )
        assertEquals(listOf("gpt-5", "gpt-5-mini"), parseChatGptModels(root))
    }
}
