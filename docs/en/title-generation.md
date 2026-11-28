# Title Generation

Open **Settings → Title Generation** to control automatic conversation titles.

- Enable or disable automatic title generation. It is enabled by default.
- Select the model used for titles, or use the current/dedicated configured model according to the selector.
- Edit the prompt used to request a concise title.
- Enable or disable the completion notification.

When enabled, automatic title generation starts as soon as the first message is saved and New Chat becomes a conversation. It does not wait for the assistant's reply.

Automatic and manual title requests use only the first user message on the currently selected branch, including its available attachment text or metadata. Assistant replies and later user messages are excluded. The request goes to the selected title model provider. A manual rename made while a title request is running is preserved.

See [Conversations](conversations.md) and [Privacy & Security](privacy.md).
