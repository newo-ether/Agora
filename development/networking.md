# Networking Features Product Contract

Status: authoritative, 2026-10-14.

This contract owns Agora's two on-device networking features — the LAN **Network Scanner** and the
**File Server** — including their engines, settings surfaces, permissions, and privacy boundaries.
Both were ported from AIOPE and adapted to Agora's architecture (single-module, manual DI, pure
settings-screen features with no agent-tool surface).

## 1. Product boundary

- Both are **user-facing Settings screens** under the Network group. Neither exposes a generation
  tool: there is no `scan_network` or file-server `ToolProvider`, and neither participates in the
  conversation runtime, reducer, or `GenerationContext`. Adding an agent-tool surface is a future,
  separate decision and must not silently couple either feature to the generation pipeline.
- Both run entirely on-device over raw sockets. The **only** outbound internet call in either
  feature is the scanner's optional WAN-IP lookup (section 2.4). Everything else is LAN-local.

## 2. Network Scanner

Owner: `scanner/NetworkScanner.kt` (engine + observable `ScanState`), `scanner/NetworkScanNet.kt`
(socket/ARP/OUI/WoL/WAN helpers), `scanner/NetworkScanTables.kt` (port/service/UDP-probe tables),
`scanner/NetworkScanInput.kt` (pure host/port parsing + WoL magic packet),
`scanner/NetworkScanState.kt` (models), `ui/settings/SettingsScannerPage.kt` +
`ui/settings/SettingsScannerSheets.kt` (UI).

### 2.1 Discovery

- A `/24` sweep tests each address by ICMP reachability, falling back to a short TCP connect on a
  fixed liveness-port set. Alive hosts are unioned with the kernel ARP/neighbor table.
- MAC → vendor resolves from the bundled `assets/oui.txt`. When the ARP table is OS-restricted,
  MAC/vendor are simply blank — this is expected, not an error.
- Discovery is skipped on cellular-only connectivity.

### 2.2 Port and UDP scanning

- TCP is a connect scan; banners are grabbed only for scans of at most 1000 ports.
- UDP sends the fixed `UDP_PROBES` payloads and reports only ports that answer (reply-only; silence
  is never reported as open/closed/filtered).
- Only `open` results are recorded.

### 2.3 Custom scan, WoL, DNS

- Custom-scan host/port input is parsed by the pure `NetworkScanInput`: single IPs, last-octet dash
  ranges, and `/24` CIDR; ports as singles/ranges/blank(=all). The expanded host list is capped at
  `MAX_HOSTS` (1024). This cap is a safety invariant and must not be removed.
- Wake-on-LAN builds a standard 102-byte magic packet (6×`0xFF` + MAC ×16) and sends it as a UDP
  broadcast to port 9. A malformed MAC yields no packet and a failure result.
- Forward DNS (`dnsLookup`) and reverse DNS (`reverseDns`) are on-demand only; the sweep never
  populates `HostInfo.hostname`.

### 2.4 WAN IP

`fetchWanIp()` does an HTTPS GET to a public echo endpoint (ipify, falling back to checkip),
returning `Unavailable` on failure. This is the only off-LAN call and is invoked on screen open and
rescan. It must stay opt-in-by-screen-presence and must never transmit scan results anywhere.

## 3. File Server

Owner: `fileserver/FileServerService.kt` (foreground service + HTTP server),
`fileserver/FileServerHttp.kt` (pure response shaping), `fileserver/FileServerMultipart.kt`
(streaming upload parser), `fileserver/FileServerTls.kt` (ephemeral TLS),
`ui/settings/SettingsFileServerPage.kt` (UI).

- A `specialUse` foreground service (mirrors `WebUiService`): started only from the foreground, has
  no natural end, holds a WiFi lock, and shows an ongoing notification with a Stop action.
- HTTP/1.1 on a raw `ServerSocket`; optional TLS via an **ephemeral self-signed EC** cert
  (secp256r1 / SHA256withECDSA) regenerated per start — browsers warn once.
- Path traversal is blocked by filtering `.`/`..` URL segments; uploaded filenames are reduced to
  their last path segment. Uploads stream to a disk spool and are capped at `MAX_UPLOAD_BYTES`
  (2 GB) checked against `Content-Length`.
- Optional PIN gate via `Bearer`/cookie/`X-Pin`; it is a lightweight LAN gate, not strong auth, and
  must not be documented or presented as strong authentication.

### 3.1 Scoped-storage write boundary (invariant)

Writes are `java.io.File`-based. Under Android scoped storage, holding a SAF tree grant does **not**
confer filesystem write access. Uploads must therefore pre-check writability (`ensureWritableDir`)
and return an actionable error for folders Agora cannot write, never a raw `EPERM`/500. True
arbitrary-folder write would require a SAF/DocumentsContract rewrite or All-Files-Access; neither is
in scope, and All-Files-Access must not be added without explicit user confirmation (privacy
posture).

## 4. Permissions and privacy

- `ACCESS_WIFI_STATE` is required (scanner gateway lookup; file-server LAN IP / WifiLock). Every
  `WifiManager` call must be wrapped so a denied/revoked permission degrades gracefully and never
  crashes.
- No analytics or telemetry. Production log calls must not log raw exception messages
  (`PrivacySafeLoggingSourceContractTest`) — log exception types instead.

## 5. Ownership summary

- Pure, socket-free logic (`NetworkScanInput`, `FileServerHttp`, `FileServerMultipart`) carries the
  parsing/formatting/safety rules and is unit tested without the network.
- Engines/services own side effects and observable state only.
- Any material change to the product boundary (adding an agent tool, All-Files-Access, or removing
  the host cap / scoped-storage guard) requires explicit user confirmation and a contract update.
