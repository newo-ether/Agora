package com.newoether.agora.ui.chat.message

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalGenerationUiSourceContractTest {
    @Test
    fun `terminal states use retry text tokens without active animation or shell`() {
        val root = locateMainSourceRoot()
        val assistant = source(root, "message/AssistantMessageContent.kt")
        val activity = source(root, "message/AssistantInlineActivity.kt")
        val terminalBar = source(root, "message/GenerationErrorBar.kt")
        val retry = source(root, "message/RetryActivityIndicator.kt")
        val tail = source(root, "StreamingTailIndicator.kt")

        assertFalse((assistant + activity).contains("AssistantStatusRow("))
        assertFalse((assistant + activity).contains("AssistantStatusKind"))
        assertTrue(assistant.contains("private val FormerAssistantStatusSpacerHeight = 6.dp"))
        assertTrue(assistant.contains(
            "Spacer(modifier = Modifier.height(FormerAssistantStatusSpacerHeight))"
        ))
        assertTrue(assistant.contains("AssistantInlineActivity("))
        assertTrue(activity.contains("RetryActivityIndicator("))
        assertTrue(assistant.contains("StoppedGenerationBar("))
        assertTrue(assistant.contains("AnimatedVisibility("))
        assertTrue(assistant.contains("fadeIn(tween(durationMillis = 180"))
        assertTrue(assistant.contains("fadeOut(tween(durationMillis = 180"))
        assertTrue(assistant.contains("errorText = errorContent?.errorText ?: retainedErrorText"))
        assertTrue(assistant.contains("precededByCard = terminalImmediatelyFollowsCard"))
        assertTrue(
            source(root, "message/AssistantMessagePresentation.kt")
                .contains("lastVisibleTerminalPredecessor"),
        )
        assertTrue(terminalBar.contains("precededByCard: Boolean = false"))
        assertTrue(terminalBar.contains("if (precededByCard) 12.dp else 8.dp"))
        assertTrue(terminalBar.contains("if (precededByCard) 12.dp"))
        assertFalse((assistant + activity).contains("if (mode == AssistantInlineActivityMode.NONE) return"))
        assertTrue(activity.contains("var retainedMode by remember"))
        assertTrue(activity.contains("visibilityTransition.targetState ||"))
        assertTrue(activity.contains(
            "visibilityTransition.targetState || retainExitLayout"
        ))
        assertTrue(activity.contains(
            "alpha = if (terminalText == null) activityOpacity else 1f"
        ))
        assertTrue(assistant.contains("retainExitLayout = inlineActivityPresentation.retainLayout"))
        assertTrue(activity.contains("clip = false"))
        assertTrue(assistant.contains("GenerationActivityDot()"))
        val messageContent = assistant.substringAfter("internal fun AssistantMessageContent(")
        val fixedSpacerIndex = messageContent.indexOf(
            "Spacer(modifier = Modifier.height(FormerAssistantStatusSpacerHeight))"
        )
        val compactIndex = messageContent.indexOf("if (compactVisible)")
        val activityIndex = messageContent.indexOf("AssistantInlineActivity(")
        val answerIndex = messageContent.indexOf("val answerBodyText")
        assertTrue(fixedSpacerIndex >= 0)
        assertTrue(compactIndex > fixedSpacerIndex)
        assertTrue(activityIndex > compactIndex)
        assertTrue(activityIndex > answerIndex)

        assertTrue(terminalBar.contains("internal fun GenerationTerminalText("))
        assertTrue(terminalBar.contains("style = ChatType.body"))
        assertTrue(terminalBar.contains("onSurfaceVariant.copy(alpha = 0.55f)"))
        assertTrue(terminalBar.contains("NoAutoScrollSelectionContainer"))
        assertTrue(terminalBar.contains("normalizePersistedGenerationErrorText("))
        assertFalse(terminalBar.contains("Surface("))
        assertFalse(terminalBar.contains("Icon("))
        assertFalse(terminalBar.contains("RoundedCornerShape"))
        assertFalse(terminalBar.contains("GenerationActivityDot("))
        assertFalse(terminalBar.contains("surfaceVariant.copy"))
        assertFalse(terminalBar.contains("colorScheme.error"))

        assertTrue(retry.contains("RETRY_REVEAL_MS_PER_GRAPHEME = 27"))
        assertTrue(retry.contains("RETRY_REVEAL_MIN_MS = 225"))
        assertTrue(retry.contains("RETRY_REVEAL_MAX_MS = 600"))
        assertTrue(retry.contains("easing = LinearOutSlowInEasing"))
        assertTrue(retry.contains("val revealProgress = remember {"))
        assertTrue(retry.contains("entranceStarted"))
        assertFalse(retry.contains("remember(label) {\n        Animatable"))
        assertTrue(retry.contains("retryGraphemeBoundaries("))
        assertTrue(retry.contains("retryRevealDurationMillis("))
        assertTrue(retry.contains("retryGraphemeAlpha("))
        assertTrue(retry.contains("retryCaretPosition("))
        assertTrue(retry.contains("getHorizontalPosition("))
        assertTrue(retry.contains("GenerationActivityDot("))
        assertTrue(retry.contains("translationX = dotTranslationPx"))
        assertTrue(retry.contains("translationY = dotVerticalOffsetPx"))
        assertTrue(retry.contains("clip = false"))
        assertFalse(retry.contains("InlineActivityDotMarker("))

        assertTrue(tail.contains("internal fun GenerationActivityDot("))
        assertTrue(tail.contains("rememberInfiniteTransition("))
        assertTrue(tail.contains("internal val GenerationActivityDotSize = 11.dp"))
        assertTrue(tail.contains(".size(GenerationActivityDotSize)"))
        assertTrue(tail.contains("alpha = opacity"))
        assertTrue(tail.contains("scaleX = appearanceScale"))
        assertTrue(tail.contains("clip = false"))
        assertFalse(tail.contains("InlineActivityDotSource"))
    }

    @Test
    fun `Thinking card uses compact chrome one trailing rotating arrow and synchronized motion`() {
        val root = locateMainSourceRoot()
        val timeline = source(root, "message/MessageItemTimeline.kt") +
            source(root, "message/TimelineSegmentsContent.kt") +
            source(root, "message/AssistantMessagePresentation.kt")
        val assistant = source(root, "message/AssistantMessageContent.kt")
        val presentation = source(root, "message/ThinkingSegmentPresentation.kt")
        val mutedText = source(root, "message/StreamingMutedText.kt")

        assertTrue(timeline.contains("CompactSegmentIcon.LOADING"))
        assertTrue(timeline.contains("compactSegmentShowsLoading("))
        assertTrue(timeline.contains("generationActive: Boolean"))
        assertTrue(timeline.contains("isCurrentCard: Boolean"))
        assertTrue(timeline.contains("isCurrentCard = blockEnd > lastVisibleSegmentIndex"))
        assertTrue(assistant.contains("val generationActive ="))
        assertTrue(assistant.contains("generationActive = generationActive"))
        assertTrue(assistant.contains("isCurrentCard = !hasAnswerContent"))
        assertTrue(timeline.contains("targetState = collapsedIcon"))
        assertTrue(timeline.contains("CircularProgressIndicator("))
        assertTrue(timeline.contains("BoxWithConstraints("))
        assertTrue(timeline.contains("private fun StartAnchoredHorizontalOverflowHost("))
        assertTrue(timeline.contains(
            ".wrapContentWidth(Alignment.Start, unbounded = true)"
        ))
        assertEquals(
            3,
            timeline.windowed("StartAnchoredHorizontalOverflowHost".length)
                .count { it == "StartAnchoredHorizontalOverflowHost" },
        )
        assertTrue(timeline.contains("rememberTextMeasurer("))
        assertTrue(timeline.contains("label = \"compactSegmentWidth\""))
        assertTrue(timeline.contains(".width(cardWidth)"))
        assertTrue(timeline.contains("durationMillis = 400"))
        assertTrue(timeline.contains("easing = LinearOutSlowInEasing"))
        assertTrue(presentation.contains("THINKING_COLLAPSED_WIDTH_ALLOWANCE_DP = 6"))
        assertTrue(presentation.contains("AUXILIARY_CARD_START_EXTENSION_DP = 4"))
        assertTrue(timeline.contains("maxWidth + (AUXILIARY_CARD_START_EXTENSION_DP * 2).dp"))
        assertFalse(timeline.contains("maxWidth + AUXILIARY_CARD_START_EXTENSION_DP.dp"))
        assertTrue(timeline.contains(".offset(x = (-AUXILIARY_CARD_START_EXTENSION_DP).dp)"))
        assertTrue(timeline.contains("val contentLayoutWidth ="))
        assertTrue(timeline.contains("wrapContentSize(Alignment.TopStart, unbounded = true)"))
        assertTrue(timeline.contains("requiredWidth(contentLayoutWidth)"))
        assertFalse(timeline.contains("animateContentSize("))
        assertTrue(timeline.contains("RoundedCornerShape(18.dp)"))
        assertTrue(timeline.contains("padding(start = 12.dp, top = 10.dp, bottom = 10.dp)"))
        assertTrue(timeline.contains(".padding(horizontal = 10.dp, vertical = 8.dp)"))
        assertTrue(timeline.contains("+ titleWidth + 4.dp + 18.dp + 12.dp +"))
        assertTrue(timeline.contains("Spacer(modifier = Modifier.width(26.dp))"))
        assertTrue(timeline.contains("align(Alignment.TopEnd)"))
        assertTrue(timeline.contains("padding(top = 10.dp, end = 8.dp)"))
        val loadingBranch = timeline
            .substringAfter("CompactSegmentIcon.LOADING -> CircularProgressIndicator(")
            .substringBefore("CompactSegmentIcon.TOOL -> Icon(")
        assertTrue(loadingBranch.contains("Modifier.size(16.dp)"))
        assertTrue(timeline.contains("fontSize = 13.sp"))
        assertTrue(timeline.contains("lineHeight = 22.sp"))
        assertTrue(timeline.contains("fontWeight = FontWeight.SemiBold"))
        assertTrue(timeline.contains("Modifier.weight(1f)"))
        assertTrue(timeline.contains("strokeWidth = 2.dp"))
        assertEquals(1, timeline.windowed("Icons.Default.KeyboardArrowDown".length)
            .count { it == "Icons.Default.KeyboardArrowDown" })
        assertFalse(timeline.contains("Icons.Default.KeyboardArrowRight"))
        assertFalse(timeline.contains("Icons.Default.KeyboardArrowUp"))
        assertTrue(timeline.contains("rotationZ = disclosureRotation"))
        assertTrue(presentation.contains("thinking_for_seconds_ellipsis"))
        assertTrue(mutedText.contains("MUTED_STREAM_TAIL_CODE_POINTS = 42"))
        assertTrue(mutedText.contains("MUTED_STREAM_TAIL_ALPHA_BANDS = 6"))
        assertTrue(mutedText.contains("MUTED_STREAM_TAIL_NEWEST_ALPHA = 0.38f"))
        val thoughtPreview = mutedText.substringAfter("internal fun StreamingThoughtPreviewText(")
        assertTrue(thoughtPreview.contains("StreamingMutedText("))
        assertEquals(2, Regex("StreamingThoughtPreviewText\\(").findAll(timeline).count())
    }

    @Test
    fun `Timeline and Thinking sheet rows reuse grouping while keeping their own outer insets`() {
        val root = locateMainSourceRoot()
        val timeline = source(root, "message/MessageItemTimeline.kt") +
            source(root, "message/TimelineSegmentsContent.kt") +
            source(root, "message/AssistantMessagePresentation.kt")
        val detail = source(root, "message/SegmentDetailSheet.kt")
        val segments = source(root, "message/MessageItemSegments.kt")

        assertTrue(segments.contains("internal enum class SegmentGroupPosition"))
        assertTrue(timeline.contains("SEGMENT_GROUP_GAP_DP = 2"))
        assertTrue(segments.contains("rememberAnimatedSegmentGroupShape("))
        assertTrue(segments.contains("SEGMENT_GROUP_OUTER_RADIUS_DP = 24"))
        assertTrue(segments.contains("SEGMENT_GROUP_INNER_RADIUS_DP = 5"))
        assertTrue(timeline.contains("timelineSegmentGroupPosition(segments, index)"))
        assertTrue(timeline.contains(
            "val groupShape = rememberAnimatedSegmentGroupShape(groupPosition)"
        ))
        assertTrue(timeline.contains("shape = groupShape"))
        assertTrue(timeline.contains("extendIntoMessageInsets: Boolean = false"))
        assertTrue(timeline.contains("extendIntoMessageInsets = true"))
        assertFalse(timeline.contains(
            "requiredWidth(maxWidth + (AUXILIARY_CARD_START_EXTENSION_DP * 2).dp)"
        ))
        assertTrue(timeline.contains("val requestedCardWidth = if (extendIntoMessageInsets)"))
        assertTrue(timeline.contains(".width(requestedCardWidth)"))
        assertTrue(timeline.contains(".offset(x = requestedCardOffset)"))
        assertTrue(timeline.contains(".offset(x = (-AUXILIARY_CARD_START_EXTENSION_DP).dp)"))
        assertTrue(detail.contains("segmentGroupPosition("))
        assertTrue(detail.contains("groupPosition = groupPosition"))
        assertTrue(detail.contains("neutralPalette = true"))
        assertFalse(detail.contains("extendIntoMessageInsets = true"))
    }

    @Test
    fun `Thinking sheet matches Settings chrome and uses primary card icons`() {
        val root = locateMainSourceRoot()
        val timeline = source(root, "message/MessageItemTimeline.kt") +
            source(root, "message/TimelineSegmentsContent.kt") +
            source(root, "message/AssistantMessagePresentation.kt")
        val detail = source(root, "message/SegmentDetailSheet.kt")
        val presentation = source(root, "message/ThinkingSegmentPresentation.kt")
        val sharedBackButton = File(
            root,
            "com/newoether/agora/ui/components/CircularBackButton.kt",
        ).readText()

        assertTrue(timeline.contains("compactSegmentDisplayTitle("))
        assertTrue(detail.contains("showSegmentListPage ->"))
        assertTrue(detail.contains("compactSegmentDisplayTitle("))
        assertFalse(detail.contains("stringResource(R.string.thinking_segments_title)"))
        assertTrue(presentation.contains("thinking_for_seconds_ellipsis"))
        assertTrue(presentation.contains("produceState("))
        assertTrue(timeline.contains("neutralPalette: Boolean = false"))
        val palette = timeline
            .substringAfter("neutralPalette: Boolean = false")
            .substringBefore("BoxWithConstraints")
        assertTrue(palette.contains(
            "MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)"
        ))
        assertTrue(palette.contains("MaterialTheme.colorScheme.surface"))
        assertTrue(palette.contains(
            "val iconTint = if (neutralPalette) MaterialTheme.colorScheme.primary"
        ))
        assertFalse(palette.contains("seg.type == \"tool\""))
        assertTrue(timeline.contains("if (neutralPalette) 1.dp else 2.dp"))
        assertTrue(timeline.contains(
            "tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)"
        ))
        val backButton = detail
            .substringAfter("CircularBackButton(")
            .substringBefore(")")
        assertTrue(backButton.contains("containerColor ="))
        assertFalse(backButton.contains("contentColor ="))
        assertFalse(backButton.contains("tonalElevation ="))
        assertTrue(sharedBackButton.contains(
            "containerColor: Color = MaterialTheme.colorScheme.surface"
        ))
        assertTrue(sharedBackButton.contains(
            "contentColor: Color = MaterialTheme.colorScheme.onSurface"
        ))
        assertTrue(sharedBackButton.contains("tonalElevation: Dp = 6.dp"))
    }

    @Test
    fun `Sources summary shares information action fade without scale or measured host`() {
        val root = locateMainSourceRoot()
        val assistant = source(root, "message/AssistantMessageContent.kt")
        val transitionHost = source(root, "message/CitationTerminalProjectionHost.kt")
        val citations = source(root, "message/CitationMessageContent.kt")
        val summary = assistant
            .substringAfter("if (sourcesSummaryVisible || informationActionsAlpha > 0f)")
            .substringBefore("val answerTailVisible")
        val capsule = citations
            .substringAfter("internal fun CitationSourcesSummaryCapsule(")
            .substringBefore("internal fun CitationSourcesBottomSheet(")

        assertTrue(summary.contains("CitationSourcesSummaryCapsule("))
        assertTrue(summary.contains("visible = sourcesSummaryVisible"))
        assertTrue(summary.contains("enabled = sourcesSummaryVisible"))
        assertTrue(summary.contains("showCitationSources = true"))
        assertFalse(summary.contains("haptics."))
        assertTrue(summary.contains(".offset(x = (-AUXILIARY_CARD_START_EXTENSION_DP).dp)"))
        assertTrue(summary.contains(".padding(top = 12.dp)"))
        assertTrue(summary.contains(".graphicsLayer { alpha = informationActionsAlpha }"))
        assertTrue(assistant.contains("durationMillis = if (actionAvailability.informationVisible)"))
        assertTrue(assistant.contains("ACTIONS_ENTER_DURATION_MS"))
        assertTrue(assistant.contains("ACTIONS_EXIT_DURATION_MS"))
        assertTrue(assistant.contains("easing = LinearEasing"))
        assertFalse(assistant.contains("CitationSourcesSummaryHost("))
        assertFalse(transitionHost.contains("CitationSourcesSummaryHost("))
        assertFalse(transitionHost.contains("MutableTransitionState"))
        assertFalse(transitionHost.contains("expandVertically("))
        assertFalse(transitionHost.contains("shrinkVertically("))
        assertFalse(transitionHost.contains("CITATION_SOURCES_SUMMARY_SIZE_DURATION_MS"))
        assertFalse(capsule.contains("citationCapsuleFadeModifier("))
        assertFalse(summary.contains("scale"))
    }

    @Test
    fun `Sources sheet reuses smooth shell with neutral numbered badges`() {
        val citations = source(locateMainSourceRoot(), "message/CitationMessageContent.kt")

        assertTrue(citations.contains("val sheetState = rememberSmoothBottomSheetState()"))
        assertTrue(citations.contains("val listState = rememberLazyListState()"))
        assertTrue(citations.contains("SmoothBottomSheet("))
        assertTrue(citations.contains("sheetState.requestDismiss()"))
        assertTrue(citations.contains("contentAtTop = {"))
        assertTrue(citations.contains("listState.firstVisibleItemIndex == 0"))
        assertTrue(citations.contains("listState.firstVisibleItemScrollOffset == 0"))
        assertTrue(citations.contains(".fillMaxSize()"))
        assertFalse(citations.contains("rememberModalBottomSheetState()"))
        assertFalse(citations.contains("ModalBottomSheet("))
        assertTrue(citations.contains("onSurfaceVariant.copy("))
        assertTrue(citations.contains("alpha = CITATION_SOURCE_BADGE_BACKGROUND_ALPHA"))
        assertTrue(citations.contains("alpha = CITATION_SOURCE_BADGE_FOREGROUND_ALPHA"))
        val sourceRow = citations
            .substringAfter("private fun CitationSourceRow(")
            .substringBefore("private fun CitationBadgeVisual(")
        assertTrue(sourceRow.contains("val titleColor = MaterialTheme.colorScheme.onSurface"))
        assertFalse(sourceRow.contains("MaterialTheme.colorScheme.primary"))
    }

    @Test
    fun `thinking tool errors and stopped states reuse shared neutral terminal text`() {
        val toolResult = source(locateMainSourceRoot(), "message/ToolResultContent.kt")
        val detail = toolResult
            .substringAfter("internal fun ToolDetailContent(")
            .substringBefore("internal fun toolDetailHorizontalPadding(")
        val errorContent = toolResult
            .substringAfter("private fun ToolErrorContent(")
            .substringBefore("internal fun ToolMutedContent(")
        val terminalText = source(locateMainSourceRoot(), "message/GenerationErrorBar.kt")
            .substringAfter("internal fun GenerationTerminalText(")
            .substringBefore("internal fun GenerationErrorBar(")
        val model = source(locateMainSourceRoot(), "message/ToolDetailPresentation.kt")
        val failedContent = model
            .substringAfter("ToolPresentationState.FAILED ->")
            .substringBefore("ToolPresentationState.STOPPED ->")
        assertTrue(detail.contains("is ToolDetailBody.Failed ->"))
        assertTrue(detail.contains("ToolErrorContent(body.text)"))
        assertFalse(failedContent.contains("rawTextResult"))
        assertFalse(failedContent.contains("rawStructuredResult"))
        assertTrue(model.contains("ToolKind.MCP ->"))
        assertTrue(detail.contains("is ToolDetailBody.Stopped -> GenerationTerminalText("))
        assertTrue(errorContent.contains("GenerationTerminalText("))
        assertTrue(errorContent.contains("selectable = true"))
        assertTrue(errorContent.contains("fillWidth = true"))
        assertFalse(errorContent.contains("Surface("))
        assertFalse(errorContent.contains("surfaceVariant"))
        assertFalse(errorContent.contains("ChatType.thoughtBody"))
        assertTrue(terminalText.contains("style = ChatType.body"))
        assertTrue(
            terminalText.contains(
                "color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)",
            ),
        )
        assertTrue(model.contains("ToolPresentationState.EMPTY,"))
        assertTrue(model.contains("ToolPresentationState.COMPLETED -> completedToolDetailBody(presentation)"))
    }

    @Test
    fun `answer and thought Markdown use centered one point one line height`() {
        val assets = source(locateMainSourceRoot(), "message/MessageBubbleAssets.kt")

        assertTrue(assets.contains("MARKDOWN_LINE_HEIGHT_MULTIPLIER = 1.1f"))
        assertTrue(assets.contains("scaledMarkdownTextStyle("))
        assertTrue(assets.contains("lineHeightStyle = LineHeightStyle("))
        assertTrue(assets.contains("alignment = LineHeightStyle.Alignment.Center"))
        assertTrue(assets.contains("trim = LineHeightStyle.Trim.Both"))
        assertTrue(assets.contains("val markdownBodyStyle = scaledMarkdownTextStyle(ChatType.body)"))
        assertTrue(assets.contains("val thoughtMarkdownBodyStyle = scaledMarkdownTextStyle(ChatType.thoughtBody)"))
        assertTrue(assets.contains("h1 = scaledMarkdownTextStyle(ChatType.mdH1)"))
        assertTrue(assets.contains("h6 = scaledMarkdownTextStyle(ChatType.mdH6)"))
        assertTrue(assets.contains("code = scaledMarkdownTextStyle(ChatType.code)"))
        assertTrue(assets.contains("h1 = scaledMarkdownTextStyle(ChatType.thH1)"))
        assertTrue(assets.contains("h6 = scaledMarkdownTextStyle(ChatType.thH6)"))
        assertTrue(assets.contains("code = scaledMarkdownTextStyle(ChatType.thoughtCode)"))
        assertTrue(assets.contains("plainTextStyle = markdownBodyStyle"))
        assertTrue(assets.contains("plainTextStyle = thoughtMarkdownBodyStyle"))
    }

    @Test
    fun `user actions move from the bottom row into the bubble long press menu`() {
        val user = source(locateMainSourceRoot(), "message/UserMessageBubble.kt")

        assertTrue(user.contains(".combinedClickable("))
        assertTrue(user.contains("onLongClick ="))
        assertFalse(user.contains("NoAutoScrollSelectionContainer"))
        assertTrue(user.contains("R.string.copy"))
        assertTrue(user.contains("R.string.edit"))
        assertTrue(user.contains("val editFocusRequester = remember(message.id)"))
        assertTrue(user.contains("LaunchedEffect(showEditor, editFocusRequester)"))
        assertTrue(user.contains("val showEditor = isEditing && message.source == null"))
        assertTrue(user.contains("if (showEditor) editFocusRequester.requestFocus()"))
        assertTrue(user.contains("editFocusRequester.requestFocus()"))
        assertTrue(user.contains(".focusRequester(editFocusRequester)"))
        assertTrue(user.contains("R.string.select_text"))
        assertTrue(user.contains("R.string.info"))
        assertTrue(user.contains("R.string.delete"))

        val branch = user.substringAfter("if (showBranchSelector")
        assertTrue(branch.contains("onSwitchBranch(-1)"))
        assertTrue(branch.contains("onSwitchBranch(1)"))
    }

    @Test
    fun `Select Text reuses the sheet shell with twelve dp raw-content top inset`() {
        val root = locateMainSourceRoot()
        val item = source(root, "message/MessageItem.kt")
        val detail = source(root, "message/SegmentDetailSheet.kt")

        assertTrue(item.contains("showUserTextSelection"))
        assertTrue(item.contains("titleOverride = stringResource(R.string.select_text)"))
        assertTrue(item.contains("directSelectableTextContent = displayMessage.text"))
        assertTrue(detail.contains("directSelectableTextContent: String? = null"))
        assertTrue(detail.contains("NoAutoScrollSelectionContainer("))
        assertTrue(detail.contains("SearchHighlightedPlainText("))
        assertTrue(detail.contains("padding(top = 12.dp, bottom = 32.dp)"))
        assertTrue(detail.contains("SmoothBottomSheet("))
        assertTrue(detail.contains("rememberSmoothBottomSheetState("))
        assertFalse(detail.contains("Dialog("))
        assertFalse(detail.contains("detectVerticalDragGestures("))
        assertFalse(detail.contains("snapshotFlow"))
    }

    private fun source(root: File, relative: String): String =
        File(root, "com/newoether/agora/ui/chat/$relative").readText()

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
