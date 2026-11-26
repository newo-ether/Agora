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
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
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
            // The card hides while full screen is requested; never leave it hidden behind nothing.
            control?.fullscreen?.value = false
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
        if (reduce) progress.snapTo(1f) else progress.animateTo(1f, tween(OPEN_MS, easing = EmphasizedEasing))
    }
    LaunchedEffect(closing) {
        if (!closing) return@LaunchedEffect
        if (reduce) progress.snapTo(0f) else progress.animateTo(0f, tween(CLOSE_MS, easing = EmphasizedEasing))
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
    } else 0f
    // Composition only needs to know which half of the reveal it is in; the frames read progress
    // in the draw phase alone.
    val shown by remember { derivedStateOf { progress.value > 0.5f } }
    val image = remember(frame) { frame?.asImageBitmap() }

    /**
     * Viewport point to display pixels. A gesture starts only on the image; once started, a finger
     * past the image edge sticks to the edge.
     */
    fun toDisplay(x: Float, y: Float, starting: Boolean): Offset? {
        if (scale <= 0f) return null
        val left = (viewport.width - screen.width * scale) / 2
        val top = (viewport.height - screen.height * scale) / 2
        val rawX = (x - left) / scale
        val rawY = (y - top) / scale
        if (starting && (rawX < 0f || rawY < 0f || rawX >= screen.width || rawY >= screen.height)) return null
        val px = rawX.coerceIn(0f, (screen.width - 1).toFloat())
        val py = rawY.coerceIn(0f, (screen.height - 1).toFloat())
        return Offset(px, py)
    }

    /** Ends a forwarded gesture without a tap, so the display never keeps a finger down. */
    fun cancelInjected(downTime: Long) {
        injected?.let { point -> runtime.touchBackground(MotionEvent.ACTION_CANCEL, point.x, point.y, downTime) }
        injected = null
    }

    LaunchedEffect(closing, mode) {
        if (closing || mode == FullScreenMode.PREVIEW) cancelInjected(android.os.SystemClock.uptimeMillis())
    }

    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInteropFilter { event ->
                    // Touches during the reveal would land where the image is heading, not where it is.
                    if (closing || progress.value < 1f) {
                        cancelInjected(event.downTime)
                        return@pointerInteropFilter true
                    }
                    if (mode == FullScreenMode.PREVIEW) {
                        if (event.actionMasked == MotionEvent.ACTION_UP) barVisible = !barVisible
                        return@pointerInteropFilter true
                    }
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> toDisplay(event.x, event.y, starting = true)?.let { point ->
                            injected = point
                            runtime.touchBackground(MotionEvent.ACTION_DOWN, point.x, point.y, event.downTime)
                        }
                        // A move without an injected down would reach the display as a stray gesture.
                        MotionEvent.ACTION_MOVE -> if (injected != null) {
                            toDisplay(event.x, event.y, starting = false)?.let { point ->
                                injected = point
                                runtime.touchBackground(MotionEvent.ACTION_MOVE, point.x, point.y, event.downTime)
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            injected?.let { point ->
                                runtime.touchBackground(event.actionMasked, point.x, point.y, event.downTime)
                            }
                            injected = null
                        }
                    }
                    true
                },
        ) {
            val reveal = progress.value
            drawRect(Color.Black, alpha = reveal)
            val bitmap = image ?: return@Canvas
            val fitScale = min(size.width / bitmap.width, size.height / bitmap.height)
            val fitSize = Size(bitmap.width * fitScale, bitmap.height * fitScale)
            val fit = ComposeRect(
                Offset((size.width - fitSize.width) / 2, (size.height - fitSize.height) / 2),
                fitSize,
            )
            // Read each frame: a rotation while full screen refits the hidden card.
            val from = (runtime.previewCard ?: card)?.let {
                ComposeRect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
            } ?: fit
            val bounds = lerp(from, fit, reveal)
            val corner = CARD_CORNER_DP.dp.toPx() * (1f - reveal)
            val outline = Path().apply { addRoundRect(RoundRect(bounds, CornerRadius(corner))) }
            clipPath(outline) {
                drawImage(
                    bitmap,
                    dstOffset = IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()),
                    dstSize = IntSize(bounds.width.roundToInt(), bounds.height.roundToInt()),
                )
            }
        }

        AnimatedVisibility(
            // Preview mode taps toggle the bar; operating mode keeps it so Stop stays one tap away.
            visible = shown && (barVisible || mode == FullScreenMode.OPERATE),
            enter = fadeIn(tween(220)) + slideInVertically(tween(220, easing = EmphasizedEasing)) { -it / 2 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 2 },
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
            visible = shown && barVisible && mode == FullScreenMode.OPERATE,
            enter = fadeIn(tween(220)) + slideInVertically(tween(220, easing = EmphasizedEasing)) { it / 2 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { it / 2 },
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
private const val OPEN_MS = 360
private const val CLOSE_MS = 300

/** Material 3 emphasized curve; the card launches quickly and settles slowly into place. */
private val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
