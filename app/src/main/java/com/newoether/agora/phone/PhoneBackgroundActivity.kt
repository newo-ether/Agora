package com.newoether.agora.phone

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.newoether.agora.AgoraApplication
import com.newoether.agora.R
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The background screen at full size, over the user's own apps. Immersive, with an operating mode
 * that forwards touches to the display and a preview mode that only shows it. Back or the handle
 * returns to the floating card; the animation grows from and shrinks back to the card's rectangle.
 */
class PhoneBackgroundActivity : ComponentActivity() {
    private var runtime: PhoneUseRuntime? = null
    private val closing = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val control = runCatching { (application as AgoraApplication).requireContainer().phoneUse }.getOrNull()
        val screen = control?.background?.value
        if (control == null || screen == null) {
            finish()
            return
        }
        runtime = control
        control.fullscreen.value = true
        screen.frameIntervalMs = PhoneVirtualScreen.LIVE_FRAME_INTERVAL_MS
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        hideSystemBars()
        val card = intent.rect(EXTRA_CARD)
        setContent {
            PhoneOverlayTheme(control.settings) {
                val requested by closing
                FullScreen(
                    runtime = control,
                    screen = screen,
                    card = card,
                    closing = requested,
                    onRequestClose = ::requestClose,
                    onFinished = ::finishNow,
                )
            }
        }
        onBackPressedDispatcher.addCallback(this) { requestClose() }
        lifecycleScope.launch {
            // A revoked lease, a Stop or a permission loss all end the background screen.
            control.background.collect { if (it == null) finishNow() }
        }
    }

    override fun onStop() {
        // Leaving for another app must not keep the floating card hidden behind it.
        if (!isFinishing && !isChangingConfigurations) finishNow()
        super.onStop()
    }

    override fun onDestroy() {
        runtime?.let { control ->
            control.fullscreen.value = false
            control.background.value?.frameIntervalMs = PhoneVirtualScreen.FRAME_INTERVAL_MS
        }
        super.onDestroy()
    }

    private fun requestClose() {
        closing.value = true
    }

    private fun finishNow() {
        runtime?.fullscreen?.value = false
        if (!isFinishing) finish()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun finish() {
        super.finish()
        // The card fades in underneath; a system transition on top would flash.
        overridePendingTransition(0, 0)
    }

    @Suppress("DEPRECATION")
    private fun Intent.rect(key: String): Rect? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Rect::class.java)
        else getParcelableExtra(key) as? Rect

    companion object {
        private const val EXTRA_CARD = "card"

        internal fun intent(context: Context, card: Rect?): Intent =
            Intent(context, PhoneBackgroundActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_CARD, card)
    }
}

internal enum class FullScreenMode(val label: Int) {
    OPERATE(R.string.phone_background_operate),
    PREVIEW(R.string.phone_background_preview),
}

