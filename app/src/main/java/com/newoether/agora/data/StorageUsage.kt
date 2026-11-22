package com.newoether.agora.data

import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.ManagedAttachmentDirectories
import com.newoether.agora.data.local.StorageUsageDao
import com.newoether.agora.sandbox.sandboxPrivateUsageBytes
import com.newoether.agora.util.DebugLog
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** App-private storage split by owner. Each byte is counted in at most one bucket. */
@Serializable
data class StorageBreakdown(
    val databaseBytes: Long = 0,
    val attachmentBytes: Long = 0,
    val cacheBytes: Long = 0,
    val localModelBytes: Long = 0,
    val sandboxBytes: Long = 0,
) {
    val totalBytes: Long
        get() = databaseBytes + attachmentBytes + cacheBytes + localModelBytes + sandboxBytes
}

/** One top-level conversation with its subagent children rolled in. */
@Serializable
data class ConversationStorage(
    val id: String,
    val title: String,
    val lastUpdated: Long,
    val isTaskExecution: Boolean,
    val messageCount: Int,
    val textBytes: Long,
    val toolBytes: Long,
    val metadataBytes: Long,
    val attachmentBytes: Long,
    val embeddingBytes: Long,
) {
    val totalBytes: Long
        get() = textBytes + toolBytes + metadataBytes + attachmentBytes + embeddingBytes
}

@Serializable
data class StorageReport(
    val breakdown: StorageBreakdown,
    /** Sorted by [ConversationStorage.totalBytes], largest first. */
    val conversations: List<ConversationStorage>,
    val computedAt: Long,
)

/**
 * Message-row figures of one stored conversation (not rolled up), valid while its
 * `dataChangedAt` watermark still equals [dataChangedAt].
 */
@Serializable
internal data class CachedConversationBytes(
    val dataChangedAt: Long,
    val messageCount: Int = 0,
    val textBytes: Long = 0,
    val toolBytes: Long = 0,
    val metadataBytes: Long = 0,
    /** Canonical local paths of the image files its messages reference. */
    val images: List<String> = emptyList(),
)

@Serializable
internal data class StorageUsageSnapshot(
    val version: Int = VERSION,
    val report: StorageReport? = null,
    val conversations: Map<String, CachedConversationBytes> = emptyMap(),
) {
    companion object {
        const val VERSION = 1
    }
}

/**
 * Persisted result of the last Storage scan. It is derived data: a missing, unreadable or
 * older-format file only costs one full rescan.
 */
internal class StorageUsageCache(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(): StorageUsageSnapshot {
        if (!file.isFile) return StorageUsageSnapshot()
        return try {
            json.decodeFromString(StorageUsageSnapshot.serializer(), file.readText())
                .takeIf { it.version == StorageUsageSnapshot.VERSION }
                ?: StorageUsageSnapshot()
        } catch (error: Exception) {
            DebugLog.w("StorageUsage", "Ignoring unreadable storage cache: ${error.message}")
            StorageUsageSnapshot()
        }
    }

    fun write(snapshot: StorageUsageSnapshot) {
        try {
            file.parentFile?.mkdirs()
            val temporary = File(file.path + ".tmp")
            temporary.writeText(json.encodeToString(StorageUsageSnapshot.serializer(), snapshot))
            if (!temporary.renameTo(file)) {
                file.delete()
                if (!temporary.renameTo(file)) temporary.delete()
            }
        } catch (error: Exception) {
            DebugLog.w("StorageUsage", "Storage cache write failed: ${error.message}")
        }
    }
}

/**
 * Computes the Storage page report. File sizes come from the files on disk, walked without
 * following symbolic links. Message rows are rescanned only for conversations whose
 * `dataChangedAt` watermark moved since the cached scan; embeddings and managed attachment
 * directories can change without that watermark, so they are measured on every scan.
 */
