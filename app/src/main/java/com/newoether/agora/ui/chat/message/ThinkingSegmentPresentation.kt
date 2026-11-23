package com.newoether.agora.ui.chat.message

import android.content.res.Resources
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal const val THINKING_COLLAPSED_WIDTH_ALLOWANCE_DP = 6
internal const val AUXILIARY_CARD_START_EXTENSION_DP = 4

internal fun Resources.segmentDetailTitle(
    seg: MessageSegment,
    detailSegments: List<MessageSegment>,
    detailIndex: Int,
): String = when (seg.type) {
    "tool" -> toolDisplayName(seg)
    "transcription" -> transcriptionLabel(detailSegments, detailIndex)
    else -> getString(R.string.tool_thinking)
}

@Composable
internal fun segmentDetailTitle(
    seg: MessageSegment,
    detailSegments: List<MessageSegment>,
    detailIndex: Int,
): String = currentResources().segmentDetailTitle(seg, detailSegments, detailIndex)

/** Live ("Thinking for …") or finished ("Thought for …") duration title. */
internal fun Resources.thinkingDurationBreakdownTitle(
    seconds: Int,
    live: Boolean,
    toolCount: Int? = null,
): String {
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val remainingSeconds = seconds % 60
    return when {
        live && hours > 0 -> getString(
            R.string.thinking_for_hours_ellipsis,
            hours,
            minutes,
            remainingSeconds,
        )
        live && seconds >= 60 -> getString(
            R.string.thinking_for_minutes_ellipsis,
            minutes,
            remainingSeconds,
        )
        live -> getString(R.string.thinking_for_seconds_ellipsis, seconds)
        toolCount != null && hours > 0 -> getString(
            R.string.thought_for_hours_called_tools,
            hours,
            minutes,
            remainingSeconds,
            toolCount,
        )
        toolCount != null && seconds >= 60 -> getString(
            R.string.thought_for_minutes_called_tools,
            minutes,
            remainingSeconds,
            toolCount,
        )
        toolCount != null -> getString(
            R.string.thought_for_seconds_called_tools,
            seconds,
            toolCount,
        )
        hours > 0 -> getString(
            R.string.thought_for_hours,
            hours,
            minutes,
            remainingSeconds,
        )
        seconds >= 60 -> getString(
            R.string.thought_for_minutes,
            minutes,
            remainingSeconds,
        )
        else -> getString(R.string.thought_for_seconds, seconds)
    }
}

internal fun Resources.thoughtDurationTitle(thoughtMs: Long?, toolCount: Int): String {
    if (thoughtMs == null) return if (toolCount > 0) {
        getString(R.string.thought_for_a_while_called_tools, toolCount)
    } else getString(R.string.thought_for_a_while)
    return thinkingDurationBreakdownTitle(
        seconds = (thoughtMs / 1_000L).toInt(),
        live = false,
        toolCount = toolCount.takeIf { it > 0 },
    )
}

internal fun Resources.compactSegmentTitle(
    segs: List<MessageSegment>,
    message: ChatMessage,
    useLiveStatus: Boolean,
): String {
    val lastSeg = segs.lastOrNull() ?: return ""
    val isLastTool = lastSeg.type == "tool"
    val isToolInProgress = useLiveStatus && isLastTool && ToolPresentationResolver.resolve(lastSeg).isActive
    val isThinking = useLiveStatus && message.status == MessageStatus.THINKING
    val isToolCalling = useLiveStatus && message.status == MessageStatus.TOOL_CALLING
    val isTranscribing = useLiveStatus && message.status == MessageStatus.TRANSCRIBING
    val toolCount = segs.count { it.type == "tool" }
    val thoughtMs = thoughtDurationMs(segs, fallbackMs = message.thoughtTimeMs)
    val hasThought = segs.any { it.type == "thought" }
    return when {
        isThinking -> message.thoughtTitle ?: getString(R.string.thinking_ellipsis)
        isTranscribing -> message.thoughtTitle ?: getString(R.string.transcription_ellipsis)
        isToolCalling || isToolInProgress ->
            if (segs.any { it.type == "transcription" }) {
                // Tool-result image transcription streams while the message is TOOL_CALLING;
                // the group title must stay the transcription label, not the tool name
                // (the transcription segment would otherwise fall back to the generic "Tool").
                transcriptionLabel(
                    segs,
                    segs.indexOfLast { it.type == "transcription" },
                )
            } else {
                toolDisplayName(lastSeg)
            }
        hasThought -> thoughtDurationTitle(thoughtMs, toolCount)
        toolCount > 0 -> getString(R.string.called_n_tools, toolCount)
        segs.any { it.type == "transcription" } -> transcriptionLabel(
            segs,
            segs.indexOfLast { it.type == "transcription" },
        )
        else -> ""
    }
}

