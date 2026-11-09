package com.newoether.agora.api.gemini

import com.newoether.agora.model.TokenUsage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class ApiGenerateContentRequest(
    val contents: List<ApiRequestContent>,
    val systemInstruction: ApiRequestContent? = null,
    val tools: List<ApiTool>? = null,
    @SerialName("toolConfig") val toolConfig: ApiToolConfig? = null,
    @SerialName("generationConfig") val generationConfig: ApiGenerationConfig? = null
)

@Serializable
internal data class ApiToolConfig(
    @SerialName("includeServerSideToolInvocations") val includeServerSideToolInvocations: Boolean = false
)

@Serializable
internal data class ApiGenerationConfig(
    @SerialName("thinkingConfig") val thinkingConfig: ApiThinkingConfig? = null,
    val temperature: Float? = null,
    @SerialName("maxOutputTokens") val maxOutputTokens: Int? = null,
    @SerialName("topP") val topP: Float? = null,
    @SerialName("frequencyPenalty") val frequencyPenalty: Float? = null,
    @SerialName("presencePenalty") val presencePenalty: Float? = null
)

@Serializable
internal data class ApiThinkingConfig(
    @SerialName("includeThoughts") val includeThoughts: Boolean,
    @SerialName("thinkingLevel") val thinkingLevel: String? = null,
    @SerialName("thinkingBudget") val thinkingBudget: Int? = null
)

@Serializable
internal data class ApiTool(
    @SerialName("code_execution") val codeExecution: JsonObject? = null,
    @SerialName("google_search") val googleSearch: JsonObject? = null,
    @SerialName("function_declarations") val functionDeclarations: List<GeminiFunctionDeclaration>? = null
)

@Serializable
internal data class GeminiFunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: JsonObject? = null,
    /** JSON Schema parameters; mutually exclusive with [parameters]. */
    @SerialName("parameters_json_schema") val parametersJsonSchema: JsonObject? = null,
)

@Serializable
internal data class ApiRequestContent(val role: String? = null, val parts: List<ApiRequestPart>)

@Serializable
internal data class ApiInlineData(val mimeType: String, val data: String)

@Serializable
internal data class ApiRequestPart(
    val text: String? = null,
    val inlineData: ApiInlineData? = null,
    val thought: String? = null,
    @SerialName("thoughtSignature") val thoughtSignature: String? = null,
    val executableCode: ApiExecutableCode? = null,
    val codeExecutionResult: ApiCodeExecutionResult? = null,
    @SerialName("functionCall") val functionCall: GeminiFunctionCall? = null,
    @SerialName("functionResponse") val functionResponse: GeminiFunctionResponse? = null
)

@Serializable
internal data class GeminiFunctionResponse(
    val id: String? = null,
    val name: String,
    val response: JsonObject
)

@Serializable
internal data class ApiResponseContent(val role: String? = null, val parts: List<ApiResponsePart>)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class ApiResponsePart(
    val text: String? = null,
    val thought: JsonElement? = null,
    @SerialName("thoughtSignature") val thoughtSignature: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @JsonNames("executable_code") val executableCode: ApiExecutableCode? = null,
    @JsonNames("code_execution_result") val codeExecutionResult: ApiCodeExecutionResult? = null,
    @SerialName("functionCall") val functionCall: GeminiFunctionCall? = null
)

@Serializable
internal data class GeminiFunctionCall(
    val id: String? = null,
    val name: String,
    val args: JsonObject? = null,
    @SerialName("thought_signature") val thoughtSignature: String? = null
)

@Serializable
internal data class ApiExecutableCode(val language: String, val code: String)

@Serializable
internal data class ApiCodeExecutionResult(val outcome: String, val output: String)

@Serializable
internal data class ApiStreamResponse(
    val candidates: List<ApiCandidate>? = null,
    @SerialName("usageMetadata") val usageMetadata: ApiUsageMetadata? = null,
    val error: ApiError? = null,
    val outcome: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class ApiCandidate(
    val content: ApiResponseContent? = null,
    @SerialName("finishReason") val finishReason: String? = null,
    @JsonNames("grounding_metadata") val groundingMetadata: JsonObject? = null,
)

@Serializable
internal data class ApiUsageMetadata(
    val promptTokenCount: Int? = null,
    val cachedContentTokenCount: Int? = null,
    val candidatesTokenCount: Int? = null,
    val totalTokenCount: Int? = null,
    val thoughtsTokenCount: Int? = null
)

internal fun ApiUsageMetadata.toTokenUsage(): TokenUsage {
    val input = promptTokenCount?.coerceAtLeast(0)
    val cached = cachedContentTokenCount?.coerceAtLeast(0)
    val uncached = if (input != null && cached != null) {
        (input - cached).coerceAtLeast(0)
    } else {
        null
    }
    val visibleOutput = candidatesTokenCount?.coerceAtLeast(0)
    val reasoning = thoughtsTokenCount?.coerceAtLeast(0)
    val output = when {
        visibleOutput != null && reasoning != null ->
            TokenUsage.addCounts(visibleOutput, reasoning)
        visibleOutput != null -> visibleOutput
        reasoning != null -> reasoning
        else -> null
    }
    val derivedTotal = when {
        input != null && output != null -> TokenUsage.addCounts(input, output)
        input != null -> input
        else -> output ?: 0
    }
    return TokenUsage(
        totalTokenCount = (totalTokenCount ?: derivedTotal).coerceAtLeast(0),
        inputTokenCount = input,
        cachedInputTokenCount = cached,
        uncachedInputTokenCount = uncached,
        outputTokenCount = output,
        reasoningTokenCount = reasoning,
    )
}

@Serializable
internal data class ApiErrorResponse(val error: ApiError)

@Serializable
internal data class ApiError(val code: Int? = null, val message: String? = null, val status: String? = null)

@Serializable
internal data class ModelListResponse(val models: List<ModelInfo>)

@Serializable
internal data class ModelInfo(val name: String, val displayName: String, val supportedGenerationMethods: List<String>)
