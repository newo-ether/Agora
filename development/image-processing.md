# Image Processing Contract

Status: authoritative owner requirements, 2026-10-27. These are acceptance criteria for the
implementation, not a statement that every existing path already conforms.

## Scope and required behavior

All images that Agora prepares for its own model requests use one processing policy and one
shared implementation. User attachments, camera and clipboard images, WebUI uploads, tool
results, generated images, Phone screenshots, PDF page rasters and video frames receive the
same treatment. Origin, tool name, transport and UI location cannot select a separate quality,
size, encoding or token-accounting path.

The owner requires three invariants:

1. Every image uses the same processing path, including images produced by tools.
2. Preview dimensions equal transmitted dimensions. Preview and dispatch reference the same
   completed image artifact and pixel content.
3. Image token estimates use the actual transmitted dimensions, with the most accurate
   reproducible model rules available. Encoded file size is not a substitute for pixel size.

External Remote sessions retain their independent execution and media ownership defined in
[remote-sessions.md](remote-sessions.md). Loading a Remote image preserves its received bytes,
encoding and dimensions. It does not invoke Agora's model-input image processing or apply
Agora Generation image-quality settings. A display decode may fit the viewport without
rewriting the stored or received image. Filo's cross-server raw attachment upload retains its
existing transfer checksum protocol; removing Agora's local image digests does not change that
external protocol or general transport authentication.

## Durable reference reclamation

`AttachmentOrphanSweeper` uses the derived Room attachment-reference index. Source transactions
invalidate only changed message/draft owners with fresh tokens; inserts, imports, updates, and
cascading deletes share those triggers. Schema upgrade creates empty index tables without reading
message bodies. Admitted maintenance seeds legacy identities with indexed continuation pages,
reads and decodes one owner's payload outside write transactions, and publishes references only
if that owner's token still matches. Completion/cursors survive cancellation and process death.

Exact reclamation waits for complete legacy coverage and no dirty owners. Its short write
transaction checks the source revision, looks up the canonical path index, and performs the
existing live-owner check and unlink under the attachment ownership lock. A concurrent source
change defers the debt; a durable or live reference protects the file. A sweep with any deferred
candidate remains retryable, so completing other files never discards unfinished reclamation debt. Malformed candidate metadata
retains the existing fail-closed behavior. Repeated checks reuse the completed index and never
search every message's JSON for a path token. Files, drafts, and messages remain authoritative;
this index owns no independent attachment lifetime or new startup admission.

## Processing and artifact ownership

The shared image processor owns dimensions, orientation, resizing, encoding and validation of
completed image artifacts. Existing attachment import and ToolImageStore owners retain
publication, cancellation and file lifecycle responsibility. PDF and video owners produce
raster inputs through this same boundary; they cannot maintain independent output-quality
constants. PDF's opaque-white initialization remains required by application-ui.md.

Each completed artifact has one path, actual MIME type, measured width and height and encoded
byte length. Image metadata, preview/cache keys and request validation do not contain or compute
SHA or another content digest. Old serialized image hash fields are ignored on read and omitted
from new writes. Metadata describes the output bytes, never the source file's extension, MIME,
dimensions or length. Every PDF page and video frame has its own descriptor.
Reordering, merging, deduplicating or projecting images must carry the matching metadata.

An artifact is published only after encoding and validation complete. Preview, request
serialization and token accounting consume that completed artifact. Base64, JSON and transport
encoding may wrap its bytes but cannot resize or recompress it. Retries and later context
replay reuse it without accumulating additional lossy encoding.

Existing conversation-owned attachment import and exact reference ownership remain authoritative.
A shared processor is not another draft, Room graph, lifecycle or cleanup owner. Interrupted
processing must not publish partial output or replace a valid previous artifact. Source retention
and cleanup must follow the owning attachment contract; this contract does not authorize a
new permanent-original archive or broader deletion.

Quality choices and numeric defaults belong to `ImageQualitySettings`; source owners do not
maintain their own quality constants. Settings / Generation / Image Quality saves immediately.
Light uses a 1024 px longest edge and JPEG quality 75, Balanced uses 2048/80 and is the default,
and High uses 4096/95. Custom retains an independent longest-edge value in 128..8192 px and
JPEG quality in 1..100, initially 2048/80. Switching presets preserves the retained custom values.
Unknown stored or imported modes normalize to Balanced; integers clamp to their valid ranges.

