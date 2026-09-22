package com.newoether.agora.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.newoether.agora.R
import com.newoether.agora.data.CustomEndpointProtocol
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.data.modelAliasDisplayName
import com.newoether.agora.ui.components.TypewriterMode
import com.newoether.agora.ui.components.TypewriterText
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.ChatViewModel
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class WelcomePage(
    val title: String,
    val description: String,
    val darkVideoResId: Int? = null,
    val lightVideoResId: Int? = null
)

private fun resolveVideoRes(isDarkTheme: Boolean, darkResId: Int?, lightResId: Int?): Int? =
    if (isDarkTheme) darkResId else lightResId ?: darkResId

// Page indices
private const val PAGE_PROVIDER = 2
private const val PAGE_API_KEY = 3
private const val PAGE_MODEL_CONFIG = 5
private const val PAGE_AUTO_BACKUP = 6

@Composable
fun WelcomeScreen(
    onComplete: () -> Unit,
    isDarkTheme: Boolean = true,
    viewModel: ChatViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val motionPolicy = LocalAgoraMotionPolicy.current

    // ── Onboarding state ──
    val builtInProviders = listOf(
        Constants.PROVIDER_GOOGLE, Constants.PROVIDER_OPENAI, Constants.PROVIDER_ANTHROPIC,
        Constants.PROVIDER_DEEPSEEK, Constants.PROVIDER_QWEN, Constants.PROVIDER_GROQ,
        Constants.PROVIDER_OLLAMA, Constants.PROVIDER_OPEN_ROUTER, Constants.PROVIDER_OPENCODE_GO,
        Constants.PROVIDER_REQUESTY
    )
    val customProviders by viewModel.settings.customProviders.collectAsState()
    val allProviders = (builtInProviders + customProviders.map { it.name } + "Custom" + Constants.PROVIDER_LOCAL).distinct()
    var selectedProvider by remember { mutableStateOf<String?>(null) }
    // True for any user-defined endpoint (the "Custom" slot or an already-created
    // provider). Its selected wire protocol determines request and response handling.
    val isCustomProvider = selectedProvider != null &&
        selectedProvider != Constants.PROVIDER_LOCAL && selectedProvider != Constants.PROVIDER_OLLAMA &&
        selectedProvider !in builtInProviders
    var apiKeyText by remember { mutableStateOf("") }
    var baseUrlText by remember { mutableStateOf("") }
    var customProtocol by remember { mutableStateOf(CustomEndpointProtocol.OPENAI) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var selectedModelId by remember { mutableStateOf<String?>(null) }
    val autoBackupEnabled by viewModel.settings.autoBackupEnabled.collectAsState()
    val availableModels by viewModel.settings.availableModels.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()
    val localChatModels by viewModel.settings.localChatModels.collectAsState()
    val existingApiKeys by viewModel.settings.apiKeys.collectAsState()
    val existingProviderUrls by viewModel.settings.providerBaseUrls.collectAsState()

    // Pre-fill API key / URL when switching to a configured provider
    LaunchedEffect(selectedProvider, customProviders) {
        val p = selectedProvider ?: return@LaunchedEffect
        customProtocol = customProviders
            .firstOrNull { it.name == p }
            ?.protocol
            ?: CustomEndpointProtocol.OPENAI
        when {
            p == Constants.PROVIDER_OLLAMA -> {
                val url = existingProviderUrls[Constants.PROVIDER_OLLAMA]
                if (!url.isNullOrBlank()) apiKeyText = url
            }
            p == Constants.PROVIDER_LOCAL -> { /* no pre-fill */ }
            p != "Custom" && p !in builtInProviders -> {
                // Existing custom provider: pre-fill both its URL and key.
                existingProviderUrls[p]?.takeIf { it.isNotBlank() }?.let { baseUrlText = it }
                existingApiKeys.find { it.provider == p }?.key?.takeIf { it.isNotBlank() }?.let { apiKeyText = it }
            }
            else -> {
                val key = existingApiKeys.find { it.provider == p }?.key
                if (!key.isNullOrBlank()) apiKeyText = key
            }
        }
    }

    // ── GGUF import ──
    var showGgufError by remember { mutableStateOf(false) }
    var isImportingGGUF by remember { mutableStateOf(false) }
    val ggufPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            isImportingGGUF = true
            scope.launch {
                try {
                    val imported = withContext(Dispatchers.IO) {
                        val dest = File(context.filesDir, "chat_model_${UUID.randomUUID()}.gguf")
                        try {
                            val aliasName =
                                com.newoether.agora.util.FileValidator.resolveFileName(context, uri)
                                    ?.let {
                                        if (
                                            it.substringAfterLast('.', "")
                                                .equals("gguf", ignoreCase = true)
                                        ) {
                                            it.substringBeforeLast('.')
                                        } else {
                                            it
                                        }
                                    }
                                    ?.trim()
                                    ?.ifBlank { null }
                                    ?: dest.nameWithoutExtension
                            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                                dest.outputStream().use { output -> input.copyTo(output) }
                                true
                            } == true
                            if (!copied) {
                                dest.delete()
                                return@withContext null
                            }
                            val magic = ByteArray(4)
                            val bytesRead = dest.inputStream().use { it.read(magic) }
                            val valid = bytesRead == magic.size &&
                                magic[0] == 'G'.code.toByte() &&
                                magic[1] == 'G'.code.toByte() &&
                                magic[2] == 'U'.code.toByte() &&
                                magic[3] == 'F'.code.toByte()
                            if (valid) {
                                Triple(dest.nameWithoutExtension, aliasName, dest.absolutePath)
                            } else {
                                dest.delete()
                                null
                            }
                        } catch (error: Exception) {
                            dest.delete()
                            throw error
                        }
                    }
                    if (imported == null) {
                        showGgufError = true
                    } else {
                        val (modelId, aliasName, path) = imported
                        localChatModels.forEach { viewModel.modelManager.deleteLocalChatModel(it.id) }
                        viewModel.modelManager.addLocalChatModel(
                            LocalChatModelConfig(
                                modelId = modelId,
                                alias = aliasName,
                                localFilePath = path,
                                nCtx = 16384,
                                maxTokens = 1024,
                            )
                        )
                    }
                } catch (_: Exception) {
                    showGgufError = true
                } finally {
                    isImportingGGUF = false
                }
            }
        }
    }

    // ── Pages ──
    val pages = listOf(
        WelcomePage(stringResource(R.string.onboarding_welcome_title), stringResource(R.string.onboarding_welcome_desc),
            R.raw.welcome_video_1, R.raw.welcome_video_1_light),
        WelcomePage(stringResource(R.string.onboarding_byok_title), stringResource(R.string.onboarding_byok_desc),
            R.raw.welcome_video_2, R.raw.welcome_video_2_light),
        WelcomePage(stringResource(R.string.onboarding_provider_title), stringResource(R.string.onboarding_provider_desc)),
        WelcomePage(stringResource(R.string.onboarding_api_key_title), stringResource(R.string.onboarding_api_key_desc)),
        WelcomePage(stringResource(R.string.onboarding_model_video_title), stringResource(R.string.onboarding_model_video_desc),
            R.raw.welcome_video_3, R.raw.welcome_video_3_light),
        WelcomePage(stringResource(R.string.onboarding_model_select_title), stringResource(R.string.onboarding_model_select_desc)),
        WelcomePage(stringResource(R.string.onboarding_auto_backup_title), stringResource(R.string.onboarding_auto_backup_desc)),
        WelcomePage(stringResource(R.string.onboarding_done_title), stringResource(R.string.onboarding_done_desc),
            R.raw.welcome_video_4, R.raw.welcome_video_4_light)
    )

    // ── Video players (null for config pages) ──
    val players = remember(isDarkTheme) {
        pages.map { page ->
            val resId = resolveVideoRes(isDarkTheme, page.darkVideoResId, page.lightVideoResId)
            resId?.let {
                val uri = "android.resource://${context.packageName}/$it"
                ExoPlayer.Builder(context).build().apply {
                    repeatMode = Player.REPEAT_MODE_ALL; playWhenReady = false
                    setMediaItem(MediaItem.fromUri(uri)); prepare()
                }
            }
        }
    }

    DisposableEffect(players) { onDispose { players.forEach { it?.release() } } }
    LaunchedEffect(motionPolicy.allowContinuousMotion, players) {
        if (!motionPolicy.allowContinuousMotion) {
            players.forEach { player -> player?.pause() }
        }
    }

    val visitedPages = remember { mutableSetOf<Int>() }
    val typedPages = remember { mutableSetOf<Int>() }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    var exiting by remember { mutableStateOf(false) }
    var showContent by remember { mutableStateOf(false) }
    val contentAlpha by animateFloatAsState(if (showContent) 1f else 0f, tween(600))

    // Persist whatever the API Key page collected for the selected provider. Custom
    // providers register their base URL (creating the provider if new) plus key; the
    // built-in/Ollama/Local paths stay as before. Blank fields are skipped.
    val saveProviderCredentials: () -> Unit = save@{
        val p = selectedProvider ?: return@save
        when {
            p == Constants.PROVIDER_LOCAL -> { /* handled by GGUF import */ }
            p == Constants.PROVIDER_OLLAMA -> if (apiKeyText.isNotBlank()) viewModel.settings.setProviderBaseUrl(Constants.PROVIDER_OLLAMA, apiKeyText)
            isCustomProvider -> {
                if (baseUrlText.isNotBlank()) {
                    val existing = customProviders.firstOrNull { it.name == p }
                    if (existing == null) {
                        viewModel.customModelConfiguration.addProvider(p, baseUrlText, customProtocol)
                    } else {
                        viewModel.settings.setProviderBaseUrl(p, baseUrlText)
                        if (existing.protocol != customProtocol) {
                            viewModel.customModelConfiguration.updateProviderProtocol(p, customProtocol)
                        }
                    }
                }
                if (apiKeyText.isNotBlank()) viewModel.settings.upsertApiKey(p, apiKeyText, p)
            }
            else -> if (apiKeyText.isNotBlank()) viewModel.settings.upsertApiKey(p, apiKeyText, p)
        }
    }

    val fm = LocalFocusManager.current
    var prevPage by remember { mutableIntStateOf(0) }
    var fetchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var isFetchingModels by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.currentPage) {
        // Save provider credentials when leaving the API Key page (swipe or button).
        if (prevPage == PAGE_API_KEY) saveProviderCredentials()
        prevPage = pagerState.currentPage
        fm.clearFocus()
        if (pagerState.currentPage !in visitedPages) {
            visitedPages.add(pagerState.currentPage)
            if (motionPolicy.allowContinuousMotion) {
                players[pagerState.currentPage]?.playWhenReady = true
            }
        }
        // Models are fetched only while the Model Select page is visible. (Re)fetch
        // on every entry with the latest key; cancel on leave so an in-flight request
        // never lands off-screen (no list jump) and a stale key's result never wins.
        fetchJob?.cancel()
        if (pagerState.currentPage == PAGE_MODEL_CONFIG && selectedProvider != null && selectedProvider != Constants.PROVIDER_LOCAL) {
            isFetchingModels = true
            fetchJob = scope.launch {
                try {
                    kotlinx.coroutines.delay(300) // debounce swipe-through + let async key save commit
                    viewModel.fetchModelsForProvider(selectedProvider!!)
                } catch (_: Exception) {
                    // Cancellation or network failure: keep whatever the list already shows.
                } finally {
                    isFetchingModels = false
                }
            }
        } else {
            isFetchingModels = false
        }
    }

    LaunchedEffect(motionPolicy.reduceMotion) {
        if (!motionPolicy.reduceMotion) {
            kotlinx.coroutines.delay(2000)
        }
        showContent = true
    }

    LaunchedEffect(exiting) { if (exiting) { kotlinx.coroutines.delay(300); onComplete() } }

    // GGUF error dialog
    if (showGgufError) AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = { showGgufError = false },
        title = { Text(stringResource(R.string.onboarding_invalid_gguf_title), fontWeight = FontWeight.Bold) },
        text = { Text(stringResource(R.string.onboarding_invalid_gguf_desc)) },
        confirmButton = { TextButton(onClick = { showGgufError = false }) { Text(stringResource(R.string.ok)) } }
    )

    AnimatedVisibility(visible = !exiting, exit = fadeOut(tween(300))) {
        Box(modifier = Modifier.fillMaxSize().clearFocusOnTap()) {
            // No imePadding here: onboarding keeps a stable centered layout while
            // the keyboard is open; chat and settings surfaces handle IME insets.
            Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {

                // Skip button
                Box(Modifier.fillMaxWidth().padding(top = 48.dp, end = 16.dp).alpha(contentAlpha), contentAlignment = Alignment.CenterEnd) {
                    TextButton(
                        onClick = { if (pagerState.currentPage < pages.size - 1) exiting = true },
                        enabled = showContent && pagerState.currentPage < pages.size - 1,
                        modifier = Modifier.alpha(if (pagerState.currentPage < pages.size - 1) 1f else 0f)
                    ) { Text(stringResource(R.string.onboarding_skip)) }
                }

                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f), userScrollEnabled = showContent, beyondViewportPageCount = 1) { index ->
                    Column(
                        Modifier.fillMaxSize().graphicsLayer {
                            // Layer reads invalidate only drawing; pager motion no longer
                            // recomposes the full onboarding page on every scroll frame.
                            val pageOffset =
                                (index - pagerState.currentPage) -
                                    pagerState.currentPageOffsetFraction
                            val absOffset = pageOffset.absoluteValue.coerceIn(0f, 1f)
                            scaleX = 1f - absOffset * 0.12f
                            scaleY = 1f - absOffset * 0.12f
                            alpha = 1f - absOffset * 0.4f
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // Main content area (video or config card)
                        Box(Modifier.fillMaxWidth().weight(1.8f), contentAlignment = Alignment.Center) {
                            when (index) {
                                PAGE_PROVIDER -> ProviderPage(
                                    providers = allProviders,
                                    selected = selectedProvider,
                                    onSelect = { selectedProvider = it; apiKeyText = ""; baseUrlText = "" },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp).alpha(contentAlpha),
                                    configuredProviders = existingApiKeys.map { it.provider }.toSet() + existingProviderUrls.filter { it.value.isNotBlank() }.keys
                                )
                                PAGE_API_KEY -> ApiKeyPage(
                                    provider = selectedProvider,
                                    isCustom = isCustomProvider,
                                    apiKeyText = apiKeyText,
                                    onApiKeyChange = { apiKeyText = it },
                                    baseUrlText = baseUrlText,
                                    onBaseUrlChange = { baseUrlText = it },
                                    customProtocol = customProtocol,
                                    onCustomProtocolChange = { customProtocol = it },
                                    apiKeyVisible = apiKeyVisible,
                                    onToggleVisibility = { apiKeyVisible = !apiKeyVisible },
                                    isImporting = isImportingGGUF,
                                    onImportGGUF = { ggufPicker.launch(arrayOf("*/*")) },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).alpha(contentAlpha),
                                    localModels = localChatModels
                                )
                                PAGE_MODEL_CONFIG -> {
                                    val pModels = if (selectedProvider != null) availableModels[selectedProvider] ?: emptyList() else emptyList()
                                    val lModels = localChatModels.map { "${Constants.PROVIDER_LOCAL}:${it.modelId}" }
                                    val models = if (selectedProvider == Constants.PROVIDER_LOCAL) lModels else pModels
                                    val applyModel: (String) -> Unit = { id ->
                                        selectedModelId = id
                                        viewModel.settings.setSelectedModel(id)
                                        viewModel.settings.setEnabledModels(setOf(id))
                                    }
                                    // Auto-apply the first model whenever the current selection
                                    // isn't in the list (initial load, or after a provider/key change).
                                    LaunchedEffect(models) {
                                        if (models.isNotEmpty() && selectedModelId !in models) {
                                            applyModel(models.first())
                                        }
                                    }
                                    ModelPage(
                                        models = models,
                                        modelAliases = modelAliases,
                                        customProviders = customProviders,
                                        selectedId = selectedModelId,
                                        isLoading = isFetchingModels,
                                        onSelect = applyModel,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp).alpha(contentAlpha)
                                    )
                                }
                                PAGE_AUTO_BACKUP -> AutoBackupPage(
                                    enabled = autoBackupEnabled,
                                    onToggle = { viewModel.dataControl.setAutoBackupEnabled(it) },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp).alpha(contentAlpha)
                                )
                                else -> {
                                    Box(Modifier.fillMaxSize()) {
                                        players[index]?.let { LoopVideo(it) }
                                        if (index == 0) FirstVideoScrim(isDarkTheme)
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(24.dp))

                        // Title + description
                        Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp).alpha(contentAlpha)) {
                            val page = pages[index]
                            val title = when {
                                index == PAGE_API_KEY && selectedProvider == Constants.PROVIDER_LOCAL -> stringResource(R.string.onboarding_gguf_title)
                                index == PAGE_API_KEY && selectedProvider == Constants.PROVIDER_OLLAMA -> stringResource(R.string.onboarding_server_url_title)
                                index == PAGE_API_KEY && isCustomProvider -> stringResource(R.string.onboarding_custom_title)
                                else -> page.title
                            }
                            val desc = when {
                                index == PAGE_API_KEY && selectedProvider == Constants.PROVIDER_LOCAL -> stringResource(R.string.onboarding_gguf_desc)
                                index == PAGE_API_KEY && selectedProvider == Constants.PROVIDER_OLLAMA -> stringResource(R.string.onboarding_ollama_desc)
                                index == PAGE_API_KEY && isCustomProvider -> stringResource(R.string.onboarding_custom_desc)
                                index == PAGE_API_KEY && selectedProvider != null -> stringResource(R.string.onboarding_api_key_for, selectedProvider!!)
                                else -> page.description
                            }
                            val isCurrent = pagerState.currentPage == index
                            val show = isCurrent || index in typedPages
                            val anim = isCurrent && index !in typedPages
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                val delay = if (index == 0) 2000 else 0
                                TypewriterText(
                                    text = title,
                                    animationKey = "onboarding-title-$index",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Start,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    typeSpeedMs = 50,
                                    initialDelayMs = if (anim) delay else 0,
                                    animate = anim && motionPolicy.allowContinuousMotion,
                                    showText = show,
                                    mode = TypewriterMode.TEXT_GRADIENT,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(8.dp))
                                TypewriterText(
                                    text = desc,
                                    animationKey = "onboarding-description-$index",
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Start,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    typeSpeedMs = 30,
                                    initialDelayMs = if (anim) delay + 200 else 0,
                                    animate = anim && motionPolicy.allowContinuousMotion,
                                    showText = show,
                                    mode = TypewriterMode.TEXT_GRADIENT,
                                    modifier = Modifier.fillMaxWidth(),
                                    onDone = { if (anim) typedPages.add(index) },
                                )
                            }
                        }

                        Spacer(Modifier.height(48.dp))
                    }
                }

                // Dot indicators. Each dot lives in a fixed 10 dp slot, keeping the row height
                // constant: selection changes no longer shift the whole indicator (or the action
                // below it) vertically, and the tween removes the spring's bounce.
                Row(Modifier.padding(bottom = 16.dp).alpha(contentAlpha), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    repeat(pages.size) { idx ->
                        val sel = pagerState.currentPage == idx
                        val sz by animateDpAsState(if (sel) 10.dp else 8.dp, tween(120))
                        val cl by animateColorAsState(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, tween(120))
                        Box(Modifier.padding(horizontal = 4.dp).size(10.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(sz).clip(CircleShape).background(cl))
                        }
                    }
                }

                // Continue / Get Started
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 48.dp)
                        .navigationBarsPadding()
                        .alpha(contentAlpha),
                ) {
                    val last = pagerState.currentPage == pages.size - 1
                    Box(
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Button(
                            onClick = {
                                if (last) { exiting = true }
                                else {
                                    // Credentials are saved by the page-leave effect (covers both
                                    // swipe and this button), so we only advance here.
                                    if (pagerState.currentPage == PAGE_PROVIDER && selectedProvider != null && selectedProvider != Constants.PROVIDER_LOCAL) apiKeyText = ""
                                    scope.launch {
                                        val targetPage = pagerState.currentPage + 1
                                        if (motionPolicy.allowProgrammaticScrollMotion) {
                                            pagerState.animateScrollToPage(
                                                targetPage,
                                                animationSpec = tween<Float>(
                                                    500,
                                                    easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f),
                                                ),
                                            )
                                        } else {
                                            pagerState.scrollToPage(targetPage)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp)
                                .height(48.dp),
                            shape = RoundedCornerShape(50),
                            enabled = showContent,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) {
                            Text(
                                if (last) stringResource(R.string.onboarding_get_started) else stringResource(R.string.onboarding_continue),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════
//  Scrim
// ═════════════════════════════════════════════════════════════

@Composable
private fun FirstVideoScrim(isDarkTheme: Boolean) {
    var visible by remember { mutableStateOf(true) }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(500))
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(200); visible = false }
    val scrimColor = if (isDarkTheme) Color.Black else Color.White
    Box(Modifier.fillMaxSize().background(scrimColor.copy(alpha = alpha)))
}

// ═════════════════════════════════════════════════════════════
//  Config pages
// ═════════════════════════════════════════════════════════════

@Composable
private fun ModelPage(models: List<String>, modelAliases: Map<String, String>, customProviders: List<CustomProviderConfig>, selectedId: String?, isLoading: Boolean, onSelect: (String) -> Unit, modifier: Modifier) {
    Surface(modifier, RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        if (models.isEmpty()) {
            // While a fetch is in flight show a quiet spinner instead of the empty
            // state, so the list never flashes "no models" then jumps into view.
            // Fixed-height slot keeps the card identical between both states, and
            // Crossfade fades the spinner in/out rather than popping.
            Box(Modifier.fillMaxWidth().padding(32.dp).height(40.dp), contentAlignment = Alignment.Center) {
                Crossfade(targetState = isLoading, animationSpec = tween(400), label = "modelLoading") { loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (loading) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text(stringResource(R.string.onboarding_no_models), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        } else {
            val scrollState = rememberScrollState()
            val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
            val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            Box(Modifier.fillMaxWidth().heightIn(max = 340.dp).drawBehind {
                if (scrollState.maxValue > 0) {
                    val progress = (scrollState.value.toFloat() / scrollState.maxValue).coerceIn(0f, 1f)
                    val barW = 4.dp.toPx()
                    val barX = size.width - barW - 8.dp.toPx()
                    val barH = size.height - 24.dp.toPx()
                    val barY = 12.dp.toPx()
                    drawRoundRect(trackColor, topLeft = Offset(barX, barY), size = Size(barW, barH), cornerRadius = CornerRadius(2.dp.toPx()))
                    val thumbH = barH * 0.35f
                    val thumbY = barY + (barH - thumbH) * progress
                    drawRoundRect(thumbColor, topLeft = Offset(barX, thumbY), size = Size(barW, thumbH), cornerRadius = CornerRadius(2.dp.toPx()))
                }
            }) {
                Column(Modifier.verticalScroll(scrollState)) {
                    Spacer(Modifier.height(10.dp))
                    models.forEach { m ->
                        val name = modelAliasDisplayName(m, modelAliases, customProviders)
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp).clip(RoundedCornerShape(28.dp)).clickable { onSelect(m) }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedId == m, onClick = { onSelect(m) })
                            Spacer(Modifier.width(8.dp))
                            Text(name, style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (selectedId == m) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
private fun LoopVideo(player: ExoPlayer) {
    val context = LocalContext.current
    var isReady by remember { mutableStateOf(false) }
    val a by animateFloatAsState(if (isReady) 1f else 0f, tween(400))

    DisposableEffect(player) {
        if (player.playbackState == Player.STATE_READY) isReady = true
        val l = object : Player.Listener { override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_READY) isReady = true } }
        player.addListener(l); onDispose { player.removeListener(l) }
    }
    AndroidView(
        factory = { PlayerView(context).apply { this.player = player; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT } },
        modifier = Modifier.fillMaxSize().aspectRatio(1f).alpha(a)
    )
}

@Composable
private fun AutoBackupPage(enabled: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier) {
    Surface(modifier, RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column(Modifier.padding(32.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.auto_backup_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable { onToggle(!enabled) }.padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.auto_backup_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.auto_backup_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(16.dp))
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
        }
    }
}
