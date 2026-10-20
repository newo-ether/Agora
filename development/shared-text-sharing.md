# Incoming Android shared content

Implemented 15 October 2026. `ShareTextActivity` is an exported `ACTION_SEND` / `ACTION_SEND_MULTIPLE`
target for any MIME type. Ordinary inline text, including text/plain and text/html, remains supported. `EXTRA_TEXT` is copied before the receiver finishes. Incoming text/plain files can
instead use `EXTRA_STREAM` with a granted content URI. The stream takes precedence over an optional
inline caption. `EXTRA_TITLE` or `EXTRA_SUBJECT` provides a fallback attachment name. Inline text is
limited to 512 KiB; oversize/empty input fails visibly without truncation. It remains an attachment; the user's composer text is not overwritten.

The destination chooser follows the existing Notes inbox UI: choose a new-chat workspace or an
existing conversation. `ConversationComposerController.attachPreparedMany` owns the ordinary CAS
transaction. A generated request/local attachment ID and a persisted selected owner make rotation,
process recreation and retry idempotent; a failed/frozen draft commit remains retryable. A completed
import selects the target chat without submitting a message or starting model generation.

Optional `org.koreader.extra.EXCERPT_METADATA` is JSON with `schema=org.koreader.excerpt/v1`, `title`,
`bookTitle`, `authors` and `thumbnailBase64`. KOReader TOC-share v0.6.0 emits it through stock Android
JNI and a normal platform chooser. It still emits ordinary text/title/subject for other receivers.
No package-specific launch intent or sibling-repository dependency is introduced.

Metadata is untrusted. Labels are bounded; the JSON is bounded to 140,000 characters, cover Base64
to 120,000 and decoded bytes to 90,000. JPEG/PNG signatures and dimensions (at most 2048×2048) are
checked before decode/re-encode to an owned JPEG. Missing/malformed covers do not lose the text.
The thumbnail is purely display metadata, with no provider image index. Sender covers fit
160×220 pixels. Standard Android content-URI previews in ClipData (or Intent.data) are copied through the granted content resolver with the same byte/signature/dimension limits. URI failures preserve the text; remote URL covers are never fetched. KOReader also supplies this granted URI for the system Sharesheet preview.

`SharedTextInboxTest` verifies ordinary shares, provenance/cover persistence and model payload,
invalid/oversize input, stable retries and manifest registration. Native backup policy tests cover
draft/message thumbnail transport. Ordinary Composer prepared-attachment tests retain CAS, frozen
submission and existing-text behavior. The user verified a fresh Android 14 TOC share on 15 October 2026: Agora displayed the cover and retained chapter title, book title, author and a private cover path before Send. Broader device behavior remains user testing.

User-facing share labels use Android resources with English fallback entries in the maintained locales. No new translated copy is claimed.

Android URI preview guidance: https://developer.android.com/training/sharing/send. The receiver copies granted bytes before the sender exits; the durable draft owns its private copy afterward.

## Destination dialog presentation

Only the incoming-share destination dialog changes. Reuse Agora's shared AlertDialog, Text,
buttons, option-row highlight and e-ink list navigation. Its existing e-ink outline, white surface,
black text and stationary presentation provide the boundary missing from the earlier raw Material
dialog when EINK mode is enabled. Normal mode retains standard Material dialog styling. A small cover sits to the left of the excerpt title, book title and author. New chat is
a distinct full-width action; existing chats are aligned, divided touch rows in a bounded lazy
list. E-ink page controls and Cancel stay outside that scrolling list. Draft selection and
attachment ownership remain in SharedTextIncoming; opening the dialog never chooses a draft.

