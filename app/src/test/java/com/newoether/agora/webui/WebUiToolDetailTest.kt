package com.newoether.agora.webui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.R
import com.newoether.agora.model.MessagePersistenceGuard
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.model.ToolImageAttachment
import com.newoether.agora.ui.chat.message.FiloPreviewTruncationMarker
import com.newoether.agora.ui.chat.message.ToolDetailBody
import com.newoether.agora.ui.chat.message.toolDetailPresentation
import com.newoether.agora.util.appLanguageResources
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WebUiToolDetailTest {
    private fun resources(language: String = "en") =
        ApplicationProvider.getApplicationContext<Application>().appLanguageResources(language)

    private fun webToolDocument(text: String) = resources().webToolDocument(text)

    @Test fun shellContentAndFailureTextMatchTheSharedOwnerInBothLanguages() {
        for (language in listOf("en", "zh")) {
            val resources = resources(language)
            val shell = MessageSegment(type = "tool", toolName = "execute_shell_command", toolResult = "{\"exit_code\":2,\"output\":\"out\"}")
            val shared = resources.toolDetailPresentation(shell).body as ToolDetailBody.Shell
            assertEquals(WebToolBody.Shell(shared.status, shared.device, shared.error, shared.output), resources.webToolDetail(shell).body)
            val failed = shell.copy(toolName = "mcp_test", toolState = ToolExecutionStates.FAILED)
            assertEquals(WebToolBody.Failed(resources.getString(R.string.tool_call_failed), null), resources.webToolDetail(failed).body)
        }
    }

    @Test fun jsonPrefixesUseSharedGrammarWithoutInventedFieldsOrSyntax() {
        val prefix = webToolDocument("{\"key\":\"par")
        val root = prefix.roots.single() as WebToolNode.Object
        assertFalse(root.complete)
        assertEquals(WebToolNode.Scalar("par", "STRING", false), root.entries.single().value)
        assertEquals("not json", webToolDocument("not json").text)
        assertEquals("{\"a\":1} trailing", webToolDocument("{\"a\":1} trailing").text)
        assertEquals(1, webToolDocument("{\"a\":1}\n{\"a\":1}").roots.size)
        assertEquals(2, webToolDocument("{\"a\":1}\n{\"a\":2}").roots.size)
    }

    @Test fun emptyContainersCarryLocalizedLabelsOnlyWhenComplete() {
        for ((language, label) in listOf("en" to "No content", "zh" to "\u65e0\u5185\u5bb9")) {
            val resources = resources(language)
            assertEquals(label, resources.getString(R.string.tool_json_empty_container))
            for (literal in listOf("[]", "{}")) {
                val node = resources.webToolDocument(literal).roots.single()
                assertEquals(
                    if (literal == "[]") WebToolNode.Array(emptyList(), true, label)
                    else WebToolNode.Object(emptyList(), true, label),
                    node,
                )
                val encoded = WebUiSync.json.encodeToString(WebToolNode.serializer(), node)
                assertEquals(node, WebUiSync.json.decodeFromString(WebToolNode.serializer(), encoded))
            }
            val nested = resources.webToolDocument("{\"array\":[],\"object\":{}}").roots.single() as WebToolNode.Object
            assertNull(nested.emptyText)
            assertEquals(resources.getString(R.string.tool_json_empty_container),
                (nested.entries[0].value as WebToolNode.Array).emptyText)
            assertEquals(resources.getString(R.string.tool_json_empty_container),
                (nested.entries[1].value as WebToolNode.Object).emptyText)
            assertNull((resources.webToolDocument("[").roots.single() as WebToolNode.Array).emptyText)
            assertNull((resources.webToolDocument("{").roots.single() as WebToolNode.Object).emptyText)
            val todo = MessageSegment(type = "tool", toolName = "read_todo", toolResult = "[]")
            val body = resources.webToolDetail(todo).body as WebToolBody.Documents
            assertEquals(resources.webToolDocument("[]"), body.values.single())
        }
    }

    @Test fun onlyExactTerminalMarkersAreOutOfBand() {
        val markers = FiloPreviewTruncationMarker + MessagePersistenceGuard.TRUNCATION_MARKER
        val document = webToolDocument("{\"value\":\"part" + markers)
        assertNull(document.text)
        assertEquals(markers.trimStart(), document.marker)
        assertFalse((document.roots.single() as WebToolNode.Object).complete)
        val literal = kotlinx.serialization.json.JsonPrimitive(markers).toString()
        assertNull(webToolDocument(literal).marker)
    }

    @Test fun imageProjectionKeepsOriginalIndicesAndNeverPublishesPrivatePaths() {
        val images = listOf(
            ToolImageAttachment("", "image/png", 1),
            ToolImageAttachment("/private/tool-image.png", "image/png", 10, 20, 30),
        )
        val segment = MessageSegment(type = "tool", toolName = "generate_image", toolResult = "{}", toolImages = images)
        val detail = resources().webToolDetail(segment)
        assertEquals(listOf(WebToolImage(1, 20, 30)), detail.images)
        assertTrue(detail.squareCrop)
        val encoded = WebUiSync.json.encodeToString(WebToolDetail.serializer(), detail)
        assertFalse(encoded.contains("/private/"))
        assertFalse(encoded.contains("mimeType"))
        assertEquals(detail, WebUiSync.json.decodeFromString(WebToolDetail.serializer(), encoded))
    }

    @Test fun structuredKindsHaveTypedBodiesAndDoNotRequireBrowserEnvelopeParsing() {
        val fixtures = listOf(
            "file_glob" to "{\"files\":[\"a\"]}",
            "file_grep" to "{\"matches\":[{\"path\":\"a\",\"line\":1,\"content\":\"match\"}]}",
            "file_read" to "{\"path\":\"a\",\"content\":\"text\"}",
            "web_search" to "{\"results\":[{\"url\":\"javascript:alert(1)\"}]}",
        )
        val bodies = fixtures.map { (name, result) -> resources().webToolDetail(MessageSegment(type = "tool", toolName = name, toolResult = result)).body }
        assertTrue(bodies[0] is WebToolBody.Paths)
        assertTrue(bodies[1] is WebToolBody.Grep)
        assertTrue(bodies[2] is WebToolBody.FileContent)
        assertNull((bodies[3] as WebToolBody.Search).results.single().safeUrl)
        bodies.forEach { body ->
            val encoded = WebUiSync.json.encodeToString(WebToolBody.serializer(), body)
            assertEquals(body, WebUiSync.json.decodeFromString(WebToolBody.serializer(), encoded))
        }
    }
}
