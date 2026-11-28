package com.newoether.agora.viewmodel

import com.newoether.agora.api.util.ToolSchemaJson
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.tool.ToolExecutionEvent
import com.newoether.agora.voice.live.LiveToolBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * Live Voice tool bridge over Agora's [GenerationToolExecutor]. Exposes the agent tool set
 * (including MCP via the executor's providers) to a Live API session as Google
 * `functionDeclarations`, and runs tool calls through the same authorized execution path used by
 * normal generation.
 *
 * The session is synthetic (no persisted Run), so it uses a fixed [RunEffectIdentity] and a plain
 * [GenerationContext] carrying the enabled-tool flags. Streaming progress events are ignored; only
 * the final result text is returned to the model.
 */
internal class LiveVoiceToolBridge(
    private val executor: GenerationToolExecutor,
    private val context: GenerationContext,
) : LiveToolBridge {

    private val identity = RunEffectIdentity(
        conversationId = context.conversationId ?: "live-voice",
        ownerToken = System.currentTimeMillis().coerceAtLeast(1L),
        runId = "live-voice-${System.nanoTime()}",
        pass = 0,
        effectId = "live-voice",
    )

    private val definitions by lazy { executor.definitions(context) }
    private val authorizedNames: Set<String> by lazy {
        definitions.mapTo(linkedSetOf()) { it.function.name }
    }

    override fun functionDeclarations(): JSONArray {
        val arr = JSONArray()
        for (def in definitions) {
            val fn = def.function
            val params = runCatching { ToolSchemaJson.of(fn.parameters).toString() }.getOrNull()
            arr.put(
                JSONObject().apply {
                    put("name", fn.name)
                    put("description", fn.description)
                    if (params != null) {
                        val obj = JSONObject(params)
                        // Gemini rejects an empty properties object in a function parameters schema.
                        if (obj.optJSONObject("properties")?.length() == 0) {
                            obj.remove("properties"); obj.remove("required")
                        }
                        put("parameters", obj)
                    }
                },
            )
        }
        return arr
    }

    override suspend fun execute(name: String, argumentsJson: String): String {
        if (name !in authorizedNames) return "Error: tool '$name' is not available."
        val call = AuthorizedToolCall(
            batchIdentity = identity,
            callId = "live-${System.nanoTime()}",
            name = name,
            arguments = argumentsJson.ifBlank { "{}" },
            context = context,
            authorizedToolNames = authorizedNames,
        )
        return try {
            val result = executor.execute(call) { /* ignore streaming progress events */ }
            result.result.text
        } catch (e: Exception) {
            "Error executing tool '$name': ${e.message}"
        }
    }
}
