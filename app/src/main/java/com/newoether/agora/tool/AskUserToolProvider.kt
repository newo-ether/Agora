package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.normalizeAskUserTimeoutMinutes
import com.newoether.agora.viewmodel.AskUserController
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Lets the model put a decision back to the user instead of guessing. The interaction bar renders
 * the question and the answer returns here.
 *
 * A blocking call suspends until the user answers or skips, so the answer is part of this tool
 * result and the model never has to guess what was chosen. The configured timeout covers the
 * whole question set and preserves submitted answers while leaving remaining items unanswered.
 *
 * A non-blocking call returns as soon as the question is on screen and the model keeps working. The
 * answer then arrives as a queued user message, so it needs a conversation to arrive in.
 *
 * One call can carry several questions. They all reach the bar at once and are answered in any
 * order, which is what makes a small set of related decisions one interruption instead of several.
 *
 * Options are optional: a question without them is answered by typing.
 */
class AskUserToolProvider(private val askUser: AskUserController) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.askUserEnabled) return emptyList()
        return listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = TOOL_NAME,
                    description = "Only call this when a decision genuinely needs the user. Ask the user a question. Use it when the decision is the " +
                        "user's to make and guessing would waste work. Supply options when the " +
                        "answer is a choice, or leave them out to ask an open question; the user " +
                        "can always type their own answer instead. Ask one question with " +
                        "`question`, or put a few related ones in `questions` so they arrive " +
                        "together; never ask the same thing twice in a row.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "question" to ToolProperty(
                                "string",
                                "The question, written in the user's language. Use this for a " +
                                    "single question, or use `questions` instead.",
                            ),
                            "options" to ToolProperty(
                                "array",
                                "Optional answers to choose from for `question`. Keep each one " +
                                    "short. Omit them for an open question.",
                                ToolProperty("string", "One selectable answer."),
                            ),
                            "allow_multiple" to ToolProperty(
                                "boolean",
                                "True lets the user pick several options for `question`. " +
                                    "Defaults to false.",
                            ),
                            "questions" to ToolProperty(
                                type = "array",
                                description = "Several questions to ask at once, each with its " +
                                    "own options. They appear together and can be answered in " +
                                    "any order. Use this instead of `question`, not with it, and " +
                                    "keep it to at most $MAX_QUESTIONS questions.",
                                items = ToolProperty(
                                    type = "object",
                                    description = "One question of the set.",
                                    properties = mapOf(
                                        "question" to ToolProperty(
                                            "string",
                                            "The question, written in the user's language.",
                                        ),
                                        "options" to ToolProperty(
                                            "array",
                                            "Optional answers to choose from. Keep each one " +
                                                "short.",
                                            ToolProperty("string", "One selectable answer."),
                                        ),
                                        "allow_multiple" to ToolProperty(
                                            "boolean",
                                            "True lets the user pick several of this question's " +
                                                "options. Defaults to false.",
                                        ),
                                    ),
                                    required = listOf("question"),
                                ),
                            ),
                            "blocking" to ToolProperty(
                                "boolean",
                                "True waits here until the user answers, so the answer is " +
                                    "part of this result. False returns immediately and you keep " +
                                    "working; the answer arrives later as a user message, so " +
                                    "only use it when you have work that does not depend on the " +
                                    "answer. Defaults to true.",
                            ),
                        ),
                        required = emptyList(),
                    ),
                ),
            ),
        )
    }

    override fun handles(name: String): Boolean = name == TOOL_NAME

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String {
        val args = runCatching { Json.parseToJsonElement(arguments.ifBlank { "{}" }) as JsonObject }
            .getOrNull() ?: return failure("bad_arguments", "Arguments are not a JSON object.")
        val blocking = args["blocking"]?.jsonPrimitive?.booleanOrNull ?: true
        if (!blocking && ctx.conversationId == null) {
            return failure(
                "no_conversation",
                "A non-blocking answer travels as a user message, which needs a conversation. " +
                    "Ask with blocking set to true here.",
            )
        }
        val questions = when (val parsed = parseQuestions(args)) {
            is ParsedQuestions.Invalid -> return failure(parsed.code, parsed.detail)
            is ParsedQuestions.Valid -> parsed.questions
        }
        // A set is one card with a page per question, so its questions share one set id.
        val setId = if (questions.size > 1) askUser.newSetId() else null
        val requests = questions.map { question ->
            askUser.open(
                conversationId = ctx.conversationId,
                question = question.question,
                options = question.options,
                allowMultiple = question.allowMultiple,
                blocking = blocking,
                setId = setId,
                runId = ctx.toolEffectIdentity?.runId,
            )
        }
        if (!blocking) return queuedResult(requests.size)
        return try {
            val timeoutMs = normalizeAskUserTimeoutMinutes(ctx.askUserTimeoutMinutes) * 60_000L
            val answers = askUser.awaitAnswers(requests, timeoutMs)
            if (answers.size == 1) singleResult(answers.first()) else groupResult(questions, answers)
        } finally {
            // Whatever is still waiting belongs to a wait that no longer exists, either because the
            // generation was stopped or because this call already returned.
            requests.forEach { askUser.abandon(it.id) }
        }
    }

    /** One question as the model asked it, before it reaches the bar. */
    private data class PendingQuestion(
        val question: String,
        val options: List<String>,
        val allowMultiple: Boolean,
    )

    private sealed interface ParsedQuestions {
        data class Valid(val questions: List<PendingQuestion>) : ParsedQuestions
        data class Invalid(val code: String, val detail: String) : ParsedQuestions
    }

    /**
     * Reads either form of the call. The set form and the single form describe the same thing, so
     * accepting both at once would leave it unclear what was asked; that is refused instead.
     */
    private fun parseQuestions(args: JsonObject): ParsedQuestions {
        val single = args["question"]?.stringOrNull()
        val set = (args["questions"] as? JsonArray).orEmpty()
        if (single != null && set.isNotEmpty()) {
            return ParsedQuestions.Invalid(
                "ambiguous_questions",
                "Use question for one question or questions for a set, not both.",
            )
        }
        if (single != null) {
            return ParsedQuestions.Valid(
                listOf(
                    PendingQuestion(
                        question = single,
                        options = optionsOf(args),
                        allowMultiple = allowMultipleOf(args),
                    ),
                ),
            )
        }
        if (set.isEmpty()) {
            return ParsedQuestions.Invalid("no_question", "A question is required.")
        }
        if (set.size > MAX_QUESTIONS) {
            return ParsedQuestions.Invalid(
                "too_many_questions",
                "Ask at most $MAX_QUESTIONS questions in one call.",
            )
        }
        val questions = set.map { entry ->
            val item = entry as? JsonObject
                ?: return ParsedQuestions.Invalid(
                    "bad_question",
                    "Each entry of questions must be an object with a question field.",
                )
            val text = item["question"]?.stringOrNull()
                ?: return ParsedQuestions.Invalid(
                    "no_question",
                    "Each entry of questions needs a question field.",
                )
            PendingQuestion(
                question = text,
                options = optionsOf(item),
                allowMultiple = allowMultipleOf(item),
            )
        }
        return ParsedQuestions.Valid(questions)
    }

    private fun optionsOf(source: JsonObject): List<String> =
        (source["options"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()

    private fun allowMultipleOf(source: JsonObject): Boolean =
        source["allow_multiple"]?.jsonPrimitive?.booleanOrNull ?: false

    private fun queuedResult(questionCount: Int): String = buildJsonObject {
        put("delivery", "queued")
        put("questions", questionCount)
        put(
            "detail",
            "The question is on screen. Keep working; the user's answer will arrive as a user " +
                "message. Do not guess it in the meantime.",
        )
    }.toString()

    /** A single question keeps the flat result shape it has always had. */
    private fun singleResult(answer: AskUserController.Answer): String = buildJsonObject {
        putAnswer(answer)
    }.toString()

    private fun groupResult(
        questions: List<PendingQuestion>,
        answers: List<AskUserController.Answer>,
    ): String = buildJsonObject {
        putJsonArray("answers") {
            questions.forEachIndexed { index, question ->
                add(
                    buildJsonObject {
                        put("question", question.question)
                        answers.getOrNull(index)
                            ?.let { answer -> putAnswer(answer) }
                    },
                )
            }
        }
    }.toString()

    private fun JsonObjectBuilder.putAnswer(answer: AskUserController.Answer) {
        put("answered", answer.answered)
        if (answer.answered) {
            put("choices", JsonArray(answer.choices.map(::JsonPrimitive)))
            answer.text?.let { put("text", it) }
        } else {
            put(
                "detail",
                "The user did not answer. Do not invent an answer; ask again or continue " +
                    "without it.",
            )
        }
    }

    private fun failure(code: String, detail: String): String = buildJsonObject {
        put("error", code)
        put("detail", detail)
    }.toString()

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private companion object {
        const val TOOL_NAME = "ask_user"

        /** Enough for a set of related decisions, few enough that the bar stays answerable. */
        const val MAX_QUESTIONS = 5
    }
}
