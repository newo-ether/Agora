package com.newoether.agora.data

import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.model.CitationAnchor
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.model.ToolImageAttachment
import com.newoether.agora.model.citationRecords
import com.newoether.agora.model.toMessageSegment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBackupMediaPolicyTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun sharedExcerptCoverRoundTripsAsDisplayMetadataWithoutProviderImages() {
        val selected = SelectedAttachment(uri = "file:///source.txt", type = "file", localPath = "/source.txt",
            preparedText = "Chapter text", sourceTitle = "Chapter Two", sourceBookTitle = "The Book",
            sourceAuthors = "Author", sourceThumbnailPath = "/cover.jpg")
        val exported = NativeBackupMediaPolicy.rewriteDraftAttachmentsForExport(listOf(selected),
            archiveEntryForSource = { "archive/" + it.substringAfterLast('/') }, onMissingResource = {})
        assertEquals("archive/cover.jpg", exported.single().sourceThumbnailPath)
        assertEquals("Chapter Two", exported.single().sourceTitle)
        val restored = NativeBackupMediaPolicy.restoreDraftAttachments(exported,
            restoredPrimaryForArchiveEntry = { "/restored/source.txt" to "file:///restored/source.txt" },
            restoredPathForArchiveEntry = { "/restored/cover.jpg" })
        assertEquals("/restored/cover.jpg", restored.single().sourceThumbnailPath)
        assertEquals("The Book", restored.single().sourceBookTitle)
        val item = AttachmentItem(type = "file", textContent = "Chapter text", sourceTitle = "Chapter Two",
            sourceThumbnailPath = "/cover.jpg")
        val raw = json.encodeToString(AttachmentMeta(listOf(item)))
        val archived = NativeBackupMediaPolicy.rewriteAttachmentMetaForExport(raw,
            oldToNewImageIndex = emptyMap(), archiveEntryForSource = { "archive/cover.jpg" })
        val returned = NativeBackupMediaPolicy.restoreAttachmentMeta(archived, archiveVersion = 5,
            legacyVideoUris = emptyMap(), restoredUriForArchiveEntry = { "file:///restored/cover.jpg" })
        val finalItem = json.decodeFromString<AttachmentMeta>(requireNotNull(returned)).items.single()
        assertEquals("file:///restored/cover.jpg", finalItem.sourceThumbnailPath)
        assertEquals("Chapter Two", finalItem.sourceTitle)
        assertNull(finalItem.imageIndex)
    }

    @Test
    fun legacyNotebookBundleAndCoverSurviveArchiveRoundTrip() {
        val item = AttachmentItem(
            originalUri = "file:///saved/note.zip", type = "file", fileName = "note.zip",
            mimeType = "application/vnd.kalamos.notes-document+zip", textContent = "Saved text",
            notesDocumentId = "old-document", notesRevisionId = "old-revision",
            notesThumbnailPath = "/saved/cover.jpg", imageIndex = 0,
        )
        val raw = json.encodeToString(AttachmentMeta(listOf(item)))
        val archived = NativeBackupMediaPolicy.rewriteAttachmentMetaForExport(raw,
            oldToNewImageIndex = mapOf(0 to 0),
            archiveEntryForSource = { "archive/" + it.substringAfterLast('/') })
        val restored = NativeBackupMediaPolicy.restoreAttachmentMeta(archived, archiveVersion = 5,
            legacyVideoUris = emptyMap(), restoredUriForArchiveEntry = { "file:///restored/" + it.substringAfterLast('/') })
        val result = json.decodeFromString<AttachmentMeta>(requireNotNull(restored)).items.single()
        assertEquals("file:///restored/note.zip", result.originalUri)
        assertEquals("file:///restored/cover.jpg", result.notesThumbnailPath)
        assertEquals("Saved text", result.textContent)
        assertEquals("old-document", result.notesDocumentId)
        assertEquals("old-revision", result.notesRevisionId)
        assertFalse(result.unavailable)
    }

    @Test
    fun nativeBackupExcludesPendingAndRuntimeSandboxDraftPayloads() {
        val private = SelectedAttachment(uri = "private", type = "file")
        val pending = SelectedAttachment(
            uri = "pending",
            type = "file",
            storage = AttachmentStorage.LOCAL_SANDBOX_PENDING,
        )
        val runtime = pending.copy(
            uri = "runtime",
            storage = AttachmentStorage.LOCAL_SANDBOX_RUNTIME,
        )

        assertEquals(
            listOf(private),
            NativeBackupMediaPolicy.exportableDraftAttachments(
                listOf(private, pending, runtime),
            ),
        )
    }

    @Test
    fun attachmentRoundTrip_reindexesPagesAndRestoresEachVideoIndependently() {
        val raw = json.encodeToString(
            AttachmentMeta(
                items = listOf(
                    AttachmentItem(
                        originalUri = "content://device/document.pdf",
                        type = "pdf",
                        imageIndex = 0,
                        pageCount = 3,
                    ),
                    AttachmentItem(
                        originalUri = "content://device/video-a.mp4",
                        type = "video",
                        imageIndex = 3,
                    ),
                    AttachmentItem(
                        originalUri = "content://device/video-b.mp4",
                        type = "video",
                        imageIndex = 4,
                    ),
                ),
            ),
        )
        val archiveEntries = mapOf(
            "content://device/video-a.mp4" to "media/videos/a.mp4",
            "content://device/video-b.mp4" to "media/videos/b.mp4",
        )

        val exported = requireNotNull(
            NativeBackupMediaPolicy.rewriteAttachmentMetaForExport(
                raw = raw,
                oldToNewImageIndex = mapOf(0 to 0, 2 to 1, 3 to 2, 4 to 3),
                archiveEntryForSource = archiveEntries::get,
            ),
        )
        val exportedItems = json.decodeFromString<AttachmentMeta>(exported).items
        assertNull(exportedItems[0].originalUri)
        assertEquals(0, exportedItems[0].imageIndex)
        assertEquals(2, exportedItems[0].pageCount)
        assertEquals("media/videos/a.mp4", exportedItems[1].originalUri)
        assertEquals("media/videos/b.mp4", exportedItems[2].originalUri)

        val restored = requireNotNull(
            NativeBackupMediaPolicy.restoreAttachmentMeta(
                raw = exported,
                archiveVersion = 4,
                oldToNewImageIndex = mapOf(0 to 0, 1 to 1, 2 to 2, 3 to 3),
                legacyVideoUris = emptyMap(),
                restoredUriForArchiveEntry = {
                    when (it) {
                        "media/videos/a.mp4" -> "content://restored/video-a"
                        "media/videos/b.mp4" -> "content://restored/video-b"
                        else -> null
                    }
                },
            ),
        )
        val restoredItems = json.decodeFromString<AttachmentMeta>(restored).items
        assertEquals("content://restored/video-a", restoredItems[1].originalUri)
        assertEquals("content://restored/video-b", restoredItems[2].originalUri)
    }

    @Test
    fun legacyAttachmentRestore_keepsMultipleVideoSlotsDistinct() {
        val raw = json.encodeToString(
            AttachmentMeta(
                items = listOf(
                    AttachmentItem(type = "video", imageIndex = 0),
                    AttachmentItem(type = "video", imageIndex = 2),
                ),
            ),
        )

        val restored = requireNotNull(
            NativeBackupMediaPolicy.restoreAttachmentMeta(
                raw = raw,
                archiveVersion = 3,
                legacyVideoUris = mapOf(
                    0 to "content://restored/legacy-a",
                    2 to "content://restored/legacy-b",
                ),
                restoredUriForArchiveEntry = { null },
            ),
        )
        val items = json.decodeFromString<AttachmentMeta>(restored).items
        assertEquals("content://restored/legacy-a", items[0].originalUri)
        assertEquals("content://restored/legacy-b", items[1].originalUri)
    }

    @Test
    fun unavailableMessageResourcesPreserveOrderTypeNameAndCount() {
        val raw = json.encodeToString(
            AttachmentMeta(
                items = listOf(
                    AttachmentItem(type = "image", fileName = "missing.png", imageIndex = 0),
                    AttachmentItem(
                        originalUri = "content://device/video.mp4",
                        type = "video",
                        fileName = "video.mp4",
                        imageIndex = 1,
                    ),
                    AttachmentItem(
                        type = "file",
                        fileName = "notes.txt",
                        textContent = "notes",
                    ),
                    AttachmentItem(type = "pdf", fileName = "missing.pdf", imageIndex = 2),
                    AttachmentItem(
                        originalUri = "content://old-device/already-missing.mp4",
                        type = "video",
                        fileName = "already-missing.mp4",
                        unavailable = true,
                    ),
                ),
            ),
        )
        var missingCount = 0
        val requestedSources = mutableListOf<String>()

        val exported = requireNotNull(
            NativeBackupMediaPolicy.rewriteAttachmentMetaForExport(
                raw = raw,
                oldToNewImageIndex = emptyMap(),
                archiveEntryForSource = { source ->
                    requestedSources += source
                    "media/videos/video.mp4".takeIf {
                        source == "content://device/video.mp4"
                    }
                },
                onMissingResource = { missingCount++ },
            ),
        )
        val items = json.decodeFromString<AttachmentMeta>(exported).items

        assertEquals(listOf("image", "video", "file", "pdf", "video"), items.map { it.type })
        assertEquals(
            listOf("missing.png", "video.mp4", "notes.txt", "missing.pdf", "already-missing.mp4"),
            items.map { it.fileName },
        )
        assertEquals(listOf(true, false, false, true, true), items.map { it.unavailable })
        assertEquals("media/videos/video.mp4", items[1].originalUri)
        assertNull(items[1].imageIndex)
        assertNull(items[4].originalUri)
        assertEquals(listOf("content://device/video.mp4"), requestedSources)
        assertEquals(3, missingCount)
    }

    @Test
    fun legacyImagesWithoutMetadataBecomeOrderedPlaceholders() {
        var missingCount = 0

        val exported = requireNotNull(
            NativeBackupMediaPolicy.rewriteAttachmentMetaForExport(
                raw = null,
                originalImageSources = listOf(
                    "/private/first.png",
                    "/private/second.jpg",
                    "/private/third.webp",
                ),
                oldToNewImageIndex = mapOf(1 to 0),
                archiveEntryForSource = { null },
                onMissingResource = { missingCount++ },
            ),
        )
        val items = json.decodeFromString<AttachmentMeta>(exported).items

        assertEquals(listOf("first.png", "second.jpg", "third.webp"), items.map { it.fileName })
        assertEquals(listOf(true, false, true), items.map { it.unavailable })
        assertEquals(listOf(null, 0, null), items.map { it.imageIndex })
        assertEquals(2, missingCount)
    }

    @Test
    fun draftPlaceholderRoundTripPreservesPositionAndIgnoresMissingDerivedFiles() {
        val original = listOf(
            SelectedAttachment(
                localId = "image",
                uri = "content://image",
                localPath = "/private/image.png",
                type = "image",
                fileName = "image.png",
            ),
            SelectedAttachment(
                localId = "video",
                uri = "content://video",
                type = "video",
                fileName = "missing.mp4",
            ),
            SelectedAttachment(
                localId = "pdf",
                uri = "content://pdf",
                localPath = "/private/document.pdf",
                type = "pdf",
                fileName = "document.pdf",
                preRenderedPaths = listOf("/private/page.png"),
            ),
        )
        val copied = mapOf(
            "/private/image.png" to "media/drafts/image.png",
            "/private/document.pdf" to "media/drafts/document.pdf",
        )
        var missingCount = 0

        val exported = NativeBackupMediaPolicy.rewriteDraftAttachmentsForExport(
            attachments = original,
            archiveEntryForSource = copied::get,
            onMissingResource = { missingCount++ },
        )

        assertEquals(listOf("image", "video", "pdf"), exported.map { it.localId })
        assertEquals(listOf(false, true, false), exported.map { it.unavailable })
        assertEquals("", exported[1].uri)
        assertNull(exported[1].localPath)
        assertNull(exported[2].preRenderedPaths)
        assertEquals(1, missingCount)

        val restored = NativeBackupMediaPolicy.restoreDraftAttachments(
            attachments = exported,
            restoredPrimaryForArchiveEntry = { entry ->
                when (entry) {
                    "media/drafts/image.png" -> "/restored/image.png" to "file:///restored/image.png"
                    else -> null
                }
            },
            restoredPathForArchiveEntry = { null },
        )

        assertEquals(listOf("image", "video", "pdf"), restored.map { it.localId })
        assertEquals(listOf(false, true, true), restored.map { it.unavailable })
        assertEquals("file:///restored/image.png", restored[0].uri)
        assertTrue(restored.drop(1).all { it.uri.isEmpty() && it.localPath == null })
    }

    @Test
    fun restoreReindexesSurvivingMessageMediaAndKeepsVideoWithMissingThumbnailAvailable() {
        val raw = json.encodeToString(
            AttachmentMeta(
                items = listOf(
                    AttachmentItem(type = "image", fileName = "image.png", imageIndex = 0),
                    AttachmentItem(type = "pdf", fileName = "pages.pdf", imageIndex = 1, pageCount = 2),
                    AttachmentItem(
                        originalUri = "media/videos/video.mp4",
                        type = "video",
                        fileName = "video.mp4",
                        imageIndex = 3,
                    ),
                    AttachmentItem(type = "image", fileName = "missing.png", imageIndex = 4),
                ),
            ),
        )

        val restored = requireNotNull(
            NativeBackupMediaPolicy.restoreAttachmentMeta(
                raw = raw,
                archiveVersion = 4,
                oldToNewImageIndex = mapOf(0 to 0, 2 to 1),
                legacyVideoUris = emptyMap(),
                restoredUriForArchiveEntry = { entry ->
                    "file:///restored/video.mp4".takeIf { entry == "media/videos/video.mp4" }
                },
            ),
        )
        val items = json.decodeFromString<AttachmentMeta>(restored).items

        assertEquals(0, items[0].imageIndex)
        assertEquals(1, items[1].imageIndex)
        assertEquals(1, items[1].pageCount)
        assertEquals("file:///restored/video.mp4", items[2].originalUri)
        assertFalse(items[2].unavailable)
        assertNull(items[2].imageIndex)
        assertTrue(items[3].unavailable)
        assertNull(items[3].originalUri)
    }

    @Test
    fun citationSegmentsSurviveNativeMediaRewriteAndRestore() {
        val answer = "Claim"
        val citation = requireNotNull(
            CitationPolicy.create(
                provider = "test",
                kind = "web",
                title = "Source",
                url = "https://example.com/source",
                anchors = listOf(CitationAnchor(0, answer.length, answer)),
                answerText = answer,
            ),
        )
        val raw = json.encodeToString(listOf(citation.toMessageSegment()))

        val exported = requireNotNull(
            NativeBackupMediaPolicy.rewriteToolImagePathsForExport(raw) {
                error("citation segments have no tool image path")
            },
        )
        val restored = requireNotNull(
            NativeBackupMediaPolicy.restoreToolImagePaths(
                raw = exported,
                archiveVersion = 4,
                restoredPathForArchiveEntry = {
                    error("citation segments have no archive media path")
                },
            ),
        )

        assertEquals(
            listOf(citation),
            json.decodeFromString<List<MessageSegment>>(restored).citationRecords(answer),
        )
    }

    @Test
    fun toolImages_roundTripOnlyCopiedFilesAndDropLegacyDevicePaths() {
        val imageA = ToolImageAttachment(
            path = "/data/user/0/app/tool-a.png",
            mimeType = "image/png",
            sizeBytes = 10,
            sha256 = "a",
        )
        val imageB = imageA.copy(
            path = "/data/user/0/app/tool-b.png",
            sha256 = "b",
        )
        val raw = json.encodeToString(
            listOf(MessageSegment(type = "tool", toolImages = listOf(imageA, imageB))),
        )
        assertEquals(
            listOf(imageA.path, imageB.path),
            NativeBackupMediaPolicy.toolImagePaths(raw),
        )

        val exported = requireNotNull(
            NativeBackupMediaPolicy.rewriteToolImagePathsForExport(raw) { source ->
                "media/images/tool-a.png".takeIf { source == imageA.path }
            },
        )
        val exportedImages = json.decodeFromString<List<MessageSegment>>(exported)
            .single()
            .toolImages
        assertEquals(listOf("media/images/tool-a.png"), exportedImages.map { it.path })

        val restored = requireNotNull(
            NativeBackupMediaPolicy.restoreToolImagePaths(
                raw = exported,
                archiveVersion = 4,
                restoredPathForArchiveEntry = { "C:/private/restored-tool-a.png" },
            ),
        )
        assertEquals(
            listOf("C:/private/restored-tool-a.png"),
            json.decodeFromString<List<MessageSegment>>(restored)
                .single()
                .toolImages
                .map { it.path },
        )

        val legacy = requireNotNull(
            NativeBackupMediaPolicy.restoreToolImagePaths(
                raw = raw,
                archiveVersion = 3,
                restoredPathForArchiveEntry = { error("legacy paths must never be resolved") },
            ),
        )
        assertEquals(
            emptyList<ToolImageAttachment>(),
            json.decodeFromString<List<MessageSegment>>(legacy).single().toolImages,
        )
    }
}
