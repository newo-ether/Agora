package com.newoether.agora.voice.live

import org.json.JSONArray

/**
 * Bridges the Live Voice session to Agora's tool system (agent tools + MCP). Implemented in the
 * app/viewmodel layer where the GenerationToolExecutor and GenerationContext live; the streamer
 * only sees this small surface.
 */
interface LiveToolBridge {
    /** Google `functionDeclarations` array for the tools to expose to the live model. */
    fun functionDeclarations(): JSONArray

    /** Execute a tool the model called; returns the result text to send back as a toolResponse. */
    suspend fun execute(name: String, argumentsJson: String): String
}
