package com.newoether.agora.api.openai

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.providerHttpError

/** HTTP failure of an OpenAI-format request, with a /v1 hint after a 404 on every candidate. */
internal fun buildGenerationError(
    statusCode: Int,
    errorRaw: String,
    endpointUrls: List<String>,
): GenerationError {
    val endpointHint = if (statusCode == 404 && endpointUrls.size > 1) {
        "\nTried ${endpointUrls.joinToString(" and ")}. OpenAI-compatible servers often require a /v1 Base URL."
    } else {
        ""
    }
    val error = providerHttpError(statusCode, errorRaw)
    return if (endpointHint.isEmpty()) error else error.copy(message = error.message + endpointHint)
}
