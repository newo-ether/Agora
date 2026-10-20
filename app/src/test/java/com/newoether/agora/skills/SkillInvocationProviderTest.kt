package com.newoether.agora.skills

import android.content.Context
import com.newoether.agora.data.SkillManager
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.tool.SkillInvocationToolProvider
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillInvocationProviderTest {
    @get:Rule val folder = TemporaryFolder()
    private fun manager(): SkillManager {
        val ctx = mockk<Context>()
        every { ctx.filesDir } returns folder.root
        return SkillManager(ctx)
    }
    private fun args(id: String, parent: String = "") = buildJsonObject {
        put("skill", id); put("input", "test"); if (parent.isNotEmpty()) put("parent", parent)
    }.toString()
    @Test fun nestedCallUsesPinnedRevisionButFreshRootUsesSavedEdits() = runBlocking {
        val manager = manager()
        val child = SkillDocument("child.md", "Old child", SkillConfiguration())
        val root = SkillDocument("parent.md", "Call the child", SkillConfiguration())
        manager.saveDocument(child, ""); manager.saveDocument(root, "")
        val provider = SkillInvocationToolProvider(manager)
        val history = mutableListOf<ToolCallData>()
        suspend fun invoke(doc: SkillDocument, token: String, parent: String = "", run: String = "run"): String {
            val result = provider.invoke(args(doc.name, parent), GenerationContext(), token, history, run)
            history += ToolCallData("invoke_skill", "{}", result.text, toolCallId = token, structuredResult = result.structuredContent)
            return result.text
        }
        invoke(root, "root")
        assertTrue(invoke(child, "child", "root").contains("Old child"))
        val raw = manager.readFile(child.name)
        manager.saveDocument(child.copy(instructions = "New child"), "", child.name, raw)
        assertTrue(invoke(child, "child-again", "root").contains("Old child"))
        assertTrue(invoke(child, "new-root", run = "next-run").contains("New child"))
    }
    @Test fun disabledAccessCannotInvoke() = runBlocking {
        val manager = manager()
        val doc = SkillDocument("a.md", "Instructions", SkillConfiguration())
        manager.saveDocument(doc, "")
        val provider = SkillInvocationToolProvider(manager)
        assertTrue(provider.definitions(GenerationContext(skillReadAccess = false)).isEmpty())
        try { provider.invoke(args(doc.name), GenerationContext(skillReadAccess = false), "call", emptyList(), "run"); fail() }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message.orEmpty().contains("disabled")) }
    }
}
