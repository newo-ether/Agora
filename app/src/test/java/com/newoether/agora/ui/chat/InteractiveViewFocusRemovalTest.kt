package com.newoether.agora.ui.chat

import android.app.Application
import android.widget.EditText
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Configure the same upstream focus teardown path before Compose initializes. */
@OptIn(ExperimentalComposeUiApi::class)
class FocusInteropTestApplication : Application() {
    override fun onCreate() {
        ComposeUiFlags.isViewFocusFixEnabled = true
        super.onCreate()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = FocusInteropTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InteractiveViewFocusRemovalTest {
    @get:Rule val compose = createComposeRule()

    @Test fun focusedNativeInputCanLeaveLazyChatWhileNetworkErrorIsShown() {
        var showSetup by mutableStateOf(true)
        lateinit var input: EditText
        compose.setContent { MaterialTheme {
            LazyColumn {
                item("setup-or-error") {
                    if (showSetup) AndroidView(factory = { context ->
                        EditText(context).apply { setText("Selected case"); input = this }
                    }, modifier = Modifier.height(180.dp))
                    else Text("Network error: check your connection")
                }
                items((0 until 40).toList(), key = { it }) { index ->
                    if (index == 39) Button(onClick = {}) { Text("Message action $index") }
                    else Text("Conversation text $index", Modifier.height(80.dp))
                }
            }
        } }
        repeat(10) {
            compose.runOnIdle { assertTrue(input.requestFocus()); assertTrue(input.hasFocus()) }
            compose.runOnIdle { showSetup = false }
            compose.waitForIdle()
            compose.onNodeWithText("Network error: check your connection").assertIsDisplayed()
            compose.runOnIdle { showSetup = true }
            compose.waitForIdle()
        }
    }
}