@Composable
internal fun compactSegmentTitle(
    segs: List<MessageSegment>,
    message: ChatMessage,
    useLiveStatus: Boolean,
): String = currentResources().compactSegmentTitle(segs, message, useLiveStatus)

/** True while this group's thought is still streaming; only then may a live timer run. */
internal fun isLiveThinkingGroup(
    segs: List<MessageSegment>,
    message: ChatMessage,
    useLiveStatus: Boolean,
): Boolean = useLiveStatus &&
    message.status == MessageStatus.THINKING &&
    segs.any { it.type == "thought" }

/** A provider-supplied thought title replaces the live timer; the placeholder does not. */
internal fun Resources.usesDefaultThinkingTitle(message: ChatMessage): Boolean =
    message.thoughtTitle.isNullOrBlank() ||
        message.thoughtTitle == getString(R.string.thinking_ellipsis)

/**
 * A collapsed group title. When [liveBaseMs] is set the title is a running timer: it counts up
 * from [liveBaseMs] and is rendered with [thinkingDurationBreakdownTitle] (live = true).
 * [liveSampledAtElapsedRealtimeMs] is the monotonic instant that base was sampled at, so the
 * timer can resume from the generation clock instead of the composition that renders it.
 */
internal data class CompactSegmentTitleState(
    val title: String,
    val liveBaseMs: Long?,
    val liveSampledAtElapsedRealtimeMs: Long? = null,
)

internal fun Resources.compactSegmentTitleState(
    segs: List<MessageSegment>,
    message: ChatMessage,
    useLiveStatus: Boolean,
): CompactSegmentTitleState {
    val thoughtMs = thoughtDurationMs(segs, fallbackMs = message.thoughtTimeMs)
    // Unknown native duration is not zero and does not authorize a local elapsed-time claim.
    val liveBaseMs = thoughtMs?.takeIf {
        isLiveThinkingGroup(segs, message, useLiveStatus) && usesDefaultThinkingTitle(message)
    }
    return if (liveBaseMs != null) {
        CompactSegmentTitleState(
            title = thinkingDurationBreakdownTitle((liveBaseMs / 1_000L).toInt(), live = true),
            liveBaseMs = liveBaseMs,
            liveSampledAtElapsedRealtimeMs = segs
                .lastOrNull { it.type == "thought" }
                ?.durationSampledAtElapsedRealtimeMs,
        )
    } else {
        CompactSegmentTitleState(compactSegmentTitle(segs, message, useLiveStatus), null)
    }
}

/**
 * Live thought time for a snapshot whose base duration was sampled at
 * [sampledAtElapsedRealtimeMs]. Clamping keeps a stale anchor (for example a restored row from
 * before a reboot) from subtracting from the settled duration.
 */
internal fun liveThoughtDurationMs(
    baseMs: Long,
    sampledAtElapsedRealtimeMs: Long,
    nowElapsedRealtimeMs: Long,
): Long = baseMs + (nowElapsedRealtimeMs - sampledAtElapsedRealtimeMs).coerceAtLeast(0L)

@Composable
internal fun compactSegmentDisplayTitle(
    segs: List<MessageSegment>,
    message: ChatMessage,
    useLiveStatus: Boolean,
): String {
    val resources = currentResources()
    val state = resources.compactSegmentTitleState(segs, message, useLiveStatus)
    val baselineMs = state.liveBaseMs
    val sampledAtMs = state.liveSampledAtElapsedRealtimeMs
    val liveThoughtMs by produceState(initialValue = baselineMs ?: 0L, baselineMs, sampledAtMs) {
        value = baselineMs ?: 0L
        if (baselineMs != null) {
            // Snapshots from before the anchor existed fall back to counting from this composition.
            val anchorMs = sampledAtMs ?: SystemClock.elapsedRealtime()
            while (isActive) {
                value = liveThoughtDurationMs(baselineMs, anchorMs, SystemClock.elapsedRealtime())
                delay(1_000L)
            }
        }
    }
    return if (baselineMs != null) {
        resources.thinkingDurationBreakdownTitle(
            seconds = (liveThoughtMs / 1_000L).toInt(),
            live = true,
        )
    } else {
        state.title
    }
}