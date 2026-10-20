package com.newoether.agora.tool

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.api.*
import com.newoether.agora.api.util.tokens.*
import com.newoether.agora.api.util.tokens.bpe.O200kBase
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Reproducible offline audit of the owner's enabled initial tool surface; no provider request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class InitialToolContextAuditTest {
    @Test fun initialSandboxSurfaceHasAnExactOfflineBreakdown() = runTest {
        O200kBase.ensureLoaded(ApplicationProvider.getApplicationContext())
        assertNotNull(O200kBase.loadedOrNull())
        val costs = ContextCostModels.forModel("DeepSeek:deepseek-flash")
        val counter = costs.text
        val context = GenerationContext(shellEnabled = true, sandboxEnabled = true, sandboxSharedStorageEnabled = true)
        // Ordinary categories are enabled in the exported device settings. Deferred bodies never
        // enter initial discovery, so only representative protocol names are needed for this audit.
        val deferredNames = listOf("render_interactive_answer",
            "read_memory_file", "invoke_skill", "read_conversation", "web_search", "ask_user")
        val available = deferredNames.map { ToolDefinition(function = ToolFunction(it, "", ToolParameters(properties = emptyMap()))) } + ShellToolDefinitions.build(context)
        val tools = ToolCapabilityLoading.definitions(available, context, emptyList())
        fun propertyParts(p: ToolProperty): Long = counter.count(p.type) + counter.count(p.description) +
            (p.items?.let(::propertyParts) ?: 0) + p.properties.orEmpty().entries.sumOf { counter.count(it.key) + propertyParts(it.value) } +
            p.required.orEmpty().sumOf(counter::count)
        var total = 0L
        val rows = tools.map { tool ->
            val f = tool.function
            val parts = linkedMapOf("wrapper" to costs.envelope.perToolDefinition.toLong(),
                "tool_type" to counter.count(tool.type), "name" to counter.count(f.name),
                "description" to counter.count(f.description), "parameters_type" to counter.count(f.parameters.type),
                "properties" to f.parameters.properties.entries.sumOf { counter.count(it.key) + propertyParts(it.value) },
                "required_names" to f.parameters.required.sumOf(counter::count))
            val raw = parts.values.sum(); total += raw
            buildJsonObject { put("name", f.name); put("raw_tokens", raw); put("parts", Json.encodeToJsonElement(parts)) }
        }
        val calculated = CostModelContextEstimator(costs).estimateFixedComposition(null, tools).toolTokens
        assertEquals(costs.safetyMargin.apply(total), calculated)
        assertFalse(tools.any { it.function.name == "invoke_skill" })
        val report = buildJsonObject {
            put("model", "DeepSeek:deepseek-flash"); put("counter", "offline o200k_base proxy")
            put("tools", JsonArray(rows)); put("raw_tokens", total); put("safety_margin_tokens", calculated-total)
            put("estimated_tool_tokens", calculated); put("definitions", Json.encodeToJsonElement(tools))
        }
        val project = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        File(project, "app/build/reports/tool-context").apply { mkdirs() }.resolve("initial-tools.json")
            .writeText(Json { prettyPrint = true }.encodeToString(report))
    }
}
