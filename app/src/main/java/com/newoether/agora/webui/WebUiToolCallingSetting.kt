package com.newoether.agora.webui

import com.newoether.agora.data.ConversationSettings

internal object WebUiToolCallingSetting {
    private const val SETTING = "toolsEnabled"

    fun isSupported(setting: String?): Boolean = setting == SETTING

    fun update(settings: ConversationSettings, setting: String?, enabled: Boolean?): ConversationSettings =
        if (isSupported(setting)) settings.copy(toolsEnabled = enabled) else settings
}
