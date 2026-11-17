package com.newoether.agora.ui.chat.message

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.util.appLanguageResources
import com.newoether.agora.webui.WebToolBody
import com.newoether.agora.webui.webToolDetail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FileTransferPresentationTest {
    private val args = """{"source_server":"Local Sandbox","source_path":"/source.bin","destination_server":"Quantum","destination_path":"/target.bin"}"""
    private val hash = "a".repeat(64)
    private fun receipt(bytes: Long = 300005, status: String = "copied") =
        """{"type":"file_transfer","ok":true,"status":"$status","source_server":"actual-source","source_path":"/Source.bin","destination_server":"actual-target","destination_path":"/Target.bin","bytes":$bytes,"source_sha256":"$hash","destination_sha256":"$hash"}"""
    private fun segment(result: String? = null, state: String? = null, arguments: String = args) =
        MessageSegment(type = "tool", toolName = "file_transfer", toolArgs = arguments,
            toolResult = result, toolState = state)
    private fun resources(language: String = "en") =
        ApplicationProvider.getApplicationContext<Application>().appLanguageResources(language)

    @Test fun callingAndRunningShowBothEndpointsWithProgressiveWording() {
        val expected = "Transferring Local Sandbox:/source.bin → Quantum:/target.bin…"
        for (state in listOf(ToolExecutionStates.CALLING, ToolExecutionStates.RUNNING)) {
            val active = segment(state = state)
            val presentation = ToolPresentationResolver.resolve(active)
            assertEquals(ToolKind.FILE_TRANSFER, presentation.kind)
            assertTrue(presentation.isActive)
            assertNull(presentation.arguments)
            assertEquals("Local Sandbox:/source.bin", presentation.subject)
            assertEquals("Quantum:/target.bin", presentation.destination)
            assertEquals(expected, resources().toolSummary(active))
            assertEquals("Transfer File", resources().toolDisplayName(active))
            assertEquals(WebToolBody.Active(expected, null), resources().webToolDetail(active).body)
        }
        assertEquals("正在传输 Local Sandbox:/source.bin → Quantum:/target.bin…",
            resources("zh").toolSummary(segment(state = ToolExecutionStates.RUNNING)))
    }

    @Test fun unfinishedArgumentsGrowBothEndpointsWithoutStrictParsing() {
        val active = segment(state = ToolExecutionStates.CALLING,
            arguments = """{"source_server":"Local Sandbox","source_path":"/source","destination_server":"Quantum","destination_path":"/par""")
        val presentation = ToolPresentationResolver.resolve(active)
        assertNull(presentation.arguments)
        assertEquals("Local Sandbox:/source", presentation.subject)
        assertEquals("Quantum:/par", presentation.destination)
        assertEquals("Transferring file…", resources().toolSummary(segment(arguments = "{")))
        val huge = segment(arguments = JsonObject(mapOf(
            "unused" to JsonPrimitive("x".repeat(4096)), "source_path" to JsonPrimitive("/hidden"),
        )).toString())
        assertNull(ToolPresentationResolver.resolve(huge).subject)
    }

    @Test fun receiptUsesActualEndpointsAndKnownBytesIncludingZero() {
        for (bytes in listOf(0L, 300005L)) {
            val done = segment(receipt(bytes), ToolExecutionStates.SUCCEEDED)
            val presentation = ToolPresentationResolver.resolve(done)
            assertEquals(ToolPresentationState.COMPLETED, presentation.state)
            assertEquals(bytes, presentation.transferBytes)
            assertEquals("Transferred actual-source:/Source.bin → actual-target:/Target.bin ($bytes bytes)",
                resources().toolSummary(done))
            assertEquals("已传输 actual-source:/Source.bin → actual-target:/Target.bin（$bytes 字节）",
                resources("zh").toolSummary(done))
            assertTrue(resources().toolDetailPresentation(done).body is ToolDetailBody.JsonContent)
            assertTrue(resources().webToolDetail(done).body is WebToolBody.Documents)
            assertEquals(args, resources().toolDetailPresentation(done).arguments)
        }
    }

    @Test fun unchangedHasItsOwnTerminalSummary() {
        val unchanged = segment(receipt(status = "unchanged"))
        assertEquals("Unchanged actual-source:/Source.bin → actual-target:/Target.bin",
            resources().toolSummary(unchanged))
        assertEquals("未更改 actual-source:/Source.bin → actual-target:/Target.bin",
            resources("zh").toolSummary(unchanged))
        assertFalse(ToolPresentationResolver.resolve(unchanged).isActive)
    }

    @Test fun structuredFailureWinsOverSuccessShapedFieldsAndKeepsActualReason() {
        val successful = Json.parseToJsonElement(receipt()) as JsonObject
        val failures = listOf(
            successful + ("ok" to JsonPrimitive(false)) + ("reason" to JsonPrimitive("permission denied: /Target.bin")),
            successful + ("error" to Json.parseToJsonElement("""{"code":"EEXIST","message":"Destination already exists; overwrite is false"}""")),
        )
        for (fields in failures) {
            val failed = segment(JsonObject(fields).toString(), ToolExecutionStates.SUCCEEDED)
            val presentation = ToolPresentationResolver.resolve(failed)
            assertEquals(ToolPresentationState.FAILED, presentation.state)
            assertFalse(presentation.isActive)
            assertFalse(resources().toolSummary(failed).startsWith("Transferred"))
            for (language in listOf("en", "zh")) {
                val body = resources(language).toolDetailPresentation(failed).body as ToolDetailBody.Failed
                assertEquals(presentation.errorMessage, body.text)
                assertEquals(WebToolBody.Failed(body.text, body.output), resources(language).webToolDetail(failed).body)
            }
        }
    }

    @Test fun nestedJsonDiagnosticAndUncertainCommitInstructionRemainReadable() {
        val error = """{"type":"file_transfer","error":"error","message":"{\"error\":{\"message\":\"permission denied: /Target.bin\"},\"message\":\"wrapper\"}"}"""
        val failed = segment(error, ToolExecutionStates.FAILED)
        assertEquals("permission denied: /Target.bin", ToolPresentationResolver.resolve(failed).errorMessage)
        assertEquals("Permission denied: /Target.bin", resources().toolSummary(failed))
        val uncertain = "Transfer abc commit outcome is unknown; inspect Quantum:/Target.bin before retrying"
        val uncertainResult = JsonObject(mapOf("error" to JsonPrimitive("error"), "message" to JsonPrimitive(uncertain))).toString()
        assertEquals(uncertain, resources().toolSummary(segment(uncertainResult)))
        assertEquals("No such file: /Source.bin", resources().toolSummary(segment("No such file: /Source.bin", ToolExecutionStates.FAILED)))
    }

    @Test fun invalidReceiptCannotClaimCompletionAndNullErrorIsNotFailure() {
        val valid = Json.parseToJsonElement(receipt()) as JsonObject
        for (result in listOf("", "{}", "{broken", JsonObject(valid - "destination_sha256").toString(),
                JsonObject(valid + ("bytes" to JsonPrimitive(-1))).toString(),
                JsonObject(valid + ("destination_sha256" to JsonPrimitive("b".repeat(64)))).toString())) {
            val presentation = ToolPresentationResolver.resolve(segment(result, ToolExecutionStates.SUCCEEDED))
            assertEquals(result, ToolPresentationState.FAILED, presentation.state)
            assertFalse(resources().toolSummary(presentation).startsWith("Transferred"))
            assertTrue(resources().toolDetailPresentation(segment(result)).body is ToolDetailBody.Failed)
        }
        assertEquals("File transfer completion could not be confirmed", resources().toolSummary(segment("{}")))
        val nullError = JsonObject(valid + ("error" to kotlinx.serialization.json.JsonNull)).toString()
        assertEquals(ToolPresentationState.COMPLETED, ToolPresentationResolver.resolve(segment(nullError)).state)
    }

    @Test fun structuredOnlyResultsUseTheSameTerminalSemantics() {
        val done = segment(state = ToolExecutionStates.RUNNING).copy(toolStructuredResult = receipt(0))
        assertEquals(ToolPresentationState.COMPLETED, ToolPresentationResolver.resolve(done).state)
        assertEquals("Transferred actual-source:/Source.bin → actual-target:/Target.bin (0 bytes)",
            resources().toolSummary(done))
        assertTrue(resources().webToolDetail(done).body is WebToolBody.Documents)
        val failed = done.copy(toolStructuredResult = """{"error":{"message":"source is missing"}}""")
        assertEquals(ToolPresentationState.FAILED, ToolPresentationResolver.resolve(failed).state)
        assertEquals("source is missing", (resources().webToolDetail(failed).body as WebToolBody.Failed).text)
        assertEquals(ToolPresentationState.FAILED,
            ToolPresentationResolver.resolve(done.copy(toolStructuredResult = "{}")).state)
        assertEquals(ToolPresentationState.STOPPED,
            ToolPresentationResolver.resolve(done.copy(toolState = ToolExecutionStates.STOPPED)).state)
        assertEquals(ToolPresentationState.FAILED,
            ToolPresentationResolver.resolve(segment(state = ToolExecutionStates.SUCCEEDED)).state)
    }

    @Test fun stoppedAndExplicitFailureNeverBecomeSuccessfulReceipt() {
        val stopped = segment(receipt(), ToolExecutionStates.STOPPED)
        assertEquals(ToolPresentationState.STOPPED, ToolPresentationResolver.resolve(stopped).state)
        assertTrue(resources().toolDetailPresentation(stopped).body is ToolDetailBody.Stopped)
        val failed = segment(receipt(), ToolExecutionStates.FAILED)
        assertEquals(ToolPresentationState.FAILED, ToolPresentationResolver.resolve(failed).state)
        assertEquals("Failed to transfer file", resources().toolSummary(failed))
    }
}
