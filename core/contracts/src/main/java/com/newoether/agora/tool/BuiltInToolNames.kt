package com.newoether.agora.tool

/** The only compatibility table for built-in protocol names. Stored history is never rewritten. */
object BuiltInToolNames {
    val legacyToCanonical: Map<String, String> = linkedMapOf(
        "read_memory_file" to "memory_read",
        "list_memory_files" to "memory_list",
        "create_memory_file" to "memory_create",
        "edit_memory_file" to "memory_edit",
        "delete_memory_file" to "memory_delete",
        "read_active_memory" to "active_memory_read",
        "update_active_memory" to "active_memory_update",
        "list_skill_files" to "skill_list",
        "read_skill_file" to "skill_read",
        "create_skill_file" to "skill_create",
        "edit_skill_file" to "skill_edit",
        "delete_skill_file" to "skill_delete",
        "read_todo" to "todo_read",
        "update_todo" to "todo_update",
        "search_conversations" to "conversation_search",
        "list_conversations" to "conversation_list",
        "read_conversation" to "conversation_read",
        "execute_shell_command" to "shell_exec",
        "list_shells" to "shell_list",
        "list_shell_jobs" to "shell_job_list",
        "get_shell_job" to "shell_job_get",
        "wait_for_job" to "shell_job_wait",
        "stop_shell_job" to "shell_job_stop",
        "view_image" to "file_view_image",
        "spawn_agent" to "agent_spawn",
        "send_agent_message" to "agent_send_message",
        "wait_agents" to "agent_wait",
        "list_agents" to "agent_list",
        "stop_agent" to "agent_stop",
        "delete_agent" to "agent_delete",
        "create_task" to "task_create",
        "list_tasks" to "task_list",
        "delete_task" to "task_delete",
        "start_loop" to "loop_start",
        "stop_loop" to "loop_stop",
        "generate_image" to "image_generate",
    )

    fun canonical(name: String): String = legacyToCanonical[name] ?: name

    /** Used only to construct known built-in templates, never to edit user prompts. */
    fun canonicalTemplate(text: String): String = legacyToCanonical.entries.fold(text) { value, (old, new) ->
        value.replace(Regex("(?<![A-Za-z0-9_])${Regex.escape(old)}(?![A-Za-z0-9_])"), new)
    }
}
