package com.newoether.agora.viewmodel

import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.RunEffectIdentity
import org.junit.Assert.*
import org.junit.Test

class QueuedGuidancePermissionsTest {
    private fun context(allowed: Boolean = true) = GenerationContext(
        conversationId = "child",
        subagentToolsEnabled = allowed,
        todoEnabled = allowed,
        accessSavedMemories = allowed,
        accessActiveMemory = allowed,
        skillReadAccess = allowed,
        skillModifyAccess = allowed,
        accessPastConversations = allowed,
        webSearchEnabled = allowed,
        imageGenEnabled = allowed,
        askUserEnabled = allowed,
        automationToolsEnabled = allowed,
        shellEnabled = allowed,
        sandboxEnabled = allowed,
        sandboxSharedStorageEnabled = allowed,
        imageTranscriptionEnabled = allowed,
    )

    private fun snapshot(
        model: String,
        context: GenerationContext = context(),
        compactContext: GenerationContext = context,
    ): GenerationAdmissionSnapshot {
        val snapshot = testGenerationAdmissionSnapshot("child", "run-$model", model)
        return snapshot.copy(
            context = context,
            automaticCompact = snapshot.automaticCompact.copy(generationContext = compactContext),
        )
    }

    private fun queued(id: String, snapshot: GenerationAdmissionSnapshot?, source: MessageSource? = null) = QueuedSend(
        id = id, text = "Message $id", modelId = snapshot?.selectedModelId ?: "UI:model",
        attachments = emptyList(), runId = "queued-$id", generationSnapshot = snapshot, source = source,
    )

    private fun permissions(context: GenerationContext) = listOf(
        context.subagentToolsEnabled, context.todoEnabled, context.accessSavedMemories, context.accessActiveMemory,
        context.skillReadAccess, context.skillModifyAccess, context.accessPastConversations, context.webSearchEnabled,
        context.imageGenEnabled, context.askUserEnabled, context.automationToolsEnabled, context.shellEnabled,
        context.sandboxEnabled, context.sandboxSharedStorageEnabled, context.imageTranscriptionEnabled,
    )

    @Test fun parentDeniedPermissionsSurviveLaterTypedSnapshotWithoutChangingSelectedConfiguration() {
        val parent = snapshot("Parent:old", context(false))
        val chosen = snapshot("UI:last", context().copy(
            enabledModelIds = listOf("UI:last"), skillCatalog = "chosen catalog",
            imageGenApiKey = "chosen image key", imageGenModel = "chosen image model",
            webSearchProvider = "kagi", webSearchApiKeys = mapOf("kagi" to "chosen search key"),
            webSearchBaseUrl = "chosen search endpoint", transcriptionApiKey = "chosen transcription key",
            toolTimeoutMs = 123L, foregroundServiceManagedExternally = true,
        ))
        val result = narrowQueuedGuidancePermissions(listOf(queued("parent", parent), queued("typed", chosen)), chosen)
        assertTrue(permissions(result.context).none { it })
        assertTrue(permissions(result.automaticCompact.generationContext).none { it })
        assertEquals(chosen.copy(
            context = chosen.context.copy(
                subagentToolsEnabled = false, todoEnabled = false, accessSavedMemories = false,
                accessActiveMemory = false, skillReadAccess = false, skillModifyAccess = false,
                accessPastConversations = false, webSearchEnabled = false, imageGenEnabled = false,
                askUserEnabled = false, automationToolsEnabled = false, shellEnabled = false,
                sandboxEnabled = false, sandboxSharedStorageEnabled = false, imageTranscriptionEnabled = false,
            ),
            automaticCompact = chosen.automaticCompact.copy(
                generationContext = chosen.automaticCompact.generationContext.copy(
                    subagentToolsEnabled = false, todoEnabled = false, accessSavedMemories = false,
                    accessActiveMemory = false, skillReadAccess = false, skillModifyAccess = false,
                    accessPastConversations = false, webSearchEnabled = false, imageGenEnabled = false,
                    askUserEnabled = false, automationToolsEnabled = false, shellEnabled = false,
                    sandboxEnabled = false, sandboxSharedStorageEnabled = false, imageTranscriptionEnabled = false,
                ),
            ),
        ), result)
        assertSame(chosen.config, result.config)
        assertSame(chosen.providerInstances, result.providerInstances)
        assertSame(chosen.context.webSearchApiKeys, result.context.webSearchApiKeys)
        assertSame(chosen.automaticCompact.generationConfig, result.automaticCompact.generationConfig)
        assertTrue(permissions(chosen.context).all { it })
    }

