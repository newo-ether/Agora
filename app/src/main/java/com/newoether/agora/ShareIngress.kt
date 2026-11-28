package com.newoether.agora

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.newoether.agora.util.AttachmentFiles
import com.newoether.agora.util.FileValidator
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Extra that carries the staged share request from the trampoline to [MainActivity]. */
internal const val EXTRA_SHARE_REQUEST = "com.newoether.agora.extra.SHARE_REQUEST"

/** One file copied out of a share grant into app-private cache. */
data class SharedAttachmentSource(
    val path: String,
    val fileName: String?,
    val mimeType: String?,
    val size: Long,
)

/** One share invocation. Equal contents remain separate user actions, as with selected text. */
data class ShareRequest(
    val id: String,
    val text: String?,
    val attachments: List<SharedAttachmentSource>,
    val rejectedMimeTypes: List<String?> = emptyList(),
) {
    val hasContent: Boolean get() = text != null || attachments.isNotEmpty()
}

internal data class ShareIntentPayload(
    val text: String?,
    val uris: List<Uri>,
)

/** Pending share requests; durable attachments remain owned by the conversation Composer. */
class ShareInbox {
    private val pending = MutableStateFlow<List<ShareRequest>>(emptyList())
    val requests: StateFlow<List<ShareRequest>> = pending.asStateFlow()

    fun enqueue(request: ShareRequest) {
        pending.value = pending.value + request
    }

    fun consume(requestId: String) {
        pending.value = pending.value.filterNot { it.id == requestId }
    }

    fun save(): Bundle = Bundle().apply {
        val array = JSONArray()
        pending.value.forEach { array.put(JSONObject(it.toJson())) }
        putString(SAVED_REQUESTS, array.toString())
    }

    fun restore(saved: Bundle?) {
        val raw = saved?.getString(SAVED_REQUESTS) ?: return
        pending.value = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                shareRequestFromJson(array.getJSONObject(index).toString())
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        internal const val SAVED_STATE_KEY = "agora.shareInbox"
        private const val SAVED_REQUESTS = "requests"
    }
}

internal fun shareIntentPayload(intent: Intent?): ShareIntentPayload? {
    val action = intent?.action ?: return null
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
    // Android may supply a styled CharSequence. Preserve its exact plain text, not its spans.
    val text = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }
        .getOrNull()?.takeIf { it.isNotBlank() }
    val streams = if (action == Intent.ACTION_SEND) {
        listOfNotNull(singleSharedStream(intent))
    } else {
        multipleSharedStreams(intent)
    }
    // Senders disagree about where the payload lives; the chooser sometimes uses only ClipData.
    val data = intent.data?.takeIf { it.scheme == "content" || it.scheme == "file" }
    val uris = (streams + intent.clipData.itemUris() + listOfNotNull(data)).distinct()
    if (text == null && uris.isEmpty()) return null
    return ShareIntentPayload(text = text, uris = uris)
}

/** Copies every shared stream into app-private cache before the sender's grant can expire. */
internal suspend fun stageSharedAttachments(
    context: Context,
    payload: ShareIntentPayload,
    requestId: String,
): Pair<List<SharedAttachmentSource>, List<String?>> = withContext(Dispatchers.IO) {
    val directory = File(File(context.cacheDir, SHARE_STAGING_DIRECTORY), requestId)
    pruneStagedShares(File(context.cacheDir, SHARE_STAGING_DIRECTORY), System.currentTimeMillis())
    val rejected = mutableListOf<String?>()
    val staged = payload.uris.mapNotNull { uri ->
        stageSharedAttachment(context, uri, directory, rejected)
    }
    staged to rejected
}

internal fun sharedAttachment(
    source: SharedAttachmentSource,
    allowLocalSandbox: Boolean,
) = FileValidator.inspectAttachment(
    source = source.path,
    fileName = source.fileName,
    mimeType = source.mimeType,
    fileSize = source.size,
    forcedType = null,
    allowLocalSandbox = allowLocalSandbox,
)

private fun stageSharedAttachment(
    context: Context,
    uri: Uri,
    directory: File,
    rejected: MutableList<String?>,
): SharedAttachmentSource? {
    val mimeType = FileValidator.resolveMimeType(context, uri.toString())
    val fileName = FileValidator.resolveFileName(context, uri) ?: uri.lastPathSegment
    val size = FileValidator.resolveFileSize(context, uri)
    val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull() ?: return null
    if (!directory.isDirectory && !directory.mkdirs()) {
        runCatching { input.close() }
        return null
    }
    // One directory per share keeps same-named files from overwriting each other.
    val target = File(directory, "${UUID.randomUUID()}-${AttachmentFiles.sanitizeFileName(fileName)}")
    return when (val copy = AttachmentFiles.copyBounded(input, target, size)) {
        is AttachmentFiles.CopyResult.Success ->
            SharedAttachmentSource(
                path = target.absolutePath,
                fileName = fileName,
                mimeType = mimeType,
                size = copy.bytesCopied,
            )
        AttachmentFiles.CopyResult.TooLarge -> {
            rejected += mimeType
            null
        }
        is AttachmentFiles.CopyResult.Failure -> null
    }
}

private fun pruneStagedShares(root: File, now: Long) {
    root.listFiles()?.forEach { entry ->
        if (now - entry.lastModified() > SHARE_STAGING_MAX_AGE_MS) {
            runCatching { entry.deleteRecursively() }
        }
    }
}

@Suppress("DEPRECATION")
private fun singleSharedStream(intent: Intent): Uri? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }
}.getOrNull()

@Suppress("DEPRECATION")
private fun multipleSharedStreams(intent: Intent): List<Uri> = runCatching {
    val values = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
    }
    values.orEmpty()
}.getOrDefault(emptyList())

private fun ClipData?.itemUris(): List<Uri> = this?.let { clip ->
    (0 until clip.itemCount).mapNotNull { index -> clip.getItemAt(index).uri }
}.orEmpty()

internal fun ShareRequest.toJson(): String = JSONObject().apply {
    put("id", id)
    put("text", text ?: JSONObject.NULL)
    put("rejected", JSONArray().also { array ->
        rejectedMimeTypes.forEach { array.put(it ?: JSONObject.NULL) }
    })
    put("attachments", JSONArray().also { array ->
        attachments.forEach { attachment ->
            array.put(JSONObject().apply {
                put("path", attachment.path)
                put("name", attachment.fileName ?: JSONObject.NULL)
                put("mime", attachment.mimeType ?: JSONObject.NULL)
                put("size", attachment.size)
            })
        }
    })
}.toString()

internal fun shareRequestFromJson(raw: String): ShareRequest? = runCatching {
    val obj = JSONObject(raw)
    val attachments = obj.optJSONArray("attachments")?.let { array ->
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            SharedAttachmentSource(
                path = item.getString("path"),
                fileName = item.stringOrNull("name"),
                mimeType = item.stringOrNull("mime"),
                size = item.optLong("size", 0L),
            )
        }
    }.orEmpty()
    val rejected = obj.optJSONArray("rejected")?.let { array ->
        (0 until array.length()).map { index ->
            if (array.isNull(index)) null else array.optString(index)
        }
    }.orEmpty()
    ShareRequest(
        id = obj.getString("id"),
        text = obj.stringOrNull("text"),
        attachments = attachments,
        rejectedMimeTypes = rejected,
    )
}.getOrNull()

private fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

private const val SHARE_STAGING_DIRECTORY = "shared-inbox"
private const val SHARE_STAGING_MAX_AGE_MS = 24L * 60L * 60L * 1000L
