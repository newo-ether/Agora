package com.newoether.agora.data

import com.newoether.agora.util.Constants
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = CustomEndpointProtocolSerializer::class)
enum class CustomEndpointProtocol(val wireValue: String) {
    OPENAI("openai"),
    GOOGLE("google"),
    ANTHROPIC("anthropic"),
    UNKNOWN("unknown");

    companion object {
        val selectable: List<CustomEndpointProtocol> = listOf(OPENAI, GOOGLE, ANTHROPIC)

        fun fromWireValue(value: String): CustomEndpointProtocol = when (value.trim().lowercase()) {
            "openai" -> OPENAI
            "google", "gemini" -> GOOGLE
            "anthropic", "claude" -> ANTHROPIC
            else -> UNKNOWN
        }
    }
}

private object CustomEndpointProtocolSerializer : KSerializer<CustomEndpointProtocol> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("CustomEndpointProtocol", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): CustomEndpointProtocol =
        CustomEndpointProtocol.fromWireValue(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: CustomEndpointProtocol) {
        encoder.encodeString(value.wireValue)
    }
}

@Serializable
data class CustomProviderConfig(
    val name: String,
    // Configs written before protocol selection existed were OpenAI-compatible.
    val protocol: CustomEndpointProtocol = CustomEndpointProtocol.OPENAI,
    /** Immutable namespace used by persisted model IDs. The blank default decodes legacy data. */
    val id: String = "",
    /** Temporary crash-safe bridge until legacy name-prefixed Room references are migrated. */
    val legacyNames: Set<String> = emptySet(),
    /** Retained across protocol changes, but consumed only while [protocol] is OpenAI. */
    val responsesApiEnabled: Boolean = false,
    /**
     * Retained across protocol changes. The enabled flag is the provider's prompt cache switch:
     * Anthropic consumes it as cache_control, OpenAI as prompt_cache_key. TTL is Anthropic-only.
     */
    val anthropicCacheEnabled: Boolean = true,
    val anthropicCacheTtl: String = "1h",
) {
    val providerId: String
        get() = id.takeIf(CustomProviderIdentityPolicy::isStableId) ?: name

    fun ownsIdentity(reference: String): Boolean =
        providerId == reference || reference in legacyNames
}

fun isOpenAiProtocolProvider(
    providerName: String,
    customProviders: List<CustomProviderConfig>,
): Boolean =
    providerName == Constants.PROVIDER_OPENAI ||
        customProviders.any {
            (it.name == providerName || it.ownsIdentity(providerName)) &&
                it.protocol == CustomEndpointProtocol.OPENAI
        }
fun isAnthropicProtocolProvider(
    providerName: String,
    customProviders: List<CustomProviderConfig>,
): Boolean = providerName == Constants.PROVIDER_ANTHROPIC || customProviders.any {
    (it.name == providerName || it.ownsIdentity(providerName)) &&
        it.protocol == CustomEndpointProtocol.ANTHROPIC
}
fun isAnthropicCacheEnabledForProvider(
    providerName: String,
    builtInEnabled: Boolean,
    customProviders: List<CustomProviderConfig>,
): Boolean = when (providerName) {
    Constants.PROVIDER_ANTHROPIC -> builtInEnabled
    else -> customProviders.firstOrNull {
        it.name == providerName || it.ownsIdentity(providerName)
    }?.let { it.protocol == CustomEndpointProtocol.ANTHROPIC && it.anthropicCacheEnabled } == true
}
/** Whether the provider exposes the user-facing prompt cache switch. */
fun supportsPromptCacheSetting(
    providerName: String,
    customProviders: List<CustomProviderConfig>,
): Boolean = isAnthropicProtocolProvider(providerName, customProviders) || customProviders.any {
    (it.name == providerName || it.ownsIdentity(providerName)) &&
        it.protocol == CustomEndpointProtocol.OPENAI
}

/**
 * Whether OpenAI-protocol requests carry prompt_cache_key. Without it, OpenAI, Munito and relays
 * such as sub2api route identical prefixes to arbitrary cache shards, so hits become random.
 * The official providers always send it; custom OpenAI endpoints follow their cache switch.
 */
fun isPromptCacheKeyEnabledForProvider(
    providerName: String,
    customProviders: List<CustomProviderConfig>,
): Boolean = when (providerName) {
    Constants.PROVIDER_OPENAI, Constants.PROVIDER_MUNITO -> true
    else -> customProviders.firstOrNull {
        it.name == providerName || it.ownsIdentity(providerName)
    }?.let { it.protocol == CustomEndpointProtocol.OPENAI && it.anthropicCacheEnabled } == true
}

/** Cache switch state shown in settings, independent of the request protocol. */
fun isPromptCacheSettingEnabledForProvider(
    providerName: String,
    builtInAnthropicEnabled: Boolean,
    customProviders: List<CustomProviderConfig>,
): Boolean = when (providerName) {
    Constants.PROVIDER_ANTHROPIC -> builtInAnthropicEnabled
    else -> customProviders.firstOrNull {
        it.name == providerName || it.ownsIdentity(providerName)
    }?.anthropicCacheEnabled ?: true
}

fun anthropicCacheTtlForProvider(
    providerName: String,
    builtInTtl: String,
    customProviders: List<CustomProviderConfig>,
): String = when (providerName) {
    Constants.PROVIDER_ANTHROPIC -> builtInTtl
    else -> customProviders.firstOrNull {
        it.name == providerName || it.ownsIdentity(providerName)
    }?.anthropicCacheTtl ?: "1h"
}

fun isResponsesApiEnabledForProvider(
    providerName: String,
    builtInOpenAiEnabled: Boolean,
    customProviders: List<CustomProviderConfig>,
): Boolean = when (providerName) {
    Constants.PROVIDER_OPENAI -> builtInOpenAiEnabled
    else -> customProviders.firstOrNull {
        it.name == providerName || it.ownsIdentity(providerName)
    }?.let { it.protocol == CustomEndpointProtocol.OPENAI && it.responsesApiEnabled } == true
}

/**
 * Derived endpoint discovered during model sync.
 *
 * [configuredBaseUrl] remains the user's input. The effective URL is only reusable while
 * both that input and the selected protocol still match, so changing either cannot leak a
 * stale `/v1` (or any other protocol-specific path) into subsequent requests.
 */
@Serializable
data class CustomEndpointResolution(
    val protocol: CustomEndpointProtocol,
    val configuredBaseUrl: String,
    val effectiveBaseUrl: String,
)
