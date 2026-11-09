package com.newoether.agora.mcp

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.api.util.ToolSchemaJson
import com.newoether.agora.api.providerToolValidationError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** MCP tool schemas reach providers intact, including nesting the typed model cannot express. */
class McpToolSchemaWireTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    // Shapes taken from the Playwright MCP tools that failed in the reported conversation.
    private val dropSchema = json.parseToJsonElement(
        """
        {"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"object",
         "properties":{
           "target":{"type":"string"},
           "data":{"type":"object","additionalProperties":{"type":"string"},
                   "description":"MIME type to value"}},
         "required":["target","missing"],"additionalProperties":false}
        """,
    ).jsonObject
    private val fillFormSchema = json.parseToJsonElement(
        """
        {"type":"object","properties":{"fields":{"type":"array","items":{"type":"object",
          "properties":{"name":{"type":"string"},"type":{"type":"string","enum":["textbox","checkbox"]}},
          "required":["name","type"]}}},"required":["fields"]}
        """,
    ).jsonObject

    private fun definition(name: String, schema: JsonObject): ToolDefinition = McpToolDescriptor(
        publicName = name,
        serverId = "server",
        serverName = "Browser",
        remote = McpRemoteTool(name = name, description = "d", inputSchema = schema),
    ).asToolDefinition()

    @Test
    fun wireSchemaKeepsNestingAndNormalizesOnlyTheTopLevel() {
        val schema = definition("mcp_drop", dropSchema).function.parameters.schema!!
        assertFalse(schema.containsKey("\$schema"))
        assertEquals("[\"target\"]", schema["required"].toString())
        assertEquals("false", schema["additionalProperties"].toString())
        assertEquals(
            dropSchema["properties"]!!.jsonObject["data"],
            schema["properties"]!!.jsonObject["data"],
        )
    }

    @Test
    fun openAiWireFormatSendsTheServerSchema() {
        val tool = definition("mcp_fill_form", fillFormSchema)
        val parameters = json.parseToJsonElement(json.encodeToString(ToolDefinition.serializer(), tool))
            .jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals(fillFormSchema, parameters)
    }

    @Test
    fun anthropicSchemaIsTheServerSchema() {
        val tool = definition("mcp_fill_form", fillFormSchema)
        assertEquals(fillFormSchema, ToolSchemaJson.of(tool.function.parameters))
    }

    @Test
    fun mapAndNestedObjectSchemasPassLocalValidation() {
        val violation = providerToolValidationError(
            listOf(definition("mcp_drop", dropSchema), definition("mcp_fill_form", fillFormSchema)),
        )
        assertEquals(null, violation)
    }

    @Test
    fun typedSchemasAreStillCheckedAndWrittenAsBefore() {
        val typed = ToolDefinition(
            function = ToolFunction(
                name = "built_in",
                description = "d",
                parameters = ToolParameters(
                    properties = mapOf("opts" to ToolProperty(type = "object", description = "")),
                ),
            ),
        )
        assertEquals(
            "tool built_in object opts has no property schema",
            providerToolValidationError(listOf(typed)),
        )
        val parameters = json.parseToJsonElement(json.encodeToString(ToolDefinition.serializer(), typed))
            .jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals(
            "{\"type\":\"object\",\"properties\":{\"opts\":{\"type\":\"object\",\"description\":\"\"}},\"required\":[]}",
            parameters.toString(),
        )
    }
}
