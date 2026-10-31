package com.newoether.agora.ui.chat.message

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase28UiSourceContractTest {
    @Test
    fun `user bubble and Select Text share exact 1_1x user-body line height`() {
        val type = source("com/newoether/agora/ui/theme/Type.kt")
        val userBubble = messageSource("UserMessageBubble.kt")
        val detail = messageSource("SegmentDetailSheet.kt")

        assertTrue(type.contains(
            "fontSize = 15.sp, lineHeight = 24.2.sp"
        ))
        assertTrue(userBubble.contains("style = ChatType.userBody"))
        assertTrue(detail.contains(
            "ChatType.userBody.copy(fontSize = 14.sp)"
        ))
        assertFalse(userBubble.contains("lineHeight ="))
        assertFalse(detail.contains(
            "ChatType.userBody.copy(fontSize = 14.sp, lineHeight ="
        ))
    }

    private fun messageSource(name: String): String =
        source("com/newoether/agora/ui/chat/message/$name")

    private fun source(relative: String): String =
        File(mainSourceRoot(), relative).readText()

    private fun mainSourceRoot(): File = locate("app/src/main/java")

    private fun locate(relative: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            File(directory, relative).takeIf(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relative")
    }
}
