package com.newoether.agora.data

import androidx.datastore.preferences.core.Preferences
import com.newoether.agora.model.OpenAiServiceTiers
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.SecretCrypto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class ApiKeyEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val key: String,
    val provider: String = Constants.PROVIDER_GOOGLE
)

const val SSH_AUTH_PASSWORD = "password"
const val SSH_AUTH_PRIVATE_KEY = "private_key"
@Serializable
data class ShellDeviceConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val type: String = "conch",          // "conch" | "ssh"
    // Conch fields (type=conch)
    val serverUrl: String = "",
    val apiKey: String = "",
    val conchPublicKey: String = "",
    // SSH fields (type=ssh)
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUser: String = "root",
    val sshPassword: String = "",
    // Optional private-key auth (PEM). When set, used in addition to / instead of the password.
    // sshKeyPassphrase decrypts an encrypted key; blank for an unencrypted key.
    val sshAuthMode: String = SSH_AUTH_PASSWORD,
    val sshPrivateKey: String = "",
    val sshPrivateKeyName: String = "",
    val sshKeyPassphrase: String = "",
    // Pinned SSH host key (base64 of the server public-key blob). Blank = not yet
    // pinned (trust-on-first-use); once set, connections must match or are rejected.
    val sshHostKey: String = ""
)

@Serializable
enum class McpTransportType {
    @SerialName("streamable_http")
    STREAMABLE_HTTP,

    @SerialName("sse")
    SSE,
}

@Serializable
data class McpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val url: String = "",
    val transport: McpTransportType = McpTransportType.STREAMABLE_HTTP,
    val headers: Map<String, String> = emptyMap(),
    /** Raw MCP tool names disabled for this server. New tools stay enabled by default. */
    val disabledTools: Set<String> = emptySet(),
)

@Serializable
data class SystemPromptEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String = "",
    val systemItems: List<PromptTemplateItem> = emptyList(),
    val userItems: List<PromptTemplateItem> = emptyList(),
    val assistantItems: List<PromptTemplateItem> = emptyList(),
    // Legacy fields retained only so prompts saved before structured message templates deserialize.
    val userPrependItems: List<PromptTemplateItem> = emptyList(),
    val userPostpendItems: List<PromptTemplateItem> = emptyList(),
) {
    val resolvedSystemItems: List<PromptTemplateItem>
        get() = if (systemItems.isNotEmpty()) systemItems
        else if (content.isNotBlank()) listOf(PromptTemplateItem(type = PromptItemType.CUSTOM, value = content))
        else emptyList()

    val resolvedUserItems: List<PromptTemplateItem>
        get() = PredefinedVariables.normalizeMessageTemplate(
            if (userItems.isNotEmpty()) {
                userItems
            } else {
                userPrependItems + PredefinedVariables.promptItem() + userPostpendItems
            },
        )

    val resolvedAssistantItems: List<PromptTemplateItem>
        get() = PredefinedVariables.normalizeMessageTemplate(assistantItems)
}

internal val WEB_SEARCH_PROVIDERS = setOf(
    "duckduckgo", "brave", "kagi", "serper", "tavily", "searxng",
)

internal fun normalizeWebSearchProvider(provider: String?): String =
    provider?.trim()?.lowercase()?.takeIf(WEB_SEARCH_PROVIDERS::contains) ?: "duckduckgo"

internal fun decodeWebSearchApiKeys(preferences: Preferences, json: Json): Map<String, String> {
    val raw = SecretCrypto.decrypt(preferences[WEB_SEARCH_API_KEYS_JSON] ?: "{}")
    return try {
        json.decodeFromString<Map<String, String>>(raw)
    } catch (error: Exception) {
        DebugLog.e("SettingsManager", "Failed to decode webSearchApiKeys", error)
        emptyMap()
    }
}

internal fun decodeConversationSettings(
    preferences: Preferences,
    json: Json,
): Map<String, ConversationSettings> = try {
    json.decodeFromString<Map<String, ConversationSettings>>(
        preferences[CONVERSATION_SETTINGS_JSON] ?: "{}",
    ).mapValues { (_, settings) -> settings.normalizedServiceTier() }
} catch (_: Exception) {
    emptyMap()
}

internal fun ConversationSettings.normalizedServiceTier(): ConversationSettings =
    if (openAiServiceTier == null) this
    else copy(openAiServiceTier = OpenAiServiceTiers.normalize(openAiServiceTier))

internal fun encryptShellDevices(devices: List<ShellDeviceConfig>, json: Json): String {
    val stored = SecretCrypto.encrypt(json.encodeToString(devices))
    check(devices.none { it.sshPrivateKey.isNotEmpty() || it.sshKeyPassphrase.isNotEmpty() } ||
        SecretCrypto.isEncrypted(stored)) { "Could not encrypt SSH private-key credentials" }
    return stored
}

internal fun decodeEncryptedShellDevices(preferences: Preferences, json: Json): List<ShellDeviceConfig> {
    val raw = SecretCrypto.decrypt(preferences[SHELL_DEVICES_JSON] ?: "[]")
    return runCatching { json.decodeFromString<List<ShellDeviceConfig>>(raw) }.getOrDefault(emptyList())
}

@Serializable
data class ConversationSettings(
    /** Provider-visible conversation token budget. Values <=100 are legacy message windows. */
    val contextWindow: Int? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val codeExecutionEnabled: Boolean? = null,
    val googleSearchEnabled: Boolean? = null,
    val openAiWebSearchEnabled: Boolean? = null,
    val thinkingEnabled: Boolean? = null,
    val thinkingLevel: String? = null,
    val thinkingBudgetEnabled: Boolean? = null,
    val thinkingBudgetTokens: Int? = null,
    val openAiServiceTierEnabled: Boolean? = null,
    val openAiServiceTier: String? = null,
    val webSearchEnabled: Boolean? = null,
    val shellEnabled: Boolean? = null,
    val lowContextModeEnabled: Boolean? = null,
) {
    fun isAllNull() = contextWindow == null && temperature == null && maxTokens == null && topP == null
        && frequencyPenalty == null && presencePenalty == null
        && codeExecutionEnabled == null && googleSearchEnabled == null
        && openAiWebSearchEnabled == null && thinkingEnabled == null
        && thinkingLevel == null && thinkingBudgetEnabled == null && thinkingBudgetTokens == null
        && openAiServiceTierEnabled == null && openAiServiceTier == null
        && webSearchEnabled == null && shellEnabled == null
        && lowContextModeEnabled == null
}
