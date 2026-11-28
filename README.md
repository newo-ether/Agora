<div align="center">
  <img src="app/src/main/assets/agora_transparent_large.png" alt="Agora Logo" width="120" />

  # Agora (XNet Fork)

  **Sovereign BYOK LLM client with multi-provider access, agentic workflows, on-device sherpa-onnx STT, and ecosystem device tooling.**

  [![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
  [![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
  [![Kotlin](https://img.shields.io/badge/Kotlin-Native-blue.svg)](https://kotlinlang.org/)
  [![Upstream: v2.3.2](https://img.shields.io/badge/Upstream-v2.3.2-blueviolet.svg)](https://github.com/newo-ether/Agora/releases/tag/v2.3.2)

  [XNet Repository](https://github.com/XNet-NGO/Agora) · [Releases](https://github.com/XNet-NGO/Agora/releases) · [Upstream Agora](https://github.com/newo-ether/Agora)

  <img src="assets/feature_graphic.png" alt="Agora — A BYOK AI app that takes back your data sovereignty." width="100%" />
</div>

## XNet Fork Architecture (v2.3.2 Sync)

This branch synchronizes our fork with upstream **Agora v2.3.2**, adopting the modular engine structure (`core:model`, `core:contracts`, `core:presentation`, `data:database`, `provider:cloud`) and upstream's on-device `sherpa-onnx` STT, Shizuku background virtual display, WebUI tools sheet, and ChatGPT/DeepSeek provider accounts.

### Maintained By
- **Organization**: XNet Inc. · Foxnet Labs · XNet NGO
- **Ecosystem Integration**: Interoperates with [ShellBox](https://github.com/xnet-admin-1/shellbox), [Switchboard](https://github.com/XNet-NGO/switchboard), [re-adb](https://github.com/XNet-NGO/re-adb), and [NeuralBridge](https://github.com/XNet-NGO/NeuralBridge-System).
- **Packaging & Security**: Configured with uncompressed JNI packaging (`useLegacyPackaging = false`) for strict 16KB ELF page-alignment compatibility on Android 15+.
- **Update Checking**: Disconnected from third-party update checking; updates are distributed through official XNet channels.

## Download

[![GitHub Releases](https://img.shields.io/badge/GitHub-Releases-blue?logo=github)](https://github.com/XNet-NGO/Agora/releases)

Agora is an open-source Android client for using your own model accounts and endpoints. It stores conversations locally, sends model requests directly to the selected provider, supports non-linear message branches and Context Compact, and can extend agent runs with MCP, automation, search, memory, local models, and remote shell tools.

## Screenshots

<table>
<tr>
<td width="33%"><img src="assets/screenshot_1.jpg?v=20260920" alt="Chat" width="100%"/></td>
<td width="33%"><img src="assets/screenshot_2.jpg?v=20260920" alt="Tools" width="100%"/></td>
<td width="33%"><img src="assets/screenshot_3.jpg?v=20260920" alt="Settings" width="100%"/></td>
</tr>
</table>

## Features

- **Ten built-in provider types:** OpenAI, Anthropic, Google Gemini, DeepSeek, Qwen/DashScope, OpenRouter, OpenCode Go, Groq, Ollama, and Local llama.cpp; custom endpoints support OpenAI-compatible, Google, or Anthropic protocols.
- **Tree-structured conversations:** edit or regenerate earlier messages without discarding alternative branches.
- **Token-budget context:** 4K–1M estimated-token budgets and non-destructive Compact capsules that retain a verbatim recent suffix.
- **Agentic tools:** web search, memory, past-conversation RAG, image generation, MCP servers, Tasks/Loops, remote shell/files, durable Conch jobs, and an F-Droid Alpine sandbox.
- **Local intelligence:** GGUF chat models and local embeddings through llama.cpp.
- **Portable data:** versioned `.agora` ZIP archives, ChatGPT/Claude imports, and scheduled backups.
- **Customizable UI:** Material 3 themes, fonts, haptics, thinking/tool presentation, and 12 explicit interface languages plus system default.

Conch application-layer encryption is enabled when an API key is configured. A blank-key Conch endpoint sends plain JSON and should use HTTPS. External providers and tools receive only the data needed for the feature you invoke; see the privacy documentation for the full boundary.

## Documentation

- 📖 **[User Manual](https://newo-ether.github.io/Agora/)** — 28 maintained manual pages covering setup, providers, Context Compact, MCP, automation, tools, privacy, and data management.
- 🏗️ **[Architecture Guide](ARCHITECTURE.md)** — current runtime, persistence, providers, tools, and data flows.
- 🧰 **[Development documentation](development/documentation-maintenance.md)** — internal contracts, baselines, and documentation-maintenance policy.

Public manuals live under `docs/<locale>/`. Internal engineering documents live under `development/`.

## Getting Started

1. Install Agora and open **Settings** from the conversation drawer.
2. Add credentials under **Providers**.
3. Sync and enable models under **Models**.
4. Select a model from the chat bottom bar and send a message.

See the [Getting Started manual](https://newo-ether.github.io/Agora/getting-started/).

### Build from source

The current project targets Android SDK 36 and uses JDK 21 in its repository workflow. Install Android Studio plus the required SDK/NDK components, then use the root project scripts and instructions.

## Tech stack

Kotlin, Jetpack Compose Material 3, Coroutines/Flow, Room, DataStore, OkHttp/SSE, `kotlinx.serialization`, Android NDK/CMake, llama.cpp, Coil, and Markdown/LaTeX rendering.

## Privacy

Agora does not relay chat completions or run general analytics. Conversations remain in app-managed local storage, while configured providers and tools are contacted directly when used. Optional update checks and explicitly submitted ratings have documented network destinations. After a crash, one report is kept locally and is sent only if the user confirms on the next launch; it contains diagnostics but no conversation text or credentials. Secret settings normally use an Android Keystore AES-GCM envelope, but legacy values and a deliberate encryption-failure fallback can remain plaintext in DataStore; exported secrets are also unencrypted inside a selected `.agora` archive.

Read [Privacy & Security](https://newo-ether.github.io/Agora/privacy/) and the repository [Privacy Policy](PRIVACY.md).

## Contributing and license

Contributions are welcome through issues and pull requests. By submitting a pull request you agree that your contribution is licensed under the license in effect when it is merged.

**Current license: [GNU General Public License v3.0](LICENSE).** This applies to Agora v2.2.0 and later.

**Historical releases:** v2.1.0 and earlier remain available under their [original MIT License](https://github.com/newo-ether/Agora/blob/9fc92fc3518c880158111ae1e9534ed8ffd09c6d/LICENSE). These licenses apply to different versions; the current project is not offered under a choice of MIT or GPL.

For GPL releases, redistributors must keep the copyright and license notices, state that they changed the files, and make the complete corresponding source available under the same license. The Agora name, logo, and screenshots are not covered by the code license, and modified builds must not imply that they are official or endorsed.

Bundled third-party payloads keep their own licenses. The Termux bootstrap and toolchain that Agora provisions (see `build-proot.sh` and `thirdparty/`) include GPLv3 components, and their corresponding source is available from the upstream projects they come from.
