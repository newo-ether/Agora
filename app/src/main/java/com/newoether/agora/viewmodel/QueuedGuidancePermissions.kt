package com.newoether.agora.viewmodel

import com.newoether.agora.data.ShellDeviceConfig

/** Keep the selected request configuration while honoring every input's admitted tool access. */
internal fun narrowQueuedGuidancePermissions(
    batch: List<QueuedSend>,
    chosen: GenerationAdmissionSnapshot,
): GenerationAdmissionSnapshot {
    val automaticSource = batch.any { it.source?.kind in setOf(
        com.newoether.agora.model.MessageSource.Kind.TASK, com.newoether.agora.model.MessageSource.Kind.LOOP,
    ) }
    val restrictions = batch.mapNotNull { it.generationSnapshot?.context } + chosen.context
    val context = chosen.context.narrowPermissions(restrictions, automaticSource)
    val compactContext = chosen.automaticCompact.generationContext.narrowPermissions(restrictions, automaticSource)
    if (context == chosen.context && compactContext == chosen.automaticCompact.generationContext) return chosen
    return chosen.copy(
        context = context,
        automaticCompact = chosen.automaticCompact.copy(generationContext = compactContext),
    )
}

private fun GenerationContext.narrowPermissions(restrictions: List<GenerationContext>, automaticSource: Boolean): GenerationContext {
    fun allowed(permission: (GenerationContext) -> Boolean): Boolean =
        permission(this) && restrictions.all(permission)

    return copy(
        phoneEnabled = allowed { it.phoneEnabled } && restrictions.all { it.phoneMode == phoneMode },
        phoneReady = allowed { it.phoneReady },
        phoneAllowAutomation = allowed { it.phoneAllowAutomation },
        automationCaller = automationCaller || automaticSource || restrictions.any { it.automationCaller },
        subagentToolsEnabled = allowed { it.subagentToolsEnabled },
        subagentDeleteEnabled = allowed { it.subagentDeleteEnabled },
        todoEnabled = allowed { it.todoEnabled },
        accessSavedMemories = allowed { it.accessSavedMemories },
        accessActiveMemory = allowed { it.accessActiveMemory },
        skillReadAccess = allowed { it.skillReadAccess },
        skillModifyAccess = allowed { it.skillModifyAccess },
        accessPastConversations = allowed { it.accessPastConversations },
        webSearchEnabled = allowed { it.webSearchEnabled },
        imageGenEnabled = allowed { it.imageGenEnabled },
        askUserEnabled = allowed { it.askUserEnabled },
        automationToolsEnabled = allowed { it.automationToolsEnabled },
        shellEnabled = allowed { it.shellEnabled },
        sandboxEnabled = allowed { it.sandboxEnabled },
        sandboxSharedStorageEnabled = allowed { it.sandboxSharedStorageEnabled },
        imageTranscriptionEnabled = allowed { it.imageTranscriptionEnabled },
        // Filter the chosen capabilities, never borrow another input's connection or credentials.
        shellDevices = shellDevices.filter { chosen ->
            restrictions.all { ctx -> ctx.shellDevices.any { chosen.sameServer(it) } }
        },
        // Web provider/keys, image/transcription models and execution policy remain chosen-owned.
    )
}

private fun ShellDeviceConfig.sameServer(other: ShellDeviceConfig): Boolean =
    id == other.id && type == other.type && when (type) {
        "ssh" -> sshHost == other.sshHost && sshPort == other.sshPort && sshUser == other.sshUser &&
            sshHostKey == other.sshHostKey
        else -> serverUrl == other.serverUrl && conchPublicKey == other.conchPublicKey
    }
