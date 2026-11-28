<div align="center">
  <img src="app/src/main/assets/agora_transparent_large.png" alt="Agora Logo" width="128" />

  # Agora (XNet Fork)

  **The Sovereign Android AI Companion — Multimodal Live Voice, On-Device STT, System Assistant Overlay, Local ONNX Embeddings, and Autonomous Tool Workflows.**

  [![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
  [![Platform: Android](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026--36)-green.svg)](https://developer.android.com)
  [![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-purple.svg)](https://kotlinlang.org/)
  [![Release](https://img.shields.io/badge/Release-v2.2.0-blue.svg)](https://github.com/XNet-NGO/Agora/releases/tag/v2.2.0)
  [![Page Size](https://img.shields.io/badge/16KB%20Page%20Aligned-Android%2015%2B-brightgreen.svg)](https://developer.android.com/guide/practices/page-sizes)

  [Latest Release](https://github.com/XNet-NGO/Agora/releases/latest) · [Architecture Guide](ARCHITECTURE.md) · [Privacy Policy](PRIVACY.md) · [Assistant Spec](ASSISTANT-LAYER1-SPEC.md)
</div>

---

## About This Fork

**Agora (XNet Fork)** is an independent, sovereign downstream fork of [`newo-ether/Agora`](https://github.com/newo-ether/Agora) maintained by **XNet Inc. / Foxnet Labs / XNet NGO**.

While upstream Agora established a pioneering Bring-Your-Own-Key (BYOK) multi-provider client, our fork radically expands Agora into a full **System AI Assistant** and **local multimodal platform**. We have re-engineered the native execution stack, introduced on-device neural speech processing, integrated direct bidirectional live multimodal voice with real-time tool loops, and ported sovereign tooling without sacrificing privacy or requiring cloud intermediaries.

### Why We Forked & Key Architectural Upgrades

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Agora (XNet Fork)                                │
├──────────────────────────┬─────────────────────────────┬───────────────┤
│    Assistant & Voice     │     Native Intelligence     │  Local Tools  │
├──────────────────────────┼─────────────────────────────┼───────────────┤
│ • System Assistant Role  │ • Pure ONNX Runtime (ORT)   │ • LAN Server  │
│ • Floating Overlay FGS   │ • sherpa-onnx On-Device STT │ • Net Scanner │
│ • Screen/Camera Vision   │ • Local Document RAG Vector │ • Alpine PRoot│
│ • Gemini Live Voice      │ • 16KB Page-Aligned JNI     │ • MCP Client  │
└──────────────────────────┴─────────────────────────────┴───────────────┘
```

1. **Pure ONNX Runtime Architecture (llama.cpp stripped):**
   Upstream bundled `llama.cpp` JNI binaries, which suffered from crashes on varied mobile chipsets, high battery drain, and memory-alignment incompatibilities on modern Android kernels. We replaced the local inference engine with **ONNX Runtime (ORT 1.28.2)** and **onnxruntime-genai**, providing robust on-device embeddings (`document_embeddings` vector store) and fast, crash-free execution.
2. **On-Device Speech-to-Text (Sherpa-ONNX):**
   Built-in offline speech recognition powered by next-gen Kaldi / `sherpa-onnx`. Transcribe voice input locally in the composer with zero cloud audio transmission, zero latency, and persistent model caching.
3. **Gemini Live Voice & Real-Time Multimodal Video:**
   Direct bidirectional WebSocket streaming to Google Gemini Live with low-latency PCM audio, acoustic echo cancellation, and speakerphone routing. Supports live camera and screen capture video streaming directly into the multimodal reasoning loop, complete with **mid-speech device tool execution**.
4. **Layer 1 Android System Assistant & Multi-Tasking Overlay:**
   Full Android Assistant role integration (`android.service.voice.VoiceInteractionService`). Replace default platform assistants with Agora to trigger via home button or power key hold. Includes an optional floating multi-tasking overlay (`SYSTEM_ALERT_WINDOW`) with touch pass-through for quick prompting without leaving active apps.
5. **Integrated Sovereign Tool Suite:**
   Directly incorporates AIOPE-native tools:
   - **PIN-Protected LAN File Server:** Share selected directories over local WiFi with a built-in HTTP server.
   - **Network Scanner:** Discover local network devices, scan ports, and analyze gateway topologies.
   - **Device Tool Provider:** Manage contacts, calendar, alarms, battery, and app launching locally.
   - **Isolated PRoot Sandbox:** Run an on-device minimal Alpine Linux environment with package installation and command execution.
6. **16KB Page-Aligned Modern Packaging:**
   Native shared libraries (`.so`) are uncompressed and aligned to 16KB boundaries (`useLegacyPackaging = false`), fully conforming to Android 15/16 16KB memory page kernel requirements.
7. **100% Standalone Non-System Application:**
   Completely decoupled from custom OS daemons. Installs and functions cleanly as a standard user-space app on any AOSP, GrapheneOS, or Google-certified Android device (Android 8.0+ / API 26 to Android 16 / API 36).

---

## Download & Installation

Grab the latest signed, optimized APK release directly from GitHub:

[![Download Latest Release](https://img.shields.io/badge/Download-Agora--v2.2.0.apk-success?style=for-the-badge&logo=android)](https://github.com/XNet-NGO/Agora/releases/latest)

> [!TIP]
> **Signature Verification:** All official XNet APK builds are signed with our official release key:
> - **Owner:** `CN=Joshua Doucette, OU=Executives, O=XNet Inc, L=Boise, ST=Idaho, C=US`
> - **SHA-256 Fingerprint:** `1E:80:0F:BD:28:0F:9C:AC:DB:43:ED:F4:B3:09:01:75:8C:98:25:5D:7A:30:71:7E:2C:B0:5C:54:4A:CB:65:97`

---

## Core Capabilities

### 🎙️ On-Device Voice & Multimodal Interaction
- **Sherpa-ONNX Voice Dictation:** One-tap on-device dictation directly into the message composer. Models (SenseVoice / Whisper / Conformer) are downloaded on-demand and cached in app storage.
- **Gemini Live Multimodal Voice:** Real-time bidirectional conversational speech. Stream audio input/output simultaneously with ultra-low latency.
- **Live Video Streaming:** Stream front/rear camera video or real-time display frames (`MediaProjection`) during Live Voice sessions for visual Q&A and screen guidance.
- **Tool Calling in Voice:** The live model can invoke MCP tools, device utilities, or search queries mid-conversation and speak the results back to you.

### 🪄 System Assistant & Multi-Tasking Overlay
- **Default Assistant Role:** Set Agora as your device's Assistant app under **Settings → Apps → Default apps → Digital assistant app**. Invoke Agora by holding the home button or power key.
- **Floating Overlay:** A persistent, collapsible bubble that floats over any application. Features touch pass-through, voice capture, and instant text queries without switching away from your current workflow.

### 🧠 Bring-Your-Own-Key (BYOK) Cloud & Local Providers
Connect directly to any model provider using your own API credentials without intermediaries:
- **Cloud Providers:** OpenAI (GPT-4o, o1, o3-mini), Anthropic (Claude 3.5 Sonnet / Haiku / Opus), Google Gemini (Gemini 2.0 Flash / Pro / Live), DeepSeek (V3, R1), Qwen / DashScope, Groq, OpenRouter, OpenCode Go.
- **Local & Custom Endpoints:** Connect to Ollama, LM Studio, vLLM, or any custom endpoint compatible with OpenAI, Anthropic, or Google APIs.
- **Local ONNX Embeddings:** Fast on-device vector embeddings for past-conversation RAG and document context matching.

### 🛠️ Agentic Tool Ecosystem & Automation
- **Model Context Protocol (MCP):** Connect to local and remote MCP servers via stdio or SSE transports. Expose custom tools, APIs, and databases directly to the reasoning loop.
- **Past-Conversation RAG:** Vector-indexed retrieval across past chat histories to augment current prompts with relevant historical context.
- **Alpine PRoot Linux Sandbox:** Built-in terminal running a lightweight Linux rootfs directly on the device. Install packages (`apk add ...`), run Python scripts, and test code safely.
- **Scheduled Automation Tasks:** Define recurring scheduled tasks with cron or interval schedules via Android WorkManager.
- **Local File Server:** Share files and folders from your device over local WiFi with a PIN-secured web interface.
- **Network Scanner:** Discover devices and inspect services on your local LAN.

### 🌲 Non-Linear Conversation Trees & Context Capsules
- **Message Branching:** Edit or regenerate earlier messages without losing prior replies. Switch between branches at any point in the tree.
- **Token-Budget Compact:** Compress long conversations into compact context capsules with token limits ranging from 4K up to 1M tokens, preserving system instructions and the most recent message suffix.

---

## Getting Started

1. **Install the APK:** Download and install [Agora-2.2.0.apk](https://github.com/XNet-NGO/Agora/releases/latest).
2. **Add a Provider:**
   - Open Agora and navigate to **Settings (gear icon) → Providers**.
   - Select your provider (e.g. Google Gemini, Anthropic, or OpenAI) and enter your API key.
3. **Sync Models:** Tap **Sync Models** to fetch the latest available models for your account.
4. **Configure Speech & Assistant (Optional):**
   - **On-Device STT:** Go to **Settings → Speech-to-Text**, download a model (e.g., SenseVoice Small), and tap the microphone icon in the chat composer to dictate.
   - **Live Voice:** Go to **Settings → Live Voice**, enable Gemini Live, and pick a voice.
   - **Assistant Role:** Go to **Settings → Assistant**, tap **Request Assistant Role**, and set Agora as default.

---

## Architecture & Technology Stack

| Layer | Technologies |
| :--- | :--- |
| **Language & Toolchain** | Kotlin 2.0+, Kotlin Coroutines & Flow, Java 17/21 bytecode target |
| **UI Framework** | Jetpack Compose (Material 3), Compose Navigation, Coil (Async Images) |
| **Persistence** | Room 2.6 (SQLite vector schemas), Jetpack DataStore (Preferences & Credentials) |
| **Networking & Streaming** | OkHttp 4, Server-Sent Events (SSE), WebSockets (RFC 6455) |
| **On-Device Machine Learning** | ONNX Runtime (ORT 1.28.2), sherpa-onnx (Kaldi STT engine) |
| **Native Components (NDK)** | Android NDK r28c, CMake 3.22+, 16KB uncompressed JNI packaging (`agora_proot`) |
| **Serialization & Parsing** | `kotlinx.serialization` (JSON), Markdown/LaTeX math renderer |

For an in-depth architectural breakdown of message generation loops, tool providers, and database schemas, see **[ARCHITECTURE.md](ARCHITECTURE.md)**.

---

## Building from Source

### Prerequisites
- **Android SDK:** Platform SDK 36 (Android 16), Build Tools 36.0.0
- **Android NDK:** r28c (`28.2.13676435`)
- **JDK:** OpenJDK 17 or OpenJDK 21
- **Gradle:** Managed via bundled `./gradlew` (Gradle 9.5+)

### Build Commands

```bash
# Clone the repository
git clone https://github.com/XNet-NGO/Agora.git
cd Agora

# Verify Kotlin source code contracts (handwritten files <= 800 lines limit)
./gradlew verifyKotlinFileSize

# Build debug APK (includes developer diagnostics)
./gradlew assembleFdroidDebug

# Build optimized release APK (with R8 minification and resource shrinking)
./gradlew assembleFdroidRelease
```

Generated APKs are located at:
```text
app/build/outputs/apk/fdroid/release/app-fdroid-release.apk
```

---

## Privacy, Security & Secret Safety

- **Direct Endpoints Only:** Agora has **no** proxy servers, cloud telemetry relays, or usage trackers. Requests travel directly from your phone to your configured provider endpoints over TLS.
- **Zero Cloud Voice Relaying:** When using offline STT (sherpa-onnx), your voice data never leaves your device's memory.
- **Hardware-Backed Credential Storage:** API keys and sensitive tokens are encrypted using an Android Keystore AES-GCM envelope before persistence.
- **Local Memory & Chat Storage:** All chat logs, vector embeddings, attachments, and settings reside in private app sandbox storage (`/data/data/com.newoether.agora/`).

Read our full [Privacy Policy](PRIVACY.md) for complete details.

---

## Contributing & Upstream Synchronization

We welcome issues and pull requests from the community.

- **Upstream Synchronization:** We track and cherry-pick non-conflicting improvements, bug fixes, and provider additions from upstream `newo-ether/Agora`.
- **Code Standards:** All handwritten Kotlin files strictly adhere to the project's **800-line size limit**, enforced by `./gradlew verifyKotlinFileSize`.
- **Privacy Contract:** No PR will be merged that introduces third-party tracking, telemetry, or analytics.

---

## License

Agora is licensed under the **GNU General Public License v3.0 ([GPL-3.0](LICENSE))**.

- Releases v2.2.0 and later are licensed under GPL-3.0.
- Historical releases v2.1.0 and earlier remain under their original MIT license.
- Portions derived from upstream `newo-ether/Agora` are copyright by their respective authors under GPL-3.0.
- Bundled native libraries and third-party tools retain their respective licenses (ONNX Runtime: MIT; sherpa-onnx: Apache 2.0; Termux PRoot: GPLv3).

---

<div align="center">
  <b>Built with sovereignty by <a href="https://github.com/XNet-NGO">XNet Inc. / Foxnet Labs</a></b>
</div>
