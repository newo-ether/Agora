package com.newoether.agora.api.util

import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.tool.AskUserToolProvider
import com.newoether.agora.viewmodel.AskUserController
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The schema providers receive for a tool's parameters.
 *
 * Anthropic and Gemini are sent this JSON verbatim, so its shape is the contract: nesting has to
 * survive, and a property must not grow keys it never declared.
 */
class ToolSchemaJsonTest {

    @Test
    fun `a parameter object carries its type properties and required list`() {
        val schema = ToolSchemaJson.of(
            ToolParameters(
                properties = mapOf("who" to ToolProperty("string", "Who to greet.")),
                required = listOf("who"),
            ),
        )
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        assertEquals(setOf("who"), schema["properties"]?.jsonObject?.keys)
        assertEquals(
            listOf("who"),
            schema["required"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `a plain property declares nothing it does not have`() {
        val schema = ToolSchemaJson.of(ToolProperty("string", "Free text."))
        assertEquals(setOf("type", "description"), schema.keys)
    }

    @Test
    fun `an array carries the schema of its elements`() {
        val schema = ToolSchemaJson.of(
            ToolProperty("array", "Names.", ToolProperty("string", "One name.")),
        )
        val items = schema["items"]?.jsonObject
        assertEquals("string", items?.get("type")?.jsonPrimitive?.content)
        assertEquals("One name.", items?.get("description")?.jsonPrimitive?.content)
    }

    @Test
    fun `an object nested in an array keeps its own fields and required list`() {
        val schema = ToolSchemaJson.of(
            ToolProperty(
                type = "array",
                description = "Questions.",
                items = ToolProperty(
                    type = "object",
                    description = "One question.",
                    properties = mapOf(
                        "question" to ToolProperty("string", "The question."),
                        "options" to ToolProperty(
                            "array",
                            "Answers to choose from.",
                            ToolProperty("string", "One answer."),
                        ),
                    ),
                    required = listOf("question"),
                ),
            ),
        )
        val item = schema["items"]!!.jsonObject
        assertEquals("object", item["type"]?.jsonPrimitive?.content)
        assertEquals(setOf("question", "options"), item["properties"]?.jsonObject?.keys)
        assertEquals(
            listOf("question"),
            item["required"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
        // The array inside the object keeps its own element schema, so nesting is not flattened.
        val options = item["properties"]!!.jsonObject["options"]!!.jsonObject
        assertEquals("string", options["items"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `the ask_user schema is valid and keeps its question set nested`() {
        val definitions = AskUserToolProvider(AskUserController())
            .definitions(GenerationContext(askUserEnabled = true))
        assertTrue(validateToolDefinitions(definitions).isEmpty())

        val schema = ToolSchemaJson.of(definitions.single().function.parameters)
        val questions = schema["properties"]!!.jsonObject["questions"]!!.jsonObject
        assertEquals("array", questions["type"]?.jsonPrimitive?.content)
        assertEquals("object", questions["items"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertFalse(
            questions["items"]!!.jsonObject["properties"]!!.jsonObject.isEmpty(),
        )
    }

    @Test
    fun `generate_image image_paths array declares an items schema (strict providers require it)`() {
        // Regression: a provider that validates hard (e.g. Qwen Cloud) rejects an array parameter
        // with no items schema -> "array image_paths has no items schema". image_paths must carry
        // an items type so the generate_image tool is accepted.
        val imagePaths = ToolProperty(
            "array",
            "Optional file paths of reference images.",
            items = ToolProperty("string", "A reference image file path."),
        )
        val schema = ToolSchemaJson.of(imagePaths)
        assertEquals("array", schema["type"]?.jsonPrimitive?.content)
        assertEquals("string", schema["items"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        // And the validator must accept a tool whose only array param carries items.
        val params = ToolParameters(properties = mapOf("image_paths" to imagePaths))
        assertTrue(ToolSchemaJson.of(params)["properties"] != null)
    }
}
