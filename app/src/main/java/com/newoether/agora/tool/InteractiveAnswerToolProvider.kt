package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.viewmodel.GenerationContext

/** Validates completed content; execution starts only for completed artifacts in a visible renderer. */
class InteractiveAnswerToolProvider : ToolProvider {
    companion object {
        // Stay below the generic 100,000-character result clipping and leave aggregate metadata room.
        const val MAX_PERSISTED_BYTES = 80000
    }
    override fun handles(name: String): Boolean = name == InteractiveAnswer.TOOL_NAME

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.interactiveAnswersEnabled) return emptyList()
        return listOf(ToolDefinition(function = ToolFunction(
            name = InteractiveAnswer.TOOL_NAME,
            description = "Create an interactive answer when a chart, diagram, calculator or simulation helps the user. " +
                "Direct factual answers should remain text. Based on Open Intelligent UI: supply CSS separately " +
                "from HTML, declare functions in jsFunctions, then initialize with jsExpressions in order. " +
                "Label assumptions, units and data sources honestly. Local controls run inside an isolated iframe. " +
                "For follow-ups use await Websandbox.connection.remote.sendPrompt({text}) inside a user click handler; " +
                "include the selected values. HTTPS links use Websandbox.connection.remote.openLink({url}). " +
                "ES module imports are available for three, gsap, d3 and chart.js; import them inside async functions, " +
                "never top-level await. Library origins: esm.sh, cdnjs.cloudflare.com, cdn.jsdelivr.net, unpkg.com. " +
                "External images: basemap.nationalmap.gov, upload.wikimedia.org, thumb.wikimedia.org. " +
                "Use the included theme CSS variables and SVG helper classes. Do not access storage or Android APIs. " +
                "Native sliders and role=slider controls retain drag gestures. Mark custom drag controls with " +
                "data-agora-drag-control, only on the control itself; leave ordinary content scrollable. " +
                "Every answer needs a useful readable fallback. Keep the entire payload below 80000 UTF-8 bytes " +
                "to fit Agora tool persistence. The viewer opens only after this complete tool call." +
                if (ctx.eInkMode) INTERACTIVE_ANSWER_EINK_GUIDANCE else "",
            parameters = ToolParameters(properties = linkedMapOf(
                "title" to ToolProperty("string", "Short title (up to 200 characters)."),
                "description" to ToolProperty("string", "Five to ten words; at most ten words."),
                "fallback" to ToolProperty("string", "Readable answer, including results, assumptions and sources; no executable code."),
                "css" to ToolProperty("string", "CSS rules without style tags."),
                "html" to ToolProperty("string", "Body HTML or SVG without style/script tags."),
                "jsFunctions" to ToolProperty("string", "Classic JavaScript function declarations; async imports belong inside functions."),
                "jsExpressions" to ToolProperty("array", "Initialization expressions, executed sequentially after functions.",
                    items = ToolProperty("string", "One JavaScript expression.")),
            ), required = listOf("title", "description", "fallback", "html")),
        )))
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
        executeOneShotResult(name, arguments, ctx).text

    override suspend fun executeOneShotResult(name: String, arguments: String, ctx: GenerationContext): ToolExecutionResult {
        require(handles(name) && ctx.interactiveAnswersEnabled) { "Interactive answers are disabled" }
        val answer = InteractiveAnswer.decode(arguments).copy(eInkAware = ctx.eInkMode).validate()
        require(answer.description.trim().split(Regex("\\s+")).filter(String::isNotEmpty).size <= 10) { "Keep the description to 10 words or fewer" }
        require(answer.skillDraft == null) { "Use propose_skill for Skill drafts" }
        require(arguments.toByteArray(Charsets.UTF_8).size <= MAX_PERSISTED_BYTES &&
            answer.encode().toByteArray(Charsets.UTF_8).size <= MAX_PERSISTED_BYTES) {
            "Interactive answer exceeds Agora's 80000-byte tool persistence budget; create a smaller interface"
        }
        return ToolExecutionResult(
            text = "Created interactive answer: ${answer.title}\n${answer.fallback}",
            displayText = answer.fallback,
            structuredContent = answer.encode(),
        )
    }
}
