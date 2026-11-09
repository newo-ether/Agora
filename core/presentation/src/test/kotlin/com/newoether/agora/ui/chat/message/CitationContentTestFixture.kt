package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.CitationAnchor
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.CitationRecord

internal abstract class CitationContentTestFixture {
    protected fun citation(
        answer: String,
        title: String,
        url: String,
        ranges: Array<IntRange>,
    ): CitationRecord = requireNotNull(
        CitationPolicy.create(
            provider = "test",
            kind = "web",
            title = title,
            url = url,
            anchors = ranges.map { range ->
                CitationAnchor(
                    startIndex = range.first,
                    endIndex = range.last + 1,
                    citedText = answer.substring(range.first, range.last + 1),
                )
            },
            answerText = answer,
        ),
    )
}
