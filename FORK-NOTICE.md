# Agora fork: attribution and licensing

This is **josephm1's independently maintained fork** of
**[Agora](https://github.com/newo-ether/Agora)**, created by **newo-ether and contributors**.
The original application, architecture, branding and upstream work belong to their respective
authors. This fork adds interactive templates, native folder access, skills/workflow changes,
e-ink refinements, sharing, dictation and Remote integrations. It is not an official upstream
release and does not imply endorsement. Modified builds must identify their fork and provide
the corresponding source for the version actually distributed:
[public fork source](https://github.com/josephm1/Agora).

## Application licence

This fork retains upstream's **GNU GPL version 3** terms; the complete text is in [LICENSE](LICENSE)
and the packaged `app/src/main/assets/licenses/LICENSE-GPL-3.0.txt`. Copyright remains with
newo-ether and all other respective contributors. Preserve the original notices, identify
modifications and provide complete corresponding source and build scripts when distributing binaries.

Upstream v2.1.0 and earlier remain MIT-licensed. Their original notice is reproduced verbatim in
[LICENSES/Agora-historical-MIT.txt](LICENSES/Agora-historical-MIT.txt), sourced from upstream commit
`9fc92fc3518c880158111ae1e9534ed8ffd09c6d`. The current GPL application is not offered as MIT.
Upstream's separate name/logo/screenshot terms remain as described in its README; the code licence
does not represent trademark endorsement.

## Bundled third-party components

Keep the component licences and author notices already shipped in the source and assets:

- OpenIntelligentUI: Atai Barkai, MIT; `app/src/main/assets/interactive/OPENINTELLIGENTUI-LICENSE.txt`.
- Websandbox: Apache-2.0; `app/src/main/assets/interactive/WEBSANDBOX-LICENSE.txt`.
- Web UI Preact, HTM, Marked and KaTeX: individual licences under `app/src/main/assets/webui/vendor/`.
- llama.cpp and its vendored dependencies: `thirdparty/llama.cpp/LICENSE`, `AUTHORS` and component notices.
- PRoot, talloc and provisioned Termux/bootstrap tools: their upstream licences and corresponding
  source requirements remain in force; inspect the component sources and `build-proot.sh` for the
  exact payload built or provisioned. Gradle and other fetched dependencies retain their own terms.

This notice supplements those licences and notices; it does not replace or relicense them.
