package com.newoether.agora.skills

/** Packaged instructions loaded only when this authoring Skill is explicitly called. */
object SkillAuthoring {
    const val NAME = "create-skill.md"
    val document get() = SkillDocument(NAME, """
        Turn the useful workflow from this discussion into one reusable Skill.
        Use the conversation already in context; do not read unrelated conversations or Skills.
        Identify the outcome, repeatable steps, inputs and checks that made the workflow useful.
        Ask a brief question only if essential information is missing.
        Choose a meaningful, concise Markdown filename that describes its content.
        Write a description of 5–10 words and an optional short slash shortcut.
        Put all instructions, existing tool names, necessary call examples, exact Alpine paths
        and references to other Skills by filename inside the Markdown instructions.
        Do not invent paths or tools. List/read a specified path through existing terminal tools
        only when needed. If a path or filename changes, the instructions must be edited.
        Do not include one-off conversation details, secrets or irrelevant chat history.
        Call propose_skill to show the name, description, shortcut and complete instructions
        in the existing interactive viewer. The user reviews/edits and saves through the native
        editor. Do not call create_skill_file or overwrite another Skill as part of drafting.
    """.trimIndent(), description = "Turn discussions into reusable Skills")
}
