# Vendored ONNX Runtime (self-built, 1.28.2)

These are prebuilt ONNX Runtime binaries used by Agora's on-device ML (currently the ONNX
embedding engine; a sherpa-onnx STT feature is planned to reuse the same native lib).

## Files
- `onnxruntime-1.28.2.jar` — the `ai.onnxruntime` Java API (OrtEnvironment / OrtSession /
  OnnxTensor …). Replaces the Maven `com.microsoft.onnxruntime:onnxruntime-android`.
- `../src/main/jniLibs/arm64-v8a/libonnxruntime.so` — ORT native core.
- `../src/main/jniLibs/arm64-v8a/libonnxruntime4j_jni.so` — ORT Java/JNI bridge (matched pair).

## Provenance
- Version: **ONNX Runtime 1.28.2**, built from source.
- ABI: **arm64-v8a** only (Agora release is arm64-v8a only).
- Toolchain: Android NDK **r28c**, min API 26, stripped (ELF aarch64).
- Origin: vendored from the sibling XNet project **AIOPE** (`core-inference`), where the same
  pair was self-compiled specifically to be **version-matched to sherpa-onnx v1.13.8** (which
  bundles ORT ~1.27.1). ORT's C ABI is stable across 1.27→1.28, so one `libonnxruntime.so`
  serves both the Java ORT API (embeddings) and sherpa's JNI (future STT) in one process —
  avoiding the two-`libonnxruntime.so` conflict that a stock Maven AAR + sherpa would cause.

## Why vendored (not Maven)
Using the stock Maven `onnxruntime-android` would pin a different ORT version than sherpa's
bundled core; an APK can only load one `libonnxruntime.so`, so mismatched Java/JNI/native
versions break. The matched self-build is the reconciliation.

## Packaging
`app/build.gradle.kts` sets `jniLibs.pickFirsts += "**/libonnxruntime.so"` so a single native
lib wins if another dependency also ships one.
