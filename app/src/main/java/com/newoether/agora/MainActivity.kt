package com.newoether.agora
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.zIndex
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.service.AgoraForegroundService
import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.ui.chat.ChatApp
import com.newoether.agora.ui.chat.FullScreenMediaPreviewDialog
import com.newoether.agora.ui.chat.MediaPreviewTarget
import com.newoether.agora.ui.onboarding.WelcomeScreen
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.ProvideAgoraMotionPolicy
import com.newoether.agora.ui.settings.SettingsScreen
import com.newoether.agora.ui.tasks.TaskEditorSessionViewModel
import com.newoether.agora.ui.tasks.TaskHistoryPreviewPhase
import com.newoether.agora.ui.theme.AgoraTheme
import com.newoether.agora.util.snackbarTimeoutMillis
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

private fun fullScreenPreviewEnterTransition(allowSpatialTransitions: Boolean): EnterTransition = fadeIn(tween(durationMillis = 220)) + (if (allowSpatialTransitions) scaleIn(tween(durationMillis = 300, easing = FastOutSlowInEasing), initialScale = 0.96f) else EnterTransition.None)
private fun fullScreenPreviewExitTransition(allowSpatialTransitions: Boolean): ExitTransition = fadeOut(tween(durationMillis = 180)) + (if (allowSpatialTransitions) scaleOut(tween(durationMillis = 220, easing = FastOutLinearInEasing), targetScale = 0.96f) else ExitTransition.None)
class MainActivity : ComponentActivity() {

    private val notificationConversationId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    companion object {
        const val EXTRA_CONVERSATION_ID = "com.newoether.agora.extra.CONVERSATION_ID"
        const val EXTRA_SCREENSHOT_DESTINATION = "com.newoether.agora.extra.SCREENSHOT_DESTINATION"
    }

