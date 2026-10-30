#pragma once

#include "llama_chat_callbacks.h"
#include "llama_chat_handle.h"
#include "llama_chat_template.h"
#include "nlohmann/json.hpp"

namespace agora::chat {

struct NativeChatParser {
    common_chat_parser_params params;
    common_chat_msg message;
    // Generated pieces with their token ids, as llama-server feeds its parser: the PEG parser
    // matches special tokens such as <think> by id.
    common_chat_input generated;
    std::string initialization_error;

    NativeChatParser(const TemplateSamplingMetadata & metadata, ChatHandle & handle) {
        params.format = metadata.format;
        params.generation_prompt = common_chat_input_tokenize(handle.vocab, metadata.generation_prompt);
        if (metadata.parser.empty()) {
            if (metadata.format != COMMON_CHAT_FORMAT_CONTENT_ONLY) {
                initialization_error = "Chat template parser metadata is missing";
            }
            return;
        }
        ChatHandle::CachedTemplate cached;
        if (!handle.find_template(metadata.parser, cached)) {
            initialization_error = "Chat template parser has expired; apply the template again";
            return;
        }
        if (cached.parser) params.parser = *cached.parser;
    }

    void add_token(const std::string & piece, llama_token token) {
        generated.append(piece, token);
    }

    bool update(
        JNIEnv * env,
        jobject callback,
        const NativeChatCallbacks & methods,
        const char * data,
        size_t length,
        bool is_partial,
        std::string & error
    ) {
        if (!initialization_error.empty()) {
            error = initialization_error;
            return false;
        }
        (void) data;
        (void) length;  // the text arrives token by token through add_token
        try {
            common_chat_msg next = common_chat_parse(generated, is_partial, params);
            if (next.empty()) {
                if (!is_partial) message = {};
                return true;
            }
            const auto diffs = common_chat_msg_diff::compute_diffs(message, next);
            message = std::move(next);
            for (const auto & diff : diffs) {
                if (!diff.reasoning_content_delta.empty() && !report_string(
                        env, callback, methods.on_thought,
                        diff.reasoning_content_delta
                    )) return false;
                if (!diff.content_delta.empty() && !report_string(
                        env, callback, methods.on_text, diff.content_delta
                    )) return false;
                if (diff.tool_call_index != std::string::npos) {
                    if (diff.tool_call_index >= message.tool_calls.size()) {
                        error = "Parsed tool call index is out of range";
                        return false;
                    }
                    if (!report_tool_call(
                            env, callback, methods,
                            diff.tool_call_index,
                            message.tool_calls[diff.tool_call_index]
                        )) return false;
                }
            }
            return true;
        } catch (const std::exception & exception) {
            error = exception.what();
            return false;
        }
    }

    bool finish(
        JNIEnv * env,
        jobject callback,
        const NativeChatCallbacks & methods,
        std::string & error
    ) {
        if (!update(env, callback, methods, "", 0, false, error)) return false;
        if (message.tool_calls.empty()) return true;
        for (const auto & call : message.tool_calls) {
            if (call.name.empty()) {
                error = "Parsed tool call is missing a name";
                return false;
            }
            try {
                const auto arguments = nlohmann::ordered_json::parse(
                    call.arguments.empty() ? "{}" : call.arguments
                );
                if (!arguments.is_object()) {
                    error = "Parsed tool call arguments are not a JSON object";
                    return false;
                }
            } catch (const std::exception & exception) {
                error = std::string("Parsed tool call arguments are incomplete: ") +
                    exception.what();
                return false;
            }
        }
        return report_tool_calls_complete(env, callback, methods);
    }
};

} // namespace agora::chat
