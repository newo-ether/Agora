# Document RAG (your own files)

keywords: documents, rag, import, pdf, text, markdown, search_documents, knowledge, embedding, semantic search, user docs

Import your own **text, Markdown, and PDF** files into Agora and let the model search them by meaning. Your documents are chunked and embedded into a private semantic index on your device, and the model can retrieve the most relevant passages with the `search_documents` tool.

This is distinct from [PDF Import](pdf-import.md), which attaches PDF **pages as images** for a vision model to look at. Document RAG instead extracts the **text** of a file and makes it searchable — useful for larger documents you want to ask questions about across a conversation.

## Importing documents

Open **Settings → Documents** and tap **Import document**. Pick a file with the system file picker. Supported inputs:

- **Plain text / Markdown** and other text formats (`.txt`, `.md`, `.csv`, `.json`, `.xml`, `.yaml`, `.log`, or any `text/*` type) — read directly.
- **PDF** (`.pdf`) — text is extracted from the PDF. Scanned or image-only PDFs have no embedded text, so nothing is extracted (import reports "no extractable text"); use [PDF Import](pdf-import.md) with a vision model for those.

On import, the document's text is split into chunks and each chunk is embedded with your **active embedding model**. You must configure an embedding model first (see [Embedding / RAG](embedding.md)); until then the import button is disabled.

The screen lists each imported document with its chunk count. Remove a document with the trash icon — this deletes just that document's chunks, leaving the rest of the index intact.

## Searching documents

When you have at least one document imported and an embedding model configured, the model gains a `search_documents` tool:

`search_documents(query, limit?)`

1. Embeds your query with the same embedding model.
2. Ranks your document chunks by cosine similarity.
3. Returns the most relevant passages, each labelled with its document title.

Ask a question grounded in your files ("what does the contract say about termination?") and the model calls `search_documents`, reads the returned passages, and answers from them. If nothing matches, the tool tells the model your documents don't cover it rather than inventing an answer.

## Isolated from your other data

User documents live in their **own** corpus in the embedding store, completely separate from:

- your **conversation history** (searched by `search_conversations`), and
- the bundled **manual** (searched by [`introspect`](introspect.md)).

`search_documents` only ever reads your imported documents, and the other tools never return them. Everything stays on your device; embedding uses whichever embedding model you configured (local or your own remote endpoint).

## Availability

`search_documents` appears only when both conditions hold: an embedding model is configured **and** you have imported at least one document. Remove all documents and the tool disappears. As with any tool, whether the model can call it also depends on the provider supporting tool calls.

## Limitations

- Importing needs an embedding model; the import button is disabled without one.
- Image-only / scanned PDFs yield no text — use PDF Import (vision) for those.
- Very large files are capped during extraction to keep indexing bounded.
- The first search embeds your query via your embedding provider (a small per-query cost for remote models).
