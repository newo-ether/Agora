package com.newoether.agora.data

import com.newoether.agora.model.ModelId
import com.newoether.agora.model.ModelThinkingCapabilities
import com.newoether.agora.model.ModelThinkingCapability

/**
 * Thinking capability of one selected model, as the UI needs it to offer exactly the options the
 * endpoint accepts.
 *
 * A custom provider's endpoint protocol decides its wire family, never its display name: a relay
 * can be called anything while speaking the Anthropic, Google or OpenAI protocol.
 */
fun thinkingCapabilityForSelectedModel(
    selectedModel: String,
    customProviders: List<CustomProviderConfig>,
): ModelThinkingCapability {
    val parsed = ModelId.parse(selectedModel)
    val custom = customProviders.firstOrNull {
        it.name == parsed.providerName || it.ownsIdentity(parsed.providerName)
    }
    return ModelThinkingCapabilities.forProvider(
        providerName = parsed.providerName,
        modelId = parsed.modelName,
        customProtocolWireValue = custom?.protocol?.wireValue,
    )
}
