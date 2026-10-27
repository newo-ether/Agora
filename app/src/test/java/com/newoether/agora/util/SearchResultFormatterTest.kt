package com.newoether.agora.util

import android.content.Context
import com.newoether.agora.R
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class SearchResultFormatterTest {

    private fun mockGetString(resId: Int, vararg args: Any?): String = when (resId) {
        R.string.search_no_query -> "No query provided"
        R.string.search_no_results -> "No results found"
        R.string.search_no_response -> "No response received"
        R.string.unknown -> "Unknown"
        R.string.search_untitled -> "Untitled"
        R.string.shell_no_devices -> "No shell devices configured"
        R.string.conversation_list_empty -> "No conversations found"
        R.string.conversation_list_more -> "More conversations available"
        R.string.conversation_read_empty -> "No messages"
        R.string.conversation_read_more -> "More messages available"
        R.string.conversation_tools_more -> "More tool calls for this message (tool_offset: ${args[0]})"
        R.string.conversation_details_error -> "Could not read stored message details (${args[0]})"
        R.string.provider_no_keys -> "No API keys configured for ${args.getOrNull(0)}"
        R.string.search_error_format -> "Error: ${args.getOrNull(0) ?: "unknown"}"
        R.string.search_found_results -> "Found ${args[0]} results for '${args[1]}'"
        R.string.search_found_results_no_query -> "Found ${args[0]} results"
        R.string.search_no_matches -> "No matches for '${args[0]}'"
        R.string.search_role_user -> "User"
        R.string.search_role_model -> "Assistant"
        R.string.search_found_matches -> "Found ${args[0]} matches for '${args[1]}'"
        R.string.shell_result_server -> "Server: ${args[0]}"
        R.string.shell_result_command -> "Command: ${args[0]}"
        R.string.shell_result_exit_code -> "Exit code: ${args[0]}"
        R.string.shell_result_error -> "Error: ${args[0]}"
        R.string.conversation_list_header -> "Showing ${args[0]} conversations (items ${args[1]}-${args[2]})"
        R.string.conversation_read_header -> "Conversation: ${args[0]}"
        R.string.conversation_read_page -> "Page: ${args[0]}-${args[1]}"
        else -> "mocked_string_$resId"
    }

    private val context = mockk<Context> {
        every { getString(any<Int>()) } answers { mockGetString(firstArg()) }
        every { getString(any<Int>(), *anyVararg()) } answers {
            mockGetString(firstArg(), *secondArg())
        }
    }

    @Test
    fun isRawSearchResult_webSearch() {
        assertTrue(SearchResultFormatter.isRawSearchResult("""{"type":"web_search"}"""))
    }

    @Test
    fun isRawSearchResult_searchConversations() {
        assertTrue(SearchResultFormatter.isRawSearchResult("""{"type":"search_conversations"}"""))
    }

    @Test
    fun isRawSearchResult_conversationList() {
        assertTrue(SearchResultFormatter.isRawSearchResult("""{"type":"list_conversations"}"""))
    }

    @Test
    fun isRawSearchResult_plainText() {
        assertFalse(SearchResultFormatter.isRawSearchResult("Hello, world!"))
    }

    @Test
    fun isRawSearchResult_invalidJson() {
        assertFalse(SearchResultFormatter.isRawSearchResult("{invalid"))
    }

    @Test
    fun format_webSearch_validResults() {
        val json = """{"type":"web_search","query":"test","results":[{"title":"Title1","url":"https://a.com","description":"Desc1"},{"title":"Title2","url":"https://b.com","description":"Desc2"}]}"""
        val result = SearchResultFormatter.format(json, context)
        assertTrue(result.contains("Found 2 results"))
        assertTrue(result.contains("Title1"))
        assertTrue(result.contains("Title2"))
    }

    @Test
    fun format_webSearch_emptyResults() {
        val json = """{"type":"web_search","query":"test","results":[]}"""
        val result = SearchResultFormatter.format(json, context)
        assertEquals("No results found", result)
    }

    @Test
    fun format_webSearch_errorState() {
        val json = """{"type":"web_search","query":"test","error":"no_results"}"""
        val result = SearchResultFormatter.format(json, context)
        assertEquals("No results found", result)
    }

    @Test
    fun format_webSearch_missingKeyUsesSelectedProvider() {
        val json = """{"type":"web_search","query":"test","error":"no_api_key","provider":"Kagi"}"""
        val result = SearchResultFormatter.format(json, context)
        assertEquals("No API keys configured for Kagi", result)
    }

    @Test
    fun format_plainText_passthrough() {
        val text = "Just a regular message"
        val result = SearchResultFormatter.format(text, context)
        assertEquals(text, result)
    }

    @Test
    fun format_listShells_valid() {
        val json = """{"type":"list_shells","devices":[{"name":"dev1","description":"Test server"}]}"""
        val result = SearchResultFormatter.format(json, context)
        assertTrue(result.contains("dev1"))
        assertTrue(result.contains("Test server"))
    }

    @Test
    fun format_conversationSearch_countsMatchesNotMessages() {
        val json = """{"type":"search_conversations","query":"q","count":1,"results":[{"title":"T","match_count":2,"messages":[{"participant":"USER","text":"a"},{"participant":"MODEL","text":"b"},{"participant":"MODEL","text":"c"}]}]}"""
        val result = SearchResultFormatter.format(json, context)
        assertTrue(result.contains("Found 2 matches"))
        assertTrue(result.contains("## T"))
    }

    @Test
    fun format_conversationList_usesReturnedPageCountAndRange() {
        val json = """{"type":"list_conversations","total":99,"offset":20,"limit":2,"has_more":true,"conversations":[{"id":"a","title":"A"},{"id":"b","title":"B"}]}"""
        val result = SearchResultFormatter.format(json, context)

        assertTrue(result.startsWith("Showing 2 conversations (items 21-22)"))
        assertFalse(result.contains("99"))
        assertTrue(result.contains("More conversations available"))
    }

    @Test
    fun format_conversationRead_omitsDurableTotalAndShowsReturnedRange() {
        val json = """{"type":"read_conversation","title":"T","total_messages":99,"offset":20,"has_more":true,"messages":[{"participant":"USER","text":"a"},{"participant":"MODEL","text":"b"}]}"""
        val result = SearchResultFormatter.format(json, context)

        assertTrue(result.startsWith("Conversation: T\nPage: 21-22"))
        assertFalse(result.contains("99"))
        assertTrue(result.contains("More messages available"))
    }

    @Test
    fun format_conversationRead_keepsPartialAnswerAndFailureDetailVisible() {
        val raw = """{"type":"read_conversation","title":"Conversation","messages":[{"participant":"MODEL","text":"Partial answer","status":"ERROR","error":"Connection closed"}]}"""
        val formatted = SearchResultFormatter.format(raw, context)
        assertTrue(formatted.contains("Partial answer"))
        assertTrue(formatted.contains("Connection closed"))
    }

    @Test
    fun format_conversationReadIncludesOptInThinkingAndToolDetails() {
        val message = """{"participant":"MODEL","text":"answer","thinking":[{"text":"saved thought"}],"tool_calls":[{"name":"shell_exec","arguments":"args","result":"output","state":"succeeded"}],"tool_calls_has_more":true,"tool_calls_next_offset":20}"""
        val formatted = SearchResultFormatter.format("""{"type":"conversation_read","messages":[$message]}""", context)
        assertTrue(formatted.contains("saved thought"))
        assertTrue(formatted.contains("shell_exec"))
        assertTrue(formatted.contains("args"))
        assertTrue(formatted.contains("output"))
        assertTrue(formatted.contains("More tool calls for this message (tool_offset: 20)"))
        assertFalse(formatted.contains("More messages available"))
    }

    @Test
    fun format_conversationReadKeepsStructuredAndImageDetailsAndFallsBackFromBlankText() {
        val message = """{"participant":"MODEL","text":"answer","tool_calls":[{"name":"mcp","result_text":"summary","result":"protocol","structured_result":"structured payload","image_transcription":"image text"},{"name":"fallback","result_text":" ","result":"fallback output"},{"name":"dedup","result_text":"unique body","structured_result":"unique body","image_transcription":"unique body"}]}"""
        val formatted = SearchResultFormatter.format("""{"type":"conversation_read","messages":[$message]}""", context)
        for (text in listOf("summary", "structured payload", "image text", "fallback output")) {
            assertTrue(formatted, formatted.contains(text))
        }
        assertEquals(1, Regex("unique body").findAll(formatted).count())
    }

    @Test
    fun format_conversationReadReportsUnreadableStoredDetails() {
        val message = """{"participant":"MODEL","text":"answer","thinking":[],"tool_calls":[],"details_error":"invalid_stored_segments"}"""
        val formatted = SearchResultFormatter.format("""{"type":"conversation_read","messages":[$message]}""", context)
        assertTrue(formatted.contains("answer"))
        assertTrue(formatted.contains("Could not read stored message details (invalid_stored_segments)"))
    }

    @Test
    fun format_conversationSearchIgnoresLegacyDetailsAndKeepsPreviewLimits() {
        val message = """{"participant":"MODEL","text":"answer","thinking":[{"text":"saved thought"}],"tool_calls":[{"name":"shell_exec","arguments":"args","result":"output","structured_result":"structured payload","image_transcription":"image text"}],"tool_calls_has_more":true,"tool_calls_next_offset":20,"details_error":"invalid_stored_segments"}"""
        val previews = (2..4).joinToString(",") { """{"participant":"USER","text":"preview $it"}""" }
        val windows = (1..6).joinToString(",") { """{"title":"Window $it","match_count":1,"messages":[$message,$previews]}""" }
        val formatted = SearchResultFormatter.format("""{"type":"conversation_search","results":[$windows]}""", context)
        assertTrue(formatted.contains("Found 6 matches"))
        assertEquals(5, Regex("## Window").findAll(formatted).count())
        for (text in listOf("answer", "preview 2", "preview 3")) {
            assertTrue(formatted, formatted.contains(text))
        }
        for (text in listOf(
            "Window 6", "preview 4", "saved thought", "shell_exec", "args", "output",
            "structured payload", "image text", "tool_offset", "invalid_stored_segments",
            "More tool calls", "Could not read stored message details",
        )) {
            assertFalse(formatted, formatted.contains(text))
        }
    }

    @Test
    fun format_conversationRead_emptyPageOmitsRange() {
        val json = """{"type":"read_conversation","title":"T","total_messages":99,"offset":99,"has_more":false,"messages":[]}"""
        val result = SearchResultFormatter.format(json, context)

        assertTrue(result.startsWith("Conversation: T\n\nNo messages"))
        assertFalse(result.contains("Page:"))
        assertFalse(result.contains("99"))
    }
}
