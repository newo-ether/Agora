package com.newoether.agora.ui.chat.message

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.newoether.agora.R
import com.newoether.agora.model.MessageSegment

/**
 * The only localization layer for tool cards. Parsing and lifecycle inference live in
 * [ToolPresentationResolver]; compact, timeline and detail surfaces all call these functions.
 *
 * Each label is a [Resources] function so the WebUI can build the same text off the UI; the
 * Composable overloads below read the current configuration's resources.
 */
@Composable
internal fun currentResources(): Resources {
    // Read like stringResource does, so a configuration change recomposes the caller.
    LocalConfiguration.current
    return LocalContext.current.resources
}

@Composable
internal fun toolDisplayName(segment: MessageSegment): String =
    currentResources().toolDisplayName(segment)

@Composable
internal fun toolSummary(segment: MessageSegment): String = currentResources().toolSummary(segment)

@Composable
internal fun toolSummary(presentation: ToolPresentation): String =
    currentResources().toolSummary(presentation)

@Composable
internal fun shellToolSummary(presentation: ToolPresentation): String =
    currentResources().shellToolSummary(presentation)

@Composable
internal fun shellExecutionSummary(presentation: ToolPresentation): String =
    currentResources().shellExecutionSummary(presentation)

internal fun Resources.toolDisplayName(segment: MessageSegment): String {
    val toolName = segment.toolName.orEmpty()
    if (toolName == "render_interactive_answer") return getString(R.string.interactive_answers)
    val kind = ToolPresentationResolver.kindForToolName(toolName)
    if (kind == ToolKind.MCP) {
        return mcpToolDisplayName(
            publicName = toolName,
            resolvedName = segment.toolDisplayName,
        ) ?: "MCP"
    }
    // A malformed-call stand-in keeps the tool the model actually named. Label the card with that
    // name using the same localized title, while the resolver keeps UNKNOWN so the summary stays
    // the plain failure reason instead of inventing a shape for a call that never ran.
    val sentName = ToolPresentationResolver
        .malformedCallOriginals(toolName, segment.toolArgs)
        ?.name
    return toolBaseDisplayName(
        kind = sentName?.let(ToolPresentationResolver::kindForToolName)
            ?.takeUnless { it == ToolKind.MCP } ?: kind,
        toolName = sentName?.takeIf { it.isNotBlank() } ?: toolName,
    )
}

private val MCP_PUBLIC_TOOL_NAME =
    Regex("""^mcp_[A-Za-z0-9]+_(.+)_[0-9a-fA-F]{6}$""")
private val TOOL_NAME_SEPARATOR = Regex("""[\s._-]+""")
private val TOOL_PATH_INITIAL = Regex("""(?<=/)\p{Ll}""")

/**
 * New calls use [resolvedName]. The public-name decoder is intentionally fallback-only for
 * history written before provider presentation metadata was persisted.
 */
internal fun mcpToolDisplayName(
    publicName: String?,
    resolvedName: String?,
): String? {
    val remoteName = resolvedName
        ?.takeIf { it.isNotBlank() }
        ?: publicName
            ?.let(MCP_PUBLIC_TOOL_NAME::matchEntire)
            ?.groupValues
            ?.getOrNull(1)
    return remoteName
        ?.trim()
        ?.split(TOOL_NAME_SEPARATOR)
        ?.filter(String::isNotBlank)
        ?.joinToString(" ") { word ->
            word.replaceFirstChar { char ->
                if (char.isLowerCase()) char.uppercaseChar() else char
            }
        }
        ?.replace(TOOL_PATH_INITIAL) { it.value.uppercase() }
        ?.takeIf(String::isNotBlank)
}

internal fun toolHeaderIcon(kind: ToolKind): ImageVector = when (kind) {
    ToolKind.TODO_READ, ToolKind.TODO_UPDATE -> Icons.Default.Checklist
    ToolKind.SUBAGENT_SPAWN, ToolKind.SUBAGENT_SEND, ToolKind.SUBAGENT_WAIT,
    ToolKind.SUBAGENT_LIST, ToolKind.SUBAGENT_STOP -> Icons.Default.AccountTree
    else -> Icons.Default.Build
}

internal fun fallbackToolDisplayName(toolName: String): String =
    toolName.split("_").joinToString(" ") { word ->
        word.replaceFirstChar { it.uppercaseChar() }
    }.replace(TOOL_PATH_INITIAL) { it.value.uppercase() }