References: [KOReader UI guidance](https://koreader.rocks/user_guide/#L2-uitips),
[Supernote toolbar](https://support.supernote.com/en_US/introduction-to-the-toolbar), and
[Kobo reading menu](https://help.kobo.com/hc/en-us/articles/360020494854-About-the-Kobo-eReader-Reading-Menu).
The visual choices apply Agora's existing component contract rather than adding global styling.


## Large text files

KOReader 0.6.0 sends up to 256 KiB inline and automatically switches larger book/chapter text to
an owned UTF-8 text file. Android receives only its FileProvider URI in EXTRA_STREAM plus small
metadata. Both the text and cover URI are present in granted ClipData. Export files expire after
24 hours; receivers must retain their own bytes during the incoming Activity's permission lifetime.

SharedTextInbox admits content URIs for text/plain only and delegates copying/preparation to the
existing AttachmentImportProcessor. It uses the same streamed 100 MiB attachment bound and ordinary
file processing as the in-app picker. The complete copied file is moved into the request's private
receipt directory before the Activity finishes. Retries use that owned copy and stable request ID.
URI failures never import an optional caption as a substitute for the requested file.

The ordinary file importer prepares at most 500,000 characters for chat context. It is not a
provider-native whole-file upload path. This change retains that policy and the complete original
file, while explicitly disclosing a shortened context in the destination dialog, serialized
attachment warning and prepared text. The detection reads only the bounded prefix plus one
character; it does not load a multi-megabyte book into Room attachment JSON. Model context limits
remain separate from Android transfer and attachment storage. No EPUB parser is added to Agora.

Host coverage includes a 4 MiB text file copied byte-for-byte, source access ending before retry,
stream precedence over captions, UTF-8/source title/author/cover preservation, untrusted schemes,
unreadable streams and visible context-limit disclosure. Physical large-file sharing remains a
user acceptance check.


## Files shared from any app

The Android share target accepts single or multiple files from file managers and other apps,
without a KOReader dependency or MIME allowlist. EXTRA_STREAM is authoritative; ClipData and
Intent.data URI fallbacks support senders without the standard extra. Inputs are granted content
URIs, not raw filesystem paths or remote URLs. At most 32 distinct files are admitted, matching
the existing grouped Composer CAS boundary; each file uses the ordinary 100 MiB streamed limit.

SharedFileInbox resolves the provider's MIME type/name/size and reuses FileValidator's picker
routing and AttachmentImportProcessor.stage. Images, PDFs and videos enter their existing
preparation/configuration flows after draft selection. Other types use the existing Local Sandbox
route; receiving a file does not enable tools, install a runtime or broaden model capabilities.
Existing build/runtime requirements and model capabilities still apply.

The complete group is copied into the private share receipt before sender grants expire. A copy
failure removes incomplete owned files and creates no group receipt. Stable per-request file IDs,
a persisted group and destination, and the ordinary Composer CAS transaction support retry after
process recreation. The copied staged group is durably adopted before its existing processing jobs
start. Replay after preparation neither duplicates a file nor resets the user's processing choices.
The existing picker transient-ingress body is extracted without behavioral changes to keep the
controller within its source-size limit. No second processor, queue or generation owner is added.

The chooser displays the source file name and group count. It never sends a message automatically.
Host tests cover wildcard manifest matching, mixed-type multi-file routing, owned copies after source
removal, ClipData-only input, failed-group cleanup, URI rejection, CAS failure and processing replay.
Physical CX File Explorer shares remain a user acceptance check.


## File name and optional thumbnail presentation

Raw filenames use Android's single-line middle ellipsis in the destination dialog, keeping both
ends (including the extension) visible within the available width. There is no separate format
label. Excerpt titles retain their existing wrapping behavior, and stored filenames are unchanged.

While sender access is still granted, SharedFileThumbnail first requests the provider thumbnail
on Android 10+. If unavailable, it can decode a bounded image or find an embedded raster EPUB
cover from the container/package metadata (EPUB 3 cover-image, EPUB 2 cover ID, or a cover-page
reference). ZIP entries are read directly with bounded inflated byte limits; no archive is extracted
or remote resource fetched. XML document declarations are rejected and image dimensions are checked
before sampled decode. The owned preview fits 160×220 and at most90KB. Failures preserve the file.

Source previews survive the existing Local Sandbox attachment projection as display metadata;
they do not become model image inputs. No file routing, tool permission, execution or global
e-ink styling changes are introduced. References: [EPUB package cover metadata](https://www.w3.org/TR/epub-33/)
and [Android middle ellipsis](https://developer.android.com/reference/kotlin/androidx/compose/ui/text/style/TextOverflow).

## Outgoing focused Markdown (19 October 2026)

The owner keeps the existing Share icon and transcript workflow unchanged. A separate Checklist
icon (Choose content) beside the answer actions opens a white/black paged selection preview.
The existing answer Copy projection supplies a read-only snapshot; no transcript title, User or
Assistant wrapper is added. It has no conversation mutation authority and is disabled with other
terminal actions while generation is live. GFM source ranges form tap-selectable blocks; code
fences/tables remain intact and top-level list items can be selected independently. All starts
selected. Section selection covers a heading through the next peer/ancestor heading. Optional
per-block text edits affect only the shared copy. Share uses the existing Android transport,
including its file fallback for large Markdown; recipients remain user-selected. No AI or
Notes-specific protocol is involved. Physical e-ink acceptance remains user-owned.
