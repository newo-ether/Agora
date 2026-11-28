package com.newoether.agora.data

import kotlinx.coroutines.flow.first

internal object NativeBackupSecretsPolicy {
    suspend fun capture(sm: SettingsManager): NativeBackupSecrets {
        val shellSecrets = sm.shellDevices.first()
            .mapNotNull { device ->
                ShellDeviceSecrets(
                    apiKey = device.apiKey,
                    sshPassword = device.sshPassword,
                    sshPrivateKey = device.sshPrivateKey,
                    sshKeyPassphrase = device.sshKeyPassphrase,
                ).takeIf {
                    it.apiKey.isNotBlank() || it.sshPassword.isNotBlank() ||
                        !it.sshPrivateKey.isNullOrBlank() || !it.sshKeyPassphrase.isNullOrBlank()
                }
                    ?.let { device.id to it }
            }
            .toMap()
        val embeddingSecrets = sm.embeddingModels.first()
            .filter { it.type == EmbeddingModelType.REMOTE && it.remoteApiKey.isNotBlank() }
            .associate { it.id to it.remoteApiKey }
        val mcpSecrets = sm.mcpServers.first()
            .filter { it.headers.isNotEmpty() }
            .associate { it.id to it.headers }
        return NativeBackupSecrets(
            apiKeys = sm.apiKeys.first(),
            activeApiKeyIds = sm.activeApiKeyIds.first(),
            webSearchApiKeys = sm.webSearchApiKeys.first(),
            proxyPassword = sm.proxyPassword.first(),
            shellDevices = shellSecrets,
            embeddingApiKeys = embeddingSecrets,
            speechRecognitionApiKeys = SpeechRecognitionPortableSettings.captureSecrets(sm.speechRecognition),
            mcpHeaders = mcpSecrets,
        )
    }

    suspend fun restore(
        data: NativeBackupSecrets,
        sm: SettingsManager,
        replace: Boolean,
    ): List<String> {
        val warnings = mutableListOf<String>()

        val importedIdMap = mutableMapOf<String, String>()
        val finalKeys = if (replace) {
            data.apiKeys
        } else {
            val merged = sm.apiKeys.first().toMutableList()
            for (imported in data.apiKeys) {
                val sameId = merged.indexOfFirst { it.id == imported.id }
                when {
                    sameId >= 0 -> {
                        merged[sameId] = imported
                        importedIdMap[imported.id] = imported.id
                    }
                    else -> {
                        val semanticMatch = merged.firstOrNull {
                            it.provider == imported.provider && it.key == imported.key
                        }
                        if (semanticMatch != null) {
                            importedIdMap[imported.id] = semanticMatch.id
                        } else {
                            merged += imported
                            importedIdMap[imported.id] = imported.id
                        }
                    }
                }
            }
            merged
        }
        if (replace) data.apiKeys.forEach { importedIdMap[it.id] = it.id }
        sm.saveApiKeys(finalKeys)

        val importedActiveIds = data.activeApiKeyIds.mapValues { (_, id) ->
            importedIdMap[id] ?: id
        }.filterValues { id -> finalKeys.any { it.id == id } }
        val finalActiveIds = if (replace) {
            importedActiveIds
        } else {
            sm.activeApiKeyIds.first() + importedActiveIds
        }
        sm.saveActiveApiKeyIds(finalActiveIds)

        val finalWebKeys = if (replace) {
            data.webSearchApiKeys
        } else {
            sm.webSearchApiKeys.first() + data.webSearchApiKeys
        }
        sm.saveWebSearchApiKeys(finalWebKeys)

        if (replace || data.proxyPassword.isNotBlank()) {
            sm.saveProxyPassword(data.proxyPassword)
        }

        val shellDevices = sm.shellDevices.first()
        val shellById = data.shellDevices
        var orphanShellSecrets = 0
        val restoredShell = shellDevices.map { device ->
            val byId = shellById[device.id]
            val legacyApiKey = data.shellApiKeys[device.name]
            when {
                byId != null -> device.copy(
                    apiKey = byId.apiKey,
                    sshPassword = byId.sshPassword,
                    sshPrivateKey = byId.sshPrivateKey ?: if (replace) "" else device.sshPrivateKey,
                    sshKeyPassphrase = byId.sshKeyPassphrase ?: if (replace) "" else device.sshKeyPassphrase,
                )
                legacyApiKey != null -> device.copy(
                    apiKey = legacyApiKey,
                    sshPassword = if (replace) "" else device.sshPassword,
                    sshPrivateKey = if (replace) "" else device.sshPrivateKey,
                    sshKeyPassphrase = if (replace) "" else device.sshKeyPassphrase,
                )
                replace -> device.withoutSecrets()
                else -> device
            }
        }
        orphanShellSecrets += shellById.keys.count { id -> shellDevices.none { it.id == id } }
        orphanShellSecrets += data.shellApiKeys.keys.count { name ->
            shellDevices.none { it.name == name }
        }
        if (orphanShellSecrets > 0) {
            warnings += "ignored $orphanShellSecrets shell credential record(s) without a matching device"
        }
        sm.saveShellDevices(restoredShell)

        val embeddingModels = sm.embeddingModels.first()
        val embeddingIds = embeddingModels.mapTo(mutableSetOf()) { it.id }
        val orphanEmbeddingSecrets = data.embeddingApiKeys.keys.count { it !in embeddingIds }
        if (orphanEmbeddingSecrets > 0) {
            warnings +=
                "ignored $orphanEmbeddingSecrets embedding credential record(s) without a matching model"
        }
        sm.saveEmbeddingModels(
            embeddingModels.map { model ->
                if (model.type != EmbeddingModelType.REMOTE) {
                    model
                } else {
                    val imported = data.embeddingApiKeys[model.id]
                    when {
                        imported != null -> model.copy(remoteApiKey = imported)
                        replace -> model.copy(remoteApiKey = "")
                        else -> model
                    }
                }
            },
        )

        val orphanSpeechSecrets = SpeechRecognitionPortableSettings.restoreSecrets(
            data.speechRecognitionApiKeys,
            sm.speechRecognition,
            replace,
        )
        if (orphanSpeechSecrets > 0) {
            warnings += "ignored $orphanSpeechSecrets speech recognition credential record(s) without a matching config"
        }

        val mcpServers = sm.mcpServers.first()
        val mcpIds = mcpServers.mapTo(mutableSetOf()) { it.id }
        val orphanMcpSecrets = data.mcpHeaders.keys.count { it !in mcpIds }
        if (orphanMcpSecrets > 0) {
            warnings += "ignored $orphanMcpSecrets MCP header record(s) without a matching server"
        }
        sm.saveMcpServers(
            mcpServers.map { server ->
                val imported = data.mcpHeaders[server.id]
                when {
                    imported != null -> server.copy(headers = imported)
                    replace -> server.copy(headers = emptyMap())
                    else -> server
                }
            },
        )

        return warnings
    }
}

