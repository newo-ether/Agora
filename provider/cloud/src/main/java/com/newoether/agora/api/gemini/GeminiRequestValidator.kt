package com.newoether.agora.api.gemini

import com.newoether.agora.api.util.requireValidRequestFormat
import com.newoether.agora.api.util.safeWireToolCallId
import com.newoether.agora.api.util.safeWireToolName

internal fun coalesceGeminiContents(
    contents: List<ApiRequestContent>,
): List<ApiRequestContent> {
    val result = mutableListOf<ApiRequestContent>()
    for (content in contents) {
        val previous = result.lastOrNull()
        if (previous != null && previous.role == content.role && content.role != null) {
            result[result.lastIndex] = previous.copy(parts = previous.parts + content.parts)
        } else {
            result += content
        }
    }
    return result
}

internal fun ApiGenerateContentRequest.requireValidWireFormat(modelName: String) {
    val violations = mutableListOf<String>()
    if (modelName.isBlank()) violations += "model is blank"
    if (contents.isEmpty()) violations += "contents is empty"
    validateSystemInstruction(violations)
    validateTools(violations)
    validateGenerationConfig(violations)

    val seenCallIds = mutableSetOf<String>()
    var pendingCalls = linkedMapOf<String, String>()
    var previousRole: String? = null
    val requiresFunctionCallSignature =
        modelName.contains("gemini-3", ignoreCase = true) ||
            modelName.contains("gemini-3.5", ignoreCase = true)

    contents.forEachIndexed { contentIndex, content ->
        val location = "contents[$contentIndex]"
        if (content.role !in setOf("user", "model")) {
            violations += "$location has invalid role ${content.role}"
        }
        if (previousRole == content.role) violations += "$location repeats role ${content.role}"
        previousRole = content.role
        if (content.parts.isEmpty()) violations += "$location parts is empty"

        val leadingResponses = content.parts.takeWhile { it.functionResponse != null }
        if (content.parts.drop(leadingResponses.size).any { it.functionResponse != null }) {
            violations += "$location has functionResponse after another part type"
        }
        if (pendingCalls.isNotEmpty()) {
            if (content.role != "user") {
                violations += "$location does not immediately answer pending function calls"
            }
            val responses = leadingResponses.mapNotNull { it.functionResponse }
            val returnedIds = responses.mapNotNull { it.id }
            if (
                returnedIds.size != pendingCalls.size ||
                returnedIds.distinct().size != returnedIds.size ||
                returnedIds.toSet() != pendingCalls.keys
            ) {
                violations += "$location does not answer every pending function call exactly once"
            }
            responses.forEach { response ->
                val expectedName = response.id?.let(pendingCalls::get)
                if (expectedName != null && response.name != expectedName) {
                    violations += "$location response name does not match call ${response.id}"
                }
            }
            pendingCalls.clear()
        } else if (leadingResponses.isNotEmpty()) {
            violations += "$location has orphan functionResponse parts"
        }

        content.parts.forEachIndexed { partIndex, part ->
            val partLocation = "$location.parts[$partIndex]"
            val payloadCount = listOfNotNull(
                part.text,
                part.inlineData,
                part.thought,
                part.executableCode,
                part.codeExecutionResult,
                part.functionCall,
                part.functionResponse,
            ).size
            if (payloadCount != 1) violations += "$partLocation must contain exactly one payload"
            if (part.text != null && part.text.isBlank()) {
                violations += "$partLocation text is blank"
            }
            part.inlineData?.let {
                if (content.role != "user" || it.mimeType.isBlank() || it.data.isBlank()) {
                    violations += "$partLocation is not a valid user inlineData part"
                }
            }
            part.executableCode?.let { code ->
                if (content.role != "model" || code.language.isBlank() || code.code.isBlank()) {
                    violations += "$partLocation is not valid model executableCode"
                }
            }
            part.codeExecutionResult?.let { result ->
                if (content.role != "model" || result.outcome.isBlank()) {
                    violations += "$partLocation is not valid model codeExecutionResult"
                }
            }
            part.functionCall?.let { call ->
                if (content.role != "model") violations += "$partLocation functionCall is not model role"
                if (call.id?.matches(safeWireToolCallId) != true) {
                    violations += "$partLocation functionCall id is not wire-safe"
                }
                if (!call.name.matches(safeWireToolName)) {
                    violations += "$partLocation functionCall name is not wire-safe"
                }
                if (requiresFunctionCallSignature && part.thoughtSignature.isNullOrBlank()) {
                    violations += "$partLocation Gemini 3 functionCall has no thoughtSignature"
                }
                val id = call.id
                if (!id.isNullOrBlank()) {
                    if (!seenCallIds.add(id)) violations += "$partLocation reuses functionCall id $id"
                    if (pendingCalls.put(id, call.name) != null) {
                        violations += "$partLocation duplicates functionCall id $id"
                    }
                }
            }
            part.functionResponse?.let { response ->
                if (content.role != "user") violations += "$partLocation functionResponse is not user role"
                if (response.id?.matches(safeWireToolCallId) != true) {
                    violations += "$partLocation functionResponse id is not wire-safe"
                }
                if (!response.name.matches(safeWireToolName)) {
                    violations += "$partLocation functionResponse name is not wire-safe"
                }
            }
            if (part.thoughtSignature != null && part.functionCall == null) {
                violations += "$partLocation thoughtSignature is not attached to a functionCall"
            }
        }
    }

    if (pendingCalls.isNotEmpty()) violations += "function calls are missing responses"
    if (contents.firstOrNull()?.role != "user") violations += "history does not start with user"
    if (contents.lastOrNull()?.role != "user") violations += "history does not end with user input"
    requireValidRequestFormat("Gemini", violations)
}

