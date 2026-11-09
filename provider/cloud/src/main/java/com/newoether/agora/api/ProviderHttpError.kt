package com.newoether.agora.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class ParsedProviderHttpErrorBody(
    val code: String?,
    val type: String?,
    val message: String,
    val structured: Boolean,
)

private val providerHttpErrorJson = Json {
    ignoreUnknownKeys = true
}

internal fun parseProviderHttpErrorBody(rawBody: String): ParsedProviderHttpErrorBody? {
    val trimmed = rawBody.trim()
    if (trimmed.isEmpty()) return null

    val root = runCatching {
        providerHttpErrorJson.parseToJsonElement(trimmed)
    }.getOrNull() ?: return ParsedProviderHttpErrorBody(
        code = null,
        type = null,
        message = trimmed,
        structured = false,
    )

    return when (root) {
        is JsonObject -> parseProviderHttpErrorObject(root)
            ?: ParsedProviderHttpErrorBody(
                code = null,
                type = null,
                message = trimmed,
                structured = false,
            )
        is JsonPrimitive -> root.nonBlankPrimitive()?.let { message ->
            ParsedProviderHttpErrorBody(
                code = null,
                type = null,
                message = message,
                structured = true,
            )
        } ?: ParsedProviderHttpErrorBody(
            code = null,
            type = null,
            message = trimmed,
            structured = false,
        )
        else -> ParsedProviderHttpErrorBody(
            code = null,
            type = null,
            message = trimmed,
            structured = false,
        )
    }
}

internal fun providerHttpError(
    statusCode: Int,
    rawBody: String?,
): GenerationError.Api {
    val parsed = rawBody
        ?.let(::parseProviderHttpErrorBody)
        ?: return GenerationError.Api(
            code = null,
            type = null,
            message = "HTTP $statusCode",
        )

    return GenerationError.Api(
        code = parsed.code ?: statusCode.toString(),
        type = parsed.type,
        message = parsed.message,
    )
}

fun extractStructuredProviderHttpErrorMessage(rawBody: String): String? =
    parseProviderHttpErrorBody(rawBody)
        ?.takeIf(ParsedProviderHttpErrorBody::structured)
        ?.message

private fun parseProviderHttpErrorObject(
    root: JsonObject,
): ParsedProviderHttpErrorBody? {
    val nestedError = root["error"] as? JsonObject
    val message = nestedError?.firstErrorMessage()
        ?: root["error"].nonBlankPrimitive()
        ?: root.firstErrorMessage()
        ?: return null
    val code = nestedError?.primitive("code")
        ?: root.primitive("code")
    val type = nestedError?.primitive("type")
        ?: nestedError?.primitive("status")
        ?: root.primitive("type")
        ?: root.primitive("status")

    return ParsedProviderHttpErrorBody(
        code = code,
        type = type,
        message = message,
        structured = true,
    )
}

private fun JsonObject.firstErrorMessage(): String? =
    primitive("message")
        ?: primitive("detail")
        ?: primitive("reason")
        ?: primitive("error_description")

private fun JsonObject.primitive(key: String): String? =
    this[key].nonBlankPrimitive()

private fun JsonElement?.nonBlankPrimitive(): String? =
    (this as? JsonPrimitive)
        ?.takeUnless { it === JsonNull }
        ?.content
        ?.trim()
        ?.takeIf(String::isNotEmpty)
