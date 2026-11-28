package com.newoether.agora.viewmodel
import com.newoether.agora.data.repository.replaceConfiguredModelReferences

import com.newoether.agora.data.CustomEndpointProtocol
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.ProviderEditorDraft
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ModelId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns custom Provider configuration and serialized custom-model reference migrations. */
internal class CustomModelConfigurationController(
    private val providers: ProviderRegistry,
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val onModelReferenceReplaced: (oldModelId: String, newModelId: String?) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutationMutex = Mutex()

    fun addProvider(
        name: String,
        baseUrl: String,
        protocol: CustomEndpointProtocol = CustomEndpointProtocol.OPENAI,
    ) = providers.addCustom(name, baseUrl, protocol)

    fun renameProvider(oldName: String, newName: String) {
        providers.renameCustom(oldName, newName)
    }

    fun updateProviderProtocol(name: String, protocol: CustomEndpointProtocol) =
        providers.updateCustomProtocol(name, protocol)

    fun deleteProvider(name: String) = providers.deleteCustom(name)

    /** Explicit Save of a remote Provider detail page, built-in or custom. */
    suspend fun commitProviderEditor(originalName: String, draft: ProviderEditorDraft) =
        mutationMutex.withLock { providers.commitEditor(originalName, draft) }

    /** Explicit Save of the add route, which is the same full editor with no Provider yet. */
    suspend fun createProviderFromEditor(draft: ProviderEditorDraft) =
        mutationMutex.withLock { providers.createEditor(draft) }

    fun updateModel(
        oldModelId: String,
        provider: String,
        modelId: String,
        alias: String,
        showProviderName: Boolean? = null,
    ) {
        val normalizedProvider = settings.stableProviderReference(provider)
        val normalizedModelId = modelId.trim()
        if (normalizedProvider.isEmpty() || normalizedModelId.isEmpty()) return
        val newModelId = ModelId(normalizedProvider, normalizedModelId).prefixed

        scope.launch(ioDispatcher) {
            mutationMutex.withLock {
                val customModels = settings.customModels.value
                if (oldModelId !in customModels) return@withLock
                if (newModelId != oldModelId && newModelId in customModels) return@withLock

                settings.replaceCustomModel(oldModelId, newModelId, alias, showProviderName)
                conversations.replaceConfiguredModelReferences(oldModelId, newModelId)
                onModelReferenceReplaced(oldModelId, newModelId)
            }
        }
    }

    fun deleteModel(modelId: String) {
        scope.launch(ioDispatcher) {
            mutationMutex.withLock {
                if (modelId !in settings.customModels.value) return@withLock

                settings.replaceCustomModel(modelId, null, "")
                conversations.replaceConfiguredModelReferences(modelId, null)
                onModelReferenceReplaced(modelId, null)
            }
        }
    }
}
