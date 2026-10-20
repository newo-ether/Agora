package com.newoether.agora.ui.chat

import android.app.Application
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w600dp-h900dp")
class InteractiveAnswerTouchTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var renderer: InteractiveAnswerTouchHost
    private lateinit var scroll: ScrollState
    private lateinit var drawer: ChatDrawerState
    private val actions = mutableListOf<Int>()

    private fun showRenderer(controlHandlesTouch: Boolean = true, scrollableControl: Boolean = false, dragControl: Boolean = false, bounds: Pair<Boolean, Boolean>? = null) {
        compose.setContent {
            drawer = rememberChatDrawerState()
            ChatDrawerHost(drawer, true, AgoraMotionPolicy(reduceMotion = true), {}, { _, _ -> }) {
                scroll = rememberScrollState()
                Column(Modifier.height(400.dp).verticalScroll(scroll)) {
                    AndroidView(factory = { context ->
                        InteractiveAnswerTouchHost(context).apply {
                            renderer = this
                            // Emulate a handled browser control in the real native touch host,
                            // including Chromium yielding interception at a scroll boundary.
                            addView(android.view.View(context).apply {
                                setOnTouchListener { view, event ->
                                    actions += event.actionMasked
                                    if (event.actionMasked == MotionEvent.ACTION_DOWN && bounds != null) {
                                        renderer.updateScrollBounds(bounds.first, bounds.second, true)
                                    }
                                    val verticalMove = event.actionMasked == MotionEvent.ACTION_MOVE &&
                                        kotlin.math.abs(event.y - 180f) > kotlin.math.abs(event.x - 300f)
                                    view.parent?.requestDisallowInterceptTouchEvent(scrollableControl && verticalMove)
                                    if (dragControl && event.actionMasked == MotionEvent.ACTION_DOWN) renderer.claimControlGesture()
                                    controlHandlesTouch
                                }
                            }, android.widget.FrameLayout.LayoutParams(-1, -1))
                        }
                    }, modifier = Modifier.fillMaxWidth().height(200.dp).testTag("renderer"))
                    Box(Modifier.fillMaxWidth().height(200.dp).testTag("outside"))
                    Spacer(Modifier.height(400.dp))
                }
            }
        }
        compose.waitForIdle()
        // Robolectric does not perform the native View layout during Compose drawing.
        val bounds = compose.onNodeWithTag("renderer").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            (renderer.parent as android.view.View).layout(0, 0, bounds.width.toInt(), bounds.height.toInt())
            renderer.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(bounds.width.toInt(), android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(bounds.height.toInt(), android.view.View.MeasureSpec.EXACTLY),
            )
            renderer.layout(0, 0, bounds.width.toInt(), bounds.height.toInt())
            assertTrue(renderer.width > 0 && renderer.height > 0)
        }
    }

    @Test fun sliderDragReachesRendererWithoutOpeningDrawerAndOutsideSwipeStillWorks() {
        showRenderer(dragControl = true)
        compose.onNodeWithTag("renderer").performTouchInput { swipeRight() }
        compose.runOnIdle {
            assertEquals(0f, drawer.progress, 0f)
            assertTrue(actions.contains(MotionEvent.ACTION_MOVE))
            assertEquals(MotionEvent.ACTION_UP, actions.last())
        }
        compose.onNodeWithTag("outside").performTouchInput { swipeRight() }
        compose.runOnIdle { assertTrue(drawer.progress > 0f) }
    }

    @Test fun cancellationReleasesRendererGestureOwnership() {
        showRenderer(dragControl = true)
        compose.onNodeWithTag("renderer").performTouchInput {
            down(Offset(80f, 80f))
            moveBy(Offset(100f, 0f))
            cancel()
        }
        compose.runOnIdle {
            assertEquals(0f, drawer.progress, 0f)
            assertEquals(MotionEvent.ACTION_CANCEL, actions.last())
        }
        compose.onNodeWithTag("outside").performTouchInput { swipeRight() }
        compose.runOnIdle { assertTrue(drawer.progress > 0f) }
    }
    @Test fun ordinaryPageAreaAllowsDrawerGestures() {
        showRenderer(controlHandlesTouch = false)
        compose.onNodeWithTag("renderer").performTouchInput { swipeRight() }
        compose.runOnIdle { assertTrue(drawer.progress > 0f) }
    }

    @Test fun verticalDragStaysInsideTheIframeEvenWhenOuterBrowserReportsNoScroll() {
        showRenderer()
        compose.onNodeWithTag("renderer").performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertEquals(0, scroll.value)
            assertEquals(0f, drawer.progress, 0f)
            assertTrue(actions.contains(MotionEvent.ACTION_MOVE))
            assertEquals(MotionEvent.ACTION_UP, actions.last())
        }
    }

    @Test fun scrollablePageCanKeepAVerticalDragWithoutMovingTheChat() {
        showRenderer(scrollableControl = true)
        compose.onNodeWithTag("renderer").performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertEquals(0, scroll.value)
            assertEquals(0f, drawer.progress, 0f)
            assertTrue(actions.contains(MotionEvent.ACTION_MOVE))
            assertEquals(MotionEvent.ACTION_UP, actions.last())
        }
    }

    @Test fun verticalSwipeOutsideTemplateStillScrollsChat() {
        showRenderer()
        compose.onNodeWithTag("outside").performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue(scroll.value > 0) }
    }

    @Test fun bottomBoundaryYieldsAnUpwardSwipeToChat() {
        showRenderer(bounds = true to false)
        compose.onNodeWithTag("renderer").performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue(scroll.value > 0); assertEquals(MotionEvent.ACTION_CANCEL, actions.last()) }
    }

    @Test fun topBoundaryYieldsADownwardSwipeToChat() {
        showRenderer(bounds = false to true)
        compose.runOnIdle { kotlinx.coroutines.runBlocking { scroll.scrollTo(50) } }
        compose.onNodeWithTag("renderer").performTouchInput { swipeDown() }
        compose.runOnIdle { assertTrue(scroll.value < 50) }
    }

    @Test fun reachingBoundaryDuringTheSameDragHandsOffToChat() {
        showRenderer(bounds = true to true)
        compose.onNodeWithTag("renderer").performTouchInput {
            down(Offset(80f, 160f)); moveBy(Offset(0f, -25f))
        }
        compose.runOnIdle { assertEquals(0, scroll.value); renderer.updateScrollBounds(true, false, true) }
        compose.onNodeWithTag("renderer").performTouchInput { moveBy(Offset(0f, -60f)); moveBy(Offset(0f, -40f)); up() }
        compose.runOnIdle { assertTrue(scroll.value > 0) }
    }

    @Test fun knownScrollableContentAndDragControlsKeepOwnership() {
        showRenderer(bounds = false to false, dragControl = true)
        compose.onNodeWithTag("renderer").performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(0, scroll.value); assertEquals(MotionEvent.ACTION_UP, actions.last()) }
    }

    @Test fun controlCannotClaimAnInactiveTouchSequence() {
        showRenderer(controlHandlesTouch = false)
        compose.runOnIdle { renderer.claimControlGesture() }
        compose.onNodeWithTag("renderer").performTouchInput { swipeRight() }
        compose.runOnIdle { assertTrue(drawer.progress > 0f) }
    }

    @Test fun browserClaimArrivingAfterDownProtectsTheRestOfTheGesture() {
        showRenderer()
        compose.onNodeWithTag("renderer").performTouchInput { down(Offset(80f, 80f)) }
        compose.runOnIdle { renderer.claimControlGesture() }
        compose.onNodeWithTag("renderer").performTouchInput { moveBy(Offset(100f, 0f)); up() }
        compose.runOnIdle {
            assertEquals(0f, drawer.progress, 0f)
            assertTrue(actions.contains(MotionEvent.ACTION_MOVE))
            assertEquals(MotionEvent.ACTION_UP, actions.last())
        }
    }

}
