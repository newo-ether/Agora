# Encrypted agent transport

The API key is the intentional authentication root. Compromise of that key or of
its authorized holder is outside the network-attacker threat model; do not add
extra authentication steps to compensate for that design choice. Windows service
credential files, backups and secret-bearing registry keys allow only Administrators
and SYSTEM. Network attackers are assumed able to observe, replace, delay, repeat,
truncate and redirect HTTP traffic without knowing the key.

Conch and Filo must validate the complete authenticated request before native work.
An unsigned HTTP error cannot prove non-execution or authorize a mutating retry.
Encrypted streams must authenticate event kind and sequence as well as bytes. Missing,
duplicated, reordered, cross-request and post-terminal frames cannot become success.
Authenticated `http_end` is necessary but not sufficient: the reader also confirms
HTTP response-body EOF before returning successful EOF. A second terminal frame,
any trailing frame or garbage bytes is an error in both Go and Android clients.
Neither redirects nor a connection failure may transparently replay a command.
Encrypted file/job responses also require the server response HMAC, using a distinct
HKDF subkey of the request's fresh session key and binding status and ciphertext.
A valid request ciphertext reflected by a network intermediary is not a response.
Update the server before admitting the corresponding strict client; do not silently
downgrade response verification for old installations.

Read limits apply before UTF-8 conversion, JSON parsing or decryption, including
chunked bodies, decompression, error bodies and lines lacking a newline. Conch uses
64 KiB for handshake/errors, 40 MiB for encrypted file/job responses and 10 MiB for
a wire event line, matching its Go client. Limits are independent of Content-Length.
The Conch-specific client retains Agora's proxy configuration and stream cancellation
ownership; these rules must not change ordinary Provider transport behavior.

Qualification requires isolated HTTP fixtures for tampering, replay, timeout, framing,
read bounds and legitimate key rotation. A source fix, a passed focused test and an
installed fleet update are distinct statuses. Do not declare the old mobile parser
safe merely because a server can emit authenticated framing metadata.

## Shared Filo channel

Filo uses Conch's Go `crypto` and `encryptedhttp` packages without a separate
cryptographic implementation. Its public handler accepts an authenticated challenge
handshake and `/e2e/request`; a plain Bearer request cannot reach application code.
A verified encrypted envelope carries the method, path, query, content type and raw
body. Encrypted response frames carry HTTP status and ordered binary body chunks.
The server's encryption key is memory-only and new for every gateway lifetime.

Agora's default Filo client requires this channel. Read requests may cache a verified
server key and recover once using a fresh handshake. Mutations always perform a fresh
handshake and have no automatic retry. Upload chunks are bounded at 256 KiB, the
channel request at 512 KiB, and decrypted response chunks at 16 KiB. Images and event
streams use the same incremental encrypted transport. There is no plaintext fallback.
Application fixture tests inject a plain local transport explicitly so they can test
the API beneath encryption; production transport is covered by Go/Android fixtures.

Do not enable the new client against an old public Filo server. Complete Go service
assembly, native attachment consumption, full failure-isolation qualification and the
owner's all-features delivery gate before updating the installed computers and phone.
## Android provider compatibility

The HiBreak crash exposed use of an unavailable X25519 alias. Android 13+ uses the
platform's documented XDH name for key generation, decoding and agreement. Android
8–12 use a private instance of the already-bundled Bouncy Castle provider for X25519.
No global provider is registered or replaced. Wire keys, HKDF, authentication and
encryption requirements remain unchanged. Crypto initialization failures become a
channel IOException at the OkHttp interceptor boundary, so asynchronous calls report
connection failure instead of terminating the app; no mutation is retried. Qualification
covers native/default XDH, the older-Android implementation, unchanged global providers,
existing cross-language vectors, async failure delivery and the single device build.
