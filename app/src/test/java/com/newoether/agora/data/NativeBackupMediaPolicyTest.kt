package com.newoether.agora.data

import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.model.CitationAnchor
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.ImageArtifact
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
    fun attachmentRoundTrip_keepsMissingPageSlotsAndRestoresEachVideoIndependently() {
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
        val sources = listOf("/private/a.png", "/private/missing.png", "/private/b.png", "", "")
        val archiveEntries = mapOf(
            sources[0] to "media/images/a", sources[2] to "media/images/b",
            "content://device/video-a.mp4" to "media/videos/a.mp4",
            "content://device/video-b.mp4" to "media/videos/b.mp4",
        )

        val exported = requireNotNull(
            NativeBackupMediaPolicy.rewriteAttachmentMetaForExport(
                raw = raw,
                originalImageSources = sources,
                oldToNewImageIndex = (0..4).associateWith { it },
                archiveEntryForSource = archiveEntries::get,
            ),
        )
        val exportedItems = json.decodeFromString<AttachmentMeta>(exported).items
        assertNull(exportedItems[0].originalUri)
        assertEquals(0, exportedItems[0].imageIndex)
        assertEquals(3, exportedItems[0].pageCount)
        assertTrue(exportedItems[0].unavailable)
        assertEquals(listOf("media/images/a", "", "media/images/b"), exportedItems[0].imageArtifacts.map { it.path })
        assertEquals(3, exportedItems[1].imageIndex)
        assertEquals(4, exportedItems[2].imageIndex)
        assertEquals("media/videos/a.mp4", exportedItems[1].originalUri)
        assertEquals("media/videos/b.mp4", exportedItems[2].originalUri)

        val restored = requireNotNull(
            NativeBackupMediaPolicy.restoreAttachmentMeta(
                raw = exported,
                archiveVersion = 4,
                oldToNewImageIndex = (0..4).associateWith { it },
                originalImageSources = listOf("media/images/a", "", "media/images/b", "", ""),
                restoredPathForArchiveEntry = { entry ->
                    when (entry) {
                        "media/images/a" -> "/restored/a.png"
                        "media/images/b" -> "/restored/b.png"
                        else -> null
                    }
                },
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
        assertEquals(3, restoredItems[0].pageCount)
        assertTrue(restoredItems[0].unavailable)
        assertEquals(listOf("/restored/a.png", "", "/restored/b.png"), restoredItems[0].imageArtifacts.map { it.path })
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
                oldToNewImageIndex = (0..2).associateWith { it },
                archiveEntryForSource = { source ->
                    "media/images/second.jpg".takeIf { source == "/private/second.jpg" }
                },
                onMissingResource = { missingCount++ },
            ),
        )
        val items = json.decodeFromString<AttachmentMeta>(exported).items

        assertEquals(listOf("first.png", "second.jpg", "third.webp"), items.map { it.fileName })
        assertEquals(listOf(true, false, true), items.map { it.unavailable })
        assertEquals(listOf(0, 1, 2), items.map { it.imageIndex })
        assertEquals(listOf("", "media/images/second.jpg", ""), items.flatMap { it.imageArtifacts }.map { it.path })
        assertEquals(2, missingCount)
    }

    @Test
    fun draftPlaceholderRoundTripPreservesPositionAndMissingDerivedSlots() {
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
        assertEquals(listOf(false, true, true), exported.map { it.unavailable })
        assertEquals("", exported[1].uri)
        assertNull(exported[1].localPath)
        assertEquals(listOf(""), exported[2].preRenderedPaths)
        assertTrue(exported[2].imageArtifacts.single().unavailable)
        assertEquals(2, missingCount)

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
    fun restoreKeepsMissingMessageSlotsAndVideoWithMissingThumbnailAvailable() {
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
                oldToNewImageIndex = (0..4).associateWith { it },
                originalImageSources = listOf("media/images/image", "", "media/images/page", "", ""),
                restoredPathForArchiveEntry = { entry ->
                    when (entry) {
                        "media/images/image" -> "/restored/image.png"
                        "media/images/page" -> "/restored/page.png"
                        else -> null
                    }
                },
                legacyVideoUris = emptyMap(),
                restoredUriForArchiveEntry = { entry ->
                    "file:///restored/video.mp4".takeIf { entry == "media/videos/video.mp4" }
                },
            ),
        )
        val items = json.decodeFromString<AttachmentMeta>(restored).items

        assertEquals(0, items[0].imageIndex)
        assertEquals(1, items[1].imageIndex)
        assertEquals(2, items[1].pageCount)
        assertTrue(items[1].unavailable)
        assertEquals(listOf("", "/restored/page.png"), items[1].imageArtifacts.map { it.path })
        assertEquals("file:///restored/video.mp4", items[2].originalUri)
        assertFalse(items[2].unavailable)
        assertEquals(3, items[2].imageIndex)
        assertTrue(items[2].imageArtifacts.single().unavailable)
        assertEquals(4, items[3].imageIndex)
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
    fun messageArtifactsFollowOccurrenceIndicesAcrossDuplicatePathsAndMissingPages() {
        val sources = listOf("/private/a.png", "/private/missing.png", "/private/a.png", "/private/b.png")
        val artifacts = sources.mapIndexed { index, path -> artifact(path, 100 + index, 200 + index) }
        val raw = json.encodeToString(AttachmentMeta(listOf(
            AttachmentItem(type = "pdf", imageIndex = 0, pageCount = 4, imageArtifacts = artifacts),
        )))
        val entries = mapOf("/private/a.png" to "media/images/a", "/private/b.png" to "media/images/b")
        val exported = NativeBackupMediaPolicy.rewriteAttachmentMetaForExport(
            raw = raw, originalImageSources = sources,
            oldToNewImageIndex = sources.indices.associateWith { it }, archiveEntryForSource = entries::get,
        )!!
        val item = json.decodeFromString<AttachmentMeta>(exported).items.single()
        assertEquals(listOf(100, 101, 102, 103), item.imageArtifacts.map { it.width })
        assertEquals(listOf("media/images/a", "", "media/images/a", "media/images/b"), item.imageArtifacts.map { it.path })
        assertEquals(4, item.pageCount)
        assertTrue(item.unavailable)
        assertEquals(artifacts[1].asUnavailablePlaceholder(), item.imageArtifacts[1])
        assertFalse(exported.contains("/private/"))

        val restoredPaths = mapOf("media/images/a" to "/restored/a.png", "media/images/b" to "/restored/b.png")
        val restored = NativeBackupMediaPolicy.restoreAttachmentMeta(
            raw = exported, archiveVersion = 6, oldToNewImageIndex = sources.indices.associateWith { it },
            legacyVideoUris = emptyMap(), restoredUriForArchiveEntry = { null },
            restoredPathForArchiveEntry = restoredPaths::get,
            originalImageSources = listOf("media/images/a", "", "media/images/a", "media/images/b"),
        )!!
        val restoredItem = json.decodeFromString<AttachmentMeta>(restored).items.single()
        assertEquals(item.imageArtifacts.map {
            if (it.unavailable) it else it.copy(path = restoredPaths.getValue(it.path))
        }, restoredItem.imageArtifacts)
        assertTrue(restoredItem.unavailable)
        assertEquals(4, restoredItem.pageCount)
        assertNull(restoredItem.originalUri)
    }

    @Test
    fun draftArtifactsKeepTheirOwnBoundsAndUseOnlyRestoredPrivatePaths() {
        val pages = listOf("/private/page-a.png", "/private/page-b.png")
        val draft = SelectedAttachment(
            uri = "content://source/document", localPath = "/private/document.pdf", type = "pdf",
            preRenderedPaths = pages,
            imageArtifacts = listOf(artifact(pages[0], 300, 900), artifact(pages[1], 1200, 400)),
        )
        val entries = mapOf(
            "/private/document.pdf" to "media/drafts/document.pdf",
            pages[0] to "media/drafts/a", pages[1] to "media/drafts/b",
        )
        val exported = NativeBackupMediaPolicy.rewriteDraftAttachmentsForExport(listOf(draft), entries::get).single()
        assertEquals(listOf("media/drafts/a", "media/drafts/b"), exported.imageArtifacts.map { it.path })
        val paths = mapOf("media/drafts/a" to "/restored/a.png", "media/drafts/b" to "/restored/b.png")
        val restored = NativeBackupMediaPolicy.restoreDraftAttachments(
            listOf(exported), { "/restored/document.pdf" to "file:///restored/document.pdf" }, paths::get,
        ).single()
        assertEquals(paths.values.toList(), restored.preRenderedPaths)
        assertEquals(paths.values.toList(), restored.imageArtifacts.map { it.path })
        assertEquals(listOf(300, 1200), restored.imageArtifacts.map { it.width })
        assertEquals(listOf(900, 400), restored.imageArtifacts.map { it.height })
        assertEquals(draft.imageArtifacts.map { it.sizeBytes }, restored.imageArtifacts.map { it.sizeBytes })
        assertFalse(json.encodeToString(restored).contains("sha256"))
        val missing = NativeBackupMediaPolicy.restoreDraftAttachments(listOf(exported), { null }, { null }).single()
        assertTrue(missing.unavailable)
        assertEquals(draft.imageArtifacts.map { it.asUnavailablePlaceholder() }, missing.imageArtifacts)
        assertFalse(json.encodeToString(missing).contains("media/drafts/"))
    }

    @Test
    fun partialDraftPagesAndVideoFramesKeepSlotsSelectionAndUnavailableAcrossRoundTrips() {
        listOf("pdf", "video").forEach { type ->
            val sources = listOf("/private/a.png", "/private/missing.png", "/private/a.png", "/private/b.png")
            val recorded = sources.mapIndexed { index, path -> artifact(path, 100 + index, 200 + index) }
            val draft = SelectedAttachment(
                uri = "/private/source.$type", localPath = "/private/source.$type", type = type,
                pageCount = 4, frameCount = 4, selectedPages = setOf(1, 3),
                preRenderedPaths = sources.takeIf { type == "pdf" },
                processedFrames = sources.takeIf { type == "video" }, imageArtifacts = recorded,
            )
            val entries = mapOf(draft.uri to "media/drafts/source.$type",
                sources[0] to "media/drafts/a", sources[3] to "media/drafts/b")
            val exported = NativeBackupMediaPolicy.rewriteDraftAttachmentsForExport(listOf(draft), entries::get).single()
            val archivedSlots = listOf("media/drafts/a", "", "media/drafts/a", "media/drafts/b")
            assertEquals(archivedSlots, if (type == "pdf") exported.preRenderedPaths else exported.processedFrames)
            assertEquals(archivedSlots, exported.imageArtifacts.map { it.path })
            assertEquals(draft.selectedPages, exported.selectedPages)
            assertEquals(recorded[1].asUnavailablePlaceholder(), exported.imageArtifacts[1])
            assertTrue(exported.unavailable)
            assertFalse(json.encodeToString(exported).contains("/private/"))

            val restoredPaths = mapOf("media/drafts/a" to "/restored/a.png", "media/drafts/b" to "/restored/b.png")
            val restored = NativeBackupMediaPolicy.restoreDraftAttachments(
                listOf(exported), { "/restored/source.$type" to "file:///restored/source.$type" }, restoredPaths::get,
            ).single()
            val restoredSlots = listOf("/restored/a.png", "", "/restored/a.png", "/restored/b.png")
            assertEquals(restoredSlots, if (type == "pdf") restored.preRenderedPaths else restored.processedFrames)
            assertEquals(restoredSlots, restored.imageArtifacts.map { it.path })
            assertEquals(draft.selectedPages, restored.selectedPages)
            assertEquals(recorded.map { it.width }, restored.imageArtifacts.map { it.width })
            assertTrue(restored.unavailable)

            val reexportEntries = restoredPaths.entries.associate { (entry, path) -> path to entry } +
                ("/restored/source.$type" to "media/drafts/source.$type")
            val reexported = NativeBackupMediaPolicy.rewriteDraftAttachmentsForExport(listOf(restored), reexportEntries::get).single()
            assertEquals(exported, reexported.copy(uri = exported.uri, localPath = exported.localPath))
        }
    }

    @Test
    fun draftPrimarySurvivesWhenAllDerivedPagesAreMissingAndViceVersa() {
        val draft = SelectedAttachment(uri = "/private/source.pdf", localPath = "/private/source.pdf", type = "pdf",
            preRenderedPaths = listOf("/private/page.png"), selectedPages = setOf(0),
            imageArtifacts = listOf(artifact("/private/page.png", 700, 900)))
        val exported = NativeBackupMediaPolicy.rewriteDraftAttachmentsForExport(listOf(draft), { source ->
            "media/drafts/source.pdf".takeIf { source == draft.uri }
        }).single()
        val restored = NativeBackupMediaPolicy.restoreDraftAttachments(listOf(exported), {
            "/restored/source.pdf" to "file:///restored/source.pdf"
        }, { null }).single()
        assertEquals("/restored/source.pdf", restored.localPath)
        assertEquals(listOf(""), restored.preRenderedPaths)
        assertEquals(setOf(0), restored.selectedPages)
        assertTrue(restored.unavailable)

        val onlyPage = NativeBackupMediaPolicy.rewriteDraftAttachmentsForExport(listOf(draft), { source ->
            "media/drafts/page.png".takeIf { source == "/private/page.png" }
        }).single()
        assertTrue(onlyPage.unavailable)
        assertEquals("", onlyPage.uri)
        assertEquals(listOf("media/drafts/page.png"), onlyPage.preRenderedPaths)
        assertFalse(onlyPage.imageArtifacts.single().unavailable)
    }

    @Test
    fun missingMessageArtifactsRetainKnownBoundsWithoutDeviceReferences() {
        val recorded = artifact("/private/lost.png", 800, 600)
        val raw = json.encodeToString(AttachmentMeta(listOf(
            AttachmentItem(type = "image", imageIndex = 0, imageArtifacts = listOf(recorded)),
        )))
        val restored = NativeBackupMediaPolicy.restoreAttachmentMeta(
            raw = raw, archiveVersion = 6, legacyVideoUris = emptyMap(), restoredUriForArchiveEntry = { null },
        )!!
        val item = json.decodeFromString<AttachmentMeta>(restored).items.single()
        assertTrue(item.unavailable)
        assertEquals(listOf(recorded.asUnavailablePlaceholder()), item.imageArtifacts)
        assertFalse(restored.contains("/private/"))
    }

    @Test
    fun malformedToolPayloadFailsTransportWithoutReturningDevicePaths() {
        val malformed = "not-json:/private/tool.png"
        assertTrue(runCatching { NativeBackupMediaPolicy.rewriteToolImagePathsForExport(malformed) { null } }.isFailure)
        assertTrue(runCatching { NativeBackupMediaPolicy.restoreToolImagePaths(malformed, 6) { null } }.isFailure)
    }

    private fun artifact(path: String, width: Int, height: Int) = ImageArtifact(
        path = path, mimeType = "image/png", sizeBytes = 10, width = width, height = height,
        policyVersion = 1,
    )

    @Test
    fun toolImages_roundTripPreservesUnavailableOccurrencesBoundsAndProtocolFields() {
        val imageA = ToolImageAttachment(
            path = "/data/user/0/app/tool-a.png",
            mimeType = "image/png",
            sizeBytes = 10,
            width = 1200,
            height = 800,
        )
        val imageB = imageA.copy(
            path = "/data/user/0/app/tool-b.png",
            width = 333,
            height = 777,
        )
        val segment = MessageSegment(
            type = "tool", toolCallId = "call", toolRoundId = "round", signature = "opaque",
            toolImages = listOf(imageA, imageB, imageB),
        )
        val raw = json.encodeToString(listOf(segment))
        assertEquals(
            listOf(imageA.path, imageB.path, imageB.path),
            NativeBackupMediaPolicy.toolImagePaths(raw),
        )

        var missing = 0
        val exported = requireNotNull(
            NativeBackupMediaPolicy.rewriteToolImagePathsForExport(
                raw = raw,
                onMissingResource = { missing++ },
            ) { source -> "media/images/tool-a.png".takeIf { source == imageA.path } },
        )
        val exportedImages = json.decodeFromString<List<MessageSegment>>(exported)
            .single()
            .toolImages
        assertEquals(listOf("media/images/tool-a.png", "", ""), exportedImages.map { it.path })
        assertEquals(listOf(false, true, true), exportedImages.map { it.unavailable })
        assertEquals(listOf(1200, 333, 333), exportedImages.map { it.width })
        assertEquals(listOf(800, 777, 777), exportedImages.map { it.height })
        assertEquals(listOf(10L, 0L, 0L), exportedImages.map { it.sizeBytes })
        assertFalse(exported.contains("sha256"))
        assertEquals(2, missing)
        assertEquals(listOf("media/images/tool-a.png"), NativeBackupMediaPolicy.toolImagePaths(exported))
        assertFalse(exported.contains("/data/user/0/app"))

        val restored = requireNotNull(
            NativeBackupMediaPolicy.restoreToolImagePaths(
                raw = exported,
                archiveVersion = 4,
                restoredPathForArchiveEntry = { "C:/private/restored-tool-a.png" },
            ),
        )
        assertEquals(
            listOf("C:/private/restored-tool-a.png", "", ""),
            json.decodeFromString<List<MessageSegment>>(restored)
                .single()
                .toolImages
                .map { it.path },
        )

        val restoredSegment = json.decodeFromString<List<MessageSegment>>(restored).single()
        assertEquals(segment.copy(toolImages = restoredSegment.toolImages), restoredSegment)
        assertEquals(exportedImages.drop(1), restoredSegment.toolImages.drop(1))

        val legacy = requireNotNull(
            NativeBackupMediaPolicy.restoreToolImagePaths(
                raw = raw,
                archiveVersion = 3,
                restoredPathForArchiveEntry = { error("legacy paths must never be resolved") },
            ),
        )
        assertEquals(
            listOf(imageA, imageB, imageB).map { it.asUnavailablePlaceholder() },
            json.decodeFromString<List<MessageSegment>>(legacy).single().toolImages,
        )
    }
}
