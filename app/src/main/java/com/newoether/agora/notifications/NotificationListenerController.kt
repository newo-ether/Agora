package com.newoether.agora.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Interface for managing notification listener access.
 * Implementation only exists in fdroid flavor.
 */
interface NotificationListenerController {
    /**
     * Checks if the user has granted notification listener access to the app.
     */
    fun isListenerAccessGranted(): Boolean

    /**
     * Opens the system notification listener settings screen.
     */
    suspend fun openNotificationListenerSettings()

    /**
     * Gets the current listener status.
     */
    suspend fun getListenerStatus(): NotificationListenerStatus
}