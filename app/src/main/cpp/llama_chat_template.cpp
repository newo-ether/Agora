#include "llama_chat_template.h"
#include "llama_chat_handle.h"
#include "llama_chat_log.h"
#include "llama_chat_callbacks.h"
#include "jni_utf8.h"
#include <cstring>

namespace agora::chat {

using agora::jni::read_java_string;

static bool read_string_field(
    JNIEnv * env,
    jobject object,
    jclass object_class,
    const char * name,
    std::string & result
) {
    jfieldID field = env->GetFieldID(object_class, name, "Ljava/lang/String;");
    if (!field) return false;
    jstring value = static_cast<jstring>(env->GetObjectField(object, field));
    const bool ok = read_java_string(env, value, result);
    if (value) env->DeleteLocalRef(value);
    return ok;
}

static jobject make_template_result(
    JNIEnv * env,
    const common_chat_params & params,
    bool supports_tools
) {
    jclass trigger_class = env->FindClass(
        "com/newoether/agora/api/ChatTemplateGrammarTrigger"
    );
    jclass result_class = env->FindClass(
        "com/newoether/agora/api/LlamaChatTemplateResult"
    );
    jclass string_class = env->FindClass("java/lang/String");
    if (!trigger_class || !result_class || !string_class) return nullptr;

    jmethodID trigger_ctor = env->GetMethodID(trigger_class, "<init>", "(ILjava/lang/String;I)V");
    jmethodID result_ctor = env->GetMethodID(
        result_class,
        "<init>",
        "(Ljava/lang/String;ZLjava/lang/String;ZLjava/lang/String;"
        "[Lcom/newoether/agora/api/ChatTemplateGrammarTrigger;[Ljava/lang/String;I"
        "Ljava/lang/String;)V"
    );
    if (!trigger_ctor || !result_ctor) return nullptr;

    jobjectArray triggers = env->NewObjectArray(
        static_cast<jsize>(params.grammar_triggers.size()), trigger_class, nullptr
    );
    jobjectArray preserved = env->NewObjectArray(
        static_cast<jsize>(params.preserved_tokens.size()), string_class, nullptr
    );
    if (!triggers || !preserved) return nullptr;

    for (jsize i = 0; i < static_cast<jsize>(params.grammar_triggers.size()); ++i) {
        const auto & trigger = params.grammar_triggers[static_cast<size_t>(i)];
        jstring value = utf8_to_jstring(env, trigger.value.data(), trigger.value.size());
        jobject item = env->NewObject(
            trigger_class, trigger_ctor,
            static_cast<jint>(trigger.type), value, static_cast<jint>(trigger.token)
        );
        env->SetObjectArrayElement(triggers, i, item);
        env->DeleteLocalRef(item);
        env->DeleteLocalRef(value);
    }
    for (jsize i = 0; i < static_cast<jsize>(params.preserved_tokens.size()); ++i) {
        const auto & token = params.preserved_tokens[static_cast<size_t>(i)];
        jstring value = utf8_to_jstring(env, token.data(), token.size());
        env->SetObjectArrayElement(preserved, i, value);
        env->DeleteLocalRef(value);
    }

    jstring prompt = utf8_to_jstring(env, params.prompt.data(), params.prompt.size());
    jstring grammar = utf8_to_jstring(env, params.grammar.data(), params.grammar.size());
    jstring generation_prompt = utf8_to_jstring(
        env, params.generation_prompt.data(), params.generation_prompt.size()
    );
    jstring parser = utf8_to_jstring(env, params.parser.data(), params.parser.size());
    jobject result = env->NewObject(
        result_class, result_ctor,
        prompt, supports_tools ? JNI_TRUE : JNI_FALSE,
        grammar, params.grammar_lazy ? JNI_TRUE : JNI_FALSE,
        generation_prompt, triggers, preserved,
        static_cast<jint>(params.format), parser
    );
    env->DeleteLocalRef(prompt);
    env->DeleteLocalRef(grammar);
    env->DeleteLocalRef(generation_prompt);
    env->DeleteLocalRef(parser);
    env->DeleteLocalRef(triggers);
    env->DeleteLocalRef(preserved);
    env->DeleteLocalRef(trigger_class);
    env->DeleteLocalRef(result_class);
    env->DeleteLocalRef(string_class);
    return result;
}

bool read_template_metadata(
    JNIEnv * env,
    jobject template_result,
    TemplateSamplingMetadata & metadata
) {
    if (!template_result) return false;
    jclass result_class = env->GetObjectClass(template_result);
    if (!result_class) return false;
    const bool strings_ok =
        read_string_field(env, template_result, result_class, "prompt", metadata.prompt) &&
        read_string_field(env, template_result, result_class, "grammar", metadata.grammar) &&
        read_string_field(
            env, template_result, result_class, "generationPrompt", metadata.generation_prompt
        ) &&
        read_string_field(env, template_result, result_class, "parser", metadata.parser);
    jfieldID lazy_field = env->GetFieldID(result_class, "grammarLazy", "Z");
    jfieldID format_field = env->GetFieldID(result_class, "format", "I");
    jfieldID triggers_field = env->GetFieldID(
        result_class, "grammarTriggers",
        "[Lcom/newoether/agora/api/ChatTemplateGrammarTrigger;"
    );
    jfieldID preserved_field = env->GetFieldID(
        result_class, "preservedTokens", "[Ljava/lang/String;"
    );
    if (!strings_ok || !lazy_field || !format_field || !triggers_field || !preserved_field) {
        env->DeleteLocalRef(result_class);
        return false;
    }
    const jint format = env->GetIntField(template_result, format_field);
    if (format < COMMON_CHAT_FORMAT_CONTENT_ONLY || format >= COMMON_CHAT_FORMAT_COUNT) {
        env->DeleteLocalRef(result_class);
        return false;
    }
    metadata.format = static_cast<common_chat_format>(format);
    metadata.grammar_lazy = env->GetBooleanField(template_result, lazy_field) == JNI_TRUE;
    jobjectArray triggers = static_cast<jobjectArray>(
        env->GetObjectField(template_result, triggers_field)
    );
    jobjectArray preserved = static_cast<jobjectArray>(
        env->GetObjectField(template_result, preserved_field)
    );
    if (!triggers || !preserved) {
        env->DeleteLocalRef(result_class);
        return false;
    }

    const jsize trigger_count = env->GetArrayLength(triggers);
    metadata.grammar_triggers.reserve(static_cast<size_t>(trigger_count));
    for (jsize i = 0; i < trigger_count; ++i) {
        jobject trigger = env->GetObjectArrayElement(triggers, i);
        jclass trigger_class = env->GetObjectClass(trigger);
        jfieldID type_field = env->GetFieldID(trigger_class, "type", "I");
        jfieldID value_field = env->GetFieldID(trigger_class, "value", "Ljava/lang/String;");
        jfieldID token_field = env->GetFieldID(trigger_class, "token", "I");
        if (!type_field || !value_field || !token_field) return false;
        const jint type = env->GetIntField(trigger, type_field);
        if (type < COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN ||
            type > COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN_FULL) return false;
        jstring value = static_cast<jstring>(env->GetObjectField(trigger, value_field));
        std::string trigger_value;
        if (!read_java_string(env, value, trigger_value)) return false;
        metadata.grammar_triggers.push_back({
            static_cast<common_grammar_trigger_type>(type),
            std::move(trigger_value),
            static_cast<llama_token>(env->GetIntField(trigger, token_field)),
        });
        if (value) env->DeleteLocalRef(value);
        env->DeleteLocalRef(trigger_class);
        env->DeleteLocalRef(trigger);
    }

    const jsize preserved_count = env->GetArrayLength(preserved);
    metadata.preserved_tokens.reserve(static_cast<size_t>(preserved_count));
    for (jsize i = 0; i < preserved_count; ++i) {
        jstring value = static_cast<jstring>(env->GetObjectArrayElement(preserved, i));
        std::string token;
        if (!read_java_string(env, value, token)) return false;
        metadata.preserved_tokens.push_back(std::move(token));
        if (value) env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(triggers);
    env->DeleteLocalRef(preserved);
    env->DeleteLocalRef(result_class);
    return true;
}

} // namespace agora::chat

using agora::chat::ChatHandle;
using agora::chat::utf8_to_jstring;
using agora::chat::read_string_field;
using agora::chat::make_template_result;

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatGetTemplate(
    JNIEnv * env, jclass /*clazz*/, jlong handle_ptr) {

    if (!handle_ptr) return nullptr;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (!handle->model) return nullptr;

    const char * tmpl = llama_model_chat_template(handle->model, nullptr);
    if (!tmpl) return nullptr;
    return utf8_to_jstring(env, tmpl, strlen(tmpl));
}

JNIEXPORT jobject JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatApplyTemplate(
    JNIEnv * env, jclass /*clazz*/, jlong handle_ptr, jobject request) {

    if (!handle_ptr || !request) return nullptr;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (!handle->model || !handle->chat_templates) return nullptr;

    jclass request_class = env->GetObjectClass(request);
    jfieldID messages_field = env->GetFieldID(
        request_class, "messages", "[Lcom/newoether/agora/api/ChatTemplateMessage;"
    );
    jfieldID tools_field = env->GetFieldID(
        request_class, "tools", "[Lcom/newoether/agora/api/ChatTemplateTool;"
    );
    jfieldID add_generation_field = env->GetFieldID(
        request_class, "addGenerationPrompt", "Z"
    );
    jfieldID thinking_field = env->GetFieldID(request_class, "enableThinking", "Z");
    if (!messages_field || !tools_field || !add_generation_field || !thinking_field) {
        env->DeleteLocalRef(request_class);
        return nullptr;
    }

    jobjectArray messages = static_cast<jobjectArray>(env->GetObjectField(request, messages_field));
    jobjectArray tools = static_cast<jobjectArray>(env->GetObjectField(request, tools_field));
    if (!messages || !tools) {
        env->DeleteLocalRef(request_class);
        return nullptr;
    }

    common_chat_templates_inputs inputs;
    const jint message_count = env->GetArrayLength(messages);
    const jint tool_count = env->GetArrayLength(tools);
    inputs.messages.reserve(static_cast<size_t>(message_count));
    inputs.tools.reserve(static_cast<size_t>(tool_count));
    inputs.add_generation_prompt =
        env->GetBooleanField(request, add_generation_field) == JNI_TRUE;
    inputs.enable_thinking = env->GetBooleanField(request, thinking_field) == JNI_TRUE;
    inputs.tool_choice = COMMON_CHAT_TOOL_CHOICE_AUTO;
    inputs.parallel_tool_calls = true;
    inputs.use_jinja = true;
    bool has_tool_history = false;

    for (jint i = 0; i < message_count; ++i) {
        jobject message = env->GetObjectArrayElement(messages, i);
        jclass message_class = env->GetObjectClass(message);
        common_chat_msg chat_message;
        if (!read_string_field(env, message, message_class, "role", chat_message.role) ||
            !read_string_field(env, message, message_class, "content", chat_message.content) ||
            !read_string_field(env, message, message_class, "toolName", chat_message.tool_name) ||
            !read_string_field(
                env, message, message_class, "toolCallId", chat_message.tool_call_id
            )) {
            env->DeleteLocalRef(message_class);
            env->DeleteLocalRef(message);
            env->DeleteLocalRef(messages);
            env->DeleteLocalRef(tools);
            env->DeleteLocalRef(request_class);
            return nullptr;
        }
        jfieldID calls_field = env->GetFieldID(
            message_class, "toolCalls",
            "[Lcom/newoether/agora/api/ChatTemplateToolCall;"
        );
        jobjectArray calls = static_cast<jobjectArray>(
            env->GetObjectField(message, calls_field)
        );
        const jsize call_count = calls ? env->GetArrayLength(calls) : 0;
        chat_message.tool_calls.reserve(static_cast<size_t>(call_count));
        for (jsize call_index = 0; call_index < call_count; ++call_index) {
            jobject call = env->GetObjectArrayElement(calls, call_index);
            jclass call_class = env->GetObjectClass(call);
            common_chat_tool_call tool_call;
            const bool ok =
                read_string_field(env, call, call_class, "id", tool_call.id) &&
                read_string_field(env, call, call_class, "name", tool_call.name) &&
                read_string_field(
                    env, call, call_class, "arguments", tool_call.arguments
                );
            env->DeleteLocalRef(call_class);
            env->DeleteLocalRef(call);
            if (!ok) return nullptr;
            chat_message.tool_calls.push_back(std::move(tool_call));
        }
        if (calls) env->DeleteLocalRef(calls);
        has_tool_history = has_tool_history || !chat_message.tool_calls.empty() ||
            chat_message.role == "tool";
        inputs.messages.push_back(std::move(chat_message));
        env->DeleteLocalRef(message_class);
        env->DeleteLocalRef(message);
    }

    for (jint i = 0; i < tool_count; ++i) {
        jobject tool = env->GetObjectArrayElement(tools, i);
        jclass tool_class = env->GetObjectClass(tool);
        common_chat_tool chat_tool;
        const bool ok =
            read_string_field(env, tool, tool_class, "name", chat_tool.name) &&
            read_string_field(env, tool, tool_class, "description", chat_tool.description) &&
            read_string_field(env, tool, tool_class, "parameters", chat_tool.parameters);
        env->DeleteLocalRef(tool_class);
        env->DeleteLocalRef(tool);
        if (!ok) return nullptr;
        inputs.tools.push_back(std::move(chat_tool));
    }

    env->DeleteLocalRef(messages);
    env->DeleteLocalRef(tools);
    env->DeleteLocalRef(request_class);

    const auto caps = common_chat_templates_get_caps(handle->chat_templates.get());
    const auto supports = [&](const char * name) {
        const auto found = caps.find(name);
        return found != caps.end() && found->second;
    };
    const bool supports_tools = supports("supports_tools") && supports("supports_tool_calls");
    if ((!inputs.tools.empty() || has_tool_history) && !supports_tools) {
        return make_template_result(env, common_chat_params{}, false);
    }

    try {
        const common_chat_params params = common_chat_templates_apply(
            handle->chat_templates.get(), inputs
        );
        return make_template_result(env, params, supports_tools);
    } catch (const std::exception &) {
        LOGE("Failed to apply model chat template");
        return nullptr;
    }
}

} // extern "C"
