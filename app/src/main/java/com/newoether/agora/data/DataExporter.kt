package com.newoether.agora.data

import android.content.Context
import android.net.Uri
import com.newoether.agora.automation.LoopPolicy
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.SelectedAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream

class DataExporter(
    private val context: Context,
    private val settingsManager: SettingsManager,
    private val memoryManager: MemoryManager,
    private val skillManager: SkillManager,
) {
    companion object {
        private const val SNAPSHOT_PREFIX = "agora-export-snapshot-"
        private const val SNAPSHOT_SUFFIX = ".jsonl"
        private const val SNAPSHOT_CONVERSATION = "C"
        private const val SNAPSHOT_RUN = "R"
        private const val SNAPSHOT_MESSAGE = "M"
        private const val SNAPSHOT_TASK = "T"
        private const val SNAPSHOT_LOOP = "L"
    }

    enum class ExportCategory(val manifestKey: String) {
        CONVERSATIONS("conversations"),
        MEMORIES("memories"),
        SYSTEM_PROMPTS("system_prompts"),
        SETTINGS("settings"),
        API_KEYS("api_keys");

        companion object {
            fun fromManifestKey(key: String): ExportCategory? =
                entries.find { it.manifestKey == key }
        }
    }

    @Serializable
    private data class ExportManifest(
        @SerialName("agora_export_version") val version: Int,
        @SerialName("app_version") val appVersion: String,
        @SerialName("exported_at") val exportedAt: String,
        val categories: List<String>,
        @SerialName("has_api_keys") val hasApiKeys: Boolean = false
    )

    @Serializable
    private data class ExportChatEntity(
        val id: String,
        val title: String,
        val lastUpdated: Long,
        val dataChangedAt: Long = 0L,
        val selectedBranchesJson: String? = null,
        val systemPromptId: String? = null,
        val modelId: String? = null,
        val taskId: String? = null,
        val origin: String = "user",
        val graduated: Boolean = false,
        val selectedRunBranchesJson: String? = null,
        val draftText: String = "",
        val draftAttachments: String? = null,
        val conversationSettings: ConversationSettings? = null,
    )

    @Serializable
    private data class ExportRunEntity(
        val id: String,
        val conversationId: String,
        val parentRunId: String? = null,
        val status: String,
        val startedAt: Long,
        val lastCheckpointAt: Long,
        val stopRequestedAt: Long? = null,
        val endedAt: Long? = null,
        val endReason: String? = null,
        val currentPass: Int = 0,
        val legacyAmbiguous: Boolean = false,
    )

    @Serializable
    private data class ExportTaskEntity(
        val id: String,
        val name: String,
        val prompt: String,
        val systemPrompt: String? = null,
        val systemPromptId: String? = null,
        val modelId: String? = null,
        val cronExpr: String,
        /** One-shot fire instant; null for a recurring (cron) task. */
        val runAt: Long? = null,
        val createdAt: Long,
        val lastRunAt: Long? = null
    )

    @Serializable
    private data class ExportLoopEntity(
        val conversationId: String,
        val intervalMs: Long,
        val prompt: String? = null,
        val cycleCount: Int = 0,
        /** New v2 archives always emit the bounded default for legacy null values. */
        val maxCycles: Int? = LoopPolicy.DEFAULT_MAX_CYCLES,
    )

    @Serializable
    private data class ExportMessageEntity(
        val id: String,
        val conversationId: String,
        val parentId: String? = null,
        val text: String,
        val images: List<String> = emptyList(),
        val thoughts: String? = null,
        val thoughtTitle: String? = null,
        val tokenCount: Int = 0,
        val inputTokenCount: Int? = null,
        val cachedInputTokenCount: Int? = null,
        val cacheWriteInputTokenCount: Int? = null,
        val uncachedInputTokenCount: Int? = null,
        val outputTokenCount: Int? = null,
        val reasoningTokenCount: Int? = null,
        val generationDurationMs: Long? = null,
        val status: String = "SUCCESS",
        val participant: String = "MODEL",
        val timestamp: Long,
        val thoughtTimeMs: Long? = null,
        val modelName: String? = null,
        val toolCallJson: String? = null,
        val attachmentMeta: String? = null,
        val runId: String,
        val runSequence: Long,
        val consumedAtPass: Int? = null,
    )

    data class ExportResult(
        val imagesExported: Int = 0,
        val missingResourceCount: Int = 0,
    )

    /** Captured JSONL spool file plus the byte-slice index describing its layout. */
    private class ExportedSpool(val file: File, val index: ExportSpoolIndex) {
        fun delete() {
            file.delete()
        }
    }

    private fun openImageStream(imgUri: String): java.io.InputStream? {
        val uri = Uri.parse(imgUri)
        // Handle content:// and file:// URIs
        if (uri.scheme == "content" || uri.scheme == "file") {
            return try { context.contentResolver.openInputStream(uri) } catch (_: Exception) { null }
        }
        // Handle bare file paths (from processImages)
        val file = java.io.File(imgUri)
        if (file.exists()) return try { file.inputStream() } catch (_: Exception) { null }
        return null
    }

    private fun mediaSourceKey(source: String): String {
        val raw = source.removePrefix("file://")
        return when {
            source.startsWith("content://") -> source
            source.startsWith("file://") || File(raw).exists() ->
                runCatching { File(raw).canonicalPath }.getOrElse { File(raw).absolutePath }
            else -> source
        }
    }

    private fun archiveMediaEntry(prefix: String, source: String): String {
        val extension = runCatching {
            val withoutQuery = source.substringBefore('?').substringBefore('#')
            withoutQuery.substringAfterLast('.', "")
                .lowercase()
                .takeIf { it.length in 1..10 && it.all(Char::isLetterOrDigit) }
        }.getOrNull()
        return buildString {
            append(prefix)
            append(UUID.randomUUID())
            if (extension != null) {
                append('.')
                append(extension)
            }
        }
    }

    /** Copies one media stream directly into the archive without a heap-sized byte array. */
    private fun copyStreamToZipEntry(
        zip: ZipArchiveOutputStream,
        entryName: String,
        input: InputStream?,
    ): Boolean {
        if (input == null) return false
        return input.use { stream ->
            zip.putArchiveEntry(ZipArchiveEntry(entryName))
            try {
                stream.copyTo(zip) > 0L
            } finally {
                zip.closeArchiveEntry()
            }
        }
    }

    private suspend fun captureConversationSnapshot(
        conversationSettings: Map<String, ConversationSettings>,
    ): ExportedSpool {
        val spool = File.createTempFile(SNAPSHOT_PREFIX, SNAPSHOT_SUFFIX, context.cacheDir)
        try {
            val index = ExportSpoolWriter(spool).use { writer ->
                ConversationExportSnapshotReader(context).readSnapshot { record ->
                    when (record) {
                        is SnapshotRecord.Conversation -> {
                            val conversation = record.entity
                            writer.writeConversation(
                                id = conversation.id,
                                dataChangedAt = conversation.dataChangedAt,
                                json = Json.encodeToString(
                                    ExportChatEntity(
                                        id = conversation.id,
                                        title = conversation.title,
                                        lastUpdated = conversation.lastUpdated,
                                        dataChangedAt = conversation.dataChangedAt,
                                        selectedBranchesJson = conversation.selectedBranchesJson,
                                        systemPromptId = conversation.systemPromptId,
                                        modelId = conversation.modelId,
                                        taskId = conversation.taskId,
                                        origin = conversation.origin,
                                        graduated = conversation.graduated,
                                        selectedRunBranchesJson = conversation.selectedRunBranchesJson,
                                        draftText = conversation.draftText,
                                        draftAttachments = conversation.draftAttachments,
                                        conversationSettings = conversationSettings[conversation.id],
                                    ),
                                ),
                            )
                        }
                        is SnapshotRecord.Run -> {
                            val run = record.entity
                            writer.writeRun(
                                Json.encodeToString(
                                    ExportRunEntity(
                                        id = run.id,
                                        conversationId = run.conversationId,
                                        parentRunId = run.parentRunId,
                                        status = run.status.name,
                                        startedAt = run.startedAt,
                                        lastCheckpointAt = run.lastCheckpointAt,
                                        stopRequestedAt = run.stopRequestedAt,
                                        endedAt = run.endedAt,
                                        endReason = run.endReason?.name,
                                        currentPass = run.currentPass,
                                        legacyAmbiguous = run.legacyAmbiguous,
                                    ),
                                ),
                            )
                        }
                        is SnapshotRecord.Message -> {
                            val message = record.entity
                            writer.writeMessage(
                                Json.encodeToString(
                                    ExportMessageEntity(
                                        id = message.id,
                                        conversationId = message.conversationId,
                                        parentId = message.parentId,
                                        text = message.text,
                                        images = message.images,
                                        thoughts = message.thoughts,
                                        thoughtTitle = message.thoughtTitle,
                                        tokenCount = message.tokenCount,
                                        inputTokenCount = message.inputTokenCount,
                                        cachedInputTokenCount = message.cachedInputTokenCount,
                                        cacheWriteInputTokenCount = message.cacheWriteInputTokenCount,
                                        uncachedInputTokenCount = message.uncachedInputTokenCount,
                                        outputTokenCount = message.outputTokenCount,
                                        reasoningTokenCount = message.reasoningTokenCount,
                                        generationDurationMs = message.generationDurationMs,
                                        status = message.status.name,
                                        participant = message.participant.name,
                                        timestamp = message.timestamp,
                                        thoughtTimeMs = message.thoughtTimeMs,
                                        modelName = message.modelName,
                                        toolCallJson = message.toolCallJson,
                                        attachmentMeta = message.attachmentMeta,
                                        runId = message.runId,
                                        runSequence = message.runSequence,
                                        consumedAtPass = message.consumedAtPass,
                                    ),
                                ),
                            )
                        }
                        is SnapshotRecord.Loop -> {
                            val sanitized = sanitizeImportedLoop(record.entity)
                            writer.writeLoop(
                                Json.encodeToString(
                                    ExportLoopEntity(
                                        conversationId = sanitized.conversationId,
                                        intervalMs = sanitized.intervalMs,
                                        prompt = sanitized.prompt,
                                        cycleCount = sanitized.cycleCount,
                                        maxCycles = sanitized.maxCycles,
                                    ),
                                ),
                            )
                        }
                        is SnapshotRecord.Task -> {
                            val task = record.entity
                            writer.writeTask(
                                Json.encodeToString(
                                    ExportTaskEntity(
                                        id = task.id,
                                        name = task.name,
                                        prompt = task.prompt,
                                        systemPrompt = task.systemPrompt,
                                        systemPromptId = task.systemPromptId,
                                        modelId = task.modelId,
                                        cronExpr = task.cronExpr,
                                        runAt = task.runAt,
                                        createdAt = task.createdAt,
                                        lastRunAt = task.lastRunAt,
                                    ),
                                ),
                            )
                        }
                    }
                }
                writer.finish()
            }
            return ExportedSpool(spool, index)
        } catch (error: Throwable) {
            spool.delete()
            throw error
        }
    }

    /**
     * Writes the captured spool to the archive one conversation slice at a time. Media for a
     * conversation is copied while its slice streams, the entry payload is assembled record by
     * record, and only dedup maps plus the index remain resident across conversations.
     */
    private suspend fun writeConversationArchive(
        zip: ZipArchiveOutputStream,
        spool: ExportedSpool,
        baseline: NativeBackupV5Baseline?,
        unchangedConversationIds: Set<String>,
    ): ExportResult {
        val spoolReader = ExportSpoolReader(spool.file)
        val sourceToArchiveEntry = mutableMapOf<String, String>()
        val copiedBaselineMedia = linkedSetOf<String>()
        val indexEntries = mutableListOf<NativeConversationIndexEntry>()
        var imagesExported = 0
        var missingResourceCount = 0

        fun copySource(
            source: String,
            prefix: String,
            referenced: MutableSet<String>?,
        ): String? {
            if (source.isBlank()) return null
            val sourceKey = mediaSourceKey(source)
            sourceToArchiveEntry[sourceKey]?.let { entry ->
                referenced?.add(entry)
                return entry
            }
            val entry = archiveMediaEntry(prefix, source)
            val copied = try {
                copyStreamToZipEntry(
                    zip = zip,
                    entryName = entry,
                    input = openImageStream(source),
                )
            } catch (_: Exception) {
                false
            }
            return entry.takeIf { copied }?.also {
                sourceToArchiveEntry[sourceKey] = it
                referenced?.add(it)
            }
        }

        fun trackedEntry(prefix: String, referenced: MutableSet<String>): (String) -> String? =
            { source -> copySource(source, prefix, referenced) }

        fun trackedLookup(referenced: MutableSet<String>): (String) -> String? =
            { source ->
                sourceToArchiveEntry[mediaSourceKey(source)]?.also { referenced.add(it) }
            }

        for (conversation in spool.index.conversations) {
            currentCoroutineContext().ensureActive()
            val baselineEntry = baseline?.indexEntry(conversation.id)
                ?.takeIf { conversation.id in unchangedConversationIds }
            if (baselineEntry != null) {
                indexEntries += NativeBackupV5Writer.copyConversationFromBaseline(
                    zip = zip,
                    baseline = baseline,
                    baselineEntry = baselineEntry,
                    copiedMedia = copiedBaselineMedia,
                )
                continue
            }

            val referencedMedia = linkedSetOf<String>()
            val archivedImagesByMessage = mutableMapOf<String, List<String>>()
            val attachmentMetaByMessage = mutableMapOf<String, String?>()
            var archivedDraftAttachments: String? = null

            // First pass over the slice: stream this conversation's media into the archive.
            spoolReader.readRecords(conversation.slice) { type, raw ->
                when (type) {
                    SNAPSHOT_CONVERSATION -> {
                        val attachments = Json.decodeFromString<ExportChatEntity>(raw)
                            .draftAttachments?.let { encoded ->
                                runCatching {
                                    Json.decodeFromString<List<SelectedAttachment>>(encoded)
                                }.getOrNull()
                            } ?: return@readRecords
                        val archived = NativeBackupMediaPolicy.rewriteDraftAttachmentsForExport(
                            attachments = attachments,
                            archiveEntryForSource = trackedEntry(
                                NativeBackupFormat.DRAFT_MEDIA_PREFIX,
                                referencedMedia,
                            ),
                            onMissingResource = { missingResourceCount++ },
                        )
                        archivedDraftAttachments = archived
                            .takeIf(List<SelectedAttachment>::isNotEmpty)
                            ?.let { Json.encodeToString(it) }
                    }
                    SNAPSHOT_MESSAGE -> {
                        val message = Json.decodeFromString<ExportMessageEntity>(raw)
                        val meta = message.attachmentMeta?.let {
                            runCatching { Json.decodeFromString<AttachmentMeta>(it) }.getOrNull()
                        }
                        meta?.items
                            ?.asSequence()
                            ?.filter { it.type == "video" && !it.unavailable }
                            ?.mapNotNull { it.originalUri }
                            ?.forEach { source ->
                                copySource(source, NativeBackupFormat.VIDEO_MEDIA_PREFIX, null)
                            }

                        if (message.images.isNotEmpty()) {
                            val oldToNewImageIndex = mutableMapOf<Int, Int>()
                            archivedImagesByMessage[message.id] = buildList {
                                message.images.forEachIndexed { oldIndex, source ->
                                    copySource(
                                        source,
                                        NativeBackupFormat.IMAGE_MEDIA_PREFIX,
                                        referencedMedia,
                                    )?.let { entry ->
                                        oldToNewImageIndex[oldIndex] = size
                                        add(entry)
                                        imagesExported++
                                    }
                                }
                            }
                            attachmentMetaByMessage[message.id] =
                                NativeBackupMediaPolicy.rewriteAttachmentMetaForExport(
                                    raw = message.attachmentMeta,
                                    originalImageSources = message.images,
                                    oldToNewImageIndex = oldToNewImageIndex,
                                    archiveEntryForSource = trackedLookup(referencedMedia),
                                    onMissingResource = { missingResourceCount++ },
                                )
                        } else if (message.attachmentMeta != null) {
                            attachmentMetaByMessage[message.id] =
                                NativeBackupMediaPolicy.rewriteAttachmentMetaForExport(
                                    raw = message.attachmentMeta,
                                    originalImageSources = emptyList(),
                                    oldToNewImageIndex = emptyMap(),
                                    archiveEntryForSource = trackedLookup(referencedMedia),
                                    onMissingResource = { missingResourceCount++ },
                                )
                        }

                        NativeBackupMediaPolicy.toolImagePaths(message.toolCallJson).forEach { source ->
                            if (copySource(source, NativeBackupFormat.IMAGE_MEDIA_PREFIX, null) != null) {
                                imagesExported++
                            }
                        }
                    }
                }
            }

            // Second pass: assemble the entry from streamed, rewritten records.
            var conversationJson: String? = null
            val runs = mutableListOf<String>()
            val loops = mutableListOf<String>()
            spoolReader.readRecords(conversation.slice) { type, raw ->
                when (type) {
                    SNAPSHOT_CONVERSATION -> {
                        val entity = Json.decodeFromString<ExportChatEntity>(raw)
                        conversationJson = Json.encodeToString(
                            entity.copy(
                                draftAttachments = archivedDraftAttachments
                                    ?: entity.draftAttachments,
                            ),
                        )
                    }
                    SNAPSHOT_RUN -> runs += raw
                    SNAPSHOT_LOOP -> loops += raw
                }
            }
            val messages = spoolReader.recordIterator(conversation.slice, SNAPSHOT_MESSAGE)
                .asSequence()
                .map { raw ->
                    val message = Json.decodeFromString<ExportMessageEntity>(raw)
                    Json.encodeToString(
                        message.copy(
                            images = archivedImagesByMessage[message.id] ?: emptyList(),
                            toolCallJson = NativeBackupMediaPolicy.rewriteToolImagePathsForExport(
                                raw = message.toolCallJson,
                                archiveEntryForSource = trackedLookup(referencedMedia),
                            ),
                            attachmentMeta = if (message.id in attachmentMetaByMessage) {
                                attachmentMetaByMessage[message.id]
                            } else {
                                message.attachmentMeta
                            },
                        ),
                    )
                }
                .iterator()
            indexEntries += NativeBackupV5Writer.writeConversationEntry(
                zip = zip,
                conversationId = conversation.id,
                dataChangedAt = conversation.dataChangedAt,
                conversationJson = requireNotNull(conversationJson),
                runs = runs.iterator(),
                messages = messages,
                loops = loops.iterator(),
                mediaEntries = referencedMedia,
            )
        }

        NativeBackupV5Writer.writeTasksEntry(
            zip = zip,
            tasks = spoolReader.recordIterator(spool.index.taskSlices),
        )
        NativeBackupV5Writer.writeConversationIndex(zip, indexEntries)
        return ExportResult(
            imagesExported = imagesExported,
            missingResourceCount = missingResourceCount,
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun export(
        uri: Uri,
        categories: Set<ExportCategory>,
        includeApiKeys: Boolean,
        baselineFile: File? = null,
        onProgress: (Float) -> Unit = {}
    ): ExportResult = withContext(Dispatchers.IO) {
        val appInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val appVersion = appInfo.versionName ?: "unknown"
        val exportedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

        val manifest = ExportManifest(
            version = NativeBackupFormat.CURRENT_VERSION,
            appVersion = appVersion,
            exportedAt = exportedAt,
            categories = categories.map { it.manifestKey },
            hasApiKeys = includeApiKeys && categories.contains(ExportCategory.API_KEYS)
        )

        var imagesExportedTotal = 0
        var missingResourceCount = 0
        val totalSteps = categories.size + 1 // +1 for manifest
        var completed = 0
        fun step() { completed++; onProgress(completed.toFloat() / totalSteps) }

        val conversationSpool = if (ExportCategory.CONVERSATIONS in categories) {
            captureConversationSnapshot(settingsManager.conversationSettings.first())
        } else {
            null
        }
        val baseline = if (conversationSpool != null) {
            NativeBackupV5Baseline.openOrNull(baselineFile)
        } else {
            null
        }
        try {
            val rawOutput = context.contentResolver.openOutputStream(uri)
                ?: throw IOException("Could not open the selected backup destination")
            rawOutput.use { raw ->
                val zip = ZipArchiveOutputStream(BufferedOutputStream(raw))

                // Manifest
                zip.putArchiveEntry(ZipArchiveEntry(NativeBackupFormat.MANIFEST_ENTRY))
                Json.encodeToStream(manifest, zip)
                zip.closeArchiveEntry()
                step()

                // Conversations
                if (conversationSpool != null) {
                    val unchangedConversationIds = baseline
                        ?.unchangedConversationIds(conversationSpool.index.watermarks)
                        .orEmpty()
                    val archiveResult = writeConversationArchive(
                        zip = zip,
                        spool = conversationSpool,
                        baseline = baseline,
                        unchangedConversationIds = unchangedConversationIds,
                    )
                    imagesExportedTotal += archiveResult.imagesExported
                    missingResourceCount += archiveResult.missingResourceCount
                    step()
                }

            // Memories
            if (ExportCategory.MEMORIES in categories) {
                val activeMemory = memoryManager.getActiveMemory()
                if (activeMemory.isNotEmpty()) {
                    zip.putArchiveEntry(ZipArchiveEntry("memories/active_memory.md"))
                    zip.write(activeMemory.toByteArray())
                    zip.closeArchiveEntry()
                }
                for (file in memoryManager.listFiles()) {
                    val content = memoryManager.readFile(file.name)
                    zip.putArchiveEntry(ZipArchiveEntry("memories/memory_db/${file.name}"))
                    zip.write(content.toByteArray())
                    zip.closeArchiveEntry()
                }
                val metaJson = memoryManager.getMetaJson()
                if (metaJson != "{}") {
                    zip.putArchiveEntry(ZipArchiveEntry("memories/memory_db/memory_meta.json"))
                    zip.write(metaJson.toByteArray())
                    zip.closeArchiveEntry()
                }
                for (file in skillManager.listFiles()) {
                    zip.putArchiveEntry(ZipArchiveEntry("memories/skill_db/${file.name}"))
                    zip.write(skillManager.readFile(file.name).toByteArray())
                    zip.closeArchiveEntry()
                }
                val skillMetaJson = skillManager.getMetaJson()
                if (skillMetaJson != "{}") {
                    zip.putArchiveEntry(ZipArchiveEntry("memories/skill_db/skill_meta.json"))
                    zip.write(skillMetaJson.toByteArray())
                    zip.closeArchiveEntry()
                }
                step()
            }

            // System Prompts
            if (ExportCategory.SYSTEM_PROMPTS in categories) {
                val prompts = settingsManager.systemPrompts.first()
                zip.putArchiveEntry(ZipArchiveEntry(NativeBackupFormat.SYSTEM_PROMPTS_ENTRY))
                Json.encodeToStream(prompts, zip)
                zip.closeArchiveEntry()
                step()
            }

            // Settings
            if (ExportCategory.SETTINGS in categories) {
                val fontFile = settingsManager.customFontPath.first()
                    .takeIf(String::isNotBlank)
                    ?.let(::File)
                    ?.takeIf(File::isFile)
                if (fontFile != null) {
                    zip.putArchiveEntry(ZipArchiveEntry(NativeBackupFormat.CUSTOM_FONT_ENTRY))
                    fontFile.inputStream().use { it.copyTo(zip) }
                    zip.closeArchiveEntry()
                }
                val settings = PortableSettingsArchive.toJsonObject(
                    sm = settingsManager,
                    customFontIncluded = fontFile != null,
                )
                zip.putArchiveEntry(ZipArchiveEntry(NativeBackupFormat.SETTINGS_ENTRY))
                Json.encodeToStream(settings, zip)
                zip.closeArchiveEntry()
                step()
            }

            // API Keys (opt-in)
            if (includeApiKeys && ExportCategory.API_KEYS in categories) {
                val keys = NativeBackupSecretsPolicy.capture(settingsManager)
                zip.putArchiveEntry(ZipArchiveEntry(NativeBackupFormat.SECRETS_ENTRY))
                Json.encodeToStream(keys, zip)
                zip.closeArchiveEntry()
                step()
            }

                zip.finish()
                zip.flush()
            }

            onProgress(1f)
            ExportResult(
                imagesExported = imagesExportedTotal,
                missingResourceCount = missingResourceCount,
            )
        } finally {
            baseline?.close()
            conversationSpool?.delete()
        }
    }
}
