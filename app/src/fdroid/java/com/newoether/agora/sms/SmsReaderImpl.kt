package com.newoether.agora.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.newoether.agora.data.SmsMessageData
import com.newoether.agora.data.SmsReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of SmsReader for fdroid flavor.
 * Uses ContentResolver to query Telephony.Sms content provider.
 */
class SmsReaderImpl(private val context: Context) : SmsReader {

    override fun isSupported(): Boolean = declaresReadSms()

    override fun hasPermission(): Boolean =
        isSupported() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun readNewMessages(lastSeenId: Long, limit: Int): List<SmsMessageData> =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext emptyList()
            query(
                selection = "${Telephony.Sms._ID} > ?",
                selectionArgs = arrayOf(lastSeenId.toString()),
                sortOrder = "${Telephony.Sms._ID} ASC LIMIT $limit",
            )
        }

    override suspend fun readById(id: Long): SmsMessageData? = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext null
        query(
            selection = "${Telephony.Sms._ID} = ?",
            selectionArgs = arrayOf(id.toString()),
            sortOrder = null,
        ).firstOrNull()
    }

    override suspend fun search(query: String, limit: Int): List<SmsMessageData> =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext emptyList()
            if (query.isBlank()) return@withContext emptyList()
            // No ESCAPE clause on the LIKE, so `%` / `_` in the user's query act as
            // wildcards — acceptable for free-text search against SMS.
            val needle = "%$query%"
            query(
                selection = "${Telephony.Sms.ADDRESS} LIKE ? OR ${Telephony.Sms.BODY} LIKE ?",
                selectionArgs = arrayOf(needle, needle),
                sortOrder = "${Telephony.Sms.DATE} DESC LIMIT $limit",
            )
        }

    override suspend fun currentMaxInboxId(): Long = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext 0L
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.TYPE} = ?",
            arrayOf(Telephony.Sms.MESSAGE_TYPE_INBOX.toString()),
            "${Telephony.Sms._ID} DESC LIMIT 1",
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
    }

    private fun query(
        selection: String,
        selectionArgs: Array<String>,
        sortOrder: String?,
    ): List<SmsMessageData> = try {
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            PROJECTION,
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressCol = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val dateCol = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val bodyCol = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val readCol = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
            buildList {
                while (cursor.moveToNext()) {
                    val body = cursor.getString(bodyCol).orEmpty()
                    add(
                        SmsMessageData(
                            id = cursor.getLong(idCol),
                            address = cursor.getString(addressCol).orEmpty(),
                            date = cursor.getLong(dateCol),
                            preview = body.take(PREVIEW_CHARS),
                            body = body,
                            read = cursor.getInt(readCol) != 0,
                        ),
                    )
                }
            }
        } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Whether READ_SMS is declared in the merged manifest — a build-time property
     * (fdroid flavor declares it, play does not), safe to cache per process.
     */
    private fun declaresReadSms(): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        ).requestedPermissions?.contains(Manifest.permission.READ_SMS) == true
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val PREVIEW_CHARS = 200
        private val PROJECTION = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ,
        )
    }
}