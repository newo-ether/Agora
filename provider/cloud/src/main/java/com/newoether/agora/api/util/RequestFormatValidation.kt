package com.newoether.agora.api.util

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolProperty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Raised only before opening an HTTP request. A provider request that cannot be proven to satisfy
 * its wire-format grammar must fail locally rather than relying on a remote 400 response.
 */
class RequestFormatException(
    val provider: String,
    val violations: List<String>,
) : IllegalStateException(
    "$provider request validation failed: ${violations.joinToString("; ")}"
)

internal fun requireValidRequestFormat(
    provider: String,
    violations: List<String>,
) {
    if (violations.isNotEmpty()) {
        throw RequestFormatException(provider, violations.distinct())
    }
}

private val requestValidationJson = Json { ignoreUnknownKeys = true }
val safeWireToolName = Regex("[A-Za-z0-9_-]{1,64}")
val safeWireToolCallId = Regex("[A-Za-z0-9_-]{1,128}")

/**
 * Final serialized-body gate. Object validators prove the typed request graph; this proves that
 * serializer configuration did not omit or reshape mandatory wire fields before network I/O.
 */
internal fun requireValidSerializedRequest(
    provider: String,
    body: String,
    requiredStringFields: Set<String> = emptySet(),
    requiredArrayFields: Set<String> = emptySet(),
) {
    val root = runCatching { requestValidationJson.parseToJsonElement(body) as? JsonObject }
        .getOrNull()
    val violations = mutableListOf<String>()
    if (root == null) {
        violations += "serialized request is not a JSON object"
    } else {
        requiredStringFields.forEach { field ->
            val value = runCatching { root[field]?.jsonPrimitive?.content }.getOrNull()
            if (value.isNullOrBlank()) violations += "serialized $field is absent or blank"
        }
        requiredArrayFields.forEach { field ->
            val value = root[field] as? JsonArray
            if (value.isNullOrEmpty()) violations += "serialized $field is absent or empty"
        }
    }
    requireValidRequestFormat(provider, violations)
}

internal fun validateToolDefinitions(tools: List<ToolDefinition>?): List<String> {
    if (tools.isNullOrEmpty()) return emptyList()
    val violations = mutableListOf<String>()
    val names = mutableSetOf<String>()
    tools.forEachIndexed { index, tool ->
        val function = tool.function
        if (tool.type != "function") violations += "tools[$index].type must be function"
        if (function.name.isBlank()) {
            violations += "tools[$index].function.name is blank"
        } else if (!function.name.matches(safeWireToolName)) {
            violations += "tools[$index].function.name is not wire-safe"
        } else if (!names.add(function.name)) {
            violations += "duplicate tool name ${function.name}"
        }
        if (function.parameters.type != "object") {
            violations += "tool ${function.name} parameters must be an object"
        }
        val unknownRequired =
            function.parameters.required.toSet() - function.parameters.properties.keys
        if (unknownRequired.isNotEmpty()) {
            violations += "tool ${function.name} requires undefined properties"
        }
        // An external (MCP) schema is sent verbatim and may use JSON Schema forms the typed model
        // cannot express, such as a map object with only additionalProperties; its nesting is the
        // server's contract, so only the typed schemas Agora builds itself are checked below the top.
        if (function.parameters.schema == null) {
            function.parameters.properties.forEach { (propertyName, property) ->
                violations += propertyViolations(function.name, propertyName, property)
            }
        }
    }
    return violations
}

/**
 * Checks one property and whatever it nests. Nesting is checked too, because a malformed inner
 * schema is rejected by the provider just like a malformed outer one.
 */
private fun propertyViolations(
    toolName: String,
    path: String,
    property: ToolProperty,
): List<String> {
    val violations = mutableListOf<String>()
    if (path.isBlank()) violations += "tool $toolName has a blank property name"
    if (property.type.isBlank()) violations += "tool $toolName property $path has no type"
    if (property.type == "array" && property.items == null) {
        violations += "tool $toolName array $path has no items schema"
    }
    if (property.type == "object" && property.properties.isNullOrEmpty()) {
        violations += "tool $toolName object $path has no property schema"
    }
    val declared = property.properties?.keys.orEmpty()
    val unknownRequired = property.required.orEmpty().toSet() - declared
    if (unknownRequired.isNotEmpty()) {
        violations += "tool $toolName object $path requires undefined properties"
    }
    property.items?.let { violations += propertyViolations(toolName, "$path[]", it) }
    property.properties?.forEach { (nestedName, nested) ->
        violations += propertyViolations(toolName, "$path.$nestedName", nested)
    }
    return violations
}
