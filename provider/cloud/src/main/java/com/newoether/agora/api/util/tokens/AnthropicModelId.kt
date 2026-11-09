package com.newoether.agora.api.util.tokens

/**
 * The two things an Anthropic model id has to tell a cost model: which tier it is and which version.
 *
 * Published cost tables are keyed by "Claude Opus 4.5" style names, while ids arrive as
 * `claude-opus-4-5-20260101`, `claude-3-7-sonnet-20250219` or `anthropic/claude-opus-4-1`. Parsing
 * happens once here so the image tier and the envelope cost cannot disagree about a model.
 */
data class AnthropicModelId(
    val tier: Tier?,
    val major: Int?,
    val minor: Int,
) {
    enum class Tier { OPUS, SONNET, HAIKU }

    /** Version as a comparable pair, or null when the id carries no readable version. */
    fun versionAtLeast(major: Int, minor: Int): Boolean {
        val parsedMajor = this.major ?: return false
        return parsedMajor > major || (parsedMajor == major && this.minor >= minor)
    }

    companion object {
        /** Version pair such as `4-5` or `4.5`, never part of a longer number like a release date. */
        private val VERSION_PAIR = Regex("""(?<!\d)(\d{1,2})[-.](\d{1,2})(?!\d)""")
        private val MAJOR_ONLY = Regex("""(?<![\d.])(\d{1,2})(?![\d.])""")

        fun parse(modelName: String): AnthropicModelId {
            val name = modelName.substringAfterLast('/').lowercase()
            val tier = when {
                name.contains("opus") -> Tier.OPUS
                name.contains("sonnet") -> Tier.SONNET
                name.contains("haiku") -> Tier.HAIKU
                else -> null
            }
            VERSION_PAIR.find(name)?.let { match ->
                return AnthropicModelId(
                    tier = tier,
                    major = match.groupValues[1].toInt(),
                    minor = match.groupValues[2].toInt(),
                )
            }
            val major = MAJOR_ONLY.find(name)?.groupValues?.get(1)?.toInt()
            return AnthropicModelId(tier = tier, major = major, minor = 0)
        }
    }
}
