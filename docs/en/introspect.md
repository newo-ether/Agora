# Self-Knowledge (Introspect)

keywords: introspect, self knowledge, manual, help, what can you do, how does, documentation, built-in manual, semantic search, embedding

Agora ships its own user manual inside the app and can answer questions **about Agora itself** — its features, tools, settings, and how to use them. When you ask "what can you do?", "how does X work?", or "where do I change Y?", the model calls the `introspect` tool, which searches the bundled manual and returns the most relevant page.

## What it does

`introspect(query)`:

1. Ranks the bundled manual pages for your question.
2. Returns the single most relevant page's **full text** (not a snippet), so the model has complete, coherent context.
3. Lists other related page titles, so the model can call `introspect` again for a different page if needed.

The result is prefixed with an instruction to answer **only from the manual** and to say so when the manual doesn't cover a question — so the model grounds its answer in documented behavior instead of guessing.

## How ranking works

- **Semantic search** when an embedding model is configured (Settings → Embedding / RAG): the manual is embedded on first use with your active embedding model and ranked by cosine similarity — the same engine conversation search uses.
- **Keyword fallback** when no embedding model is set up (or before the first index completes): pages are ranked by term overlap against each page's title, `keywords` line, and body. This means `introspect` always works, with or without an embedding model.

## Isolated from your data

The manual lives in its **own** embedding store, completely separate from your conversation history and conversation RAG:

- `introspect` never returns your conversations or personal data.
- Conversation search (`search_conversations`) never returns manual pages.

## Indexed on first use, refreshed per version

The manual is indexed lazily the first time `introspect` runs with an embedding model configured. It re-indexes automatically when the bundled manual changes (a new app version) or when you switch embedding models, so pages stay consistent with your current model. If you never use `introspect`, nothing is embedded.

## Availability

`introspect` is part of the default tool set. It only appears when the manual is bundled (every release includes it). Like any tool, model availability depends on the provider supporting tool calls.

## Limitations

- Answers are only as current as the bundled manual pages.
- Semantic ranking's first call embeds the manual via your embedding provider (a small one-time cost per app version / model); the keyword fallback has no such cost.
- One full page is returned per call by design; a broad question may need a second `introspect` call for a related page.

See also [Embedding / RAG](embedding.md) and [Agentic Tools](tools.md).
