package com.newoether.agora.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of SmsSender for fdroid flavor.
 * Uses SmsManager to send text messages.
 */
class SmsSenderImpl(private val context: Context) : SmsSender {

    override fun isSupported(): Boolean = declaresSendSms()

    override fun hasPermission(): Boolean =
        isSupported() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun sendSms(address: String, body: String): SmsSendResult {
        if (!hasPermission()) {
            return SmsSendResult.Failure("SEND_SMS permission not granted")
        }
        if (address.isBlank()) return SmsSendResult.Failure("Missing address")
        if (body.isEmpty()) return SmsSendResult.Failure("Empty body")

        return withContext(Dispatchers.IO) {
            try {
                val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                val parts = manager.divideMessage(body)
                if (parts.size <= 1) {
                    manager.sendTextMessage(address, null, body, null, null)
                } else {
                    manager.sendMultipartTextMessage(address, null, parts, null, null)
                }
                SmsSendResult.Success
            } catch (e: Exception) {
                com.newoether.agora.util.DebugLog.e("SmsSender", "Failed to send SMS", e)
                SmsSendResult.Failure(e.message ?: e::class.simpleName ?: "Send failed")
            }
        }
    }

    /**
     * Whether SEND_SMS is declared in the merged manifest — a build-time property
     * (fdroid flavor declares it, play does not), safe to cache per process.
     */
    private fun declaresSendSms(): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        ).requestedPermissions?.contains(Manifest.permission.SEND_SMS) == true
    } catch (_: Exception) {
        false
    }
}