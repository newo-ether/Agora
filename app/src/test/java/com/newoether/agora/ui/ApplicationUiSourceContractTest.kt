package com.newoether.agora.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ApplicationUiSourceContractTest : UiSourceContractFixture() {
    @Test
    fun `onboarding primary action keeps fixed geometry without custom press motion`() {
        val source = sourceFile("app/src/main/java/com/newoether/agora/ui/onboarding/WelcomeScreen.kt")

        assertFalse(source.contains("val continueInteractionSource"))
        assertFalse(source.contains("collectIsPressedAsState()"))
        assertFalse(source.contains("val isContinuePressed"))
        assertFalse(source.contains("val horizontalInset by animateDpAsState"))
        assertFalse(source.contains("val actionHeight by animateDpAsState"))
        assertFalse(source.contains("val contentScale by animateFloatAsState"))
        assertFalse(source.contains("interactionSource = continueInteractionSource"))
        assertTrue(source.contains(".padding(horizontal = 32.dp)"))
        assertTrue(source.contains(".height(48.dp)"))
        assertFalse(source.contains(".scale(contentScale)"))
        assertTrue(source.contains("pagerState.animateScrollToPage("))
        assertTrue(source.contains("if (last) { exiting = true }"))
    }

    @Test
    fun `onboarding dot indicator keeps constant row height without spring`() {
        val source = sourceFile("app/src/main/java/com/newoether/agora/ui/onboarding/WelcomeScreen.kt")

        // Fixed outer slot: selection changes never shift the whole indicator vertically.
        assertTrue(source.contains(
            "Box(Modifier.padding(horizontal = 4.dp).size(10.dp), contentAlignment = Alignment.Center)"
        ))
        assertTrue(source.contains("animateDpAsState(if (sel) 10.dp else 8.dp, tween(120))"))
        assertTrue(source.contains(
            "animateColorAsState(if (sel) MaterialTheme.colorScheme.primary else " +
                "MaterialTheme.colorScheme.outlineVariant, tween(120))"
        ))
        assertFalse(source.contains("spring("))
    }

    @Test
    fun `Skills settings mirrors the saved Memory file presentation`() {
        val source = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsSkillsPage.kt",
        )

        assertTrue(source.contains("var skillsLoaded"))
        assertTrue(source.contains("var skillOperationInFlight"))
        assertTrue(source.contains("fun loadSkills("))
        assertTrue(source.contains("CircularProgressIndicator("))
        assertTrue(source.contains("R.string.memory_access_title"))
        assertTrue(source.contains("R.string.skills_create_hint"))
        assertTrue(source.contains("Icons.Default.MoreVert"))
        assertTrue(source.contains("DropdownMenu("))
        assertTrue(source.contains("SettingsAddItem("))
        assertFalse(source.contains("import androidx.compose.material3.Button\n"))
        assertTrue(source.contains("containerColor = MaterialTheme.colorScheme.surfaceContainer"))
        assertTrue(source.contains("fontWeight = FontWeight.Bold"))
        assertTrue(source.contains("shape = RoundedCornerShape(16.dp)"))
        assertTrue(source.contains("FontFamily.Monospace"))
        assertTrue(source.contains("Modifier.clearFocusOnTap()"))
        assertTrue(source.contains("Spacer(modifier = Modifier.height(80.dp))"))
        assertTrue(source.contains("file.name.removeSuffix(\".md\")"))
    }

    @Test
    fun `singular transcription ellipsis exists in every locale`() {
        val directories = listOf(
            "values", "values-ar", "values-de", "values-es", "values-fr", "values-ja",
            "values-ko", "values-pt-rBR", "values-ru", "values-vi", "values-zh",
            "values-zh-rTW",
        )

        directories.forEach { directory ->
            val strings = sourceFile("app/src/main/res/$directory/strings.xml")
            assertTrue(
                "$directory transcription_ellipsis_single",
                strings.contains("name=\"transcription_ellipsis_single\""),
            )
        }
    }

    @Test
    fun `Skills entry uses Extension while Markdown import uses Memory Description icon`() {
        val settings = sourceFile("app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt")
        val page = sourceFile("app/src/main/java/com/newoether/agora/ui/settings/SettingsSkillsPage.kt")
        val strings = sourceFile("app/src/main/res/values/strings.xml")

        assertTrue(settings.contains("R.string.settings_skills, R.string.settings_skills_desc, Icons.Default.Extension"))
        assertFalse(page.contains("AutoAwesome"))
        assertTrue(page.contains("Icons.Default.Extension"))
        assertTrue(page.contains("Icons.Default.Description"))
        mapOf(
            "skills_access" to "Access Saved Skills",
            "skills_saved_title" to "Saved Skills",
            "skills_add" to "Add Skill",
            "skills_delete_title" to "Delete Skill?",
        ).forEach { (key, value) ->
            assertTrue(strings.contains("""<string name="$key">$value</string>"""))
        }
    }

    @Test
    fun `Skills UI strings keep locale and delete placeholder parity`() {
        val directories = listOf(
            "values", "values-ar", "values-de", "values-es", "values-fr", "values-ja",
            "values-ko", "values-pt-rBR", "values-ru", "values-vi", "values-zh",
            "values-zh-rTW",
        )

        directories.forEach { directory ->
            val strings = sourceFile("app/src/main/res/$directory/strings.xml")
            assertTrue("$directory skills_create_hint", strings.contains("name=\"skills_create_hint\""))
            assertTrue("$directory skills_create", strings.contains("name=\"skills_create\""))
            assertTrue("$directory skills_edit", strings.contains("name=\"skills_edit\""))
            assertTrue(
                "$directory skills_delete_message placeholder",
                stringValue(strings, "skills_delete_message").contains("%1\$s"),
            )
        }
    }

    @Test
    fun `PDF page bitmaps are initialized opaque white before both framework render paths`() {
        val source = sourceFile(
            "app/src/main/java/com/newoether/agora/util/PdfPageRenderer.kt",
        )

        assertTrue(source.contains("private const val MAX_PAGES = 5"))
        assertTrue(source.contains("private const val TARGET_LONG_EDGE = 1536"))
        assertTrue(source.contains("private fun createPageBitmap(width: Int, height: Int): Bitmap"))
        assertEquals(1, Regex("Bitmap\\.createBitmap\\(").findAll(source).count())
        assertTrue(source.contains("eraseColor(Color.WHITE)"))
        assertEquals(
            2,
            Regex("bitmap = createPageBitmap\\(width, height\\)")
                .findAll(source)
                .count(),
        )
        assertEquals(
            2,
            Regex(
                "page\\.render\\(bitmap, null, null, " +
                    "PdfRenderer\\.Page\\.RENDER_MODE_FOR_DISPLAY\\)",
            ).findAll(source).count(),
        )
        assertEquals(
            2,
            Regex("Bitmap\\.CompressFormat\\.JPEG, 80").findAll(source).count(),
        )
        assertTrue(source.contains("for (index in selectedPages.sorted())"))
        assertTrue(source.contains("onProgress?.invoke(index + 1, effectiveTotal)"))
        assertEquals(4, Regex("paths\\.forEach \\{ File\\(it\\)\\.delete\\(\\) \\}")
            .findAll(source).count())
        assertTrue(source.contains(
            "): List<String> = renderAllPages(context, uri.toString(), maxPages, onProgress)",
        ))
        assertTrue(source.contains("val descriptor = openDescriptor(context, source) ?: return emptyList()"))
    }

    @Test
    fun `generation settings description names only localized LLM parameters`() {
        val expected = linkedMapOf(
            "values" to "LLM parameters",
            "values-ar" to "معاملات LLM",
            "values-de" to "LLM-Parameter",
            "values-es" to "Parámetros del LLM",
            "values-fr" to "Paramètres du LLM",
            "values-ja" to "LLM パラメーター",
            "values-ko" to "LLM 매개변수",
            "values-pt-rBR" to "Parâmetros do LLM",
            "values-ru" to "Параметры LLM",
            "values-vi" to "Tham số LLM",
            "values-zh" to "LLM 参数",
            "values-zh-rTW" to "LLM 參數",
        )

        expected.forEach { (directory, value) ->
            val strings = sourceFile("app/src/main/res/$directory/strings.xml")
            assertEquals(
                "$directory settings_generation_desc",
                value,
                stringValue(strings, "settings_generation_desc"),
            )
        }
    }

    @Test
    fun `MCP page entry refresh is background single flight without polling`() {
        val page = sourceFile("app/src/main/java/com/newoether/agora/ui/settings/SettingsMcpPage.kt")
        val viewModel = sourceFile("app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt")
        val registry = sourceFile("app/src/main/java/com/newoether/agora/mcp/McpRegistry.kt")

        assertTrue(page.contains("LaunchedEffect(Unit)"))
        assertTrue(page.contains("viewModel.refreshMcpServersOnPageEntry()"))
        assertFalse(page.contains("delay("))
        assertTrue(viewModel.contains("fun refreshMcpServersOnPageEntry()"))
        assertTrue(viewModel.contains("mcpRegistry.refreshOnPageEntry()"))
        assertTrue(registry.contains("private val workDispatcher: CoroutineDispatcher = Dispatchers.IO"))
        assertTrue(registry.contains("scope.launch(workDispatcher)"))
        assertTrue(registry.contains("pendingBuilds"))
        assertTrue(registry.contains("McpRuntimeRefreshReason.PAGE_ENTRY"))
        assertTrue(registry.contains("isCurrentMcpRuntimeBuild("))
        val entryRefresh = registry.substringAfter("fun refreshOnPageEntry()")
            .substringBefore("suspend fun execute(")
        assertFalse(entryRefresh.contains("synchronized(lock)"))
        assertFalse(entryRefresh.contains("delay("))
    }

    @Test
    fun `ordinary segment detail does not repeat message error while Compact keeps its error`() {
        val messageItem = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/MessageItem.kt",
        )
        val segmentDetail = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/SegmentDetailSheet.kt",
        )

        val compactDetail = messageItem.substringAfter("if (showCompactDetail) {")
        val ordinarySegmentDetail = segmentDetail
            .substringAfter("internal fun MessageSegmentDetailHost(")
            .substringBefore("internal fun usesVirtualizedSegmentDetail(")

        assertTrue(compactDetail.contains("errorText = detailErrorText"))
        assertFalse(ordinarySegmentDetail.contains("errorText ="))
    }

    @Test
    fun `generation error and stopped bars share neutral body text presentation`() {
        val source = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/GenerationErrorBar.kt",
        )
        val errorBar = source
            .substringAfter("internal fun GenerationErrorBar(")
            .substringBefore("internal fun StoppedGenerationBar(")
        val stoppedBar = source.substringAfter("internal fun StoppedGenerationBar(")

        assertTrue(errorBar.contains("GenerationTerminalText("))
        assertTrue(stoppedBar.contains("GenerationTerminalText("))
        assertTrue(source.contains("style = ChatType.body"))
        assertTrue(source.contains(
            "color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)",
        ))
    }

    @Test
    fun `editing a user message scrolls its turn to focus with reduced motion fallback`() {
        val messageList = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/MessageList.kt",
        )
        val scrollActor = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/RobustLazyListScroll.kt",
        )
        val editFocus = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/MessageListEditScrollEffect.kt",
        ).substringAfter("LaunchedEffect(\n        conversationId,\n        editingMessageId,")

        assertTrue(messageList.contains(
            "val editingMessageIdState = remember(conversationId) { mutableStateOf<String?>(null) }",
        ))
        assertTrue(messageList.contains("editingMessageIdState = editingMessageIdState,"))
        assertTrue(editFocus.contains("messageListTurnIndex(turns, messageId)"))
        assertTrue(editFocus.contains("withFrameNanos { }"))
        assertTrue(editFocus.contains("cancelMutationAnchoring()"))
        assertTrue(editFocus.contains("140.dp.toPx()"))
        assertTrue(editFocus.contains("state.scrollToItem("))
        assertTrue(editFocus.contains("state.smoothSeekToItem("))
        assertTrue(scrollActor.contains("scroll(MutatePriority.Default)"))
    }

    @Test
    fun `user edit size owner includes the branch selector`() {
        val user = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/UserMessageBubble.kt",
        )
        val stableBlock = user
            .substringAfter("Column(\n        horizontalAlignment = Alignment.End,")
            .substringBefore("DropdownMenu(")

        assertEquals(1, Regex("Modifier\\.animateContentSize").findAll(user).count())
        assertTrue(stableBlock.contains("Modifier.animateContentSize"))
        assertTrue(user.substringAfter(stableBlock).contains("if (showBranchSelector"))
    }

    @Test
    fun `image transcription progress does not impersonate provider retry activity`() {
        val transcription = sourceFile(
            "app/src/main/java/com/newoether/agora/viewmodel/TranscriptionManager.kt",
        )
        val generation = sourceFile(
            "app/src/main/java/com/newoether/agora/viewmodel/GenerationOutputAccumulator.kt",
        )
        val assistant = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/AssistantInlineActivity.kt",
        )

        assertFalse(transcription.contains("retryText ="))
        assertTrue(generation.contains(
            "retryText = context.getString(R.string.generation_retry_attempt, event.attempt, event.maxAttempts)",
        ))
        assertTrue(assistant.contains(
            "!retryText.isNullOrBlank() -> AssistantInlineActivityMode.RETRY",
        ))
    }

    @Test
    fun `Context and Thinking segment labels are localized in every supported locale`() {
        val keys = listOf(
            "context_title",
            "context_desc",
            "thinking_segment_display_mode",
            "thinking_segment_display_mode_desc",
            "thinking_segment_display_card",
            "thinking_segment_display_bottom_sheet",
            "thinking_segments_title",
        )
        val expected = linkedMapOf(
            "values-ar" to listOf(
                "السياق", "إدارة السياق", "مقاطع التفكير",
                "اختر مكان فتح مقاطع التفكير", "بطاقة", "لوحة سفلية", "مقاطع التفكير",
            ),
            "values-de" to listOf(
                "Kontext", "Kontextverwaltung", "Denksegmente",
                "Auswählen, wo Denksegmente geöffnet werden", "Karte",
                "Unteres Dialogfeld", "Denksegmente",
            ),
            "values-es" to listOf(
                "Contexto", "Gestión del contexto", "Segmentos de razonamiento",
                "Elige dónde se abren los segmentos de razonamiento", "Tarjeta",
                "Hoja inferior", "Segmentos de razonamiento",
            ),
            "values-fr" to listOf(
                "Contexte", "Gestion du contexte", "Segments de réflexion",
                "Choisissez où ouvrir les segments de réflexion", "Carte",
                "Panneau inférieur", "Segments de réflexion",
            ),
            "values-ja" to listOf(
                "コンテキスト", "コンテキスト管理", "思考セグメント",
                "思考セグメントを開く場所を選択", "カード", "ボトムシート", "思考セグメント",
            ),
            "values-ko" to listOf(
                "컨텍스트", "컨텍스트 관리", "사고 세그먼트",
                "사고 세그먼트를 열 위치 선택", "카드", "하단 시트", "사고 세그먼트",
            ),
            "values-pt-rBR" to listOf(
                "Contexto", "Gerenciamento de contexto", "Segmentos de raciocínio",
                "Escolha onde abrir os segmentos de raciocínio", "Cartão",
                "Painel inferior", "Segmentos de raciocínio",
            ),
            "values-ru" to listOf(
                "Контекст", "Управление контекстом", "Сегменты рассуждений",
                "Выберите, где открывать сегменты рассуждений", "Карточка",
                "Нижняя панель", "Сегменты рассуждений",
            ),
            "values-vi" to listOf(
                "Ngữ cảnh", "Quản lý ngữ cảnh", "Phân đoạn suy luận",
                "Chọn nơi mở các phân đoạn suy luận", "Thẻ",
                "Bảng dưới", "Phân đoạn suy luận",
            ),
            "values-zh" to listOf(
                "上下文", "上下文管理", "思考片段",
                "选择思考片段的打开位置", "卡片", "底部面板", "思考片段",
            ),
            "values-zh-rTW" to listOf(
                "上下文", "上下文管理", "思考片段",
                "選擇思考片段的開啟位置", "卡片", "底部面板", "思考片段",
            ),
        )

        expected.forEach { (directory, values) ->
            val strings = sourceFile("app/src/main/res/$directory/strings.xml")
            keys.zip(values).forEach { (key, value) ->
                assertEquals("$directory $key", value, stringValue(strings, key))
            }
        }
    }

    @Test
    fun `transcription chooser lists concrete models only while nullable summary stays compatible`() {
        val source = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsTranscriptionPage.kt",
        )
        val chooser = source
            .substringAfter("if (showModelDialog)")
            .substringBefore("if (showAddDialog)")

        assertFalse(chooser.contains("transcription-model-none"))
        assertFalse(chooser.contains("setImageTranscriptionModel(null)"))
        assertTrue(source.contains("?: stringResource(R.string.transcription_no_model)"))
        assertTrue(source.contains("transcriptionModel == null"))
    }

    @Test
    fun `Appearance uses semantic groups and leading visuals for every setting row`() {
        val appearance = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsAppearancePage.kt",
        )
        val groupKeys = listOf(
            "appearance_theme_color",
            "appearance_motion_feedback",
            "appearance_chat_display",
            "font_title",
        )
        val groupIndices = groupKeys.map { key ->
            appearance.indexOf("title = stringResource(R.string.$key)")
        }

        assertTrue(groupIndices.all { it >= 0 })
        assertTrue(groupIndices.zipWithNext().all { (current, next) -> current < next })
        assertFalse(appearance.contains("R.string.appearance_interface"))
        assertEquals(4, Regex("SettingsGroup\\(").findAll(appearance).count())
        assertEquals(16, Regex("SettingsItem\\(").findAll(appearance).count())
        assertEquals(16, Regex("leadingContent\\s*=").findAll(appearance).count())
        listOf(
            "Palette",
            "Style",
            "BlurOn",
            "MotionPhotosOff",
            "Vibration",
            "VerticalAlignBottom",
            "Functions",
            "ViewAgenda",
            "TextFields",
            "UploadFile",
        ).forEach { icon ->
            assertTrue("Appearance is missing the $icon icon", appearance.contains("Icons.Default.$icon"))
        }
        val toolBlocksRow = appearance
            .substringAfter("headlineContent = { Text(stringResource(R.string.tool_call_display_mode)) }")
            .substringBefore("trailingContent =")
        val thinkingSegmentsRow = appearance
            .substringAfter("Text(stringResource(R.string.thinking_segment_display_mode))")
            .substringBefore("trailingContent =")
        val autoExpandRow = appearance
            .substringAfter("Text(stringResource(R.string.auto_expand_active_group))")
            .substringBefore("trailingContent =")
        assertTrue(toolBlocksRow.contains("painterResource(R.drawable.material_symbol_lists_24)"))
        assertTrue(thinkingSegmentsRow.contains("Icons.Default.ViewAgenda"))
        assertTrue(
            autoExpandRow.contains(
                "R.drawable.material_symbol_expand_content_24",
            ),
        )
        listOf("AccountTree", "Psychology", "UnfoldMore").forEach { oldIcon ->
            assertFalse("Appearance still uses $oldIcon", appearance.contains("Icons.Default.$oldIcon"))
        }
        assertTrue(appearance.contains(".background(currentPrimary)"))

        val toolBlocksIndex = appearance.indexOf("R.string.tool_call_display_mode")
        val thinkingSegmentIndex = appearance.indexOf("R.string.thinking_segment_display_mode")
        val autoExpandIndex = appearance.indexOf("R.string.auto_expand_active_group")
        assertTrue(toolBlocksIndex >= 0)
        assertTrue(thinkingSegmentIndex > toolBlocksIndex)
        assertTrue(autoExpandIndex > thinkingSegmentIndex)
    }

    @Test
    fun `Appearance removes dead detailed token usage UI threading but keeps persistence compatibility`() {
        val appearance = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsAppearancePage.kt",
        )
        val chatApp = sourceFile("app/src/main/java/com/newoether/agora/ui/chat/ChatApp.kt")
        val messageList = sourceFile("app/src/main/java/com/newoether/agora/ui/chat/MessageList.kt")
        val messageItem = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/MessageItem.kt",
        )
        val assistant = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/AssistantMessageContent.kt",
        )
        val settings = sourceFile(
            "app/src/main/java/com/newoether/agora/data/SettingsManager.kt",
        ) + sourceFile(
            "app/src/main/java/com/newoether/agora/data/PortableSettingsReset.kt",
        )
        val archive = sourceFile(
            "app/src/main/java/com/newoether/agora/data/PortableSettingsArchive.kt",
        )

        listOf(appearance, chatApp, messageList, messageItem, assistant).forEach {
            assertFalse(it.contains("detailedTokenUsage"))
        }
        assertFalse(appearance.contains("R.string.detailed_token_usage"))
        assertFalse(appearance.contains("setDetailedTokenUsage"))
        assertTrue(settings.contains("detailedTokenUsage"))
        assertTrue(settings.contains("saveDetailedTokenUsage"))
        assertTrue(archive.contains("\"detailedTokenUsage\""))
    }

    @Test
    fun `Stick to bottom is portable and gates only generation auto follow`() {
        val appearance = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsAppearancePage.kt",
        )
        val chatApp = sourceFile("app/src/main/java/com/newoether/agora/ui/chat/ChatApp.kt")
        val settings = sourceFile(
            "app/src/main/java/com/newoether/agora/data/SettingsManager.kt",
        ) + sourceFile(
            "app/src/main/java/com/newoether/agora/data/PortableSettingsReset.kt",
        )
        val repository = sourceFile(
            "app/src/main/java/com/newoether/agora/data/repository/SettingsRepository.kt",
        )
        val archive = sourceFile(
            "app/src/main/java/com/newoether/agora/data/PortableSettingsArchive.kt",
        )
        val availability = chatApp
            .substringAfter("val streamingFollowAvailability =")
            .substringBefore("Box(modifier = Modifier.fillMaxSize())")

        assertTrue(appearance.contains("R.string.stick_to_bottom"))
        assertTrue(appearance.contains("setStickToBottom"))
        assertTrue(settings.contains("it[STICK_TO_BOTTOM] ?: true"))
        assertTrue(settings.contains("saveStickToBottom"))
        assertTrue(settings.contains("prefs.remove(STICK_TO_BOTTOM)"))
        assertTrue(repository.contains("hot(settingsManager.stickToBottom, true)"))
        assertTrue(archive.contains("\"stickToBottom\""))
        assertTrue(archive.contains("saveStickToBottom"))
        assertFalse(availability.contains("stickToBottom"))
        assertTrue(chatApp.contains(
            "streamingFollowAvailability.enabled && stickToBottom",
        ))
    }

    @Test
    fun `Thinking display policy is configurable only outside Timeline and auto expands Grouped cards`() {
        val appearance = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsAppearancePage.kt",
        )
        val assistant = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/AssistantMessageContent.kt",
        )
        val messageItem = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/MessageItem.kt",
        )
        val model = sourceFile("app/src/main/java/com/newoether/agora/model/ChatMessage.kt")

        assertTrue(model.contains("fun isAvailableFor(toolCallDisplayMode: String?)"))
        assertTrue(model.contains("fun effectiveMode("))
        assertTrue(model.contains("fun allowsAutoExpand("))
        assertTrue(model.contains(
            "ToolCallDisplayModes.normalize(toolCallDisplayMode) != ToolCallDisplayModes.TIMELINE"
        ))
        assertTrue(model.contains("ToolCallDisplayModes.GROUPED_TIMELINE"))
        assertTrue(model.contains("normalize(thinkingSegmentDisplayMode) == CARD"))
        assertTrue(appearance.contains(
            "ThinkingSegmentDisplayModes.isAvailableFor(normalizedToolCallDisplayMode)"
        ))
        assertTrue(appearance.contains("ThinkingSegmentDisplayModes.allowsAutoExpand("))
        val toolBlocksIndex = appearance.indexOf("R.string.tool_call_display_mode")
        val thinkingSegmentIndex = appearance.indexOf("R.string.thinking_segment_display_mode")
        val autoExpandIndex = appearance.indexOf("R.string.auto_expand_active_group")
        assertTrue(toolBlocksIndex >= 0)
        assertTrue(thinkingSegmentIndex > toolBlocksIndex)
        assertTrue(autoExpandIndex > thinkingSegmentIndex)
        assertTrue(assistant.contains("ThinkingSegmentDisplayModes.effectiveMode("))
        assertTrue(messageItem.contains("ThinkingSegmentDisplayModes.allowsAutoExpand("))
    }

    @Test
    fun `Responses API rows use the API Format JSON icon`() {
        val provider = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsProviderDetailPage.kt",
        )

        assertTrue(provider.contains("SettingsIconContent(icon = Icons.Default.DataObject)"))
        val responseRows = provider
            .split("headlineContent = { Text(stringResource(R.string.responses_api)) },")
            .drop(1)
        assertEquals(2, responseRows.size)
        responseRows.forEach { rowSource ->
            val row = rowSource.substringBefore("trailingContent = {")
            assertTrue(row.contains("Icons.Default.DataObject"))
            assertTrue(row.contains("tint = MaterialTheme.colorScheme.primary"))
        }
    }

    @Test
    fun `provider base URL placeholder stays separate from its stored default`() {
        val provider = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsProviderDetailPage.kt",
        )

        assertTrue(provider.contains(
            "val defaultUrl = providerInstance?.defaultBaseUrl.orEmpty()"
        ))
        assertTrue(provider.contains(
            "val placeholderUrl = providerInstance?.baseUrlPlaceholder.orEmpty()"
        ))
        assertTrue(provider.contains(
            "val displayedUrl = savedUrl?.takeIf(String::isNotBlank) ?: defaultUrl"
        ))
        assertTrue(provider.contains("placeholder = { Text(placeholderUrl"))
    }

    @Test
    fun `Settings destination rows omit redundant arrows without losing behavior`() {
        val home = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt",
        )
        val twoPane = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsTwoPane.kt",
        )
        val shell = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsShellPage.kt",
        )
        val provider = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsProviderPage.kt",
        )

        assertFalse(home.contains("KeyboardArrowRight"))
        assertTrue(home.contains("onCategorySelected = { selectedCategory = it }"))
        assertTrue(twoPane.contains(".clickable { onCategorySelected(category.key) }"))
        assertTrue(twoPane.contains("Column(modifier = Modifier.weight(1f))"))

        val sandbox = shell
            .substringAfter("private fun SandboxSection(")
            .substringBefore("private fun SandboxNotSupportedSection(")
        assertFalse(sandbox.contains("Icons.Default.ChevronRight"))
        assertTrue(sandbox.contains("Switch(checked = sandboxEnabled"))
        assertTrue(sandbox.contains("modifier = Modifier.clickable { onManage() }"))

        assertFalse(provider.contains("KeyboardArrowRight"))
        assertTrue(provider.contains("modifier = Modifier.clickable { selectedProvider = name }"))
        assertTrue(provider.contains("config.protocol.displayName()"))
        assertTrue(provider.contains("modifier = Modifier.clickable { selectedProvider = config.name }"))
        assertTrue(provider.contains(
            "modifier = Modifier.clickable { selectedProvider = Constants.PROVIDER_LOCAL }"
        ))
        assertFalse(provider.contains("Spacer(modifier = Modifier.width(4.dp))"))
    }

    @Test
    fun `wide Settings navigation preserves user scroll and nested back behavior`() {
        val twoPane = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsTwoPane.kt",
        )
        val scaffold = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsScaffold.kt",
        )
        val main = sourceFile("app/src/main/java/com/newoether/agora/MainActivity.kt")
        val screenshotScript = sourceFile("scripts/generate-screenshots.ps1")

        assertFalse(twoPane.contains("scrollToItem("))
        assertFalse(twoPane.contains("rememberLazyListState"))
        assertTrue(twoPane.contains("private val SettingsNavigationPaneWidth = 400.dp"))
        assertTrue(twoPane.contains(".widthIn(max = SettingsContentMaxWidth)"))
        assertTrue(twoPane.contains("Crossfade("))
        assertTrue(twoPane.contains("label = \"settingsCategory\""))
        assertTrue(twoPane.contains("animateColorAsState("))
        assertTrue(twoPane.contains("label = \"settingsNavigationContainer\""))
        assertTrue(twoPane.contains("Spacer(Modifier.height(3.dp))"))
        assertTrue(twoPane.contains(".clipToBounds()"))
        assertTrue(twoPane.contains(
            "CompositionLocalProvider(LocalSettingsPaneBackButtonVisible provides false)"
        ))
        assertTrue(scaffold.contains("if (LocalSettingsPaneBackButtonVisible.current)"))
        assertTrue(scaffold.contains(".widthIn(max = SettingsContentMaxWidth)"))
        assertTrue(scaffold.contains(".align(Alignment.TopCenter)"))
        val titlePosition = scaffold
            .substringAfter("val titleX =")
            .substringBefore("// Opaque bar")
        assertTrue(titlePosition.contains("24.dp + (70.dp - 24.dp) * eased"))
        assertTrue(titlePosition.contains("24.dp"))
        assertTrue(scaffold.contains(".padding(horizontal = 24.dp)"))
        assertTrue(scaffold.contains("contentHorizontalPadding: Dp = 24.dp"))
        val providerPage = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsProviderPage.kt",
        )
        assertTrue(providerPage.contains("SettingsSecondaryPane {"))
        assertTrue(main.contains("onBack = { showScreenshotSettings = false }"))
        assertTrue(screenshotScript.contains("} finally {"))
        assertTrue(screenshotScript.contains(
            "Restore-GlobalSetting \$setting \$savedAnimationSettings[\$setting]"
        ))
        assertTrue(screenshotScript.contains("\"window_animation_scale\""))
        assertTrue(screenshotScript.contains("\"transition_animation_scale\""))
        assertTrue(screenshotScript.contains("\"animator_duration_scale\""))
    }

    @Test
    fun `shell confirmation code surface provides standalone Markdown locals`() {
        val main = sourceFile("app/src/main/java/com/newoether/agora/MainApplicationDialogs.kt")
        val assets = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/ChatMarkdownCode.kt",
        )
        val codeBlock = assets
            .substringAfter("internal fun ChatMarkdownCodeBlock(")
            .substringBefore("internal fun TrackStreamingHorizontalScroll(")

        assertTrue(
            main.contains("ChatMarkdownCodeBlock(code = pending.summary, autoWrap = autoWrapCodeBlocks)"),
        )
        assertTrue(main.contains("val autoWrapCodeBlocks by viewModel.settings.autoWrapCodeBlocks"))
        assertTrue(codeBlock.contains("if (autoWrap) codeStyle.copy(lineBreak = LineBreak.Simple)"))
        assertTrue(codeBlock.contains("CompositionLocalProvider("))
        assertTrue(codeBlock.contains("LocalMarkdownColors provides assets.renderContext.colors"))
        assertTrue(codeBlock.contains("LocalMarkdownDimens provides markdownDimens()"))
        assertTrue(codeBlock.contains("MarkdownCodeBackground("))
    }

    @Test
    fun `Automation groups exact execution and battery optimization in every locale`() {
        val page = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsAutomationPage.kt",
        )
        val manifest = sourceFile("app/src/main/AndroidManifest.xml")
        val backgroundGroup = page
            .substringAfter("title = stringResource(R.string.automation_background_execution)")
            .substringBefore("if (showDocFab)")

        assertTrue(backgroundGroup.contains("R.string.automation_exact_execution"))
        assertTrue(backgroundGroup.contains("R.string.automation_battery_optimization"))
        assertTrue(
            backgroundGroup.indexOf("R.string.automation_exact_execution") <
                backgroundGroup.indexOf("R.string.automation_battery_optimization"),
        )
        assertTrue(page.contains("isIgnoringBatteryOptimizations(context.packageName)"))
        assertTrue(page.contains("Lifecycle.Event.ON_RESUME"))
        assertTrue(page.contains("Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS"))
        assertFalse(manifest.contains("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))

        val directories = listOf(
            "values", "values-ar", "values-de", "values-es", "values-fr", "values-ja",
            "values-ko", "values-pt-rBR", "values-ru", "values-vi", "values-zh",
            "values-zh-rTW",
        )
        val keys = listOf(
            "automation_background_execution",
            "automation_battery_optimization",
            "automation_battery_optimization_ignored_desc",
            "automation_battery_optimization_active_desc",
        )
        directories.forEach { directory ->
            val fileName = "automation_strings.xml"
            val strings = sourceFile("app/src/main/res/$directory/$fileName")
            keys.forEach { key ->
                assertTrue("$directory $key", strings.contains("name=\"$key\""))
            }
            assertFalse("$directory obsolete scheduling category", strings.contains(
                "name=\"automation_scheduling\"",
            ))
        }
    }

    @Test
    fun `Once date picker keeps the Material modal height without taking over mode or IME`() {
        val source = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/tasks/TaskEditorSupportingComponents.kt",
        )
        val picker = source
            .substringAfter("internal fun TaskDatePickerDialog(")
            .substringBefore("internal fun TaskTimePickerDialog(")

        assertTrue(picker.contains("modifier = Modifier.height(568.dp)"))
        assertTrue(picker.contains("showModeToggle = true"))
        assertFalse(picker.contains("pendingCalendarMode"))
        assertFalse(picker.contains("displayMode = DatePickerDisplayMode"))
        assertFalse(picker.contains("LocalSoftwareKeyboardController"))
        assertFalse(picker.contains("delay("))
    }

    private fun stringValue(xml: String, key: String): String {
        val regex = Regex("""<string name="$key">([^<]*)</string>""")
        return requireNotNull(regex.find(xml)) { "Missing $key" }.groupValues[1]
    }
}
