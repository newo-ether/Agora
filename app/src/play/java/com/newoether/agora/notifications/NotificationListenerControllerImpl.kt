package com.newoether.agora.notifications

import com.newoether.agora.data.NotificationListenerController
import com.newoether.agora.data.NotificationListenerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * No-op implementation for play flavor - notifications not supported.
 */
class NotificationListenerControllerImpl : NotificationListenerController {

    override fun isListenerAccessGranted(): Boolean = false

    override suspend fun openNotificationListenerSettings() = withContext(Dispatchers.IO) {
        // No-op for play flavor
    }

    override suspend fun getListenerStatus(): NotificationListenerStatus = withContext(Dispatchers.IO) {
        NotificationListenerStatus(hasAccess = false, intentEnabled = false)
    }
}