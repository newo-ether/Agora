package com.newoether.agora.ui.chat.message

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.newoether.agora.R
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Canonical metadata for one playable Markdown audio track. */
internal data class MarkdownAudioTrack(
    val key: String,
    val link: String,
    val title: String,
    val extensionBadge: String,
    val source: MarkdownAudioSource,
)

/** Session-wide audio playback state shared by inline cards, the mini player bar, and the sheet. */
internal data class MarkdownAudioPlaybackState(
    val track: MarkdownAudioTrack? = null,
    val resolvedFile: File? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isDownloading: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val sheetExpanded: Boolean = false,
)

/**
 * Owns the single active [Player] for Markdown audio clips at the ViewModel scope so playback
 * survives message list scrolling, recomposition, and conversation switches.
 */
internal class MarkdownAudioPlayerController(
    private val context: Context,
    private val audioLoader: ShellMarkdownVideo,
    private val scope: CoroutineScope,
    private val createPlayer: (Context) -> Player = ::defaultMarkdownAudioPlayer,
) {
    private val _state = MutableStateFlow(MarkdownAudioPlaybackState())
    val state: StateFlow<MarkdownAudioPlaybackState> = _state.asStateFlow()

    private var player: Player? = null
    private var loadedTrackKey: String? = null
    private var suppressIdleState: Boolean = false
    private var loadJob: Job? = null
    private var tickerJob: Job? = null
    private var bufferingGraceJob: Job? = null
    private var activeErrorCallback: ((String) -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (loadedTrackKey == null) return
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    if (_state.value.durationMs <= 0L) {
                        _state.update { it.copy(isBuffering = true) }
                    } else {
                        scheduleBufferingGrace()
                    }
                }
                Player.STATE_READY -> {
                    cancelBufferingGrace()
                    val dur = player?.duration?.takeIf { it > 0L }
                    _state.update { current ->
                        current.copy(
                            isBuffering = false,
                            durationMs = dur ?: current.durationMs,
                        )
                    }
                }
                Player.STATE_ENDED -> {
                    cancelBufferingGrace()
                    stopTicker()
                    player?.let { active ->
                        active.seekTo(0L)
                        active.playWhenReady = false
                    }
                    _state.update {
                        it.copy(
                            isPlaying = false,
                            isBuffering = false,
                            positionMs = 0L,
                        )
                    }
                }
                Player.STATE_IDLE -> if (!suppressIdleState) {
                    cancelBufferingGrace()
                    _state.update { it.copy(isBuffering = false) }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (loadedTrackKey == null) return
            if (isPlaying) {
                cancelBufferingGrace()
                _state.update { it.copy(isPlaying = true, isBuffering = false) }
                startTicker()
                return
            }
            stopTicker()
            val active = player
            val transientSeekRebuffer = _state.value.isPlaying &&
                _state.value.durationMs > 0L &&
                active != null &&
                active.playWhenReady &&
                active.playbackState == Player.STATE_BUFFERING &&
                active.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
            if (transientSeekRebuffer) {
                scheduleBufferingGrace()
            } else {
                _state.update { it.copy(isPlaying = false) }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (loadedTrackKey == null) return
            if (!playWhenReady) {
                cancelBufferingGrace()
                stopTicker()
                _state.update { it.copy(isPlaying = false) }
            }
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            if (loadedTrackKey == null) return
            if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                cancelBufferingGrace()
                stopTicker()
                _state.update { it.copy(isPlaying = false) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (loadedTrackKey == null) return
            val notify = activeErrorCallback
            stopAndClose()
            notify?.invoke(context.getString(R.string.audio_playback_failed))
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (loadedTrackKey == null) return
            _state.update { it.copy(positionMs = newPosition.positionMs.coerceAtLeast(0L)) }
        }
    }

    /**
     * Plays or toggles [track]. When [openSheet] is true, the expanded player sheet is opened;
     * tapping an already-loaded active track's body opens the sheet without pausing it.
     */
    fun playOrToggle(
        track: MarkdownAudioTrack,
        openSheet: Boolean = false,
        onError: (String) -> Unit = {},
    ) {
        activeErrorCallback = onError
        val current = _state.value
        if (current.track?.key == track.key) {
            if (openSheet) {
                _state.update { it.copy(sheetExpanded = true) }
                if (loadedTrackKey != track.key && loadJob?.isActive != true) {
                    beginLoadAndPlay(track, openSheet = true, onError = onError)
                }
                return
            }
            if (current.isDownloading) return
            if (loadedTrackKey == track.key && player != null) {
                togglePlayPause()
                return
            }
            beginLoadAndPlay(track, openSheet = false, onError = onError)
            return
        }
        beginLoadAndPlay(track, openSheet = openSheet, onError = onError)
    }

    private fun beginLoadAndPlay(
        track: MarkdownAudioTrack,
        openSheet: Boolean,
        onError: (String) -> Unit,
    ) {
        loadJob?.cancel()
        cancelBufferingGrace()
        stopTicker()
        loadedTrackKey = null
        player?.pause()
        val speed = _state.value.playbackSpeed
        _state.value = MarkdownAudioPlaybackState(
            track = track,
            isBuffering = track.source !is MarkdownAudioSource.Conch,
            isDownloading = track.source is MarkdownAudioSource.Conch,
            playbackSpeed = speed,
            sheetExpanded = openSheet,
        )
        loadJob = scope.launch(Dispatchers.Main.immediate) {
            val resolved = resolvePlayableUri(track)
            if (resolved == null) {
                val message = resolutionErrorMessage(track)
                if (_state.value.track?.key == track.key) {
                    resetStateKeepingSpeed()
                }
                onError(message)
                return@launch
            }
            val (uri, file, errorMessage) = resolved
            if (uri == null) {
                if (_state.value.track?.key == track.key) {
                    resetStateKeepingSpeed()
                }
                onError(errorMessage ?: context.getString(R.string.markdown_link_file_unavailable))
                return@launch
            }
            if (_state.value.track?.key != track.key) return@launch
            val activePlayer = ensurePlayer()
            loadedTrackKey = track.key
            _state.update {
                it.copy(
                    resolvedFile = file,
                    isDownloading = false,
                    isBuffering = true,
                    positionMs = 0L,
                    durationMs = 0L,
                )
            }
            suppressIdleState = true
            try {
                activePlayer.setMediaItem(MediaItem.fromUri(uri))
            } finally {
                suppressIdleState = false
            }
            activePlayer.setPlaybackSpeed(_state.value.playbackSpeed)
            activePlayer.prepare()
            activePlayer.playWhenReady = true
        }
    }

    private data class ResolvedAudio(
        val uri: Uri?,
        val file: File?,
        val errorMessage: String? = null,
    )

    private suspend fun resolvePlayableUri(track: MarkdownAudioTrack): ResolvedAudio? = try {
        when (val source = track.source) {
            is MarkdownAudioSource.Remote -> ResolvedAudio(Uri.parse(source.url), null)
            is MarkdownAudioSource.Local -> {
                val resolution = withContext(Dispatchers.IO) {
                    resolveLocalFileTarget(context, source.file)
                }
                when (resolution) {
                    is LocalFileResolution.Ready -> ResolvedAudio(Uri.fromFile(resolution.file), resolution.file)
                    LocalFileResolution.NotFound -> ResolvedAudio(
                        null,
                        null,
                        context.getString(R.string.markdown_link_file_missing),
                    )
                    LocalFileResolution.NeedsSharedStorageAccess -> ResolvedAudio(
                        null,
                        null,
                        context.getString(R.string.markdown_link_shared_storage_required),
                    )
                    LocalFileResolution.Unavailable -> ResolvedAudio(
                        null,
                        null,
                        context.getString(R.string.markdown_link_file_unavailable),
                    )
                }
            }
            is MarkdownAudioSource.Conch -> {
                val file = audioLoader.fetchFile(track.link)
                ResolvedAudio(Uri.fromFile(file), file)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        ResolvedAudio(
            null,
            null,
            failure.message?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.audio_playback_failed),
        )
    }

    private fun resolutionErrorMessage(track: MarkdownAudioTrack): String =
        context.getString(R.string.markdown_link_file_unavailable)

    fun togglePlayPause() {
        val active = player ?: return
        if (active.isPlaying) {
            active.pause()
        } else {
            if (active.playbackState == Player.STATE_ENDED) {
                active.seekTo(0L)
            }
            active.play()
        }
    }

    fun pause() {
        player?.pause()
    }

    fun seekTo(positionMs: Long) {
        val duration = _state.value.durationMs
        val clamped = if (duration > 0L) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        _state.update { it.copy(positionMs = clamped) }
        player?.seekTo(clamped)
    }

    fun seekBy(deltaMs: Long) {
        val current = player?.currentPosition?.coerceAtLeast(0L) ?: _state.value.positionMs
        seekTo(current + deltaMs)
    }

    fun cyclePlaybackSpeed() {
        val current = _state.value.playbackSpeed
        val index = PLAYBACK_SPEED_STEPS.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
        val next = PLAYBACK_SPEED_STEPS[(index + 1) % PLAYBACK_SPEED_STEPS.size]
        _state.update { it.copy(playbackSpeed = next) }
        player?.setPlaybackSpeed(next)
    }

    fun openSheet() {
        if (_state.value.track != null) {
            _state.update { it.copy(sheetExpanded = true) }
        }
    }

    fun dismissSheet() {
        _state.update { it.copy(sheetExpanded = false) }
    }

    fun stopAndClose() {
        loadJob?.cancel()
        loadJob = null
        cancelBufferingGrace()
        stopTicker()
        releasePlayerInternal()
        resetStateKeepingSpeed()
    }

    fun release() {
        loadJob?.cancel()
        loadJob = null
        cancelBufferingGrace()
        stopTicker()
        releasePlayerInternal()
        _state.value = MarkdownAudioPlaybackState()
    }

    private fun resetStateKeepingSpeed() {
        val speed = _state.value.playbackSpeed
        _state.value = MarkdownAudioPlaybackState(playbackSpeed = speed)
    }

    private fun ensurePlayer(): Player {
        player?.let { return it }
        return createPlayer(context.applicationContext).also { created ->
            created.addListener(listener)
            player = created
        }
    }

    private fun releasePlayerInternal() {
        val existing = player ?: return
        player = null
        loadedTrackKey = null
        existing.removeListener(listener)
        existing.release()
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                val active = player ?: break
                val pos = active.currentPosition.coerceAtLeast(0L)
                val dur = active.duration
                _state.update { current ->
                    current.copy(
                        positionMs = pos,
                        durationMs = if (dur > 0L) dur else current.durationMs,
                    )
                }
                delay(200)
            }
        }
    }

    private fun scheduleBufferingGrace() {
        if (bufferingGraceJob?.isActive == true) return
        val trackKey = loadedTrackKey ?: return
        bufferingGraceJob = scope.launch(Dispatchers.Main.immediate) {
            delay(SEEK_BUFFERING_GRACE_MS)
            if (loadedTrackKey == trackKey && player?.playbackState == Player.STATE_BUFFERING) {
                _state.update { it.copy(isBuffering = true, isPlaying = false) }
            }
        }
    }

    private fun cancelBufferingGrace() {
        bufferingGraceJob?.cancel()
        bufferingGraceJob = null
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    companion object {
        internal val PLAYBACK_SPEED_STEPS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        internal const val SEEK_BUFFERING_GRACE_MS = 200L
    }
}

private fun defaultMarkdownAudioPlayer(context: Context): Player =
    ExoPlayer.Builder(context)
        .setHandleAudioBecomingNoisy(true)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )
        .build()

