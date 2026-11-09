package com.newoether.agora.viewmodel

/** Defaults owned by the shared invocation contract. */
object GenerationDefaults {
    const val TOOL_EXECUTION_TIMEOUT_SECONDS = 300
    const val TOOL_EXECUTION_TIMEOUT_MS = TOOL_EXECUTION_TIMEOUT_SECONDS * 1_000L
    const val IMAGE_TRANSCRIPTION_USER =
        "Please describe this image in detail. Include all visible text, data, charts, layout, and visual elements. Preserve the original language of any text shown."
}
