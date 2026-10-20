package com.newoether.agora.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.newoether.agora.model.ChatMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Stable
internal class ConversationInteractionState internal constructor(
    initialSearchActive: Boolean = false,
    initialSearchQuery: String = "",
    initialSearchMatchIndex: Int = -1,
) {
    var searchActive by mutableStateOf(initialSearchActive)
        private set
    var searchQuery by mutableStateOf(initialSearchQuery)
        private set
    var submittedSearchQuery by mutableStateOf(initialSearchQuery)
        private set
    internal fun submitSearch() { submittedSearchQuery = searchQuery }
    var searchMatchIndex by mutableIntStateOf(initialSearchMatchIndex)
        private set
    var shareSelectionActive by mutableStateOf(false)
        private set
    var selectedShareMessageIds by mutableStateOf<Set<String>>(emptySet())
        private set

    internal var searchSelectedMatchKey by mutableStateOf<String?>(null)
        private set
    internal var searchScrollRequestKey by mutableStateOf<Int?>(null)
        private set
    private var searchScrollSequence = 0

    internal fun requestSearchScroll() {
        searchScrollRequestKey = ++searchScrollSequence
    }

    private val searchMatchDistances = mutableStateMapOf<String, Float>()
    private val searchTurnIndexByMessageId = mutableStateMapOf<String, Int>()
    private fun resetForConversation() {
        searchActive = false
        searchQuery = ""
        submittedSearchQuery = ""
        replaceSearchMatchIndex(-1)
        searchScrollRequestKey = null
        searchMatchDistances.clear()
        searchTurnIndexByMessageId.clear()
        shareSelectionActive = false
        selectedShareMessageIds = emptySet()
    }

    private fun replaceSearchMatchIndex(index: Int, matches: List<ConversationSearchMatch> = emptyList()) {
        searchMatchIndex = index
        searchSelectedMatchKey = matches.getOrNull(index)?.key
    }

    internal fun selectSearchMatch(index: Int, matches: List<ConversationSearchMatch>): Boolean {
        if (index !in matches.indices) return false
        replaceSearchMatchIndex(index, matches)
        requestSearchScroll()
        return true
    }

    private fun reconcileShareSelection(selectableIds: Set<String>) {
        selectedShareMessageIds = selectedShareMessageIds.intersect(selectableIds)
    }

    internal fun updateSearchQuery(query: String) {
        if (query == searchQuery) return
        searchMatchDistances.clear()
        replaceSearchMatchIndex(-1)
        searchScrollRequestKey = null
        searchQuery = query
        if (query.isBlank()) submittedSearchQuery = ""
    }
    internal fun recordSearchMatchDistance(key: String, distance: Float) {
        searchMatchDistances[key] = distance
    }
    internal fun visibleSearchMatchDistances(
        matches: List<ConversationSearchMatch>,
    ): Map<String, Float> = searchMatchDistances
        .filterKeys { key -> matches.any { match -> match.key == key } }
    internal fun recordSearchTurns(turns: List<MessageListTurn>) {
        searchTurnIndexByMessageId.clear()
        turns.forEachIndexed { index, turn ->
            turn.messages.forEach { message -> searchTurnIndexByMessageId[message.id] = index }
        }
    }

    internal fun searchTurnIndexes(
        matches: List<ConversationSearchMatch>,
    ): Map<String, Int> = searchTurnIndexByMessageId
        .filterKeys { messageId -> matches.any { match -> match.messageId == messageId } }

    internal fun dismissSearch() {
        searchActive = false
        searchQuery = ""
        submittedSearchQuery = ""
        replaceSearchMatchIndex(-1)
        searchScrollRequestKey = null
        searchMatchDistances.clear()
    }

    internal fun activateSearch() {
        shareSelectionActive = false
        selectedShareMessageIds = emptySet()
        searchActive = true
    }

    internal fun activateShareSelection() {
        dismissSearch()
        selectedShareMessageIds = emptySet()
        shareSelectionActive = true
    }

    internal fun dismissShareSelection() {
        shareSelectionActive = false
        selectedShareMessageIds = emptySet()
    }

    internal fun toggleShareMessage(messageId: String) {
        selectedShareMessageIds =
            if (messageId in selectedShareMessageIds) {
                selectedShareMessageIds - messageId
            } else {
                selectedShareMessageIds + messageId
            }
    }

    internal fun toggleAllShareMessages(selectableIds: Set<String>) {
        selectedShareMessageIds =
            if (selectableIds.isNotEmpty() && selectedShareMessageIds.containsAll(selectableIds)) {
                emptySet()
            } else {
                selectableIds
            }
    }

    internal fun takeShareSelection(): Set<String> {
        val selection = selectedShareMessageIds
        if (selection.isNotEmpty()) dismissShareSelection()
        return selection
    }

    @Composable
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    internal fun project(
        currentConversationId: String?,
        messages: State<List<ChatMessage>>,
        listState: LazyListState,
        searchMessages: suspend (String, List<String>) -> List<ChatMessage>,
        searchAllMessages: ((String) -> Flow<List<ConversationSearchMatch>>)? = null,
    ): ConversationInteractionProjection {
        val eInk = com.newoether.agora.ui.motion.LocalAgoraMotionPolicy.current.eInkMode
        val effectiveQuery = if (eInk) submittedSearchQuery else searchQuery
        val selectedPathSearchMessages = remember(messages.value) {
            messages.value.filter(::isConversationSearchBodyEligible)
        }
        val selectedPathSearchMessageIds = remember(selectedPathSearchMessages) {
            selectedPathSearchMessages.map(ChatMessage::id)
        }
        val selectedPathSearchRevision = remember(selectedPathSearchMessages) {
            selectedPathSearchMessages.map { message -> message.id to message.text }
        }
        val searchMatches by produceState(
            initialValue = emptyList(),
            currentConversationId,
            searchActive,
            effectiveQuery,
            selectedPathSearchRevision.takeIf { searchAllMessages == null && !eInk },
            searchMessages.takeIf { searchAllMessages == null },
            searchAllMessages,
        ) {
            value = emptyList()
            if (searchActive && currentConversationId != null && effectiveQuery.isNotBlank()) {
                if (searchAllMessages != null) {
                    searchAllMessages(effectiveQuery).collect { value = it }
                } else value = scanConversationSearchMatches(
                    selectedPathMessageIds = selectedPathSearchMessageIds,
                    query = effectiveQuery,
                    loadMessages = { messageIds ->
                        searchMessages(currentConversationId, messageIds)
                    },
                )
            }
        }
        val messagesForSelection = if (shareSelectionActive) messages.value else emptyList()
        val selectableShareMessageIds = remember(messagesForSelection) {
            messagesForSelection.mapTo(linkedSetOf()) { it.id }
        }

        LaunchedEffect(searchActive, effectiveQuery, searchMatches, currentConversationId) {
            val retainedIndex = searchSelectedMatchKey?.let { key ->
                searchMatches.indexOfFirst { it.key == key }.takeIf { it >= 0 }
            }
            if (!searchActive || effectiveQuery.isBlank() || searchMatches.isEmpty()) {
                replaceSearchMatchIndex(-1)
                return@LaunchedEffect
            }
            if (searchAllMessages != null && retainedIndex != null) {
                replaceSearchMatchIndex(retainedIndex, searchMatches)
                return@LaunchedEffect
            }
            val retainedVisibleDistances = visibleSearchMatchDistances(searchMatches)
            val visibleDistances = if (retainedVisibleDistances.isNotEmpty()) {
                retainedVisibleDistances
            } else {
                withTimeoutOrNull(250L) {
                    snapshotFlow { visibleSearchMatchDistances(searchMatches) }
                        .filter { it.isNotEmpty() }
                        .debounce(32L)
                        .first()
                }.orEmpty()
            }
            val exactVisibleIndex = nearestVisibleConversationSearchMatchIndex(
                searchMatches,
                visibleDistances,
            )
            if (exactVisibleIndex != null) {
                replaceSearchMatchIndex(exactVisibleIndex, searchMatches)
                return@LaunchedEffect
            }
            val actualTurnIndexes = snapshotFlow { searchTurnIndexes(searchMatches) }
                .filter { it.isNotEmpty() }
                .first()
            val layout = listState.layoutInfo
            val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            val anchorTurn = layout.visibleItemsInfo
                .minByOrNull { item ->
                    kotlin.math.abs((item.offset + item.size / 2) - viewportCenter)
                }
                ?.index
                ?: listState.firstVisibleItemIndex
            replaceSearchMatchIndex(
                nearestConversationSearchMatchIndex(
                    matches = searchMatches,
                    turnIndexByMessageId = actualTurnIndexes,
                    anchorTurnIndex = anchorTurn,
                ),
                searchMatches,
            )
        }
        val latestSearchMatches by rememberUpdatedState(searchMatches)
        LaunchedEffect(currentConversationId, searchActive, effectiveQuery, searchAllMessages != null) {
            if (searchAllMessages == null || !searchActive || effectiveQuery.isBlank()) return@LaunchedEffect
            delay(300L)
            // One query owns one automatic positioning request. Pages only update results.
            snapshotFlow {
                searchSelectedMatchKey?.takeIf { key -> latestSearchMatches.any { it.key == key } }
            }.first { it != null }
            if (searchScrollRequestKey == null) requestSearchScroll()
        }
        LaunchedEffect(currentConversationId) {
            resetForConversation()
        }
        LaunchedEffect(selectableShareMessageIds) {
            reconcileShareSelection(selectableShareMessageIds)
        }

        return remember(
            this,
            selectableShareMessageIds,
            searchMatches,
            effectiveQuery,
        ) {
            ConversationInteractionProjection(
                state = this,
                selectableShareMessageIds = selectableShareMessageIds,
                searchMatches = searchMatches,
                renderedSearchQuery = effectiveQuery,
            )
        }
    }

    companion object {
        val Saver = listSaver<ConversationInteractionState, Any>(
            save = { state -> listOf(state.searchActive, state.searchQuery) },
            restore = { restored ->
                ConversationInteractionState(
                    initialSearchActive = restored[0] as Boolean,
                    initialSearchQuery = restored[1] as String,
                )
            },
        )
    }
}