private fun Resources.toolBaseDisplayName(
    kind: ToolKind,
    toolName: String,
): String = when (kind) {
    ToolKind.MEMORY_LIST -> getString(R.string.tool_look_up_memories)
    ToolKind.MEMORY_READ -> getString(
        if (toolName == "read_active_memory") R.string.tool_read_active_memory
        else R.string.tool_read_memory,
    )
    ToolKind.MEMORY_CREATE -> getString(R.string.tool_add_memory)
    ToolKind.MEMORY_EDIT -> getString(R.string.tool_edit_memory)
    ToolKind.MEMORY_DELETE -> getString(R.string.tool_delete_memory)
    ToolKind.MEMORY_UPDATE_ACTIVE -> getString(R.string.tool_update_active_memory)
    ToolKind.SKILL_LIST -> getString(R.string.tool_list_skills)
    ToolKind.SKILL_READ -> getString(R.string.tool_read_skill)
    ToolKind.SKILL_CREATE -> getString(R.string.tool_create_skill)
    ToolKind.SKILL_EDIT -> getString(R.string.tool_edit_skill)
    ToolKind.SKILL_DELETE -> getString(R.string.tool_delete_skill)
    ToolKind.WEB_SEARCH -> when (toolName) {
        "openai_search" -> getString(R.string.openai_search)
        "google_search" -> getString(R.string.google_search)
        else -> getString(R.string.tool_web_search)
    }
    ToolKind.WEB_FETCH -> getString(R.string.tool_web_fetch)
    ToolKind.CONVERSATION_SEARCH -> getString(R.string.tool_search_conversations)
    ToolKind.CONVERSATION_LIST -> getString(R.string.tool_list_conversations)
    ToolKind.CONVERSATION_READ -> getString(R.string.tool_read_conversation)
    ToolKind.SHELL_LIST -> getString(R.string.tool_list_shells)
    ToolKind.SHELL_EXECUTE -> getString(R.string.tool_execute_shell)
    ToolKind.SHELL_JOB_LIST -> getString(R.string.tool_shell_jobs)
    ToolKind.SHELL_JOB_GET -> getString(R.string.tool_shell_job)
    ToolKind.SHELL_JOB_WAIT -> getString(R.string.tool_wait_for_job)
    ToolKind.SHELL_JOB_STOP -> getString(R.string.tool_stop_shell_job)
    ToolKind.FILE_READ -> getString(R.string.tool_file_read)
    ToolKind.FILE_WRITE -> getString(R.string.tool_file_write)
    ToolKind.FILE_EDIT -> getString(R.string.tool_file_edit)
    ToolKind.FILE_GLOB -> getString(R.string.tool_file_glob)
    ToolKind.FILE_GREP -> getString(R.string.tool_file_grep)
    ToolKind.IMAGE_VIEW -> getString(R.string.tool_view_image)
    ToolKind.IMAGE_GENERATE -> getString(R.string.tool_generate_image)
    ToolKind.TASK_CREATE -> getString(R.string.tool_create_task)
    ToolKind.TASK_LIST -> getString(R.string.tool_list_tasks)
    ToolKind.TASK_DELETE -> getString(R.string.tool_delete_task)
    ToolKind.LOOP_START -> getString(R.string.tool_start_loop)
    ToolKind.LOOP_STOP -> getString(R.string.tool_stop_loop)
    ToolKind.ASK_USER -> getString(R.string.tool_ask_user)
    ToolKind.TODO_READ -> getString(R.string.tool_read_todo)
    ToolKind.TODO_UPDATE -> getString(R.string.tool_update_todo)
    ToolKind.SUBAGENT_SPAWN -> getString(R.string.tool_spawn_agent)
    ToolKind.SUBAGENT_SEND -> getString(R.string.tool_send_agent_message)
    ToolKind.SUBAGENT_WAIT -> getString(R.string.tool_wait_agents)
    ToolKind.SUBAGENT_LIST -> getString(R.string.tool_list_agents)
    ToolKind.SUBAGENT_STOP -> getString(R.string.tool_stop_agent)
    ToolKind.MCP -> "MCP"
    ToolKind.UNKNOWN -> if (toolName == "code_execution") {
        getString(R.string.code_execution)
    } else {
        fallbackToolDisplayName(toolName.ifBlank { getString(R.string.tool_context) })
    }
}

internal fun Resources.toolSummary(segment: MessageSegment): String {
    return this.toolSummary(ToolPresentationResolver.resolve(segment))
}