internal class StorageUsageCalculator(
    private val filesDir: File,
    private val cacheDir: File,
    private val databaseFile: File,
    private val dao: StorageUsageDao,
    private val cache: StorageUsageCache? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: android.content.Context, database: ChatDatabase) : this(
        filesDir = context.filesDir,
        cacheDir = context.cacheDir,
        databaseFile = context.getDatabasePath(ChatDatabase.DB_NAME),
        dao = database.storageUsageDao(),
        cache = StorageUsageCache(File(context.noBackupFilesDir, CACHE_FILE)),
    )

    /** The last persisted report, shown while a fresh scan runs. */
    fun cachedReport(): StorageReport? = cache?.read()?.report

    suspend fun compute(): StorageReport {
        val previous = cache?.read()?.conversations.orEmpty()
        val (conversations, entries) = computeConversations(previous)
        val report = StorageReport(computeBreakdown(), conversations, now())
        cache?.write(StorageUsageSnapshot(report = report, conversations = entries))
        return report
    }

    internal suspend fun computeBreakdown(): StorageBreakdown {
        val sandboxHome = File(filesDir, SANDBOX_HOME)
        val managedAttachments = File(sandboxHome, "attachments")
        val managedAttachmentBytes = directoryBytes(managedAttachments)

        var attachmentBytes = managedAttachmentBytes
        var localModelBytes = 0L
        filesDir.listFiles().orEmpty().forEach { child ->
            currentCoroutineContext().ensureActive()
            when {
                child.isFile && ATTACHMENT_FILE_PREFIXES.any(child.name::startsWith) ->
                    attachmentBytes += child.length()
                child.isFile && child.name.endsWith(".gguf") -> localModelBytes += child.length()
                child.isDirectory && child.name in ATTACHMENT_DIRECTORIES ->
                    attachmentBytes += directoryBytes(child)
            }
        }

        var sandboxBytes = 0L
        val missing = File(filesDir, ".storage-usage-none")
        filesDir.listFiles().orEmpty()
            .filter { it.isDirectory && ROOTFS_NAME.matches(it.name) }
            .forEach { sandboxBytes += sandboxPrivateUsageBytes(it, missing) }
        sandboxBytes += (sandboxPrivateUsageBytes(missing, sandboxHome) - managedAttachmentBytes)
            .coerceAtLeast(0)

        val databaseBytes = DATABASE_SUFFIXES.sumOf { suffix ->
            File(databaseFile.path + suffix).takeIf(File::isFile)?.length() ?: 0L
        }
        return StorageBreakdown(
            databaseBytes = databaseBytes,
            attachmentBytes = attachmentBytes,
            cacheBytes = directoryBytes(cacheDir),
            localModelBytes = localModelBytes,
            sandboxBytes = sandboxBytes,
        )
    }

    /**
     * Returns the rolled-up conversations and the per-conversation entries to cache. Reading the
     * watermark before the rows means a write that races the scan leaves an older watermark in
     * the cache, so the next scan rescans that conversation instead of trusting stale bytes.
     */
    internal suspend fun computeConversations(
        previous: Map<String, CachedConversationBytes> = emptyMap(),
    ): Pair<List<ConversationStorage>, Map<String, CachedConversationBytes>> {
        val owners = dao.conversations()
        val byId = owners.associateBy { it.id }
        val entries = HashMap<String, CachedConversationBytes>(owners.size)
        val stale = ArrayList<String>()
        owners.forEach { owner ->
            val cached = previous[owner.id]
            if (cached != null && cached.dataChangedAt == owner.dataChangedAt) {
                entries[owner.id] = cached
            } else {
                stale += owner.id
            }
        }
        stale.chunked(QUERY_BATCH).forEach { batch ->
            currentCoroutineContext().ensureActive()
            val rows = dao.messageBytes(batch).associateBy { it.conversationId }
            val images = HashMap<String, MutableSet<String>>()
            dao.imageSources(batch).forEach { row ->
                row.images.forEach { source ->
                    localPath(source)?.let { images.getOrPut(row.conversationId, ::LinkedHashSet).add(it) }
                }
            }
            batch.forEach { id ->
                val row = rows[id]
                entries[id] = CachedConversationBytes(
                    dataChangedAt = byId.getValue(id).dataChangedAt,
                    messageCount = row?.messageCount ?: 0,
                    textBytes = row?.textBytes ?: 0,
                    toolBytes = row?.toolBytes ?: 0,
                    metadataBytes = row?.metadataBytes ?: 0,
                    images = images[id]?.toList().orEmpty(),
                )
            }
        }

        val roots = HashMap<String, String>(owners.size)
        fun rootOf(id: String): String {
            roots[id]?.let { return it }
            var current = id
            val seen = HashSet<String>()
            while (seen.add(current)) {
                val parent = byId[current]?.parentConversationId ?: break
                if (parent !in byId) break
                current = parent
            }
            roots[id] = current
            return current
        }

        val totals = HashMap<String, MutableTotals>()
        fun totals(conversationId: String): MutableTotals? {
            if (conversationId !in byId) return null
            return totals.getOrPut(rootOf(conversationId)) { MutableTotals() }
        }

        // A file shared by several messages of one conversation is counted once for it.
        val files = HashMap<String, MutableSet<String>>()
        entries.forEach { (id, entry) ->
            totals(id)?.apply {
                messageCount += entry.messageCount
                textBytes += entry.textBytes
                toolBytes += entry.toolBytes
                metadataBytes += entry.metadataBytes
            }
            if (entry.images.isNotEmpty()) files.getOrPut(rootOf(id), ::HashSet).addAll(entry.images)
        }
        currentCoroutineContext().ensureActive()
        dao.embeddingBytes().forEach { row -> totals(row.conversationId)?.apply { embeddingBytes += row.bytes } }

        files.forEach { (root, paths) ->
            currentCoroutineContext().ensureActive()
            val bytes = paths.sumOf { path -> File(path).takeIf(File::isFile)?.length() ?: 0L }
            totals.getOrPut(root) { MutableTotals() }.attachmentBytes += bytes
        }

        val managedRoot = File(filesDir, "$SANDBOX_HOME/attachments")
        dao.attachmentDirectories().forEach { claim ->
            if (!ManagedAttachmentDirectories.isDirectory(claim.directory)) return@forEach
            totals(claim.conversationId)?.apply {
                attachmentBytes += directoryBytes(File(managedRoot, claim.directory))
            }
        }

        val conversations = owners.asSequence()
            .filter { rootOf(it.id) == it.id }
            .map { owner ->
                val t = totals[owner.id] ?: MutableTotals()
                ConversationStorage(
                    id = owner.id,
                    title = owner.title,
                    lastUpdated = owner.lastUpdated,
                    isTaskExecution = owner.taskId != null,
                    messageCount = t.messageCount,
                    textBytes = t.textBytes,
                    toolBytes = t.toolBytes,
                    metadataBytes = t.metadataBytes,
                    attachmentBytes = t.attachmentBytes,
                    embeddingBytes = t.embeddingBytes,
                )
            }
            .sortedWith(compareByDescending<ConversationStorage> { it.totalBytes }.thenBy { it.id })
            .toList()
        return conversations to entries
    }

    private fun localPath(source: String): String? {
        if (source.startsWith("content://") || source.startsWith("http://") || source.startsWith("https://")) {
            return null
        }
        val raw = source.removePrefix("file://")
        if (raw.isBlank()) return null
        return runCatching { File(raw).canonicalPath }.getOrNull()
    }

    private class MutableTotals {
        var messageCount = 0
        var textBytes = 0L
        var toolBytes = 0L
        var metadataBytes = 0L
        var attachmentBytes = 0L
        var embeddingBytes = 0L
    }

    companion object {
        private const val CACHE_FILE = "storage-usage.json"
        /** Stays below SQLite's 999 bound-parameter limit on older Android releases. */
        private const val QUERY_BATCH = 500
        private const val SANDBOX_HOME = "sandbox-home"
        private val ATTACHMENT_FILE_PREFIXES = listOf("att_", "vid_", "img_", "pdf_")
        private val ATTACHMENT_DIRECTORIES = setOf(
            "images", "run-inputs", "fork-attachments", "tool-media", "attachments", "markdown-media",
        )
        private val ROOTFS_NAME = Regex("[A-Za-z0-9_]+-rootfs(\\.installing)?")
        private val DATABASE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")
    }
}

/** Regular-file bytes under [directory], not following symbolic links. */
internal suspend fun directoryBytes(directory: File): Long {
    val root = directory.toPath()
    if (!Files.isDirectory(root)) return 0L
    val context = currentCoroutineContext()
    var bytes = 0L
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
            context.ensureActive()
            if (attributes.isRegularFile) bytes += attributes.size()
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(path: Path, error: java.io.IOException): FileVisitResult =
            FileVisitResult.CONTINUE
    })
    return bytes
}
