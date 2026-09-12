package com.newoether.agora.data

import com.newoether.agora.automation.TaskManager
import com.newoether.agora.automation.LoopManager
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.HeartbeatManager.Companion.DEFAULT_HEARTBEAT_PROMPT

/**
 * Builds the prompt sent to the model during a heartbeat check.
 *
 * The prompt includes sections for:
 * 1. Pending Tasks/Loops
 * 2. Pending automation work
 * 3. Memory promotion candidates
 * 4. New SMS (snapshot passed in — the caller removes it from the queue only after success)
 * 5. New notifications (snapshot passed in — same after-success consumption)
 * 6. Custom prompt (user-defined)
 *
 * Pending SMS/notification lists are parameters, not store lookups, so this builder
 * stays pure and unit-testable; the scheduler owns the snapshot/remove lifecycle.
 */
class HeartbeatPromptBuilder(
    private val taskManager: TaskManager,
    private val loopManager: LoopManager,
    private val conversationRepository: ConversationRepository,
    private val memoryManager: MemoryManager,
    private val taskRepository: com.newoether.agora.data.repository.TaskRepository,
) {

    /**
     * Builds the complete heartbeat prompt by assembling all sections.
     */
    suspend fun buildHeartbeatPrompt(
        customPrompt: String,
        pendingSms: List<SmsMessageData> = emptyList(),
        pendingNotifications: List<NotificationRecord> = emptyList(),
        recentResponses: List<String> = emptyList(),
    ): String {
        val sections = mutableListOf<String>()

        // Base prompt: custom instructions or default heartbeat prompt
        if (customPrompt.isNotBlank()) {
            sections.add("## Custom Instructions\n$customPrompt")
        } else {
            sections.add(DEFAULT_HEARTBEAT_PROMPT)
        }

        // Section 1: Tasks/Loops
        val tasksSection = buildTasksSection()
        if (tasksSection.isNotBlank()) sections.add(tasksSection)

        // Section 2: Pending automations
        val pendingSection = buildPendingAutomationsSection()
        if (pendingSection.isNotBlank()) sections.add(pendingSection)

        // Section 3: Promotion candidates
        val promotionSection = buildPromotionCandidatesSection()
        if (promotionSection.isNotBlank()) sections.add(promotionSection)

        // Section 4: New SMS
        val smsSection = buildSmsSection(pendingSms)
        if (smsSection.isNotBlank()) sections.add(smsSection)

        // Section 5: New Notifications
        val notificationsSection = buildNotificationsSection(pendingNotifications)
        if (notificationsSection.isNotBlank()) sections.add(notificationsSection)

        // Section 6: Previous Heartbeat Results (for continuity)
        val previousSection = buildPreviousHeartbeatSection(recentResponses)
        if (previousSection.isNotBlank()) sections.add(previousSection)

        return sections.joinToString("\n\n")
    }

    private suspend fun buildTasksSection(): String {
        val tasks = taskManager.tasks.value
        if (tasks.isEmpty()) return ""

        val activeTasks = tasks.filter { it.enabled }
        val dueTasks = activeTasks.filter { it.nextRunAt <= System.currentTimeMillis() }

        // For loops, we need to query LoopManager differently
        val loops = getActiveLoops()

        val lines = mutableListOf("## Pending Tasks & Loops")
        if (dueTasks.isNotEmpty()) {
            lines.add("### Due Tasks:")
            for (task in dueTasks.take(10)) {
                lines.add("- ${task.name}: ${task.prompt.take(100)}...")
            }
        }
        if (loops.isNotEmpty()) {
            lines.add("### Active Loops:")
            for (loop in loops.take(5)) {
                lines.add("- ${loop.conversationId}: every ${loop.intervalMs / 60000} min")
            }
        }
        return lines.joinToString("\n")
    }

    private suspend fun getActiveLoops(): List<com.newoether.agora.data.local.LoopEntity> {
        return taskRepository.getActiveLoops()
    }

    private suspend fun buildPendingAutomationsSection(): String {
        // In a full implementation, this would check for pending automation work
        // For now, return empty string as placeholder
        return ""
    }

    private suspend fun buildPromotionCandidatesSection(): String {
        val candidates = memoryManager.getPromotionCandidates()
        if (candidates.isEmpty()) return ""
        val lines = mutableListOf("## Memory Promotion Candidates")
        lines.add("The following memory files have been accessed frequently. Consider if any of this information should be promoted. Use the `pin_memory_file` tool to pin these memories so they are no longer suggested here.")
        for (c in candidates) {
            val descriptionText = if (c.description.isNotBlank()) ": ${c.description}" else ""
            lines.add("- **${c.name}**$descriptionText")
        }
        return lines.joinToString("\n")
    }

    private fun buildSmsSection(pendingSms: List<SmsMessageData>): String {
        if (pendingSms.isEmpty()) return ""

        val lines = mutableListOf("## New SMS")
        lines.add("These SMS arrived since the last heartbeat. Summarise briefly; only flag items that genuinely need attention.")
        for (msg in pendingSms.take(20)) {
            val sender = msg.address.ifBlank { "(unknown sender)" }
            val preview = msg.preview.ifBlank { "" }
            lines.add("- **$sender** (id: ${msg.id})${if (preview.isNotBlank()) ": $preview" else ""}")
        }
        return lines.joinToString("\n")
    }

    private fun buildNotificationsSection(pendingNotifications: List<NotificationRecord>): String {
        if (pendingNotifications.isEmpty()) return ""

        val lines = mutableListOf("## New Notifications")
        lines.add("These notifications arrived since the last heartbeat. Summarise briefly; only flag items that genuinely need attention.")
        
        val sortedNotifications = pendingNotifications.sortedByDescending { it.postedAt }.take(20)
        for (record in sortedNotifications) {
            val titleText = if (record.title.isNotBlank()) ": ${record.title}" else ""
            lines.add("- **${record.appLabel}**$titleText (id: ${record.id}): ${record.preview}")
        }
        return lines.joinToString("\n")
    }

    private fun buildPreviousHeartbeatSection(recentResponses: List<String>): String {
        if (recentResponses.isEmpty()) return ""
        val lines = mutableListOf("## Previous Heartbeat Results", "For context, here are your most recent heartbeat summaries:")
        for ((i, response) in recentResponses.withIndex()) {
            val label = if (i == 0) "Most recent" else "${i + 1} heartbeats ago"
            lines.add("### $label\n$response")
        }
        return lines.joinToString("\n")
    }
}