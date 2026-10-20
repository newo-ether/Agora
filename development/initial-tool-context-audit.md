# Initial tool context audit — 17 October 2026

For the owner's DeepSeek Flash configuration: Shell/local Alpine enabled, no remote shell or MCP
servers, image generation and automation disabled. Notes, interactive interfaces, templates,
memory, Skills, past conversations, web and questions are enabled through on-demand discovery.
The OSCE conversation has no per-conversation overrides or provider tool history; its setup is a
local card excluded from provider messages. This is an offline reconstruction, not a paid request.

| Initial definition | Schema text | Wrapper allowance | Raw estimate |
|---|---:|---:|---:|
| load_tool_capability | 128 | 16 | 144 |
| list_shells | 17 | 16 | 33 |
| execute_shell_command | 203 | 16 | 219 |
| file_read | 86 | 16 | 102 |
| file_write | 65 | 16 | 81 |
| file_edit | 102 | 16 | 118 |
| file_glob | 111 | 16 | 127 |
| file_grep | 76 | 16 | 92 |
| Total | 788 | 128 | 916 |

The estimator adds 10% and rounds up: `ceil(916 * 1.1) = 1008`. The safety buffer is 92;
DeepSeek's cost family has zero extra tool-set overhead. The UI abbreviates this to 1K. Text uses
the bundled o200k_base tokenizer as a proxy for DeepSeek, plus an estimated 16-token wrapper per
tool. This is exact arithmetic for the app's estimate, not DeepSeek's actual tokenizer/billing.
No tokens are sent or billed merely by showing the meter. On Send/Start, these schemas are input
context even if the model never calls a tool. The buffer/wrapper are estimator constants, not
literal additional instructions transmitted over HTTP.

The loader directory contains only eight short capability summaries and one capability argument.
Full deferred schemas are loaded per user turn only after the appropriate capability call. Shell
and file tools remain outside that deferred mapping, so all seven schemas are present initially:
772 raw tokens. Discovery costs 144 raw tokens. No Conch server means no view_image tool.

This initial tools list contains no article text, folder listing, template HTML, Skill bodies,
Skill catalogue, tool outputs, system prompt or message history. Those are independent inputs.
No tool visibility or permissions were changed by this audit.

`InitialToolContextAuditTest` reproduces the projection/count offline and writes the full definitions,
field-level costs and total to `app/build/reports/tool-context/initial-tools.json`. The user-facing
companion files `next-request-tools.json` and `initial-tools-breakdown.md` contain the complete
schemas and explanation for inspection. They are generated artifacts, not provider request logs;
no credentials or user messages are included.

## Reported 941 System / 1.4K Tools and subsequent corrections

A later native export confirmed the Default system prompt byte-for-byte (2,867 UTF-8 bytes).
Before the tokenizer loaded, the rough fallback estimated 847 system text tokens + 8 envelope
allowance + 86 margin = 941. It estimated 1,321 tool tokens + 133 margin = 1,454. The same snapshot
with bundled o200k_base gives 544 + 8 + 56 = 608 System and 916 + 92 = 1,008 Tools.
The counter previously lacked a tokenizer-readiness invalidation signal, so the rough initial
projection could stay visible after loading. Readiness now invalidates the projection.

Removing independent template tools subsequently reduced the loader directory to seven categories:
135 raw discovery tokens + 772 shell/file tokens = 907, plus 91 margin = 998 Tools using the
bundled proxy. No article, template HTML or Skill body was added to the initial request.
The historical 941/1,454 figures above describe the earlier snapshot, not a provider invoice.
Generated current-prompt-audit/system-prompt.txt and next-request-context.json show the verified
system text and current fixed request context. They exclude credentials, eventual user input,
generation parameters and any additional conversation history.
