package com.newoether.agora.ui.chat.message

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.EventListener
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.map.Mapper
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.newoether.agora.R
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.ui.chat.bottombar.AttachmentPreviewRow
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeImageAvailabilityTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val files = mutableListOf<File>()
    private val starts = ConcurrentLinkedQueue<String>()
    private val successes = ConcurrentLinkedQueue<String>()
    private val loadErrors = ConcurrentLinkedQueue<Pair<String, Throwable>>()
    private var imageLoader: ImageLoader? = null
    private val opened = mutableListOf<Pair<List<String>, Int>>()
    private val broken get() = context.getString(R.string.attachment_copy_failed_image)
    private val viewImage get() = context.getString(R.string.tool_view_image)

    @After
    fun cleanup() {
        imageLoader?.shutdown()
        files.forEach(File::delete)
    }

    private fun png(unavailable: Boolean = false): ImageArtifact {
        val file = File.createTempFile("availability-", ".png", context.cacheDir).also(files::add)
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GREEN)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val decoded = checkNotNull(BitmapFactory.decodeFile(file.path))
        assertEquals(64, decoded.width)
        assertEquals(32, decoded.height)
        decoded.recycle()
        return ImageArtifact(file.path, "image/png", file.length(), 64, 32, unavailable = unavailable)
    }

    private fun content(width: Int = 120, body: @Composable () -> Unit) {
        val loader = ImageLoader.Builder(context).components {
            // Android paths are Unix-style; map only this fixture's host-platform paths.
            add(Mapper<String, File> { data, _ -> files.firstOrNull { it.path == data } })
        }.eventListener(object : EventListener {
            override fun onStart(request: ImageRequest) { starts += request.data.toString() }
            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                successes += request.data.toString()
            }
            override fun onError(request: ImageRequest, result: ErrorResult) {
                loadErrors += request.data.toString() to result.throwable
            }
        }).build()
        imageLoader = loader
        compose.setContent {
            CompositionLocalProvider(
                LocalImageLoader provides loader,
                LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = true),
            ) {
                MaterialTheme { Box(Modifier.fillMaxSize()) { Box(Modifier.width(width.dp)) { body() } } }
            }
        }
    }

    private fun awaitLoads(count: Int) {
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            loadErrors.peek()?.let { (path, error) -> throw AssertionError("Image load failed for $path", error) }
            successes.size >= count
        }
        settle()
    }

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }

    private fun tool(images: List<ImageArtifact>, generated: Boolean = false) = MessageSegment(
        type = "tool",
        toolName = if (generated) "image_generate" else "file_view_image",
        toolState = ToolExecutionStates.SUCCEEDED,
        toolResult = "{}",
        toolImages = images,
    )

    @Composable
    private fun detail(segment: MessageSegment) {
        ToolDetailContent(segment, onMediaClick = { urls, index -> opened += urls to index })
    }

    @Composable
    private fun generated(segment: MessageSegment) {
        GeneratedImageThumbnail(
            segment = segment,
            messageId = "generated",
            detailIndex = 0,
            isStreaming = false,
            segmentAppearanceRegistry = androidx.compose.runtime.remember { SegmentAppearanceRegistry() },
            onMediaClick = { urls, index -> opened += urls to index },
        )
    }

    @Composable
    private fun bubble(message: ChatMessage) {
        UserMessageBubble(
            message = message,
            shape = UserBubbleShape(),
            backgroundColor = MaterialTheme.colorScheme.primaryContainer,
            textColor = MaterialTheme.colorScheme.onPrimaryContainer,
            contextAlpha = Modifier,
            isEditing = false,
            sizeAnimationReady = false,
            isLoading = false,
            isEditingAllowed = false,
            showActions = false,
            actionCopyText = null,
            showBranchSelector = false,
            branchIndex = 0,
            totalBranches = 1,
            onEdit = { _, _ -> },
            onCancelEdit = {},
            onStartEdit = {},
            onSelectText = {},
            onSwitchBranch = {},
            onMediaClick = { urls, index -> opened += urls to index },
            onFileContentClick = null,
            onPdfPagesClick = { pages, index -> opened += pages to index },
            onShowInfo = {},
            onShowDelete = {},
            searchHighlight = null,
        )
    }

    @Test
    fun rejectedToolImageKeepsFailureSlotWithoutDecodingAValidFile() {
        val rejected = png(unavailable = true)
        content { detail(tool(listOf(rejected))) }
        settle()
        compose.onNodeWithContentDescription(broken).assertIsDisplayed().assertIsNotEnabled()
            .performTouchInput { click(center) }
        compose.onNodeWithContentDescription(viewImage).assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(starts.contains(rejected.path))
            assertTrue(opened.isEmpty())
        }
    }

    @Test
    fun toolMixedOccurrencesKeepFailureSlotsAndExactFilteredPagerIndices() {
        val first = png()
        val second = png()
        content { detail(tool(listOf(
            first.copy(unavailable = true), first, second.copy(unavailable = true), second, first,
        ), generated = true)) }
        awaitLoads(3)
        compose.onAllNodesWithContentDescription(broken).assertCountEquals(2)
        compose.onAllNodesWithContentDescription(viewImage).assertCountEquals(3)
        repeat(3) { compose.onAllNodesWithContentDescription(viewImage)[it].performClick() }
        compose.runOnIdle {
            val paths = listOf(first.path, second.path, first.path)
            assertEquals(listOf(paths to 0, paths to 1, paths to 2), opened)
        }
    }

    @Test
    fun demandLoadedRejectedToolImageNeverStartsDecodeOrOpens() {
        val rejected = png(unavailable = true)
        var loads = 0
        content {
            CompositionLocalProvider(LocalToolImageLoader provides { _, _ -> loads++; rejected }) {
                detail(tool(emptyList()).copy(toolCallId = "remote-call", toolImageRequestKey = "revision"))
            }
        }
        settle()
        compose.onNodeWithContentDescription(broken).assertIsDisplayed().assertIsNotEnabled()
            .performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(1, loads)
            assertFalse(starts.contains(rejected.path))
            assertTrue(opened.isEmpty())
        }
    }

    @Test
    fun remoteDemandImageWithDefaultAvailabilityStillLoadsAndOpens() {
        val image = png().copy(width = null, height = null)
        content {
            CompositionLocalProvider(LocalToolImageLoader provides { _, _ -> image }) {
                detail(tool(emptyList()).copy(
                    remoteToolSource = "codex", toolCallId = "remote-call", toolImageRequestKey = "revision",
                ))
            }
        }
        awaitLoads(1)
        compose.onNodeWithContentDescription(viewImage).performClick()
        compose.runOnIdle { assertEquals(listOf(listOf(image.path) to 0), opened) }
    }

    @Test
    fun generatedRejectedLeadingOccurrenceKeepsFailureWithoutPromotingLaterImage() {
        val rejected = png(unavailable = true)
        val next = png()
        content(width = 300) { generated(tool(listOf(rejected, next), generated = true)) }
        settle()
        compose.onNodeWithContentDescription(broken).assertIsDisplayed().performTouchInput { click(center) }
        compose.onNodeWithContentDescription(viewImage).assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(starts.contains(rejected.path))
            assertFalse(starts.contains(next.path))
            assertTrue(opened.isEmpty())
        }
    }

    @Test
    fun generatedPagerFiltersRejectedOccurrencesAndRevocationRemovesTheDecodedImage() {
        val image = png()
        val rejected = png(unavailable = true)
        val segment = mutableStateOf(tool(listOf(image, rejected, image), generated = true))
        content(width = 300) { generated(segment.value) }
        awaitLoads(1)
        compose.onNodeWithContentDescription(viewImage).performClick()
        compose.runOnIdle {
            assertEquals(listOf(listOf(image.path, image.path) to 0), opened)
            segment.value = segment.value.copy(toolImages = listOf(image.copy(unavailable = true), rejected, image))
        }
        settle()
        compose.onNodeWithContentDescription(viewImage).assertDoesNotExist()
        compose.onNodeWithContentDescription(broken).assertIsDisplayed().performTouchInput { click(center) }
        compose.runOnIdle {
            assertFalse(starts.contains(rejected.path))
            assertEquals(1, opened.size)
        }
    }

    @Test
    fun artifactOnlyUserRevocationRecomposesAndAbsentStateRemainsAvailable() {
        val image = png()
        val message = mutableStateOf(ChatMessage(
            id = "legacy", text = "", participant = Participant.USER, images = listOf(image.path),
            imageArtifacts = mapOf(image.path to image.copy(unavailable = true)),
        ))
        content(width = 300) { bubble(message.value) }
        settle()
        compose.onNodeWithContentDescription(broken).assertIsDisplayed().performTouchInput { click(center) }
        compose.onAllNodes(hasClickAction() and isEnabled()).assertCountEquals(0)
        compose.runOnIdle {
            assertFalse(starts.contains(image.path))
            assertTrue(opened.isEmpty())
            message.value = message.value.copy(imageArtifacts = emptyMap())
        }
        awaitLoads(1)
        compose.onAllNodes(hasClickAction() and isEnabled()).onFirst().performClick()
        compose.runOnIdle {
            assertEquals(listOf(listOf(image.path) to 0), opened)
            message.value = message.value.copy(imageArtifacts = mapOf(image.path to image.copy(unavailable = true)))
        }
        settle()
        compose.onNodeWithContentDescription(broken).assertIsDisplayed()
        compose.onAllNodes(hasClickAction() and isEnabled()).assertCountEquals(0)
    }

    @Test
    fun storedMixedPdfOpensTheFirstReadablePageAndRetainsRejectedSlots() {
        val rejected = png(unavailable = true)
        val readable = png()
        val artifacts = listOf(rejected, readable, readable)
        val item = AttachmentItem(type = "pdf", imageIndex = 0, pageCount = 3,
            unavailable = true, imageArtifacts = artifacts)
        val message = mutableStateOf(ChatMessage(id = "mixed-pdf", text = "", participant = Participant.USER,
            images = artifacts.map(ImageArtifact::path), attachmentMeta = AttachmentMeta(listOf(item))))
        content(width = 300) { bubble(message.value) }
        settle()
        compose.onAllNodes(hasClickAction() and isEnabled()).assertCountEquals(1).onFirst().performClick()
        compose.runOnIdle {
            assertEquals(listOf(listOf("", readable.path, readable.path) to 1), opened)
            assertFalse(starts.contains(rejected.path))
            message.value = message.value.copy(attachmentMeta = AttachmentMeta(listOf(
                item.copy(imageArtifacts = artifacts.map { it.copy(unavailable = true) })) ))
        }
        settle()
        compose.onAllNodes(hasClickAction() and isEnabled()).assertCountEquals(0)
    }

    @Test
    fun composerMixedPdfOpensReadablePagesWithoutDroppingRejectedSlots() {
        val rejected = png(unavailable = true)
        val readable = png()
        val artifacts = listOf(rejected, readable, readable)
        val attachment = mutableStateOf(SelectedAttachment(uri = "source", type = "pdf", unavailable = true,
            preRenderedPaths = artifacts.map(ImageArtifact::path), imageArtifacts = artifacts))
        content(width = 300) {
            AttachmentPreviewRow(attachments = listOf(attachment.value), editable = false,
                onRemove = {}, onRetry = {}, onAllMediaClick = null, onFileContentClick = null,
                onPdfPagesClick = { pages, index -> opened += pages to index })
        }
        settle()
        compose.onAllNodes(hasClickAction() and isEnabled()).assertCountEquals(1).onFirst().performClick()
        compose.runOnIdle {
            assertEquals(listOf(listOf("", readable.path, readable.path) to 1), opened)
            assertFalse(starts.contains(rejected.path))
            attachment.value = attachment.value.copy(imageArtifacts = artifacts.map { it.copy(unavailable = true) })
        }
        settle()
        compose.onAllNodes(hasClickAction() and isEnabled()).assertCountEquals(0)
    }

    @Test
    fun userMixedOccurrencesPreferOwnDescriptorsAndKeepRepeatedPagerIndices() {
        val image = png()
        val rejected = png(unavailable = true)
        val images = listOf(image.copy(unavailable = true), image, rejected, image)
        val message = ChatMessage(
            id = "mixed", text = "", participant = Participant.USER, images = images.map(ImageArtifact::path),
            attachmentMeta = AttachmentMeta(images.mapIndexed { index, artifact ->
                AttachmentItem(type = "image", imageIndex = index, imageArtifacts = listOf(artifact))
            }),
            imageArtifacts = mapOf(image.path to image.copy(unavailable = true), rejected.path to rejected),
        )
        content(width = 300) { bubble(message) }
        awaitLoads(1)
        val row = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
        row.performScrollToIndex(1)
        compose.onAllNodes(hasClickAction() and isEnabled()).onFirst().performClick()
        row.performScrollToIndex(3)
        compose.onAllNodes(hasClickAction() and isEnabled()).onLast().performClick()
        compose.runOnIdle {
            val paths = listOf(image.path, image.path)
            assertEquals(listOf(paths to 0, paths to 1), opened)
            assertFalse(starts.contains(rejected.path))
        }
    }
}
