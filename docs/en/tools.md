# Agentic Tools

Agora can let a model call tools across multiple generation passes.

## Available capabilities

Depending on settings, build, and provider support, tools include:

- web search
- image generation
- active and saved memory
- past-conversation search
- MCP servers
- Tasks and Loops automation
- remote Conch/SSH commands, durable job management, file operations, and `file_view_image`
- the F-Droid Alpine sandbox

## Phone Use

In Settings → Tools → Phone Use, **Max Screenshots** defaults to 5. The slider offers 1 to 16 images, with **Unlimited** to the right of 16. A numeric limit sends only the most recent images from built-in phone tools; Unlimited removes that count limit. Changes apply to new generations. Neither option deletes screenshots from the conversation or affects user attachments, generated images, or images from other tools.

## Ask User

Interaction settings control whether the model can ask you a question. Blocking questions wait for your answer for five minutes by default. The Timeout slider has seven presets: 1, 2, 5, 10, 15, 30, and 60 minutes, and saves when you release it. A question set shares one timeout. When it expires, submitted answers are kept and the other questions return unanswered. Non-blocking questions remain available while the model continues working. Changes apply to new generations.

## Permissions and defaults

Web search, active memory, saved memory, past-conversation access, and the global shell permission are enabled by default. Shell calls still require a configured device, and its confirmation policy remains authoritative. Automation tools are disabled by default. MCP availability is controlled per server and per tool.

Tool calls and results become part of the conversation protocol and can be sent to the selected model on subsequent passes. External tools receive the arguments needed for their call. Review each server and permission before enabling it.

Image-generation and embedding credentials come from their selected providers; they are not separate universal tool secrets. See [MCP](mcp.md), [Automation](automation.md), [Shell](shell.md), and [Privacy & Security](privacy.md).