private fun ApiGenerateContentRequest.validateSystemInstruction(
    violations: MutableList<String>,
) {
    systemInstruction?.let { instruction ->
        if (instruction.role != null) violations += "system_instruction must not carry a role"
        if (
            instruction.parts.isEmpty() ||
            instruction.parts.any {
                it.text.isNullOrBlank() ||
                    listOfNotNull(
                        it.inlineData,
                        it.functionCall,
                        it.functionResponse,
                        it.thought,
                        it.thoughtSignature,
                        it.executableCode,
                        it.codeExecutionResult,
                    ).isNotEmpty()
            }
        ) {
            violations += "system_instruction must contain only nonblank text"
        }
    }
}

private fun ApiGenerateContentRequest.validateTools(violations: MutableList<String>) {
    val functionNames = mutableSetOf<String>()
    tools.orEmpty().forEachIndexed { toolIndex, tool ->
        val payloadCount = listOfNotNull(
            tool.codeExecution,
            tool.googleSearch,
            tool.functionDeclarations,
        ).size
        if (payloadCount != 1) violations += "tools[$toolIndex] must contain exactly one tool type"
        tool.functionDeclarations.orEmpty().forEachIndexed { functionIndex, declaration ->
            if (!declaration.name.matches(safeWireToolName)) {
                violations += "tools[$toolIndex].functions[$functionIndex].name is not wire-safe"
            }
            if (!functionNames.add(declaration.name)) {
                violations += "duplicate function declaration ${declaration.name}"
            }
            if (declaration.parameters != null && declaration.parametersJsonSchema != null) {
                violations += "function ${declaration.name} declares both parameter schema forms"
            }
            val schema = declaration.parameters ?: declaration.parametersJsonSchema
            if (schema?.get("type")?.toString()?.trim('"') != "object") {
                violations += "function ${declaration.name} parameters are not an object schema"
            }
        }
    }
}

private fun ApiGenerateContentRequest.validateGenerationConfig(
    violations: MutableList<String>,
) {
    generationConfig?.let { config ->
        if (config.maxOutputTokens != null && config.maxOutputTokens <= 0) {
            violations += "maxOutputTokens must be positive"
        }
        if (config.topP != null && config.topP !in 0f..1f) {
            violations += "topP is outside 0..1"
        }
        config.thinkingConfig?.let { thinking ->
            if (thinking.thinkingBudget != null && thinking.thinkingBudget < 0) {
                violations += "thinkingBudget is negative"
            }
            if (thinking.thinkingLevel != null && thinking.thinkingLevel.isBlank()) {
                violations += "thinkingLevel is blank"
            }
        }
    }
}
