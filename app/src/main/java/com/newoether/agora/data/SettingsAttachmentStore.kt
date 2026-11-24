package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.newoether.agora.util.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Owns the attachment ingestion mode: whether attachments prefer a Local Sandbox path link with
 * inline text as fallback, or always embed extracted text. The storage route is fixed when an
 * attachment is added, so this value only steers future attachments.
 */
internal class SettingsAttachmentStore(private val dataStore: DataStore<Preferences>) {
    val attachmentMode: Flow<String> = dataStore.data.map { it[ATTACHMENT_MODE] ?: Constants.ATTACHMENT_MODE_SANDBOX_LINK }

    suspend fun saveAttachmentMode(mode: String) {
        dataStore.edit { it[ATTACHMENT_MODE] = mode }
    }
}
