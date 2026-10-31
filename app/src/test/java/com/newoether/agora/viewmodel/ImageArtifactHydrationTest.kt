package com.newoether.agora.viewmodel

import android.app.Application
import android.graphics.Bitmap
import com.newoether.agora.api.util.Base64FileRegistry
import com.newoether.agora.api.util.convertToOpenAiMessages
import com.newoether.agora.api.util.imageArtifactOccurrences
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.ProviderContextTopologySnapshot
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.ImageArtifactProcessor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageArtifactHydrationTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun uiAndProviderLoadersResolveTheSameLegacyBytesWithoutChangingDurableRows() = runBlocking {
        val file = image(91, 37)
        val bytes = file.readBytes()
        val uri = "content://${app.packageName}.fileprovider/images/${file.name}"
        val row = MessageEntity(
            id = "message", conversationId = "selected", text = "image", images = listOf(uri),
            participant = Participant.USER, status = MessageStatus.SUCCESS,
            timestamp = 1, runId = "run", runSequence = 0,
            attachmentMeta = Json.encodeToString(AttachmentMeta(listOf(
                AttachmentItem(type = "image", imageIndex = 0, mimeType = "image/jpeg"),
            ))),
        )
        val dao = mockk<ChatDao>()
        coEvery { dao.getMessagesByIds(listOf(row.id)) } returns listOf(row)
        coEvery { dao.getProviderContextTopologySnapshot("selected") } returns ProviderContextTopologySnapshot(
            selectedBranchesJson = null,
            messages = listOf(MessageContextTopology(
                id = row.id, conversationId = row.conversationId, parentId = null,
                status = row.status, participant = row.participant, timestamp = row.timestamp,
                modelName = row.modelName, runId = row.runId, runSequence = row.runSequence,
                consumedAtPass = row.consumedAtPass,
            )),
        )
        val repository = ConversationRepository(dao, database = null)
        try {
            val ui = ConversationMessagePayloadHydration(repository, app)
                .loadMessages("selected", listOf(row.id)).single()
            val context = DurableSelectedContextLoader(repository, { it }, app).load(
                DurableSelectedContextRequest("selected", row.id, includeStoredTranscriptions = false),
            )
            val sent = context.messages.single()
            assertEquals(listOf(file.absolutePath), ui.images)
            assertEquals(ui.images, sent.images)
            assertEquals(ui.imageArtifacts, sent.imageArtifacts)
            val artifact = sent.imageArtifacts.getValue(file.absolutePath)
            assertEquals("image/png", artifact.mimeType)
            assertEquals(91, artifact.width)
            assertEquals(37, artifact.height)
            assertEquals(bytes.size.toLong(), artifact.sizeBytes)
            assertEquals(0, artifact.policyVersion)
            assertEquals(row, context.entities.single())
            assertEquals(listOf(uri), row.images)
            assertArrayEquals(bytes, file.readBytes())
            coVerify(exactly = 0) { dao.upsertMessage(any()) }
            coVerify(exactly = 0) { dao.getProviderContextTopologySnapshot(neq("selected")) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun mixedPageSizesRemainPerOccurrenceAndMissingImagesKeepTheirSlots() {
        val first = image(93, 41)
        val second = image(41, 137)
        val missing = File(app.filesDir, "missing-${UUID.randomUUID()}.png").absolutePath
        val source = ChatMessage(
            text = "", participant = Participant.USER,
            images = listOf(first.path, second.path, missing),
            attachmentMeta = AttachmentMeta(listOf(
                AttachmentItem(type = "pdf", imageIndex = 0, pageCount = 2),
                AttachmentItem(type = "image", imageIndex = 2),
            )),
        )
        try {
            val result = ImageArtifactHydration(app).hydrate(source)
            val pages = result.attachmentMeta!!.items[0].imageArtifacts
            assertEquals(listOf(93, 41), pages.map(ImageArtifact::width))
            assertEquals(listOf(41, 137), pages.map(ImageArtifact::height))
            assertEquals(3, result.images.size)
            assertEquals(missing, result.images[2])
            assertTrue(result.imageArtifacts.getValue(missing).unavailable)
            assertTrue(result.attachmentMeta.items[1].unavailable)
        } finally {
            first.delete()
            second.delete()
        }
    }

    @Test
    fun blankRestoredOccurrencesPreserveSeparateKnownDimensions() {
        val one = ImageArtifact("", "image/png", 0, 10, 20, unavailable = true)
        val two = one.copy(width = 30, height = 40)
        val message = ChatMessage(
            text = "", participant = Participant.USER, images = listOf("", ""),
            attachmentMeta = AttachmentMeta(listOf(
                AttachmentItem(type = "pdf", imageIndex = 0, pageCount = 2, imageArtifacts = listOf(one, two)),
            )),
            segments = listOf(MessageSegment(type = "tool", toolImages = listOf(one, two))),
        )
        val result = ImageArtifactHydration(app).hydrate(message)
        assertEquals(2, result.images.size)
        assertEquals(listOf(10, 30), result.attachmentMeta!!.items.single().imageArtifacts.map { it.width })
        assertEquals(listOf(10, 30), result.segments!!.single().toolImages.map { it.width })
        assertTrue(result.segments.single().toolImages.all { it.unavailable })
    }

    @Test
    fun ownedContentUrisRejectTraversalForeignAuthorityAndNonImageRoots() {
        val hydration = ImageArtifactHydration(app)
        for (uri in listOf(
            "content://other.fileprovider/images/picture.png",
            "content://${app.packageName}.fileprovider/images/../private.png",
            "content://${app.packageName}.fileprovider/images/%2e%2e/private.png",
            "content://${app.packageName}.fileprovider/sandbox-home/private.png",
            "content://${app.packageName}.fileprovider/images/picture.png?other=1",
        )) {
            assertThrows(java.io.IOException::class.java) { hydration.canonicalPath(uri) }
        }
    }

    @Test
    fun readableHistoricalMeasurementsRefreshWithoutReencodingOrDurableMutation() {
        val file = image(93, 41)
        try {
            val bytes = file.readBytes()
            for (policy in listOf(0, ImageArtifactProcessor.POLICY_VERSION)) {
                val actual = ImageArtifactProcessor(app).inspect(file.path, policy)
                val stale = actual.copy(width = 2_048, height = 2_048,
                    mimeType = "image/jpeg", sizeBytes = actual.sizeBytes + 17)
                val source = ChatMessage(
                    text = "inspect", participant = Participant.USER, images = listOf(file.path),
                    imageArtifacts = mapOf(file.path to stale),
                    attachmentMeta = AttachmentMeta(listOf(AttachmentItem(
                        type = "image", imageIndex = 0, imageArtifacts = listOf(stale),
                    ))),
                )
                val result = ImageArtifactHydration(app).hydrate(source)
                assertEquals(listOf(actual), result.imageArtifactOccurrences())
                assertEquals(listOf(actual), result.attachmentMeta!!.items.single().imageArtifacts)
                assertEquals(stale, source.imageArtifacts.getValue(file.path))
                assertArrayEquals(bytes, file.readBytes())
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun readableFileChangesRefreshLengthDimensionsAndEncoding() {
        val file = image(93, 41)
        val replacement = image(41, 137, android.graphics.Color.MAGENTA)
        try {
            val old = ImageArtifactProcessor(app).inspect(file.path)
            val bitmap = Bitmap.createBitmap(41, 137, Bitmap.Config.ARGB_8888)
            try {
                replacement.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)) }
            } finally {
                bitmap.recycle()
            }
            file.writeBytes(replacement.readBytes())
            val bytes = file.readBytes()
            val result = ImageArtifactHydration(app).hydrate(ChatMessage(
                text = "", participant = Participant.USER, images = listOf(file.path),
                imageArtifacts = mapOf(file.path to old),
            ))
            val actual = result.imageArtifacts.getValue(file.path)
            assertFalse(actual.unavailable)
            assertEquals(41, actual.width)
            assertEquals(137, actual.height)
            assertEquals("image/jpeg", actual.mimeType)
            assertEquals(bytes.size.toLong(), actual.sizeBytes)
            assertArrayEquals(bytes, file.readBytes())
        } finally {
            file.delete()
            replacement.delete()
        }
    }

    @Test
    fun repeatedPathsRefreshEveryReadableSlotAndRetainUnavailableSlotsInEitherOrder() {
        val file = image(93, 41)
        try {
            val actual = ImageArtifactProcessor(app).inspect(file.path)
            for (badIndex in 0..1) {
                val records = List(2) { index ->
                    actual.copy(width = 2_048, sizeBytes = 1, unavailable = index == badIndex)
                }
                val source = ChatMessage(
                    text = "keep text", participant = Participant.USER, images = List(2) { file.path },
                    imageArtifacts = mapOf(file.path to records.last()),
                    attachmentMeta = AttachmentMeta(listOf(AttachmentItem(
                        type = "pdf", imageIndex = 0, pageCount = 2, unavailable = true,
                        imageArtifacts = records,
                    ))),
                )
                val result = ImageArtifactHydration(app).hydrate(source)
                val artifacts = result.attachmentMeta!!.items.single().imageArtifacts
                assertEquals(records.map { it.unavailable }, artifacts.map { it.unavailable })
                assertEquals(actual, artifacts[1 - badIndex])
                assertEquals(2, result.images.size)
                val sent = projectGenerationInputMessages(listOf(result), true, null, null).single()
                assertEquals(listOf(actual), sent.imageArtifactOccurrences())
                assertEquals(source.text, sent.text)
                assertEquals(records, source.attachmentMeta!!.items.single().imageArtifacts)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun unchangedMeasuredArtifactsKeepTheirIdentityThroughOwnedAliases() {
        val file = image(93, 41)
        try {
            for (policy in listOf(0, ImageArtifactProcessor.POLICY_VERSION)) {
                val artifact = ImageArtifactProcessor(app).inspect(file.path, policy)
                for (reference in listOf(file.path, file.toURI().toString(),
                    "content://${app.packageName}.fileprovider/images/${file.name}")) {
                    val result = ImageArtifactHydration(app).hydrate(ChatMessage(
                        text = "", participant = Participant.USER, images = listOf(reference),
                        imageArtifacts = mapOf(reference to artifact),
                    ))
                    assertEquals(listOf(file.path), result.images)
                    assertEquals(artifact, result.imageArtifacts.getValue(file.path))
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun corruptAndMissingImagesKeepUiSlotsAndAreSilentlyOmittedAtProductionWire() {
        val valid = image(93, 41)
        val corrupt = File(valid.parentFile, "corrupt-${UUID.randomUUID()}.png").apply {
            writeText("not an image")
        }
        val missing = File(valid.parentFile, "missing-${UUID.randomUUID()}.png")
        try {
            val bytes = valid.readBytes()
            for (paths in listOf(
                listOf(valid.path, corrupt.path, missing.path, valid.path),
                listOf(corrupt.path, valid.path, valid.path, missing.path),
            )) {
                val source = ChatMessage(
                    id = "user", text = "ordinary text", participant = Participant.USER, images = paths,
                    attachmentMeta = AttachmentMeta(listOf(AttachmentItem(
                        type = "pdf", imageIndex = 0, pageCount = paths.size,
                    ))),
                )
                val hydrated = ImageArtifactHydration(app).hydrate(source)
                assertEquals(paths, hydrated.images)
                assertEquals(paths.map { it != valid.path },
                    hydrated.imageArtifactOccurrences().map { it!!.unavailable })
                val projected = projectGenerationInputMessages(listOf(hydrated), true, null, null).single()
                assertEquals(List(2) { valid.path }, projected.images)
                assertEquals(source.text, projected.text)
                val registry = Base64FileRegistry()
                val wire = convertToOpenAiMessages(listOf(projected), base64Files = registry)
                assertEquals(2, wire.single().content!!.count { it.type == "image_url" })
                val request = checkNotNull(registry.prepare(Json.encodeToString(wire)))
                request.body.writeTo(Buffer())
                assertArrayEquals(bytes, valid.readBytes())
                assertEquals(paths, source.images)
            }
        } finally {
            valid.delete()
            corrupt.delete()
        }
    }

    @Test
    fun indexedCanonicalAliasesHydrateThroughProductionWireWithoutChangingBytes() {
        val file = image(93, 41)
        try {
            val bytes = file.readBytes()
            val processor = ImageArtifactProcessor(app)
            val references = listOf(file.path, file.toURI().toString(),
                "content://${app.packageName}.fileprovider/images/${file.name}")
            for (policy in listOf(0, ImageArtifactProcessor.POLICY_VERSION)) {
                val measured = processor.inspect(file.path, policy)
                for (reference in references) for (recordedPath in references) {
                    val input = ChatMessage(
                        id = "user", text = "inspect", participant = Participant.USER,
                        images = listOf(reference),
                        attachmentMeta = AttachmentMeta(listOf(AttachmentItem(
                            type = "image", imageIndex = 0,
                            imageArtifacts = listOf(measured.copy(path = recordedPath)),
                        ))),
                    )
                    val hydrated = ImageArtifactHydration(app).hydrate(input)
                    assertEquals(listOf(file.canonicalPath), hydrated.images)
                    assertEquals(listOf(measured), hydrated.imageArtifactOccurrences())
                    val registry = Base64FileRegistry()
                    val converted = convertToOpenAiMessages(
                        projectGenerationInputMessages(listOf(hydrated), true, null, null),
                        base64Files = registry,
                    )
                    val request = checkNotNull(registry.prepare(Json.encodeToString(converted)))
                    val json = Json.parseToJsonElement(Buffer().also(request.body::writeTo).readUtf8()).jsonArray
                    val uri = json.single().jsonObject.getValue("content").jsonArray
                        .map { it.jsonObject }.single { it["type"]?.jsonPrimitive?.content == "image_url" }
                        .getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content
                    assertArrayEquals(bytes, java.util.Base64.getDecoder().decode(uri.substringAfter(";base64,")))
                    assertArrayEquals(bytes, file.readBytes())
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun image(width: Int, height: Int, color: Int = 0): File {
        val directory = File(app.filesDir, "images").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return file
    }
}
