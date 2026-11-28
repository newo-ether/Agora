package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.MessageSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolPresentationTimeoutTest {

    @Test
    fun durableTimedOutJobIsTerminalTimeoutNotFailure() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolArgs = """{"command":"sleep 60"}""",
                toolResult = """
                    {
                      "type":"execute_shell_command",
                      "server":"conch",
                      "job_id":"job-7",
                      "result":{"state":"timed_out","output":"partial"}
                    }
                """.trimIndent(),
            ),
        )

        assertEquals(ToolPresentationState.TIMED_OUT, presentation.state)
        assertEquals(ShellPresentationStatus.TimedOut, shellPresentationStatus(presentation))
        assertEquals("partial", shellOutputText(presentation))
        assertEquals("job-7", presentation.jobId)
        // The capsule already says Timeout; a timeout carries no error text.
        assertNull(presentation.errorMessage)
    }

    @Test
    fun legacyBareTimedOutFlagWithoutLifecycleStateIsTerminalTimeout() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = """{"type":"execute_shell_command","timed_out":true,"output":"partial"}""",
            ),
        )

        assertEquals(ToolPresentationState.TIMED_OUT, presentation.state)
        assertNull(presentation.errorMessage)
    }

    @Test
    fun waitExpiryEnvelopeKeepsBackgroundRunning() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = """
                    {"type":"shell_job_wait","job_id":"job-8","state":"running","timed_out":true,"output":"partial"}
                """.trimIndent(),
            ),
        )

        assertEquals(ToolPresentationState.BACKGROUND_RUNNING, presentation.state)
        assertEquals(ShellPresentationStatus.Background("job-8"), shellPresentationStatus(presentation))
    }
}