    @Test fun laterUncapturedAskUserInputStillHonorsEarlierParentRestrictionsWithDrainTimeCapture() {
        val parent = snapshot("Parent:model", context().copy(
            shellEnabled = false, todoEnabled = false, accessPastConversations = false, subagentToolsEnabled = false,
        ))
        val chosen = snapshot("Drain:last")
        val batch = listOf(queued("parent", parent), queued("ask-user", null,
            MessageSource.askUser(listOf(MessageSource.AskUserItem("Next action?", "Continue")))))
        val result = narrowQueuedGuidancePermissions(batch, chosen)
        assertFalse(result.context.shellEnabled)
        assertFalse(result.context.todoEnabled)
        assertFalse(result.context.accessPastConversations)
        assertFalse(result.context.subagentToolsEnabled)
        assertTrue(result.context.webSearchEnabled)
        assertEquals("Drain:last", result.selectedModelId)
        assertEquals(chosen.runId, result.runId)
    }

    @Test fun differentParentSnapshotsIntersectPermissionsRegardlessOfBatchOrder() {
        val first = snapshot("First:model", context().copy(
            skillModifyAccess = false, sandboxEnabled = false, subagentToolsEnabled = false,
        ))
        val second = snapshot("Second:model", context().copy(
            webSearchEnabled = false, askUserEnabled = false, accessPastConversations = false,
        ))
        val chosen = snapshot("UI:last")
        val batch = listOf(queued("first", first), queued("second", second), queued("typed", chosen))
        val result = narrowQueuedGuidancePermissions(batch, chosen)
        assertEquals(result, narrowQueuedGuidancePermissions(batch.reversed(), chosen))
        assertFalse(result.context.skillModifyAccess)
        assertFalse(result.context.sandboxEnabled)
        assertFalse(result.context.subagentToolsEnabled)
        assertFalse(result.context.webSearchEnabled)
        assertFalse(result.context.askUserEnabled)
        assertFalse(result.context.accessPastConversations)
        assertTrue(result.context.todoEnabled)
        assertTrue(result.context.shellEnabled)
        assertEquals(chosen.context.copy(
            skillModifyAccess = false, sandboxEnabled = false, subagentToolsEnabled = false,
            webSearchEnabled = false, askUserEnabled = false, accessPastConversations = false,
        ), result.context)
        assertEquals(chosen.selectedModelId, result.selectedModelId)
        assertSame(chosen.config, result.config)
    }

    @Test fun ordinaryTypedBatchesWithTheSamePermissionsReturnTheChosenSnapshotUnchanged() {
        val first = snapshot("UI:older")
        val chosen = snapshot("UI:last")
        val batch = listOf(queued("first", first), queued("last", chosen))
        assertSame(chosen, narrowQueuedGuidancePermissions(batch, chosen))
        assertSame(chosen, narrowQueuedGuidancePermissions(listOf(queued("legacy", null)), chosen))
        assertSame(chosen, narrowQueuedGuidancePermissions(emptyList(), chosen))
    }

    @Test fun chosenRestrictionsAndCompactSpecificRestrictionsCanNeverBeEnabledByAnEarlierSnapshot() {
        val chosen = snapshot("UI:last", context(false), context().copy(todoEnabled = false))
        val earlier = snapshot("Parent:model")
        val result = narrowQueuedGuidancePermissions(listOf(queued("earlier", earlier), queued("last", chosen)), chosen)
        assertTrue(permissions(result.context).none { it })
        assertTrue(permissions(result.automaticCompact.generationContext).none { it })
        val drainCaptured = narrowQueuedGuidancePermissions(listOf(queued("legacy", null)), chosen)
        assertTrue(permissions(drainCaptured.context).none { it })
        assertTrue(permissions(drainCaptured.automaticCompact.generationContext).none { it })
        val ordinary = snapshot("UI:ordinary", context(), context(false))
        assertSame(ordinary, narrowQueuedGuidancePermissions(listOf(queued("last", ordinary)), ordinary))
    }

