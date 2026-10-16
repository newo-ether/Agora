package com.newoether.agora.api.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the Responses-API input ordering fix: a `function_call` must be immediately followed by
 * its `function_call_output`, with interleaved `message` items (as MiniMax M3 emits) deferred past
 * the pending call window so the request does not fail with "input[N] interrupts pending tool
 * results".
 */
class ResponsesInputOrderingTest {

    private fun msg(text: String) = buildJsonObject {
        put("type", "message"); put("role", "assistant"); put("text", text)
    }
    private fun call(id: String) = buildJsonObject {
        put("type", "function_call"); put("call_id", id); put("name", "introspect")
    }
    private fun output(id: String) = buildJsonObject {
        put("type", "function_call_output"); put("call_id", id); put("output", "result")
    }
    private fun typesOf(items: List<JsonObject>) =
        items.map { (it["type"] as JsonPrimitive).content }
    private fun pendingOk(items: List<JsonObject>): Boolean {
        val pending = linkedSetOf<String>()
        for (it in items) {
            when ((it["type"] as JsonPrimitive).content) {
                "function_call" -> pending.add((it["call_id"] as JsonPrimitive).content)
                "function_call_output" -> pending.remove((it["call_id"] as JsonPrimitive).content)
                else -> if (pending.isNotEmpty()) return false // a message interrupted a pending call
            }
        }
        return true
    }
    private fun endsOk(items: List<JsonObject>): Boolean {
        val last = items.lastOrNull() ?: return false
        val t = (last["type"] as JsonPrimitive).content
        val role = (last["role"] as? JsonPrimitive)?.content
        return t == "function_call_output" || (t == "message" && role == "user")
    }
    private fun userMsg(text: String) = buildJsonObject {
        put("type", "message"); put("role", "user"); put("text", text)
    }

    @Test
    fun wellFormedSequenceEndingInUserPassesThrough() {
        val input = listOf(userMsg("hi"), call("a"), output("a"), userMsg("thanks"))
        val out = sanitizeResponsesInputOrdering(input)
        assertEquals(input, out)
        assertEquals(true, pendingOk(out))
        assertEquals(true, endsOk(out))
    }

    @Test
    fun messageInterleavedBetweenCallAndOutputIsMovedOut() {
        // call, assistant message, then output: the message must not sit in the pending window,
        // and since it would be trailing (replayed output) it is dropped -> ends on the output.
        val input = listOf(call("a"), msg("thinking out loud"), output("a"))
        val out = sanitizeResponsesInputOrdering(input)
        assertEquals(listOf("function_call", "function_call_output"), typesOf(out))
        assertEquals(true, pendingOk(out))
        assertEquals(true, endsOk(out))
    }

    @Test
    fun minimaxShape_trailingAssistantContentAfterOutputIsDropped() {
        // MiniMax M3: ... output(a), then a dangling assistant answer/thought with no user turn.
        val input = listOf(userMsg("q"), call("a"), msg("mid-thought"), output("a"), msg("answer+thought"))
        val out = sanitizeResponsesInputOrdering(input)
        assertEquals(true, pendingOk(out))
        assertEquals(true, endsOk(out)) // ends on function_call_output, not the assistant message
        assertEquals("function_call_output", typesOf(out).last())
    }

    @Test
    fun interTurnAssistantContentIsPreservedWhenAnotherCallFollows() {
        // Assistant content between two tool rounds is legit context (a call follows it).
        val input = listOf(
            userMsg("start"),
            call("a"), msg("answer A"), output("a"),
            msg("let me also check"),
            call("b"), output("b"),
        )
        val out = sanitizeResponsesInputOrdering(input)
        assertEquals(true, pendingOk(out))
        assertEquals(true, endsOk(out))
        // The inter-turn message survives (flushed before call b).
        assertEquals(true, out.any { typesOf(listOf(it)).first() == "message" &&
            (it["text"] as? JsonPrimitive)?.content == "let me also check" })
    }

    @Test
    fun parallelCallsAllAnsweredBeforeTerminalOutput() {
        val input = listOf(userMsg("q"), call("a"), call("b"), msg("mid"), output("a"), output("b"))
        val out = sanitizeResponsesInputOrdering(input)
        assertEquals(true, pendingOk(out))
        assertEquals(true, endsOk(out))
        assertEquals(
            listOf("message", "function_call", "function_call", "function_call_output", "function_call_output"),
            typesOf(out),
        )
    }
}
