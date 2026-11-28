package com.newoether.agora.ui.chat.message

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.remote.RemoteActivity
import com.newoether.agora.remote.RemoteMessage
import com.newoether.agora.remote.projectRemoteMessages
import com.newoether.agora.webui.WebToolBody
import com.newoether.agora.webui.webToolDetail
import com.newoether.agora.webui.webToolDocument
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class RemoteToolDetailRenderingTest {
    @get:Rule val compose = createComposeRule()
    private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources
    private fun segment(name: String, args: String, result: String? = null, state: String = "succeeded") =
        MessageSegment(type = "tool", toolName = name, toolArgs = args, toolResult = result,
            toolState = state, remoteToolSource = "filo")

    @Test fun projectionPreservesNativeIdentityAndMarksRemoteOnly() {
        val activity = RemoteActivity("tool", "Read", """{"file_path":"a.kt"}""", "1→literal", "succeeded")
        val projected = projectRemoteMessages(listOf(RemoteMessage("tool", turnId = "turn", clientId = null, role = "assistant", text = "", timestamp = null, activity = activity)))
            .single().segments!!.single()
        assertEquals("Read", projected.toolName)
        assertEquals(activity.arguments, projected.toolArgs)
        assertEquals(activity.result, projected.toolResult)
        assertEquals("filo", projected.remoteToolSource)
        assertTrue(resources.toolDetailPresentation(projected).body is ToolDetailBody.FileContent)
    }

    @Test fun sharedComposeBodyShowsCodeDiffAndTerminalWithoutEscapedPayloads() {
        val value = mutableStateOf(segment("Write", """{"file_path":"a.kt","content":"first\nsecond"}""", "written"))
        compose.setContent { MaterialTheme { ToolDetailContent(value.value) { _, _ -> } } }
        compose.onNodeWithText("first").assertExists()
        compose.onNodeWithText("second").assertExists()
        compose.runOnIdle { value.value = segment("Edit", """{"file_path":"a.kt","old_string":"old","new_string":"new"}""", "done") }
        compose.onNodeWithText("old").assertExists()
        compose.onNodeWithText("new").assertExists()
        compose.onAllNodesWithText("old_string").assertCountEquals(0)
        compose.runOnIdle { value.value = segment("Bash", """{"command":"echo hello"}""", """{"output":"hello","exit_code":7}""", "failed") }
        compose.onNodeWithText("echo hello").assertExists()
        compose.onNodeWithText("hello").assertExists()
        assertEquals("7", (mapRemoteToolDetail("filo", "Bash", value.value.toolArgs, value.value.toolResult, "failed")!!.body as ToolDetailBody.Shell).status)
    }

    @Test fun sharedWebProjectionKeepsReceiptsAndEmptySearchMetadata() {
        val write = segment("Write", """{"file_path":"a.kt","content":"literal"}""", "written")
        val web = resources.webToolDetail(write)
        assertEquals("FILE_WRITE", web.kind)
        assertEquals("literal", (web.body as WebToolBody.FileContent).content)
        assertEquals(resources.webToolDocument("written"), web.supplemental.single())
        val search = segment("web_search", """{"query":"x"}""", """{"results":[],"truncated":true}""")
        assertEquals(resources.webToolDocument("""{"truncated":true}"""),
            resources.webToolDetail(search).supplemental.single())
        val stopped = resources.toolDetailPresentation(segment("Read", """{"file_path":"a"}""", state = "stopped"))
        assertTrue(stopped.body is ToolDetailBody.Stopped)
    }

    @Test fun codexLiteralAddAndMultiFileChangesReachComposeAndWebDiffBodies() {
        val input = """{"changes":[{"path":"created.py","kind":{"type":"add"},"diff":"import pathlib\n+literal\n"},{"path":"gone.py","kind":"delete","diff":"removed"},{"path":"old.py","kind":{"type":"update","move_path":"moved.py"},"diff":"@@\n-before\n+after"}]}"""
        val native = segment("file_edit", input, input).copy(remoteToolSource = "codex-cli")
        compose.setContent { MaterialTheme { ToolDetailContent(native) { _, _ -> } } }
        for (text in listOf("created.py", "import pathlib", "+literal", "Add",
            "gone.py", "Delete", "removed", "old.py", "moved.py", "Move", "before", "after")) {
            compose.onNodeWithText(text).assertExists()
        }
        compose.onAllNodesWithText("changes").assertCountEquals(0)
        val web = resources.webToolDetail(native)
        val body = web.body as WebToolBody.Edit
        for (text in listOf("*** Add File:", "*** Delete File:", "*** Move to:", "@@")) {
            compose.onAllNodesWithText(text, substring = true).assertCountEquals(0)
        }
        assertEquals(3, body.files.size)
        assertTrue(body.hunks.isEmpty())
        assertEquals(listOf("import pathlib", "+literal"), body.files[0].hunks.single().lines.filter { it.kind == "added" }.map { it.text })
        assertEquals("removed", body.files[1].hunks.single().lines.single { it.kind == "removed" }.text)
        assertEquals("moved.py", body.files[2].moveTo!!.name)
        assertTrue(web.supplemental.isEmpty())
    }

    @Test fun applyPatchUsesPrimaryFileHeadersWithoutRepeatedProtocolRowsAtNarrowWidth() {
        val patch = "*** Begin Patch\n*** Update File: /repo/src/a.kt\n@@ first anchor\n-old\n+new\n@@ second anchor\n-before\n+after\n*** Add File: /repo/new/b.kt\n+created\n*** End Patch"
        val native = segment("apply_patch", patch, "done")
        val original = native.copy()
        var primary = androidx.compose.ui.graphics.Color.Unspecified
        compose.setContent {
            MaterialTheme {
                primary = MaterialTheme.colorScheme.primary
                Box(Modifier.width(220.dp)) { ToolDetailContent(native) { _, _ -> } }
            }
        }
        for (text in listOf("a.kt", "/repo/src", "b.kt", "/repo/new", "Update", "Add", "#1", "#2")) {
            compose.onNodeWithText(text).assertExists()
        }
        for (text in listOf("*** Begin Patch", "*** Update File:", "*** Add File:", "*** End Patch", "@@")) {
            compose.onAllNodesWithText(text, substring = true).assertCountEquals(0)
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("a.kt").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(primary, layouts.single().layoutInput.style.color)
        assertEquals(com.newoether.agora.ui.theme.MonoFamily, layouts.single().layoutInput.style.fontFamily)
        val web = resources.webToolDetail(native)
        val body = web.body as WebToolBody.Edit
        assertEquals(listOf("a.kt", "b.kt"), body.files.map { it.path.name })
        assertEquals(listOf(1, 2), body.files.first().hunks.map { it.index })
        val encoded = com.newoether.agora.webui.WebUiSync.json.encodeToString(WebToolBody.serializer(), body)
        assertEquals(body, com.newoether.agora.webui.WebUiSync.json.decodeFromString(WebToolBody.serializer(), encoded))
        assertEquals(original, native)
    }

    @Test fun malformedPatchKeepsWholeOriginalArgumentsResultAndNativeState() {
        val patch = "*** Begin Patch\n*** Add File: good.kt\n+kept\n*** Unknown File: bad.kt\n+x\n*** End Patch"
        val native = segment("apply_patch", patch, "original receipt")
        val detail = resources.toolDetailPresentation(native)
        assertEquals(patch, detail.arguments)
        assertEquals(listOf("original receipt"), (detail.body as ToolDetailBody.JsonContent).values)
        assertEquals("succeeded", native.toolState)
        assertEquals(patch, native.toolArgs)
    }

    @Test fun runningReadCanBecomeCodeAndMalformedResultKeepsOriginalJson() {
        val value = mutableStateOf(segment("Read", """{"file_path":"a.kt"}""", state = "running"))
        compose.setContent { MaterialTheme { ToolDetailContent(value.value) { _, _ -> } } }
        compose.runOnIdle { value.value = value.value.copy(toolResult = " 9→val native = true", toolState = "succeeded") }
        compose.onNodeWithText("val native = true").assertExists()
        compose.onAllNodesWithText("9→val native = true").assertCountEquals(0)
        val invalid = segment("file_read", "{}", """{"secretField":"kept"}""")
        val fallback = resources.toolDetailPresentation(invalid)
        assertEquals(invalid.toolArgs, fallback.arguments)
        assertEquals(listOf(invalid.toolResult), (fallback.body as ToolDetailBody.JsonContent).values)
        assertNull(mapRemoteToolDetail("filo", "file_read", "{", "result", "succeeded"))
    }
}
