package com.newoether.agora.tool

import com.newoether.agora.api.ToolFunction
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.viewmodel.GenerationContext
import org.junit.Assert.*
import org.junit.Test

class ShellToolDefinitionsTest {
    private val local = "Local Sandbox"
    private val conch = ShellDeviceConfig(name = "Workstation", serverUrl = "http://localhost")
    private val ssh = ShellDeviceConfig(name = "SshHost", type = "ssh", sshHost = "localhost")
    private val standardTools = setOf(
        "shell_job_list", "shell_job_get", "shell_job_wait", "shell_job_stop", "file_view_image",
    )
    private val ordinaryTools = setOf(
        "shell_exec", "file_read", "file_write", "file_edit", "file_multi_edit", "file_glob", "file_grep",
    )
    private val allTools = standardTools + ordinaryTools + setOf("shell_list", "file_transfer")

    private fun context(sandbox: Boolean, vararg devices: ShellDeviceConfig) =
        GenerationContext(shellEnabled = true, sandboxEnabled = sandbox, shellDevices = devices.toList())

    private fun definitions(ctx: GenerationContext): Map<String, ToolFunction> =
        ShellToolDefinitions.build(ctx).map { it.function }.associateBy { it.name }

    private fun assertServers(
        defs: Map<String, ToolFunction>,
        names: Set<String>,
        eligible: List<String>,
        excluded: List<String> = emptyList(),
    ) {
        for (name in names) {
            val parameters = defs.getValue(name).parameters
            val server = parameters.properties.getValue("server")
            assertEquals("$name server type", "string", server.type)
            assertEquals("$name server requirement", eligible.size > 1, "server" in parameters.required)
            assertEquals("$name omission guidance", eligible.size == 1, server.description.contains("optional"))
            for (target in eligible) assertTrue("$name is missing $target", server.description.contains(target))
            for (target in excluded) assertFalse("$name exposes $target", server.description.contains(target))
            assertTrue("$name has undeclared required fields", parameters.properties.keys.containsAll(parameters.required))
        }
    }

    @Test fun localOnlyExposesTheCompleteStandardToolSetWithOptionalServer() {
        val defs = definitions(context(true))
        assertEquals(allTools, defs.keys)
        assertServers(defs, standardTools + ordinaryTools, listOf(local))
        assertEquals(listOf("description", "command", "timeout_ms"), defs.getValue("shell_exec").parameters.required)
        assertEquals(listOf("description"), defs.getValue("shell_job_list").parameters.required)
        assertEquals(listOf("job_id", "description"), defs.getValue("shell_job_get").parameters.required)
        assertEquals(listOf("job_id", "timeout_ms", "description"), defs.getValue("shell_job_wait").parameters.required)
        assertEquals(listOf("job_id", "description"), defs.getValue("shell_job_stop").parameters.required)
        assertEquals(listOf("path"), defs.getValue("file_view_image").parameters.required)
    }

    @Test fun localAndConchRequireServerForEveryTargetedTool() {
        val defs = definitions(context(true, conch))
        assertEquals(allTools, defs.keys)
        assertServers(defs, standardTools + ordinaryTools, listOf(local, conch.name))
        assertEquals(listOf("description", "command", "server", "timeout_ms"), defs.getValue("shell_exec").parameters.required)
        assertEquals(listOf("job_id", "description", "server"), defs.getValue("shell_job_get").parameters.required)
        assertEquals(listOf("path", "server"), defs.getValue("file_view_image").parameters.required)
    }

    @Test fun sshAndLocalOnlyRequireServerForOrdinaryTools() {
        val defs = definitions(context(true, ssh))
        assertEquals(allTools, defs.keys)
        assertServers(defs, standardTools, listOf(local), listOf(ssh.name))
        assertServers(defs, ordinaryTools, listOf(local, ssh.name))
    }

    @Test fun conchOnlyKeepsOptionalServerWithoutExposingDisabledSandbox() {
        val defs = definitions(context(false, conch))
        assertEquals(allTools, defs.keys)
        assertServers(defs, standardTools + ordinaryTools, listOf(conch.name), listOf(local))
    }

    @Test fun sshDoesNotMakeAConchJobOrImageTargetAmbiguous() {
        val defs = definitions(context(false, ssh, conch))
        assertEquals(allTools, defs.keys)
        assertServers(defs, standardTools, listOf(conch.name), listOf(local, ssh.name))
        assertServers(defs, ordinaryTools, listOf(ssh.name, conch.name), listOf(local))
        val mixed = definitions(context(true, ssh, conch))
        assertServers(mixed, standardTools, listOf(local, conch.name), listOf(ssh.name))
    }

    @Test fun sshOnlyDoesNotExposeUnsupportedJobOrImageTools() {
        val defs = definitions(context(false, ssh))
        assertEquals(allTools - standardTools, defs.keys)
        assertServers(defs, ordinaryTools, listOf(ssh.name), listOf(local, conch.name))
    }

    @Test fun disabledShellAndNoEligibleServersExposeNoTools() {
        assertTrue(definitions(context(false)).isEmpty())
        assertTrue(definitions(context(true, conch).copy(shellEnabled = false)).isEmpty())
    }

    @Test fun executionDescriptionsPromiseTheSameLocalAndConchWaitAndRetentionSemantics() {
        val defs = definitions(context(true, conch))
        val command = defs.getValue("shell_exec")
        val timeout = command.parameters.properties.getValue("timeout_ms").description
        val background = command.parameters.properties.getValue("background").description
        for (text in listOf(command.description, timeout, background)) {
            assertTrue(text.contains(local))
            assertTrue(text.contains("Conch"))
            assertTrue(text.contains("job_id"))
        }
        assertTrue(timeout.contains("295000ms"))
        assertTrue(timeout.contains("same durable job"))
        assertTrue(timeout.contains("never killed or restarted"))
        assertTrue(background.contains("immediately"))
        for (text in listOf(command.description, background)) {
            assertTrue(text.contains("independently of"))
            assertTrue(text.contains("app process death"))
            assertTrue(text.contains("interrupted"))
            assertTrue(text.contains("never replayed"))
        }
        for (name in standardTools) {
            assertTrue("$name must describe local support", defs.getValue(name).description.contains(local))
            assertTrue("$name must describe Conch support", defs.getValue(name).description.contains("Conch"))
        }
        val wait = defs.getValue("shell_job_wait")
        assertTrue(wait.description.contains("leaves the job running"))
        assertTrue(wait.description.contains("shell_job_wait again"))
        assertTrue(wait.parameters.properties.keys.containsAll(listOf("head_lines", "tail_lines")))
        for (name in defs.keys.filter { it.startsWith("shell_") }) {
            assertTrue("$name requires description", "description" in defs.getValue(name).parameters.required)
        }
    }
}
