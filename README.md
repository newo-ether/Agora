<div align="center">
  <img src="app/src/main/assets/agora_transparent_large.png" alt="Agora Logo" width="128" />

  # Agora (XNet Fork)

  **The Sovereign Android AI Companion — Realtime Gemini Live Voice/Video, On-Device STT, System Assistant Overlay, Modular Engine Architecture, LAN File Server, and Autonomous Device Workflows.**

  [![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
  [![Platform: Android](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026--36)-green.svg)](https://developer.android.com)
  [![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-purple.svg)](https://kotlinlang.org/)
  [![Release](https://img.shields.io/badge/Release-v2.3.2-blue.svg)](https://github.com/XNet-NGO/Agora/releases/tag/v2.3.2)
  [![Upstream Sync](https://img.shields.io/badge/Upstream-v2.3.2-blueviolet.svg)](https://github.com/newo-ether/Agora/releases/tag/v2.3.2)
  [![Page Size](https://img.shields.io/badge/16KB%20Page%20Aligned-Android%2015%2B-brightgreen.svg)](https://developer.android.com/guide/practices/page-sizes)

  [Latest Release](https://github.com/XNet-NGO/Agora/releases/latest) · [XNet Repository](https://github.com/XNet-NGO/Agora) · [Upstream Agora](https://github.com/newo-ether/Agora) · [Privacy Policy](PRIVACY.md)
</div>

---

## About This Fork

**Agora (XNet Fork)** is an independent, sovereign downstream fork of [`newo-ether/Agora`](https://github.com/newo-ether/Agora) maintained by **XNet Inc. / Foxnet Labs / XNet NGO**.

While upstream Agora established a pioneering Bring-Your-Own-Key (BYOK) multi-provider client, our fork expands Agora into a full **System AI Assistant** and **local multimodal platform**. Synchronized with upstream release **v2.3.2**, our fork inherits upstream's modular multi-module architecture (`core:model`, `core:contracts`, `core:presentation`, `data:database`, `provider:cloud`), Room DB v48, Shizuku background virtual display, WebUI tools sheet, and ChatGPT/DeepSeek provider accounts, while retaining and extending all of XNet's sovereign capabilities.

### Key Architectural Features

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Agora (XNet Fork) v2.3.2                         │
├──────────────────────────┬─────────────────────────────┬───────────────┤
│    Assistant & Voice     │     Modular Architecture    │  Local Tools  │
├──────────────────────────┼─────────────────────────────┼───────────────┤
│ • System Assistant Role  │ • core:model & contracts    │ • LAN Server  │
│ • Floating Overlay FGS   │ • core:presentation         │ • Net Scanner │
│ • Screen/Camera Vision   │ • data:database (Room v48)  │ • Device Tools│
│ • Gemini Live Voice/WS   │ • provider:cloud            │ • MCP Client  │
│ • sherpa-onnx 2.0.3 STT  │ • 16KB Page-Aligned JNI     │ • Alpine PRoot│
└──────────────────────────┴─────────────────────────────┴───────────────┘
```

1. **Gemini Live Bidirectional WebSocket Streaming:**
   Direct real-time two-way voice streaming via Google Gemini Live API (`BidiGenerateContent`) over WebSockets. Includes low-latency PCM audio recording (16 kHz) and playback (24 kHz) with acoustic echo cancellation (AEC), automatic speakerphone routing, and mid-turn agent tool execution (including MCP).
2. **Real-Time Camera & Screen Vision:**
   Stream camera frames and live screen captures (`MediaProjection`) directly into the Gemini Live multimodal loop from the chat composer or the assistant overlay.
3. **Android System Assistant Role & Multi-Tasking Overlay:**
   Full Android Assistant role integration (`android.service.voice.VoiceInteractionService`). Replace default platform assistants with Agora to trigger via home button or power key hold. Includes an optional floating multi-tasking overlay (`SYSTEM_ALERT_WINDOW`) with touch pass-through for quick prompting without leaving active apps.
4. **On-Device Speech-to-Text (Sherpa-ONNX 2.0.3):**
   Offline neural speech recognition powered by next-gen Kaldi / `sherpa-onnx 2.0.3`. Transcribe voice input locally in the composer with zero cloud audio transmission, persistent model caching, and warm preloading.
5. **Integrated Sovereign Tool Suite:**
   - **Local File Server:** Embedded HTTP/TLS file server with multipart uploads and PIN authentication for sharing folders over local Wi-Fi.
   - **LAN Network Scanner:** Subnet discovery, ARP/ND tables, gateway discovery, and OUI hardware vendor identification.
   - **Device Tools:** System introspection (battery, storage, memory, location, installed packages) for agent reasoning loops.
   - **Model Context Protocol (MCP):** Connect to local and remote MCP tool servers.
6. **16KB Page Alignment on Android 15+:**
   Uncompressed JNI packaging (`useLegacyPackaging = false`) ensures 100% compatibility with Android 15+ 16KB ELF page sizes on modern arm64 kernels.
7. **Strict Code Quality Contracts:**
   All handwritten Kotlin files strictly adhere to Agora's **800-line ceiling** enforced by `./gradlew verifyKotlinFileSize`, and all WebUI sources adhere to `./gradlew verifyWebUiFileSize`.

---

## Upstream Synchronization (v2.3.2)

Our fork tracks upstream releases while maintaining our custom enhancements:
- **Multi-Module Separation:** Clean boundary division between model definitions, database schemas, presentation components, cloud provider implementations, and the main app shell.
- **Provider Parity:** DeepSeek, ChatGPT, Anthropic, Google Gemini, Qwen/DashScope, OpenRouter, OpenCode Go, Groq, and Ollama.
- **Shizuku Virtual Display & WebUI:** Background virtual display capture via Shizuku, plus WebUI tools sheet.
- **Privacy & Security:** Disconnected third-party update checking; updates are distributed exclusively via official XNet GitHub releases.

---

## Download & Installation

Pre-built signed releases are available on GitHub:

[![GitHub Releases](https://img.shields.io/badge/GitHub-Releases-blue?logo=github)](https://github.com/XNet-NGO/Agora/releases)

1. Download **`app-fdroid-release.apk`** from the [latest release](https://github.com/XNet-NGO/Agora/releases/latest).
2. Install the APK on any Android 8.0+ device (API 26–36).
3. Grant optional permissions as needed (Microphone for STT/Live Voice, Display Over Other Apps for Floating Overlay).

---

## Building from Source

### Prerequisites
- **Android SDK:** Platform SDK 36 (Android 16), Build Tools 36.0.0
- **Android NDK:** r28c (`28.2.13676358`)
- **JDK:** OpenJDK 17 or OpenJDK 21
- **Gradle:** Managed via bundled `./gradlew` (Gradle 9.5+)

### Verification & Compilation

```bash
# Clone the repository
git clone https://github.com/XNet-NGO/Agora.git
cd Agora

# Verify Kotlin line count limits (<= 800 lines per file)
./gradlew verifyKotlinFileSize

# Verify WebUI source limits
./gradlew verifyWebUiFileSize

# Verify module boundary rules
./gradlew verifyModuleBoundaries

# Compile debug APK
./gradlew assembleFdroidDebug

# Compile optimized release APK (R8 minified)
./gradlew assembleFdroidRelease
```

Generated APKs are located at:
```text
app/build/outputs/apk/fdroid/release/app-fdroid-release.apk
```

---

## Privacy & Security

- **Direct Endpoints Only:** Agora has **no** proxy servers, cloud telemetry relays, or usage trackers. Requests travel directly from your phone to your configured provider endpoints over TLS.
- **Zero Cloud STT Audio:** Offline speech recognition (sherpa-onnx) processes voice entirely in device memory; no audio ever leaves the phone.
- **Hardware-Backed Credential Storage:** API keys and sensitive tokens are encrypted using an Android Keystore AES-GCM envelope before persistence.
- **Local Storage:** All chat logs, vector embeddings, attachments, and settings reside exclusively in private app sandbox storage (`/data/data/com.newoether.agora/`).

---

## License

Agora is licensed under the **GNU General Public License v3.0 ([GPL-3.0](LICENSE))**.

- Releases v2.2.0 and later are licensed under GPL-3.0.
- Historical releases v2.1.0 and earlier remain under their original MIT license.
- Portions derived from upstream `newo-ether/Agora` are copyright by their respective authors under GPL-3.0.
- Bundled native libraries and third-party tools retain their respective licenses (sherpa-onnx: Apache 2.0; Termux PRoot: GPLv3).

---

<div align="center">
  <b>Maintained by <a href="https://github.com/XNet-NGO">XNet Inc. / Foxnet Labs / XNet NGO</a></b>
</div>
