package com.newoether.agora.speech

/** Which sherpa-onnx offline model family a preset loads; decides the recognizer config shape. */
internal enum class LocalSpeechModelKind { SENSE_VOICE, FIRE_RED_CTC, QWEN3_ASR, NEMO_TRANSDUCER }

/** One file of a model, pinned by size and SHA-256 so either mirror yields identical bytes. */
internal data class LocalSpeechModelFile(val path: String, val size: Long, val sha256: String)

/**
 * A downloadable on-device recognizer. [repo] and [revision] address a Hugging Face repository at a
 * fixed commit, so the pinned digests in [files] never drift.
 */
internal data class LocalSpeechModel(
    val id: String,
    val displayName: String,
    val kind: LocalSpeechModelKind,
    val repo: String,
    val revision: String,
    val files: List<LocalSpeechModelFile>,
    /** Language hints this model accepts; empty means it always detects the language itself. */
    val languages: List<String> = emptyList(),
    /** True when the model accepts vocabulary hints (hotwords). */
    val supportsVocabulary: Boolean = false,
) {
    val totalBytes: Long get() = files.sumOf { it.size }
}

/** Download host. Both serve the same Hugging Face repositories; the pinned digests decide trust. */
internal enum class LocalSpeechSource(val id: String, val host: String) {
    HF_MIRROR("hf-mirror", "https://hf-mirror.com"),
    HUGGING_FACE("huggingface", "https://huggingface.co"),
    ;

    fun url(model: LocalSpeechModel, file: LocalSpeechModelFile): String =
        "$host/${model.repo}/resolve/${model.revision}/${file.path}"

    companion object {
        val DEFAULT = HF_MIRROR

        fun fromId(id: String?): LocalSpeechSource = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

internal object LocalSpeechModels {
    val all: List<LocalSpeechModel> = listOf(
        LocalSpeechModel(
            id = "sense-voice-int8-2025-09-09",
            displayName = "SenseVoice Small",
            kind = LocalSpeechModelKind.SENSE_VOICE,
            repo = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
            revision = "355f4d4884d8afd08aef04b9007a8556d7b463b2",
            files = listOf(
                LocalSpeechModelFile("model.int8.onnx", 237_115_547L, "12ca1a2ae7ecf3e0019ef2822307ee0b5cadc9196569e379b4c4026f8205276d"),
                LocalSpeechModelFile("tokens.txt", 315_894L, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"),
            ),
            languages = listOf("zh", "en", "yue", "ja", "ko"),
        ),
        LocalSpeechModel(
            id = "fire-red-asr2-ctc-int8-2026-02-25",
            displayName = "FireRedASR2 CTC",
            kind = LocalSpeechModelKind.FIRE_RED_CTC,
            repo = "csukuangfj2/sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25",
            revision = "423be1acfdc5d8aaa5a485fb6263fe4ea8570b06",
            files = listOf(
                LocalSpeechModelFile("model.int8.onnx", 775_861_420L, "ca3dbabd82170110cc0b343c2890866d449984bc9cd92b9a18371ff80a81bb99"),
                LocalSpeechModelFile("tokens.txt", 79_172L, "1bc613de2112d257e61a349c3e72d1b1a9cf19c33d3ca954197ad2171e5ea07b"),
            ),
        ),
        LocalSpeechModel(
            id = "qwen3-asr-0.6b-int8-2026-03-25",
            displayName = "Qwen3-ASR 0.6B",
            kind = LocalSpeechModelKind.QWEN3_ASR,
            repo = "csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
            revision = "68818b2313fe77bd06f6a7c5068ff3ef59d02b8a",
            files = listOf(
                LocalSpeechModelFile("conv_frontend.onnx", 44_148_281L, "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e"),
                LocalSpeechModelFile("encoder.int8.onnx", 182_491_662L, "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9"),
                LocalSpeechModelFile("decoder.int8.onnx", 755_914_231L, "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771"),
                LocalSpeechModelFile("tokenizer/merges.txt", 1_671_853L, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
                LocalSpeechModelFile("tokenizer/tokenizer_config.json", 12_487L, "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c"),
                LocalSpeechModelFile("tokenizer/vocab.json", 2_776_833L, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"),
            ),
            supportsVocabulary = true,
        ),
        LocalSpeechModel(
            id = "parakeet-tdt-0.6b-v3-int8",
            displayName = "Parakeet TDT 0.6B v3",
            kind = LocalSpeechModelKind.NEMO_TRANSDUCER,
            repo = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
            revision = "ed5d745623472f97cfc2e6c2916ee248798fc5a8",
            files = listOf(
                LocalSpeechModelFile("encoder.int8.onnx", 652_282_294L, "1afbe69c1ffdabee94755f42e4daf1d93549131a3da11356bda84fcef0f2c479"),
                LocalSpeechModelFile("decoder.int8.onnx", 11_845_274L, "0222a86147299d4dee90edf95b6d13827edb42ad8b7c9cf0b2582f030937c0b5"),
                LocalSpeechModelFile("joiner.int8.onnx", 6_355_277L, "cbd02f3696e9944970111fb439d9c3fd365a80374565f13f2b9c7007d253fc98"),
                LocalSpeechModelFile("tokens.txt", 93_939L, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
            ),
        ),
    )

    fun byId(id: String): LocalSpeechModel? = all.firstOrNull { it.id == id }
}
