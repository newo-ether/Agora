package com.newoether.agora.viewmodel

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.Participant
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.model.hasMeasuredFactsFor
import com.newoether.agora.util.AttachmentFiles
import com.newoether.agora.util.AttachmentSourceReader
import com.newoether.agora.util.Constants
import com.newoether.agora.util.PdfPageRenderer
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Stateless, one-attempt attachment staging and preparation boundary. */
internal class AttachmentImportProcessor(
    private val app: Application,
    private val normalizeImage: suspend (source: String, outputOwner: Any?) -> String? = { source, owner ->
        ImageProcessor(app).normalizeImage(source, owner)
    },
    private val extractVideoFrames: suspend (
        source: String,
        config: VideoSliceConfig,
        outputOwner: Any?,
    ) -> List<String> = { source, config, owner ->
        ImageProcessor(app).extractVideoFrames(source, config, owner)
    },
    private val measurePixels: suspend (source: String) -> ImagePixelSize? = { source ->
        ImageProcessor(app).measurePixels(source)
    },
    private val renderPdf: suspend (source: String, pages: Set<Int>?, outputOwner: Any?) -> List<String> =
        { source, pages, owner -> PdfPageRenderer.renderAsImages(app, source, pages, owner) },
    private val renderAllPdfPages: suspend (
        source: String,
        maxPages: Int,
        onProgress: (suspend (current: Int, total: Int) -> Unit)?,
        outputOwner: Any?,
    ) -> List<String> = { source, maxPages, onProgress, owner ->
        PdfPageRenderer.renderAllPages(app, source, maxPages, onProgress, owner)
    },
    private val readText: (source: String, maxChars: Int) -> String? =
        { source, maxChars -> AttachmentSourceReader.readText(app, source, maxChars) },
    private val openSource: (source: String) -> InputStream? =
        { source -> AttachmentSourceReader.open(app, source) },
    private val readPdfPageCount: (source: String) -> Int =
        { source -> PdfPageRenderer.getPageCount(app, source) },
    private val readVideoDurationMs: (source: String) -> Long = { source ->
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
        } finally {
            retriever.release()
        }
    },
    private val maxAttachmentBytes: Long = AttachmentFiles.MAX_ATTACHMENT_BYTES,
    private val measureArtifact: suspend (source: String) -> ImageArtifact = { source ->
        ImageProcessor(app).measureArtifact(source)
    },
) {
    sealed interface StageResult {
        data class Success(
            val attachment: SelectedAttachment,
            val createdPaths: List<String>,
            val obsoletePaths: List<String> = emptyList(),
        ) : StageResult
        data object TooLarge : StageResult
        data class Failure(
            val cause: Throwable? = null,
            val attachment: SelectedAttachment? = null,
            val createdPaths: List<String> = emptyList(),
        ) : StageResult
    }

    sealed interface ProcessResult {
        data class Ready(
            val attachment: SelectedAttachment,
            val createdPaths: List<String> = emptyList(),
            val obsoletePaths: List<String> = emptyList(),
        ) : ProcessResult

        data class Failure(val cause: Throwable? = null) : ProcessResult
    }

    /** Measures completed selected-draft bytes without entering staging or pixel processing. */
    suspend fun hydrateDraftAttachment(attachment: SelectedAttachment): SelectedAttachment =
        withContext(Dispatchers.IO) {
            if (attachment.unavailable || attachment.storage.isLocalSandbox ||
                attachment.importState != AttachmentImportState.READY ||
                attachment.type !in setOf("image", "pdf", "video")
            ) return@withContext attachment
            val source = attachment.localPath?.takeIf(String::isNotBlank) ?: return@withContext attachment
            val paths = when (attachment.type) {
                "image" -> listOf(source)
                "pdf" -> attachment.preRenderedPaths.orEmpty()
                else -> attachment.processedFrames.orEmpty()
            }
            if (paths.isEmpty()) return@withContext attachment
            val hydration = ImageArtifactHydration(app)
            val primary = try {
                hydration.canonicalPath(source).takeIf { File(it).isFile && File(it).canRead() }
            } catch (_: Exception) {
                null
            } ?: return@withContext attachment.copy(unavailable = true)
            val hydrated = hydration.hydrate(ChatMessage(
                text = "", participant = Participant.USER, images = paths,
                attachmentMeta = AttachmentMeta(listOf(AttachmentItem(
                    type = attachment.type, imageIndex = 0, pageCount = paths.size,
                    imageArtifacts = attachment.imageArtifacts,
                ))),
            ))
            val item = checkNotNull(hydrated.attachmentMeta).items.single()
            val first = item.imageArtifacts.firstOrNull()
            attachment.copy(
                localPath = if (attachment.type == "image") hydrated.images.single() else primary,
                processedFrames = if (attachment.type == "video") hydrated.images else attachment.processedFrames,
                preRenderedPaths = if (attachment.type == "pdf") hydrated.images else attachment.preRenderedPaths,
                imageArtifacts = item.imageArtifacts,
                mimeType = if (attachment.type == "image") first?.mimeType else attachment.mimeType,
                fileSize = if (attachment.type == "image") first?.sizeBytes else attachment.fileSize,
                pixelWidth = first?.width,
                pixelHeight = first?.height,
                unavailable = item.unavailable,
            )
        }

    suspend fun stage(attachment: SelectedAttachment, outputOwner: Any? = null): StageResult = withContext(Dispatchers.IO) {
        val source = attachment.localPath ?: attachment.uri
        val directory = File(File(app.filesDir, "attachments/staged"), attachment.localId)
        val extension = sourceExtension(attachment)
        val canonicalTarget = File(directory, "source.$extension")
        val target = if (canonicalTarget.exists()) {
            File(directory, "source-${UUID.randomUUID()}.$extension")
        } else {
            canonicalTarget
        }
        val partial = File(directory, "${target.name}.part")
        outputOwner?.let {
            AttachmentFiles.retainLivePath(it, target.absolutePath)
            AttachmentFiles.retainLivePath(it, partial.absolutePath)
        }
        try {
            val input = openSource(source)
                ?: return@withContext StageResult.Failure(
                    IllegalStateException("Unable to open attachment source"),
                )
            when (
                val copy = AttachmentFiles.copyBounded(
                    input = input,
                    target = partial,
                    expectedSize = attachment.fileSize,
                    maxBytes = maxAttachmentBytes,
                )
            ) {
                is AttachmentFiles.CopyResult.Success -> {
                    if (target.exists() && !target.delete()) {
                        return@withContext StageResult.Failure(
                            IllegalStateException("Unable to replace staged attachment"),
                        )
                    }
                    if (!partial.renameTo(target)) {
                        return@withContext StageResult.Failure(
                            IllegalStateException("Unable to promote staged attachment"),
                        )
                    }
                    val obsoletePaths = attachment.localPath
                        ?.takeUnless { it == target.absolutePath }
                        ?.let(::listOf)
                        .orEmpty()
                    val pageCount = if (attachment.type == "pdf") {
                        runCatching { readPdfPageCount(target.absolutePath) }.getOrDefault(0)
                            .takeIf { it > 0 }
                            ?: return@withContext metadataFailure(
                                attachment = attachment,
                                target = target,
                                fileSize = copy.bytesCopied,
                                message = "Unable to read PDF page count",
                            )
                    } else {
                        attachment.pageCount
                    }
                    val videoDurationMs = if (attachment.type == "video") {
                        runCatching { readVideoDurationMs(target.absolutePath) }.getOrDefault(0L)
                    } else {
                        attachment.videoDurationMs
                    }
                    StageResult.Success(
                        attachment = attachment.copy(
                            fileSize = copy.bytesCopied,
                            processedFrames = null,
                            preRenderedPaths = null,
                            imageArtifacts = emptyList(),
                            localPath = target.absolutePath,
                            pageCount = pageCount,
                            videoDurationMs = videoDurationMs,
                            importState = AttachmentImportState.PROCESSING,
                            preparedText = null,
                            unavailable = false,
                        ),
                        createdPaths = listOf(target.absolutePath)
                            .filterNot { attachment.localPath == it },
                        obsoletePaths = obsoletePaths,
                    )
                }
                AttachmentFiles.CopyResult.TooLarge -> StageResult.TooLarge
                is AttachmentFiles.CopyResult.Failure -> StageResult.Failure(copy.cause)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            StageResult.Failure(failure)
        } finally {
            partial.delete()
            if (directory.isDirectory && directory.list().isNullOrEmpty()) directory.delete()
        }
    }

    suspend fun preparePdfPreview(
        attachment: SelectedAttachment,
        outputOwner: Any? = null,
        onProgress: (suspend (current: Int, total: Int) -> Unit)? = null,
    ): ProcessResult = withContext(Dispatchers.IO) {
        if (
            attachment.type != "pdf" ||
            attachment.importState != AttachmentImportState.PROCESSING ||
            attachment.selectedPages != null
        ) {
            return@withContext ProcessResult.Failure(
                IllegalArgumentException("PDF preview requires an unconfigured processing PDF"),
            )
        }
        val stagedPath = attachment.localPath
            ?: return@withContext ProcessResult.Failure(
                IllegalStateException("Attachment has no private staged source"),
            )
        val pageCount = attachment.pageCount
            ?.takeIf { it > 0 }
            ?: return@withContext ProcessResult.Failure(
                IllegalStateException("PDF page count is unavailable"),
            )
        if (!File(stagedPath).isFile) {
            return@withContext ProcessResult.Failure(
                IllegalStateException("Attachment staged source is unavailable"),
            )
        }

        try {
            val pages = renderAllPdfPages(stagedPath, pageCount, onProgress, outputOwner)
            if (pages.size != pageCount) {
                pages.forEach { File(it).delete() }
                ProcessResult.Failure(IllegalStateException("PDF preview rendering failed"))
            } else {
                ProcessResult.Ready(
                    attachment = attachment.copy(
                        preRenderedPaths = pages,
                        imageArtifacts = measureCreatedArtifacts(pages),
                        importState = AttachmentImportState.PROCESSING,
                    ),
                    createdPaths = pages,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ProcessResult.Failure(failure)
        }
    }

    suspend fun process(
        attachment: SelectedAttachment,
        sandboxHomeDir: File? = null,
        outputOwner: Any? = null,
    ): ProcessResult = withContext(Dispatchers.IO) {
        val stagedPath = attachment.localPath
            ?: return@withContext ProcessResult.Failure(
                IllegalStateException("Attachment has no private staged source"),
            )
        val stagedFile = File(stagedPath)
        if (!stagedFile.isFile) {
            return@withContext ProcessResult.Failure(
                IllegalStateException("Attachment staged source is unavailable"),
            )
        }

        try {
            when {
                attachment.storage == AttachmentStorage.LOCAL_SANDBOX_PENDING ->
                    processSandbox(attachment, stagedFile, sandboxHomeDir, outputOwner)
                attachment.type == "image" -> processImage(attachment, stagedPath, outputOwner)
                attachment.type == "video" -> processVideo(attachment, stagedPath, outputOwner)
                attachment.type == "pdf" -> processPdf(attachment, stagedPath, outputOwner)
                attachment.type == "file" -> processFile(attachment, stagedPath)
                else -> ProcessResult.Failure(
                    IllegalArgumentException("Unsupported attachment type: ${attachment.type}"),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ProcessResult.Failure(failure)
        }
    }

    private suspend fun processImage(
        attachment: SelectedAttachment,
        stagedPath: String,
        outputOwner: Any?,
    ): ProcessResult {
        val normalizedPath = normalizeImage(stagedPath, outputOwner)
            ?: return ProcessResult.Failure(IllegalStateException("Image normalization failed"))
        val artifact = try {
            measureCompleteArtifact(normalizedPath)
        } catch (failure: Exception) {
            if (normalizedPath != stagedPath) File(normalizedPath).delete()
            throw failure
        }
        return ProcessResult.Ready(
            attachment = attachment.copy(
                localPath = artifact.path,
                mimeType = artifact.mimeType,
                fileSize = artifact.sizeBytes,
                pixelWidth = artifact.width,
                pixelHeight = artifact.height,
                imageArtifacts = listOf(artifact),
                importState = AttachmentImportState.READY,
            ),
            createdPaths = listOf(artifact.path),
            obsoletePaths = listOf(stagedPath).filterNot { it == normalizedPath },
        )
    }

    private suspend fun processVideo(
        attachment: SelectedAttachment,
        stagedPath: String,
        outputOwner: Any?,
    ): ProcessResult {
        val frameCount = attachment.frameCount
            ?: return ProcessResult.Failure(IllegalStateException("Video slice is not selected"))
        val intervalMs = attachment.sliceIntervalMs
            ?: return ProcessResult.Failure(IllegalStateException("Video slice is not selected"))
        val frames = extractVideoFrames(
            stagedPath,
            VideoSliceConfig(intervalMicros = intervalMs * 1_000L, frameCount = frameCount),
            outputOwner,
        )
        if (frames.isEmpty()) {
            return ProcessResult.Failure(IllegalStateException("Video frame extraction failed"))
        }
        val artifacts = measureCreatedArtifacts(frames)
        return ProcessResult.Ready(
            attachment = attachment.copy(
                processedFrames = frames,
                pixelWidth = artifacts.first().width,
                pixelHeight = artifacts.first().height,
                imageArtifacts = artifacts,
                importState = AttachmentImportState.READY,
            ),
            createdPaths = frames,
        )
    }

    private suspend fun processPdf(
        attachment: SelectedAttachment,
        stagedPath: String,
        outputOwner: Any?,
    ): ProcessResult {
        val previewPaths = attachment.preRenderedPaths.orEmpty()
        val selection = attachment.selectedPages?.sorted()
        val usesPreview = previewPaths.isNotEmpty() && selection != null
        val pages: List<String>
        val artifacts: List<ImageArtifact>
        if (previewPaths.isNotEmpty() && selection != null) {
            if (selection.isEmpty() || selection.any { it !in previewPaths.indices }) {
                return ProcessResult.Failure(IllegalStateException("Selected PDF preview index is invalid"))
            }
            pages = selection.map(previewPaths::get)
            artifacts = pages.map { path ->
                val recorded = attachment.imageArtifacts.firstOrNull { it.path == path }
                try {
                    // Read the selected preview as-is; historical measurements never reject readable pixels.
                    measureCompleteArtifact(path).copy(policyVersion = recorded?.policyVersion ?: 0)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    recorded?.copy(unavailable = true) ?: ImageArtifact(
                        path = path, mimeType = "application/octet-stream", sizeBytes = 0,
                        unavailable = true,
                    )
                }
            }
        } else {
            pages = renderPdf(stagedPath, attachment.selectedPages, outputOwner)
            if (pages.isEmpty()) {
                return ProcessResult.Failure(IllegalStateException("PDF rendering failed"))
            }
            artifacts = measureCreatedArtifacts(pages)
        }
        return ProcessResult.Ready(
            attachment = attachment.copy(
                selectedPages = pages.indices.toSet(),
                preRenderedPaths = pages,
                pixelWidth = artifacts.first().width,
                pixelHeight = artifacts.first().height,
                imageArtifacts = artifacts,
                importState = AttachmentImportState.READY,
            ),
            createdPaths = if (usesPreview) emptyList() else pages,
        )
    }

    private suspend fun measureCompleteArtifact(path: String): ImageArtifact =
        measureArtifact(path).also { artifact ->
            check(artifact.hasMeasuredFactsFor(path)) { "Image artifact metadata is incomplete" }
        }

    private suspend fun measureCreatedArtifacts(paths: List<String>): List<ImageArtifact> = try {
        paths.map { measureCompleteArtifact(it) }
    } catch (failure: Exception) {
        paths.forEach { File(it).delete() }
        throw failure
    }

    private fun processFile(
        attachment: SelectedAttachment,
        stagedPath: String,
    ): ProcessResult {
        val text = readText(stagedPath, Constants.MAX_FILE_CONTENT_READ_LENGTH)
            ?: return ProcessResult.Failure(IllegalStateException("File text read failed"))
        return ProcessResult.Ready(
            attachment.copy(
                importState = AttachmentImportState.READY,
                preparedText = text,
            ),
        )
    }

    private fun processSandbox(
        attachment: SelectedAttachment,
        stagedFile: File,
        sandboxHomeDir: File?,
        outputOwner: Any?,
    ): ProcessResult {
        val home = sandboxHomeDir
            ?: return ProcessResult.Failure(IllegalStateException("Local Sandbox is unavailable"))
        val fileName = AttachmentFiles.sanitizeFileName(attachment.fileName)
        val relativePath = "attachments/${attachment.localId}/$fileName"
        val target = File(home, relativePath)
        outputOwner?.let { AttachmentFiles.retainLivePath(it, target.absolutePath) }
        return when (
            val copy = AttachmentFiles.copyBounded(
                input = stagedFile.inputStream(),
                target = target,
                expectedSize = attachment.fileSize,
                maxBytes = maxAttachmentBytes,
            )
        ) {
            is AttachmentFiles.CopyResult.Success -> ProcessResult.Ready(
                attachment = attachment.copy(
                    fileName = fileName,
                    fileSize = copy.bytesCopied,
                    localPath = target.absolutePath,
                    sandboxPath = "/home/agora/$relativePath",
                    importState = AttachmentImportState.READY,
                ),
                createdPaths = listOf(target.absolutePath),
                obsoletePaths = listOf(stagedFile.absolutePath),
            )
            AttachmentFiles.CopyResult.TooLarge ->
                ProcessResult.Failure(IllegalStateException("Attachment exceeds size limit"))
            is AttachmentFiles.CopyResult.Failure -> ProcessResult.Failure(copy.cause)
        }
    }

    private fun metadataFailure(
        attachment: SelectedAttachment,
        target: File,
        fileSize: Long,
        message: String,
    ) = StageResult.Failure(
        cause = IllegalStateException(message),
        attachment = attachment.copy(
            fileSize = fileSize,
            processedFrames = null,
            preRenderedPaths = null,
            imageArtifacts = emptyList(),
            localPath = target.absolutePath,
            importState = AttachmentImportState.FAILED,
            preparedText = null,
            unavailable = false,
        ),
        createdPaths = listOf(target.absolutePath)
            .filterNot { attachment.localPath == it },
    )

    private fun sourceExtension(attachment: SelectedAttachment): String {
        val preferred = attachment.fileName?.substringAfterLast('.', "")
            ?.takeIf(String::isNotBlank)
            ?: when (attachment.type) {
                "image" -> "img"
                "video" -> "video"
                "pdf" -> "pdf"
                else -> "bin"
            }
        return AttachmentFiles.sanitizeFileName(preferred).take(16)
    }
}
