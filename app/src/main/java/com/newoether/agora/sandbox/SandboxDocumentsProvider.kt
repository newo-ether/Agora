package com.newoether.agora.sandbox

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.newoether.agora.AgoraApplication
import com.newoether.agora.R
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Locale
import kotlinx.coroutines.runBlocking

/** Exposes the selected sandbox through the process-shared manager's guest path resolver. */
class SandboxDocumentsProvider : DocumentsProvider() {
    companion object {
        const val ROOT_ID = "agora_sandbox"
        const val ROOT_DOCUMENT_ID = "root"
        const val DEFAULT_AUTHORITY_SUFFIX = "documents"

        private const val LEGACY_HOME_DOCUMENT_ID = "home"
        private const val HOME_VIRTUAL_PATH = "home/agora"

        internal fun rootDocumentId(distribution: SandboxDistribution): String =
            if (distribution == SandboxDistribution.ALPINE) ROOT_DOCUMENT_ID else "${distribution.id}-root"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_AVAILABLE_BYTES,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }

    // Tests supply discovery only; all document operations still use the production resolver.
    internal var managerProvider: (() -> SandboxManager?)? = null

    // Android creates providers before Application.onCreate. Do not wait for startup here.
    override fun onCreate(): Boolean = context != null

    private fun managerOrNull(): SandboxManager? {
        managerProvider?.let { return it() }
        val application = context?.applicationContext as? AgoraApplication ?: return null
        val factory = runBlocking { application.awaitContainer() }?.sandboxManagerFactory ?: return null
        return if (factory.isAvailable()) factory.create() else null
    }

    private fun manager(): SandboxManager = managerOrNull()
        ?: throw FileNotFoundException("Sandbox is unavailable")

