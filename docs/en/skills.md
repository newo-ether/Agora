# Skills

A Skill is a named Markdown workflow you can reuse in chat. Open **Settings → Skills → Add Skill**.
Enter a meaningful name, a short description (aim for 5–10 words), an optional shortcut and the
instructions. Put tool names, necessary call examples, exact Alpine terminal paths and other Skill
filenames directly in the instructions. Save explicitly; Back discards unsaved changes. Reopen any
saved Skill to edit it, or use its menu to delete it.

Optionally select **Before template**, **After template** and **Continue with Skill** in the
Workflow group. Before collects inputs locally before the model starts. When the Skill explicitly
finishes the entire exercise using `complete_skill`, After presents the outcome and any review
controls. Pressing Continue passes those inputs to the next Skill; if it has its own Before
interface, that opens locally first. No During phase is used. Without a continuation, the interface
returns its collected data to the conversation. Cycles and chains longer than six Skills are rejected.
You can return to these settings and edit or remove the choices later.

Use your existing Alpine terminal integration to list/read a path when needed. There is no separate
folder attachment or dependency form. Paths and filenames are literal references: if you rename a
file or folder, update the instructions that refer to it. The F-Droid sandbox needs setup/enabling;
shared Android storage uses its existing `/mnt/shared` mount. Play has no local Linux sandbox.

Type **/** to browse templates and Skills together, or **/skills** for Skills only.
Each row shows its command/name and about five words of description. Regular mode uses the app
theme; e-ink uses black text, white surfaces and list page controls when needed. Select a Skill. Add your task to the prepared command and Send.
A configured shortcut also works unless a template already owns it. Discovery does not load every
Skill's content. Only the selected Skill's short description and instructions are loaded. Other
Skills can be called by filename through `invoke_skill` with the returned parent token, in the same
conversation. Existing tool permissions still apply.

At the end of a useful discussion, ask to **create a Skill from this discussion**, or use
**Create with AI** in Settings. That action adds an unsent request to the current draft; close
Settings, review and Send. The packaged `create-skill.md` authoring Skill asks the model to formalize
the workflow and present it using `propose_skill`.

The draft card shows its name, short description, shortcut and complete instructions. Expand it and
choose **Review and save Skill** to open the same native editor. Edit if needed, then Save. No Skill
is saved by merely displaying the card. Existing filenames cannot be overwritten by this action.
You can later edit the saved Skill in Settings. A user-saved `create-skill.md` overrides the packaged
authoring instructions.

There are no separate import or duplicate actions. Skills remain included in normal Agora backups
and native archives. Ordinary prompts do not automatically include the library; an advanced custom
`{skill_catalog}` variable explicitly opts into filenames only.

When connected to a Remote agent session, the slash picker can show native Skill commands supplied
by that session's bridge. Those entries remain temporary, are deduplicated, and disappear when
switching sessions or disconnecting. They are never imported into local Skills or backups.
Selection fills the agent's exact command; Send invokes it remotely. Bridges that do not yet
advertise native Skills show no extra entries. Agora's current bridge remains Codex-specific;
Pi and other agents need corresponding computer-side adapters.
