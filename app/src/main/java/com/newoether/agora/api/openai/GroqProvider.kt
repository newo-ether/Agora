package com.newoether.agora.api.openai

import com.newoether.agora.util.Constants

class GroqProvider : BaseOpenAiProvider() {
    override val name: String = Constants.PROVIDER_GROQ
    override val defaultBaseUrl: String = "https://api.groq.com/openai/v1"
}
