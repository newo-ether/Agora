# Model Context Protocol (MCP)

Agora can connect to remote MCP servers and expose their tools to supported chat models.

## Add a Server

1. Open **Settings → MCP**.
2. Tap **Add MCP Server**.
3. Choose **Streamable HTTP** or **SSE**.
4. Enter a display name and an http:// or https:// server URL.
5. Add only the custom HTTP headers required by the server, then save.

Streamable HTTP is the current transport. SSE is available for legacy MCP servers.

Custom header values use the same secret-setting storage as API keys: normally an Android Keystore AES-GCM envelope, with legacy/encryption-failure plaintext fallback. They can contain authorization tokens, so protect the device and exports and do not add headers the server does not require.

## Connection and Tool Discovery

An enabled server connects in the background. Its status is shown as **Idle**, **Connecting**, **Connected**, or **Connection error**. Use **Reconnect** from the server menu or editor to retry discovery.

After a successful connection, Agora lists the server's tools. Each discovered tool is enabled by default; open the server editor to disable individual tools. Disabling the server makes all of its tools unavailable without deleting the configuration.

Models receive three fixed MCP entries. `mcp_list_servers` lists enabled servers with stable IDs, local connection status and available tool counts, including unavailable servers and servers with no tools. It does not connect or refresh, and omits endpoint URLs, headers and error details. `mcp_search_tools` discovers available tools and returns their full input schemas; `mcp_call_tool` invokes an exact discovered `tool_id` with an `arguments` object. The server validates tool-specific arguments.

Search is global by default; listing first is optional. Search supports keywords, empty-query browsing, exact `server_id` filtering and pagination. Unknown or disabled server IDs return `invalid_server`; enabled servers that are not currently connected return `server_unavailable`. A valid connected server with no matching tools returns an empty page. A scoped search never silently falls back to global search. Only connected, enabled tools can be discovered or called.

Directory reads, searches and calls use Agora's normal tool pipeline and their results remain in conversation history. Changing servers or their tool lists does not rewrite the three entry definitions or earlier results, preserving prompt-cache reuse eligibility. Adding the list entry changes that prefix once; actual hits still depend on the provider, model, system prompt, context window and cache lifetime.

## Edit or Remove a Server

Tap a server to edit its transport, name, URL, custom headers, connection status, and enabled tools. Use the server's overflow menu to reconnect or delete it.

Deleting a server removes its configuration and makes its tools unavailable. Existing conversation history is retained.

## Security

An MCP server can cause external side effects through its tools. Only connect servers you trust, review their tool list, and grant the narrowest credentials possible. Prefer HTTPS outside a trusted local network.

## Troubleshooting

### Connection error

- Confirm the URL is reachable from the Android device.
- Verify that the selected transport matches the server.
- Check authentication headers and server logs.
- Use **Reconnect** after changing the server.

### No tools appear

- Confirm the server is enabled and reports **Connected**.
- Reconnect to refresh discovery.
- Open the editor and ensure the individual tools are enabled.

### A tool is not offered to the model

The active model/provider must support tool calling. Also verify that the server and the specific tool are both enabled.
