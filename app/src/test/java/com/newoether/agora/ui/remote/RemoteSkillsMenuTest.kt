package com.newoether.agora.ui.remote

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.newoether.agora.remote.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RemoteSkillsMenuTest {
    @get:Rule val compose = createComposeRule()
    @Test fun remoteEntriesAreUniqueFillTheNativeCommandAndDisappearWithoutALiveRuntime() {
        val skill = RemoteSkill("osce", "Practice a focused clinical scenario", "/skill:osce")
        val runtime = mutableStateOf<RemoteRuntime?>(RemoteRuntime("idle", skills = listOf(skill, skill)))
        var command = ""
        compose.setContent { MaterialTheme { RemoteSkillsMenu("/", runtime.value, true, { command = it }) } }
        compose.onAllNodesWithText("/skill:osce").assertCountEquals(1)
        compose.onNodeWithText("Practice a focused clinical scenario").assertExists()
        compose.onNodeWithText("/skill:osce").performClick()
        compose.runOnIdle { assertEquals("/skill:osce ", command); runtime.value = null }
        compose.onNodeWithText("/skill:osce").assertDoesNotExist()
        assertNull(remoteSkillsMenuQuery("mention /osce"))
        assertNull(remoteSkillsMenuQuery("/skill:osce question"))
        assertEquals("skill:os", remoteSkillsMenuQuery("/skill:os"))
    }
}
