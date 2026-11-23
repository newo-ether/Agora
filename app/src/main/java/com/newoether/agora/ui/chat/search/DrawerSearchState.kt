package com.newoether.agora.ui.chat.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.settledSearchMethod
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.delay

/**
 * State holder for the conversation-drawer search subsystem: owns the query text, the
 * debounced result set, and whether search mode is active. The debounce + actual search
 * (semantic vs. literal) run in [rememberDrawerSearchState]; the holder exposes read-only
 * results/isActive so the drawer UI only reads them.
 */
internal class DrawerSearchState(
    private val viewModel: ChatViewModel
) {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Pair<MessageEntity, Float>>>(emptyList())
        private set
    var isActive by mutableStateOf(false)
        private set
    var isSearching by mutableStateOf(false)
        private set
    // Each query change cancels the previous run. Only the newest run may clear [isSearching];
    // a cancelled run finishing late must not hide the indicator of the run that replaced it.
    private var searchGeneration = 0

    suspend fun runSearch(method: String) {
        val generation = ++searchGeneration
        if (query.isBlank()) {
            results = emptyList()
            isActive = false
            isSearching = false
            return
        }

        isSearching = true
        try {
            delay(200)
            if (query.isNotBlank()) {
                results = if (method == Constants.SEARCH_METHOD_RAG)
                    viewModel.semanticSearch(query)
                else
                    viewModel.searchMessages(query).map { it to 0f }
                isActive = true
            }
        } finally {
            if (generation == searchGeneration) isSearching = false
        }
    }
}

@Composable
internal fun rememberDrawerSearchState(viewModel: ChatViewModel): DrawerSearchState {
    val state = remember(viewModel) { DrawerSearchState(viewModel) }
    val manualSearchMethod by viewModel.settings.manualSearchMethod.collectAsState()
    val activeEmbeddingModel by viewModel.ragManager.activeEmbeddingModel.collectAsState()
    LaunchedEffect(state.query) {
        // A stored semantic selection outlives its embedding model; settle it the same way the
        // settings page and the WebUI search path do.
        state.runSearch(settledSearchMethod(manualSearchMethod, activeEmbeddingModel != null))
    }
    return state
}
