package com.newoether.agora.model

data class RunMessagePresentation(
    val showActions: Boolean = false,
    val copyText: String? = null,
    val deleteTargetMessageId: String? = null,
    val showBranchSelector: Boolean = false,
    val branchIndex: Int = 0,
    val totalBranches: Int = 1,
    val branchAnchorParentId: String? = null,
    val branchAnchorMessageId: String? = null,
)

/**
 * Derives message-generation UI affordances from the selected message path.
 *
 * Edit and Regenerate are two independent structural branch dimensions:
 *
 *  - edited USER messages are siblings under the preceding message, so their selector belongs
 *    only to the generation's input;
 *  - regenerated MODEL roots are siblings under one shared USER input, so their selector belongs
 *    only to the selected generation's terminal output.
 *
 * Each real USER and each durable Assistant Run boundary starts a generation. The USER and the
 * last ordinary assistant in each boundary expose actions. Compact and synthetic tool/result rows never expose their own bars.
 */
object RunUiProjection {
    fun project(
        visibleMessages: List<ChatMessage>,
        allMessages: List<ChatMessage>,
    ): Map<String, RunMessagePresentation> {
        if (visibleMessages.isEmpty()) return emptyMap()

        // ID is the structural identity. A transient Room/optimistic-commit race must never be
        // interpreted as two real branches even if a caller accidentally supplies duplicates.
        val uniqueAllMessages = allMessages.distinctBy { it.id }
        val uniqueVisibleMessages = visibleMessages.distinctBy { it.id }
        val boundaryInputs = uniqueAllMessages.filter(MessageGenerationBoundaryResolver::isRealUser)
        val editSiblingsByParent = boundaryInputs
            .groupBy { it.parentId }
            .mapValues { (_, messages) -> messages.sortedWith(branchOrder) }
        val rootOutputs = uniqueAllMessages.filter { message ->
            MessageGenerationBoundaryResolver.isOrdinaryAssistant(message) &&
                boundaryInputs.any { input -> message.parentId == input.id }
        }
        val regenerationSiblingsByParent = rootOutputs
            .groupBy { it.parentId }
            .mapValues { (_, messages) -> messages.sortedWith(branchOrder) }

        val result = uniqueVisibleMessages
            .associate { it.id to RunMessagePresentation() }
            .toMutableMap()
        uniqueVisibleMessages
            .filter(MessageGenerationBoundaryResolver::isRealUser)
            .forEach { userBoundary ->
                val siblings = editSiblingsByParent[userBoundary.parentId].orEmpty()
                result[userBoundary.id] = RunMessagePresentation(
                    showActions = true,
                    copyText = userBoundary.text.takeIf { it.isNotBlank() },
                    deleteTargetMessageId = userBoundary.id,
                    showBranchSelector = siblings.size > 1,
                    branchIndex = siblings.indexOfFirst { it.id == userBoundary.id }.coerceAtLeast(0),
                    totalBranches = siblings.size.coerceAtLeast(1),
                    branchAnchorParentId = userBoundary.parentId,
                    branchAnchorMessageId = userBoundary.id,
                )
            }

        MessageGenerationBoundaryResolver.resolve(uniqueVisibleMessages).forEach { boundary ->
            val outputBoundary = boundary.lastAssistant ?: return@forEach
            val rootOutput = boundary.firstAssistant ?: outputBoundary
            val structuralInput = boundary.input
                ?.takeIf { rootOutput.parentId == it.id }
                ?: uniqueAllMessages.firstOrNull { candidate ->
                    candidate.id == rootOutput.parentId &&
                        MessageGenerationBoundaryResolver.isRealUser(candidate)
                }
            val siblings = structuralInput
                ?.let { regenerationSiblingsByParent[it.id] }
                .orEmpty()
            result[outputBoundary.id] = RunMessagePresentation(
                showActions = true,
                copyText = outputBoundary.text.takeIf { it.isNotBlank() },
                deleteTargetMessageId = rootOutput.id,
                showBranchSelector = siblings.size > 1,
                branchIndex = siblings.indexOfFirst { it.id == rootOutput.id }.coerceAtLeast(0),
                totalBranches = siblings.size.coerceAtLeast(1),
                branchAnchorParentId = rootOutput.parentId,
                branchAnchorMessageId = rootOutput.id,
            )
        }
        return result
    }

    private val branchOrder =
        compareBy<ChatMessage> { it.timestamp }
            .thenBy { it.id }
}
