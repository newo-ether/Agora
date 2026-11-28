package com.newoether.agora.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsPreferenceSchemaTest {
    @Test
    fun durableKeyNamesRemainCompatible() {
        assertEquals("selected_model", SELECTED_MODEL.name)
        assertEquals("api_keys_json", API_KEYS_JSON.name)
        assertEquals("context_token_budget", CONTEXT_TOKEN_BUDGET.name)
        assertEquals("max_context_window", MAX_CONTEXT_WINDOW.name)
        assertEquals("context_compact_retain_count", CONTEXT_COMPACT_RETAIN_COUNT.name)
        assertEquals("context_compact_threshold_percent", CONTEXT_COMPACT_THRESHOLD_PERCENT.name)
        assertEquals("openai_responses_api_enabled", OPENAI_RESPONSES_API_ENABLED.name)
        assertEquals("anthropic_cache_enabled", ANTHROPIC_CACHE_ENABLED.name)
        assertEquals("anthropic_cache_ttl", ANTHROPIC_CACHE_TTL.name)
        assertEquals("mcp_servers_json", MCP_SERVERS_JSON.name)
        assertEquals("todo_enabled", TODO_ENABLED.name)
        assertEquals("subagent_tools_enabled", SUBAGENT_TOOLS_ENABLED.name)
        assertEquals("subagent_max_total", SUBAGENT_MAX_TOTAL.name)
        assertEquals("subagent_max_concurrent", SUBAGENT_MAX_CONCURRENT.name)
        assertEquals("subagent_enabled_models", SUBAGENT_ENABLED_MODELS.name)
        assertEquals("subagent_recommended_model", SUBAGENT_RECOMMENDED_MODEL.name)
        assertEquals("stick_to_bottom", STICK_TO_BOTTOM.name)
        assertEquals(
            "local_model_idle_retention_minutes",
            LOCAL_MODEL_IDLE_RETENTION_MINUTES.name,
        )
        assertEquals("last_models_fetch_fingerprint", LAST_MODELS_FETCH_FINGERPRINT.name)
    }

    @Test
    fun appearanceDefaultsUseForestTonalSpotWithoutDynamicColor() {
        assertEquals("amoled_enabled", AMOLED_ENABLED.name)
        assertEquals("FOREST", DEFAULT_COLOR_SCHEME)
        assertEquals("TONAL_SPOT", DEFAULT_SCHEME_STYLE)
        assertFalse(DEFAULT_DYNAMIC_COLOR)
    }

    @Test
    fun localModelIdleRetentionUsesClosedPresetsAndFiveMinuteDefault() {
        assertEquals(5, DEFAULT_LOCAL_MODEL_IDLE_RETENTION_MINUTES)
        assertArrayEquals(
            intArrayOf(0, 1, 2, 5, 10, 15, 30),
            LOCAL_MODEL_IDLE_RETENTION_PRESETS,
        )
        LOCAL_MODEL_IDLE_RETENTION_PRESETS.forEach {
            assertEquals(it, normalizeLocalModelIdleRetentionMinutes(it))
        }
        listOf(null, -1, 3, 31).forEach {
            assertEquals(5, normalizeLocalModelIdleRetentionMinutes(it))
        }
    }

    @Test
    fun localInferenceTuningUsesClosedPresetsAndStableDefaults() {
        assertEquals("local_inference_threads", LOCAL_INFERENCE_THREADS.name)
        assertEquals("local_kv_cache_type", LOCAL_KV_CACHE_TYPE.name)
        assertEquals(4, DEFAULT_LOCAL_INFERENCE_THREADS)
        assertArrayEquals(intArrayOf(1, 2, 4, 6, 8), LOCAL_INFERENCE_THREAD_PRESETS)
        LOCAL_INFERENCE_THREAD_PRESETS.forEach {
            assertEquals(it, normalizeLocalInferenceThreads(it))
        }
        listOf(null, -1, 3, 16).forEach {
            assertEquals(4, normalizeLocalInferenceThreads(it))
        }
        assertEquals("q8_0", DEFAULT_LOCAL_KV_CACHE_TYPE)
        LOCAL_KV_CACHE_TYPES.forEach {
            assertEquals(it, normalizeLocalKvCacheType(it))
        }
        listOf(null, "", "q5_0").forEach {
            assertEquals("q8_0", normalizeLocalKvCacheType(it))
        }
    }

    @Test
    fun publicProxyDefaultsRemainCompatible() {
        assertEquals("127.0.0.1", SettingsManager.DEFAULT_PROXY_HOST)
        assertEquals("7890", SettingsManager.DEFAULT_PROXY_PORT)
        assertEquals(
            "localhost\n127.0.0.1\n10.0.0.0/8\n172.16.0.0/12\n192.168.0.0/16\n::1",
            SettingsManager.DEFAULT_PROXY_BYPASS,
        )
    }
}
