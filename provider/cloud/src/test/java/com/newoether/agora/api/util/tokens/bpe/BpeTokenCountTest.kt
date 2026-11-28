package com.newoether.agora.api.util.tokens.bpe

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Counts checked against the reference tokenizer.
 *
 * The expectations in `o200k_ref.json` were produced offline with tiktoken 0.12.0 over the same
 * vocabulary file the app ships, so a disagreement here means this implementation is wrong rather
 * than merely different. The vocabulary is read straight from the asset directory, which is the file
 * the APK carries.
 */
class BpeTokenCountTest {

    @Serializable
    private data class Case(val text: String, val tokens: Int)

    private val vocabulary: BpeVocabulary by lazy {
        repoFile(ASSET).bufferedReader().useLines(BpeVocabulary::parseTiktoken)
    }

    private val cases: Map<String, Case> by lazy {
        Json.decodeFromString<Map<String, Case>>(repoFile(REFERENCE).readText())
    }

    @Test
    fun `the shipped vocabulary loads completely`() {
        // o200k_base publishes 199998 mergeable ranks; a short load would silently overcount.
        assertEquals(199_998, vocabulary.size)
    }

    @Test
    fun `every reference text counts exactly as the reference tokenizer does`() {
        val wrong = cases.filter { (_, case) ->
            BpeTokenCount.of(case.text, vocabulary) != case.tokens.toLong()
        }.map { (name, case) ->
            "$name: expected ${case.tokens}, got ${BpeTokenCount.of(case.text, vocabulary)}"
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `the reference set covers the cases that break naive counting`() {
        // A guard on the fixture itself: these are the shapes a character-based estimate gets wrong.
        assertTrue(cases.keys.containsAll(listOf("chinese", "emoji", "whitespace_runs", "code")))
        assertTrue(cases.size >= 20)
    }

    @Test
    fun `the pattern avoids syntax that only the JVM accepts`() {
        // Tests run on the JVM, the app on Android's ICU engine. ICU rejects inline flags such as
        // `(?U)` at class-init time, so a pattern that passes here could still crash on a device.
        // Only non-capturing groups and lookaheads may follow "(?".
        val inlineFlag = Regex("""\(\?(?![:!=])""")
        assertEquals(null, inlineFlag.find(BpeTokenCount.O200K_PATTERN)?.value)
        // `\s` and `\S` differ between the two engines without a flag, so they must not appear.
        assertTrue(!BpeTokenCount.O200K_PATTERN.contains("\\s", ignoreCase = true))
    }

    @Test
    fun `memoised counts equal direct counts on first and repeated lookups`() {
        val memo = com.newoether.agora.api.util.tokens.BpeTextTokenCounter.CountMemo
        cases.values.map { it.text.repeat(8) }.forEach { text ->
            val direct = BpeTokenCount.of(text, vocabulary)
            assertEquals(direct, memo.countOf(text, vocabulary))
            assertEquals(direct, memo.countOf(String(text.toCharArray()), vocabulary))
        }
    }

    @Test
    fun `empty text costs nothing`() {
        assertEquals(0L, BpeTokenCount.of("", vocabulary))
    }

    @Test
    fun `a single byte token is found in the vocabulary`() {
        val letter = "a".toByteArray(Charsets.UTF_8)
        assertNotEquals(BpeVocabulary.ABSENT, vocabulary.rankOf(letter, 0, letter.size))
    }

    @Test
    fun `an unknown byte sequence has no rank`() {
        // A lone UTF-8 continuation byte is never a token on its own.
        val orphan = byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte())
        assertEquals(BpeVocabulary.ABSENT, vocabulary.rankOf(orphan, 0, orphan.size))
    }

    private fun repoFile(relativePath: String): File {
        var directory = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(directory, "settings.gradle.kts").isFile) {
                return File(directory, relativePath).also { check(it.isFile) { "Missing fixture: $it" } }
            }
            directory = directory.parentFile ?: return@repeat
        }
        error("Cannot locate repository fixture: $relativePath")
    }

    private companion object {
        const val ASSET = "app/src/main/assets/tokenizers/o200k_base.tiktoken"
        const val REFERENCE = "app/src/test/resources/tokenizers/o200k_ref.json"
    }
}
