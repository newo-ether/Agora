package com.newoether.agora.sms

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Interface for sending SMS messages.
 * Implementation only exists in fdroid flavor.
 */
interface SmsSender {
    /**
     * True when this build can ever send SMS — i.e. Android + `SEND_SMS` declared
     * in the merged manifest (fdroid flavor only). Independent of the runtime grant.
     */
    fun isSupported(): Boolean

    /**
     * True when [isSupported] and the user has granted `SEND_SMS` at runtime.
     */
    fun hasPermission(): Boolean

    /**
     * Fires the message via the system's default SMS stack. Long bodies are split
     * into multiple parts. Returns [SmsSendResult.Success] on accepted submission
     * (delivery is best-effort and may complete asynchronously), or
     * [SmsSendResult.Failure] on a precondition violation (missing permission,
     * bad address, platform unsupported).
     */
    suspend fun sendSms(address: String, body: String): SmsSendResult
}

sealed class SmsSendResult {
    data object Success : SmsSendResult()
    data class Failure(val message: String) : SmsSendResult()
}