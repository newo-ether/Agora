package com.newoether.agora.api.openai

import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.util.Constants

class OpenCodeGoProvider(
    private val appVersion: String = "?",
) : BaseOpenAiProvider() {
    override val name: String = Constants.PROVIDER_OPENCODE_GO
    override val defaultBaseUrl: String = "https://opencode.ai/zen/go/v1"

    override fun getExtraHeaders(config: ProviderConfig): Map<String, String> = buildMap {
        put("User-Agent", "Agora/$appVersion")
        config.sessionId?.takeIf(String::isNotBlank)?.let { put("x-opencode-session", it) }
    }
}