internal fun buildMarkdownAudioTrack(link: String, title: String? = null): MarkdownAudioTrack? {
    val source = markdownAudioSource(link) ?: return null
    val key = resolveMarkdownDestination(link)?.identity() ?: link.trim().removeSurrounding("<", ">")
    val cleanTitle = title?.trim().orEmpty()
    val displayName = if (cleanTitle.isNotEmpty()) {
        cleanTitle
    } else {
        val rawName = when (source) {
            is MarkdownAudioSource.Remote -> source.url.substringBefore('?').substringBefore('#').substringAfterLast('/')
            is MarkdownAudioSource.Local -> source.file.relativePath.substringAfterLast('/')
            is MarkdownAudioSource.Conch -> source.path.substringAfterLast('/')
        }
        (percentDecodePath(rawName) ?: rawName).ifBlank { link }
    }
    val extensionBadge = link.trim().removeSurrounding("<", ">")
        .substringBefore('?').substringBefore('#')
        .substringAfterLast('.', "")
        .uppercase()
        .take(5)
    return MarkdownAudioTrack(
        key = key,
        link = link,
        title = displayName,
        extensionBadge = extensionBadge,
        source = source,
    )
}

internal fun formatPlaybackSpeed(speed: Float): String = when {
    kotlin.math.abs(speed - 1.0f) < 0.01f -> "1×"
    kotlin.math.abs(speed - 2.0f) < 0.01f -> "2×"
    kotlin.math.abs(speed - 1.5f) < 0.01f -> "1.5×"
    else -> "${speed}×"
}
