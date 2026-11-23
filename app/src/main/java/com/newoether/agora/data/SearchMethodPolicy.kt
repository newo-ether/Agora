package com.newoether.agora.data

import com.newoether.agora.util.Constants

/**
 * Conversation search settles on keyword whenever semantic retrieval cannot run.
 *
 * Both the persisted setting and every surface that presents it use this one rule, so no page can
 * show or execute a semantic selection while no embedding model is configured. Keeping the rule
 * pure makes the transition the same before and after the write reaches DataStore.
 */
internal fun settledSearchMethod(method: String, semanticAvailable: Boolean): String =
    if (!semanticAvailable && method == Constants.SEARCH_METHOD_RAG) {
        Constants.SEARCH_METHOD_KEYWORD
    } else {
        method
    }
