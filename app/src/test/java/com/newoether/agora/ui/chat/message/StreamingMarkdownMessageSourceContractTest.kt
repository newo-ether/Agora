package com.newoether.agora.ui.chat.message

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownMessageSourceContractTest {
    @Test
    fun `all streaming Markdown UI enters through the parameterized message component`() {
        val root = locateMainSourceRoot()
        val wrapper = source(root, "StreamingMarkdownMessage.kt")
        val incremental = source(root, "IncrementalStreamingMarkdown.kt")
        val assistant = source(root, "AssistantMessageContent.kt")
        val timeline = source(root, "MessageItemTimeline.kt") +
            source(root, "TimelineSegmentsContent.kt")
        val detail = source(root, "SegmentDetailSheet.kt")
        val segments = source(root, "MessageItemSegments.kt")
        val interaction = source(root, "StreamingMarkdownInteractionCommitGate.kt")
        val lifecycle = source(root, "GenerationLifecycleMotion.kt")
        val selectionHost = File(root, "com/newoether/agora/util/NoOpBringIntoView.kt").readText()

        assertTrue(wrapper.contains("internal fun StreamingMarkdownMessage("))
        assertTrue(wrapper.contains("IncrementalStreamingMarkdownContent("))
        assertFalse(wrapper.contains("showStreamingIndicator"))
        assertTrue(incremental.contains("computeBlockFadeSpecs("))
        assertEquals(
            2,
            Regex("LocalStreamingGlyphFadeSpec provides ").findAll(incremental).count(),
        )
        assertFalse(incremental.contains("takeIf { showStreamingIndicator }"))
        assertTrue(
            incremental.contains(
                "mutableStateOf(isStreaming || !textDeltas.isNullOrEmpty())",
            ),
        )
        assertTrue(
            incremental.contains(
                "if (isStreaming || !textDeltas.isNullOrEmpty()) hasStreamed = true",
            ),
        )
        assertEquals(
            2,
            Regex("textDeltas = answerTextDeltas,").findAll(assistant).count(),
        )
        assertTrue(timeline.contains("textDeltas = seg.streamingTextDeltas,"))
        assertEquals(
            2,
            Regex("fadeTracker = answerFadeTracker,").findAll(assistant).count(),
        )
        assertTrue(timeline.contains("fadeTracker = answerFadeTracker,"))
        assertTrue(segments.contains("streamingFadeTrackers.getOrPut(key)"))
        assertTrue(wrapper.contains("fadeTracker: StreamingTailFadeTracker"))
        assertTrue(wrapper.contains("fadeTracker = fadeTracker,"))
        assertTrue(incremental.contains("private val fadeTracker: StreamingTailFadeTracker"))
        assertFalse(incremental.contains("private val fadeTracker = StreamingTailFadeTracker()"))
        assertTrue(incremental.contains("textDeltas = pending.textDeltas,"))
        assertTrue(incremental.contains("textDeltas = published.textDeltas,"))
        assertTrue(incremental.contains("LaunchedEffect(state, content, isStreaming, textDeltas)"))
        assertTrue(lifecycle.contains("val informationVisible = !isStreaming && !regenerateRequested"))
        assertTrue(assistant.contains("informationVisible = actionAvailability.informationVisible"))
        assertFalse(incremental.contains("internal class StreamingInteractionCommitGate"))
        assertTrue(interaction.contains("internal class StreamingInteractionCommitGate"))
        assertTrue(wrapper.contains("emptyStreamingTextStyle: TextStyle"))
        assertTrue(wrapper.contains("AnimatedVisibility("))
        assertTrue(wrapper.contains(".padding(top = 8.dp)"))
        assertTrue(selectionHost.contains("movableContentOf"))
        assertTrue(selectionHost.contains("DisableSelection(content = movableContent)"))

        listOf(assistant, timeline, detail).forEach {
            assertTrue(it.contains("StreamingMarkdownMessage("))
            assertFalse(it.contains("IncrementalStreamingMarkdownContent("))
            assertFalse(it.contains("ChatStreamingMarkdown("))
        }
        assertFalse(detail.contains("showStreamingIndicator"))
        assertTrue(detail.contains("observedStreamingMarkdown"))
    }

    @Test
    fun `terminal citation projection keeps one Markdown subtree and anchors immediate handoff`() {
        val root = locateMainSourceRoot()
        val assistant = source(root, "AssistantMessageContent.kt")
        val timeline = source(root, "MessageItemTimeline.kt") +
            source(root, "TimelineSegmentsContent.kt")
        val citation = source(root, "CitationMessageContent.kt")
        val handoff = source(root, "CitationTerminalProjectionHost.kt")
        val inlineHost = citation
            .substringAfter("internal fun CitationInlineContentHost(")
            .substringBefore("private fun CitationInlineCapsule(")

        assertTrue(assistant.contains("CitationTerminalProjectionHost("))
        assertTrue(timeline.contains("CitationTerminalProjectionHost("))
        assertTrue(assistant.contains("presentedProjection, presentedIsStreaming"))
        assertTrue(timeline.contains("presentedProjection, presentedIsStreaming"))
        assertTrue(
            handoff.contains(
                "LaunchedEffect(animationKey, isStreaming, projection)",
            ),
        )
        val startIndex = handoff.indexOf("currentLayoutMutationStarted(mutationKey)")
        val commitFrameIndex = handoff.indexOf("withFrameNanos { }", startIndex)
        val commitIndex = handoff.indexOf("presentedProjection = projection", commitFrameIndex)
        val settleIndex = handoff.indexOf("currentLayoutMutationSettled(mutationKey)", commitIndex)
        assertTrue(startIndex >= 0)
        assertTrue(commitFrameIndex > startIndex)
        assertTrue(commitIndex > commitFrameIndex)
        assertTrue(settleIndex > commitIndex)
        assertTrue(handoff.contains("Box(modifier = modifier)"))
        assertFalse(handoff.contains("animateContentSize("))
        assertFalse(handoff.contains("CITATION_TERMINAL_PROJECTION_SIZE_DURATION_MS"))
        assertFalse(handoff.contains("CITATION_TERMINAL_PROJECTION_SETTLE_FALLBACK_MS"))
        assertFalse(handoff.contains("delay("))
        assertFalse(handoff.contains("AnimatedContent("))
        assertFalse(handoff.contains("Crossfade("))
        assertFalse(Regex("content\\(\\)\\s*return").containsMatchIn(inlineHost))
        assertEquals(1, Regex("content = content").findAll(inlineHost).count())
    }

    @Test
    fun `finalized virtualized Thinking detail remains selectable without auto scroll`() {
        val detail = source(locateMainSourceRoot(), "SegmentDetailSheet.kt")
        val virtualizedDetail = detail
            .substringAfter("val detailPageContent")
            .substringAfter("if (usesVirtualizedSingleMarkdown)")
            .substringBefore("} else {")

        assertTrue(virtualizedDetail.contains("NoAutoScrollSelectionContainer("))
        assertTrue(virtualizedDetail.contains("LazyMarkdownTextContent("))
        assertTrue(detail.contains("selectionEnabled = !isStreaming"))
    }

    @Test
    fun `generation error bar owns only Local help dialog state outside Markdown`() {
        val root = locateMainSourceRoot()
        val wrapper = source(root, "StreamingMarkdownMessage.kt")
        val errorBar = source(root, "GenerationErrorBar.kt")
        val assistant = source(root, "AssistantMessageContent.kt")
        val detail = source(root, "SegmentDetailSheet.kt")

        assertTrue(errorBar.contains("internal fun GenerationErrorBar("))
        assertTrue(errorBar.contains("var showHelpDialog by remember { mutableStateOf(false) }"))
        assertEquals(1, Regex("mutableStateOf\\(").findAll(errorBar).count())
        assertFalse(errorBar.contains("MessageStatus"))
        assertFalse(wrapper.contains("GenerationErrorBar"))
        assertFalse(wrapper.contains("showHelpDialog"))
        assertFalse(assistant.contains("showHelpDialog"))
        assertTrue(assistant.contains("GenerationErrorBar("))
        assertTrue(assistant.contains("precededByCard = terminalImmediatelyFollowsCard"))
        assertTrue(detail.contains("GenerationErrorBar(it)"))
    }

    @Test
    fun `Compact detail and pill share presentation crossfade and size transform`() {
        val source = source(locateMainSourceRoot(), "MessageItem.kt")
        val pillSource = source.substringAfter("internal fun ContextCompactPill(")
        val labelTransition = pillSource
            .substringAfter("presentationTransition.AnimatedContent(")
            .substringBefore("Box {\n                IconButton(")

        assertTrue(source.contains("R.string.context_compact_streaming"))
        assertTrue(source.contains("directMarkdownContent = compactDetailText"))
        assertTrue(source.contains("errorText = detailErrorText"))
        assertFalse(source.contains("\\u200B"))
        assertTrue(source.contains("R.string.context_compact_error"))
        assertTrue(source.contains("R.string.context_compact_stopped"))
        assertTrue(pillSource.contains("val presentationTransition = updateTransition("))
        assertEquals(3, Regex("presentationTransition\\.animateColor\\(").findAll(pillSource).count())
        assertEquals(1, Regex("presentationTransition\\.Crossfade\\(").findAll(pillSource).count())
        assertEquals(1, Regex("presentationTransition\\.AnimatedContent\\(").findAll(pillSource).count())
        assertFalse(pillSource.contains("animateColorAsState("))
        assertFalse(pillSource.contains("animateContentSize("))
        assertTrue(labelTransition.contains("SizeTransform("))
        assertTrue(labelTransition.contains("motionPolicy.allowSpatialTransitions"))
        assertTrue(labelTransition.contains("snap()"))
        assertTrue(labelTransition.contains("contentAlignment = Alignment.CenterStart"))
        assertTrue(pillSource.contains("Icons.Default.Error"))
        assertTrue(pillSource.contains("MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)"))
        assertTrue(pillSource.contains("MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)"))
        assertFalse(pillSource.contains("MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)"))
        assertFalse(pillSource.contains("MaterialTheme.colorScheme.error.copy(alpha = 0.8f)"))
        assertTrue(pillSource.contains(".padding(horizontal = 7.dp)"))
        assertTrue(
            Regex(
                """modifier = Modifier\.size\(32\.dp\),\s*contentAlignment = Alignment\.Center,""",
            ).containsMatchIn(pillSource),
        )
        assertEquals(
            2,
            Regex("modifier = Modifier\\.size\\(32\\.dp\\)").findAll(pillSource).count(),
        )
        assertEquals(
            3,
            Regex("modifier = Modifier\\.size\\(18\\.dp\\)").findAll(pillSource).count(),
        )
    }

    @Test
    fun `normal and incremental Markdown roots explicitly forward inline content`() {
        val root = locateMainSourceRoot()
        val normal = source(root, "MessageItemMarkdown.kt")
        val incremental = source(root, "IncrementalStreamingMarkdown.kt")
        val timeline = source(root, "MessageItemTimeline.kt") +
            source(root, "TimelineSegmentsContent.kt")
        val citation = source(root, "CitationMarkdownProjection.kt")
        val presentation = source(root, "CitationMessageContent.kt")

        listOf(normal, incremental).forEach { owner ->
            assertTrue(owner.contains("val inlineContent = LocalMarkdownInlineContent.current"))
            assertTrue(owner.contains("inlineContent = inlineContent,"))
        }
        assertEquals(
            2,
            Regex("isStreaming = answerIsStreaming").findAll(timeline).count(),
        )
        assertTrue(citation.contains("internal fun citationMarkdownProjection("))
        assertFalse((citation + presentation).contains("terminalCitationMarkdownProjection"))
        assertTrue(citation.contains("val unsupported by lazy(LazyThreadSafetyMode.NONE)"))
        assertTrue(citation.contains("boundedTrailingCitationWrapperStart("))
        assertTrue(citation.contains("PlainCitationArtifact.findAll(answerText)"))
        assertTrue(citation.contains("CitationPolicy.stripPrivateMarkers(projection.markdown, positions)"))
        val projectionPolicy = citation.substringAfter("internal fun citationMarkdownProjection(")
            .substringBefore("internal fun citationRecordsForAnswerSlice(")
        assertTrue(projectionPolicy.indexOf("if (isStreaming)") <
            projectionPolicy.indexOf("projectCitationMarkdown(answerText, citations, includeSourcePositions)"))
        assertTrue(projectionPolicy.contains("projection.sourcePositions.toMutableList()"))
        assertTrue(projectionPolicy.contains("sourcePositions = positions.orEmpty()"))
        assertTrue(projectionPolicy.contains("markers = emptyList()"))
        assertFalse((citation + presentation).contains("answerText.lastIndexOf(\"([\")"))
    }

    @Test
    fun `all native Markdown roots share definition projection and document reference ownership`() {
        val root = locateMainSourceRoot()
        val normal = source(root, "MessageItemMarkdown.kt")
        val incremental = source(root, "IncrementalStreamingMarkdown.kt")
        val references = source(root, "MarkdownDocumentReferences.kt")
        assertEquals(2, Regex("markdownVisibleBlocks\\(state.node\\)").findAll(normal).count())
        assertTrue(incremental.contains("markdownVisibleBlocks(root, preserveTrailingSpacing)"))
        listOf(normal, incremental).forEach { assertTrue(it.contains("MarkdownDocumentReferenceHandler(")) }
        assertTrue(incremental.contains("stableReferences.putIfAbsent(label, destination)"))
        assertTrue(incremental.contains("liveReferences = markdownReferenceDefinitions(root, tail)"))
        assertTrue(references.contains("ASTNode by original"))
        assertFalse(references.contains("buildMarkdownTreeFromString"))
        assertFalse(references.contains("Regex("))
    }

    @Test
    fun `low level incremental renderer has exactly one UI caller`() {
        val root = locateMainSourceRoot()
        val consumers = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("IncrementalStreamingMarkdownContent(") }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf("IncrementalStreamingMarkdown.kt", "StreamingMarkdownMessage.kt"),
            consumers,
        )
    }

    private fun source(root: File, name: String): String =
        File(root, "com/newoether/agora/ui/chat/message/$name").readText()

    private fun locateMainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main Java source directory")
    }
}
