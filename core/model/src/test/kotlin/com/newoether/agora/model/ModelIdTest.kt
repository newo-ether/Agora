package com.newoether.agora.model

import org.junit.Assert.*
import org.junit.Test

class ModelIdTest {

    @Test
    fun `parse prefixed ID extracts provider and model`() {
        val id = ModelId.parse("OpenAI:gpt-4")
        assertEquals("OpenAI", id.providerName)
        assertEquals("gpt-4", id.modelName)
        assertEquals("OpenAI:gpt-4", id.prefixed)
    }

    @Test
    fun `parse Google model with models prefix`() {
        val id = ModelId.parse("Google:models/gemini-2.5-flash")
        assertEquals("Google", id.providerName)
        assertEquals("models/gemini-2.5-flash", id.modelName)
    }

    @Test
    fun `parse local model`() {
        val id = ModelId.parse("Local:my-gguf-model")
        assertEquals("Local", id.providerName)
        assertEquals("my-gguf-model", id.modelName)
    }

    @Test
    fun `parse with multiple colons keeps first as separator`() {
        val id = ModelId.parse("Provider:model:with:colons")
        assertEquals("Provider", id.providerName)
        assertEquals("model:with:colons", id.modelName)
    }

    // ── Legacy / unprefixed heuristics ───────────────────────

    @Test
    fun `heuristic gpt prefix → OpenAI`() {
        assertEquals("OpenAI", ModelId.parse("gpt-4").providerName)
        assertEquals("OpenAI", ModelId.parse("gpt-4o-mini").providerName)
    }

    @Test
    fun `heuristic o1 prefix → OpenAI`() {
        assertEquals("OpenAI", ModelId.parse("o1").providerName)
        assertEquals("OpenAI", ModelId.parse("o3-mini").providerName)
    }

    @Test
    fun `heuristic claude prefix → Anthropic`() {
        assertEquals("Anthropic", ModelId.parse("claude-3-opus").providerName)
        assertEquals("Anthropic", ModelId.parse("claude-3.5-sonnet").providerName)
    }

    @Test
    fun `heuristic deepseek → DeepSeek`() {
        assertEquals("DeepSeek", ModelId.parse("deepseek-chat").providerName)
        assertEquals("DeepSeek", ModelId.parse("deepseek-reasoner").providerName)
    }

    @Test
    fun `heuristic qwen → Qwen`() {
        assertEquals("Qwen", ModelId.parse("qwen-max").providerName)
        assertEquals("Qwen", ModelId.parse("qwen-plus").providerName)
    }

    @Test
    fun `heuristic groq → Groq`() {
        assertEquals("Groq", ModelId.parse("llama-3.1-8b-instant-groq").providerName)
    }

    @Test
    fun `heuristic gemini prefix → Google`() {
        assertEquals("Google", ModelId.parse("gemini-1.5-flash").providerName)
    }

    @Test
    fun `heuristic models slash → Google`() {
        assertEquals("Google", ModelId.parse("models/gemini-2.5-flash").providerName)
    }

    @Test
    fun `unknown model returns Unknown provider`() {
        val id = ModelId.parse("completely-unknown-model-xyz")
        assertEquals("Unknown", id.providerName)
        assertEquals("completely-unknown-model-xyz", id.modelName)
    }

    // ── construction ─────────────────────────────────────────

    @Test
    fun `constructor builds correct prefixed string`() {
        val id = ModelId("Anthropic", "claude-3-opus")
        assertEquals("Anthropic:claude-3-opus", id.prefixed)
    }

    // ── apiModelName extension ───────────────────────────────

    @Test
    fun `apiModelName strips models prefix`() {
        val id = ModelId.parse("Google:models/gemini-2.5-flash")
        assertEquals("gemini-2.5-flash", id.apiModelName)
    }

    @Test
    fun `apiModelName no-op without models prefix`() {
        val id = ModelId.parse("OpenAI:gpt-4")
        assertEquals("gpt-4", id.apiModelName)
    }
}
