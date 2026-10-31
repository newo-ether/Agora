package com.newoether.agora.ui

import com.newoether.agora.util.bottomOverlayFadeStops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ChatOverlaySourceContractTest : UiSourceContractFixture() {
    @Test
    fun `chat bottom dropdowns keep twenty four dp icons and adaptive provider color`() {
        val attachment = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/bottombar/AttachmentAddMenu.kt",
        )
        val bottomBar = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/bottombar/ChatBottomBar.kt",
        )
        val menuItems = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/bottombar/ComposerToolsMenuContent.kt",
        )
        val components = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/bottombar/ChatBottomBarComponents.kt",
        )
        val userMessage = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/UserMessageBubble.kt",
        )

        assertTrue(components.contains("CHAT_DROPDOWN_MENU_ICON_SIZE_DP = 24"))
        assertTrue(attachment.contains("CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp"))
        assertTrue(bottomBar.contains("ComposerToolsMenuContent("))
        assertTrue(menuItems.contains("CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp"))
        assertTrue(components.contains("Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp)"))
        assertTrue(menuItems.contains("ColorFilter.tint(LocalContentColor.current)"))
        assertFalse(menuItems.contains("ColorFilter.tint(Color.White)"))
        assertTrue(components.contains("tint = LocalContentColor.current"))
        assertFalse(components.contains("tint = Color.White"))
        assertTrue(attachment.contains("Icons.Default.Add"))
        assertTrue(attachment.contains("modifier = Modifier.size(18.dp)"))
        assertTrue(bottomBar.contains("Icons.Default.MoreVert"))
        assertTrue(bottomBar.contains("modifier = Modifier.size(18.dp)"))
        assertTrue(userMessage.contains("leadingIcon = { Icon(Icons.Default.ContentCopy, null) }"))
    }

    @Test
    fun `bottom overlay fade geometry tracks the live composer cover and clamps safely`() {
        val regular = bottomOverlayFadeStops(
            canvasHeightPx = 1_000f,
            fadeHeightPx = 40f,
            bottomOverlayHeightPx = 200f,
        )
        assertEquals(0.8f, regular.first, 0.0001f)
        assertEquals(0.84f, regular.second, 0.0001f)

        val oversizedOverlay = bottomOverlayFadeStops(
            canvasHeightPx = 1_000f,
            fadeHeightPx = 40f,
            bottomOverlayHeightPx = 1_500f,
        )
        assertEquals(0f, oversizedOverlay.first, 0.0001f)
        assertEquals(0.04f, oversizedOverlay.second, 0.0001f)
    }

    @Test
    fun `both fork entry points require the shared confirmation dialog`() {
        val source = sourceFile("app/src/main/java/com/newoether/agora/ui/chat/ChatApp.kt")
        val dialogs = sourceFile("app/src/main/java/com/newoether/agora/ui/chat/ChatDialogs.kt")
        val topMenuEntry = source
            .substringAfter("onForkConversation = {")
            .substringBefore("onShareConversation = {")
        val messageActionEntry = source
            .substringAfter("onFork = { id ->")
            .substringBefore("onShare = { id ->")
        val confirmation = dialogs
            .substringAfter("internal fun ChatForkConfirmationHost(")
            .substringBefore("internal fun ChatForkConfirmDialog(")

        assertTrue(topMenuEntry.contains(
            "pendingForkRequest = ForkConversationRequest(messageId = null)",
        ))
        assertFalse(topMenuEntry.contains("viewModel.forkConversationFrom("))
        assertTrue(messageActionEntry.contains(
            "pendingForkRequest = ForkConversationRequest(messageId = id)",
        ))
        assertFalse(messageActionEntry.contains("viewModel.forkConversationFrom("))
        assertTrue(confirmation.contains("ChatForkConfirmDialog("))
        assertEquals(
            2,
            Regex("viewModel\\.forkConversationFrom\\(").findAll(confirmation).count(),
        )
        assertTrue(source.contains(
            "ChatForkConfirmationHost(pendingForkRequest, viewModel) { pendingForkRequest = null }",
        ))
        assertTrue(confirmation.contains("onDismiss = onDismiss"))
    }

    @Test
    fun `every full screen viewer uses shared spatial entrance and exit with reduced motion fallback`() {
        val source = sourceFile("app/src/main/java/com/newoether/agora/MainActivity.kt")
        val mediaViewer = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/FullScreenMediaViewer.kt",
        )
        val mediaDialog = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/FullScreenMediaPreviewDialog.kt",
        )
        val imageActions = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/ImageActions.kt",
        )
        val videoPlayer = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/VideoPlayer.kt",
        )
        val texturePlayerLayout = sourceFile(
            "app/src/main/res/layout/view_texture_video_player.xml",
        )
        val dialogWindow = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/components/DialogWindowEdgeToEdge.kt",
        )

        assertTrue(source.contains(
            "private fun fullScreenPreviewEnterTransition(allowSpatialTransitions: Boolean)"
        ))
        assertTrue(source.contains("fadeIn(tween(durationMillis = 220))"))
        assertTrue(source.contains(
            "scaleIn(tween(durationMillis = 300, easing = FastOutSlowInEasing), initialScale = 0.96f)"
        ))
        assertTrue(source.contains("EnterTransition.None"))
        assertTrue(source.contains(
            "private fun fullScreenPreviewExitTransition(allowSpatialTransitions: Boolean)"
        ))
        assertTrue(source.contains("fadeOut(tween(durationMillis = 180))"))
        assertTrue(source.contains(
            "scaleOut(tween(durationMillis = 220, easing = FastOutLinearInEasing), targetScale = 0.96f)"
        ))
        assertTrue(source.contains("ExitTransition.None"))
        assertEquals(
            2,
            Regex("enter = fullScreenPreviewEnterTransition\\(motionPolicy\\.allowSpatialTransitions\\)")
                .findAll(source)
                .count(),
        )
        assertEquals(
            2,
            Regex("exit = fullScreenPreviewExitTransition\\(motionPolicy\\.allowSpatialTransitions\\)")
                .findAll(source)
                .count(),
        )
        assertEquals(1, Regex("if \\(!currentState && !isRunning\\)").findAll(source).count())
        assertTrue(mediaDialog.contains("if (!currentState && !isRunning && latestTarget == null) onHidden()"))
        assertTrue(source.contains(
            "topLevelPresentation.release(TopLevelPresentation.MEDIA_PREVIEW)"
        ))
        assertTrue(source.contains(
            "topLevelPresentation.release(TopLevelPresentation.TEXT_PREVIEW)"
        ))
        assertTrue(source.contains("FullScreenMediaPreviewDialog("))
        assertTrue(mediaDialog.contains("Dialog("))
        assertTrue(mediaDialog.contains(".background(Color.Black)"))
        assertTrue(mediaDialog.contains("visibilityTransition.animateFloat("))
        assertTrue(mediaDialog.contains("label = \"mediaPreviewBackdropAlpha\""))
        assertTrue(mediaDialog.contains(".graphicsLayer { alpha = backdropAlpha }"))
        assertTrue(mediaDialog.contains("DialogWindowNoSystemDim()"))
        assertTrue(imageActions.contains("DialogWindowNoSystemDim()"))
        assertTrue(imageActions.contains("DialogWindowNoSystemAnimation()"))
        assertTrue(imageActions.contains("withFrameNanos { }"))
        assertTrue(imageActions.contains("HttpClient.client.executeWithNetworkRetry("))
        assertTrue(videoPlayer.contains("R.layout.view_texture_video_player"))
        assertTrue(texturePlayerLayout.contains("""app:surface_type="texture_view""""))
        assertTrue(texturePlayerLayout.contains(
            """app:shutter_background_color="@android:color/transparent"""",
        ))
        assertTrue(dialogWindow.contains(
            "clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)"
        ))
        assertTrue(source.contains("if (savedContent != null && savedName != null)"))
        assertTrue(mediaViewer.contains(
            "val currentPageIsVideo = rememberIsVideoMedia(urls[pagerState.currentPage])",
        ))
        assertTrue(mediaViewer.contains("if (currentPageIsVideo == true) closing = true"))
        assertTrue(mediaViewer.contains("else onClose()"))
        assertTrue(mediaViewer.contains("BackHandler { requestClose() }"))
        assertTrue(mediaViewer.contains("onClick = { requestClose() }"))
        assertFalse(mediaViewer.contains("LaunchedEffect(closing)"))
        assertFalse(mediaViewer.contains("kotlinx.coroutines.delay(400)"))
    }

    @Test
    fun `notification permission waits for Chat and gates initial composer focus until dismissal`() {
        val main = sourceFile("app/src/main/java/com/newoether/agora/MainActivity.kt")
        val chat = sourceFile("app/src/main/java/com/newoether/agora/ui/chat/ChatApp.kt")
        val interactionEffects = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/ChatAppInteractionEffects.kt",
        )
        val service = sourceFile(
            "app/src/main/java/com/newoether/agora/service/AgoraForegroundService.kt",
        )
        val activityStartup = main
            .substringAfter("override fun onCreate(savedInstanceState: Bundle?)")
            .substringBefore("override fun onResume()")
        val mainNavigation = main.substringAfter("fun MainNavigation(")
        val permissionLauncher = mainNavigation
            .substringAfter("val notificationPermissionLauncher")
            .substringBefore("LaunchedEffect(Unit)")
        val permissionEffect = mainNavigation
            .substringAfter("LaunchedEffect(Unit) {")
            .substringBefore("var showSettings")
        val launchEffects = interactionEffects
            .substringAfter("internal fun ChatLaunchInteractionEffects(")
            .substringBefore("internal fun ChatNavigationEffects(")
        val generationChannel = service
            .substringAfter("private fun createGenerationChannel(context: Context)")
            .substringBefore("fun showTerminalNotification(")
        val completionChannel = service
            .substringAfter("private fun createCompletionChannel(context: Context)")
            .substringBefore("private fun createPendingIntent(")

        val onboardingBranch = activityStartup.substringAfter("when (showOnboarding)")

        assertFalse(activityStartup.contains("requestPermissions("))
        assertTrue(onboardingBranch.substringAfter("false -> {").contains("MainNavigation("))
        assertTrue(mainNavigation.contains(
            "mutableStateOf(screenshotDestination == null && !shouldRequestNotificationPermission)"
        ))
        assertTrue(permissionLauncher.contains("initialComposerFocusReady = true"))
        assertTrue(permissionEffect.contains("AgoraForegroundService.createChannels(appContext)"))
        assertTrue(permissionEffect.contains("notificationPermissionLauncher.launch("))
        assertFalse(permissionEffect.contains("delay("))
        assertTrue(
            permissionEffect.indexOf("AgoraForegroundService.createChannels(appContext)") <
                permissionEffect.indexOf("notificationPermissionLauncher.launch("),
        )
        assertTrue(main.contains("initialComposerFocusReady = initialComposerFocusReady"))
        assertTrue(chat.contains("initialComposerFocusReady: Boolean = true"))
        assertTrue(chat.contains("ChatLaunchInteractionEffects("))
        assertTrue(launchEffects.contains("LaunchedEffect(Unit)"))
        assertTrue(launchEffects.contains("latestOnShowLaunchContent()"))
        assertTrue(launchEffects.contains(
            "LaunchedEffect(initialComposerFocusReady, inputFocusRequester)"
        ))
        assertTrue(launchEffects.contains("if (initialComposerFocusReady)"))
        assertTrue(launchEffects.contains("inputFocusRequester.requestFocus()"))
        assertTrue(generationChannel.contains("NotificationManager.IMPORTANCE_LOW"))
        assertTrue(generationChannel.contains("setSound(null, null)"))
        assertTrue(generationChannel.contains("setShowBadge(false)"))
        assertTrue(completionChannel.contains("NotificationManager.IMPORTANCE_HIGH"))
        assertTrue(completionChannel.contains("RingtoneManager.TYPE_NOTIFICATION"))
        assertTrue(completionChannel.contains("enableVibration(true)"))
        assertTrue(service.contains(".setPriority(NotificationCompat.PRIORITY_HIGH)"))
    }
}
