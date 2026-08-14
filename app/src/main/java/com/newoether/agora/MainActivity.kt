package com.newoether.agora

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.key
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.newoether.agora.ui.settings.RatingForm
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.service.AgoraForegroundService
import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.ui.chat.ChatApp
import com.newoether.agora.ui.chat.FullScreenMediaViewer
import com.newoether.agora.ui.chat.message.ChatMarkdownCodeBlock
import com.newoether.agora.ui.onboarding.WelcomeScreen
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.ProvideAgoraMotionPolicy
import com.newoether.agora.ui.settings.SettingsScreen
import com.newoether.agora.ui.tasks.TaskHistoryPreviewPhase
import com.newoether.agora.ui.tasks.TaskHistoryPreviewState
import com.newoether.agora.ui.tasks.TaskHistoryPreviewStateSaver
import com.newoether.agora.ui.theme.AgoraTheme
import com.newoether.agora.util.CrashReporter
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {

    private val notificationConversationId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    companion object {
        const val EXTRA_CONVERSATION_ID = "com.newoether.agora.extra.CONVERSATION_ID"
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
        AgoraForegroundService.createChannel(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
        }

        val settingsManager = SettingsManager(applicationContext)
        lifecycleScope.launch {
            val storedVersion = withContext(Dispatchers.IO) {
                ChatDatabase.getStoredVersion(this@MainActivity)
            }
            val needsErrorDialog = storedVersion > ChatDatabase.CURRENT_VERSION
            withContext(Dispatchers.IO) {
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
            val colorSchemeName by settingsManager.colorScheme.collectAsState(initial = "DEFAULT")
            val schemeStyleName by settingsManager.schemeStyle.collectAsState(initial = "TONAL_SPOT")
            val dynamicColor by settingsManager.dynamicColor.collectAsState(initial = false)
            val fontPreference by settingsManager.fontPreference.collectAsState(initial = "app_default")
            val customFontPath by settingsManager.customFontPath.collectAsState(initial = "")
            val appReduceMotion by settingsManager.reduceMotion.collectAsState(initial = false)

            val themeModeEnum = try { com.newoether.agora.ui.theme.ThemeMode.valueOf(themeMode) } catch (_: Exception) { com.newoether.agora.ui.theme.ThemeMode.FOLLOW_DEVICE }
            val colorSchemePreset = try { com.newoether.agora.ui.theme.ColorSchemePreset.valueOf(colorSchemeName) } catch (_: Exception) { com.newoether.agora.ui.theme.ColorSchemePreset.GEMINI }
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
                                            withContext(Dispatchers.IO) {
                                                applicationContext.deleteDatabase(ChatDatabase.DB_NAME)
                                            }
                                            activity?.recreate()
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
                    val container = (application as AgoraApplication).container
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
) {
    val appContext = LocalContext.current.applicationContext
    val motionPolicy = LocalAgoraMotionPolicy.current
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showTasks by rememberSaveable { mutableStateOf(false) }
    val topLevelPresentation = remember {
        TopLevelPresentationState(
            initialOwner = when {
                showTasks -> TopLevelPresentation.TASKS
                showSettings -> TopLevelPresentation.SETTINGS
                else -> TopLevelPresentation.CHAT
            },
        )
    }
    val tasksListState = rememberLazyListState()
    var taskToOpen by rememberSaveable { mutableStateOf<String?>(null) }
    var taskHistoryPreview by rememberSaveable(
        stateSaver = TaskHistoryPreviewStateSaver,
    ) {
        mutableStateOf(TaskHistoryPreviewState.Idle)
    }
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val isNewChatMode by viewModel.isNewChatMode.collectAsState()
    val notificationTarget by notificationConversationId.collectAsState()
    LaunchedEffect(notificationTarget) {
        val id = notificationTarget ?: return@LaunchedEffect
        try {
            val exists = withContext(Dispatchers.IO) {
                (appContext as AgoraApplication).container.conversationRepository
                    .getConversation(id) != null
            }
            if (exists) {
                showSettings = false
                showTasks = false
                taskToOpen = null
                taskHistoryPreview = TaskHistoryPreviewState.Idle
                viewModel.selectConversation(id)
            }
        } finally {
            // A newer notification may have replaced [id] while this effect was suspended.
            // Only consume the event this effect actually handled.
            onNotificationConversationConsumed(id)
        }
    }
    var fullScreenMediaUrls by remember { mutableStateOf<List<String>?>(null) }
    var fullScreenMediaIndex by remember { mutableIntStateOf(0) }
    var pdfViewerSelection by remember { mutableStateOf(setOf<Int>()) }
    val onTogglePdfSelection: (Int) -> Unit = { page ->
        pdfViewerSelection = if (page in pdfViewerSelection) pdfViewerSelection - page else pdfViewerSelection + page
    }
    val onInitPdfSelection: (Set<Int>) -> Unit = { selection ->
        pdfViewerSelection = selection
    }
    var pdfPreviewFromDialog by remember { mutableStateOf(false) }
    val hapticsEnabled by viewModel.settings.hapticsEnabled.collectAsState()
    val pdfPages by viewModel.previewPdfPages.collectAsState()
    val pdfIndex by viewModel.previewPdfIndex.collectAsState()
    var savedPdfPages by remember { mutableStateOf<List<String>>(emptyList()) }
    if (pdfPages.isNotEmpty()) { savedPdfPages = pdfPages } else { savedPdfPages = emptyList() }
    val snackbarHostState = remember { SnackbarHostState() }
    var snackbarVersion by remember { mutableIntStateOf(0) }
    val accessibilityManager = LocalAccessibilityManager.current
    var chatSnackbarOffset by remember { mutableStateOf(0.dp) }
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Full-screen media viewer (and settings) drop the snackbar to the bottom (nav-bar inset only);
    // in chat it floats above the bottom bar. The animateDpAsState below turns the change into a
    // rise/fall animation as the viewer opens/closes.
    val targetSnackbarPadding = if (showSettings || fullScreenMediaUrls != null) navBarPadding else chatSnackbarOffset
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
    val ratingScope = rememberCoroutineScope()

    // Update dialog
    val updateDialogData by viewModel.updateDialogData.collectAsState()
    if (updateDialogData != null) {
        val info = updateDialogData!!
        val ctx = LocalContext.current
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { viewModel.dismissUpdateDialog() },
            icon = { Icon(Icons.Default.Download, null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary) },
            title = {
                Text(
                    text = stringResource(R.string.about_update_available, info.version),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.about_available_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (info.body.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Column {
                            // Lightweight markdown render of the release notes, kept on the
                            // shared type scale: '## ' → bold section label, '- ' → indented
                            // bullet, blank line → vertical gap, everything else → paragraph.
                            info.body.split("\n").forEach { line ->
                                when {
                                    line.startsWith("## ") -> Text(
                                        text = line.removePrefix("## "),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
                                    )
                                    line.startsWith("- ") -> Text(
                                        text = "•  ${line.removePrefix("- ")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 3.dp, start = 2.dp)
                                    )
                                    line.isBlank() -> Spacer(modifier = Modifier.height(4.dp))
                                    else -> Text(
                                        text = line,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.url)))
                    viewModel.dismissUpdateDialog()
                }) { Text(stringResource(R.string.about_view_release)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUpdateDialog() }) {
                    Text(stringResource(R.string.about_later))
                }
            }
        )
    }

    // Remote shell action confirmation gate
    val pendingShellCommand by viewModel.pendingShellCommand.collectAsState()
    pendingShellCommand?.let { pending ->
        var alwaysAllow by remember(pending) { mutableStateOf(false) }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { viewModel.resolveShellConfirmation(allow = false) },
            icon = { Icon(Icons.Default.Terminal, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary) },
            title = { Text(stringResource(R.string.shell_confirm_title, pending.server), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        ChatMarkdownCodeBlock(code = pending.summary)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .pointerInput(Unit) { detectTapGestures { alwaysAllow = !alwaysAllow } }
                    ) {
                        Checkbox(checked = alwaysAllow, onCheckedChange = { alwaysAllow = it })
                        Text(stringResource(R.string.shell_confirm_always), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveShellConfirmation(allow = true, alwaysAllowServer = alwaysAllow) }) {
                    Text(stringResource(R.string.shell_confirm_allow))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.resolveShellConfirmation(allow = false) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.shell_confirm_deny)) }
            }
        )
    }

    // Crash report — opt-in, shown once on the first launch after an unexpected exit
    val crashContext = LocalContext.current
    var pendingCrash by remember { mutableStateOf<Pair<String, String>?>(null) }
    val crashSubmittedMsg = stringResource(R.string.crash_submitted)
    LaunchedEffect(Unit) {
        pendingCrash = withContext(Dispatchers.IO) {
            CrashReporter.pendingReport(crashContext)?.let { report ->
                val trace = runCatching {
                    org.json.JSONObject(report).optString("trace", "")
                }.getOrDefault("")
                report to trace
            }
        }
    }
    fun dismissPendingCrash() {
        pendingCrash = null
        ratingScope.launch(Dispatchers.IO) { CrashReporter.clear(crashContext) }
    }
    pendingCrash?.let { (report, trace) ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = ::dismissPendingCrash,
            icon = { Icon(Icons.Default.BugReport, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.crash_title), fontWeight = FontWeight.Bold) },
            text = {
                val clipboard = LocalClipboardManager.current
                Column {
                    Text(
                        stringResource(R.string.crash_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(14.dp))
                    // Privacy reassurance as a distinct fine-print block, not just smaller text.
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                null,
                                modifier = Modifier.size(15.dp).padding(top = 1.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.crash_privacy_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (trace.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                stringResource(R.string.crash_log_label),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(trace)) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    stringResource(R.string.copy),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.heightIn(max = 200.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = trace,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily(Font(R.font.jetbrains_mono_regular)),
                                        lineHeight = 16.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingCrash = null
                    ratingScope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            CrashReporter.submit(report).also { submitted ->
                                if (submitted) CrashReporter.clear(crashContext)
                            }
                        }
                        if (ok) {
                            try {
                                snackbarHostState.showSnackbar(crashSubmittedMsg)
                            } finally {
                                snackbarVersion++
                            }
                        }
                    }
                }) { Text(stringResource(R.string.crash_submit)) }
            },
            dismissButton = {
                TextButton(onClick = ::dismissPendingCrash) {
                    Text(stringResource(R.string.crash_dismiss))
                }
            }
        )
    }

    // Rating prompt — read from flow directly to avoid collectAsState initial-value race
    var showRatingPrompt by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        val firstLaunch = settingsManager.firstLaunchTime.first()
        if (firstLaunch == null) {
            settingsManager.saveFirstLaunchTime(now)
        }

        val submitted = settingsManager.ratingPromptSubmitted.first()
        val dismissed = settingsManager.ratingPromptDismissed.first()
        val msgCount = settingsManager.totalMessagesSent.first()
        if (!submitted && !dismissed && firstLaunch != null && msgCount >= 3) {
            val daysElapsed = (now - firstLaunch) / (1000 * 60 * 60 * 24)
            if (daysElapsed >= 7) {
                showRatingPrompt = true
            }
        }
    }

    if (showRatingPrompt) {
        Dialog(
            onDismissRequest = {
                showRatingPrompt = false
                ratingScope.launch {
                    settingsManager.saveRatingPromptDismissed(true)
                }
            }
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                RatingForm(
                    onSubmitted = {
                        showRatingPrompt = false
                        ratingScope.launch {
                            settingsManager.saveRatingPromptSubmitted(true)
                        }
                    }
                )
            }
        }
    }

    // Sandbox events piped into the same global SnackbarHost.
    // Uses a launch+Job pattern so a new message cancels the
    // previous showSnackbar suspension immediately.
    LaunchedEffect(Unit) {
        var snackbarJob: Job? = null
        viewModel.sandboxManager?.snackbarMessage?.collect { msg ->
            if (msg != null) {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarJob?.cancel()
                snackbarJob = launch {
                    try {
                        snackbarHostState.showSnackbar(msg)
                    } finally {
                        snackbarVersion++
                    }
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
                onNavigateBack = taskHistoryPreview.taskId
                    ?.takeIf { taskHistoryPreview.active }
                    ?.let { taskId ->
                        {
                            taskToOpen = taskId
                            taskHistoryPreview = taskHistoryPreview.requestReturn()
                            topLevelPresentation.present(TopLevelPresentation.TASKS)
                            showTasks = true
                        }
                    },
                drawerEnabled = !taskHistoryPreview.active,
                onOpenSettings = {
                    topLevelPresentation.present(TopLevelPresentation.SETTINGS)
                    showSettings = true
                },
                onOpenTasks = { taskId ->
                    taskToOpen = taskId
                    topLevelPresentation.present(TopLevelPresentation.TASKS)
                    showTasks = true
                },
                onMediaClick = { urls, index ->
                    focusManager.clearFocus()
                    topLevelPresentation.present(TopLevelPresentation.MEDIA_PREVIEW)
                    fullScreenMediaUrls = urls
                    fullScreenMediaIndex = index
                },
                onFileContentClick = { name, content ->
                    focusManager.clearFocus()
                    topLevelPresentation.present(TopLevelPresentation.TEXT_PREVIEW)
                    viewModel.showFilePreview(name, content)
                },
                onPdfPagesClick = { pages, idx ->
                    focusManager.clearFocus()
                    topLevelPresentation.present(TopLevelPresentation.MEDIA_PREVIEW)
                    viewModel.showPdfPreview(pages, idx)
                    fullScreenMediaUrls = pages
                    fullScreenMediaIndex = idx
                    pdfPreviewFromDialog = false
                },
                onPdfPreviewSelect = { pages, idx ->
                    focusManager.clearFocus()
                    topLevelPresentation.present(TopLevelPresentation.MEDIA_PREVIEW)
                    viewModel.showPdfPreview(pages, idx)
                    fullScreenMediaUrls = pages
                    fullScreenMediaIndex = idx
                    pdfPreviewFromDialog = true
                },
                pdfViewerSelection = pdfViewerSelection,
                onTogglePdfSelection = onTogglePdfSelection,
                onInitPdfSelection = onInitPdfSelection,
                fullScreenViewerUrls = fullScreenMediaUrls,
                topLevelPresentation = topLevelPresentation.owner,
                onSnackbarOffsetChanged = { chatSnackbarOffset = it }
            )

            SettingsOverlayHost(
                visible = showSettings,
                onDismiss = { showSettings = false },
                onExitFinished = {
                    topLevelPresentation.release(TopLevelPresentation.SETTINGS)
                },
            ) {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = {
                        showSettings = false
                    }
                )
            }

            SettingsOverlayHost(
                visible = showTasks,
                onDismiss = { showTasks = false },
                onExitFinished = {
                    topLevelPresentation.release(TopLevelPresentation.TASKS)
                },
                onEnterFinished = {
                    val preview = taskHistoryPreview
                    if (preview.phase == TaskHistoryPreviewPhase.RETURNING) {
                        if (preview.originWasNewChat) {
                            viewModel.createNewChat()
                        } else {
                            preview.originConversationId?.let { conversationId ->
                                viewModel.selectConversation(
                                    id = conversationId,
                                    hapticOnCompletion = false,
                                )
                            }
                        }
                        taskHistoryPreview = TaskHistoryPreviewState.Idle
                    }
                },
            ) {
                com.newoether.agora.ui.tasks.TasksScreen(
                    viewModel = viewModel,
                    taskListState = tasksListState,
                    initialTaskId = taskToOpen,
                    onInitialTaskHandled = { taskToOpen = null },
                    onBack = { showTasks = false },
                    onOpenConversation = { taskId, conversationId ->
                        taskHistoryPreview = taskHistoryPreview.open(
                            taskId = taskId,
                            currentConversationId = currentConversationId,
                            isNewChatMode = isNewChatMode,
                        )
                        showTasks = false
                        viewModel.selectConversation(conversationId)
                    }
                )
            }

            // Full screen image preview
            val mediaPreviewTransition = updateTransition(
                targetState = fullScreenMediaUrls != null,
                label = "mediaPreview",
            )
            LaunchedEffect(mediaPreviewTransition) {
                snapshotFlow {
                    mediaPreviewTransition.currentState to mediaPreviewTransition.isRunning
                }.collect { (currentState, isRunning) ->
                    if (!currentState && !isRunning) {
                        topLevelPresentation.release(TopLevelPresentation.MEDIA_PREVIEW)
                    }
                }
            }
            mediaPreviewTransition.AnimatedVisibility(
                visible = { it },
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                // Keep the last values for the duration of the exit animation
                var lastUrls by remember { mutableStateOf<List<String>?>(null) }
                var lastIndex by remember { mutableIntStateOf(0) }
                var lastPdfPages by remember { mutableStateOf<List<String>>(emptyList()) }
                var lastPdfTogglePage by remember { mutableStateOf<((Int) -> Unit)?>(null) }
                LaunchedEffect(fullScreenMediaUrls) {
                    if (fullScreenMediaUrls != null) {
                        lastUrls = fullScreenMediaUrls
                        lastIndex = fullScreenMediaIndex
                        lastPdfPages = savedPdfPages
                        lastPdfTogglePage = if (pdfPreviewFromDialog) onTogglePdfSelection else null
                    }
                }

                val urls = lastUrls ?: return@AnimatedVisibility
                FullScreenMediaViewer(
                    urls = urls,
                    initialIndex = lastIndex,
                    pdfPages = lastPdfPages,
                    pdfSelectedPages = if (lastPdfPages.isNotEmpty() && pdfPreviewFromDialog) pdfViewerSelection else null,
                    onTogglePdfPage = lastPdfTogglePage,
                    onClose = { viewModel.clearPreviews(); fullScreenMediaUrls = null; pdfPreviewFromDialog = false },
                    onNavigate = { idx -> fullScreenMediaIndex = idx },
                    onMessage = { viewModel.emitSnackbar(it) },
                    hapticsEnabled = hapticsEnabled
                )
            }

            // Text file viewer
            val fileContent by viewModel.previewFileContent.collectAsState()
            val fileName by viewModel.previewFileName.collectAsState()
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
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                if (savedContent != null && savedName != null) {
                    com.newoether.agora.ui.chat.TextFileViewer(content = savedContent!!, fileName = savedName!!, onClose = { viewModel.clearPreviews() })
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

private fun snackbarTimeoutMillis(
    visuals: SnackbarVisuals,
    accessibilityManager: AccessibilityManager?
): Long {
    val durationMillis = when (visuals.duration) {
        SnackbarDuration.Short -> 4000L
        SnackbarDuration.Long -> 10000L
        SnackbarDuration.Indefinite -> Long.MAX_VALUE
    }
    if (durationMillis == Long.MAX_VALUE) return durationMillis
    return accessibilityManager?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = durationMillis,
        containsIcons = true,
        containsText = true,
        containsControls = visuals.actionLabel != null
    ) ?: durationMillis
}
