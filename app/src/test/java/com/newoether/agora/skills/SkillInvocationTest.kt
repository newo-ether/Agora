package com.newoether.agora.skills

import com.newoether.agora.model.ToolCallData
import org.junit.Assert.*
import org.junit.Test

class SkillInvocationTest {
    private fun doc(name: String) = SkillDocument(name, "Instructions for $name")
    private fun invoke(d: SkillDocument, token: String, parent: String? = null, history: List<SkillInvocation> = emptyList(), run: String = "run") =
        SkillInvocation.create(d, d.encode(), "task", token, parent, history, run)

    @Test fun filenameChildSharesItsRootAndRetainsAnExactSnapshot() {
        val child = doc("child.md")
        val root = doc("root.md")
        val a = invoke(root, "a")
        val b = invoke(child, "b", "a", listOf(a))
        assertEquals(listOf(root.name, child.name), b.chain)
        assertEquals("a", b.rootToken)
        assertEquals(child, SkillDocument.decode(b.name, b.document))
        assertEquals(SkillDocument.revision(b.document), b.revision)
    }
    @Test fun cyclesAndUnknownParentsFailBeforeContinuation() {
        val root = doc("root.md")
        val a = invoke(root, "a")
        assertThrows(IllegalArgumentException::class.java) { invoke(root, "b", "a", listOf(a)) }
        assertEquals(listOf("root.md", "child.md"), invoke(doc("child.md"), "b", "a", listOf(a)).chain)
        assertThrows(IllegalStateException::class.java) { invoke(doc("child.md"), "b", "missing", listOf(a)) }
    }
    @Test fun repeatedSequentialChildCallsAreAllowed() {
        val child = doc("child.md")
        val root = doc("root.md")
        val a = invoke(root, "a")
        val b = invoke(child, "b", "a", listOf(a))
        assertEquals(b.chain, invoke(child, "c", "a", listOf(a, b)).chain)
    }
    @Test fun nestingAndPerTurnBudgetAreBoundedWithoutDisablingLaterTurns() {
        val chain = (0..6).map { doc("$it.md") }.toMutableList()
        val history = mutableListOf<SkillInvocation>()
        for (i in 0..5) history += invoke(chain[i], "$i", if (i == 0) null else "${i-1}", history)
        assertThrows(IllegalArgumentException::class.java) { invoke(chain[6], "6", "5", history) }
        val many = (0 until SkillInvocation.MAX_CALLS).map { invoke(doc("root-$it.md"), "token-$it") }
        assertThrows(IllegalArgumentException::class.java) { invoke(doc("extra.md"), "extra", history = many) }
        assertEquals("later", invoke(doc("later.md"), "later", history = many, run = "next-turn").token)
    }
    @Test fun foreignOrMalformedStructuredResultsCannotAuthorizeNestedCalls() {
        val d = doc("a.md")
        val frame = invoke(d, "actual")
        assertTrue(SkillInvocation.history(listOf(ToolCallData("other_tool", "{}", "", toolCallId = "actual", structuredResult = frame.encode()))).isEmpty())
        assertTrue(SkillInvocation.history(listOf(ToolCallData("invoke_skill", "{}", "", toolCallId = "foreign", structuredResult = frame.encode()))).isEmpty())
        assertEquals(listOf(frame), SkillInvocation.history(listOf(ToolCallData("invoke_skill", "{}", "", toolCallId = "actual", structuredResult = frame.encode()))))
    }
}
