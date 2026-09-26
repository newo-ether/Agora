package com.newoether.agora.ui

import com.newoether.agora.readLocaleStringResourceSources
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ApprovedFeatureSourceContractTest : UiSourceContractFixture() {
    @Test
    fun cacheCountsOwnActionsAndOnlyRunningWorkOwnsProgress() {
        val root = sourceRoot()
        val rag = source(root, "com/newoether/agora/viewmodel/RagManager.kt")
        val settings = source(root, "com/newoether/agora/ui/settings/SettingsSearchPage.kt")
        val dao = source(root, "com/newoether/agora/data/local/ChatSearchDao.kt")
        val entities = source(root, "com/newoether/agora/data/local/ChatEntities.kt")
        val database = source(root, "com/newoether/agora/data/local/ChatDatabase.kt")

        assertFalse(rag.contains("init {\n        loadCacheCounts()"))
        assertTrue(rag.contains("fun startPostList()"))
        assertTrue(rag.contains("pendingRefreshModels = models"))
        assertTrue(rag.contains("getEmbeddingCountsByModels(configuredIds.toList())"))
        assertTrue(rag.contains("getSemanticLedgers(configuredIds.toList())"))
        assertTrue(rag.contains("getOrAdmitSemanticLedgerState"))
        assertTrue(rag.contains("getWorkInfosForUniqueWorkFlow("))
        assertTrue(rag.contains("EmbeddingCacheWorker.schedule(modelId, workManager)"))
        assertFalse(rag.contains("cacheJobs"))
        assertFalse(rag.contains("runCacheLoop"))
        assertFalse(rag.contains("ExistingWorkPolicy.REPLACE"))
        assertFalse(rag.contains("_cachingProgress"))
        assertTrue(rag.contains("EmbeddingCacheRowSnapshot") && rag.contains("scheduledCacheWorkIds"))
        assertFalse(rag.contains("EmbeddingCacheRowReducer.finalizing"))
        assertTrue(rag.contains("EmbeddingCacheRowReducer.workChanged"))

        assertTrue(settings.contains("viewModel.ragManager.cacheRows.collectAsState()") &&
            settings.contains("LaunchedEffect(embeddingModelIds) { viewModel.ragManager.loadCacheCounts() }"))
        assertTrue(settings.contains("EmbeddingCacheRowPhase.RECACHE"))
        assertTrue(settings.contains("stringResource(R.string.loading_label)"))
        assertFalse(settings.contains("retryCacheRow"))
        assertFalse(settings.contains("EmbeddingCacheRowSnapshot.Loading"))
        assertFalse(settings.contains("R.string.cache_work_remaining"))
        assertTrue(settings.contains("targetState = listOfNotNull(typeLabel, cacheLabel)"))
        assertEquals(2, settings.split("enabled = visualPhase == phase").size - 1)
        assertTrue(settings.split("animationSpec = tween(250)").size - 1 >= 2)
        assertTrue(settings.contains("modifier = Modifier.size(cacheActionSize)"))
        assertTrue(settings.contains("modifier = Modifier.size(24.dp)"))
        assertTrue(settings.contains("viewModel.ragManager.setAutoCacheEnabled"))
        assertTrue(listOf("cachingProgress", "val allCached =", "embeddingCacheActionState(")
            .none(settings::contains))

        assertTrue(dao.contains("GROUP BY e.modelId"))
        assertTrue(dao.contains("getEmbeddingCountsByModels"))
        assertTrue(entities.contains("Index(value = [\"modelId\"])"))
        assertTrue(database.contains("CURRENT_VERSION = 33"))
        assertTrue(database.contains("MIGRATION_23_24"))
        assertTrue(database.contains("MIGRATION_24_25"))
        assertTrue(database.contains("MIGRATION_25_26"))
        assertTrue(database.contains("MIGRATION_26_27"))
        assertTrue(database.contains("MIGRATION_27_28"))
        assertTrue(database.contains("MIGRATION_28_29"))
        assertTrue(database.contains("MIGRATION_29_30"))
    }

    @Test
    fun contextProgressTweensLocallyAndSnapsForReducedMotion() {
        val root = sourceRoot()
        val controls = source(
            root,
            "com/newoether/agora/ui/chat/bottombar/ChatBottomBarComponents.kt",
        )
        val sharedProgress = source(
            root,
            "com/newoether/agora/ui/motion/MotionAwareProgressIndicators.kt",
        )

        assertTrue(controls.contains("val contextProgress by animateFloatAsState("))
        assertTrue(controls.contains("motionPolicy.allowContinuousMotion"))
        assertTrue(controls.contains("tween(durationMillis = 400)"))
        assertTrue(controls.contains("snap()"))
        assertTrue(controls.contains("progress = { if (available) contextProgress else 0f }") && controls.contains("progress = { contextProgress }"))
        assertFalse(sharedProgress.contains("animateFloatAsState"))
    }

    @Test
    fun toolResultImageContextRowKeepsANonProtocolIdPrefix() {
        val root = sourceRoot()
        val toolMessages = source(root, "com/newoether/agora/api/util/ToolMessages.kt")

        // The API-only image-context row must never start with a protocol prefix: provider
        // serializers branch on tool_/result_ and would silently drop the row (view_image
        // results would display in the UI but never reach the model).
        assertTrue(toolMessages.contains("id = \"image_context_\$digest\""))
        assertFalse(toolMessages.contains("tool_image_context_"))
    }

    @Test
    fun providerCollectorsBindStableDiagnosticRequestKinds() {
        val root = sourceRoot()
        val title = source(root, "com/newoether/agora/viewmodel/ConversationTitleGenerator.kt")
        val transcription = source(root, "com/newoether/agora/viewmodel/TranscriptionManager.kt")
        val generation = source(root, "com/newoether/agora/viewmodel/GenerationManager.kt")
        val providerPass = source(
            root,
            "com/newoether/agora/viewmodel/ProviderPassEffectExecutor.kt",
        )

        assertTrue(title.contains("requestKind = \"title\""))
        assertTrue(title.contains("HttpClient.withStreamScope(scope = null, requestTrace = requestTrace)"))
        assertTrue(title.contains("requestTrace.recordParsedEvent(event)"))
        assertEquals(
            2,
            Regex("requestKind = \"transcription\"").findAll(transcription).count(),
        )
        assertEquals(
            2,
            Regex("requestTrace\\.recordParsedEvent\\(event\\)")
                .findAll(transcription).count(),
        )
        assertTrue(generation.contains("requestKind = \"tool_continuation\""))
        assertTrue(providerPass.contains("request.requestTrace?.recordParsedEvent(event)"))
    }

    @Test
    fun toolResultImageTranscriptionFollowsTheGenericDeclaredRule() {
        val root = sourceRoot()
        val toolProvider = source(root, "com/newoether/agora/tool/ToolProvider.kt")
        val shell = source(root, "com/newoether/agora/tool/ShellToolProvider.kt")
        val executor = source(
            root,
            "com/newoether/agora/viewmodel/GenerationToolBatchEffectExecutor.kt",
        )
        val manager = source(root, "com/newoether/agora/viewmodel/GenerationManager.kt")
        val transcription = source(root, "com/newoether/agora/viewmodel/TranscriptionManager.kt")
        val contracts = source(root, "com/newoether/agora/viewmodel/GenerationContracts.kt")

        // The tool declares intent via the result flag; the executor implements one generic
        // rule with no tool-name routing; the transcriber travels the per-generation call
        // chain; GenerationContext stays free of function fields.
        assertTrue(toolProvider.contains("val transcribeImages: Boolean = false"))
        assertTrue(shell.contains("transcribeImages = true"))
        assertTrue(executor.contains("result.transcribeImages && toolImage != null && transcriber != null"))
        assertFalse(executor.contains("\"view_image\""))
        assertFalse(executor.contains("[Image description]"))
        assertTrue(executor.contains("appendTranscriptionSegment("))
        assertTrue(executor.contains("toolImageTranscriber = request.toolImageTranscriber"))
        assertTrue(manager.contains("toolImageTranscriber ="))
        assertTrue(manager.contains("transcriptionManager.describeImageWithProgress("))
        assertTrue(transcription.contains("suspend fun describeImageWithProgress("))
        assertFalse(contracts.contains("toolImageTranscriber"))
        val toolMessages = source(root, "com/newoether/agora/api/util/ToolMessages.kt")
        assertTrue(toolMessages.contains("--- Image Transcription: view_image ---"))
        assertTrue(toolMessages.contains("transcriptionDescriptionsForBatch("))
        // Defect pins (owner device reports): transcription-enabled models never receive raw
        // images; the compact group title stays the transcription label while TOOL_CALLING;
        // the thinking block always announces the transcribing state.
        val pathBuilder = source(root, "com/newoether/agora/viewmodel/GenerationApiPathBuilder.kt")
        val titles = source(
            root,
            "com/newoether/agora/ui/chat/message/ThinkingSegmentPresentation.kt",
        )
        assertTrue(pathBuilder.contains("includeImages = !request.context.imageTranscriptionEnabled"))
        assertTrue(titles.contains("segs.any { it.type == \"transcription\" }"))
        assertTrue(transcription.contains("onProgress(context.getString(R.string.transcription_ellipsis_single))"))
    }

    @Test
    fun backgroundShellJobDoesNotOccupyTheGroupLoadingIndicator() {
        val root = sourceRoot()
        val presentation = source(
            root,
            "com/newoether/agora/ui/chat/message/ToolPresentation.kt",
        )
        val labels = source(
            root,
            "com/newoether/agora/ui/chat/message/MessageItemToolLabels.kt",
        )

        // isActive drives the group loading bar; a detached background job must not occupy it.
        assertTrue(presentation.contains(
            "state == ToolPresentationState.CALLING ||\n            state == ToolPresentationState.RUNNING"
        ))
        assertFalse(presentation.contains(
            "state == ToolPresentationState.BACKGROUND_RUNNING\n"
        ))
        // The card still shows the background status (matched before isActive).
        assertTrue(labels.contains(
            "presentation.state == ToolPresentationState.BACKGROUND_RUNNING ->"
        ))
    }

    @Test
    fun ratingPaddingBelongsOnlyToDialogHost() {
        val root = sourceRoot()
        val mainActivity = source(root, "com/newoether/agora/MainApplicationDialogs.kt")
        val rating = source(root, "com/newoether/agora/ui/settings/RatingForm.kt")
        val settings = source(root, "com/newoether/agora/ui/settings/SettingsAboutPage.kt")

        assertTrue(rating.contains("Modifier.clearFocusOnTap()"))
        assertFalse(rating.contains(".padding(horizontal = 24.dp, vertical = 20.dp)"))
        assertTrue(mainActivity.contains(
            "modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)"
        ))
        assertTrue(settings.contains(
            "modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)"
        ))
    }

    @Test
    fun expandedTimelineSegmentsKeepSpacingWithoutVisibleDividers() {
        val root = sourceRoot()
        val timeline = source(
            root,
            "com/newoether/agora/ui/chat/message/MessageItemTimeline.kt",
        )

        assertTrue(timeline.contains("if (idx < segs.lastIndex)"))
        assertTrue(timeline.contains("modifier = Modifier.padding(vertical = 2.dp)"))
        assertTrue(timeline.contains("color = Color.Transparent"))
        assertFalse(timeline.contains("outlineVariant.copy(alpha = 0.2f)"))
    }

    @Test
    fun toolCallCreationPublishesTheCompleteBatchBeforeExecution() {
        val manager = source(
            sourceRoot(),
            "com/newoether/agora/viewmodel/GenerationOutputAccumulator.kt",
        )
        val updateBranch = manager
            .substringAfter("is StreamEvent.ToolCallUpdate -> {")
            .substringBefore("is StreamEvent.ToolCallRequest -> {")
        val batchBranch = manager
            .substringAfter("is StreamEvent.ToolCallsRequest -> {")
            .substringBefore("\n        }\n\n        val now")

        assertTrue(updateBranch.contains("val created = upsertStreamingToolSegment("))
        assertTrue(updateBranch.contains("publishStreamUpdate(created)"))
        val upsertIndex = batchBranch.indexOf("event.calls.forEach")
        val publishIndex = batchBranch.indexOf("publishStreamUpdate(true)")
        assertTrue(upsertIndex >= 0)
        assertTrue(batchBranch.contains("upsertStreamingToolSegment("))
        assertTrue(publishIndex > upsertIndex)
        assertEquals(1, Regex("publishStreamUpdate\\(").findAll(batchBranch).count())
    }

    @Test
    fun developerCapturePageKeepsApprovedUiAndCanonicalOwners() {
        val capture = source(
            sourceRoot(),
            "com/newoether/agora/ui/settings/SettingsDeveloperCapturePage.kt",
        )
        val modes = capture
            .substringAfter("private enum class CaptureViewMode {")
            .substringBefore("}")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()
        val eventCard = capture
            .substringAfter("private fun CaptureEventCard(")
            .substringBefore("private fun CaptureEventContent(")

        assertEquals(listOf("SUMMARY,", "RAW,"), modes)
        assertTrue(capture.contains("PillTabSwitcher("))
        assertFalse(capture.contains("SingleChoiceSegmentedButtonRow"))
        assertFalse(capture.contains("SegmentedButton("))
        assertTrue(capture.contains("actions = {"))
        assertTrue(capture.contains("Icons.Default.MoreVert"))
        assertTrue(capture.contains("containerColor = MaterialTheme.colorScheme.surfaceContainer"))
        assertTrue(capture.contains("tonalElevation = 16.dp"))
        assertTrue(capture.contains("shape = RoundedCornerShape(12.dp)"))
        assertTrue(
            capture.contains("R.string.developer_options_clear_diagnostics_action"),
        )
        assertTrue(capture.contains("R.string.developer_options_clear_diagnostics)"))
        assertEquals(3, Regex("\\bCaptureExportMenuItem\\(").findAll(capture).count())
        assertFalse(capture.contains("DiagnosticExportFormat.RAW_JSON"))
        assertTrue(capture.contains("DiagnosticExportFormat.REDACTED_JSON"))
        assertTrue(capture.contains("DiagnosticExportFormat.SUMMARY_TEXT"))
        assertTrue(capture.contains("FloatingActionButton("))
        assertEquals(2, Regex("\\bSmallFloatingActionButton\\(").findAll(capture).count())
        assertEquals(3, Regex("shape = CircleShape").findAll(capture).count())
        assertTrue(capture.contains("horizontalArrangement = Arrangement.End"))
        assertTrue(capture.contains(".padding(end = 24.dp, bottom = 24.dp)"))
        assertFalse(capture.contains(".padding(horizontal = 16.dp)"))
        assertFalse(capture.contains("verticalArrangement = Arrangement.spacedBy(12.dp)"))
        assertEquals(2, Regex("Modifier\\.padding\\(bottom = 12\\.dp\\)").findAll(capture).count())
        assertTrue(capture.contains("targetState = captureRunning"))
        assertTrue(capture.contains("DeveloperDiagnostics.startCapture()"))
        assertTrue(capture.contains("DeveloperDiagnostics.pauseCapture()"))
        assertTrue(capture.contains("captureActionEnabled = !snapshot.capacityLimitReached"))
        assertTrue(capture.contains("onClick = { requestDirectionalScroll(toTop = true) }"))
        assertTrue(capture.contains("onClick = { requestDirectionalScroll(toTop = false) }"))
        assertTrue(capture.contains("R.string.developer_options_capture_capacity_incomplete"))
        assertEquals(1, Regex("Modifier\\.semantics \\{ disabled\\(\\) \\}").findAll(capture).count())
        assertTrue(capture.contains("MaterialTheme.colorScheme.surfaceVariant"))
        assertTrue(capture.contains("MaterialTheme.colorScheme.onSurfaceVariant"))
        assertTrue(capture.contains("CaptureCrossfadeDurationMillis = 250"))
        assertEquals(2, Regex("\\bCrossfade\\(").findAll(capture).count())
        assertEquals(1, Regex("\\bAnimatedContent\\(").findAll(capture).count())
        assertEquals(2, Regex("\\bAnimatedVisibility\\(").findAll(capture).count())
        assertEquals(2, Regex("expandVertically\\(").findAll(capture).count())
        assertEquals(2, Regex("shrinkVertically\\(").findAll(capture).count())
        assertEquals(2, Regex("targetState = viewMode").findAll(capture).count())
        assertTrue(capture.contains("items(snapshot.events, key = DiagnosticEvent::sequence)"))
        assertFalse(capture.contains("snapshot.events.reversed"))
        assertFalse(capture.contains("snapshot.events.asReversed"))
        assertTrue(eventCard.contains("Surface("))
        assertTrue(eventCard.contains("shape = RoundedCornerShape(24.dp)"))
        assertTrue(
            eventCard.contains(
                "LocalAgoraMotionPolicy.current.allowSpatialTransitions",
            ),
        )
        assertTrue(eventCard.contains("AnimatedContent("))
        assertTrue(eventCard.contains("targetState = viewMode"))
        assertTrue(eventCard.contains("fadeIn("))
        assertTrue(eventCard.contains("fadeOut("))
        assertTrue(eventCard.contains("SizeTransform("))
        assertTrue(eventCard.contains("clip = false"))
        assertTrue(eventCard.contains("if (allowSpatialTransitions)"))
        assertTrue(
            eventCard.contains("tween(CaptureCrossfadeDurationMillis)"),
        )
        assertTrue(eventCard.contains("snap()"))
        assertFalse(eventCard.contains("Modifier.animateContentSize("))
        assertTrue(eventCard.contains("SettingsItem("))
        assertFalse(eventCard.contains("leadingContent"))
        assertFalse(capture.contains("FontFamily"))
        assertFalse(capture.contains("fontFamily ="))
        assertFalse(capture.contains("collectIsDraggedAsState()"))
        assertFalse(capture.contains("directionalScrollJob"))
        assertFalse(capture.contains("directionalScrollRequestId"))
        assertFalse(capture.contains("directionalScrollActive"))
        assertFalse(capture.contains("scrollUpEnabled"))
        assertFalse(capture.contains("scrollDownEnabled"))
        assertFalse(capture.contains("allowProgrammaticScrollMotion"))
        assertFalse(capture.contains("animateToAbsoluteTop"))
        assertFalse(capture.contains("animateToAbsoluteBottom"))
        assertTrue(capture.contains("CaptureEdgeTolerance = 2.dp"))
        listOf(
            "val edgeTolerancePx = with(density) { CaptureEdgeTolerance.roundToPx() }",
            "listState.firstVisibleItemIndex == 0",
            "listState.firstVisibleItemScrollOffset <= edgeTolerancePx.coerceAtLeast(0)",
            "val lastVisibleItem = layoutInfo.visibleItemsInfo.maxByOrNull { it.index }",
            "lastVisibleItem?.index == layoutInfo.totalItemsCount - 1",
            "lastVisibleItem.offset + lastVisibleItem.size <=",
            "layoutInfo.viewportEndOffset + edgeTolerancePx.coerceAtLeast(0)",
            "val canScrollUp = !atTop",
            "val canScrollDown = !atBottom",
        ).forEach { edgeContract ->
            assertTrue(capture.contains(edgeContract))
        }
        assertFalse(capture.contains("listState.canScrollBackward"))
        assertFalse(capture.contains("listState.canScrollForward"))
        assertTrue(capture.contains("listState.scrollToItem(0)"))
        assertTrue(capture.contains("listState.scrollToItem(lastIndex)"))
        assertFalse(capture.contains("estimatedItemSizePx"))
        assertFalse(capture.contains("remainingItems * averageVisibleSizePx"))
        assertTrue(capture.contains("visible = hasNavigableEvents && canScrollUp"))
        assertTrue(capture.contains("visible = hasNavigableEvents && canScrollDown"))
        assertFalse(capture.contains("followLatest"))
        assertFalse(capture.contains("scrollToLatestCaptureEvent"))
        assertFalse(capture.contains("animateScrollToItem"))
        assertFalse(capture.contains("R.string.developer_options_capture_jump_latest"))
        assertFalse(capture.contains("R.string.developer_options_capture_export_raw_json"))
        assertTrue(capture.contains("item(key = \"capture-fab-spacer\")"))
        assertTrue(capture.contains("Spacer(Modifier.height(80.dp))"))
        assertTrue(capture.contains("val rawEventDetails = remember(event) { event.rawDetails() }"))
        assertTrue(capture.contains("CaptureViewMode.RAW -> rawEventDetails"))
        assertTrue(capture.contains("captureEventJson.encodeToString(DiagnosticEvent.serializer(), this)"))
        assertTrue(capture.contains("DeveloperDiagnostics.snapshots.collectAsState()"))
        assertTrue(capture.contains("DeveloperDiagnostics.clear()"))
        assertTrue(capture.contains("DeveloperDiagnostics.flush()"))
        assertFalse(capture.contains("CaptureToolbar("))
        assertFalse(capture.contains("CaptureIconAction("))
        listOf(
            "\"Start\"",
            "\"Pause\"",
            "\"Clear\"",
            "\"Export\"",
            "\"Summary\"",
            "\"Raw\"",
            "\"Scroll to Top\"",
            "\"Scroll to Bottom\"",
            "\"No captured events.\"",
        ).forEach { hardCodedLabel ->
            assertFalse("Capture page still contains $hardCodedLabel", capture.contains(hardCodedLabel))
        }
        assertFalse(capture.contains("DiagnosticCaptureStore"))
        assertFalse(capture.contains("DiagnosticEventBuffer"))
        assertFalse(capture.contains("noBackupFilesDir"))
    }

    @Test
    fun developerPageContainsOnlyApprovedHierarchyAndLocalCaptureRoute() {
        val page = source(
            sourceRoot(),
            "com/newoether/agora/ui/settings/SettingsDeveloperPage.kt",
        )
        val developerIndex = page.indexOf("R.string.settings_developer")
        val captureIndex = page.indexOf("R.string.developer_options_capture")
        val debugIndex = page.indexOf("R.string.developer_options_debug_model")

        assertTrue(developerIndex >= 0)
        assertTrue(captureIndex > developerIndex)
        assertTrue(debugIndex > captureIndex)
        assertTrue(
            page.contains(
                "title = stringResource(R.string.developer_options_features_group)",
            ),
        )
        assertTrue(page.contains("R.string.developer_options_debug_model,"))
        assertFalse(page.contains("Text(\"Debug Model\")"))
        assertEquals(3, Regex("\\bSettingsItem\\(").findAll(page).count())
        assertEquals(3, Regex("supportingContent =").findAll(page).count())
        assertEquals(2, Regex("\\bSwitch\\(").findAll(page).count())
        assertEquals(1, Regex("\\bSettingsGroup\\(").findAll(page).count())
        assertTrue(page.contains("var showCapturePage by rememberSaveable"))
        assertTrue(page.contains("BackHandler(enabled = showCapturePage)"))
        assertTrue(page.contains("GuardedAnimatedContent("))
        assertTrue(page.contains("targetState = showCapturePage"))
        assertTrue(page.contains("forward = showCapturePage"))
        assertTrue(page.contains("SettingsDeveloperCapturePage("))
        assertTrue(page.contains("onBack = { showCapturePage = false }"))
        assertTrue(page.contains("R.string.developer_options_mode_description"))
        assertTrue(page.contains("R.string.developer_options_capture_description"))
        assertTrue(page.contains("R.string.developer_options_debug_model_description"))
        assertFalse(page.contains("KeyboardArrowRight"))
        assertFalse(page.contains("ChevronRight"))
        assertFalse(page.contains("if (showCapturePage)"))
        assertTrue(page.contains("viewModel.settings.debugModelEnabled.collectAsState()"))
        assertTrue(page.contains("viewModel.settings::setDebugModelEnabled"))

        val disableBody = page
            .substringAfter("DeveloperDiagnostics.disableAndClear()")
            .substringBefore("onDisabled()")
        assertTrue(disableBody.contains("setDeveloperOptionsEnabled(false)"))
        assertTrue(disableBody.contains(".join()"))

        listOf(
            "DeveloperConversationInspector",
            "DeveloperTestLab",
            "DiagnosticBundleExporter",
            "FileProvider",
            "DiagnosticTimelineItem",
            "shareDiagnosticBundle",
            "developer_options_timeline_group",
            "developer_options_inspector",
            "developer_options_test_lab",
            "DeveloperDiagnostics.startCapture()",
            "DeveloperDiagnostics.pauseCapture()",
            "DeveloperDiagnostics.clear()",
        ).forEach { obsolete ->
            assertFalse("Developer page still contains $obsolete", page.contains(obsolete))
        }
    }

    @Test
    fun developerResourcesExposeOnlyFinalLocalizedKeySet() {
        val resourceRoot = File(sourceRoot().parentFile, "res")
        val localeFiles = resourceRoot.listFiles()
            ?.filter { directory ->
                directory.isDirectory &&
                    (directory.name == "values" || directory.name.startsWith("values-"))
            }
            ?.map { directory -> File(directory, "strings.xml") }
            ?.filter(File::isFile)
            ?.sortedBy { file -> checkNotNull(file.parentFile).name }
            .orEmpty()
        val expectedKeys = setOf(
            "developer_options_already_enabled_message",
            "developer_options_capture",
            "developer_options_capture_clear_confirm",
            "developer_options_capture_clear_message",
            "developer_options_capture_counters",
            "developer_options_capture_capacity_incomplete",
            "developer_options_capture_description",
            "developer_options_capture_empty",
            "developer_options_capture_export_redacted_json",
            "developer_options_capture_export_summary_text",
            "developer_options_capture_http_request_summary",
            "developer_options_capture_http_response_summary",
            "developer_options_capture_more_actions",
            "developer_options_capture_parsed_event_summary",
            "developer_options_capture_pause",
            "developer_options_capture_play",
            "developer_options_capture_raw",
            "developer_options_capture_scroll_to_bottom",
            "developer_options_capture_scroll_to_top",
            "developer_options_capture_session",
            "developer_options_capture_state_idle",
            "developer_options_capture_state_paused",
            "developer_options_capture_state_running",
            "developer_options_capture_status_summary",
            "developer_options_capture_summary",
            "developer_options_capture_wire_line_summary",
            "developer_options_clear_diagnostics",
            "developer_options_clear_diagnostics_action",
            "developer_options_debug_model",
            "developer_options_debug_model_description",
            "developer_options_disable_confirm",
            "developer_options_disable_message",
            "developer_options_disable_title",
            "developer_options_enabled_message",
            "developer_options_export_failed",
            "developer_options_export_share_title",
            "developer_options_features_group",
            "developer_options_mode_description",
            "developer_options_taps_remaining",
            "developer_options_title",
        )
        val developerKey = Regex("""<string name="(developer_options_[^"]+)"""")

        assertEquals(12, localeFiles.size)
        localeFiles.forEach { file ->
            val keys = developerKey.findAll(file.readLocaleStringResourceSources())
                .map { match -> match.groupValues[1] }
                .toList()
            assertEquals(
                "${checkNotNull(file.parentFile).name} contains duplicate Developer keys",
                keys.size,
                keys.toSet().size,
            )
            assertEquals(
                "${checkNotNull(file.parentFile).name} has an unexpected Developer key set",
                expectedKeys,
                keys.toSet(),
            )
        }
    }

    @Test
    fun generationAdmissionWaitsForProviderLifecycle() {
        val root = sourceRoot()
        val builder = source(root, "com/newoether/agora/viewmodel/GenerationRequestBuilder.kt")
        val generation = source(root, "com/newoether/agora/viewmodel/MessageGenerationController.kt")
        val queuedDrain = source(root, "com/newoether/agora/viewmodel/QueuedGuidanceDrainExecutor.kt")

        val admission = builder
            .substringAfter("internal suspend fun captureAdmissionSnapshot(")
            .substringBefore("internal suspend fun captureContextProjectionSnapshot(")
        assertTrue(
            admission.indexOf("providerRegistry.awaitInitialSync()") in
                0 until admission.indexOf("providerRegistry.canonicalModelId(modelId)"),
        )
        assertTrue(builder.contains("internal suspend fun awaitProviderKey(modelId: String)"))
        assertTrue(builder.contains("providerRegistry.awaitInitialSync()\n        return resolveProviderKey(modelId)"))
        assertEquals(2, Regex("requestBuilder\\.awaitProviderKey\\(").findAll(generation).count())
        val foregroundAdmission = builder
            .substringAfter("internal suspend fun prepareForegroundSend(")
            .substringBefore("internal suspend fun awaitProviderKey(")
        assertTrue(generation.contains("requestBuilder.prepareForegroundSend(target, composer, application)"))
        assertTrue(
            foregroundAdmission.indexOf("awaitProviderKey(target.modelId)") in
                0 until foregroundAdmission.indexOf("captureAdmissionSnapshot("),
        )
        assertFalse(foregroundAdmission.contains("resolveProviderKey("))
        assertFalse(generation.contains("requestBuilder.resolveProviderKey("))
        val queuedLaunch = queuedDrain.substringAfter("fun launchClaim(")
        assertFalse(
            queuedLaunch.substringBefore("state.launchGenerationJob(uiToken)")
                .contains("resolveProviderKey("),
        )
        assertTrue(queuedLaunch.contains("requestBuilder.captureAdmissionSnapshot("))
    }

    @Test
    fun debugProviderUsesHiddenExactGenerationBoundary() {
        val root = sourceRoot()
        val provider = source(root, "com/newoether/agora/api/DebugProvider.kt")
        val registry = source(root, "com/newoether/agora/viewmodel/ProviderRegistry.kt")
        val builder = source(root, "com/newoether/agora/viewmodel/GenerationRequestBuilder.kt")

        assertTrue(provider.contains("class DebugProvider : LlmProvider"))
        assertTrue(provider.contains("StreamEvent.HostedToolCallUpdate("))
        assertTrue(provider.contains("delay(STEP_DELAY_MILLIS)"))
        assertFalse(provider.contains("HttpClient"))
        assertFalse(provider.contains("requestResolver"))
        assertFalse(provider.contains("StreamEvent.ToolCallUpdate"))
        assertFalse(provider.contains("StreamEvent.ToolCallRequest"))
        assertFalse(provider.contains("StreamEvent.ToolCallsRequest"))

        val builtIns = registry
            .substringAfter("private val builtInProviders")
            .substringBefore("private val debugProvider")
        assertFalse(builtIns.contains("DebugProvider"))
        assertTrue(registry.contains("private val debugProvider = DebugProvider()"))
        assertTrue(registry.contains("val all: Map<String, LlmProvider> get() = providers"))
        assertTrue(registry.contains("fun generationSnapshot(): Map<String, LlmProvider>"))
        assertTrue(registry.contains("providers.toMap() + (DebugProvider.PROVIDER_NAME to debugProvider)"))
        assertTrue(registry.contains("settings.developerOptionsEnabled.value && settings.debugModelEnabled.value"))
        assertTrue(registry.contains("registered = getInstanceOrNull(providerName) != null"))
        assertTrue(registry.contains("if (providerName == DebugProvider.PROVIDER_NAME) return null"))

        val resolution = registry
            .substringAfter("fun providerForModel(modelId: String): String")
            .substringBefore("/** Canonicalizes legacy name-prefixed IDs")
        val exactDebugIndex = resolution.indexOf("modelId == DebugProvider.MODEL_ID")
        val prefixedIndex = resolution.indexOf("modelId.contains(\":\")")
        val availableModelsIndex = resolution.indexOf("settings.availableModels.value")
        assertTrue(exactDebugIndex >= 0)
        assertTrue(prefixedIndex > exactDebugIndex)
        assertTrue(availableModelsIndex > prefixedIndex)

        assertTrue(builder.contains("providerRegistry.generationSnapshot()"))
        assertFalse(builder.contains("providerRegistry.all.toMap()"))
    }

    @Test
    fun debugVisibilityAndModelFallbackStayOnTheCanonicalChatBoundary() {
        val root = sourceRoot()
        val chatApp = source(root, "com/newoether/agora/ui/chat/ChatApp.kt")
        val selection = source(
            root,
            "com/newoether/agora/viewmodel/ConversationSelectionController.kt",
        )
        val generation = source(
            root,
            "com/newoether/agora/viewmodel/MessageGenerationController.kt",
        )
        val workspace = source(
            root,
            "com/newoether/agora/viewmodel/ConversationWorkspaceStore.kt",
        )

        assertTrue(chatApp.contains("viewModel.settings.developerOptionsEnabled.collectAsState()"))
        assertTrue(chatApp.contains("viewModel.settings.debugModelEnabled.collectAsState()"))
        assertTrue(chatApp.contains("validChatModels("))
        assertEquals(2, Regex("enabledModels = chatEnabledModels").findAll(chatApp).count())
        assertFalse(chatApp.contains("enabledModels = enabledModels"))
        assertTrue(chatApp.contains("DebugProvider.MODEL_ID to DebugProvider.PROVIDER_NAME"))
        assertEquals(2, Regex("modelAliases = chatModelAliases").findAll(chatApp).count())

        listOf(
            "com/newoether/agora/ui/settings/SettingsModelsPage.kt",
            "com/newoether/agora/ui/settings/ModelSettingsProjection.kt",
            "com/newoether/agora/ui/settings/CustomModelProviderPicker.kt",
            "com/newoether/agora/ui/settings/SettingsContextPage.kt",
            "com/newoether/agora/ui/settings/SettingsTitleGenPage.kt",
            "com/newoether/agora/ui/settings/SettingsTranscriptionPage.kt",
            "com/newoether/agora/ui/tasks/TaskEditorPage.kt",
        ).forEach { path ->
            val surface = source(root, path)
            assertFalse("Debug leaked into $path", surface.contains("DebugProvider"))
            assertFalse("Chat model policy leaked into $path", surface.contains("validChatModels"))
        }

        assertTrue(selection.contains("awaitInitialLoad()"))
        assertTrue(selection.contains("StateFlow<Set<String>?>"))
        assertTrue(selection.contains("resolveValidModel("))
        assertTrue(selection.contains("workspaces.setModel(\n            NEW_CHAT_WORKSPACE_ID"))
        assertTrue(selection.contains("workspaces.setModel(conversationId, resolvedModel)"))
        assertTrue(selection.contains(".stateIn(scope, SharingStarted.Eagerly, \"\")"))

        val foregroundTargetCapture = generation
            .substringAfter("internal fun captureForegroundSendTarget(")
            .substringBefore("internal suspend fun prepareForegroundSend(")
        assertTrue(foregroundTargetCapture.contains("val wasNewChat ="))
        assertTrue(foregroundTargetCapture.contains("modelId = currentActiveModel.value"))

        val foregroundDelegation = generation
            .substringAfter("internal suspend fun prepareForegroundSend(")
            .substringBefore("internal suspend fun sendMessage(")
        assertTrue(foregroundDelegation.contains("requestBuilder.prepareForegroundSend(target, composer, application)"))
        val foregroundAdmission = source(root, "com/newoether/agora/viewmodel/GenerationRequestBuilder.kt")
            .substringAfter("internal suspend fun prepareForegroundSend(")
            .substringBefore("internal suspend fun awaitProviderKey(")
        assertTrue(foregroundTargetCapture.contains("captureNewChatWorkspace()"))
        assertTrue(foregroundAdmission.contains("target.newChatWorkspace?.awaitCaptured()"))
        assertTrue(workspace.contains("fun captureNewChatSnapshot()"))
        assertTrue(workspace.contains("newChatCommands.trySend(NewChatCommand.Read(completion))"))
        assertTrue(foregroundAdmission.contains("draftText = composer.text"))
        assertTrue(foregroundAdmission.contains("modelId = target.modelId"))
        assertTrue(foregroundAdmission.contains("captureAdmissionSnapshot("))
        assertFalse(foregroundAdmission.contains("toSendAdmission"))
        assertFalse(generation.contains("globalDefaultModel"))
        assertFalse(workspace.contains("NewChatSendAdmission"))
        assertFalse(workspace.contains("toSendAdmission("))
    }

    @Test
    fun skillsAreSavedCatalogToolsWithRequestResolvedPromptAndNoActiveSkill() {
        val root = sourceRoot()
        val manager = source(root, "com/newoether/agora/data/SkillManager.kt")
        val provider = source(root, "com/newoether/agora/tool/SkillToolProvider.kt")
        val builder = source(
            root,
            "com/newoether/agora/viewmodel/GenerationRequestBuilder.kt",
        )
        val exporter = source(root, "com/newoether/agora/data/DataExporter.kt")
        val importer = source(root, "com/newoether/agora/data/DataImporter.kt")
        val settings = source(
            root,
            "com/newoether/agora/ui/settings/SettingsSkillsPage.kt",
        )

        assertTrue(manager.contains("File(context.filesDir, \"skill_db\")"))
        assertTrue(manager.contains("fun catalog(): String"))
        assertFalse(manager.contains("active_skill"))
        assertTrue(provider.contains("list_skill_files"))
        assertTrue(provider.contains("read_skill_file"))
        assertTrue(provider.contains("create_skill_file"))
        assertTrue(provider.contains("edit_skill_file"))
        assertTrue(provider.contains("delete_skill_file"))
        assertFalse(provider.contains("update_active_skill"))
        assertTrue(builder.contains("skillCatalog = if (skillReadAccess) skillManager.catalog()"))
        assertTrue(builder.contains("if (includeSkillCatalog) skillManager.catalog() else \"\""))
        assertTrue(builder.contains("PredefinedVariables.SKILL_CATALOG to skillCatalog"))
        assertTrue(builder.contains("skillCatalog = skillCatalogDeferred.await()"))
        assertFalse(builder.contains("effectiveSystemPromptWithSkills"))
        assertTrue(exporter.contains("memories/skill_db/"))
        assertTrue(importer.contains("memories/skill_db/"))
        assertTrue(settings.contains("settings.accessSkills.collectAsState()"))
        assertFalse(settings.contains("Active Skill"))
    }
}
