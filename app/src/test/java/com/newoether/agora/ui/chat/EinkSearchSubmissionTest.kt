package com.newoether.agora.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class EinkSearchSubmissionTest {
    @Test
    fun typingDoesNotReplaceTheSubmittedQueryAndClearingRemovesIt() {
        val state = ConversationInteractionState()
        state.updateSearchQuery("first")
        assertEquals("", state.submittedSearchQuery)
        state.submitSearch()
        assertEquals("first", state.submittedSearchQuery)
        state.updateSearchQuery("second")
        assertEquals("first", state.submittedSearchQuery)
        state.submitSearch()
        assertEquals("second", state.submittedSearchQuery)
        state.updateSearchQuery("")
        assertEquals("", state.submittedSearchQuery)
    }
}
