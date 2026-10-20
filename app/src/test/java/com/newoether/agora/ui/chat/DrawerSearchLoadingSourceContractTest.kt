package com.newoether.agora.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerSearchLoadingSourceContractTest {
    @Test
    fun `keyword search uses a single SQLite LIKE escape character`() {
        val dao = source("data/local/ChatSearchDao.kt")
        val repository = source("data/repository/ConversationMessageSearch.kt")
        val globalSearchQuery = dao
            .substringBefore("suspend fun searchMessages")
            .substringAfterLast("@Query(")
        val kotlinEncodedSingleBackslashEscape = """ESCAPE '\\'"""
        val kotlinEncodedDoubleBackslashEscape = """ESCAPE '\\\\'"""

        assertEquals(2, globalSearchQuery.count(kotlinEncodedSingleBackslashEscape))
        assertFalse(globalSearchQuery.contains(kotlinEncodedDoubleBackslashEscape))
        assertTrue(
            repository.contains(
                """query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")""",
            ),
        )
    }

    @Test
    fun `drawer list uses a bounded projection with truthful fade-backed loading and search states`() {
        val dao = source("data/local/ChatDao.kt")
        val repository = source("data/repository/ConversationRepository.kt")
        val viewModel = source("viewmodel/ChatViewModel.kt")
        val normalizedViewModel = viewModel.replace("\r\n", "\n")
        val drawer = source("ui/chat/ChatDrawerContent.kt")
        val searchState = source("ui/chat/search/DrawerSearchState.kt")
        val normalizedSearchState = searchState.replace("\r\n", "\n")
        val searchBar = source("ui/chat/search/DrawerSearchBar.kt")
        val searchResultItem = source("ui/chat/search/ChatSearchResultItem.kt")

        assertTrue(dao.contains("SELECT id, title, systemPromptId, modelId, taskId, origin, graduated, hasUnreadGeneration, isPinned, NULL AS selectedBranchesJson, parentConversationId FROM conversations"))
        assertTrue(dao.contains("WHERE taskId IS NULL AND parentConversationId IS NULL ORDER BY lastUpdated DESC"))
        assertTrue(dao.contains("WHERE taskId IS NULL AND parentConversationId IS NULL ORDER BY isPinned DESC"))
        assertTrue(dao.contains("WHERE taskId IS NULL AND parentConversationId IS NULL AND id IN (:ids)"))
        assertTrue(dao.contains("fun getAllConversations(): Flow<List<ChatConversation>>"))
        assertFalse(dao.contains("SELECT * FROM conversations WHERE taskId IS NULL ORDER BY lastUpdated DESC"))
        assertTrue(repository.contains("fun getAllConversations(): Flow<List<ChatConversation>> = chatDao.getAllConversations()"))
        assertTrue(viewModel.contains("StateFlow<List<ChatConversation>?>"))
        assertTrue(viewModel.contains("stateIn(viewModelScope, SharingStarted.Eagerly, null)"))
        assertTrue(drawer.contains("val isConversationListLoading = conversationList == null"))
        assertTrue(drawer.contains("visible = isConversationListLoading"))
        assertTrue(drawer.contains("visible = !isConversationListLoading"))
        assertTrue(drawer.split("enter = fadeIn(tween(180))").size - 1 == 2)
        assertTrue(drawer.split("exit = fadeOut(tween(180))").size - 1 == 2)
        assertTrue(drawer.contains("modifier = Modifier.size(36.dp)"))
        assertTrue(drawer.contains("strokeWidth = 4.dp"))
        assertTrue(drawer.contains("Crossfade("))
        assertTrue(
            drawer.contains("targetState = search.results.takeIf { search.isActive }"),
        )
        assertTrue(drawer.contains("?.filter { \"conversation:\${it.first.conversationId}\" in conversationsByKey }"))
        assertTrue(drawer.contains("val isSelected = conversation.id == drawerConversationId"))
        assertTrue(drawer.contains("if (!isSelected) viewModel.selectConversation(conversation.id)"))
        assertTrue(drawer.contains("if (convId != drawerConversationId) viewModel.selectConversation(convId)"))
        assertTrue(drawer.contains("animationSpec = tween(180)"))
        assertTrue(drawer.contains("label = \"DrawerConversationContentTransition\""))
        assertTrue(drawer.split("rememberLazyListState()").size - 1 == 2)
        assertTrue(drawer.contains("state = conversationListState"))
        assertTrue(drawer.contains("state = searchListState"))
        assertTrue(drawer.split("LazyColumn(").size - 1 == 2)
        assertTrue(drawer.contains("key = { \"search:\${it.key}\" }"))
        assertTrue(drawer.contains("key = { it },"))
        assertTrue(source("ui/chat/DrawerConversationPolicy.kt").contains("add(\"conversation:\${it.id}\")"))
        assertTrue(drawer.split("fadeInSpec = null").size - 1 == 1)
        assertTrue(drawer.split("fadeOutSpec = tween(180)").size - 1 == 1)
        val normalizedDrawer = drawer.replace("\r\n", "\n")
        assertTrue(normalizedDrawer.split("placementSpec = if (").size - 1 == 1)
        val placementBlock = normalizedDrawer
            .substringAfter("placementSpec = if (")
            .substringBefore("fadeOutSpec = tween(180)")
        assertTrue(placementBlock.contains("motionPolicy.allowSpatialTransitions"))
        assertTrue(placementBlock.contains("tween(400)"))
        assertTrue(placementBlock.contains("null"))
        assertFalse(normalizedDrawer.contains("placementSpec = null"))
        assertTrue(drawer.split("conversationListState.requestScrollToItem(").size - 1 == 1)
        val numericReorderAnchorBlock = normalizedDrawer
            .substringAfter("SideEffect {")
            .substringBefore("val atTop by remember")
        assertTrue(numericReorderAnchorBlock.contains("!search.isActive"))
        assertTrue(
            numericReorderAnchorBlock.contains(
                "conversationListState.layoutInfo.totalItemsCount == conversationKeys.size",
            ),
        )
        assertTrue(numericReorderAnchorBlock.contains("indexedConversationId != firstVisibleConversationId"))
        assertTrue(
            numericReorderAnchorBlock.contains(
                "firstVisibleConversationId in conversationKeys",
            ),
        )
        assertTrue(numericReorderAnchorBlock.contains("firstVisibleIndex,"))
        assertTrue(
            numericReorderAnchorBlock.contains(
                "conversationListState.firstVisibleItemScrollOffset,",
            ),
        )
        assertTrue(drawer.contains("viewModel.firstMessageCommitted.collect { conversationId ->"))
        assertTrue(
            drawer.split(
                "if (viewModel.currentConversationId.value != conversationId) return@collect",
            ).size - 1 == 2,
        )
        assertTrue(drawer.contains("currentConversations.firstOrNull()?.id == conversationId"))
        assertTrue(drawer.contains("conversationListState.animateToAbsoluteTop("))
        assertTrue(drawer.contains("feedbackSpec = SendFeedbackScrollSpec"))
        assertTrue(drawer.contains("latestMotionPolicy.allowProgrammaticScrollMotion"))
        assertTrue(drawer.contains("conversationListState.scrollToItem(0)"))
        assertTrue(normalizedViewModel.contains("private val _firstMessageCommitted = MutableSharedFlow<String>("))
        assertTrue(normalizedViewModel.contains("val firstMessageCommitted = _firstMessageCommitted.asSharedFlow()"))
        assertTrue(
            normalizedViewModel.contains(
                "            if (selected) {\n" +
                    "                // The send's own bottom scroll handles the first message; skip the open scroll.\n" +
                    "                scrollRequests.suppressNextOpenScroll = true\n" +
                    "                _firstMessageCommitted.tryEmit(conversationId)",
            ),
        )
        assertTrue(drawer.contains("val visibleConversationTitle = replaceCustomProviderIdsForDisplay("))
        val titleCrossfadeBlock = normalizedDrawer
            .substringAfter("targetState = visibleConversationTitle")
            .substringBefore("label = \"DrawerConversationTitleCrossfade\"")
        assertTrue(titleCrossfadeBlock.contains("durationMillis = 200"))
        assertTrue(titleCrossfadeBlock.contains("easing = FastOutSlowInEasing"))
        assertTrue(titleCrossfadeBlock.contains("modifier = Modifier.weight(1f)"))
        assertTrue(drawer.split("label = \"DrawerConversationTitleCrossfade\"").size - 1 == 1)
        assertTrue(drawer.split(".animateItem(").size - 1 == 1)
        assertFalse(drawer.contains("fadeInSpec = tween(180)"))
        assertTrue(drawer.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue(searchResultItem.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue(searchState.contains("var isSearching by mutableStateOf(false)"))
        assertTrue(searchState.contains("isSearching = true"))
        // Only the newest run clears the indicator; a cancelled run finishing late cannot hide it.
        assertTrue(normalizedSearchState.contains("val generation = ++searchGeneration"))
        assertTrue(normalizedSearchState.contains("} finally {\n            if (generation == searchGeneration) isSearching = false"))
        assertTrue(searchBar.contains("searching: Boolean = false"))
        assertTrue(searchBar.contains("visible = searching"))
        assertTrue(searchBar.contains("CircularProgressIndicator("))
    }

    private fun source(relative: String): String =
        File(mainSourceRoot(), "com/newoether/agora/$relative").readText()

    private fun mainSourceRoot(): File = locate("app/src/main/java")

    private fun String.count(needle: String): Int = split(needle).size - 1

    private fun locate(relative: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            File(directory, relative).takeIf(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relative")
    }
}