    // Report immediately writable space, without counting theoretically evictable cache.
    @SuppressLint("UsableSpace")
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection?.toList()?.toTypedArray() ?: DEFAULT_ROOT_PROJECTION
        return MatrixCursor(columns).apply {
            val manager = managerOrNull() ?: return@apply
            val distribution = manager.distribution.value
            val root = manager.resolveHostFile("/", distribution) ?: return@apply
            if (!root.isDirectory) return@apply
            newRow().apply {
                add(Root.COLUMN_ROOT_ID, ROOT_ID)
                add(Root.COLUMN_DOCUMENT_ID, rootDocumentId(distribution))
                add(Root.COLUMN_TITLE, "Agora")
                add(Root.COLUMN_SUMMARY, "${distribution.displayName} /")
                add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY)
                add(Root.COLUMN_MIME_TYPES, "*/*")
                add(Root.COLUMN_AVAILABLE_BYTES, root.usableSpace)
            }
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val columns = projection?.toList()?.toTypedArray() ?: DEFAULT_DOCUMENT_PROJECTION
        val (distribution, virtualPath) = parseDocumentId(documentId)
        val file = fileForVirtualPath(manager(), distribution, virtualPath)
        return MatrixCursor(columns).apply {
            includeFile(this, documentId, virtualPath, file)
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection?.toList()?.toTypedArray() ?: DEFAULT_DOCUMENT_PROJECTION
        val (distribution, parentVirtualPath) = parseDocumentId(parentDocumentId)
        val manager = manager()
        val parent = fileForVirtualPath(manager, distribution, parentVirtualPath)
        if (!parent.isDirectory) throw FileNotFoundException("Not a directory: $parentDocumentId")

        return MatrixCursor(columns).apply {
            parent.listFiles().orEmpty().mapNotNull { child ->
                // Stat guest targets through the same resolver as shell/file tools, never host links.
                runCatching {
                    val path = appendVirtualPath(parentVirtualPath, child.name)
                    child.name to fileForVirtualPath(manager, distribution, path)
                }.getOrNull()
            }.sortedWith(compareBy<Pair<String, File>> { !it.second.isDirectory }
                .thenBy { it.first.lowercase(Locale.ROOT) }).forEach { (name, file) ->
                includeFile(
                    this,
                    appendDocumentId(parentDocumentId, name),
                    appendVirtualPath(parentVirtualPath, name),
                    file,
                )
            }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val (distribution, path) = parseDocumentId(documentId)
        val file = fileForVirtualPath(manager(), distribution, path)
        if (file.isDirectory) throw FileNotFoundException("Cannot open directory as file")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        validateDisplayName(displayName)
        val (distribution, parentPath) = parseDocumentId(parentDocumentId)
        val path = appendVirtualPath(parentPath, displayName)
        val child = entryForVirtualPath(manager(), distribution, path, requireExists = false)
        if (Files.exists(child.toPath(), NOFOLLOW_LINKS)) {
            throw FileNotFoundException("File already exists: $displayName")
        }
        val created = if (mimeType == Document.MIME_TYPE_DIR) child.mkdir() else child.createNewFile()
        if (!created) throw FileNotFoundException("Unable to create: $displayName")
        return appendDocumentId(parentDocumentId, displayName)
    }

    override fun deleteDocument(documentId: String) {
        val (distribution, path) = parseDocumentId(documentId)
        if (isProtectedRoot(path)) throw FileNotFoundException("Cannot delete sandbox root")
        val entry = entryForVirtualPath(manager(), distribution, path)
        deleteSandboxTree(entry)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        validateDisplayName(displayName)
        val (distribution, path) = parseDocumentId(documentId)
        if (isProtectedRoot(path)) throw FileNotFoundException("Cannot rename sandbox root")
        val source = entryForVirtualPath(manager(), distribution, path)
        val target = File(source.parentFile, displayName)
        if (Files.exists(target.toPath(), NOFOLLOW_LINKS) || !source.renameTo(target)) {
            throw FileNotFoundException("Unable to rename document")
        }
        return if (':' in documentId) {
            val namespace = documentId.substringBefore(':')
            val suffix = documentId.substringAfter(':').substringBeforeLast('/', "")
            "$namespace:${appendVirtualPath(suffix, displayName)}"
        } else {
            throw FileNotFoundException("Cannot rename sandbox root")
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val (parentDistribution, parent) = parseDocumentId(parentDocumentId)
        val (childDistribution, child) = parseDocumentId(documentId)
        if (parentDistribution != childDistribution || child == parent) return false
        return if (parent.isEmpty()) child.isNotEmpty() else child.startsWith("$parent/")
    }

    private fun includeFile(cursor: MatrixCursor, documentId: String, virtualPath: String, file: File) {
        if (!file.exists()) throw FileNotFoundException("Document does not exist: $documentId")
        var flags = if (isProtectedRoot(virtualPath)) 0
            else Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        flags = if (file.isDirectory) flags or Document.FLAG_DIR_SUPPORTS_CREATE
            else flags or Document.FLAG_SUPPORTS_WRITE
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, if (virtualPath.isEmpty()) "/" else virtualPath.substringAfterLast('/'))
            add(Document.COLUMN_MIME_TYPE, mimeTypeFor(file))
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (file.isFile) file.length() else null)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        }
    }

    private fun parseDocumentId(documentId: String): Pair<SandboxDistribution, String> {
        val namespace = documentId.substringBefore(':')
        val suffix = if (':' in documentId) checkedVirtualPath(documentId.substringAfter(':')) else ""
        // Persisted pre-distribution grants always retain Alpine or shared-home identity.
        if (namespace == LEGACY_HOME_DOCUMENT_ID) {
            return SandboxDistribution.ALPINE to appendVirtualPath(HOME_VIRTUAL_PATH, suffix)
        }
        val distribution = SandboxDistribution.entries.firstOrNull { rootDocumentId(it) == namespace }
            ?: throw FileNotFoundException("Unknown document ID: $documentId")
        return distribution to suffix
    }

    private fun appendDocumentId(parent: String, name: String): String {
        val namespace = parent.substringBefore(':')
        val suffix = if (':' in parent) parent.substringAfter(':') else ""
        return "$namespace:${appendVirtualPath(suffix, name)}"
    }

    private fun fileForVirtualPath(manager: SandboxManager, distribution: SandboxDistribution, path: String): File {
        val file = manager.resolveHostFile("/${checkedVirtualPath(path)}", distribution)
            ?: throw FileNotFoundException("Sandbox is unavailable")
        if (!file.exists()) throw FileNotFoundException("Document does not exist: $path")
        return file
    }

    /** Resolve parents in the guest, then mutate the leaf entry without following its link. */
    private fun entryForVirtualPath(
        manager: SandboxManager,
        distribution: SandboxDistribution,
        path: String,
        requireExists: Boolean = true,
    ): File {
        val checked = checkedVirtualPath(path)
        val parent = fileForVirtualPath(manager, distribution, checked.substringBeforeLast('/', ""))
        if (!parent.isDirectory) throw FileNotFoundException("Parent is not a directory")
        val entry = File(parent, checked.substringAfterLast('/'))
        if (requireExists && !Files.exists(entry.toPath(), NOFOLLOW_LINKS)) {
            throw FileNotFoundException("Document does not exist: $path")
        }
        return entry
    }

    private fun checkedVirtualPath(path: String): String {
        if (path.isEmpty()) return ""
        if (path.startsWith('/') || '\\' in path || '\u0000' in path || ':' in path) {
            throw FileNotFoundException("Invalid document path: $path")
        }
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw FileNotFoundException("Invalid document path: $path")
        }
        return path
    }

    private fun appendVirtualPath(parent: String, child: String): String {
        val checkedChild = checkedVirtualPath(child)
        if (checkedChild.isEmpty()) return checkedVirtualPath(parent)
        return if (parent.isEmpty()) checkedChild else "${checkedVirtualPath(parent)}/$checkedChild"
    }

    private fun isProtectedRoot(path: String): Boolean =
        path.isEmpty() || path == "home" || path == HOME_VIRTUAL_PATH || path == "mnt/shared"

    private fun validateDisplayName(name: String) {
        if (name.isBlank() || '/' in name) throw FileNotFoundException("Invalid file name: $name")
        checkedVirtualPath(name)
    }

    private fun mimeTypeFor(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT))
            ?: "application/octet-stream"
    }
}

/** Open the current sandbox root, falling back to a picker that starts in that distribution. */
fun Context.openSandboxRoot(authority: String = "$packageName.${SandboxDocumentsProvider.DEFAULT_AUTHORITY_SUFFIX}") {
    val rootUri = DocumentsContract.buildRootUri(authority, SandboxDocumentsProvider.ROOT_ID)
    val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(rootUri, "vnd.android.document/root")
        addCategory(Intent.CATEGORY_DEFAULT)
        addFlags(grantFlags)
        if (this@openSandboxRoot !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        startActivity(viewIntent)
    } catch (_: Exception) {
        openSandboxRootPicker(authority)
    }
}

private fun Context.openSandboxRootPicker(authority: String) {
    val distribution = runCatching {
        (applicationContext as? AgoraApplication)?.requireContainer()
            ?.sandboxManagerFactory?.create()?.distribution?.value
    }.getOrNull()
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && distribution != null) {
            val treeUri = DocumentsContract.buildTreeDocumentUri(
                authority, SandboxDocumentsProvider.rootDocumentId(distribution),
            )
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri)
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        if (this@openSandboxRootPicker !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}
