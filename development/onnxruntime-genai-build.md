# Build spec: matched onnxruntime-genai for Agora (Phase 2 local chat)

Agora vendors ONNX Runtime **1.28.2** (arm64-v8a) for on-device embeddings, matched to
sherpa-onnx. Phase 2 (local LLM chat on ONNX GenAI) must reuse that **same**
`libonnxruntime.so` — an APK loads only one into the process. So the GenAI artifact has to be
built against ORT 1.28.2, not a different version.

## What to build (on a machine with Android SDK + NDK r28c)

```
git clone https://github.com/microsoft/onnxruntime-genai
cd onnxruntime-genai
git checkout v0.17.0            # or the chosen genai version

# Point GenAI at ORT 1.28.2 so its bundled libonnxruntime.so matches Agora's.
# (GenAI's build.py resolves an ORT; supply the 1.28.2 headers+libs we already use —
#  the same build that produced app/src/main/jniLibs/arm64-v8a/libonnxruntime.so.)
#  See genai build docs: --ort_home <dir with ORT 1.28.2 include/ + lib/>.

python build.py --build_java --android \
  --android_home "$ANDROID_HOME" \
  --android_ndk_path "$ANDROID_NDK_HOME" \
  --android_abi arm64-v8a \
  --config Release \
  --ort_home <path-to-ORT-1.28.2>

# Output AAR:
#   build/Android/Release/src/java/build/android/outputs/aar/onnxruntime-genai-release.aar
```

## What to extract from the AAR and host in XNet-NGO/deps

Unzip the AAR and publish these as release assets (tag e.g. `onnxruntime-genai-0.17.0-ort1.28.2`):

- `classes.jar` → rename `onnxruntime-genai-0.17.0.jar` (the `ai.onnxruntime.genai` Java API:
  `Model`, `Tokenizer`, `TokenizerStream`, `Generator`, `GeneratorParams`, `Config`).
- `jni/arm64-v8a/libonnxruntime-genai.so` (the GenAI native lib).
- `jni/arm64-v8a/libonnxruntime.so` — **MUST be the ORT 1.28.2 build**. If it is byte-identical
  (or ABI-identical) to Agora's existing vendored one, Agora keeps only one copy and `pickFirst`
  resolves it. If GenAI insists on its own, confirm it is 1.28.2 so the ABI matches sherpa.

## How Agora will vendor it (Phase 2, drop-in)

- `app/libs/onnxruntime-genai-0.17.0.jar` (Java API) + README provenance.
- `app/src/main/jniLibs/arm64-v8a/libonnxruntime-genai.so` (git-tracked via the same .gitignore
  negation as `libonnxruntime.so`).
- Do NOT add a second `libonnxruntime.so` — reuse the embedding one. `pickFirsts "**/libonnxruntime.so"`
  already handles any duplicate.
- A matched chat model in GGUF→ONNX form (e.g. Phi-3.5-mini-instruct or Qwen2.5-1.5B-instruct,
  INT4, built with the GenAI model builder) hosted in XNet-NGO/deps, downloaded at runtime to
  `filesDir/models/<chat>` like the Bekko embedding model.

## Why this matters (the version-conflict Agora must avoid)
Using a stock Maven GenAI AAR (built against a different ORT) would put two incompatible
`libonnxruntime.so` in one process → the Java ORT API (embeddings) or GenAI's JNI breaks. The
matched 1.28.2 build is the reconciliation, same approach AIOPE used for sherpa.

## Agora integration plan (drop-in once the jar+so are vendored)

GenAI Java API (`ai.onnxruntime.genai`, confirmed from the API docs):
`Model(modelPath)`, `model.createTokenizer()`, `tokenizer.encode(text)` / `decode(ids)` /
`createStream()`, `TokenizerStream.decode(token)` (incremental text), `model.createGeneratorParams()`,
`GeneratorParams.setSearchOption(name, double|bool)` and `setInput(Sequences)`,
`Generator(model, params)` with `isDone()` / `computeLogits()` / `generateNextToken()` /
`getLastTokenInSequence()`.

New `api/OnnxLlmEngine.kt` (streaming loop):
```
val model = Model(modelDir)                      // dir with model.onnx + genai_config.json + tokenizer
val tokenizer = model.createTokenizer()
val stream = tokenizer.createStream()
val params = model.createGeneratorParams().apply {
    setSearchOption("max_length", maxTokens.toDouble())
    setSearchOption("temperature", temperature.toDouble())
    setSearchOption("top_p", topP.toDouble())
}
val sequences = tokenizer.encode(renderedPrompt)
val gen = Generator(model, params)
gen.appendTokenSequences(sequences)              // (API name per genai version)
while (!gen.isDone()) {
    gen.generateNextToken()
    val tok = gen.getLastTokenInSequence(0)
    emit(stream.decode(tok))                     // incremental delta -> StreamEvent.Delta
}
```
- Replaces `LocalProvider`'s `LocalModelRuntime.runChat` / `LlamaChatEngine`. Keep the single
  process-wide resident model + FIFO admission concept from `LocalModelRuntime`.
- `LocalChatModelConfig.localFilePath` (a GGUF file) becomes an ONNX model **directory**.
- Bootstrap an ONNX chat model dir from XNet-NGO/deps. Target: a **Qwen3.6 instruct** model in
  the **0.5B–0.9B** range (`qwen3.6-*`, ~0.6B), built to ONNX **INT4** via the GenAI model builder
  (`python -m onnxruntime_genai.models.builder -m <Qwen3.6-0.6B-Instruct HF id> -p int4 -e cpu
  -o <out>`; GenAI 0.16+ added Qwen3.6 export support). Produces a model dir with
  model.onnx(+.data), genai_config.json, and tokenizer files (~300–500MB). Downloaded at runtime
  like OnnxModelBootstrap (its own tag in XNet-NGO/deps, marker + size floor).
- Deps: `implementation(files("libs/onnxruntime-genai-<v>.jar"))`; the `.so` goes in jniLibs
  (git-tracked via the .gitignore negation); reuse the single vendored `libonnxruntime.so`.
- Verify on-device: a short local chat streams tokens, no second libonnxruntime.so / no
  UnsatisfiedLinkError.

## Phase 3 (after Phase 2 verified): delete llama.cpp
Remove `LlamaEngine`, `LlamaChatEngine`, GGUF paths in `LocalModelRuntime`/`LocalProvider`,
the llama CMake native module, `thirdparty/llama.cpp` (~152 MB), and GGUF chat settings/catalog.
Keep ONNX (embeddings + chat) and proot. Full gates + on-device.

