@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")

package com.newoether.agora.ui.chat

import android.content.Context
import android.content.res.Resources
import android.os.Trace
import android.util.Log
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.node.LayoutNode
import androidx.compose.ui.node.UiApplier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.request.DefaultRequestOptions
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.ui.chat.message.GeneratedImageThumbnail
import com.newoether.agora.ui.chat.message.SegmentAppearanceRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStateCrossfadeSourceContractTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun generatedImageRetainsBothContentsDuringStopTransition() = runTest {
        mockkStatic(Trace::class)
        every { Trace.beginSection(any()) } answers { }
        every { Trace.endSection() } answers { }
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } answers {
            (thirdArg<Throwable?>())?.printStackTrace()
            0
        }
        mockkStatic("androidx.compose.ui.graphics.AndroidPaint_androidKt")
        every { androidx.compose.ui.graphics.Paint() } returns mockk(relaxed = true)
        mockkStatic("androidx.compose.ui.graphics.AndroidPath_androidKt")
        every { androidx.compose.ui.graphics.Path() } returns mockk(relaxed = true)
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = mockk<Context>(relaxed = true)
        val resources = mockk<Resources>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.resources } returns resources
        every { resources.getString(any()) } returns "Image"
        val loader = mockk<ImageLoader>(relaxed = true)
        every { loader.defaults } returns DefaultRequestOptions()
        val segment = mutableStateOf(MessageSegment(
            type = "tool",
            toolName = "generate_image",
            toolState = ToolExecutionStates.RUNNING,
        ))
        val registry = SegmentAppearanceRegistry()
        val root = LayoutNode()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(backgroundScope.coroutineContext + clock)
        val composition = Composition(UiApplier(root), recomposer)
        backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        var frame = 0L
        fun advanceFrame() {
            Snapshot.sendApplyNotifications()
            runCurrent()
            clock.sendFrame(++frame * 16_000_000)
            runCurrent()
        }
        try {
            composition.setContent {
                CompositionLocalProvider(
                    LocalContext provides context,
                    LocalResources provides resources,
                    LocalImageLoader provides loader,
                    LocalInspectionMode provides true,
                    LocalDensity provides Density(1f),
                    LocalLayoutDirection provides LayoutDirection.Ltr,
                    LocalViewConfiguration provides mockk<ViewConfiguration>(relaxed = true),
                ) {
                    GeneratedImageThumbnail(
                        segment = segment.value,
                        messageId = "generated-message",
                        detailIndex = 0,
                        isStreaming = segment.value.toolState == ToolExecutionStates.RUNNING,
                        segmentAppearanceRegistry = registry,
                        onMediaClick = { _, _ -> },
                    )
                }
            }
            repeat(35) { advanceFrame() }
            val crossfade = root.children.single().children.single().children.single()
            assertEquals(1, crossfade.children.size)
            segment.value = segment.value.copy(toolState = ToolExecutionStates.STOPPED)
            val frameAlphas = mutableListOf<List<Float>>()
            val contentCounts = List(25) {
                advanceFrame()
                assertSame(crossfade, root.children.single().children.single().children.single())
                frameAlphas += crossfade.children.map { child ->
                    var alpha = Float.NaN
                    val scope = mockk<GraphicsLayerScope>(relaxed = true)
                    every { scope.alpha = any() } answers { alpha = firstArg() }
                    val modifierField = LayoutNode::class.java.getDeclaredField("pendingModifier")
                    modifierField.isAccessible = true
                    val modifier = modifierField.get(child) as androidx.compose.ui.Modifier
                    modifier.foldIn(Unit) { _, element ->
                        if (element.javaClass.simpleName == "BlockGraphicsLayerElement") {
                            val field = element.javaClass.getDeclaredField("block")
                            field.isAccessible = true
                            @Suppress("UNCHECKED_CAST")
                            (field.get(element) as (GraphicsLayerScope) -> Unit)(scope)
                        }
                    }
                    alpha
                }
                crossfade.children.size
            }
            assertTrue("Stop frame content counts: $contentCounts", 2 in contentCounts)
            assertTrue("Stop must interpolate both layers: $frameAlphas", frameAlphas.any {
                it.size == 2 && it.all { alpha -> alpha > 0f && alpha < 1f }
            })
            assertEquals(1, crossfade.children.size)
        } finally {
            composition.dispose()
            recomposer.close()
            unmockkStatic(Trace::class)
            unmockkStatic(Log::class)
            unmockkStatic("androidx.compose.ui.graphics.AndroidPaint_androidKt")
            unmockkStatic("androidx.compose.ui.graphics.AndroidPath_androidKt")
            Dispatchers.resetMain()
        }
    }

    @Test
    fun bubbleAndFullscreenMediaUseFixedCrossfadeStates() {
        val bubble = source("ui/chat/AttachmentThumbnail.kt")
            .substringAfter("private fun MessageMediaThumbnail(")
        val zoom = source("ui/chat/ZoomableImageItem.kt")

        listOf(bubble, zoom).forEach { media ->
            assertTrue(media.contains("MediaLoadPresentation.LOADING"))
            assertTrue(media.contains("MediaLoadPresentation.LOADED"))
            assertTrue(media.contains("MediaLoadPresentation.FAILED"))
            assertTrue(media.contains("onError ="))
            assertTrue(media.contains("Crossfade("))
            assertTrue(media.contains("modifier = Modifier.fillMaxSize()"))
            assertTrue(media.contains("MEDIA_STATE_CROSSFADE_MILLIS"))
            assertTrue(media.contains("MEDIA_LOADING_INDICATOR_STROKE_WIDTH"))
        }
        assertFalse(bubble.contains("messageMediaThumbnail:$"))
        assertFalse(zoom.contains("if (imageSize == Size.Zero)"))
    }

    @Test
    fun generatedAndToolImagesCrossfadeInsideTheWholeViewport() {
        val source = source("ui/chat/message/ToolResultContent.kt")
        val generated = source
            .substringAfter("internal fun GeneratedImageThumbnail(")
            .substringBefore("private fun GeneratedImagePendingDots(")
        val toolImage = source.substringAfter("private fun ToolImagePreview(")

        listOf(generated, toolImage).forEach { media ->
            assertTrue(media.contains("MediaLoadPresentation.LOADING"))
            assertTrue(media.contains("MediaLoadPresentation.LOADED"))
            assertTrue(media.contains("MediaLoadPresentation.FAILED"))
            assertTrue(media.contains("Crossfade("))
            assertTrue(media.contains("modifier = Modifier.fillMaxSize()"))
        }
        assertTrue(generated.contains("Icons.Default.BrokenImage"))
        assertTrue(toolImage.contains("onError ="))
        assertTrue(toolImage.contains("strokeWidth = MEDIA_LOADING_INDICATOR_STROKE_WIDTH"))
        assertFalse(source.contains("ToolImagePreviewState"))
        assertFalse(source.contains("animateFloatAsState"))
        assertFalse(source.contains("graphicsLayer"))
    }

    @Test
    fun attachmentAdmissionPreservesPickerAndCameraHapticsButClipboardStaysSilent() {
        val composer = source("ui/chat/bottombar/ChatComposerState.kt")
        val reportUnsupported = composer
            .substringAfter("fun reportUnsupportedFiles(")
            .substringBefore("fun reportCameraPreparationFailure(")
        val bottomBar = source("ui/chat/bottombar/ChatBottomBar.kt")

        assertFalse(reportUnsupported.contains("haptics."))
        val pickerAdmission = bottomBar
            .substringAfter("fun importUris(")
            .substringBefore("val clipboardImageReceiver")
        assertTrue(pickerAdmission.contains("imported && emitSuccessHaptic"))
        assertTrue(
            Regex("""haptics\.selection\(\)""").findAll(pickerAdmission).count() == 1,
        )
        val clipboardAdmission = bottomBar
            .substringAfter("val clipboardImageReceiver")
            .substringBefore("var showThinkingSheet")
        assertTrue(clipboardAdmission.contains("emitSuccessHaptic = false"))
        assertFalse(clipboardAdmission.contains("haptics."))
        val cameraAdmission = composer
            .substringAfter(".onSuccess { imported ->")
            .substringBefore(".onFailure { failure ->")
        assertTrue(cameraAdmission.contains("if (imported) {"))
        assertTrue(cameraAdmission.contains("haptics.selection()"))
    }

    private fun source(relativePath: String): String =
        File(mainSourceRoot(), "com/newoether/agora/$relativePath")
            .readText()
            .replace("\r\n", "\n")

    private fun mainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (true) {
            val candidate = File(directory, "app/src/main/java")
            if (candidate.isDirectory) return candidate
            directory = directory.parentFile ?: error("Unable to locate app/src/main/java")
        }
    }
}
