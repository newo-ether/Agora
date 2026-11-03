# Web Search

Open **Settings → Web Search** to choose a search backend and result count.

## Providers

Agora supports Brave, Kagi, Serper, Tavily, Exa, SearXNG, and DuckDuckGo. Most require credentials; SearXNG requires the instance URL. DuckDuckGo and Exa answer without an API key: DuckDuckGo is the default best-effort scraper, and Exa has a keyless public MCP endpoint.

Web search is enabled by default. The default result count is 5 and can be set from 1–10.

## Exa

Exa is a neural search API that returns page highlights rather than plain snippets. The query is sent as both the search text and the ranking objective, and each result's highlights become the description the model sees.

- **No key required.** Searches go to Exa's public MCP endpoint (`https://mcp.exa.ai/mcp`) as a `web_search_exa` tool call, which needs no credentials. The endpoint answers as an event stream whose text block lists `Title:`, `URL:`, `Published:`, `Author:`, and `Highlights:` for each result, separated by `---`.
- **With a key.** A key from [dashboard.exa.ai](https://dashboard.exa.ai) switches that provider to the billed REST endpoint (`https://api.exa.ai/search`) with `x-api-key`, requesting `highlights` content directly as JSON.

Both paths cap results to the configured count locally.

## Data flow

Queries are sent directly to the selected search provider. Returned snippets and links can be stored in the conversation as tool data and sent to the chat model during the agentic loop. Provider policies, quotas, and availability are external to Agora and can change.

See [Agentic Tools](tools.md) and [Privacy & Security](privacy.md).
