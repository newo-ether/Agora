package com.newoether.agora.ui.chat

import androidx.compose.material3.DrawerValue
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDrawerHostTest {
    @Test
    fun drawerWidthIsBoundedByScreenAndMaximum() {
        assertEquals(320.dp, drawerWidthFor(320.dp))
        assertEquals(DRAWER_MAX_WIDTH, drawerWidthFor(DRAWER_MAX_WIDTH))
        assertEquals(DRAWER_MAX_WIDTH, drawerWidthFor(1200.dp))
    }

    @Test
    fun sideBySideModeUsesStrictCombinedWidthThreshold() {
        val threshold = DRAWER_MAX_WIDTH + CHAT_APP_WIDTH_THRESHOLD

        assertFalse(usesSideBySideDrawer(threshold))
        assertTrue(usesSideBySideDrawer(threshold + 1.dp))
    }

    @Test
    fun velocityOverridesDrawerPosition() {
        assertEquals(
            DrawerValue.Open,
            resolveDrawerSettleTarget(
                velocity = 0.1f,
                revealedPosition = 0f,
                drawerWidth = 360f,
            ),
        )
        assertEquals(
            DrawerValue.Closed,
            resolveDrawerSettleTarget(
                velocity = -0.1f,
                revealedPosition = 360f,
                drawerWidth = 360f,
            ),
        )
    }

    @Test
    fun zeroVelocityUsesHalfWidthWithEqualityOpening() {
        assertEquals(
            DrawerValue.Closed,
            resolveDrawerSettleTarget(velocity = 0f, revealedPosition = 179.9f, drawerWidth = 360f),
        )
        assertEquals(
            DrawerValue.Open,
            resolveDrawerSettleTarget(velocity = 0f, revealedPosition = 180f, drawerWidth = 360f),
        )
        assertEquals(
            DrawerValue.Open,
            resolveDrawerSettleTarget(velocity = 0f, revealedPosition = 181f, drawerWidth = 360f),
        )
    }

    @Test
    fun hostIsTheSingleDrawerMotionAndResponsiveLayoutOwner() {
        val host = source("ui/chat/ChatDrawerHost.kt")
        val app = source("ui/chat/ChatApp.kt")
        val drawer = source("ui/chat/ChatDrawerContent.kt")
        val effects = source("ui/chat/ChatAppInteractionEffects.kt")
        val topBar = source("ui/chat/ChatTopBar.kt")

        assertTrue(host.contains("internal val DRAWER_MAX_WIDTH = 360.dp"))
        assertTrue(host.contains("internal val CHAT_APP_WIDTH_THRESHOLD = 600.dp"))
        assertTrue(host.contains("screenWidth > DRAWER_MAX_WIDTH + CHAT_APP_WIDTH_THRESHOLD"))
        assertTrue(host.contains("if (drawerEnabled && !sideBySide)"))
        // A touch during the settle animation must catch the drawer instead of losing every frame
        // to the animation that keeps writing the offset.
        assertTrue(host.contains("startDragImmediately = state.isAnimationRunning"))
        assertTrue(host.contains("onDragStarted = { state.takeOverAnimation() }"))
        assertTrue(host.contains("anchoredState.anchoredDrag(MutatePriority.UserInput)"))
        assertTrue(host.contains("if (!sideBySide) animateTo(DrawerValue.Closed, motionPolicy)"))
        assertTrue(host.contains("durationMillis = DRAWER_TWEEN_DURATION_MILLIS"))
        assertTrue(host.contains("import androidx.compose.animation.core.LinearOutSlowInEasing"))
        assertTrue(host.contains("easing = LinearOutSlowInEasing"))
        assertFalse(host.contains("CubicBezierEasing"))
        assertFalse(host.contains("FastOutSlowInEasing"))
        assertFalse(host.contains("DRAWER_EASING"))
        assertTrue(host.contains(".background(scrimColor.copy(alpha = scrimColor.alpha * progress))"))
        assertFalse(host.contains(".alpha(progress)"))
        assertTrue(host.contains("val scrimInteractionSource = remember { MutableInteractionSource() }"))
        assertTrue(host.contains("interactionSource = scrimInteractionSource"))
        assertTrue(host.contains("indication = null"))
        assertFalse(host.contains(".clickable {"))
        assertTrue(app.contains("ChatDrawerHost("))
        assertTrue(app.contains("drawerState.toggle(motionPolicy)"))
        assertFalse(app.contains("ModalNavigationDrawer("))
        assertFalse(app.contains("rememberDrawerState("))
        assertTrue(drawer.contains("onRequestClose: suspend () -> Unit"))
        assertFalse(drawer.contains("DrawerState"))
        assertTrue(effects.contains("enabled = onNavigateBack != null && !drawerState.shouldHandleBack"))

        val titleBlock = topBar
            .substringAfter("// Reserve the trailing capsule first; the title may only use the remaining width.")
            .substringBefore("// Actions capsule")
        val internalTitlePadding =
            Regex("""Modifier\.padding\(end = 20\.dp\)\.widthIn\(max = 180\.dp\)""")
        assertEquals(2, internalTitlePadding.findAll(titleBlock).count())
        assertFalse(titleBlock.contains("Spacer(modifier = Modifier.width(20.dp))"))
    }

    private fun source(relative: String): String =
        File(mainSourceRoot(), "com/newoether/agora/$relative").readText()

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
