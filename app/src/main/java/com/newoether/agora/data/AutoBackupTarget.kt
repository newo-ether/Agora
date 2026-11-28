package com.newoether.agora.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** One `Agora_backup_*` artifact inside the configured destination. */
internal data class BackupEntry(
    val name: String,
    val lastModified: Long?,
    val uri: Uri,
    val file: File?,
)

/**
 * Destination of automatic backups.
 *
 * The user can pick a plain path or, through the system picker, a Storage Access Framework tree.
 * A SAF tree must be written through [DocumentsContract]; treating it as a path is what silently
 * pushed every backup to Download while erasing the chosen location (issue #211).
 */
internal interface AutoBackupTarget {
    /** Existing backup artifacts, newest first. Ordering uses the timestamped file name. */
    fun entries(): List<BackupEntry>

    /** Local copy of the newest complete backup for incremental reuse, or null when unavailable. */
    fun baseline(): File?

    /** Writes [name] through [write]; false when the provider refused to create the file. */
    suspend fun write(name: String, write: suspend (Uri) -> Unit): Boolean

    /** Releases whatever [baseline] materialized once the export is over. */
    fun releaseBaseline(baseline: File?)

    fun delete(entry: BackupEntry)
}

private const val BACKUP_PREFIX = "Agora_backup_"
private const val BACKUP_SUFFIX = ".agora"
private const val TEMPORARY_SUFFIX = ".agora.tmp"

internal fun isCompleteBackupName(name: String): Boolean =
    name.startsWith(BACKUP_PREFIX) && name.endsWith(BACKUP_SUFFIX)

internal fun isTemporaryBackupName(name: String): Boolean =
    name.startsWith(BACKUP_PREFIX) && name.endsWith(TEMPORARY_SUFFIX)

internal fun isBackupArtifactName(name: String): Boolean =
    isCompleteBackupName(name) || isTemporaryBackupName(name)

internal fun newestCompleteBackup(entries: List<BackupEntry>): BackupEntry? =
    entries.filter { isCompleteBackupName(it.name) }.maxByOrNull { it.name }

/** A destination addressed by a filesystem path. */
internal class FileBackupTarget(private val directory: File) : AutoBackupTarget {
    fun prepare(): Boolean = (directory.exists() && directory.canWrite()) || directory.mkdirs()

    override fun entries(): List<BackupEntry> =
        directory.listFiles { candidate -> candidate.isFile && isBackupArtifactName(candidate.name) }
            .orEmpty()
            .map { file ->
                BackupEntry(file.name, file.lastModified(), Uri.fromFile(file), file)
            }
            .sortedByDescending { it.name }

    override fun baseline(): File? = newestCompleteBackup(entries())?.file

    override suspend fun write(name: String, write: suspend (Uri) -> Unit): Boolean {
        val temporary = File(directory, "$name.tmp")
        try {
            write(Uri.fromFile(temporary))
            if (!temporary.renameTo(File(directory, name))) {
                temporary.delete()
                return false
            }
            return true
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    override fun releaseBaseline(baseline: File?) = Unit

    override fun delete(entry: BackupEntry) {
        entry.file?.delete()
    }
}

/** A destination addressed by a Storage Access Framework tree permission. */
internal class SafBackupTarget(
    private val context: Context,
    treeUri: Uri,
) : AutoBackupTarget {
    private val resolver get() = context.contentResolver
    private val treeDocument: Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )

    override fun entries(): List<BackupEntry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeDocument,
            DocumentsContract.getDocumentId(treeDocument),
        )
        val entries = mutableListOf<BackupEntry>()
        resolver.query(children, PROJECTION, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.stringOrNull(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    ?: continue
                if (!isBackupArtifactName(name)) continue
                val documentId = cursor.stringOrNull(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    ?: continue
                val lastModified = cursor.longOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                entries += BackupEntry(
                    name = name,
                    lastModified = lastModified,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeDocument, documentId),
                    file = null,
                )
            }
        }
        return entries.sortedByDescending { it.name }
    }

    /**
     * The incremental baseline needs random access to the previous archive, which the provider may
     * not offer, so the newest backup is copied into the cache for the duration of the export.
     */
    override fun baseline(): File? {
        val newest = newestCompleteBackup(entries()) ?: return null
        val cached = File(context.cacheDir, BASELINE_CACHE_NAME)
        cached.delete()
        return runCatching {
            resolver.openInputStream(newest.uri).use { input ->
                requireNotNull(input) { "Backup directory is not readable" }
                cached.outputStream().use { output -> input.copyTo(output) }
            }
            cached
        }.getOrElse {
            cached.delete()
            null
        }
    }

    override suspend fun write(name: String, write: suspend (Uri) -> Unit): Boolean {
        val created = DocumentsContract.createDocument(resolver, treeDocument, BACKUP_MIME_TYPE, name)
            ?: return false
        try {
            write(created)
            return true
        } catch (error: Throwable) {
            // A provider cannot rename, so the partial document is removed instead of replaced.
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw error
        }
    }

    override fun releaseBaseline(baseline: File?) {
        if (baseline?.name == BASELINE_CACHE_NAME) baseline.delete()
    }

    override fun delete(entry: BackupEntry) {
        runCatching { DocumentsContract.deleteDocument(resolver, entry.uri) }
    }

    private companion object {
        const val BASELINE_CACHE_NAME = "auto_backup_baseline.agora"
        const val BACKUP_MIME_TYPE = "application/octet-stream"
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

private fun android.database.Cursor.stringOrNull(column: String): String? {
    val index = getColumnIndex(column).takeIf { it >= 0 } ?: return null
    return if (isNull(index)) null else getString(index)
}

private fun android.database.Cursor.longOrNull(column: String): Long? {
    val index = getColumnIndex(column).takeIf { it >= 0 } ?: return null
    return if (isNull(index)) null else getLong(index)
}