    @Test fun shellCapabilitiesAreAnIntersectionOfTargetsAndKeepOnlyChosenConnectionObjects() {
        val a = ShellDeviceConfig(id = "a", name = "A", serverUrl = "a-endpoint", apiKey = "chosen-a")
        val b = ShellDeviceConfig(id = "b", name = "B", serverUrl = "b-endpoint", apiKey = "chosen-b")
        val c = ShellDeviceConfig(id = "c", name = "C", serverUrl = "c-endpoint", apiKey = "chosen-c")
        val d = ShellDeviceConfig(id = "d", name = "D", serverUrl = "d-endpoint")
        val first = snapshot("Parent:first", context().copy(shellDevices = listOf(
            a.copy(apiKey = "earlier-a"), b.copy(apiKey = "earlier-b"), d,
        )))
        val second = snapshot("Parent:second", context().copy(shellDevices = listOf(
            b.copy(name = "Renamed B", apiKey = "other-b"), c,
        )))
        val chosen = snapshot("UI:last", context().copy(shellDevices = listOf(c, b, a)))
        val result = narrowQueuedGuidancePermissions(listOf(
            queued("first", first), queued("second", second), queued("last", chosen),
        ), chosen)
        assertEquals(listOf(b), result.context.shellDevices)
        assertSame(b, result.context.shellDevices.single())
        assertEquals(listOf(b), result.automaticCompact.generationContext.shellDevices)
        assertEquals("chosen-b", result.context.shellDevices.single().apiKey)
        assertEquals(listOf(c, b, a), chosen.context.shellDevices)
    }

    @Test fun sameDeviceIdDoesNotAuthorizeAChangedServerTargetOrSshAccount() {
        val base = ShellDeviceConfig(id = "shared", name = "Server", serverUrl = "admitted-endpoint",
            conchPublicKey = "admitted-key")
        val ssh = ShellDeviceConfig(id = "ssh", name = "SSH", type = "ssh", sshHost = "admitted-host",
            sshPort = 22, sshUser = "admitted-user", sshHostKey = "admitted-host-key")
        val parent = snapshot("Parent:model", context().copy(shellDevices = listOf(base, ssh)))
        val changedTargets = listOf(
            base.copy(serverUrl = "different-endpoint"), base.copy(conchPublicKey = "different-key"),
            base.copy(type = "ssh"), ssh.copy(sshHost = "different-host"), ssh.copy(sshPort = 23),
            ssh.copy(sshUser = "different-user"), ssh.copy(sshHostKey = "different-host-key"),
        )
        changedTargets.forEach { target ->
            val chosen = snapshot("UI:last", context().copy(shellDevices = listOf(target)))
            val result = narrowQueuedGuidancePermissions(listOf(queued("parent", parent), queued("last", chosen)), chosen)
            assertTrue(result.context.shellDevices.isEmpty())
        }
    }

    @Test fun sshCredentialChangesKeepTargetIntersectionAndOnlyChosenCredentials() {
        val admitted = ShellDeviceConfig(
            id = "ssh", name = "SSH", type = "ssh", sshHost = "host", sshUser = "user",
            sshHostKey = "pin", sshPassword = "old password",
        )
        val chosenDevice = admitted.copy(
            sshAuthMode = com.newoether.agora.data.SSH_AUTH_PRIVATE_KEY,
            sshPassword = "", sshPrivateKey = "chosen key", sshKeyPassphrase = "chosen passphrase",
        )
        val parent = snapshot("Parent:model", context().copy(shellDevices = listOf(admitted)))
        val chosen = snapshot("UI:last", context().copy(shellDevices = listOf(chosenDevice)))
        val result = narrowQueuedGuidancePermissions(listOf(queued("parent", parent)), chosen)
        assertSame(chosenDevice, result.context.shellDevices.single())
        assertEquals("chosen key", result.context.shellDevices.single().sshPrivateKey)
        assertEquals("chosen passphrase", result.context.shellDevices.single().sshKeyPassphrase)
    }

    @Test fun webConfigurationsAndCredentialsAreNeverMixedAcrossModels() {
        val parent = snapshot("Parent:model", context().copy(
            webSearchProvider = "brave", webSearchApiKeys = mapOf("brave" to "parent key"),
            webSearchBaseUrl = "parent endpoint", imageGenApiKey = "parent image key",
        ))
        val chosen = snapshot("UI:last", context().copy(
            webSearchProvider = "kagi", webSearchApiKeys = mapOf("kagi" to "chosen key", "tavily" to "unused key"),
            webSearchBaseUrl = "chosen endpoint", imageGenApiKey = "chosen image key", imageGenModel = "chosen image model",
        ))
        assertSame(chosen, narrowQueuedGuidancePermissions(listOf(queued("parent", parent), queued("last", chosen)), chosen))
        val deniedParent = parent.copy(context = parent.context.copy(webSearchEnabled = false))
        val result = narrowQueuedGuidancePermissions(listOf(queued("parent", deniedParent), queued("last", chosen)), chosen)
        assertFalse(result.context.webSearchEnabled)
        assertEquals("kagi", result.context.webSearchProvider)
        assertSame(chosen.context.webSearchApiKeys, result.context.webSearchApiKeys)
        assertEquals("chosen endpoint", result.context.webSearchBaseUrl)
        assertEquals("chosen image key", result.context.imageGenApiKey)
        assertEquals("chosen image model", result.context.imageGenModel)
    }

