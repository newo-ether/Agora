package com.newoether.agora.phone

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.newoether.agora.AgoraApplication
import com.newoether.agora.R
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
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
    /** The background screen ended; the view scales and fades out instead of returning to the card. */
    private val vanishing = mutableStateOf(false)

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
                val ended by vanishing
                FullScreen(
                    runtime = control,
                    screen = screen,
                    card = card,
                    closing = requested,
                    vanishing = ended,
                    onRequestClose = ::requestClose,
                    onFinished = ::finishNow,
                )
            }
        }
        coverAfterFirstFrame(control)
        onBackPressedDispatcher.addCallback(this) { requestClose() }
        lifecycleScope.launch {
            // A revoked lease, a Stop or a permission loss all end the background screen.
            control.background.collect { if (it == null) vanishing.value = true }
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
            control.fullscreenCovers.value = false
            control.background.value?.frameIntervalMs = PhoneVirtualScreen.FRAME_INTERVAL_MS
        }
        super.onDestroy()
    }

    private fun requestClose() {
        closing.value = true
    }

    /**
     * The card is an overlay above this activity. It hides only after this window's first frame,
     * drawn at the card's rect, has reached the screen; hiding it sooner shows a frame of neither.
     */
    private fun coverAfterFirstFrame(control: PhoneUseRuntime) {
        val cover = {
            Choreographer.getInstance().postFrameCallback {
                if (!isFinishing && !closing.value && !vanishing.value) control.fullscreenCovers.value = true
            }
        }
        val decor = window.decorView
        if (Build.VERSION.SDK_INT >= 29) decor.viewTreeObserver.registerFrameCommitCallback { cover() }
        else decor.doOnPreDraw { decor.post { cover() } }
    }

    private fun finishNow() {
        runtime?.fullscreenCovers?.value = false
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

internal enum class FullScreenMode(val label: Int, val id: String) {
    OPERATE(R.string.phone_background_operate, "operate"),
    PREVIEW(R.string.phone_background_preview, "preview");

    companion object {
        fun fromId(id: String): FullScreenMode = entries.firstOrNull { it.id == id } ?: OPERATE
    }
}

@Composable
private fun FullScreen(
    runtime: PhoneUseRuntime,
    screen: PhoneVirtualScreen,
    card: Rect?,
    closing: Boolean,
    vanishing: Boolean,
    onRequestClose: () -> Unit,
    onFinished: () -> Unit,
) {
    val frame by screen.frame.collectAsState()
    val held by runtime.lease.held.collectAsState()
    val action by runtime.actionName.collectAsState()
    var elapsed by remember { mutableLongStateOf(0) }
    LaunchedEffect(held?.epoch) {
        val claimedAt = held?.claimedAt ?: return@LaunchedEffect
        while (true) {
            elapsed = (android.os.SystemClock.elapsedRealtime() - claimedAt) / 1000
            delay(1000)
        }
    }
    val presence = remember { Animatable(1f) }
    val motion = LocalAgoraMotionPolicy.current
    val reduce = motion.reduceMotion || motion.systemAnimationsDisabled
    val controlFade = if (motion.systemAnimationsDisabled) 0 else PHONE_CONTROL_FADE_MS
    val progress = remember { Animatable(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val config by runtime.settings.phoneUse.collectAsState()
    val mode = FullScreenMode.fromId(config.backgroundFullscreenMode)
    val scope = rememberCoroutineScope()
    var barVisible by remember { mutableStateOf(true) }
    var injected by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(Unit) {
        // Grow only once the card above has handed over, so no frame shows both.
        runtime.fullscreenCovers.first { it }
        if (reduce) progress.snapTo(1f) else progress.animateTo(1f, tween(OPEN_MS, easing = EmphasizedEasing))
    }
    LaunchedEffect(vanishing) {
        if (!vanishing) return@LaunchedEffect
        if (reduce) presence.snapTo(0f) else presence.animateTo(0f, tween(VANISH_MS, easing = FastOutSlowInEasing))
        onFinished()
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
    // The display drops its last frame when it ends; the exit keeps drawing that frame.
    val fresh = remember(frame) { frame?.asImageBitmap() }
    var retained by remember { mutableStateOf(fresh) }
    if (fresh != null) SideEffect { retained = fresh }
    val image = fresh ?: retained
    val stopping = closing || vanishing

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

    LaunchedEffect(stopping, mode) {
        if (stopping || mode == FullScreenMode.PREVIEW) cancelInjected(android.os.SystemClock.uptimeMillis())
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .graphicsLayer {
                alpha = presence.value
                val scale = VANISH_SCALE + (1f - VANISH_SCALE) * presence.value
                scaleX = scale
                scaleY = scale
            },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInteropFilter { event ->
                    // Touches during the reveal would land where the image is heading, not where it is.
                    if (stopping || progress.value < 1f) {
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

        // Preview mode taps toggle the bars; operating mode keeps them so Stop stays one tap away.
        val barsShown = shown && !stopping && (barVisible || mode == FullScreenMode.OPERATE)
        AnimatedVisibility(
            visible = barsShown,
            enter = fadeIn(tween(controlFade)) + slideInVertically(tween(controlFade, easing = EmphasizedEasing)) { -it / 2 },
            exit = fadeOut(tween(controlFade)) + slideOutVertically(tween(controlFade)) { -it / 2 },
        ) {
            TopBar(
                elapsed = elapsed,
                status = action ?: stringResource(R.string.phone_thinking),
                thinking = action == null,
                onExit = onRequestClose,
                onStop = runtime::stopByUser,
            )
        }
        AnimatedVisibility(
            visible = barsShown,
            enter = fadeIn(tween(controlFade)) + slideInVertically(tween(controlFade, easing = EmphasizedEasing)) { it / 2 },
            exit = fadeOut(tween(controlFade)) + slideOutVertically(tween(controlFade)) { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomBar(
                mode = mode,
                onMode = { next ->
                    scope.launch { runtime.settings.phoneUseStore.setBackgroundFullscreenMode(next.id) }
                    barVisible = true
                },
            )
        }
    }
}

/** The control strip's segments: duration, status, then exit and Stop. */
@Composable
private fun TopBar(
    elapsed: Long,
    status: String,
    thinking: Boolean,
    onExit: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            PhoneDurationCapsule(elapsed)
            PhoneStatusCapsule(status, thinking = thinking)
            BarButton(RoundedCornerShape(5.dp), onExit) {
                Icon(Icons.Rounded.FullscreenExit, stringResource(R.string.phone_background_exit_fullscreen))
            }
            BarButton(
                RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp, topEnd = 24.dp, bottomEnd = 24.dp),
                onStop, Modifier.phoneCapsuleCenter(roundedStart = false),
            ) {
                Icon(Icons.Rounded.Stop, stringResource(R.string.phone_stop))
            }
        }
    }
}

/** The mode switch as two segments of one strip, like the top bar; the selected one is filled. */
@Composable
private fun BottomBar(mode: FullScreenMode, onMode: (FullScreenMode) -> Unit) {
    Row(
        Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val entries = FullScreenMode.entries
        entries.forEachIndexed { index, option ->
            val selected = option == mode
            val fill by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                tween(160), label = "modeFill",
            )
            val ink by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onPrimaryContainer, tween(160),
                label = "modeInk",
            )
            val first = index == 0
            val last = index == entries.lastIndex
            val shape = RoundedCornerShape(
                topStart = if (first) 24.dp else 5.dp, bottomStart = if (first) 24.dp else 5.dp,
                topEnd = if (last) 24.dp else 5.dp, bottomEnd = if (last) 24.dp else 5.dp,
            )
            Surface(
                onClick = { onMode(option) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { role = Role.Tab },
                shape = shape,
                color = fill,
                contentColor = ink,
            ) {
                Box(Modifier.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(option.label), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun BarButton(
    shape: Shape,
    onClick: () -> Unit,
    contentModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(Modifier.fillMaxSize().then(contentModifier), contentAlignment = Alignment.Center) { content() }
    }
}

private const val CARD_CORNER_DP = 14
private const val OPEN_MS = 360
private const val CLOSE_MS = 300
private const val VANISH_MS = 240
/** Scale the view shrinks to as it fades out when the background screen ends. */
private const val VANISH_SCALE = 0.9f

/** Material 3 emphasized curve; the card launches quickly and settles slowly into place. */
private val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