Each new image operation reads one durable settings snapshot. An entire PDF-page or video-frame
batch uses its initial snapshot even when preferences change during processing. A later operation
uses the new values. Completed artifacts, including previewed attachments, accepted/queued requests,
history and retries, keep their existing bytes and dimensions when quality settings change.
Validation checks measured artifact metadata and readability independently of the current preset.
The processor preserves alpha with PNG, normalizes orientation and never enlarges an existing
raster. JPEG quality applies only to opaque JPEG output. PDF points are rendered at the selected
raster bound. Settings changes do not create a permanent original archive, trigger global migration
or introduce hidden request-time re-encoding. Portable fields follow `import-export.md`.

`ImageArtifactProcessor` is the shared pixel/encoding implementation. `ImageArtifact` carries
its result through attachment metadata and tool image slots. `ImageArtifactHydration` resolves
legacy selected-owner files without re-encoding; Provider serializers check readability and send
the completed bytes. Native and WebUI preview owners consume those same artifacts.

## Preview and dispatch identity

Composer previews, message media, tool previews, full-screen viewers and WebUI read the same
artifact that the associated model input uses. Viewport size in dp, center cropping inside a
thumbnail and bounded display decoding are presentation operations; they do not define a
second image artifact or change transmitted pixels. Full-screen viewing must not silently
substitute an unprocessed original for the transmitted image.

A change in processing settings cannot leave a preview of one artifact beside an estimate or
request for another. Regeneration of a pending artifact, when applicable, completes before
its replacement is published. An accepted request retains its exact image membership and
artifacts through queueing, execution and retries.

Native restore, fork and legacy reads preserve these identities across message image paths,
attachment metadata and tool-image references. Compatibility work is restricted to the
selected/admitted owner and the existing demand-driven loading boundaries. No application
startup scan or request-only hidden re-encoding is allowed. Missing, unreadable or corrupt images
keep their original UI slots and use the existing broken-image icon. Sending silently omits those
image occurrences while preserving valid images and ordinary text. It must not report an image
error, insert a warning into the request, or fail the whole request because an image is damaged.
Readable historical images are measured from their current bytes; differences from old dimensions,
MIME, length, encoding or orientation metadata do not create a content-identity rejection.

## Token accounting

Count every valid image occurrence in the final Provider-visible projection, including synthetic
USER images from tool results and legacy assistant images. Unavailable or corrupt images omitted
from dispatch contribute no image tokens. Resolve each sent image's measured artifact
dimensions independently; a first-page size or source-byte average cannot represent a set.

Resolve the actual selected model identity and the image detail/resolution mode sent by the
request. An OpenAI-compatible transport does not establish an OpenAI model. Documented rules
must include server resizing, patch or tile rounding, multipliers and applicable limits.
Keep the rule's scope and primary source traceable. A documented calculation remains an
estimate unless exact request usage is returned by the Provider.

Unknown rules use an explicitly identified dimension-based heuristic. Missing dimensions
remain unknown until the existing media owner resolves them; encoded JPEG length or an
unlabelled fixed value cannot masquerade as a measured estimate. Do not claim a universal
upper bound when the Provider's rules do not establish one. Safety margin and raw estimate
remain distinguishable.

Context display, rollout and automatic Compact eligibility use the same main-request image
projection and counting configuration. A Compact model's own image policy applies to its
summary request only. Stored historical usage cannot replace the current request estimate.

## Required verification

Verify all input families through the shared processor; exact resize boundaries; unchanged
small-image dimensions; orientation and alpha; real output MIME and bounds; preview/dispatch
byte identity; and reuse without a second lossy encode. Test mixed-size PDF pages and video
frames, tool-image USER projection, image reorder/deduplication and per-occurrence counting.

Restore and fork tests must prove coherent paths and metadata, unavailable-media behavior,
reference-safe cleanup and no unrelated-owner reads. Remote image tests must prove byte and
dimension preservation regardless of Agora quality settings. Model-rule tests use official
examples and cover unknown identities, extreme aspect ratios and actual request detail mode.
Production request captures must prove that the counted artifacts are the transmitted ones.
Automated evidence does not establish real-device visual acceptance or paid API accuracy.