    @Test fun compactNarrowingPreservesItsOwnConnectionAndProviderConfiguration() {
        val device = ShellDeviceConfig(id = "a", name = "A", serverUrl = "endpoint", apiKey = "ordinary key")
        val parent = snapshot("Parent:model", context().copy(
            shellDevices = listOf(device.copy(apiKey = "parent key")), skillModifyAccess = false,
        ))
        val ordinaryContext = context().copy(shellDevices = listOf(device))
        val compactContext = ordinaryContext.copy(
            shellDevices = listOf(device.copy(apiKey = "compact key")), webSearchEnabled = false,
            todoEnabled = false, subagentToolsEnabled = false, imageGenModel = "compact image model",
            imageGenApiKey = "compact image key", toolTimeoutMs = 456L,
        )
        val chosen = snapshot("UI:last", ordinaryContext, compactContext)
        val result = narrowQueuedGuidancePermissions(listOf(queued("parent", parent), queued("last", chosen)), chosen)
        assertFalse(result.context.skillModifyAccess)
        assertFalse(result.automaticCompact.generationContext.skillModifyAccess)
        assertFalse(result.automaticCompact.generationContext.webSearchEnabled)
        assertFalse(result.automaticCompact.generationContext.todoEnabled)
        assertFalse(result.automaticCompact.generationContext.subagentToolsEnabled)
        assertEquals("ordinary key", result.context.shellDevices.single().apiKey)
        assertEquals("compact key", result.automaticCompact.generationContext.shellDevices.single().apiKey)
        assertEquals("compact image model", result.automaticCompact.generationContext.imageGenModel)
        assertEquals(456L, result.automaticCompact.generationContext.toolTimeoutMs)
        assertEquals(chosen.automaticCompact.copy(
            generationContext = compactContext.copy(skillModifyAccess = false),
        ), result.automaticCompact)
    }

    @Test fun queuedPhonePermissionsIntersectModeReadinessAndAutomationLineage() {
        val allowed = context().copy(phoneEnabled = true, phoneReady = true, phoneAllowAutomation = true)
        val chosen = snapshot("last", allowed)
        val automated = snapshot("task", allowed.copy(automationCaller = true, phoneAllowAutomation = false))
        val narrowed = narrowQueuedGuidancePermissions(listOf(queued("task", automated), queued("typed", chosen)), chosen)
        assertTrue(narrowed.context.automationCaller)
        assertFalse(narrowed.context.phoneAllowAutomation)
        assertTrue(com.newoether.agora.phone.phoneDefinitions(narrowed.context).isEmpty())
        val differentMode = snapshot("parent", allowed.copy(phoneMode = "root"))
        assertFalse(narrowQueuedGuidancePermissions(listOf(queued("parent", differentMode)), chosen).context.phoneEnabled)
        val notReady = snapshot("parent", allowed.copy(phoneReady = false))
        assertFalse(narrowQueuedGuidancePermissions(listOf(queued("parent", notReady)), chosen).context.phoneReady)
    }

    @Test fun uncapturedAutomationInputsCannotAcquireInteractivePhoneAdmissionAtDrain() {
        val chosen = snapshot("typed", context().copy(phoneEnabled = true, phoneReady = true))
        for (source in listOf(MessageSource.TASK, MessageSource.LOOP)) {
            val result = narrowQueuedGuidancePermissions(listOf(queued("legacy", null, source)), chosen)
            assertTrue(result.context.automationCaller)
            assertTrue(result.automaticCompact.generationContext.automationCaller)
            assertTrue(com.newoether.agora.phone.phoneDefinitions(result.context).isEmpty())
        }
    }

    @Test fun restrictionsNeverReplaceChosenConversationOrEffectIdentity() {
        val parent = snapshot("Parent:model", context(false).copy(
            toolEffectIdentity = RunEffectIdentity("parent", 1L, "parent-run", 1, "old-effect"),
        ))
        val chosen = snapshot("UI:last")
        val result = narrowQueuedGuidancePermissions(listOf(queued("parent", parent), queued("last", chosen)), chosen)
        assertEquals("child", result.context.conversationId)
        assertNull(result.context.toolEffectIdentity)
        assertEquals(chosen.runId, result.runId)
        assertEquals(chosen.conversationId, result.conversationId)
    }
}
