# Embedding / RAG

Embedding models convert conversation text into vectors used by semantic conversation search. Configure them under **Settings → Conversation Search**.

## Providers and presets

Available presets currently include:

- OpenAI: `text-embedding-3-small`, `text-embedding-3-large`, `text-embedding-ada-002`
- Mistral: `mistral-embed`
- Voyage AI: `voyage-3-large`, `voyage-3-lite`, `voyage-code-3`
- SiliconFlow: `BAAI/bge-m3`, `BAAI/bge-large-en-v1.5`
- OpenRouter: OpenAI embedding model routes
- Requesty: OpenAI embedding model routes
- Ollama, a local embedding model, or a custom endpoint

Remote embeddings use the credentials and base URL configured for the selected provider. Text sent for embedding therefore leaves the device for that provider. Local embeddings remain on-device.

## RAG controls

- Context range: 4–32 conversation steps, in steps of 4
- Result count: 5–30, in steps of 5
- Similarity threshold: 0–1 (default 0.5)

Changing the embedding model may require existing indexed content to be embedded again before results are complete. See [Conversation Search](search.md) and [Privacy & Security](privacy.md).
