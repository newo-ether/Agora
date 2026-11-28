#pragma once

#include <jni.h>
#include "chat.h"
#include <set>
#include <string>
#include <vector>

namespace agora::chat {

struct TemplateSamplingMetadata {
    std::string prompt;
    std::string grammar;
    bool grammar_lazy = false;
    std::string generation_prompt;
    std::vector<common_grammar_trigger> grammar_triggers;
    std::vector<std::string> preserved_tokens;
    std::set<llama_token> preserved_token_ids;
    common_chat_format format = COMMON_CHAT_FORMAT_CONTENT_ONLY;
    std::string parser;
};

bool read_template_metadata(
    JNIEnv * env,
    jobject template_result,
    TemplateSamplingMetadata & metadata
);

} // namespace agora::chat
