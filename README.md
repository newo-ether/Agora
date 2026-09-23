<div align="center">
  <img src="app/src/main/assets/agora_transparent_large.png" alt="Agora Logo" width="120" />

  # Agora

  **BYOK LLM client with multi-provider access, agentic workflows, and remote device control.**

  [![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
  [![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
  [![Kotlin](https://img.shields.io/badge/Kotlin-Native-blue.svg)](https://kotlinlang.org/)

  [Website](https://agora.newoether.com) · [User Manual](https://newo-ether.github.io/Agora/)

  <img src="assets/feature_graphic.png" alt="Agora — A BYOK AI app that takes back your data sovereignty." width="100%" />
</div>

## Announcement: third-party app “金龙AI-Pro”

An Android app named “金龙AI-Pro” (Jinlong AI-Pro, package `com.youlong.ai`) is distributed as a component of the Android toolbox “游龙工具箱 9.0”, first published on 2026-09-19. It is built from Agora’s code and assets under a different name and package, is signed with a third-party certificate, and still calls Agora’s `newoether.space` endpoints. Agora has not authorized any third party to release it under another name, and Agora is not affiliated with that app in any way.

The full technical analysis is in **[INCIDENT-2026-09-jinlong-ai-pro.md](INCIDENT-2026-09-jinlong-ai-pro.md)**: sample hashes, signature and certificate details, the identifiers still present in that build, the capabilities it adds, its distribution channels and download counts, and the method to reproduce every result.

As a consequence, Agora is relicensed. **v2.2.0 and later are released under GPL-3.0** ([LICENSE](LICENSE)); v2.1.0 and earlier remain MIT (see the [historical license](https://github.com/newo-ether/Agora/blob/9fc92fc3518c880158111ae1e9534ed8ffd09c6d/LICENSE)).

## Introducing Agora

[![Watch Introducing Agora on YouTube](https://i.ytimg.com/vi/P0p5PzROC0I/maxresdefault.jpg)](https://youtu.be/P0p5PzROC0I)

[Watch on YouTube](https://youtu.be/P0p5PzROC0I)

## Download

[![F-Droid](https://img.shields.io/badge/F--Droid-Install-blue?logo=fdroid)](https://f-droid.org/packages/com.newoether.agora/)
&nbsp;&nbsp;
[![Google Play](https://img.shields.io/badge/Google_Play-Install-blue?logo=google-play)](https://play.google.com/store/apps/details?id=com.newoether.agora)
&nbsp;&nbsp;
[![GitHub Releases](https://img.shields.io/badge/GitHub-Releases-blue?logo=github)](https://github.com/newo-ether/Agora/releases)

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

- **Eleven built-in provider types:** OpenAI, Anthropic, Google Gemini, DeepSeek, Qwen/DashScope, OpenRouter, Requesty, OpenCode Go, Groq, Ollama, and Local llama.cpp; custom endpoints support OpenAI-compatible, Google, or Anthropic protocols.
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

For GPL releases, redistributors must keep the copyright and license notices, state that they changed the files, and make the complete corresponding source available under the same license. The Agora name, logo, and screenshots are not covered by the code license, and modified builds must not imply that they are official or endorsed; the announcement above documents the incident that prompted this change.

Bundled third-party payloads keep their own licenses. The Termux bootstrap and toolchain that Agora provisions (see `build-proot.sh` and `thirdparty/`) include GPLv3 components, and their corresponding source is available from the upstream projects they come from.
