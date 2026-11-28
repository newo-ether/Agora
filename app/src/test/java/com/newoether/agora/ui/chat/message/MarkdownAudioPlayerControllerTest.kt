package com.newoether.agora.ui.chat.message

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.newoether.agora.sandbox.SandboxManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MarkdownAudioPlayerControllerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun playsLocalSandboxHomeTrackAndKeepsPlayingWhenSheetDismissed() = runBlocking {
        val context = isolatedContext()
        val source = File(context.filesDir, "sandbox-home/music/demo.mp3").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x49, 0x44, 0x33, 0x03))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val players = mutableListOf<FakeAudioPlayer>()
        try {
            val controller = newController(context, scope) {
                FakeAudioPlayer().also { players += it }.mock
            }
            val track = buildMarkdownAudioTrack("/home/agora/music/demo.mp3", "Demo Track")!!

            controller.playOrToggle(track, openSheet = true)
            val ready = awaitState(controller) { it.isPlaying && it.resolvedFile != null }
            assertEquals(track, ready.track)
            assertEquals(source.canonicalFile, ready.resolvedFile?.canonicalFile)
            assertTrue(ready.sheetExpanded)
            assertEquals(120_000L, ready.durationMs)
            assertEquals(1, players.size)
            assertEquals(
                Uri.fromFile(source.canonicalFile),
                players.single().lastMediaItem?.localConfiguration?.uri,
            )

            // Dismissing the expanded sheet collapses it while keeping playback active.
            controller.dismissSheet()
            assertFalse(controller.state.value.sheetExpanded)
            assertTrue(controller.state.value.isPlaying)

            // Tapping the active card body re-opens the sheet without pausing playback.
            controller.playOrToggle(track, openSheet = true)
            assertTrue(controller.state.value.sheetExpanded)
            assertTrue(controller.state.value.isPlaying)

            // Tapping the play button pauses, and togglePlayPause resumes.
            controller.playOrToggle(track, openSheet = false)
            assertFalse(controller.state.value.isPlaying)
            controller.togglePlayPause()
            assertTrue(controller.state.value.isPlaying)

            // Explicit pause silences playback without clearing track or sheet state.
            controller.pause()
            assertFalse(controller.state.value.isPlaying)
            assertEquals(track, controller.state.value.track)
            assertTrue(controller.state.value.sheetExpanded)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun copiesSandboxRootfsAudioAndSupportsSeekAndSpeedCycle() = runBlocking {
        val context = isolatedContext()
        val rootfs = temporary.newFolder("rootfs")
        val payload = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00)
        val source = File(rootfs, "tmp/song.wav").apply {
            parentFile?.mkdirs()
            writeBytes(payload)
        }
        val manager = mockk<SandboxManager>()
        coEvery { manager.isAvailable() } returns true
        every { manager.resolveHostFile("/tmp/song.wav") } returns source

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fakePlayer = FakeAudioPlayer(durationMs = 90_000L)
        try {
            val controller = newController(
                context = context,
                scope = scope,
                sandboxEnabled = { true },
                sandbox = { manager },
            ) { fakePlayer.mock }
            val track = buildMarkdownAudioTrack("shell://Local%20Sandbox/tmp/song.wav")!!

            controller.playOrToggle(track, openSheet = false)
            val ready = awaitState(controller) { it.isPlaying && it.resolvedFile != null }
            assertFalse(ready.sheetExpanded)
            assertNotNull(ready.resolvedFile)
            assertArrayEquals(payload, ready.resolvedFile!!.readBytes())

            controller.seekTo(45_000L)
            assertEquals(45_000L, controller.state.value.positionMs)
            assertEquals(45_000L, fakePlayer.positionMs)

            controller.seekBy(10_000L)
            assertEquals(55_000L, controller.state.value.positionMs)
            controller.seekBy(-100_000L)
            assertEquals(0L, controller.state.value.positionMs)
            controller.seekBy(500_000L)
            assertEquals(90_000L, controller.state.value.positionMs)

            val expectedSpeeds = listOf(1.25f, 1.5f, 2.0f, 0.75f, 1.0f)
            for (expected in expectedSpeeds) {
                controller.cyclePlaybackSpeed()
                assertEquals(expected, controller.state.value.playbackSpeed, 0.001f)
                assertEquals(expected, fakePlayer.playbackSpeed, 0.001f)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun stopAndCloseReleasesPlayerAndPreservesPlaybackSpeed() = runBlocking {
        val context = isolatedContext()
        val source = File(context.filesDir, "sandbox-home/music/clip.ogg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x4F, 0x67, 0x67, 0x53))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val players = mutableListOf<FakeAudioPlayer>()
        try {
            val controller = newController(context, scope) {
                FakeAudioPlayer().also { players += it }.mock
            }
            val track = buildMarkdownAudioTrack("/home/agora/music/clip.ogg")!!

            controller.playOrToggle(track, openSheet = true)
            awaitState(controller) { it.isPlaying }
            controller.cyclePlaybackSpeed()
            assertEquals(1.25f, controller.state.value.playbackSpeed, 0.001f)

            controller.stopAndClose()
            assertTrue(players.first().released)
            assertNull(controller.state.value.track)
            assertNull(controller.state.value.resolvedFile)
            assertFalse(controller.state.value.isPlaying)
            assertFalse(controller.state.value.sheetExpanded)
            assertEquals(1.25f, controller.state.value.playbackSpeed, 0.001f)

            controller.playOrToggle(track, openSheet = false)
            awaitState(controller) { it.isPlaying }
            assertEquals(2, players.size)
            assertEquals(1.25f, players.last().playbackSpeed, 0.001f)
            assertEquals(source.canonicalFile, controller.state.value.resolvedFile?.canonicalFile)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun missingFileAndPlayerErrorResetStateAndInvokeErrorCallback() = runBlocking {
        val context = isolatedContext()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val players = mutableListOf<FakeAudioPlayer>()
        try {
            val controller = newController(context, scope) {
                FakeAudioPlayer().also { players += it }.mock
            }

            val missingTrack = buildMarkdownAudioTrack("/home/agora/music/missing.mp3")!!
            var missingError: String? = null
            controller.playOrToggle(missingTrack) { missingError = it }
            awaitState(controller) { it.track == null && missingError != null }
            assertTrue(missingError!!.isNotBlank())
            assertTrue(players.isEmpty())

            val validFile = File(context.filesDir, "sandbox-home/music/valid.mp3").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(0x49, 0x44, 0x33))
            }
            assertTrue(validFile.isFile)
            val validTrack = buildMarkdownAudioTrack("/home/agora/music/valid.mp3")!!
            var playbackError: String? = null
            controller.playOrToggle(validTrack, openSheet = true) { playbackError = it }
            awaitState(controller) { it.isPlaying }

            players.single().emitError()
            assertTrue(players.single().released)
            assertNull(controller.state.value.track)
            assertFalse(controller.state.value.isPlaying)
            assertFalse(controller.state.value.sheetExpanded)
            assertTrue(playbackError?.isNotBlank() == true)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun switchingTracksSuppressesIntermediateIdleStateFromSetMediaItem() = runBlocking {
        val context = isolatedContext()
        val firstFile = File(context.filesDir, "sandbox-home/music/one.mp3").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x49, 0x44, 0x33, 0x01))
        }
        val secondFile = File(context.filesDir, "sandbox-home/music/two.mp3").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x49, 0x44, 0x33, 0x02))
        }
        assertTrue(firstFile.isFile && secondFile.isFile)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fakePlayer = FakeAudioPlayer(emitIdleOnSetMediaItem = true)
        var bufferingClearedDuringSetMediaItem = false
        try {
            val controller = newController(context, scope) { fakePlayer.mock }
            val firstTrack = buildMarkdownAudioTrack("/home/agora/music/one.mp3", "One")!!
            val secondTrack = buildMarkdownAudioTrack("/home/agora/music/two.mp3", "Two")!!

            controller.playOrToggle(firstTrack, openSheet = false)
            awaitState(controller) { it.track == firstTrack && it.isPlaying }

            fakePlayer.afterIdleOnSetMediaItem = {
                if (!controller.state.value.isBuffering) {
                    bufferingClearedDuringSetMediaItem = true
                }
            }
            controller.playOrToggle(secondTrack, openSheet = false)
            val switched = awaitState(controller) { it.track == secondTrack && it.isPlaying }
            assertFalse(bufferingClearedDuringSetMediaItem)
            assertEquals(secondFile.canonicalFile, switched.resolvedFile?.canonicalFile)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun transientSeekBufferingKeepsPlayingStateUntilGraceWindowExpires() = runBlocking {
        val context = isolatedContext()
        val file = File(context.filesDir, "sandbox-home/music/seek.mp3").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x49, 0x44, 0x33, 0x05))
        }
        assertTrue(file.isFile)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fakePlayer = FakeAudioPlayer()
        try {
            val controller = newController(context, scope) { fakePlayer.mock }
            val track = buildMarkdownAudioTrack("/home/agora/music/seek.mp3", "Seek")!!

            controller.playOrToggle(track, openSheet = false)
            awaitState(controller) { it.isPlaying && it.durationMs > 0L }

            // Brief ExoPlayer rebuffering during a seek should not drop isPlaying or flash busy.
            controller.seekTo(30_000L)
            fakePlayer.emitTransientSeekBuffering()
            ShadowLooper.idleMainLooper()
            assertTrue(controller.state.value.isPlaying)
            assertFalse(controller.state.value.isBuffering)

            fakePlayer.emitReadyResumed()
            ShadowLooper.idleMainLooper()
            assertTrue(controller.state.value.isPlaying)
            assertFalse(controller.state.value.isBuffering)

            // Prolonged buffering beyond the grace window surfaces the buffering state.
            fakePlayer.emitTransientSeekBuffering()
            ShadowLooper.idleMainLooper(
                MarkdownAudioPlayerController.SEEK_BUFFERING_GRACE_MS + 50L,
                TimeUnit.MILLISECONDS,
            )
            val stalled = controller.state.value
            assertTrue(stalled.isBuffering)
            assertFalse(stalled.isPlaying)

            fakePlayer.emitReadyResumed()
            ShadowLooper.idleMainLooper()
            assertTrue(controller.state.value.isPlaying)
            assertFalse(controller.state.value.isBuffering)

            // Pausing during the buffering grace window immediately clears isPlaying.
            fakePlayer.emitTransientSeekBuffering()
            ShadowLooper.idleMainLooper()
            assertTrue(controller.state.value.isPlaying)
            controller.pause()
            ShadowLooper.idleMainLooper()
            assertFalse(controller.state.value.isPlaying)
            assertFalse(controller.state.value.isBuffering)
        } finally {
            scope.cancel()
        }
    }

    private fun newController(
        context: Context,
        scope: CoroutineScope,
        sandboxEnabled: () -> Boolean = { false },
        sandbox: () -> SandboxManager? = { null },
        createPlayer: (Context) -> Player,
    ): MarkdownAudioPlayerController {
        val loader = ShellMarkdownVideo(
            context = context,
            devices = { emptyList() },
            scope = scope,
            cacheDirectoryName = "markdown-audio",
            sandboxEnabled = sandboxEnabled,
            sandbox = sandbox,
        )
        return MarkdownAudioPlayerController(
            context = context,
            audioLoader = loader,
            scope = scope,
            createPlayer = createPlayer,
        )
    }

    private suspend fun awaitState(
        controller: MarkdownAudioPlayerController,
        predicate: (MarkdownAudioPlaybackState) -> Boolean,
    ): MarkdownAudioPlaybackState {
        repeat(100) {
            ShadowLooper.idleMainLooper()
            val snapshot = controller.state.value
            if (predicate(snapshot)) return snapshot
            delay(20)
        }
        error("Timed out waiting for audio playback state; last=${controller.state.value}")
    }

    private fun isolatedContext(): Context {
        val cache = temporary.newFolder("cache")
        val files = temporary.newFolder("files")
        val base = RuntimeEnvironment.getApplication()
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = cache
            override fun getFilesDir(): File = files
        }
    }

    private class FakeAudioPlayer(
        var durationMs: Long = 120_000L,
        private val emitIdleOnSetMediaItem: Boolean = false,
    ) {
        var listener: Player.Listener? = null
        var lastMediaItem: MediaItem? = null
        var afterIdleOnSetMediaItem: (() -> Unit)? = null
        var playbackSpeed: Float = 1.0f
        var positionMs: Long = 0L
        var state: Int = Player.STATE_IDLE
        var playing: Boolean = false
        var playWhenReadyValue: Boolean = false
        var released: Boolean = false

        val mock: Player = mockk(relaxed = true)

        init {
            every { mock.addListener(any()) } answers { listener = firstArg() }
            every { mock.removeListener(any()) } answers {
                if (listener === firstArg<Player.Listener>()) listener = null
            }
            every { mock.setMediaItem(any()) } answers {
                lastMediaItem = firstArg()
                if (emitIdleOnSetMediaItem) {
                    state = Player.STATE_IDLE
                    listener?.onPlaybackStateChanged(Player.STATE_IDLE)
                    afterIdleOnSetMediaItem?.invoke()
                }
            }
            every { mock.setPlaybackSpeed(any()) } answers { playbackSpeed = firstArg() }
            every { mock.prepare() } answers {
                state = Player.STATE_READY
                listener?.onPlaybackStateChanged(Player.STATE_READY)
            }
            every { mock.playWhenReady = any() } answers {
                val wantPlay = firstArg<Boolean>()
                playWhenReadyValue = wantPlay
                if (wantPlay && state == Player.STATE_READY && !playing) {
                    playing = true
                    listener?.onIsPlayingChanged(true)
                } else if (!wantPlay && playing) {
                    playing = false
                    listener?.onIsPlayingChanged(false)
                }
            }
            every { mock.play() } answers {
                playWhenReadyValue = true
                if (!playing) {
                    playing = true
                    listener?.onIsPlayingChanged(true)
                }
            }
            every { mock.pause() } answers {
                playWhenReadyValue = false
                listener?.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                if (playing) {
                    playing = false
                    listener?.onIsPlayingChanged(false)
                }
            }
            every { mock.seekTo(any<Long>()) } answers {
                positionMs = firstArg()
            }
            every { mock.release() } answers {
                released = true
                playing = false
            }
            every { mock.isPlaying } answers { playing }
            every { mock.playWhenReady } answers { playWhenReadyValue }
            every { mock.playbackSuppressionReason } returns Player.PLAYBACK_SUPPRESSION_REASON_NONE
            every { mock.playbackState } answers { state }
            every { mock.currentPosition } answers { positionMs }
            every { mock.duration } answers { durationMs }
        }

        fun emitTransientSeekBuffering() {
            state = Player.STATE_BUFFERING
            playing = false
            listener?.onPlaybackStateChanged(Player.STATE_BUFFERING)
            listener?.onIsPlayingChanged(false)
        }

        fun emitReadyResumed() {
            state = Player.STATE_READY
            listener?.onPlaybackStateChanged(Player.STATE_READY)
            if (playWhenReadyValue && !playing) {
                playing = true
                listener?.onIsPlayingChanged(true)
            }
        }

        fun emitError() {
            listener?.onPlayerError(mockk<PlaybackException>(relaxed = true))
        }
    }
}
