package com.newoether.agora.assistant

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Helper around Android's RoleManager for the ASSISTANT (default digital
 * assistant) role.
 *
 * Requesting this role shows a system consent dialog; only the user can grant
 * it. Holding it makes Agora the device's assistant (assist gesture /
 * power-button handoff). It does NOT grant any privileged capability on its
 * own — it only routes the assist trigger to Agora.
 *
 * Adapted from the AIOPE assistant implementation (XNet).
 */
object AssistantRole {

    private fun roleManager(context: Context): RoleManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)
        } else {
            null
        }

    /** Whether the ASSISTANT role exists and can be requested on this device. */
    fun isAvailable(context: Context): Boolean {
        val rm = roleManager(context) ?: return false
        return try {
            rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
        } catch (_: Exception) {
            false
        }
    }

    /** Whether Agora currently holds the ASSISTANT role. */
    fun isHeld(context: Context): Boolean {
        val rm = roleManager(context) ?: return false
        return try {
            rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Intent that lets the user make Agora the default assistant.
     *
     * NOTE: `ROLE_ASSISTANT` is a special role that Android's PermissionController does NOT grant
     * through the generic `createRequestRoleIntent` consent dialog (unlike BROWSER/SMS/DIALER).
     * On AOSP/GrapheneOS that request is refused/cancelled immediately, which is why the in-app
     * switch appeared to "do nothing". The supported path is the system Digital-assistant picker
     * (Settings > Apps > Default apps > Digital assistant app), where Agora appears because it
     * publishes a VoiceInteractionService. We therefore deep-link to that picker.
     *
     * Returns null only if the role isn't available at all on this device. Launch via an
     * ActivityResultLauncher; on return, re-check [isHeld] (the picker has no RESULT_OK contract).
     */
    fun requestIntent(context: Context): Intent? {
        if (!isAvailable(context)) return null
        // Primary: the dedicated assistant/voice-input settings screen (the real picker).
        val voiceInput = Intent("android.settings.VOICE_INPUT_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (voiceInput.resolveActivity(context.packageManager) != null) return voiceInput

        // Fallback: the generic Default-apps settings screen.
        val defaults = Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (defaults.resolveActivity(context.packageManager) != null) return defaults

        // Last resort: the RoleManager request intent (works on some OEM builds).
        val rm = roleManager(context) ?: return null
        return try {
            rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
        } catch (_: Exception) {
            null
        }
    }
}
