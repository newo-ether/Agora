package com.newoether.agora.templates

import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.util.ShellFileReadResult
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TemplateSourcesTest {
    private val sandbox = mockk<SandboxManager>()
    private val ctx = GenerationContext(sandboxEnabled = true, sandboxSharedStorageEnabled = true)
    private val template = InteractiveTemplate(name = "Setup", sourceDirectory = "/mnt/shared/bmj",
        answer = InteractiveAnswer("Setup", "Choose a clinical reference", "Choose", html = "<p>Choose</p>"))
    @Test fun preparationListsMetadataOnlyAndStartReadsExactlyTheSelectedCanonicalFile() = runTest {
        coEvery { sandbox.isAvailable() } returns true
        coEvery { sandbox.fileGlob("*.md", "/mnt/shared/bmj", 5) } returns (listOf(
            "/mnt/shared/bmj/Cardiology/Heart failure.md", "/mnt/shared/bmj/Surgery/Appendicitis.md") to false)
        val sources = TemplateSources(sandbox, ctx)
        val opened = sources.prepare(template)
        assertEquals(2, opened.resourceFiles.size)
        assertTrue(opened.answer.jsExpressions.first().contains("Cardiology/Heart failure.md"))
        coVerify(exactly = 0) { sandbox.fileRead(any(), any(), any()) }
        coEvery { sandbox.executeCommand(any(), any(), any()) } returns SandboxManager.SandboxResult("/mnt/shared/bmj/Cardiology/Heart failure.md", "", 0)
        coEvery { sandbox.fileRead("/mnt/shared/bmj/Cardiology/Heart failure.md", 0, 128000) } returns
            ShellFileReadResult("Selected article", 1, 1, 16, 16, 0, 128000, false)
        assertEquals("Selected article", sources.read(opened, "Cardiology/Heart failure.md"))
        coVerify(exactly = 1) { sandbox.fileRead("/mnt/shared/bmj/Cardiology/Heart failure.md", 0, 128000) }
        coVerify(exactly = 0) { sandbox.fileRead(match { it.contains("Appendicitis") }, any(), any()) }
        try { sources.read(opened, "../secret.md"); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun disabledMountMissingMovedAndOversizedSourcesFailWithoutAnAiFallback() = runTest {
        val sources = TemplateSources(sandbox, ctx)
        coEvery { sandbox.isAvailable() } returns true
        try { TemplateSources(sandbox, ctx.copy(sandboxSharedStorageEnabled = false)).prepare(template); fail() }
        catch (_: IllegalArgumentException) { }
        coEvery { sandbox.fileGlob(any(), any(), any()) } returns (emptyList<String>() to false)
        try { sources.prepare(template); fail() } catch (_: IllegalArgumentException) { }
        coEvery { sandbox.executeCommand(any(), any(), any()) } returns SandboxManager.SandboxResult("", "missing", 1)
        try { sources.read(template.copy(resourceFiles = listOf("case.md")), "case.md"); fail() } catch (_: IllegalArgumentException) { }
        coVerify(exactly = 0) { sandbox.fileRead(any(), any(), any()) }
        coEvery { sandbox.executeCommand(any(), any(), any()) } returns SandboxManager.SandboxResult("/mnt/shared/bmj/case.md", "", 0)
        coEvery { sandbox.fileRead(any(), any(), any()) } returns ShellFileReadResult("partial", 1, 2, 200000, 128000, 0, 128000, true)
        try { sources.read(template.copy(resourceFiles = listOf("case.md")), "case.md"); fail() } catch (_: IllegalArgumentException) { }
        listOf("/etc", "/mnt/shared/../private", "/home/agora/./x").forEach { root ->
            assertThrows(IllegalArgumentException::class.java) { validateTemplateDirectory(root) }
        }
    }
}
