> 18 October 2026: Notes integration has been removed. Notes-specific behavior and test counts
> below describe the earlier qualification, not the current build. See [sharing boundary](notes-integration.md).

# On-demand tool capability verification

## Scope

The existing provider definitions and permission checks remain authoritative. Ordinary requests defer
Notes, interactive answers, saved templates, memory, skills, past conversations, web and structured
questions behind one `load_tool_capability` function. Shell, image generation, automation and external
providers retain their established visibility policy; this change does not claim to defer them.
No system-prompt injection, provider-specific discovery API, file index, routing model, lifecycle owner
or alternate execution loop is introduced.

A successful category selection enters the existing durable tool round. The next provider pass
rebuilds its messages, schemas, budget and authorization together. Activation lasts until the next
real user input and is reconstructed from only the selected branch's successful discovery results.
Notes attachments enter directly. Background Notes processing retains its existing scoped tools.
Markdown Notes pages retain the existing single Markdown-return function. Local template menus and
Run use the existing native path, including selected-resource attachment.

Notes organization no longer advertises a second handwriting-conversion operation. Recognition and
semantic editing remain distinct existing tools; existing proposal parsers and saved data remain
compatible. Explicit recognition-then-organization retains the existing staged workflow.

## Focused evidence

- `ToolCapabilityLoadingTest`: compact definitions; relevant category selection; multiple selections;
  turn/path isolation; malformed, unavailable and disabled requests; failed/mismatched results;
  direct Notes attachment; background Notes visibility.
- `GenerationCapabilityRequestTest`: actual executor authorization, ordinary durable tool round and
  provider-path reconstruction; the initial pass cannot execute a guessed deferred tool; the next
  pass contains the selected schema with its exact fixed cost; reconstruction, permission removal,
  new user input, sibling branches and Low Context Mode retain their defined behavior.
- `ConversationContextProjectorTest`: accounting receives the actual durable selected path, including
  tool protocol payloads; existing stale-completion, cancellation, branch and failure checks remain.
- `NotesHostWorkflowTest`: organization/recognition responsibilities are distinct. Existing Notes
  document, recognition, return, background and attachment tests cover compatibility.

The 81-test focused F-Droid run passes. The packaged Chromium viewer tests, build-logic tests and
strict MkDocs build pass.

## Fixed-input estimate

For the audited configuration with all eight categories enabled, the previous 23 tool schemas cost
3,717 estimated tokens. The new initial discovery schema is approximately 159: a 95.7% reduction in
initial tool-definition overhead. The 608-token system prompt is unchanged. These are source-derived
estimates using the shipped o200k vocabulary, framing and existing 10% margin, not provider billing
or a new on-device capture. Other enabled providers add their own definitions. Selecting a capability
adds its detailed schemas and the ordinary discovery protocol result to subsequent requests.
Discovery can add a model round trip; direct native entry avoids that step when its context is known.
No paid model request was made during qualification.

## Device acceptance

Physical e-ink, live dictation, Notes pen behavior and an actual provider OSCE remain user tests.

## Full qualification

- F-Droid: 3,212 tests, zero failures/errors, five existing optional skips.
- Play: 3,195 tests, zero failures/errors, five existing optional skips.
- Both debug APKs and the signed ARM64 F-Droid preview assemble successfully.
- `verifyKotlinFileSize` passes with no baseline exceptions.
- The complete Gradle qualification completed successfully in 4 minutes 14 seconds.

Reproduce with the maintained Android SDK/JDK environment:

```sh
./gradlew :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest \
  :app:assembleFdroidDebug :app:assemblePlayDebug :app:assembleFdroidRelease \
  verifyKotlinFileSize --no-daemon --max-workers=2
./gradlew -p build-logic test
node scripts/test-interactive-viewer.cjs
mkdocs build --strict
```

The device preview uses the existing application ID suffix, signing identity and ARM64 delivery
configuration. Installation uses replacement mode to retain existing app data. Installation is not
physical-device or model-response acceptance.
