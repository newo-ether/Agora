package com.newoether.agora.api.util

import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON Schema for tool parameters, for providers that take a schema object instead of Agora's typed
 * [ToolParameters].
 *
 * Anthropic and Gemini both want the same plain schema, so the conversion lives here once rather
 * than being repeated per provider. It is recursive: an array carries its item schema and an object
 * carries its own properties, which is what lets a tool declare a list of structured values.
 */
object ToolSchemaJson {

    /** The schema of a tool's whole parameter object; an external schema is returned unchanged. */
    fun of(parameters: ToolParameters): JsonObject = parameters.schema ?: JsonObject(
        buildMap {
            put("type", JsonPrimitive(parameters.type))
            put("properties", propertiesOf(parameters.properties))
            put("required", JsonArray(parameters.required.map(::JsonPrimitive)))
        },
    )

    /** The schema of one property, including whatever it nests. */
    fun of(property: ToolProperty): JsonObject = JsonObject(
        buildMap<String, JsonElement> {
            put("type", JsonPrimitive(property.type))
            put("description", JsonPrimitive(property.description))
            property.items?.let { put("items", of(it)) }
            property.properties?.let { put("properties", propertiesOf(it)) }
            property.required?.let { required ->
                put("required", JsonArray(required.map(::JsonPrimitive)))
            }
        },
    )

    private fun propertiesOf(properties: Map<String, ToolProperty>): JsonObject =
        JsonObject(properties.mapValues { (_, property) -> of(property) })
}
