package com.newoether.agora.ui.chat.message

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultContentSourceContractTest {
    @Test
    fun `Web Search results keep semantic tiers and use rounded full-row link ripples`() {
        val source = source(locateMainSourceRoot(), "ToolResultContent.kt")
        val segmentDetailSheet = source(locateMainSourceRoot(), "SegmentDetailSheet.kt")
        val webSearch = source
            .substringAfter("private fun WebSearchResult(")
            .substringBefore("private fun IndexedCodeLine(")

        assertTrue(webSearch.contains("style = ChatType.body,"))
        assertTrue(webSearch.contains("fontWeight = FontWeight.SemiBold"))
        assertTrue(webSearch.contains("style = ChatType.thoughtBody,"))
        assertTrue(webSearch.contains("style = ChatType.micro,"))
        assertTrue(webSearch.contains("HorizontalDivider("))
        assertFalse(webSearch.contains(".background("))
        assertTrue(webSearch.contains("val uriHandler = LocalUriHandler.current"))
        assertTrue(webSearch.contains("val resultShape = RoundedCornerShape(12.dp)"))
        val model = source(locateMainSourceRoot(), "ToolDetailPresentation.kt")
        assertTrue(model.contains("safeUrl = CitationPolicy.safeHttpUrl(url)"))
        assertTrue(webSearch.contains("val safeUrl = item.safeUrl"))
        assertTrue(webSearch.contains("enabled = safeUrl != null"))
        assertTrue(webSearch.contains("runCatching { uriHandler.openUri(destination) }"))
        assertTrue(
            source.contains("internal fun toolDetailHorizontalPadding(segment: MessageSegment): Dp"),
        )
        assertTrue(source.contains("ToolKind.WEB_SEARCH -> 16.dp"))
        assertTrue(source.contains("else -> 24.dp"))
        assertFalse(source.contains("segment.isImageGenerationSegment() -> 0.dp"))
        assertTrue(
            segmentDetailSheet.contains(
                ".padding(horizontal = toolDetailHorizontalPadding(detailSeg))",
            ),
        )
        assertTrue(
            segmentDetailSheet.contains(
                ".padding(horizontal = toolDetailHorizontalPadding(seg))",
            ),
        )
        val toolDetail = source
            .substringAfter("internal fun ToolDetailContent(")
            .substringBefore("internal fun toolDetailHorizontalPadding(")
        assertTrue(
            toolDetail.contains(
                "val contentAlignmentModifier = if (presentation.kind == ToolKind.WEB_SEARCH)",
            ),
        )
        assertFalse(
            toolDetail.contains(
                "segment.isImageGenerationSegment() -> Modifier.padding(horizontal = 24.dp)",
            ),
        )

        val clipPosition = webSearch.indexOf(".clip(resultShape)")
        val clickablePosition = webSearch.indexOf(".clickable(")
        val paddingPosition = webSearch.indexOf(
            ".padding(horizontal = 8.dp, vertical = 12.dp)",
        )
        assertTrue(clipPosition >= 0)
        assertTrue(clickablePosition > clipPosition)
        assertTrue(paddingPosition > clickablePosition)

        val titlePosition = webSearch.indexOf("text = title")
        val snippetPosition = webSearch.indexOf("text = snippet")
        val urlPosition = webSearch.indexOf("text = url")
        assertTrue(titlePosition >= 0)
        assertTrue(snippetPosition > titlePosition)
        assertTrue(urlPosition > snippetPosition)
    }

    @Test
    fun `Generated image thumbnail keeps ordered fixed lifecycle presentation`() {
        val root = locateMainSourceRoot()
        val source = source(root, "ToolResultContent.kt")
        val presentation = source(root, "AssistantMessagePresentation.kt")
        val timeline = source(root, "MessageItemTimeline.kt") +
            source(root, "TimelineSegmentsContent.kt") + presentation
        val assistant = source(root, "AssistantMessageContent.kt")
        val detailSheet = source(root, "SegmentDetailSheet.kt")
        val generatedSource = source(root, "GeneratedImageThumbnail.kt")
        val thumbnail = generatedSource
            .substringAfter("internal fun GeneratedImageThumbnail(")
            .substringBefore("private fun GeneratedImagePendingDots(")
        val pending = generatedSource
            .substringAfter("private fun GeneratedImagePendingDots(")
            .substringBefore("internal fun toolDetailHorizontalPadding(")

        assertTrue(thumbnail.contains("generatedImageAppearanceKey(messageId, detailIndex)"))
        assertTrue(thumbnail.contains("rememberSegmentAppearance("))
        assertTrue(thumbnail.contains("generationLifecycleAppearanceModifier("))
        assertTrue(thumbnail.contains("initialScale = SEGMENT_ENTER_INITIAL_SCALE"))
        assertTrue(thumbnail.contains("contentAlignment = Alignment.TopStart"))
        assertTrue(thumbnail.contains("val thumbnailSize = 300.dp"))
        assertTrue(thumbnail.contains("thumbnailSize.roundToPx().coerceAtLeast(1)"))
        assertTrue(thumbnail.contains("image?.path?.let { path ->"))
        assertTrue(thumbnail.contains("ImageRequest.Builder(context)"))
        assertTrue(thumbnail.contains(".data(path)"))
        assertTrue(thumbnail.contains(".size(thumbnailSizePx, thumbnailSizePx)"))
        assertTrue(thumbnail.contains("rememberAsyncImagePainter(model = imageRequest)"))
        assertFalse(thumbnail.contains("rememberAsyncImagePainter(model = image?.path)"))
        assertTrue(thumbnail.contains(".size(thumbnailSize)"))
        assertTrue(thumbnail.contains("Crossfade("))
        assertTrue(thumbnail.contains("imagePainter.state.toMediaLoadPresentation()"))
        assertTrue(thumbnail.contains("MEDIA_STATE_CROSSFADE_MILLIS"))
        assertTrue(thumbnail.contains("modifier = Modifier.fillMaxSize()"))
        assertTrue(thumbnail.contains("Icons.Default.BrokenImage"))
        assertTrue(thumbnail.contains("contentScale = ContentScale.Crop"))
        assertTrue(thumbnail.contains("onMediaClick(paths, 0)"))

        assertTrue(pending.contains("LocalAgoraMotionPolicy.current.allowContinuousMotion"))
        assertTrue(pending.contains("Offset(0.5f, 0.5f)"))
        assertTrue(pending.contains("val dotFieldInsetPx = with(density) { 16.dp.toPx() }"))
        assertTrue(pending.contains("val anchorInsetPx = with(density) { 32.dp.toPx() }"))
        assertTrue(pending.contains("if (!allowContinuousMotion)"))
        assertTrue(pending.contains("anchorStart = Offset(0.5f, 0.5f)"))
        assertTrue(pending.contains("anchorTarget = anchorStart"))
        assertTrue(pending.contains("while (true)"))
        assertTrue(pending.contains("x = random.nextFloat()"))
        assertTrue(pending.contains("y = random.nextFloat()"))
        assertTrue(pending.contains("progress.animateTo("))
        assertTrue(pending.contains("durationMillis = 1_300"))
        assertFalse(pending.contains("durationMillis = 2_600"))
        assertTrue(pending.contains("val maxRadiusPx = with(density) { 3.9.dp.toPx() }"))
        assertFalse(pending.contains("val maxRadiusPx = with(density) { 2.6.dp.toPx() }"))
        assertTrue(pending.contains("Canvas(modifier = modifier)"))
        assertTrue(pending.contains("val dotLeft = dotFieldInsetPx"))
        assertTrue(pending.contains("val dotRight = (size.width - dotFieldInsetPx)"))
        assertTrue(pending.contains("val dotCenterLeft = (dotLeft + maxRadiusPx)"))
        assertTrue(pending.contains("val dotCenterRight = (dotRight - maxRadiusPx)"))
        assertTrue(pending.contains("val columnCount = ((dotFieldWidth / spacingPx).toInt() + 1)"))
        assertTrue(pending.contains("val rowCount = ((dotFieldHeight / spacingPx).toInt() + 1)"))
        assertTrue(pending.contains("val columnStep = if (columnCount > 1)"))
        assertTrue(pending.contains("val rowStep = if (rowCount > 1)"))
        assertTrue(pending.contains("repeat(rowCount) { row ->"))
        assertTrue(pending.contains("repeat(columnCount) { column ->"))
        assertTrue(pending.contains("val anchorLeft = anchorInsetPx"))
        assertTrue(pending.contains("val anchorRight = (size.width - anchorInsetPx)"))
        assertTrue(pending.contains("val influenceDistancePx = with(density) { 150.dp.toPx() }"))
        assertTrue(
            pending.contains(
                "val distanceScale = (1f - distance / influenceDistancePx).coerceIn(0f, 1f)",
            ),
        )
        assertTrue(pending.contains("val influence = distanceScale * distanceScale"))
        assertFalse(pending.contains("maxDistance"))
        assertTrue(pending.contains("radius = minRadiusPx +"))
        assertFalse(pending.contains("center = anchorPx"))

        val imageResults = source
            .substringAfter("private fun ToolImageResults(")
            .substringBefore("private fun ToolSectionLabel(")
        assertTrue(source.contains("squareCrop = segment.isImageGenerationSegment()"))
        assertTrue(imageResults.contains("val previewHeight = if (squareCrop)"))
        assertTrue(imageResults.contains("maxWidth"))
        assertTrue(
            imageResults.contains(
                "val previewModifier = Modifier.fillMaxWidth().height(previewHeight)",
            ),
        )
        assertTrue(imageResults.contains("alignment = Alignment.Center"))
        assertTrue(
            imageResults.contains(
                "contentScale = if (squareCrop) ContentScale.Crop else ContentScale.Fit",
            ),
        )
        assertFalse(imageResults.contains("minOf(maxWidth, maxPreviewHeight)"))
        assertFalse(imageResults.contains("maxPreviewHeight"))
        assertFalse(detailSheet.contains("BoxWithConstraints(modifier = Modifier.fillMaxSize())"))
        assertFalse(detailSheet.contains("imagePreviewMaxHeight"))

        assertTrue(timeline.contains("val blockEnd = groupedInfoBlockEndExclusive(segments, index)"))
        assertTrue(timeline.contains("preserveInitialCompactIdentity"))
        assertTrue(timeline.contains("expansionKey = if (useInitialCompactIdentity)"))
        assertTrue(timeline.contains("compactSegmentBlockAppearanceKey(message.id)"))
        assertTrue(timeline.contains("collapseForImageBoundary = block.imageBoundary != null"))
        assertTrue(timeline.contains("GENERATED_IMAGE_BOUNDARY_GAP_DP = 8"))
        assertTrue(timeline.contains("if (collapseForImageBoundary) {"))
        assertTrue(timeline.contains("GENERATED_IMAGE_BOUNDARY_GAP_DP.dp"))
        assertTrue(
            timeline.contains("endsAtGeneratedImageBoundary = seg.isImageGenerationSegment()"),
        )
        assertTrue(timeline.contains("if (endsAtGeneratedImageBoundary)"))
        assertTrue(timeline.contains("else segmentGroupBottomPadding(groupPosition)"))
        assertTrue(timeline.contains("!collapseImageBoundaryOnAppearance &&"))
        assertTrue(timeline.contains("collapseImageBoundaryOnAppearance || !allowSpatialTransitions"))
        assertTrue(timeline.contains("allowSpatialTransitions && !collapseImageBoundaryOnAppearance"))
        assertTrue(timeline.contains("collapseImageBoundaryOnAppearance -> EnterTransition.None"))
        assertTrue(timeline.contains("collapseImageBoundaryOnAppearance -> ExitTransition.None"))
        assertTrue(timeline.contains("onGroupHeaderClick: ((List<Int>) -> Unit)? = null"))
        assertTrue(timeline.contains("(onGroupHeaderClick ?: onSegmentClick)(blockDetailIndices)"))
        assertTrue(timeline.contains("GeneratedImageThumbnail("))
        assertTrue(timeline.contains("onMediaClick = onMediaClick"))
        assertTrue(presentation.contains("val hasImageGenerationBoundary ="))
        assertTrue(presentation.contains("hasImageGenerationBoundary && mergedSegments.none"))
        assertTrue(presentation.contains("useTimelineSegments = hasImageGenerationBoundary ||"))
        assertTrue(assistant.contains("message.images.isNotEmpty()"))
    }

    @Test
    fun `Tool presentation resources keep locale key and placeholder parity`() {
        val resourceRoot = locateResourceRoot()
        val directories = listOf(
            "values", "values-ar", "values-de", "values-es", "values-fr", "values-ja",
            "values-ko", "values-pt-rBR", "values-ru", "values-vi", "values-zh", "values-zh-rTW",
        )
        val resources = directories.associateWith { directory ->
            val file = File(resourceRoot, "$directory/tool_presentation_strings.xml")
            assertTrue("Missing $directory tool presentation resources", file.isFile)
            parseStrings(file)
        }
        val defaults = resources.getValue("values")
        assertTrue(defaults.keys.containsAll(listOf(
            "tool_read_active_memory", "tool_reading_active_memory", "tool_read_active_memory_success",
            "tool_read_active_memory_empty", "tool_read_active_memory_failed",
            "tool_list_mcp_servers", "tool_listing_mcp_servers", "tool_listed_mcp_servers_default",
            "tool_no_mcp_servers_returned", "tool_list_mcp_servers_failed",
        )))
        resources.forEach { (directory, values) ->
            assertEquals("$directory keys", defaults.keys, values.keys)
            defaults.forEach { (key, defaultValue) ->
                assertEquals(
                    "$directory $key placeholders",
                    placeholders(defaultValue),
                    placeholders(values.getValue(key)),
                )
            }
            values.filterKeys { key ->
                key.startsWith("tool_progress_") ||
                    key.endsWith("ing_skill_subject") ||
                    key == "tool_listing_skills" || key == "tool_reading_active_memory" ||
                    key == "tool_listing_mcp_servers"
            }.forEach { (key, value) ->
                assertFalse("$directory $key uses ASCII ellipsis", value.contains("..."))
            }
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File(resourceRoot, "$directory/tool_presentation_strings.xml"))
            val plurals = document.getElementsByTagName("plurals")
            assertEquals("$directory quantity keys", 20, plurals.length)
            val quantityKeys = mutableSetOf<String>()
            repeat(plurals.length) { index ->
                val node = plurals.item(index)
                val key = node.attributes.getNamedItem("name").nodeValue
                assertTrue("$directory duplicate $key", quantityKeys.add(key))
                val items = node.childNodes
                var hasOther = false
                repeat(items.length) { itemIndex ->
                    val item = items.item(itemIndex)
                    if (item.nodeName == "item") {
                        if (item.attributes.getNamedItem("quantity").nodeValue == "other") hasOther = true
                        assertEquals("$directory $key quantity placeholders",
                            when (key) {
                                "tool_web_search_done", "tool_conversation_search_done_for" -> setOf("%1\$d", "%2\$s")
                                "tool_questions_mixed" -> setOf("%1\$d", "%2\$d")
                                else -> setOf("%1\$d")
                            }, placeholders(item.textContent))
                    }
                }
                assertTrue("$directory $key missing other", hasOther)
            }
            assertEquals("$directory quantity key parity", setOf(
                "tool_listed_mcp_servers", "tool_found_mcp_tools",
                "tool_lookup_count", "tool_listed_skills", "tool_web_search_done", "tool_web_search_result_count",
                "tool_conversation_search_done_default", "tool_conversation_search_done_for", "tool_listed_conversations",
                "tool_shell_list_count", "tool_shell_job_count", "tool_found_files", "tool_searched_file",
                "tool_listed_task_count", "tool_reading_files_count", "tool_read_files_count",
                "tool_questions_queued", "tool_questions_answered", "tool_questions_skipped", "tool_questions_mixed",
            ), quantityKeys)
        }
    }
    @Test
    fun `Completed summaries never fabricate unknown counts`() {
        val source = source(locateMainSourceRoot(), "MessageItemToolLabels.kt")
            .substringAfter("private fun Resources.completedSummary(")
            .substringBefore("private fun Resources.failedSummary(")
        assertFalse(source.contains("?: 0"))
        listOf(
            "tool_listed_memories_default",
            "tool_conversation_search_done_no_count",
            "tool_listed_conversations_default",
            "tool_listed_shells_default",
            "tool_listed_shell_jobs_default",
            "tool_found_files_default",
            "tool_found_matches_default",
        ).forEach { key -> assertTrue("Missing unknown-count fallback $key", source.contains(key)) }
    }
    @Test
    fun `Remote file operation labels exist once across each locale directory`() {
        val root = locateResourceRoot()
        val keys = listOf("tool_edit_operation_update", "tool_edit_operation_add",
            "tool_edit_operation_delete", "tool_edit_operation_move")
        val defaults = parseStrings(File(root, "values/tool_presentation_strings.xml"))
        val directories = root.listFiles().orEmpty().filter { it.isDirectory &&
            (it.name == "values" || it.name.matches(Regex("values-[a-z]{2}(-r[A-Z]{2})?"))) }
        assertEquals(12, directories.size)
        directories.forEach { directory ->
            val entries = directory.listFiles().orEmpty().filter { it.extension == "xml" }
                .flatMap { file ->
                    val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                        .getElementsByTagName("string")
                    (0 until nodes.length).map { index -> nodes.item(index).let { node ->
                        node.attributes.getNamedItem("name").nodeValue to node.textContent
                    } }
                }.groupBy({ it.first }, { it.second })
            assertTrue("${directory.name} aggregation was empty", entries.size > 1000)
            keys.forEach { key ->
                val values = entries[key].orEmpty()
                assertEquals("${directory.name} $key missing or duplicated", 1, values.size)
                assertTrue("${directory.name} $key empty", values.single().isNotBlank())
                assertEquals(placeholders(defaults.getValue(key)), placeholders(values.single()))
            }
        }
        assertEquals(listOf("Update", "Add", "Delete", "Move"), keys.map { defaults.getValue(it) })
    }

    private fun parseStrings(file: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return buildMap {
            repeat(nodes.length) { index ->
                val node = nodes.item(index)
                put(node.attributes.getNamedItem("name").nodeValue, node.textContent)
            }
        }
    }
    private fun placeholders(value: String): Set<String> =
        Regex("""%\d+\$[a-zA-Z]""").findAll(value).map { it.value }.toSet()
    private fun locateResourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/res"),
                File(directory, "src/main/res"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main resource directory")
    }
    private fun source(root: File, name: String): String =
        File(root, "com/newoether/agora/ui/chat/message/$name")
            .readText()
            .replace("\r\n", "\n")

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
