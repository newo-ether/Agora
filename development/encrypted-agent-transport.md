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

## Agora Conch retries

Conch handshake GETs and read-only file/job requests silently use Agora's shared five-retry budget.
Read-only encrypted POST retries rebuild the complete envelope with fresh keys and nonce; never
resend a previously accepted encrypted envelope. The safe list is file read/image/glob/grep,
transfer capabilities/info/read/status, and job list/get. Mutating file/job requests and command stream opening retry only proven pre-connect
failures. Once a command may have been sent, neither a response error nor stream interruption
permits command replay. Authentication/framing failures and cancellation are terminal.
This policy does not change the Filo channel below.

## Binary file copies

`file_transfer` requires explicit source/destination server and absolute path pairs and an optional
boolean `overwrite` (false by default). It copies one regular file up to 1 GiB across Local Sandbox
and Conch endpoints; SSH is rejected. Destination writes use existing Shell confirmation, including
Local Sandbox shared-storage exposure. File bytes never enter model arguments or results.

Conch POST `/file/transfer/{capabilities,info,read,begin,chunk,commit,status,abort}` reuses the same
authenticated encrypted envelope. Chunks are 256 KiB, handler wire requests at most 1 MiB. Safe
read retries rebuild envelopes. `begin`, `chunk`, `commit` and `abort` retain mutation non-replay.
An uncertain chunk response is reconciled only if status proves the exact next accepted offset;
an uncertain commit succeeds only if status proves a matching committed receipt. Unknown or
expired commit status is an explicit inspect-before-retry error. Old endpoints return an update
Conch error, without command/text-file fallbacks.

The client downloads to a disk spool and verifies complete source size/SHA-256 before staging a
destination. Reads are stateless; this does not promise a frozen source handle. Both local and
Conch destinations verify private staged bytes, flush the file, then publish atomically. No-overwrite
uses a platform atomic create-if-absent operation. Android Local Sandbox uses native
`renameat2(RENAME_NOREPLACE)` without a weaker fallback; Android app domains prohibit hard links.
Linux Conch uses the same rename primitive, with atomic hard linking only if that operation is
unsupported; other Conch platforms retain atomic hard linking. Overwrite uses atomic replacement.
Missing parents, nonregular files and unsupported filesystem operations fail explicitly. Same endpoint and identical resolved
source path is an unchanged receipt; aliases that are not recognized still use safe copy semantics.
Success reports endpoint paths, byte count and both hashes. Sources are retained.

Cancellation deletes client spool/local staging and best-effort aborts remote staging, bounded to
five seconds; an already published destination stays complete. Conch retains at most 64 entries,
expires idle entries after 15 minutes with minute-granularity cleanup, and removes journaled orphan
staging at restart. Completion receipts are process-local. App process death may leave local temp
files; cross-restart resume and full power-loss transactions are not provided.

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
streams use the same incremental encrypted transport. Decrypted Filo application HTTP
bodies and SSE lines are bounded at 4 MiB before UTF-8 allocation; conversation pages
are at most 3 MiB. These application limits do not change channel frame/request limits.
There is no plaintext fallback.
Application fixture tests inject a plain local transport explicitly so they can test
the API beneath encryption; production transport is covered by Go/Android fixtures.

Do not enable the new client against an old public Filo server. Complete Go service
assembly, native attachment consumption, full failure-isolation qualification and the
owner's all-features delivery gate before updating the installed computers and phone.