package com.newoether.agora.api.local

import com.newoether.agora.api.LlamaGenerationEvent
import com.newoether.agora.api.LlamaGenerationStopReason
import com.newoether.agora.api.LOCAL_CONTEXT_CAPACITY_ERROR_CODE
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.ui.settings.LocalChatModelDraft
import com.newoether.agora.ui.settings.LocalChatModelDraftError
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlamaOwnershipSourceContractTest {
    @Test
    fun nativeTextAndFilesystemInputsUseTheStandardUtf8Boundary() {
        val chat = mainCppSource("llama_chat_jni.cpp")
        val template = mainCppSource("llama_chat_template.cpp")
        val embedding = mainCppSource("llama_jni.cpp")
        listOf(chat, template, embedding).forEach { native ->
            assertTrue(native.contains("#include \"jni_utf8.h\""))
            assertFalse(native.contains("GetStringUTFChars("))
            assertFalse(native.contains("ReleaseStringUTFChars("))
        }
        assertTrue(template.contains("read_java_string(env, value, result)"))
        assertTrue(chat.contains("read_java_path(env, path, path_str)"))
        assertTrue(chat.contains("read_java_path(env, mmproj_path, mmproj_str)"))
        assertTrue(chat.contains("read_java_path(env, jpath, image_path_storage[i])"))
        assertTrue(embedding.contains("read_java_string(env, text, input)"))
        assertTrue(embedding.contains("read_java_path(env, path, path_str)"))
        assertTrue(embedding.contains("read_java_path(env, native_library_dir, directory)"))
    }

    @Test
    fun `title generation delegates local serialization to the Provider`() {
        val source = mainSource("com/newoether/agora/viewmodel/ConversationTitleGenerator.kt")

        assertFalse(source.contains("LocalModelRuntime"))
    }

    @Test
    fun `Provider runs the complete request inside the process runtime`() {
        val source = mainSource("com/newoether/agora/api/local/LocalProvider.kt")
        val admission = source.indexOf("LocalModelRuntime.runChat(")
        val template = source.indexOf("engine.applyTemplate")
        val generation = source.indexOf("tokenFlow.collect")

        assertTrue(admission >= 0)
        assertTrue(template > admission)
        assertTrue(generation > template)
        assertFalse(source.contains("currentEngine"))
        assertFalse(source.contains("releaseEngineBlocking"))
    }

    @Test
    fun `native mutation is exclusive while cancellation remains concurrent`() {
        val source = mainSource("com/newoether/agora/api/LlamaChatEngine.kt")

        listOf("loadMmproj", "unloadMmproj").forEach { functionName ->
            assertTrue(functionSection(source, functionName).contains("lock.writeLock().lock()"))
        }
        assertFalse(source.contains("resetContext"))
        assertFalse(source.contains("nativeChatReset"))
        assertTrue(functionSection(source, "cancel").contains("lock.readLock().lock()"))
    }

    @Test
    fun `stream delivery blocks for capacity and native cancellation is atomic`() {
        val engine = mainSource("com/newoether/agora/api/LlamaChatEngine.kt")
        val native = mainCppSource("llama_chat_jni.cpp")

        assertTrue(engine.contains("trySendBlocking(LlamaGenerationEvent.Text(text)).isSuccess"))
        assertTrue(engine.contains("trySendBlocking(LlamaGenerationEvent.Thought(thought)).isSuccess"))
        assertTrue(engine.contains("trySendBlocking(LlamaGenerationEvent.ToolCallUpdate(call)).isSuccess"))
        assertFalse(engine.contains("trySend(token)"))
        assertTrue(mainCppSource("llama_chat_handle.h").contains("std::atomic<bool> cancelled"))
        assertFalse((native + mainCppSource("llama_chat_handle.h")).contains("volatile bool cancelled"))
    }

    @Test
    fun `chat templates use the official structured tool owner and fail closed by capability`() {
        val cmake = mainCppSource("CMakeLists.txt")
        val native = mainCppSource("llama_chat_jni.cpp")
        val template = mainCppSource("llama_chat_template.cpp")
        val engine = mainSource("com/newoether/agora/api/LlamaChatEngine.kt")
        val provider = mainSource("com/newoether/agora/api/local/LocalProvider.kt")

        assertTrue(cmake.contains("set(LLAMA_BUILD_COMMON ON CACHE BOOL \"\" FORCE)"))
        assertFalse(cmake.contains("add_subdirectory(\${LLAMA_CPP_DIR}/common"))
        assertTrue(cmake.contains("target_link_libraries(agora_llama llama llama-common"))
        assertTrue(native.contains("common_chat_templates_init(handle->model"))
        assertTrue(native.contains("common_chat_templates_was_explicit"))
        assertTrue(template.contains("common_chat_templates_apply("))
        assertTrue(template.contains("inputs.enable_thinking ="))
        assertTrue(template.contains("inputs.tool_choice = COMMON_CHAT_TOOL_CHOICE_AUTO"))
        assertTrue(template.contains("inputs.parallel_tool_calls = true"))
        assertTrue(template.contains("supports(\"supports_tools\")"))
        assertTrue(template.contains("supports(\"supports_tool_calls\")"))
        assertTrue(template.contains("!inputs.tools.empty() || has_tool_history"))
        assertFalse((native + template).contains("llama_chat_apply_template("))

        assertTrue(engine.contains("class LlamaChatTemplateRequest("))
        assertTrue(engine.contains("class LlamaChatTemplateResult("))
        assertTrue(engine.contains("class ChatTemplateToolCall("))
        assertTrue(engine.contains("class ChatTemplateTool("))
        assertTrue(provider.contains("TEMPLATE_JSON.encodeToString(tool.function.parameters)"))
        assertTrue(provider.contains("message.toolCalls.isNotEmpty() || message.role == \"tool\""))
        assertTrue(provider.contains("if (requiresToolCapableTemplate && !template.supportsTools)"))
        assertTrue(provider.contains("role = \"assistant\""))
        assertTrue(provider.contains("toolCalls = toolCalls.toTypedArray()"))
        assertTrue(provider.contains("role = \"tool\""))
        assertTrue(provider.contains("toolName = name"))
        assertTrue(provider.contains("toolCallId ="))
        assertFalse(provider.contains("Tool call:"))
        assertFalse(provider.contains("Tool result:"))
        assertTrue(provider.contains("enableThinking = config.thinkingEnabled"))
    }

    @Test
    fun `template grammar and penalties reach the shared native sampler`() {
        val provider = mainSource("com/newoether/agora/api/local/LocalProvider.kt")
        val engine = mainSource("com/newoether/agora/api/LlamaChatEngine.kt")
        val native = mainCppSource("llama_chat_jni.cpp")
        val generation = mainCppSource("llama_chat_generation.cpp")
        val sampler = generation
            .substringAfter("common_sampler * init_chat_sampler(")
            .substringBefore("bool is_preserved_token(")

        assertEquals(2, Regex("frequencyPenalty = config\\.frequencyPenalty \\?: 0f")
            .findAll(provider).count())
        assertEquals(2, Regex("presencePenalty = config\\.presencePenalty \\?: 0f")
            .findAll(provider).count())
        assertEquals(4, Regex("frequencyPenalty: Float").findAll(engine).count())
        assertEquals(4, Regex("presencePenalty: Float").findAll(engine).count())
        assertTrue(generation.contains("static constexpr int32_t PENALTY_LAST_N = 64;"))
        assertEquals(2, Regex("common_sampler \\* smpl = init_chat_sampler\\(")
            .findAll(native).count())
        assertTrue(sampler.contains("params.grammar = { COMMON_GRAMMAR_TYPE_TOOL_CALLS"))
        assertTrue(sampler.contains("params.grammar_lazy = metadata.grammar_lazy"))
        assertTrue(sampler.contains("params.generation_prompt = metadata.generation_prompt"))
        assertTrue(sampler.contains("common_tokenize(handle->vocab, value, false, true)"))
        assertTrue(sampler.contains("COMMON_GRAMMAR_TRIGGER_TYPE_WORD"))
        assertTrue(sampler.contains("COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN"))
        assertTrue(sampler.contains("Grammar trigger token is not preserved"))
        assertTrue(sampler.contains("Lazy grammar requires at least one trigger"))
        val penalties = sampler.indexOf("COMMON_SAMPLER_TYPE_PENALTIES")
        val minP = sampler.indexOf("COMMON_SAMPLER_TYPE_MIN_P")
        val topP = sampler.indexOf("COMMON_SAMPLER_TYPE_TOP_P")
        val temperature = sampler.indexOf("COMMON_SAMPLER_TYPE_TEMPERATURE")
        assertTrue(penalties >= 0 && penalties < minP)
        assertTrue(minP < topP && topP < temperature)
        assertFalse((native + generation).contains("llama_sampler_init_penalties("))
        assertFalse((native + generation).contains("llama_sampler_chain_init("))
    }

    @Test
    fun `text and multimodal loops accept template sampling before lossless delivery`() {
        val native = mainCppSource("llama_chat_jni.cpp")
        val generationHeader = mainCppSource("llama_chat_generation.h")
        val generation = mainCppSource("llama_chat_generation.cpp")

        assertTrue(generationHeader.contains("CALLBACK_TOKEN_BATCH = 4"))
        assertTrue(generationHeader.contains("CALLBACK_BYTE_BATCH = 64"))
        listOf("nativeChatGenerate", "nativeChatGenerateWithImages").forEach { functionName ->
            val function = nativeFunctionSection(native, functionName)
            val loop = function.substringAfter("while (generated < generation_limit)")
            val cancellation = loop.indexOf("cancelled.load")
            val sample = loop.indexOf("common_sampler_sample")
            val accept = loop.indexOf("common_sampler_accept")
            val eog = loop.indexOf("llama_vocab_is_eog")
            val piece = loop.indexOf("token_to_piece(handle->vocab")
            val decode = loop.indexOf("llama_decode")
            val count = loop.indexOf("generated++")
            val parse = loop.indexOf("parser.update(")

            assertTrue(cancellation >= 0 && cancellation < sample)
            assertTrue(sample >= 0 && sample < accept)
            assertTrue(accept < eog)
            assertTrue(eog < piece)
            assertTrue(loop.contains("!is_preserved_token(metadata, new_token_id)"))
            assertTrue(piece >= 0 && piece < decode)
            assertTrue(decode < count)
            assertTrue(count < parse)
            assertTrue(function.contains("common_sampler_free(smpl);"))
            assertTrue(loop.contains("callback_tokens >= CALLBACK_TOKEN_BATCH"))
            assertTrue(loop.contains("callback_buffer.size() >= CALLBACK_BYTE_BATCH"))
            assertTrue(loop.contains("std::min(callback_buffer.size(), CALLBACK_BYTE_BATCH)"))
            assertTrue(loop.contains("callback_buffer[emit_len]) & 0xC0) == 0x80"))
            assertTrue(loop.contains("!consumer_closed && !callback_buffer.empty()"))
            assertTrue(loop.contains("failure = \"Stream consumer closed\""))
            assertTrue(loop.contains("if (consumer_closed) break"))
            assertTrue(loop.contains("Generated incomplete UTF-8 output"))
            assertFalse(loop.contains("llama_synchronize"))
            assertFalse(loop.contains("char piece[256]"))
        }
        assertFalse((native + generation).contains("llama_sampler_sample("))
        assertFalse((native + generation).contains("llama_sampler_free("))
    }

    @Test
    fun `local output uses the template parser as the typed stream authority`() {
        val native = mainCppSource("llama_chat_jni.cpp")
        val callbacks = mainCppSource("llama_chat_callbacks.cpp")
        val engine = mainSource("com/newoether/agora/api/LlamaChatEngine.kt")
        val providerContract = mainSource("com/newoether/agora/api/LlmProvider.kt")
        val provider = mainSource("com/newoether/agora/api/local/LocalProvider.kt")
        val normalizer = mainSource("com/newoether/agora/api/util/ProviderStreamNormalizer.kt")
        val runner = mainSource("com/newoether/agora/viewmodel/ProviderPassRunner.kt")
        val parser = mainCppSource("llama_chat_parser.h")
            .substringAfter("struct NativeChatParser {")

        assertTrue(engine.contains("val format: Int = 0"))
        assertTrue(engine.contains("val parser: String = \"\""))
        assertTrue(engine.contains("fun onText(text: String): Boolean"))
        assertTrue(engine.contains("fun onThought(thought: String): Boolean"))
        assertTrue(engine.contains("fun onToolCall(index: Int, id: String, name: String, arguments: String): Boolean"))
        assertTrue(engine.contains("fun onToolCallsComplete(): Boolean"))
        assertTrue(engine.contains("id = id.takeIf(String::isNotBlank) ?: previous?.id"))
        assertTrue(engine.contains("name = name.takeIf(String::isNotBlank) ?: previous?.name.orEmpty()"))
        assertTrue(engine.contains("toolCalls.toSortedMap().values.toList()"))
        assertFalse(engine.contains("fun onToken("))

        assertTrue(parser.contains("params.format = metadata.format"))
        assertTrue(parser.contains("params.generation_prompt = metadata.generation_prompt"))
        assertTrue(parser.contains("params.parser.load(metadata.parser)"))
        assertTrue(parser.contains("common_chat_parse(generated_text, is_partial, params)"))
        assertTrue(parser.contains("common_chat_msg_diff::compute_diffs(message, next)"))
        assertTrue(parser.contains("message.tool_calls[diff.tool_call_index]"))
        assertTrue(parser.contains("if (!is_partial) message = {}"))
        assertTrue(parser.contains("nlohmann::ordered_json::parse("))
        assertTrue(parser.contains("if (!arguments.is_object())"))
        assertTrue(parser.contains("report_tool_calls_complete(env, callback, methods)"))
        assertEquals(2, Regex("NativeChatParser parser\\(metadata\\)")
            .findAll(native).count())
        assertEquals(2, Regex("std::strcmp\\(stop_reason, \\\"cancelled\\\"\\) != 0")
            .findAll(native).count())
        assertEquals(2, Regex("parser\\.finish\\(env, callback, callbacks, failure\\)")
            .findAll(native).count())
        assertFalse((native + callbacks + parser).contains("report_token("))
        assertFalse((native + callbacks + parser).contains("\"onToken\""))

        assertTrue(providerContract.contains("val nativeTextParsingAuthoritative: Boolean"))
        assertTrue(providerContract.contains("get() = false"))
        assertTrue(provider.contains("override val nativeTextParsingAuthoritative: Boolean = true"))
        assertTrue(runner.contains("nativeTextParsingAuthoritative = provider.nativeTextParsingAuthoritative"))
        assertTrue(normalizer.contains(
            "offeredToolNames.isNotEmpty() && !nativeTextParsingAuthoritative"
        ))
        assertTrue(normalizer.contains("routeRawText(content, downstream)"))
        assertTrue(provider.contains("is LlamaGenerationEvent.Thought ->"))
        assertTrue(provider.contains("StreamEvent.ThoughtChunk(event.value)"))
        assertTrue(provider.contains("is LlamaGenerationEvent.ToolCallUpdate ->"))
        assertTrue(provider.contains("StreamEvent.ToolCallUpdate("))
        assertTrue(provider.contains("is LlamaGenerationEvent.ToolCallsCompleted ->"))
        assertTrue(provider.contains("StreamEvent.ToolCallsRequest(calls)"))
        assertTrue(provider.contains("\"${'$'}{call.name}:${'$'}passKey:${'$'}{call.index}\""))
        assertTrue(provider.contains("streamKey = \"local_tool_${'$'}{passKey}_${'$'}{call.index}\""))
        assertFalse(provider.contains("STOP_PATTERNS"))
        assertFalse(provider.contains("rawBuf"))
    }

    @Test
    fun `native performance logs contain counts and timings but no private paths`() {
        val native = mainCppSource("llama_chat_jni.cpp")

        assertTrue(native.contains("Chat load: model_ms="))
        assertTrue(native.contains("Text prefill: input_tokens="))
        assertTrue(native.contains("Text decode: output_tokens="))
        assertTrue(native.contains("Multimodal prefill: input_tokens="))
        assertTrue(native.contains("Multimodal decode: output_tokens="))
        assertFalse(native.contains("Failed to load image: %s"))
    }

    @Test
    fun `native stop reason mapping is closed`() {
        LlamaGenerationStopReason.entries.forEach { reason ->
            assertEquals(reason, LlamaGenerationStopReason.fromNative(reason.nativeValue))
        }
        assertNull(LlamaGenerationStopReason.fromNative("unknown"))
    }

    @Test
    fun `typed generation events stay module internal`() {
        val engine = mainSource("com/newoether/agora/api/LlamaChatEngine.kt")

        assertTrue(engine.contains("internal fun generate("))
        assertTrue(engine.contains("internal fun generateWithImages("))
    }

    @Test
    fun `runtime unloads before switching identity and isolates embeddings`() {
        val runtime = mainSource("com/newoether/agora/api/LocalModelRuntime.kt")
        val embeddingNative = mainCppSource("llama_jni.cpp")
        val chatSwitch = runtime.indexOf("unloadResident()")
        val chatLoad = runtime.indexOf("LlamaChatEngine(identity.canonicalPath, identity.nCtx)")
        val chatFailure = runtime.indexOf("if (!loaded.load())", chatLoad)
        val chatInstall = runtime.indexOf("resident = Resident.Chat", chatFailure)
        val embeddingSwitch = runtime.indexOf("unloadResident()", chatSwitch + 1)
        val embeddingLoad = runtime.indexOf("LlamaEngine.loadResident", embeddingSwitch)

        assertTrue(runtime.contains("data class Chat("))
        assertTrue(runtime.contains("val nCtx: Int"))
        assertTrue(runtime.contains("data class Embedding("))
        assertTrue(chatSwitch >= 0 && chatLoad > chatSwitch)
        assertTrue(chatFailure > chatLoad && chatInstall > chatFailure)
        assertTrue(runtime.substring(chatFailure, chatInstall).contains("return@run false"))
        assertTrue(embeddingSwitch >= 0 && embeddingLoad > embeddingSwitch)
        assertTrue(runtime.contains("activeChatEngine = engine"))
        assertTrue(runtime.contains("activeChatEngine = null"))
        assertTrue(runtime.contains("activeChatEngine?.cancel()"))
        assertTrue(embeddingNative.contains(
            "llama_memory_clear(llama_get_memory(handle->ctx), true);"
        ))
    }

    @Test
    fun `same chat identity reuses only proven native token prefixes`() {
        val runtime = mainSource("com/newoether/agora/api/LocalModelRuntime.kt")
        val native = mainCppSource("llama_chat_jni.cpp")
        val text = nativeFunctionSection(native, "nativeChatGenerate")
        val prepare = mainCppSource("llama_chat_generation.cpp")
            .substringAfter("size_t prepare_text_cache(")
            .substringBefore("bool token_to_piece(")

        val sameIdentity = runtime
            .substringAfter("current is Resident.Chat && current.identity == identity")
            .substringBefore("} else {")
        assertTrue(sameIdentity.contains("current.engine"))
        assertFalse(sameIdentity.contains("resetContext"))

        assertTrue(mainCppSource("llama_chat_handle.h").contains("std::vector<llama_token> decoded_tokens;"))
        assertTrue(prepare.contains("handle->decoded_tokens[retained_prefix] =="))
        assertTrue(prepare.contains("retained_prefix == prompt_tokens.size()"))
        assertTrue(prepare.contains("retained_prefix--;"))
        assertTrue(prepare.contains("llama_memory_seq_rm("))
        assertTrue(prepare.contains("llama_memory_seq_pos_min(memory, 0)"))
        assertTrue(prepare.contains("llama_memory_seq_pos_max(memory, 0)"))
        assertTrue(prepare.contains("pos_min == 0 && pos_max + 1 =="))
        assertTrue(prepare.contains("clear_text_cache(handle);"))
        assertTrue(prepare.contains("handle->decoded_tokens.resize(retained_prefix);"))

        val capacityCheck = text.indexOf("n_tokens + min_generation_room > n_ctx")
        val cacheMutation = text.indexOf("prepare_text_cache(handle, tokens)")
        assertTrue(capacityCheck >= 0 && cacheMutation > capacityCheck)
        assertTrue(text.contains("for (int32_t off = cached_tokens; off < n_tokens"))
        assertTrue(text.contains("handle->decoded_tokens.insert("))
        assertTrue(text.contains("handle->decoded_tokens.push_back(new_token_id);"))
        assertEquals(2, Regex("const int32_t decode_result = llama_decode")
            .findAll(text).count())
        assertEquals(2, Regex("clear_text_cache\\(handle\\);")
            .findAll(text).count())
        val prefillDecode = text.indexOf("llama_decode(handle->ctx, batch)")
        val prefillLedger = text.indexOf("handle->decoded_tokens.insert(")
        val generatedDecode = text.indexOf("llama_decode(handle->ctx, single)")
        val generatedLedger = text.indexOf("handle->decoded_tokens.push_back(new_token_id)")
        assertTrue(prefillDecode >= 0 && prefillLedger > prefillDecode)
        assertTrue(generatedDecode >= 0 && generatedLedger > generatedDecode)
    }

    @Test
    fun `multimodal evaluation invalidates text cache on every terminal path`() {
        val native = mainCppSource("llama_chat_jni.cpp")
        val multimodal = nativeFunctionSection(native, "nativeChatGenerateWithImages")
        val evaluation = multimodal.indexOf("mtmd_helper_eval_chunks(")
        val invalidation = multimodal.lastIndexOf("clear_text_cache(handle);", evaluation)

        assertTrue(invalidation >= 0 && invalidation < evaluation)
        assertTrue(multimodal.indexOf("Unable to read chat template") < invalidation)
        assertTrue(multimodal.indexOf("Failed to tokenize multimodal prompt") < invalidation)
        assertTrue(Regex("clear_text_cache\\(handle\\);")
            .findAll(multimodal).count() >= 5)
    }

    @Test
    fun `android CPU backends are packaged and initialized before local model loads`() {
        val cmake = mainCppSource("CMakeLists.txt")
        val appContainer = mainSource("com/newoether/agora/di/AppContainer.kt")
        val runtime = mainSource("com/newoether/agora/api/LocalModelRuntime.kt")
        val engine = mainSource("com/newoether/agora/api/LlamaEngine.kt")
        val embeddingNative = mainCppSource("llama_jni.cpp")
        val chatNative = mainCppSource("llama_chat_jni.cpp")

        listOf(
            "set(BUILD_SHARED_LIBS ON CACHE BOOL \"\" FORCE)",
            "set(GGML_NATIVE OFF CACHE BOOL \"\" FORCE)",
            "set(GGML_BACKEND_DL ON CACHE BOOL \"\" FORCE)",
            "set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL \"\" FORCE)",
            "set(GGML_CPU_KLEIDIAI OFF CACHE BOOL \"\" FORCE)",
            "set(GGML_VULKAN OFF CACHE BOOL \"\" FORCE)",
            "set(GGML_OPENMP OFF CACHE BOOL \"\" FORCE)",
        ).forEach { assertTrue(cmake.contains(it)) }
        listOf(
            "ggml-cpu-android_armv8.0_1",
            "ggml-cpu-android_armv8.2_1",
            "ggml-cpu-android_armv8.2_2",
            "ggml-cpu-android_armv8.6_1",
            "ggml-cpu-android_armv9.0_1",
            "ggml-cpu-android_armv9.2_1",
            "ggml-cpu-android_armv9.2_2",
        ).forEach { assertTrue(cmake.contains(it)) }
        assertTrue(cmake.contains("LIBRARY_OUTPUT_DIRECTORY \${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"))
        assertTrue(cmake.contains("Required Android CPU backend target is missing"))

        assertEquals(
            1,
            Regex.escape("LocalModelRuntime.initialize(").toRegex()
                .findAll(appContainer).count(),
        )
        assertTrue(appContainer.contains("application.applicationInfo.nativeLibraryDir"))
        assertTrue(runtime.contains("LlamaEngine.initializeBackends(canonicalDirectory)"))
        assertTrue(runtime.contains("if (nativeBackendDirectory == null) return@run false"))
        assertTrue(runtime.contains("if (nativeBackendDirectory == null) return@run null"))
        assertTrue(engine.contains("nativeInitializeBackends(nativeLibraryDir)"))

        val initialization = embeddingNative
            .substringAfter("LlamaEngine_nativeInitializeBackends(")
            .substringBefore("\nJNIEXPORT")
        val loadBackends = initialization.indexOf("ggml_backend_load_all_from_path(directory.c_str())")
        val verifyCpu = initialization.indexOf("ggml_backend_reg_by_name(\"CPU\")")
        val initializeLlama = initialization.indexOf("llama_backend_init()")
        assertTrue(loadBackends >= 0 && verifyCpu > loadBackends)
        assertTrue(initializeLlama > verifyCpu)

        val embeddingLoad = embeddingNative
            .substringAfter("LlamaEngine_nativeLoadModel(")
            .substringBefore("\nJNIEXPORT")
        val chatLoad = nativeFunctionSection(chatNative, "nativeChatLoadModel")
        assertFalse(embeddingLoad.contains("llama_backend_init"))
        assertFalse(embeddingLoad.contains("ggml_backend_load_all"))
        assertFalse(chatLoad.contains("llama_backend_init"))
        assertFalse(chatLoad.contains("ggml_backend_load_all"))
    }

    @Test
    fun `idle offload is generation safe and uses the canonical permit`() {
        val runtime = mainSource("com/newoether/agora/api/LocalModelRuntime.kt")
        val queue = runtime.substringAfter("internal class LocalModelTaskQueue(")
            .substringBefore("internal object LocalModelRuntime")

        assertTrue(queue.contains("submittedTasks++"))
        assertTrue(queue.contains("submittedTasks--"))
        assertTrue(queue.contains("if (submittedTasks == 0) onQueueIdle()"))
        assertTrue(queue.contains("suspend fun runIfIdle"))
        assertTrue(queue.contains("permit.withPermit"))
        assertTrue(runtime.contains("onTaskArrived = ::cancelIdleDeadline"))
        assertTrue(runtime.contains("onQueueIdle = ::startIdleDeadline"))
        assertTrue(runtime.contains("tasks.signalIdleIfEmpty()"))
        assertTrue(runtime.contains("if (delayMillis > 0) delay(delayMillis)"))
        assertTrue(runtime.contains("tasks.runIfIdle"))
        assertTrue(runtime.contains("if (epoch != idleEpoch) return@runIfIdle"))
        val epochCheck = runtime.indexOf("if (epoch != idleEpoch)")
        assertTrue(runtime.indexOf("unloadResident()", epochCheck) > epochCheck)
    }

    @Test
    fun `idle retention is bound once and remains device local`() {
        val appContainer = mainSource("com/newoether/agora/di/AppContainer.kt")
        val settingsPage = mainSource(
            "com/newoether/agora/ui/settings/SettingsProviderDetailPage.kt",
        )
        val portable = mainSource("com/newoether/agora/data/PortableSettingsArchive.kt")
        val settingsManager = mainSource("com/newoether/agora/data/SettingsManager.kt")
        val portableReset = settingsManager
            .substringAfter("suspend fun resetPortableSettingsForImport()")
            .substringBefore("suspend fun invalidatePortableModelCaches")
        val localModelsGroup = settingsPage.indexOf("R.string.local_models_title")
        val advancedGroup = settingsPage.indexOf("R.string.advanced_title", localModelsGroup)

        assertEquals(
            1,
            Regex.escape("LocalModelRuntime.bindIdleRetention(").toRegex()
                .findAll(appContainer).count(),
        )
        assertTrue(appContainer.contains("it.localModelIdleRetentionMinutes, appScope"))
        assertTrue(localModelsGroup >= 0 && advancedGroup > localModelsGroup)
        val slider = mainSource(
            "com/newoether/agora/ui/settings/LocalModelIdleRetentionSlider.kt",
        )
        assertTrue(settingsPage.contains("LocalModelIdleRetentionSlider("))
        assertTrue(slider.contains("LOCAL_MODEL_IDLE_RETENTION_PRESETS"))
        assertTrue(slider.contains("PersistedSliderFeedbackGate"))
        assertFalse(portable.contains("localModelIdleRetentionMinutes"))
        assertFalse(portable.contains("local_model_idle_retention_minutes"))
        assertFalse(portableReset.contains("LOCAL_MODEL_IDLE_RETENTION_MINUTES"))
    }

    @Test
    fun `projector is image gated and reused by path`() {
        val provider = mainSource("com/newoether/agora/api/local/LocalProvider.kt")
        val engine = mainSource("com/newoether/agora/api/LlamaChatEngine.kt")
        val projectorLoad = functionSection(engine, "loadMmproj")

        assertTrue(provider.contains("if (hasImages)"))
        assertTrue(provider.contains("engine.loadMmproj(modelConfig.mmprojPath)"))
        assertTrue(projectorLoad.contains("loadedMmprojPath == mmprojPath"))
        assertTrue(projectorLoad.indexOf("loadedMmprojPath == mmprojPath") <
            projectorLoad.indexOf("nativeChatLoadMmproj"))
    }

    @Test
    fun `native callback context failure receives the stable semantic code`() {
        val failure = localGenerationFailure(
            event = LlamaGenerationEvent.Failed(
                message = "LOCAL_CONTEXT_EXCEEDED:24636:8192",
                inputTokenCount = 24_636,
                outputTokenCount = 0,
            ),
            displayMessage = "This conversation needs 24636 prompt tokens",
        )
        val other = localGenerationFailure(
            event = LlamaGenerationEvent.Failed(
                message = "native decode failed",
                inputTokenCount = 0,
                outputTokenCount = 0,
            ),
            displayMessage = "Generation failed: native decode failed",
        )

        assertEquals(LOCAL_CONTEXT_CAPACITY_ERROR_CODE, failure.code)
        assertEquals("This conversation needs 24636 prompt tokens", failure.message)
        assertNull(other.code)
    }

    @Test
    fun `Local context capacity failures keep one stable semantic code`() {
        val provider = mainSource("com/newoether/agora/api/local/LocalProvider.kt")
        val completedContextFull = provider
            .substringAfter("LlamaGenerationStopReason.CONTEXT_FULL ->")
            .substringBefore("LlamaGenerationStopReason.CANCELLED ->")
        val thrownContextExceeded = provider
            .substringAfter("DebugLog.e(TAG, \"Generation failed\", e)")
            .substringBefore("return@runChat")

        assertTrue(completedContextFull.contains("code = LOCAL_CONTEXT_CAPACITY_ERROR_CODE"))
        assertTrue(provider.contains("is LlamaGenerationEvent.Failed ->"))
        assertTrue(provider.contains("event = event"))
        assertTrue(thrownContextExceeded.contains("localGenerationFailure("))
        assertTrue(thrownContextExceeded.contains("rawMessage = e.message"))
    }

    @Test
    fun `local context and settings cannot promise an impossible output`() {
        val provider = mainSource("com/newoether/agora/api/local/LocalProvider.kt")
        val settings = mainSource("com/newoether/agora/ui/settings/LocalChatModelEditor.kt")
        val onboarding = mainSource("com/newoether/agora/ui/onboarding/WelcomeScreen.kt")
        val native = mainCppSource("llama_chat_jni.cpp")
        val legacyDefaults = LocalChatModelConfig(modelId = "model", alias = "Model")

        assertEquals(2048, legacyDefaults.nCtx)
        assertEquals(4096, legacyDefaults.maxTokens)
        val draft = LocalChatModelDraft(modelId = "model", modelPath = "/model.gguf")
        assertEquals("16384", draft.nCtx)
        assertEquals("1024", draft.maxTokens)
        assertNull(draft.validate { false })
        assertEquals(
            LocalChatModelDraftError.OUTPUT_EXCEEDS_CONTEXT,
            draft.copy(maxTokens = "16385").validate { false },
        )
        val legacyDraft = LocalChatModelDraft.from(legacyDefaults.copy(localFilePath = "/legacy.gguf"))
        assertEquals("2048", legacyDraft.nCtx)
        assertEquals("4096", legacyDraft.maxTokens)
        assertEquals(LocalChatModelDraftError.OUTPUT_EXCEEDS_CONTEXT, legacyDraft.validate { false })
        assertTrue(settings.contains("draft.validate { id ->"))
        assertTrue(settings.indexOf("draft.validate { id ->") < settings.indexOf("addLocalChatModel(config)"))
        assertTrue(settings.indexOf("draft.validate { id ->") < settings.indexOf("updateLocalChatModel("))
        assertTrue(onboarding.contains("nCtx = 16384"))
        assertTrue(onboarding.contains("maxTokens = 1024"))
        assertTrue(provider.contains(
            "minOf(config.maxContextWindow, modelConfig.nCtx).coerceAtLeast(1)"
        ))
        assertFalse(provider.contains("?: buildPrompt(templateMessages)"))
        listOf("nativeChatGenerate", "nativeChatGenerateWithImages").forEach { functionName ->
            val function = nativeFunctionSection(native, functionName)
            assertTrue(function.contains("generation_limit = std::min(max_tokens, remaining_context)"))
            assertTrue(function.contains("context_limited ? \"context_full\" : \"max_tokens\""))
        }
    }

    private fun functionSection(source: String, functionName: String): String = source
        .substringAfter("fun $functionName(")
        .substringBefore("\n    fun ")

    private fun nativeFunctionSection(source: String, functionName: String): String = source
        .substringAfter("Java_com_newoether_agora_api_LlamaChatEngine_$functionName(")
        .substringBefore("\nJNIEXPORT")

    private fun mainSource(relativePath: String): String = locateSourceRoot("java")
        .resolve(relativePath)
        .readText()

    private fun mainCppSource(fileName: String): String = locateSourceRoot("cpp")
        .resolve(fileName)
        .readText()

    private fun locateSourceRoot(kind: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/$kind"),
                File(directory, "src/main/$kind"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main $kind source directory")
    }
}
