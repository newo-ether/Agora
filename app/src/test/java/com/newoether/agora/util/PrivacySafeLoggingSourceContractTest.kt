package com.newoether.agora.util

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class PrivacySafeLoggingSourceContractTest {
    @Test
    fun `production logging routes through the privacy-aware wrapper`() {
        val sourceRoot = locateMainSourceRoot()
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "DebugLog.kt" }
            .forEach { file ->
                val source = file.readText()
                assertFalse(
                    "${file.relativeTo(sourceRoot)} imports Android Log directly",
                    source.contains("import android.util.Log"),
                )
                assertFalse(
                    "${file.relativeTo(sourceRoot)} invokes Android Log directly",
                    source.contains("android.util.Log."),
                )
            }
    }

    @Test
    fun `production log calls do not receive known sensitive raw values`() {
        val sourceRoot = locateMainSourceRoot()
        val forbiddenFragments = listOf(
            "\$jsonBody",
            "\$requestBodyJson",
            "\$requestJson",
            "\$errorRaw",
            "\$responseBody",
            "\$baseUrl",
            "\$endpointUrl",
            "\$finalUrlString",
            "\$url",
            "\$domainName",
            "\$host",
            "\$imagePath",
            "\$imagePaths",
            "\$modelPath",
            "\$mmprojPath",
            "\${message.text}",
            "\${result.reason}",
            "\${task.name}",
            "\${file.absolutePath}",
            "\${e.message}",
            "\${e.localizedMessage}",
            "prompt.take(",
            "body?.string()",
        )

        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "DebugLog.kt" }
            .forEach { file ->
                val calls = extractDebugLogCalls(file.readText())
                forbiddenFragments.forEach { fragment ->
                    assertFalse(
                        "${file.relativeTo(sourceRoot)} logs sensitive fragment: $fragment",
                        calls.contains(fragment),
                    )
                }
            }
    }

    @Test
    fun `generation diagnostics do not log request content private endpoints or raw responses`() {
        val sourceRoot = locateMainSourceRoot()
        val protectedSources = listOf(
            "com/newoether/agora/api/HttpClient.kt",
            "com/newoether/agora/api/LlamaChatEngine.kt",
            "com/newoether/agora/api/LlamaEngine.kt",
            "com/newoether/agora/api/anthropic/AnthropicProvider.kt",
            "com/newoether/agora/api/anthropic/AnthropicMessagesTransport.kt",
            "com/newoether/agora/api/gemini/GeminiProvider.kt",
            "com/newoether/agora/api/local/LocalProvider.kt",
            "com/newoether/agora/api/ollama/OllamaProvider.kt",
            "com/newoether/agora/api/openai/BaseOpenAiProvider.kt",
            "com/newoether/agora/api/util/MessageConverter.kt",
            "com/newoether/agora/automation/TaskManager.kt",
            "com/newoether/agora/data/AutoBackupManager.kt",
        ).associateWith { relativePath ->
            extractDebugLogCalls(File(sourceRoot, relativePath).readText())
        }

        val forbiddenFragments = listOf(
            "BODY:",
            "prompt.take(",
            "POST \$url",
            "host=\$domainName",
            "endpoint=\$endpoint",
            "at \$endpointUrl",
            "from \$endpointUrl",
            "\${endpointUrls.first()}",
            ": \$errorRaw",
            "\$baseUrl/",
            "image: \$imagePath",
            "\${model.mmprojPath}",
            ": \$modelPath",
            "cursor '\$cursor'",
            "\${e.message}",
            "\${task.name}",
            "\${result.reason}",
        )

        protectedSources.forEach { (path, source) ->
            forbiddenFragments.forEach { fragment ->
                assertFalse("$path contains unsafe logging fragment: $fragment", source.contains(fragment))
            }
        }
    }

    private fun extractDebugLogCalls(source: String): String = buildString {
        var searchFrom = 0
        while (true) {
            val callStart = source.indexOf("DebugLog.", searchFrom)
            if (callStart < 0) return@buildString
            val argumentsStart = source.indexOf('(', callStart)
            if (argumentsStart < 0) return@buildString

            var depth = 0
            var inString = false
            var escaped = false
            var cursor = argumentsStart
            while (cursor < source.length) {
                val char = source[cursor]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == '"' -> inString = false
                    }
                } else {
                    when (char) {
                        '"' -> inString = true
                        '(' -> depth++
                        ')' -> {
                            depth--
                            if (depth == 0) {
                                append(source.substring(callStart, cursor + 1))
                                append('\n')
                                searchFrom = cursor + 1
                                break
                            }
                        }
                    }
                }
                cursor++
            }
            if (cursor >= source.length) return@buildString
        }
    }

    private fun locateMainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main Java source directory")
    }
}
