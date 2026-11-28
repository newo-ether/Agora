# Source size policy

Status: authoritative repository build policy.

Agora limits every handwritten Kotlin source file and maintained WebUI JavaScript/CSS file to at most
800 physical lines. Aim below 700-800 lines and split by responsibility before reaching the limit.
Never compress formatting or remove useful documentation to evade the budget.

`verifyKotlinFileSize` scans main, test, flavor and build-logic Kotlin sources. It excludes build
and generated output, caches and the vendored `thirdparty` tree. CRLF, LF and standalone CR each
count as one line boundary, so Windows and Linux produce the same result.

The migration is complete. `config/kotlin-source-size-baseline.txt` has zero entries and the
allowed exception set is empty. Restoring a historical allowance or adding a new one fails the
build. Every maintained Kotlin source is checked directly against the 800-line limit.

The root convention plugin wires the verification into Gradle `check`, the aggregate `test` task
used by the local `build.ps1`, and Android `preBuild`. GitHub Actions also invokes the task
explicitly before assembling the F-Droid APK.

`verifyWebUiFileSize` checks `.js`, `.mjs` and `.css` under `app/src/main/assets/webui`, including
maintained Material modules and newly added files. The third-party `vendor` subtree is excluded.
It uses the same physical-line counting and 800-line limit, with no baseline exceptions.
`verifyKotlinFileSize` depends on this task, so every existing local and CI gate also checks WebUI.

Run the policy tests and repository check with:

```powershell
.\gradlew.bat -p build-logic test
.\gradlew.bat verifyKotlinFileSize
```
