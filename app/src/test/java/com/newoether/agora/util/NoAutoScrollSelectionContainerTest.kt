package com.newoether.agora.util

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoAutoScrollSelectionContainerTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun selectionChangesShareAFrameWithLazyRemeasureAndRemoval() {
        val enabled = mutableStateOf(false)
        val indices = mutableStateOf((0 until 60).toList())
        val list = LazyListState()
        compose.setContent {
            LazyColumn(state = list, modifier = Modifier.size(360.dp, 180.dp)) {
                items(indices.value.size, key = { indices.value[it] }) { position ->
                    val index = indices.value[position]
                    NoAutoScrollSelectionContainer(enabled = enabled.value) {
                        Box(Modifier.fillMaxWidth().height(100.dp)) {
                            Text("Item $index")
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        repeat(32) { iteration ->
            compose.runOnUiThread {
                enabled.value = !enabled.value
                indices.value = if (iteration % 2 == 0) (0 until 60).toList() else (0 until 60).reversed().toList()
                list.requestScrollToItem(if (iteration % 2 == 0) 0 else 40)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(60, list.layoutInfo.totalItemsCount)
                assertEquals(if (iteration % 2 == 0) 0 else 40, list.firstVisibleItemIndex)
            }
        }
        compose.runOnUiThread {
            enabled.value = !enabled.value
            indices.value = emptyList()
            list.requestScrollToItem(0)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, list.layoutInfo.totalItemsCount) }
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun selectionChangesSurviveLazyItemEvictionAndReuse() {
        val enabled = mutableStateOf(false)
        val list = LazyListState()
        compose.setContent {
            LazyColumn(
                state = list,
                modifier = Modifier.size(360.dp, 180.dp),
            ) {
                items(60, key = { it }) { index ->
                    NoAutoScrollSelectionContainer(enabled = enabled.value) {
                        Box(Modifier.fillMaxWidth().height(100.dp)) {
                            Text("Item $index")
                        }
                    }
                }
            }
        }
        repeat(24) { iteration ->
            val index = if (iteration % 2 == 0) 0 else 40
            compose.runOnIdle {
                enabled.value = !enabled.value
                runBlocking { list.scrollToItem(index) }
            }
            compose.onNodeWithText("Item $index").assertIsDisplayed()
        }
    }

    @Test
    fun selectionChangesRetainMeasuredContentDuringPendingPlacementAndRemoval() {
        val enabled = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val text = mutableStateOf("Streaming")
        val offset = mutableFloatStateOf(0f)
        var entries = 0
        var disposals = 0
        compose.setContent {
            Box(Modifier.size(360.dp, 400.dp)) {
                if (visible.value) {
                    NoAutoScrollSelectionContainer(enabled = enabled.value) {
                        val identity = remember { Any() }
                        DisposableEffect(identity) {
                            entries++
                            onDispose { disposals++ }
                        }
                        Box(
                            Modifier.fillMaxWidth().widthIn(max = 320.dp)
                                .layout { measurable, constraints ->
                                    val child = measurable.measure(constraints)
                                    layout(child.width, child.height) {
                                        child.placeRelative(0, offset.floatValue.toInt())
                                    }
                                }.testTag("retained-body"),
                        ) {
                            Column(Modifier.height(80.dp)) { Text(text.value) }
                        }
                    }
                }
            }
        }
        repeat(32) { iteration ->
            compose.runOnIdle {
                offset.floatValue = iteration.toFloat()
                enabled.value = !enabled.value
                text.value = "Frame $iteration"
            }
            compose.onNodeWithText("Frame $iteration").assertIsDisplayed()
            compose.onNodeWithTag("retained-body").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(1, entries)
                assertEquals(0, disposals)
            }
        }
        compose.runOnIdle {
            offset.floatValue = 100f
            enabled.value = false
            visible.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, disposals) }
    }
}
