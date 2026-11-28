package com.newoether.agora.viewmodel

import com.newoether.agora.api.util.projectedImageOccurrences
import com.newoether.agora.api.util.withProjectedImages
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.phone.PHONE_MAX_SCREENSHOTS_UNLIMITED
import com.newoether.agora.phone.PHONE_TOOL_NAMES
import com.newoether.agora.phone.normalizePhoneMaxScreenshots
import com.newoether.agora.util.Constants

/** Request-only Phone image budget. Durable messages and every non-Phone image stay untouched. */
internal fun projectPhoneToolImages(
    messages: List<ChatMessage>,
    maxScreenshots: Int,
): List<ChatMessage> {
    var remaining = normalizePhoneMaxScreenshots(maxScreenshots)
    // Skip only count filtering; the shared provider projection still handles pairing and image gates.
    if (remaining == PHONE_MAX_SCREENSHOTS_UNLIMITED) return messages
    if (messages.none { message ->
        message.toolCall?.toolName in PHONE_TOOL_NAMES || message.segments.orEmpty().any {
            it.type == "tool" && it.toolName in PHONE_TOOL_NAMES
        }
    }) return messages
    val requestGroups = mutableMapOf<String, List<PhoneImageGroup>>()
    val matchedResults = mutableMapOf<String, MutableSet<PhoneImageCall>>()
    var latestRequestId: String? = null
    val projections = messages.map { message ->
        val isRequest = message.id.startsWith(Constants.TOOL_MSG_PREFIX)
        val isResult = message.id.startsWith(Constants.RESULT_MSG_PREFIX)
        if (isRequest) latestRequestId = message.id
        val roundId = if (isResult) message.parentId ?: latestRequestId ?: message.id else message.id
        val segments = message.segments.orEmpty()
        val legacy = message.toolCall
        val legacySegment = legacy?.let { call ->
            segments.indexOfFirst { segment ->
                segment.type == "tool" && segment.toolName == call.toolName &&
                    if (call.toolCallId != null) segment.toolCallId == call.toolCallId
                    else (segment.toolArgs ?: "{}") == call.arguments &&
                        segment.toolResult.orEmpty() == call.result && segment.signature == call.signature
            }.takeIf { it >= 0 }
        }
        val groups = segments.mapIndexedNotNull { index, segment ->
            if (segment.type != "tool") return@mapIndexedNotNull null
            PhoneImageGroup(
                call = PhoneImageCall(
                    roundId = segment.toolRoundId ?: roundId,
                    name = segment.toolName.orEmpty(),
                    arguments = segment.toolArgs ?: "{}",
                    id = segment.toolCallId,
                    ordinal = if (segment.toolCallId == null) index else 0,
                ),
                segmentIndex = index,
                legacy = legacySegment == index,
                images = segment.toolImages.ifEmpty {
                    if (legacySegment == index) legacy?.resultImages.orEmpty() else emptyList()
                },
            )
        }.toMutableList()
        if (legacy != null && legacySegment == null) {
            groups += PhoneImageGroup(
                call = PhoneImageCall(roundId, legacy.toolName, legacy.arguments, legacy.toolCallId,
                    if (legacy.toolCallId == null) groups.size else 0),
                segmentIndex = null,
                legacy = true,
                images = legacy.resultImages,
            )
        }
        if (isResult) {
            val claimed = matchedResults.getOrPut(roundId) { mutableSetOf() }
            groups.indices.forEach { index ->
                val group = groups[index]
                val request = requestGroups[roundId].orEmpty().firstOrNull { candidate ->
                    candidate.call !in claimed && candidate.call.name == group.call.name &&
                        if (group.call.id != null) candidate.call.id == group.call.id
                        else candidate.call.arguments == group.call.arguments
                }
                if (request != null) {
                    groups[index] = group.copy(call = request.call)
                    claimed += request.call
                }
            }
        }
        val occurrences = if (isResult) message.projectedImageOccurrences() else emptyList()
        // Old single-tool result rows may have only the top-level image paths.
        if (isResult && groups.size == 1 && groups.single().images.isEmpty()) {
            groups[0] = groups.single().copy(images = occurrences.map { occurrence ->
                occurrence.artifact ?: ImageArtifact(occurrence.path, "application/octet-stream", 0L)
            })
        }
        val topSlots = MutableList<Pair<Int, Int>?>(message.images.size) { null }
        if (isResult) {
            val ordered = groups.flatMapIndexed { groupIndex, group ->
                group.images.mapIndexed { imageIndex, image -> Triple(groupIndex, imageIndex, image) }
            }
            if (ordered.map { it.third.path } == occurrences.map { it.path }) {
                ordered.forEachIndexed { index, slot -> topSlots[index] = slot.first to slot.second }
            } else {
                // Claim occurrences one at a time, including non-Phone slots. A path set would
                // erase unrelated images when two tools return the same path.
                ordered.forEach { (groupIndex, imageIndex, image) ->
                    val index = occurrences.indices.firstOrNull { candidate ->
                        val occurrence = occurrences[candidate]
                        topSlots[candidate] == null && occurrence.path == image.path &&
                            (occurrence.artifact == null || occurrence.artifact == image)
                    }
                    if (index != null) topSlots[index] = groupIndex to imageIndex
                }
            }
        }
        if (isRequest) requestGroups[message.id] = groups
        PhoneImageMessage(message, groups, topSlots)
    }
    val seen = mutableSetOf<Pair<PhoneImageCall, Int>>()
    val kept = mutableSetOf<Pair<PhoneImageCall, Int>>()
    projections.asReversed().forEach { projection ->
        projection.groups.asReversed().filter { it.isPhone }.forEach { group ->
            group.images.indices.reversed().forEach { index ->
                val occurrence = group.call to index
                if (seen.add(occurrence) && remaining > 0) {
                    kept += occurrence
                    remaining--
                }
            }
        }
    }
    if (kept.size == seen.size) return messages
    return projections.map { projection ->
        val message = projection.message
        if (projection.groups.none { it.isPhone }) return@map message
        var segments = message.segments
        val legacyCall = message.toolCall
        var legacy = legacyCall
        projection.groups.filter { it.isPhone }.forEach { group ->
            group.segmentIndex?.let { index ->
                val original = message.segments!![index]
                val images = original.toolImages.filterIndexed { imageIndex, _ ->
                    (group.call to imageIndex) in kept
                }
                if (images != original.toolImages) {
                    segments = segments!!.toMutableList().apply {
                        this[index] = original.copy(toolImages = images)
                    }
                }
            }
            if (group.legacy && legacyCall != null) {
                val images = legacyCall.resultImages.filterIndexed { index, _ ->
                    (group.call to index) in kept
                }
                if (images != legacyCall.resultImages) legacy = legacyCall.copy(resultImages = images)
            }
        }
        val occurrences = message.projectedImageOccurrences()
        val retained = occurrences.filterIndexed { index, _ ->
            val slot = projection.topSlots[index] ?: return@filterIndexed true
            val group = projection.groups[slot.first]
            !group.isPhone || (group.call to slot.second) in kept
        }
        // Capture occurrence descriptors before removing mirrors, so a same-path sibling cannot
        // acquire the dropped Phone image's metadata through the lossy path map.
        val projected = if (retained.size == occurrences.size) message
            else message.withProjectedImages(retained)
        if (segments == message.segments && legacy == message.toolCall && projected === message) message
        else projected.copy(segments = segments, toolCall = legacy)
    }
}

private data class PhoneImageCall(
    val roundId: String,
    val name: String,
    val arguments: String,
    val id: String?,
    val ordinal: Int,
)

private data class PhoneImageGroup(
    val call: PhoneImageCall,
    val segmentIndex: Int?,
    val legacy: Boolean,
    val images: List<ImageArtifact>,
) {
    val isPhone: Boolean get() = call.name in PHONE_TOOL_NAMES
}

private data class PhoneImageMessage(
    val message: ChatMessage,
    val groups: List<PhoneImageGroup>,
    val topSlots: List<Pair<Int, Int>?>,
)
