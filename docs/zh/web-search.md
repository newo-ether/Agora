# 网络搜索

打开**设置 → 网络搜索**选择后端和结果数。

支持 Brave、Kagi、Serper、Tavily、Exa、SearXNG 与 DuckDuckGo。多数服务需要凭据；SearXNG 需要实例 URL。DuckDuckGo 与 Exa 不需要 API Key：DuckDuckGo 是默认的尽力而为抓取，Exa 走公开的 MCP 端点。

网络搜索默认开启，结果数默认 5，可设置 1–10。

## Exa

Exa 是神经搜索 API，返回网页要点而不是普通摘要。查询同时作为搜索词与排序目标，每条结果的要点成为模型看到的描述。

- **无需 Key。** 搜索发往 Exa 的公开 MCP 端点（`https://mcp.exa.ai/mcp`），作为一次 `web_search_exa` 工具调用。端点以事件流返回，文本块按 `Title:`、`URL:`、`Published:`、`Author:`、`Highlights:` 列出每条结果，结果之间用 `---` 分隔。
- **带 Key。** 在 [dashboard.exa.ai](https://dashboard.exa.ai) 取得 Key 后，该服务改用计费的 REST 端点（`https://api.exa.ai/search`），通过 `x-api-key` 直接以 JSON 请求 `highlights` 内容。

两条路径都会在本地把结果数截到配置的上限。

查询会直接发送给所选搜索服务。返回的摘要和链接可作为工具数据保存在对话中，并在代理循环中发送给聊天模型。配额、政策和可用性由外部服务决定。