internal fun Resources.toolSummary(presentation: ToolPresentation): String {
    if (presentation.state == ToolPresentationState.FAILED) {
        toolFailureReasonSummary(presentation.errorMessage)?.let { return it }
    }
    if (presentation.toolName == "render_interactive_answer") {
        when (presentation.state) {
            ToolPresentationState.CALLING, ToolPresentationState.RUNNING ->
                return getString(R.string.interactive_answer_creating)
            ToolPresentationState.COMPLETED -> return getString(R.string.interactive_answer_created)
            else -> Unit
        }
    }
    if (presentation.kind == ToolKind.SHELL_EXECUTE) {
        return this.shellToolSummary(presentation)
    }
    val subject = presentation.subject
    return when (presentation.state) {
        ToolPresentationState.FAILED -> failedSummary(presentation, subject)
        ToolPresentationState.STOPPED -> stoppedToolSummary(presentation)
        ToolPresentationState.BACKGROUND_RUNNING -> getString(R.string.tool_background_job_running_default)
        ToolPresentationState.CALLING,
        ToolPresentationState.RUNNING -> runningSummary(presentation, subject)
        ToolPresentationState.EMPTY -> emptySummary(presentation, subject)
        ToolPresentationState.COMPLETED -> completedSummary(presentation, subject)
    }
}

private fun Resources.runningSummary(
    presentation: ToolPresentation,
    subject: String?,
): String = when (presentation.kind) {
    ToolKind.MEMORY_LIST -> getString(R.string.tool_looking_up_memories)
    ToolKind.MEMORY_READ -> if (presentation.toolName == "read_active_memory") {
        getString(R.string.tool_reading_active_memory)
    } else presentation.count?.takeIf { it > 1 }?.let {
        getQuantityString(R.plurals.tool_reading_files_count, it, it)
    } ?: optionalSubjectSummary(
        subject,
        R.string.tool_reading_memory,
        R.string.tool_progress_reading,
    )
    ToolKind.MEMORY_CREATE -> optionalSubjectSummary(
        subject,
        R.string.tool_saving_memory,
        R.string.tool_progress_saving,
    )
    ToolKind.MEMORY_EDIT -> editActionSummary(presentation, subject, active = true) ?: optionalSubjectSummary(
        subject,
        R.string.tool_updating_memory,
        R.string.tool_progress_updating,
    )
    ToolKind.MEMORY_DELETE -> optionalSubjectSummary(
        subject,
        R.string.tool_removing_memory,
        R.string.tool_progress_removing,
    )
    ToolKind.MEMORY_UPDATE_ACTIVE -> getString(R.string.tool_updating_active)
    ToolKind.SKILL_LIST -> getString(R.string.tool_listing_skills)
    ToolKind.SKILL_READ -> presentation.count?.takeIf { it > 1 }?.let {
        getQuantityString(R.plurals.tool_reading_files_count, it, it)
    } ?: optionalSubjectSummary(
        subject,
        R.string.tool_reading_skill_subject,
        R.string.tool_progress_reading,
    )
    ToolKind.SKILL_CREATE -> optionalSubjectSummary(
        subject,
        R.string.tool_creating_skill_subject,
        R.string.tool_progress_creating,
    )
    ToolKind.SKILL_EDIT -> editActionSummary(presentation, subject, active = true) ?: optionalSubjectSummary(
        subject,
        R.string.tool_editing_skill_subject,
        R.string.tool_progress_editing,
    )
    ToolKind.SKILL_DELETE -> optionalSubjectSummary(
        subject,
        R.string.tool_deleting_skill_subject,
        R.string.tool_progress_deleting,
    )
    ToolKind.WEB_SEARCH -> optionalSubjectSummary(
        subject,
        R.string.tool_searching_web,
        R.string.tool_progress_searching,
    )
    ToolKind.WEB_FETCH -> optionalSubjectSummary(
        subject,
        R.string.tool_web_fetching,
        R.string.tool_progress_fetching,
    )
    ToolKind.CONVERSATION_SEARCH -> optionalSubjectSummary(
        subject,
        R.string.tool_searching_for,
        R.string.tool_progress_searching,
    )
    ToolKind.CONVERSATION_LIST -> getString(R.string.tool_listing_conversations)
    ToolKind.CONVERSATION_READ -> optionalSubjectSummary(
        subject,
        R.string.tool_reading_conversation_subject,
        R.string.tool_progress_reading,
    )
    ToolKind.SHELL_LIST -> getString(R.string.tool_listing_shells)
    ToolKind.SHELL_EXECUTE -> optionalSubjectSummary(
        singleLineShellCommand(subject),
        R.string.tool_executing_shell,
        R.string.tool_progress_executing,
    )
    ToolKind.SHELL_JOB_LIST -> getString(R.string.tool_listing_shell_jobs)
    ToolKind.SHELL_JOB_WAIT -> optionalSubjectSummary(
        subject,
        R.string.tool_waiting_shell_job,
        R.string.tool_waiting_shell_job_default,
    )
    ToolKind.SHELL_JOB_GET -> optionalSubjectSummary(
        subject,
        R.string.tool_reading_shell_job,
        R.string.tool_progress_reading,
    )
    ToolKind.SHELL_JOB_STOP -> optionalSubjectSummary(
        subject,
        R.string.tool_stopping_shell_job,
        R.string.tool_progress_stopping,
    )
    ToolKind.FILE_READ -> optionalSubjectSummary(
        subject,
        R.string.tool_reading_file,
        R.string.tool_progress_reading,
    )
    ToolKind.FILE_WRITE -> optionalSubjectSummary(
        subject,
        R.string.tool_writing_file,
        R.string.tool_progress_writing,
    )
    ToolKind.FILE_EDIT -> optionalSubjectSummary(
        subject,
        R.string.tool_editing_file,
        R.string.tool_progress_editing,
    )
    ToolKind.FILE_GLOB -> optionalSubjectSummary(
        subject,
        R.string.tool_finding_files,
        R.string.tool_progress_finding,
    )
    ToolKind.FILE_GREP -> optionalSubjectSummary(
        subject,
        R.string.tool_searching_file,
        R.string.tool_progress_searching,
    )
    ToolKind.IMAGE_VIEW -> optionalSubjectSummary(
        subject,
        R.string.tool_viewing_image,
        R.string.tool_progress_viewing,
    )
    ToolKind.IMAGE_GENERATE -> optionalSubjectSummary(
        subject,
        R.string.tool_generating_image_subject,
        R.string.tool_progress_generating,
    )
    ToolKind.TASK_CREATE -> optionalSubjectSummary(
        subject,
        R.string.tool_creating_task_subject,
        R.string.tool_progress_creating,
    )
    ToolKind.TASK_LIST -> getString(R.string.tool_listing_tasks)
    ToolKind.TASK_DELETE -> optionalSubjectSummary(
        subject,
        R.string.tool_deleting_task_subject,
        R.string.tool_progress_deleting,
    )
    ToolKind.LOOP_START -> getString(R.string.tool_progress_starting)
    ToolKind.LOOP_STOP -> getString(R.string.tool_progress_stopping)
    ToolKind.ASK_USER -> getString(R.string.tool_waiting_for_answers)
    ToolKind.TODO_READ -> getString(R.string.tool_reading_todo)
    ToolKind.TODO_UPDATE -> getString(R.string.tool_updating_todo)
    ToolKind.SUBAGENT_SPAWN -> getString(R.string.tool_spawning_agent)
    ToolKind.SUBAGENT_SEND -> getString(R.string.tool_sending_agent_message)
    ToolKind.SUBAGENT_WAIT -> getString(R.string.tool_waiting_agents)
    ToolKind.SUBAGENT_LIST -> getString(R.string.tool_listing_agents)
    ToolKind.SUBAGENT_STOP -> getString(R.string.tool_stopping_agent)
    ToolKind.MCP,
    ToolKind.UNKNOWN -> getString(R.string.tool_calling_ellipsis)
}

