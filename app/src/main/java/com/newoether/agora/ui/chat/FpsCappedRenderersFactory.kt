package com.newoether.agora.ui.chat

import android.content.Context
import android.os.Handler
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Renderers factory that caps a chat-background video to [maxFps] presented frames per second.
 * Decoding continues at the source rate, but frames are only presented at the cap — surface
 * composition is the dominant power cost of a looping background, so this cuts GPU/compositor
 * work without stalling the pipeline. Ported from AIOPE's FpsCappedRenderersFactory.
 */
internal class FpsCappedRenderersFactory(
    context: Context,
    private val maxFps: Float,
) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        out.add(
            FpsCappedVideoRenderer(
                builder = MediaCodecVideoRenderer.Builder(context)
                    .setMediaCodecSelector(mediaCodecSelector)
                    .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                    .setEnableDecoderFallback(enableDecoderFallback)
                    .setEventHandler(eventHandler)
                    .setEventListener(eventListener)
                    .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY),
                maxFps = maxFps,
            ),
        )
    }
}

/** [MediaCodecVideoRenderer] that presents frames at no more than [maxFps]. */
internal class FpsCappedVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    maxFps: Float,
) : MediaCodecVideoRenderer(builder) {

    private val minFrameIntervalUs: Long = (1_000_000L / maxFps).toLong()
    private var lastPresentedElapsedUs: Long = Long.MIN_VALUE

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        if (lastPresentedElapsedUs == Long.MIN_VALUE ||
            elapsedRealtimeUs - lastPresentedElapsedUs >= minFrameIntervalUs
        ) {
            lastPresentedElapsedUs = elapsedRealtimeUs
            super.render(positionUs, elapsedRealtimeUs)
        }
    }
}
