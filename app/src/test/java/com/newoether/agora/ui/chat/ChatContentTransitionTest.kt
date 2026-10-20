package com.newoether.agora.ui.chat

import android.app.Application
import android.widget.TextView
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatContentTransitionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun einkSwapsNativeTemplateAndConversationWithoutRetainingTheOldLayoutTree() {
        var showingSetup by mutableStateOf(true)
        var height by mutableStateOf(320)
        val mounted = mutableSetOf<Boolean>()
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(true, true)) {
                ChatContentTransition(showingSetup to false, { EnterTransition.None togetherWith ExitTransition.None },
                    "Test", Modifier.fillMaxSize()) { (setup, _) ->
                    DisposableEffect(setup) { mounted.add(setup); onDispose { mounted.remove(setup) } }
                    if (setup) Box(Modifier.height(height.dp)) {
                        AndroidView(factory = { TextView(it).apply { text = "Native setup" } })
                    } else Text("Conversation")
                }
            }
        } }
        repeat(20) { turn ->
            compose.runOnIdle { height = if (turn % 2 == 0) 600 else 80; showingSetup = false }
            compose.waitForIdle()
            compose.onNodeWithText("Conversation").assertIsDisplayed()
            compose.runOnIdle { assertEquals(setOf(false), mounted); showingSetup = true }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(setOf(true), mounted) }
        }
    }
}