    override fun attachBaseContext(newBase: Context) {
        val langCode = kotlinx.coroutines.runBlocking {
            SettingsManager(newBase).appLanguage.first()
        }
        val locale = when (langCode) {
            "zh" -> java.util.Locale("zh", "CN")
            "en" -> java.util.Locale("en")
            "es" -> java.util.Locale("es")
            "fr" -> java.util.Locale("fr")
            "de" -> java.util.Locale("de")
            "ru" -> java.util.Locale("ru")
            "pt-BR" -> java.util.Locale("pt", "BR")
            "ja" -> java.util.Locale("ja")
            "ko" -> java.util.Locale("ko")
            "ar" -> java.util.Locale("ar")
            "vi" -> java.util.Locale("vi")
            "zh-Hant" -> java.util.Locale.forLanguageTag("zh-Hant")
            else -> null
        }
        if (locale != null) {
            java.util.Locale.setDefault(locale)
            val config = android.content.res.Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        var startupReady = false
        splashScreen.setKeepOnScreenCondition { !startupReady }
        super.onCreate(savedInstanceState)
        handleNavigationIntent(intent)

        com.newoether.agora.util.DebugLog.init(this)

        val settingsManager = SettingsManager(applicationContext)
        val agoraApplication = application as AgoraApplication
        lifecycleScope.launch {
            val databaseStartupState = agoraApplication.awaitDatabaseStartup()
            val needsErrorDialog = databaseStartupState is DatabaseStartupState.Blocked
            withContext(Dispatchers.IO) {
                intent?.getStringExtra(EXTRA_SCREENSHOT_DESTINATION)?.let { destination ->
                    runCatching {
                        Class.forName("com.newoether.agora.screenshot.ScreenshotFixture")
                            .getMethod("seed", AgoraApplication::class.java, String::class.java)
                            .invoke(null, agoraApplication, destination)
                    }.onFailure { error ->
                        if (error !is ClassNotFoundException) {
                            com.newoether.agora.util.DebugLog.e(
                                "MainActivity",
                                "Screenshot fixture failed",
                                error,
                            )
                        }
                    }
                }
                runCatching {
                    settingsManager.initializeFirstInstallDefaults(
                        locale = java.util.Locale.getDefault()
                    )
                }.onFailure { error ->
                    com.newoether.agora.util.DebugLog.e(
                        "MainActivity",
                        "First-install settings initialization failed",
                        error,
                    )
                }
            }

            enableEdgeToEdge()
            // Remove navigation bar scrim so it blends with app content
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            setContent {
            val themeMode by settingsManager.themeMode.collectAsState(initial = "FOLLOW_DEVICE")
            val amoledEnabled by settingsManager.amoledEnabled.collectAsState(initial = false)
            val colorSchemeName by settingsManager.colorScheme.collectAsState(initial = com.newoether.agora.data.DEFAULT_COLOR_SCHEME)
            val schemeStyleName by settingsManager.schemeStyle.collectAsState(initial = com.newoether.agora.data.DEFAULT_SCHEME_STYLE)
            val dynamicColor by settingsManager.dynamicColor.collectAsState(initial = com.newoether.agora.data.DEFAULT_DYNAMIC_COLOR)
            val fontPreference by settingsManager.fontPreference.collectAsState(initial = "app_default")
            val customFontPath by settingsManager.customFontPath.collectAsState(initial = "")
            val appReduceMotion by settingsManager.reduceMotion.collectAsState(initial = false)

            val themeModeEnum = try { com.newoether.agora.ui.theme.ThemeMode.valueOf(themeMode) } catch (_: Exception) { com.newoether.agora.ui.theme.ThemeMode.FOLLOW_DEVICE }
            val colorSchemePreset = try { com.newoether.agora.ui.theme.ColorSchemePreset.valueOf(colorSchemeName) } catch (_: Exception) { com.newoether.agora.ui.theme.ColorSchemePreset.FOREST }
            val schemeStyle = try { com.newoether.agora.ui.theme.SchemeStyle.valueOf(schemeStyleName) } catch (_: Exception) { com.newoether.agora.ui.theme.SchemeStyle.TONAL_SPOT }

            val systemDark = isSystemInDarkTheme()
            val isDark = when (themeModeEnum) {
                com.newoether.agora.ui.theme.ThemeMode.LIGHT -> false
                com.newoether.agora.ui.theme.ThemeMode.DARK -> true
                com.newoether.agora.ui.theme.ThemeMode.FOLLOW_DEVICE -> systemDark
            }

            SideEffect {
                val window = this@MainActivity.window
                val insetsController = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
            }

            AgoraTheme(
                themeMode = themeModeEnum,
                amoledEnabled = amoledEnabled,
                colorSchemePreset = colorSchemePreset,
                schemeStyle = schemeStyle,
                dynamicColor = dynamicColor,
                fontPreference = fontPreference,
                customFontPath = customFontPath
            ) {
                ProvideAgoraMotionPolicy(appReduceMotion = appReduceMotion) {
                val activity = LocalActivity.current

                if (needsErrorDialog) {
                    val databaseScope = rememberCoroutineScope()
                    var clearingDatabase by remember { mutableStateOf(false) }
                    AlertDialog(
                        onDismissRequest = { activity?.finish() },
                        title = { Text(stringResource(R.string.database_incompatible), fontWeight = FontWeight.Bold) },
                        text = { Text(stringResource(R.string.database_incompatible_desc)) },
                        dismissButton = {
                            TextButton(onClick = { activity?.finish() }) { Text(stringResource(R.string.quit)) }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    if (!clearingDatabase) {
                                        clearingDatabase = true
                                        databaseScope.launch {
                                            val cleared = agoraApplication.clearIncompatibleDatabase()
                                            if (cleared) {
                                                activity?.recreate()
                                            } else {
                                                clearingDatabase = false
                                            }
                                        }
                                    }
                                },
                                enabled = !clearingDatabase,
                            ) { Text(stringResource(R.string.clear_database)) }
                        }
                    )
                } else {
                    var showOnboarding by remember { mutableStateOf<Boolean?>(null) }
                    val onboardingScope = rememberCoroutineScope()

                    LaunchedEffect(Unit) {
                        showOnboarding = !settingsManager.onboardingCompleted.first()
                    }

                    // Create ViewModel via the process-scoped DI container (owned by AgoraApplication),
                    // so the same shared singletons back both the UI and background task execution.
                    val container = agoraApplication.requireContainer()
                    val factory = remember { container.chatViewModelFactory() }
                    val viewModel: ChatViewModel = viewModel(factory = factory)

                    when (showOnboarding) {
                        null -> { /* loading — splash screen covers this */ }
                        true -> {
                            WelcomeScreen(
                                onComplete = {
                                    onboardingScope.launch {
                                        settingsManager.saveOnboardingCompleted(true)
                                    }
                                    showOnboarding = false
                                },
                                isDarkTheme = isDark,
                                viewModel = viewModel
                            )
                        }
                        false -> {
                            MainNavigation(
                                viewModel = viewModel,
                                settingsManager = settingsManager,
                                notificationConversationId = notificationConversationId,
                                onNotificationConversationConsumed = { expectedId ->
                                    consumeNotificationTarget(notificationConversationId, expectedId)
                                },
                                screenshotDestination = intent?.getStringExtra(EXTRA_SCREENSHOT_DESTINATION),
                            )
                        }
                    }
                }
            }
            }
            }
            startupReady = true
        }
    }

    override fun onResume() {
        super.onResume()
        AppForegroundTracker.setInForeground(true)
    }

    override fun onPause() {
        super.onPause()
        AppForegroundTracker.setInForeground(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: Intent?) {
        notificationConversationId.value = intent?.getStringExtra(EXTRA_CONVERSATION_ID)
            ?.takeIf { it.isNotBlank() }
            ?: intent?.data?.takeIf { uri ->
                uri.scheme == "agora" && uri.host == "conversation"
            }?.lastPathSegment?.takeIf { it.isNotBlank() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavigation(
    viewModel: ChatViewModel,
    settingsManager: SettingsManager,
    notificationConversationId: kotlinx.coroutines.flow.StateFlow<String?>,
    onNotificationConversationConsumed: (String) -> Unit,
    screenshotDestination: String? = null,
) {
    val appContext = LocalContext.current.applicationContext
    val motionPolicy = LocalAgoraMotionPolicy.current
    val shouldRequestNotificationPermission =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    var initialComposerFocusReady by remember {
        mutableStateOf(screenshotDestination == null && !shouldRequestNotificationPermission)
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        initialComposerFocusReady = true
    }
    LaunchedEffect(Unit) {
        AgoraForegroundService.createChannels(appContext)
        if (shouldRequestNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var showSettings by rememberSaveable {
        mutableStateOf(screenshotDestination?.startsWith("settings") == true)
    }
    var showScreenshotSettings by rememberSaveable(screenshotDestination) {
        mutableStateOf(screenshotDestination?.startsWith("settings") == true)
    }
    LaunchedEffect(screenshotDestination) {
        if (screenshotDestination?.startsWith("settings") == true) {
            showSettings = true
            showScreenshotSettings = true
        }
    }
    var showTasks by rememberSaveable { mutableStateOf(false) }
    var showRemote by rememberSaveable { mutableStateOf(false) }
    val topLevelPresentation = remember {
        TopLevelPresentationState(
            initialOwner = when {
                showRemote -> TopLevelPresentation.REMOTE
                showTasks -> TopLevelPresentation.TASKS
                showSettings -> TopLevelPresentation.SETTINGS
                else -> TopLevelPresentation.CHAT
            },
            onOwnerChanged = { owner ->
                AppForegroundTracker.setChatPresented(owner == TopLevelPresentation.CHAT)
            },
        )
    }
    val tasksListState = rememberLazyListState()
    val taskEditorSession: TaskEditorSessionViewModel = viewModel()
    var taskToOpen by remember { mutableStateOf<String?>(null) }
    val taskHistoryPreview = taskEditorSession.historyPreview
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val isNewChatMode by viewModel.isNewChatMode.collectAsState()
    val isConversationSwitching by viewModel.isSwitching.collectAsState()
    com.newoether.agora.ui.tasks.TaskHistoryDestinationEffect(
        editorSession = taskEditorSession,
        currentConversationId = currentConversationId,
        isNewChatMode = isNewChatMode,
        isSwitching = isConversationSwitching,
    )
    val notificationTarget by notificationConversationId.collectAsState()
    LaunchedEffect(notificationTarget) {
        val id = notificationTarget ?: return@LaunchedEffect
        try {
            val exists = withContext(Dispatchers.IO) {
                (appContext as AgoraApplication).requireContainer().conversationRepository
                    .getConversation(id) != null
            }
            if (exists) {
                showSettings = false
                showRemote = false
                showTasks = false
                taskToOpen = null
                taskEditorSession.clear()
                viewModel.selectConversation(id)
            }
        } finally {
            // A newer notification may have replaced [id] while this effect was suspended.
            // Only consume the event this effect actually handled.
            onNotificationConversationConsumed(id)
        }
    }
    var mediaPreviewTarget by remember { mutableStateOf<MediaPreviewTarget?>(null) }
    var pdfViewerSelection by remember { mutableStateOf(setOf<Int>()) }
    val onTogglePdfSelection: (Int) -> Unit = { page ->
        pdfViewerSelection = if (page in pdfViewerSelection) pdfViewerSelection - page else pdfViewerSelection + page
    }
    val onInitPdfSelection: (Set<Int>) -> Unit = { selection ->
        pdfViewerSelection = selection
    }
    var pdfPreviewFromDialog by remember { mutableStateOf(false) }
    val hapticsEnabled by viewModel.settings.hapticsEnabled.collectAsState()
    val pdfPages by viewModel.mediaPreview.pdfPages.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarVersionState = remember { mutableIntStateOf(0) }
    var snackbarVersion by snackbarVersionState
    val accessibilityManager = LocalAccessibilityManager.current
    var chatSnackbarOffset by remember { mutableStateOf(0.dp) }
    var remoteSnackbarOffset by remember { mutableStateOf(0.dp) }
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Full-screen media viewer (and settings) drop the snackbar to the bottom (nav-bar inset only);
    // in chat it floats above the bottom bar. The animateDpAsState below turns the change into a
    // rise/fall animation as the viewer opens/closes.
    val targetSnackbarPadding = if (showSettings || mediaPreviewTarget != null) navBarPadding
        else if (showRemote) remoteSnackbarOffset else chatSnackbarOffset
    val snackbarBottomPadding by animateDpAsState(
        targetValue = targetSnackbarPadding,
        animationSpec = if (motionPolicy.allowSpatialTransitions) {
            spring(dampingRatio = 1.0f, stiffness = 1000f)
        } else {
            snap()
        },
        label = "snackbarPadding"
    )
    val focusManager = LocalFocusManager.current
    val openMediaPreview: (List<String>, Int) -> Unit = { urls, index ->
        focusManager.clearFocus()
        mediaPreviewTarget = MediaPreviewTarget(urls, index)
        topLevelPresentation.present(TopLevelPresentation.MEDIA_PREVIEW)
    }
    MainApplicationDialogs(
        viewModel = viewModel,
        settingsManager = settingsManager,
        snackbarHostState = snackbarHostState,
        snackbarVersionState = snackbarVersionState,
    )

    val customProviders by viewModel.settings.customProviders.collectAsState()

    // Sandbox outcomes are buffered by their manager and consumed in production order, but display
    // is interrupting: a newer outcome dismisses the one on screen instead of waiting it out.
    LaunchedEffect(Unit) {
        var sandboxSnackbarJob: Job? = null
        viewModel.sandboxManager?.snackbarMessage?.collect { msg ->
            snackbarHostState.currentSnackbarData?.dismiss()
            sandboxSnackbarJob?.cancel()
            sandboxSnackbarJob = launch {
                try {
                    snackbarHostState.showSnackbar(
                        viewModel.displayText(msg),
                    )
                } finally {
                    snackbarVersion++
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        var snackbarJob: Job? = null
        viewModel.snackbarMessage.collect { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarJob?.cancel()
            snackbarJob = launch {
                try {
                    val result = snackbarHostState.showSnackbar(
                        message = event.message,
                        actionLabel = event.actionLabel,
                        duration = if (event.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        event.onAction?.invoke()
                    }
                } finally {
                    snackbarVersion++
                }
            }
        }
    }
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ChatApp(
                viewModel = viewModel,
                initialComposerFocusReady = initialComposerFocusReady,
                onNavigateBack = taskHistoryPreview.backTaskId(currentConversationId, isNewChatMode)
                    ?.let { taskId ->
                        {
                            taskToOpen = taskId
                            taskEditorSession.requestHistoryReturn()
                            taskEditorSession.beginHistoryReturnRestore { restore, onRestoreFailure ->
                                if (restore.originWasNewChat) {
                                    viewModel.restoreNewChatDestination(onRestoreFailure)
                                } else {
                                    restore.originConversationId?.let { origin ->
                                        viewModel.restoreConversationDestination(origin, onRestoreFailure)
                                    }
                                }
                            }
                            topLevelPresentation.present(TopLevelPresentation.TASKS)
                            showTasks = true
                        }
                    },
                drawerEnabled = taskHistoryPreview.backTaskId(currentConversationId, isNewChatMode) == null,
                openDrawerOnStart = screenshotDestination == "drawer",
                initialScrollToTop = screenshotDestination == "chat",
                onOpenSettings = {
                    topLevelPresentation.present(TopLevelPresentation.SETTINGS)
                    showSettings = true
                },
                onOpenRemote = {
                    topLevelPresentation.present(TopLevelPresentation.REMOTE)
                    showRemote = true
                },
                onOpenTasks = { taskId ->
                    taskToOpen = taskId
                    topLevelPresentation.present(TopLevelPresentation.TASKS)
                    showTasks = true
                },
                onMediaClick = openMediaPreview,
                onFileContentClick = { name, content ->
                    focusManager.clearFocus()
                    topLevelPresentation.present(TopLevelPresentation.TEXT_PREVIEW)
                    viewModel.mediaPreview.showFile(name, content)
                },
                onPdfPagesClick = { pages, idx ->
                    focusManager.clearFocus()
                    viewModel.mediaPreview.showPdf(pages, idx)
                    mediaPreviewTarget = MediaPreviewTarget(pages, idx)
                    pdfPreviewFromDialog = false
                    topLevelPresentation.present(TopLevelPresentation.MEDIA_PREVIEW)
                },
                onPdfPreviewSelect = { pages, idx ->
                    focusManager.clearFocus()
                    viewModel.mediaPreview.showPdf(pages, idx)
                    mediaPreviewTarget = MediaPreviewTarget(pages, idx)
                    pdfPreviewFromDialog = true
                    topLevelPresentation.present(TopLevelPresentation.MEDIA_PREVIEW)
                },
                pdfViewerSelection = pdfViewerSelection,
                onTogglePdfSelection = onTogglePdfSelection,
                onInitPdfSelection = onInitPdfSelection,
                fullScreenViewerUrls = mediaPreviewTarget?.urls,
                topLevelPresentation = topLevelPresentation.owner,
                onSnackbarOffsetChanged = { chatSnackbarOffset = it }
            )

            com.newoether.agora.ui.remote.RemoteOverlay(
                visible = showRemote, settings = viewModel.settings,
                hapticsActive = topLevelPresentation.owner == TopLevelPresentation.REMOTE,
                onDismiss = { showRemote = false },
                onExitFinished = { topLevelPresentation.release(TopLevelPresentation.REMOTE) },
                onMessage = viewModel::emitSnackbar,
                onSnackbarOffsetChanged = { remoteSnackbarOffset = it },
                onMediaClick = openMediaPreview,
            )

            SettingsOverlayHost(
                visible = showSettings && screenshotDestination?.startsWith("settings") != true,
                onDismiss = { showSettings = false },
                onExitFinished = {
                    topLevelPresentation.release(TopLevelPresentation.SETTINGS)
                },
            ) {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = {
                        showSettings = false
                    },
                    initialCategory = screenshotDestination
                        ?.substringAfter("settings:", "")
                        ?.ifBlank { null },
                )
            }

            if (showScreenshotSettings && screenshotDestination?.startsWith("settings") == true) {
                Surface(
                    modifier = Modifier.fillMaxSize().zIndex(2f),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onBack = { showScreenshotSettings = false },
                        initialCategory = screenshotDestination
                            .substringAfter("settings:", "")
                            .ifBlank { null },
                    )
                }
            }
            SettingsOverlayHost(
                visible = showTasks,
                onDismiss = {
                    taskEditorSession.clear()
                    showTasks = false
                },
                onExitFinished = {
                    topLevelPresentation.release(TopLevelPresentation.TASKS)
                },
                onEnterFinished = {
                    val preview = taskEditorSession.historyPreview
                    if (preview.phase == TaskHistoryPreviewPhase.RETURNING) {
                        taskEditorSession.markHistoryReturnOverlayCovered(preview.generation)
                    }
                },
            ) {
                com.newoether.agora.ui.tasks.TasksScreen(
                    viewModel = viewModel,
                    editorSession = taskEditorSession,
                    taskListState = tasksListState,
                    backHandlingEnabled = showTasks,
                    initialTaskId = taskToOpen,
                    onInitialTaskHandled = { taskToOpen = null },
                    onBack = {
                        taskEditorSession.clear()
                        showTasks = false
                    },
                    onOpenConversation = { conversationId ->
                        taskEditorSession.openHistory(
                            previewConversationId = conversationId,
                            currentConversationId = currentConversationId,
                            isNewChatMode = isNewChatMode,
                        )
                        showTasks = false
                        viewModel.selectConversation(conversationId)
                    }
                )
            }

            // A dedicated dialog gives the media viewer its own window above source sheets.
            FullScreenMediaPreviewDialog(
                currentTarget = mediaPreviewTarget,
                currentPdfPages = pdfPages,
                currentPdfSelectedPages = pdfViewerSelection,
                currentPdfSelectionEnabled = pdfPreviewFromDialog,
                currentPdfTogglePage = onTogglePdfSelection,
                enter = fullScreenPreviewEnterTransition(motionPolicy.allowSpatialTransitions),
                exit = fullScreenPreviewExitTransition(motionPolicy.allowSpatialTransitions),
                onHidden = {
                    topLevelPresentation.release(TopLevelPresentation.MEDIA_PREVIEW)
                },
                onClose = { target ->
                    if (mediaPreviewTarget?.requestId != target.requestId) return@FullScreenMediaPreviewDialog
                    viewModel.mediaPreview.clear()
                    mediaPreviewTarget = null
                    pdfPreviewFromDialog = false
                },
                onNavigate = { target, idx ->
                    if (mediaPreviewTarget?.requestId == target.requestId) {
                        mediaPreviewTarget = target.copy(index = idx)
                    }
                },
                onMessage = { viewModel.emitSnackbar(it) },
                hapticsEnabled = hapticsEnabled,
            )

            // Text file viewer
            val fileContent by viewModel.mediaPreview.fileContent.collectAsState()
            val fileName by viewModel.mediaPreview.fileName.collectAsState()
            var savedContent by remember { mutableStateOf(fileContent) }
            var savedName by remember { mutableStateOf(fileName) }
            if (fileContent != null) { savedContent = fileContent; savedName = fileName }
            val textPreviewTransition = updateTransition(
                targetState = fileContent != null,
                label = "textPreview",
            )
            LaunchedEffect(textPreviewTransition) {
                snapshotFlow {
                    textPreviewTransition.currentState to textPreviewTransition.isRunning
                }.collect { (currentState, isRunning) ->
                    if (!currentState && !isRunning) {
                        topLevelPresentation.release(TopLevelPresentation.TEXT_PREVIEW)
                    }
                }
            }
            textPreviewTransition.AnimatedVisibility(
                visible = { it },
                enter = fullScreenPreviewEnterTransition(motionPolicy.allowSpatialTransitions),
                exit = fullScreenPreviewExitTransition(motionPolicy.allowSpatialTransitions)
            ) {
                if (savedContent != null && savedName != null) {
                    com.newoether.agora.ui.chat.TextFileViewer(content = savedContent!!, fileName = savedName!!, onClose = { viewModel.mediaPreview.clear() })
                }
            }

            val current = snackbarHostState.currentSnackbarData
            var showing by remember { mutableStateOf(false) }
            var content by remember { mutableStateOf<SnackbarData?>(null) }

            LaunchedEffect(current, snackbarVersion) {
                if (current != null) {
                    if (showing) { showing = false; delay(200) }
                    content = current
                    showing = true
                } else {
                    showing = false
                    delay(400)
                    content = null
                }
            }

            LaunchedEffect(content, accessibilityManager) {
                val data = content ?: return@LaunchedEffect
                val timeoutMillis = snackbarTimeoutMillis(data.visuals, accessibilityManager)
                if (timeoutMillis != Long.MAX_VALUE) {
                    delay(timeoutMillis)
                    if (snackbarHostState.currentSnackbarData === data) {
                        data.dismiss()
                    }
                }
            }

            AnimatedVisibility(
                visible = showing,
                enter = if (motionPolicy.allowSpatialTransitions) {
                    fadeIn(tween(400)) + scaleIn(tween(400), initialScale = 0.8f)
                } else {
                    fadeIn(tween(400))
                },
                exit = if (motionPolicy.allowSpatialTransitions) {
                    fadeOut(tween(400)) + scaleOut(tween(400), targetScale = 0.8f)
                } else {
                    fadeOut(tween(400))
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = snackbarBottomPadding + 2.dp)
            ) {
                content?.let { data ->
                    Snackbar(
                        modifier = Modifier.padding(horizontal = 12.dp).padding(vertical = 10.dp).shadow(6.dp, RoundedCornerShape(12.dp), clip = false),
                        shape = RoundedCornerShape(12.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        actionContentColor = MaterialTheme.colorScheme.primary,
                        dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        dismissAction = @Composable {
                            Box(modifier = Modifier.padding(end = 8.dp)) {
                                IconButton(onClick = { data.dismiss() }, modifier = Modifier.size(28.dp).clip(CircleShape)) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel), modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        action = data.visuals.actionLabel?.let { label ->
                            @Composable { TextButton(onClick = { data.performAction() }) { Text(label) } }
                        },
                        content = { Text(data.visuals.message) }
                    )
                }
            }
        }
    }
}

internal fun consumeNotificationTarget(
    target: kotlinx.coroutines.flow.MutableStateFlow<String?>,
    expectedId: String,
): Boolean = target.compareAndSet(expectedId, null)