@Composable
private fun FullScreen(
    runtime: PhoneUseRuntime,
    screen: PhoneVirtualScreen,
    card: Rect?,
    closing: Boolean,
    onRequestClose: () -> Unit,
    onFinished: () -> Unit,
) {
    val frame by screen.frame.collectAsState()
    val motion = LocalAgoraMotionPolicy.current
    val reduce = motion.reduceMotion || motion.systemAnimationsDisabled
    val progress = remember { Animatable(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var mode by remember { mutableStateOf(FullScreenMode.OPERATE) }
    var barVisible by remember { mutableStateOf(true) }
    var injected by remember { mutableStateOf<Offset?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (reduce) progress.snapTo(1f) else progress.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(closing) {
        if (!closing) return@LaunchedEffect
        if (reduce) progress.snapTo(0f) else progress.animateTo(0f, tween(220, easing = LinearOutSlowInEasing))
        onFinished()
    }
    LaunchedEffect(mode, barVisible) {
        if (mode == FullScreenMode.PREVIEW && barVisible) {
            delay(2600)
            barVisible = false
        }
    }

    val scale = if (viewport.width > 0 && viewport.height > 0) {
        min(viewport.width / screen.width.toFloat(), viewport.height / screen.height.toFloat())
    } else 1f
    val origin = card
    val reveal = progress.value

    fun toDisplay(x: Float, y: Float): Offset? {
        if (scale <= 0f) return null
        val left = (viewport.width - screen.width * scale) / 2
        val top = (viewport.height - screen.height * scale) / 2
        val px = (x - left) / scale
        val py = (y - top) / scale
        return if (px in 0f..screen.width.toFloat() && py in 0f..screen.height.toFloat()) Offset(px, py) else null
    }

    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = (origin?.left ?: 0) * (1f - reveal)
                    translationY = (origin?.top ?: 0) * (1f - reveal)
                    scaleX = if (origin != null && viewport.width > 0) {
                        origin.width().toFloat() / viewport.width * (1f - reveal) + reveal
                    } else 1f
                    scaleY = if (origin != null && viewport.height > 0) {
                        origin.height().toFloat() / viewport.height * (1f - reveal) + reveal
                    } else 1f
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                .clip(RoundedCornerShape((CARD_CORNER_DP * (1f - reveal)).dp))
                .background(Color.Black)
                .pointerInteropFilter { event ->
                    if (mode == FullScreenMode.PREVIEW) {
                        if (event.actionMasked == MotionEvent.ACTION_UP) barVisible = !barVisible
                        true
                    } else {
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> toDisplay(event.x, event.y)?.let { point ->
                                injected = point
                                runtime.touchBackground(MotionEvent.ACTION_DOWN, point.x, point.y, event.downTime)
                            }
                            MotionEvent.ACTION_MOVE -> toDisplay(event.x, event.y)?.let { point ->
                                runtime.touchBackground(MotionEvent.ACTION_MOVE, point.x, point.y, event.downTime)
                            }
                            MotionEvent.ACTION_UP -> {
                                injected?.let { point ->
                                    runtime.touchBackground(MotionEvent.ACTION_UP, point.x, point.y, event.downTime)
                                }
                                injected = null
                            }
                            MotionEvent.ACTION_CANCEL -> {
                                injected?.let { point ->
                                    runtime.touchBackground(MotionEvent.ACTION_CANCEL, point.x, point.y, event.downTime)
                                }
                                injected = null
                            }
                        }
                        true
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val bitmap = frame ?: return@Canvas
                val fit = min(size.width / bitmap.width, size.height / bitmap.height)
                val width = bitmap.width * fit
                val height = bitmap.height * fit
                drawImage(
                    bitmap.asImageBitmap(),
                    dstOffset = IntOffset(((size.width - width) / 2).roundToInt(), ((size.height - height) / 2).roundToInt()),
                    dstSize = IntSize(width.roundToInt(), height.roundToInt()),
                )
            }
        }

        AnimatedVisibility(
            visible = reveal > 0.5f,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { -it / 3 },
            exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 3 },
        ) {
            TopBar(
                mode = mode,
                onMode = { next ->
                    mode = next
                    barVisible = true
                },
                onMinimize = onRequestClose,
                onStop = runtime::stopByUser,
            )
        }
        AnimatedVisibility(
            visible = reveal > 0.5f && barVisible && mode == FullScreenMode.OPERATE,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 28.dp)) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    tonalElevation = 6.dp,
                    shadowElevation = 10.dp,
                ) {
                    IconButton(onClick = { scope.launch { runtime.backgroundBack() } }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.phone_background_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    mode: FullScreenMode,
    onMode: (FullScreenMode) -> Unit,
    onMinimize: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
        ) {
            Row(
                Modifier.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onMinimize) {
                    Icon(
                        Icons.Rounded.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.phone_background_minimize),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FullScreenMode.entries.forEach { option ->
                        val selected = option == mode
                        Text(
                            stringResource(option.label),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                )
                                .clickable { onMode(option) }
                                .padding(horizontal = 18.dp, vertical = 9.dp),
                        )
                    }
                }
                IconButton(onClick = onStop) {
                    Icon(
                        Icons.Rounded.Stop,
                        contentDescription = stringResource(R.string.phone_stop),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private const val CARD_CORNER_DP = 14