internal sealed interface ShellPresentationStatus {
    data object Executing : ShellPresentationStatus
    data class Background(val jobId: String?) : ShellPresentationStatus
    data object Stopped : ShellPresentationStatus
    data class Exit(val code: Int?) : ShellPresentationStatus

    /**
     * A shell call that never produced a usable terminal result: connection loss, server error, or
     * a rejected command. `code` stays null for transport failures, `message` carries the server
     * text when there is one.
     */
    data class Failed(val code: Int?, val message: String?) : ShellPresentationStatus
}

internal fun shellPresentationStatus(presentation: ToolPresentation): ShellPresentationStatus =
    when {
        presentation.state == ToolPresentationState.BACKGROUND_RUNNING ->
            ShellPresentationStatus.Background(presentation.jobId)
        presentation.isActive -> ShellPresentationStatus.Executing
        presentation.state == ToolPresentationState.STOPPED -> ShellPresentationStatus.Stopped
        // A failed call must not fall through to the exit-code branch and read as "completed".
        presentation.state == ToolPresentationState.FAILED ->
            ShellPresentationStatus.Failed(presentation.exitCode, presentation.errorMessage)
        else -> ShellPresentationStatus.Exit(presentation.exitCode)
    }

internal fun Resources.shellToolSummary(presentation: ToolPresentation): String =
    when (val status = shellPresentationStatus(presentation)) {
        ShellPresentationStatus.Executing -> optionalSubjectSummary(
            singleLineShellCommand(presentation.subject),
            R.string.tool_executing_shell,
            R.string.tool_progress_executing,
        )
        is ShellPresentationStatus.Background ->
            getString(R.string.tool_background_job_running_default)
        ShellPresentationStatus.Stopped -> stoppedToolSummary(presentation)
        is ShellPresentationStatus.Failed -> shellFailureSummary(status)
        is ShellPresentationStatus.Exit -> status.code?.let { code ->
            getString(R.string.tool_shell_returned_code, code)
        } ?: getString(R.string.tool_shell_execution_completed)
    }

