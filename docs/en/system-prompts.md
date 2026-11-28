# System Prompts

Open **Settings → System Prompts** to manage reusable prompt configurations.

## Create and edit

New prompts start from either **Blank** or **Default**. The editor has three ordered templates:

- **System** defines the complete provider-visible system message.
- **User** defines ordinary user messages.
- **Assistant** defines ordinary assistant messages.

User and Assistant each contain exactly one structural `Prompt` item. It represents the original message body and cannot be deleted, moved, or inserted again. Text and variables can be added above or below it.

Context variables include `{time}`, `{date}`, `{sent_time}`, `{sent_date}`, `{active_memory}`, `{skill_catalog}`, `{current_model_id}`, and `{message_model_id}`. `{current_model_id}` is the model selected for the outbound request. `{message_model_id}` is resolved separately for each ordinary historical message from the model that created it, and is empty when that message has no model identity. The legacy `{model_id}` remains readable as an alias of `{current_model_id}` but is no longer offered for new templates.

Older saved tool-guidance variables are no longer offered. If a customized template still contains one, it appears as a removable Unknown item and contributes no text to previews or requests. Opening the editor does not rewrite the saved template.

Variables resolve immediately before each outbound provider request, including the initial request, tool continuations, and transport retries. `{active_memory}` and `{skill_catalog}` are captured once when a reply starts and remain fixed for every request of that reply to preserve prompt-cache stability. Mid-reply edits apply to the next reply; current active memory can be read with `active_memory_read`. Editor previews use example values without freezing future request values.

The selected structured System template fully owns the ordinary system prompt. Agora does not append hidden memory, skill, runtime, or tool-guidance text. The Default template begins with one editable text item containing Response, Tools, Memory, Skills, Past conversations, Web, Devices, Subagents and automation, and Asking the user. These sections remain present regardless of enabled tools. The skill catalog follows, with active memory last. Access settings control tool availability; removing a variable removes its content from the prompt.

User and Assistant templates apply only to ordinary conversation messages. Tool messages, context compaction, title generation, and other special generation paths keep their dedicated formats. Saving is explicit. Leaving a changed editor asks whether to discard its draft.

## Manage and select

Prompts can be edited, duplicated, deleted, and marked as the global default. A conversation can inherit that default or select another saved prompt.

Only exact, unmodified copies of known older built-in defaults upgrade automatically. Custom system text and message wrappers are preserved. The built-in Default is defined by the app and can change with product behavior.