@Stable
internal class ConversationInteractionProjection internal constructor(
    private val state: ConversationInteractionState,
    val selectableShareMessageIds: Set<String>,
    val searchMatches: List<ConversationSearchMatch>,
    val renderedSearchQuery: String = state.searchQuery,
) {
    val searchActive: Boolean get() = state.searchActive
    val searchQuery: String get() = state.searchQuery
    val searchMatchIndex: Int get() = state.searchSelectedMatchKey?.let { key ->
        searchMatches.indexOfFirst { it.key == key }
    } ?: state.searchMatchIndex
    val searchScrollRequestKey: Int? get() = state.searchScrollRequestKey
    val shareSelectionActive: Boolean get() = state.shareSelectionActive
    val selectedShareMessageIds: Set<String> get() = state.selectedShareMessageIds

    fun updateSearchQuery(query: String) = state.updateSearchQuery(query)
    fun submitSearch() = state.submitSearch()

    fun previousSearchMatch(): Boolean =
        searchMatchIndex > 0 && state.selectSearchMatch(searchMatchIndex - 1, searchMatches)

    fun nextSearchMatch(): Boolean =
        searchMatchIndex in 0 until searchMatches.lastIndex &&
            state.selectSearchMatch(searchMatchIndex + 1, searchMatches)

    fun dismissSearch() = state.dismissSearch()

    fun activateSearch() = state.activateSearch()

    fun activateShareSelection() = state.activateShareSelection()

    fun dismissShareSelection() = state.dismissShareSelection()

    fun toggleShareMessage(messageId: String) = state.toggleShareMessage(messageId)

    fun toggleAllShareMessages() = state.toggleAllShareMessages(selectableShareMessageIds)

    fun takeShareSelection(): Set<String> = state.takeShareSelection()

    fun recordSearchMatchDistance(key: String, distance: Float) =
        state.recordSearchMatchDistance(key, distance)
    fun recordSearchTurns(turns: List<MessageListTurn>) = state.recordSearchTurns(turns)

}

@Composable
internal fun rememberConversationInteractionState(
    currentConversationId: String?,
    messages: State<List<ChatMessage>>,
    listState: LazyListState,
    searchMessages: suspend (String, List<String>) -> List<ChatMessage> = { _, _ -> emptyList() },
    searchAllMessages: ((String) -> Flow<List<ConversationSearchMatch>>)? = null,
): ConversationInteractionProjection {
    val state = rememberSaveable(saver = ConversationInteractionState.Saver) {
        ConversationInteractionState()
    }
    return state.project(
        currentConversationId = currentConversationId,
        messages = messages,
        listState = listState,
        searchMessages = searchMessages,
        searchAllMessages = searchAllMessages,
    )
}