private fun Resources.shellFailureSummary(status: ShellPresentationStatus.Failed): String {
    val detail = toolFailureReasonSummary(status.message)
    return when {
        detail != null -> detail
        else -> getString(R.string.tool_shell_failed)
    }
}

internal fun toolFailureReasonSummary(message: String?): String? {
    var reason = message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    reason = reason.replace(Regex("^Error executing tool '[^']+':\\s*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^Error:\\s*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ").trim().removeSuffix(".")
    if (reason.isBlank() || reason.equals("error", ignoreCase = true)) return null
    val firstWord = reason.substringBefore(' ')
    val naturalWord = firstWord.all { it.isLetter() } &&
        firstWord.drop(1).none { it.isUpperCase() }
    if (naturalWord) reason = reason.replaceFirstChar { it.uppercaseChar() }
    return if (reason.length > 160) reason.take(159) + "\u2026" else reason
}

internal fun Resources.shellExecutionSummary(presentation: ToolPresentation): String =
    when (val status = shellPresentationStatus(presentation)) {
        ShellPresentationStatus.Executing -> getString(R.string.tool_state_executing)
        is ShellPresentationStatus.Background ->
            getString(R.string.tool_background_job_running_default)
        ShellPresentationStatus.Stopped -> stoppedToolSummary(presentation)
        is ShellPresentationStatus.Failed -> status.code?.let { code ->
            getString(R.string.tool_shell_detail_returned_code, code)
        } ?: getString(R.string.tool_state_failed)
        is ShellPresentationStatus.Exit -> status.code?.let { code ->
            getString(R.string.tool_shell_detail_returned_code, code)
        } ?: getString(R.string.tool_shell_detail_executed)
    }

internal fun singleLineShellCommand(
    command: String?,
    maxCharacters: Int = 120,
): String? = normalizeToolSummarySubject(command, maxCharacters)

private fun Resources.optionalSubjectSummary(
    subject: String?,
    @StringRes withSubject: Int,
    @StringRes withoutSubject: Int,
): String = if (subject.isNullOrBlank()) {
    getString(withoutSubject)
} else {
    getString(withSubject, subject)
}

internal fun Resources.stoppedToolSummary(presentation: ToolPresentation): String =
    getString(if (presentation.jobState == "interrupted") R.string.tool_interrupted
        else if (presentation.kind in setOf(ToolKind.SHELL_EXECUTE, ToolKind.SHELL_JOB_GET,
            ToolKind.SHELL_JOB_WAIT, ToolKind.SHELL_JOB_STOP)) R.string.tool_state_stopped
        else R.string.tool_execution_stopped)

private fun Resources.editActionSummary(presentation: ToolPresentation, subject: String?, active: Boolean): String? =
    when (presentation.operation) {
        "rename" -> if (subject != null && presentation.destination != null) {
            getString(if (active) R.string.tool_renaming_file else R.string.tool_renamed_file,
                subject, presentation.destination)
        } else getString(if (active) R.string.tool_renaming_file_default else R.string.tool_renamed_file_default)
        "describe" -> optionalSubjectSummary(subject,
            if (active) R.string.tool_describing_file else R.string.tool_described_file,
            if (active) R.string.tool_describing_file_default else R.string.tool_described_file_default)
        else -> null
    }

private fun Resources.questionOutcomeSummary(presentation: ToolPresentation): String = when (presentation.outcome) {
    "queued" -> presentation.count?.let { getQuantityString(R.plurals.tool_questions_queued, it, it) }
        ?: getString(R.string.tool_questions_asked)
    "answered" -> getQuantityString(R.plurals.tool_questions_answered, 1, 1)
    "skipped" -> getQuantityString(R.plurals.tool_questions_skipped, 1, 1)
    "answers" -> answerSetSummary(presentation.answeredCount, presentation.count)
    else -> getString(R.string.tool_questions_asked)
}

// The mixed form agrees with the total question count ("2 of 3 questions").
private fun Resources.answerSetSummary(answered: Int?, total: Int?): String = when {
    answered == null || total == null -> getString(R.string.tool_questions_asked)
    answered == total -> getQuantityString(R.plurals.tool_questions_answered, total, total)
    answered == 0 -> getQuantityString(R.plurals.tool_questions_skipped, total, total)
    else -> getQuantityString(R.plurals.tool_questions_mixed, total, answered, total)
}

private fun Resources.emptySummary(
    presentation: ToolPresentation,
    subject: String?,
): String = when (presentation.kind) {
    ToolKind.MEMORY_READ,
    ToolKind.SKILL_READ -> if (presentation.toolName == "read_active_memory") {
        getString(R.string.tool_read_active_memory_empty)
    } else optionalSubjectSummary(
        subject, R.string.tool_read_file_empty, R.string.tool_read_content_empty,
    )
    ToolKind.CONVERSATION_READ -> getString(R.string.tool_read_conversation_empty)
    ToolKind.MEMORY_LIST -> getString(R.string.tool_no_memories)
    ToolKind.SKILL_LIST -> getString(R.string.tool_no_skills)
    ToolKind.WEB_SEARCH -> optionalSubjectSummary(
        subject,
        R.string.tool_web_search_no_result,
        R.string.tool_web_search_no_result_default,
    )
    ToolKind.CONVERSATION_SEARCH -> optionalSubjectSummary(
        subject,
        R.string.tool_conversation_search_no_result,
        R.string.tool_conversation_search_no_result_default,
    )
    ToolKind.CONVERSATION_LIST -> getString(R.string.tool_listed_no_conversations)
    ToolKind.SHELL_LIST -> getString(R.string.tool_no_shells)
    ToolKind.SHELL_EXECUTE -> this.shellExecutionSummary(presentation)
    ToolKind.SHELL_JOB_LIST -> getString(R.string.tool_no_shell_jobs)
    ToolKind.FILE_READ -> optionalSubjectSummary(
        subject,
        R.string.tool_read_file_empty,
        R.string.tool_read_file_empty_default,
    )
    ToolKind.FILE_GLOB -> getString(R.string.tool_found_no_files)
    ToolKind.FILE_GREP -> getString(R.string.tool_found_no_matches)
    ToolKind.TASK_LIST -> getString(R.string.tool_no_tasks)
    ToolKind.TODO_READ -> getString(R.string.tool_todo_empty)
    ToolKind.SUBAGENT_LIST -> getString(R.string.tool_agents_empty)
    ToolKind.SUBAGENT_WAIT -> getString(R.string.tool_agent_results_empty)
    else -> completedSummary(presentation, subject)
}

private fun Resources.completedSummary(
    presentation: ToolPresentation,
    subject: String?,
): String = when (presentation.kind) {
    ToolKind.MEMORY_LIST -> presentation.count?.let {
        getQuantityString(R.plurals.tool_lookup_count, it, it)
    } ?: getString(R.string.tool_listed_memories_default)
    ToolKind.SKILL_LIST -> presentation.count?.let {
        getQuantityString(R.plurals.tool_listed_skills, it, it)
    } ?: getString(R.string.tool_listed_skills_default)
    ToolKind.SKILL_READ -> presentation.count?.takeIf { it > 1 }?.let {
        getQuantityString(R.plurals.tool_read_files_count, it, it)
    } ?: optionalSubjectSummary(
        subject,
        R.string.tool_read_skill_done,
        R.string.tool_read_skill_done_default,
    )
    ToolKind.SKILL_CREATE -> optionalSubjectSummary(
        subject,
        R.string.tool_created_skill,
        R.string.tool_created_skill_default,
    )
    ToolKind.SKILL_EDIT -> editActionSummary(presentation, subject, active = false) ?: optionalSubjectSummary(
        subject,
        R.string.tool_edited_skill,
        R.string.tool_edited_skill_default,
    )
    ToolKind.SKILL_DELETE -> optionalSubjectSummary(
        subject,
        R.string.tool_deleted_skill,
        R.string.tool_deleted_skill_default,
    )
    ToolKind.MEMORY_READ -> if (presentation.toolName == "read_active_memory") {
        getString(R.string.tool_read_active_memory_success)
    } else presentation.count?.takeIf { it > 1 }?.let {
        getQuantityString(R.plurals.tool_read_files_count, it, it)
    } ?: optionalSubjectSummary(
        subject,
        R.string.tool_read_memory_name,
        R.string.tool_read_memory_success,
    )
    ToolKind.MEMORY_CREATE -> optionalSubjectSummary(
        subject,
        R.string.tool_save_memory_name,
        R.string.tool_save_memory_default,
    )
    ToolKind.MEMORY_EDIT -> editActionSummary(presentation, subject, active = false) ?: optionalSubjectSummary(
        subject,
        R.string.tool_edit_memory_name,
        R.string.tool_edit_memory_default,
    )
    ToolKind.MEMORY_DELETE -> optionalSubjectSummary(
        subject,
        R.string.tool_delete_memory_name,
        R.string.tool_delete_memory_default,
    )
    ToolKind.MEMORY_UPDATE_ACTIVE -> getString(R.string.tool_update_active_default)
    ToolKind.WEB_SEARCH -> when {
        presentation.count == null -> getString(R.string.tool_web_search_done_default)
        subject == null -> getQuantityString(R.plurals.tool_web_search_result_count,
            presentation.count, presentation.count)
        else -> getQuantityString(R.plurals.tool_web_search_done,
            presentation.count, presentation.count, subject)
    }
    ToolKind.WEB_FETCH -> optionalSubjectSummary(
        subject,
        R.string.tool_web_fetch_done,
        R.string.tool_web_fetch_done_default,
    )
    ToolKind.CONVERSATION_SEARCH -> when {
        presentation.count == null -> getString(R.string.tool_conversation_search_done_no_count)
        subject == null -> getQuantityString(
            R.plurals.tool_conversation_search_done_default,
            presentation.count, presentation.count,
        )
        else -> getQuantityString(
            R.plurals.tool_conversation_search_done_for,
            presentation.count, presentation.count,
            subject,
        )
    }
    ToolKind.CONVERSATION_LIST -> presentation.count?.let {
        getQuantityString(R.plurals.tool_listed_conversations, it, it)
    } ?: getString(R.string.tool_listed_conversations_default)
    ToolKind.CONVERSATION_READ -> optionalSubjectSummary(
        subject,
        R.string.tool_read_conversation_done,
        R.string.tool_read_conversation_done_default,
    )
    ToolKind.SHELL_LIST -> presentation.count?.let {
        getQuantityString(R.plurals.tool_shell_list_count, it, it)
    } ?: getString(R.string.tool_listed_shells_default)
    ToolKind.SHELL_EXECUTE -> this.shellExecutionSummary(presentation)
    ToolKind.SHELL_JOB_LIST -> presentation.count?.let {
        getQuantityString(R.plurals.tool_shell_job_count, it, it)
    } ?: getString(R.string.tool_listed_shell_jobs_default)
    ToolKind.SHELL_JOB_WAIT -> optionalSubjectSummary(
        presentation.jobId ?: subject,
        R.string.tool_waited_shell_job,
        R.string.tool_waited_shell_job_default,
    )
    ToolKind.SHELL_JOB_GET -> optionalSubjectSummary(
        presentation.jobId ?: subject,
        R.string.tool_shell_job_status,
        R.string.tool_shell_job_read_default,
    )
    ToolKind.SHELL_JOB_STOP -> optionalSubjectSummary(
        presentation.jobId ?: subject,
        R.string.tool_stopped_shell_job,
        R.string.tool_shell_job_stopped_default,
    )
    ToolKind.FILE_READ -> optionalSubjectSummary(
        subject,
        R.string.tool_read_file_done,
        R.string.tool_read_file_done_default,
    )
    ToolKind.FILE_WRITE -> optionalSubjectSummary(
        subject,
        R.string.tool_wrote_file,
        R.string.tool_wrote_file_default,
    )
    ToolKind.FILE_EDIT -> optionalSubjectSummary(
        subject,
        R.string.tool_edited_file,
        R.string.tool_edited_file_default,
    )
    ToolKind.FILE_GLOB -> presentation.count?.let {
        getQuantityString(R.plurals.tool_found_files, it, it)
    } ?: getString(R.string.tool_found_files_default)
    ToolKind.FILE_GREP -> presentation.count?.let {
        getQuantityString(R.plurals.tool_searched_file, it, it)
    } ?: getString(R.string.tool_found_matches_default)
    ToolKind.IMAGE_VIEW -> optionalSubjectSummary(
        subject,
        R.string.tool_viewed_image,
        R.string.tool_viewed_image_default,
    )
    ToolKind.IMAGE_GENERATE -> getString(R.string.tool_generated_image)
    ToolKind.TASK_CREATE -> optionalSubjectSummary(
        subject,
        R.string.tool_created_task_subject,
        R.string.tool_created_task,
    )
    ToolKind.TASK_LIST -> presentation.count?.let {
        getQuantityString(R.plurals.tool_listed_task_count, it, it)
    } ?: getString(R.string.tool_listed_tasks)
    ToolKind.TASK_DELETE -> optionalSubjectSummary(
        subject,
        R.string.tool_deleted_task_subject,
        R.string.tool_deleted_task,
    )
    ToolKind.LOOP_START -> getString(R.string.tool_started_loop)
    ToolKind.LOOP_STOP -> getString(if (presentation.outcome == "already_stopped")
        R.string.tool_loop_already_stopped else R.string.tool_stopped_loop)
    ToolKind.ASK_USER -> questionOutcomeSummary(presentation)
    ToolKind.TODO_READ -> getString(R.string.tool_read_todo_done)
    ToolKind.TODO_UPDATE -> getString(R.string.tool_updated_todo)
    ToolKind.SUBAGENT_SPAWN -> getString(R.string.tool_spawned_agent)
    ToolKind.SUBAGENT_SEND -> getString(R.string.tool_queued_agent_message)
    ToolKind.SUBAGENT_WAIT -> getString(R.string.tool_waited_agents)
    ToolKind.SUBAGENT_LIST -> getString(R.string.tool_listed_agents)
    ToolKind.SUBAGENT_STOP -> getString(R.string.tool_stopped_agent)
    ToolKind.MCP,
    ToolKind.UNKNOWN -> getString(R.string.tool_done)
}

private fun Resources.failedSummary(
    presentation: ToolPresentation,
    subject: String?,
): String {
    val reason = toolFailureReasonSummary(presentation.errorMessage)
    val target = subject?.takeIf { it.isNotBlank() }
    return when (presentation.kind) {
        ToolKind.MEMORY_READ, ToolKind.SKILL_READ, ToolKind.CONVERSATION_READ, ToolKind.FILE_READ ->
            if (presentation.toolName == "read_active_memory") getString(R.string.tool_read_active_memory_failed)
            else target?.let { getString(R.string.tool_failed_to_read, it) }
                ?: getString(R.string.tool_read_failed_default)
        ToolKind.MEMORY_CREATE, ToolKind.SKILL_CREATE, ToolKind.TASK_CREATE ->
            target?.let { getString(R.string.tool_failed_to_create, it) }
                ?: getString(R.string.tool_create_failed_default)
        ToolKind.MEMORY_EDIT, ToolKind.SKILL_EDIT, ToolKind.FILE_EDIT,
        ToolKind.MEMORY_UPDATE_ACTIVE ->
            target?.let { getString(R.string.tool_failed_to_update, it) }
                ?: getString(R.string.tool_update_failed_default)
        ToolKind.MEMORY_DELETE, ToolKind.SKILL_DELETE, ToolKind.TASK_DELETE ->
            target?.let { getString(R.string.tool_failed_to_delete, it) }
                ?: getString(R.string.tool_delete_failed_default)
        ToolKind.FILE_WRITE -> target?.let { getString(R.string.tool_failed_to_write, it) }
            ?: getString(R.string.tool_write_failed_default)
        ToolKind.WEB_SEARCH, ToolKind.CONVERSATION_SEARCH, ToolKind.FILE_GREP ->
            target?.let { getString(R.string.tool_failed_to_search, it) }
                ?: getString(R.string.tool_search_failed)
        ToolKind.WEB_FETCH -> target?.let { getString(R.string.tool_failed_to_fetch, it) }
            ?: getString(R.string.tool_web_fetch_failed)
        ToolKind.FILE_GLOB -> target?.let { getString(R.string.tool_failed_to_find, it) }
            ?: getString(R.string.tool_find_failed_default)
        ToolKind.IMAGE_VIEW -> target?.let { getString(R.string.tool_failed_to_view, it) }
            ?: getString(R.string.tool_view_failed_default)
        ToolKind.IMAGE_GENERATE -> getString(R.string.tool_image_generation_failed)
        ToolKind.SHELL_EXECUTE -> reason?.let {
            getString(R.string.tool_shell_failed_with_reason, it)
        } ?: getString(R.string.tool_shell_failed)
        ToolKind.SHELL_JOB_GET, ToolKind.SHELL_JOB_WAIT ->
            getString(R.string.tool_shell_job_read_failed)
        ToolKind.SHELL_JOB_STOP -> getString(R.string.tool_shell_job_stop_failed)
        ToolKind.MEMORY_LIST, ToolKind.SKILL_LIST, ToolKind.CONVERSATION_LIST,
        ToolKind.SHELL_LIST, ToolKind.SHELL_JOB_LIST, ToolKind.TASK_LIST ->
            getString(R.string.tool_list_failed_default)
        ToolKind.LOOP_START -> getString(R.string.tool_loop_start_failed)
        ToolKind.LOOP_STOP -> getString(R.string.tool_loop_stop_failed)
        ToolKind.ASK_USER -> getString(R.string.tool_ask_user_failed)
        ToolKind.TODO_READ -> getString(R.string.tool_read_failed_default)
        ToolKind.TODO_UPDATE -> getString(R.string.tool_update_failed_default)
        ToolKind.SUBAGENT_SPAWN -> getString(R.string.tool_create_failed_default)
        ToolKind.SUBAGENT_LIST -> getString(R.string.tool_list_failed_default)
        ToolKind.SUBAGENT_SEND, ToolKind.SUBAGENT_WAIT, ToolKind.SUBAGENT_STOP,
        ToolKind.MCP, ToolKind.UNKNOWN -> reason ?: getString(R.string.tool_call_failed)
    }
}
