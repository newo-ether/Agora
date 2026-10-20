package com.newoether.agora.remote

import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.interactiveAnswer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class RemoteInteractiveAnswerTest {
    private val answer = InteractiveAnswer("Figure", "Description", "Fallback", html = "<svg></svg>")
    private fun activity(state: String = "succeeded", name: String = InteractiveAnswer.TOOL_NAME) = RemoteActivity(
        type = "tool", toolName = name, state = state,
        structuredResult = Json.parseToJsonElement(answer.encode()).jsonObject,
    )

    @Test fun completedArtifactUsesTheExistingViewerContract() {
        val payload = requireNotNull(remoteInteractiveResult(activity()))
        val segment = MessageSegment(type = "tool", toolName = InteractiveAnswer.TOOL_NAME,
            toolState = "succeeded", toolStructuredResult = payload)
        assertEquals(answer, segment.interactiveAnswer())
    }

    @Test fun incompleteForeignAndOversizedResultsCannotExecute() {
        for (state in listOf("running", "failed", "stopped")) assertNull(remoteInteractiveResult(activity(state)))
        assertNull(remoteInteractiveResult(activity(name = "other_tool")))
        assertNull(remoteInteractiveResult(activity().copy(structuredResult = Json.parseToJsonElement("{}").jsonObject)))
        val large = answer.copy(html = "é".repeat(40_000))
        assertNull(remoteInteractiveResult(activity().copy(structuredResult = Json.parseToJsonElement(large.encode()).jsonObject)))
    }

    @Test fun piRuntimeRetainsRemoteSkillDiscoveryAndLegacyDefaults() {
        val runtime = Json.decodeFromString<RemoteRuntime>(
            """{"status":"idle","agent":"Pi","skills":[{"name":"Teach","command":"/teach"}]}""",
        )
        assertEquals("Pi", runtime.agent)
        assertEquals("/teach", runtime.skills.single().command)
        val legacy = Json.decodeFromString<RemoteRuntime>("""{"status":"idle"}""")
        assertNull(legacy.agent)
        assertTrue(legacy.skills.isEmpty())
    }

}
