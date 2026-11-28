package com.newoether.agora.util

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacySafeLoggingSourceContractTest {
    @Test
    fun `production logging routes through the privacy-aware wrapper`() {
        val repository = locateRepositoryRoot()
        productionSources(repository).forEach { file ->
            val source = file.readText()
            assertFalse(
                "${file.relativeTo(repository)} imports Android Log directly",
                source.contains("import android.util.Log"),
            )
            assertFalse(
                "${file.relativeTo(repository)} invokes Android Log directly",
                source.contains("android.util.Log."),
            )
        }

        val facade = File(repository, APP_LOG_WRAPPER).readText()
        val cloud = File(repository, CLOUD_LOG_WRAPPER).readText()
        assertTrue(facade.contains("get() = CloudDebugLog.forceEnabled"))
        assertTrue(facade.contains("CloudDebugLog.forceEnabled = value"))
        assertTrue(facade.contains("fun init(context: Context) = CloudDebugLog.init(context)"))
        assertTrue(facade.contains("safeThrowableSummary(tr: Throwable): String = CloudDebugLog.safeThrowableSummary(tr)"))
        assertTrue(cloud.contains("enabled = forceEnabled || (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0"))
        assertTrue(cloud.contains("private val active: Boolean get() = forceEnabled || enabled"))
        val compactCloud = cloud.replace(Regex("\\s+"), " ")
        listOf("d", "e", "w").forEach { level ->
            assertTrue(facade.contains("fun $level(tag: String, msg: String) = CloudDebugLog.$level(tag, msg)"))
            assertTrue(facade.contains("fun $level(tag: String, msg: String, tr: Throwable) = CloudDebugLog.$level(tag, msg, tr)"))
            assertTrue(compactCloud.contains("fun $level(tag: String, msg: String) { if (active) Log.$level(tag, msg) }"))
            assertTrue(compactCloud.contains("fun $level(tag: String, msg: String, tr: Throwable) { if (active) Log.$level(tag, \"\$msg \${safeThrowableSummary(tr)}\") }"))
        }
        val summary = cloud.substringAfter("fun safeThrowableSummary(tr: Throwable): String")
        assertTrue(summary.contains("append(tr.javaClass.name)"))
        assertTrue(summary.contains(".take(MAX_SAFE_STACK_FRAMES)"))
        assertTrue(summary.contains("MAX_SAFE_STACK_FRAMES = 24"))
        listOf("tr.message", "tr.localizedMessage", "tr.cause", "printStackTrace", "getStackTraceString").forEach { raw ->
            assertFalse("Cloud throwable summary exposes $raw", summary.contains(raw))
        }
    }

    @Test
    fun `production log calls do not receive known sensitive raw values`() {
        val repository = locateRepositoryRoot()
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

        productionSources(repository).forEach { file ->
            val calls = extractDebugLogCalls(file.readText())
            forbiddenFragments.forEach { fragment ->
                assertFalse(
                    "${file.relativeTo(repository)} logs sensitive fragment: $fragment",
                    calls.contains(fragment),
                )
            }
        }
    }

    @Test
    fun `generation diagnostics do not log request content private endpoints or raw responses`() {
        val repository = locateRepositoryRoot()
        val protectedSources = listOf(
            "provider/cloud/src/main/java/com/newoether/agora/api/HttpClient.kt",
            "app/src/main/java/com/newoether/agora/api/LlamaChatEngine.kt",
            "app/src/main/java/com/newoether/agora/api/LlamaEngine.kt",
            "provider/cloud/src/main/java/com/newoether/agora/api/anthropic/AnthropicProvider.kt",
            "provider/cloud/src/main/java/com/newoether/agora/api/gemini/GeminiProvider.kt",
            "app/src/main/java/com/newoether/agora/api/local/LocalProvider.kt",
            "provider/cloud/src/main/java/com/newoether/agora/api/ollama/OllamaProvider.kt",
            "provider/cloud/src/main/java/com/newoether/agora/api/openai/BaseOpenAiProvider.kt",
            "provider/cloud/src/main/java/com/newoether/agora/api/util/MessageConverter.kt",
            "app/src/main/java/com/newoether/agora/automation/TaskManager.kt",
            "app/src/main/java/com/newoether/agora/data/AutoBackupManager.kt",
        ).associateWith { relativePath ->
            extractDebugLogCalls(File(repository, relativePath).readText())
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

    private fun productionSources(repository: File): Sequence<File> = sequence {
        listOf("app/src/main/java", "provider/cloud/src/main/java").forEach { path ->
            val root = File(repository, path)
            check(root.isDirectory) { "Missing production source root: $root" }
            yieldAll(root.walkTopDown().filter {
                it.isFile && it.extension == "kt" &&
                    it.relativeTo(repository).invariantSeparatorsPath !in setOf(APP_LOG_WRAPPER, CLOUD_LOG_WRAPPER)
            })
        }
    }

    private fun locateRepositoryRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the repository root")
    }

    private companion object {
        const val APP_LOG_WRAPPER = "app/src/main/java/com/newoether/agora/util/DebugLog.kt"
        const val CLOUD_LOG_WRAPPER = "provider/cloud/src/main/java/com/newoether/agora/util/CloudDebugLog.kt"
    }
}
