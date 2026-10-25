package com.newoether.agora.ui.chat.message

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.tool.ToolExecutionResult
import com.newoether.agora.viewmodel.finalToolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ToolSummaryRegressionTest {
    private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources

    @Test
    fun activeMemoryReaderKeepsItsTargetInEveryReadState() {
        val call = MessageSegment(type = "tool", toolName = "read_active_memory", toolArgs = "{}")
        assertEquals("Read Active Memory", resources.toolDisplayName(call))
        assertEquals("Reading active memory\u2026", resources.toolSummary(call))
        assertEquals("Reading active memory\u2026", resources.toolSummary(call.copy(toolState = ToolExecutionStates.RUNNING)))
        for (text in listOf("body", "Error: an example", """{"error":"example"}""")) {
            val completed = call.copy(toolResult = text, toolState = finalToolState(ToolExecutionResult(text), "read_active_memory"))
            val presentation = ToolPresentationResolver.resolve(completed)
            assertEquals(ToolPresentationState.COMPLETED, presentation.state)
            assertEquals(text, presentation.rawResult)
            assertEquals("{}", presentation.rawArguments)
            assertEquals("Read active memory", resources.toolSummary(completed))
        }
        assertEquals("Active memory is empty", resources.toolSummary(call.copy(toolResult = "", toolState = ToolExecutionStates.SUCCEEDED)))
        assertEquals("Failed to read active memory", resources.toolSummary(call.copy(toolResult = "", toolState = ToolExecutionStates.FAILED)))
        assertEquals("Command timeout", resources.toolSummary(call.copy(toolResult = "Error: command timeout", toolState = ToolExecutionStates.FAILED)))
        val file = call.copy(toolName = "read_memory_file", toolArgs = """{"name":"Notes.md"}""")
        assertEquals("Read Memory", resources.toolDisplayName(file))
        assertEquals("Reading Notes.md\u2026", resources.toolSummary(file))
        assertEquals("Read Notes.md", resources.toolSummary(file.copy(toolResult = "body", toolState = ToolExecutionStates.SUCCEEDED)))
    }

    @Test
    fun activeMemoryReaderHasLocalizedTitleAndStateSummaries() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        for ((language, expected) in listOf(
            "zh" to listOf("读取活跃记忆", "正在读取活跃记忆…", "已读取活跃记忆", "活跃记忆为空", "读取活跃记忆失败"),
            "zh-TW" to listOf("讀取活躍記憶", "正在讀取活躍記憶…", "已讀取活躍記憶", "活躍記憶為空", "讀取活躍記憶失敗"),
        )) {
            val (title, active, completed, empty, failed) = expected
            val configuration = android.content.res.Configuration(app.resources.configuration)
            configuration.setLocale(java.util.Locale.forLanguageTag(language))
            val localized = app.createConfigurationContext(configuration).resources
            val call = MessageSegment(type = "tool", toolName = "read_active_memory", toolArgs = "{}")
            assertEquals(title, localized.toolDisplayName(call))
            assertEquals(active, localized.toolSummary(call))
            assertEquals(completed, localized.toolSummary(call.copy(toolResult = "body", toolState = ToolExecutionStates.SUCCEEDED)))
            assertEquals(empty, localized.toolSummary(call.copy(toolResult = "", toolState = ToolExecutionStates.EMPTY)))
            assertEquals(failed, localized.toolSummary(call.copy(toolResult = "", toolState = ToolExecutionStates.FAILED)))
        }
    }

    @Test
    fun malformedStandInCardIsLabelledWithTheToolTheModelNamed() {
        val segment = MessageSegment(
            type = "tool",
            toolName = "agora_malformed_tool_call",
            toolArgs = """{"error":"arguments were not a valid JSON object","original_name":"ask_user","original_arguments":"[{\"question\":\"Pick one\"}]"}""",
            toolResult = "Error: the previous tool call was malformed and was not executed",
            toolState = ToolExecutionStates.FAILED,
        )
        assertEquals("Ask User", resources.toolDisplayName(segment))
        assertEquals(
            "The previous tool call was malformed and was not executed",
            resources.toolSummary(segment),
        )
    }

    @Test
    fun everyFailureKindShowsItsConcreteReasonBeforeGenericActionText() {
        for (name in listOf("read_memory_file", "read_skill_file", "file_read", "execute_shell_command", "web_search", "mcp_test", "unknown")) {
            val segment = MessageSegment(type = "tool", toolName = name,
                toolResult = "Error: command timeout", toolState = ToolExecutionStates.FAILED)
            assertEquals("Command timeout", resources.toolSummary(segment))
        }
        val generic = MessageSegment(type = "tool", toolName = "mcp_test", toolResult = "", toolState = ToolExecutionStates.FAILED)
        assertEquals("Tool call failed", resources.toolSummary(generic))
        assertEquals("Tool call failed", resources.toolSummary(generic.copy(toolResult = "Error:")))
    }

    @Test
    fun successfulReadTextNeverDeclaresFailureByItsSpelling() {
        for (name in listOf("read_memory_file", "read_active_memory", "read_skill_file", "mcp_test_read_abcdef")) {
            for (text in listOf("Error handling guidelines", "Errors are examples", "Error: an example", """{"error":"example"}""")) {
                val state = finalToolState(ToolExecutionResult(text), name)
                val presentation = ToolPresentationResolver.resolve(
                    MessageSegment(type = "tool", toolName = name, toolResult = text, toolState = state),
                )
                assertEquals(ToolExecutionStates.SUCCEEDED, state)
                assertEquals(ToolPresentationState.COMPLETED, presentation.state)
                assertNull(presentation.errorMessage)
                assertEquals(text, presentation.rawResult)
            }
        }
    }

    @Test
    fun batchReadsAndNamesPrecedenceUseAllEffectiveTargets() {
        for (name in listOf("read_memory_file", "read_skill_file")) {
            val batch = MessageSegment(type = "tool", toolName = name,
                toolArgs = """{"name":"ignored.md","names":["a.md","b.md"]}""",
                toolResult = "body", toolState = ToolExecutionStates.SUCCEEDED)
            val presentation = ToolPresentationResolver.resolve(batch)
            assertEquals(2, presentation.count)
            assertNull(presentation.subject)
            assertEquals("Read 2 files", resources.toolSummary(batch))
            assertEquals("Reading 2 files\u2026", resources.toolSummary(batch.copy(toolResult = null, toolState = ToolExecutionStates.RUNNING)))
            val single = batch.copy(toolArgs = """{"name":"ignored.md","names":["actual.md"]}""")
            assertEquals("Read actual.md", resources.toolSummary(single))
            assertEquals("Read ignored.md", resources.toolSummary(batch.copy(toolArgs = """{"name":"ignored.md","names":[]}""")))
        }
    }

    @Test
    fun partialReadArraysDoNotInventACompletedCountOrFirstFileSubject() {
        for (name in listOf("read_memory_file", "read_skill_file")) {
            val segment = MessageSegment(type = "tool", toolName = name,
                toolArgs = """{"names":["a.md","b""", toolState = ToolExecutionStates.RUNNING)
            val p = ToolPresentationResolver.resolve(segment)
            assertNull(p.subject)
            assertNull(p.count)
            assertEquals("Reading\u2026", resources.toolSummary(segment))
        }
        for (name in listOf("read_skill_file", "create_skill_file", "edit_skill_file", "delete_skill_file")) {
            val p = ToolPresentationResolver.resolve(MessageSegment(type = "tool", toolName = name,
                toolArgs = """{"name":"Known.md","content":"""", toolState = ToolExecutionStates.RUNNING))
            assertEquals("Known.md", p.subject)
        }
    }

    @Test
    fun emptyReadsAndEmptyConversationPageDoNotClaimNormalCompletion() {
        for (name in listOf("read_memory_file", "read_skill_file")) {
            val empty = MessageSegment(type = "tool", toolName = name, toolArgs = """{"name":"empty.md"}""",
                toolResult = "", toolState = ToolExecutionStates.EMPTY)
            assertEquals("Read empty.md \u00b7 empty", resources.toolSummary(empty))
        }
        val page = MessageSegment(type = "tool", toolName = "read_conversation",
            toolResult = """{"title":"A","messages":[],"total_messages":20,"offset":30}""",
            toolState = ToolExecutionStates.SUCCEEDED)
        assertEquals(ToolPresentationState.EMPTY, ToolPresentationResolver.resolve(page).state)
        assertEquals("No conversation messages returned", resources.toolSummary(page))
    }

    @Test
    fun countSummariesSelectSingularAndPluralAndRetainCountsWithoutSubjects() {
        for ((name, field, singular, plural) in listOf(
            listOf("list_memory_files", "files", "Looked through 1 saved memory", "Looked through 2 saved memories"),
            listOf("list_skill_files", "files", "Listed 1 skill", "Listed 2 skills"),
            listOf("web_search", "results", "Found 1 result", "Found 2 results"),
            listOf("search_conversations", "results", "Found 1 conversation", "Found 2 conversations"),
            listOf("list_conversations", "conversations", "Listed 1 conversation", "Listed 2 conversations"),
            listOf("list_shells", "devices", "Listed 1 shell", "Listed 2 shells"),
            listOf("list_shell_jobs", "jobs", "Listed 1 shell job", "Listed 2 shell jobs"),
            listOf("file_glob", "files", "Found 1 file", "Found 2 files"),
            listOf("file_grep", "matches", "Found 1 match", "Found 2 matches"),
            listOf("list_tasks", "tasks", "Listed 1 task", "Listed 2 tasks"),
        )) {
            val call = MessageSegment(type = "tool", toolName = name, toolState = ToolExecutionStates.SUCCEEDED)
            assertEquals(singular, resources.toolSummary(call.copy(toolResult = """{"$field":[{}]}""")))
            assertEquals(plural, resources.toolSummary(call.copy(toolResult = """{"$field":[{},{}]}""")))
        }
        for ((name, noun) in listOf("web_search" to "result", "search_conversations" to "conversation")) {
            val call = MessageSegment(type = "tool", toolName = name, toolArgs = """{"query":"MixedCase"}""",
                toolResult = """{"results":[{}]}""", toolState = ToolExecutionStates.SUCCEEDED)
            assertEquals("Found 1 $noun for \"MixedCase\"", resources.toolSummary(call))
        }
    }

    @Test
    fun longSubjectsShowTruncationWithoutChangingTheOriginalArguments() {
        val path = "MixedCase".repeat(20)
        val args = """{"path":"$path"}"""
        val segment = MessageSegment(type = "tool", toolName = "file_read", toolArgs = args,
            toolResult = """{"content":"body"}""", toolState = ToolExecutionStates.SUCCEEDED)
        val p = ToolPresentationResolver.resolve(segment)
        assertEquals(path.take(119) + "\u2026", p.subject)
        assertEquals(args, p.rawArguments)
        assertEquals("12345", normalizeToolSummarySubject("12345", 5))
        assertEquals("\u2026", normalizeToolSummarySubject("12", 1))
        assertEquals("Mixed Case", normalizeToolSummarySubject(" Mixed\n Case "))
    }

    @Test
    fun localizedQuantityRulesRenderRussianFormsAndChineseCounts() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        for ((language, count, expected) in listOf(
            Triple("ru", 1, "Найден 1 файл"), Triple("ru", 2, "Найдены 2 файла"),
            Triple("ru", 5, "Найдено 5 файлов"), Triple("ru", 21, "Найден 21 файл"),
            Triple("zh", 1, "找到 1 个文件"), Triple("zh", 2, "找到 2 个文件"),
        )) {
            val configuration = android.content.res.Configuration(app.resources.configuration)
            configuration.setLocale(java.util.Locale.forLanguageTag(language))
            val localized = app.createConfigurationContext(configuration).resources
            assertEquals(expected, localized.getQuantityString(com.newoether.agora.R.plurals.tool_found_files, count, count))
        }
        for ((language, expected) in listOf(
            "zh" to "已回答 2 个问题，共 3 个",
            "ru" to "Отвечено на 2 из 3 вопросов",
        )) {
            val configuration = android.content.res.Configuration(app.resources.configuration)
            configuration.setLocale(java.util.Locale.forLanguageTag(language))
            val localized = app.createConfigurationContext(configuration).resources
            assertEquals(expected, localized.getQuantityString(com.newoether.agora.R.plurals.tool_questions_mixed, 3, 2, 3))
        }
    }

    @Test
    fun everyKnownKindAndGenericToolUsesTheCanonicalSummaryPath() {
        val names = listOf(
            "list_memory_files", "read_memory_file", "create_memory_file", "edit_memory_file", "delete_memory_file", "read_active_memory", "update_active_memory",
            "list_skill_files", "read_skill_file", "create_skill_file", "edit_skill_file", "delete_skill_file",
            "web_search", "web_fetch", "search_conversations", "list_conversations", "read_conversation",
            "list_shells", "execute_shell_command", "list_shell_jobs", "get_shell_job", "wait_for_job", "stop_shell_job",
            "file_read", "file_write", "file_edit", "file_multi_edit", "file_glob", "file_grep", "view_image", "generate_image",
            "create_task", "list_tasks", "delete_task", "start_loop", "stop_loop", "ask_user", "mcp_list_servers", "mcp_search_tools", "mcp_test", "unknown",
            "read_todo", "update_todo", "spawn_agent", "send_agent_message", "wait_agents", "list_agents", "stop_agent", "delete_agent",
        ) + com.newoether.agora.phone.PHONE_TOOL_NAMES
        assertEquals(ToolKind.entries.toSet(), names.map(ToolPresentationResolver::kindForToolName).toSet())
        for (name in names) {
            val call = MessageSegment(type = "tool", toolName = name, toolArgs = "{}")
            org.junit.Assert.assertTrue(resources.toolSummary(call).isNotBlank())
            org.junit.Assert.assertTrue(resources.toolSummary(call.copy(toolResult = "{}",
                toolState = ToolExecutionStates.SUCCEEDED)).isNotBlank())
            assertEquals("Command timeout", resources.toolSummary(call.copy(toolResult = "Error: command timeout",
                toolState = ToolExecutionStates.FAILED)))
        }
    }

    @Test
    fun declaredErrorRetainsRawResultAndProvidesTheReason() {
        for (name in listOf("read_memory_file", "read_active_memory")) {
            val text = "Error executing tool '$name': command timeout"
            val state = finalToolState(ToolExecutionResult(text, isError = true), name)
            val presentation = ToolPresentationResolver.resolve(
                MessageSegment(type = "tool", toolName = name, toolResult = text, toolState = state),
            )
            assertEquals(ToolExecutionStates.FAILED, state)
            assertEquals(ToolPresentationState.FAILED, presentation.state)
            assertEquals(text, presentation.rawResult)
            assertEquals("Command timeout", toolFailureReasonSummary(presentation.errorMessage))
            assertEquals(
                ToolExecutionStates.FAILED,
                finalToolState(ToolExecutionResult("body", structuredContent = """{"error":"command timeout"}"""), name),
            )
        }
    }

    @Test
    fun jobLifecycleUsesExistingStopModelWithoutClaimingCompletion() {
        for (name in listOf("execute_shell_command", "get_shell_job", "wait_for_job", "stop_shell_job")) {
            for (state in listOf("running", "stopping", "settling", "stopped", "interrupted")) {
                for (nested in listOf(false, true)) {
                    val raw = """{"job_id":"ID","state":"$state"}"""
                    val result = if (nested) """{"job_id":"ID","result":$raw}""" else raw
                    val wire = finalToolState(ToolExecutionResult(result), name)
                    val segment = MessageSegment(type = "tool", toolName = name, toolResult = result, toolState = wire)
                    val summary = resources.toolSummary(segment)
                    if (state == "stopped" || state == "interrupted") {
                        assertEquals(ToolExecutionStates.STOPPED, wire)
                        assertEquals(if (state == "interrupted") "Interrupted" else "Stopped", summary)
                    } else {
                        assertEquals(ToolExecutionStates.BACKGROUND_RUNNING, wire)
                        assertEquals("Running in background", summary)
                    }
                }
            }
        }
    }

    @Test
    fun editOperationAndQuestionResultsUseConcreteOutcomeWording() {
        for (name in listOf("edit_memory_file", "edit_skill_file")) {
            val rename = MessageSegment(type = "tool", toolName = name,
                toolArgs = """{"name":"Old.md","operation":"rename","new_name":"New.md"}""",
                toolResult = "updated", toolState = ToolExecutionStates.SUCCEEDED)
            assertEquals("Renamed Old.md to New.md", resources.toolSummary(rename))
            assertEquals("Renaming Old.md to New.md\u2026", resources.toolSummary(rename.copy(toolResult = null, toolState = ToolExecutionStates.RUNNING)))
            assertEquals("Updated description of Old.md", resources.toolSummary(rename.copy(toolArgs = """{"name":"Old.md","operation":"describe"}""")))
        }
        val ask = MessageSegment(type = "tool", toolName = "ask_user", toolState = ToolExecutionStates.RUNNING)
        assertEquals("Waiting for answers\u2026", resources.toolSummary(ask))
        for ((result, expected) in listOf(
            """{"delivery":"queued","questions":1}""" to "Queued 1 question",
            """{"delivery":"queued","questions":2}""" to "Queued 2 questions",
            """{"answered":true}""" to "Answered 1 question",
            """{"answered":false}""" to "Skipped 1 question",
            """{"answers":[{"answered":true},{"answered":false}]}""" to "Answered 1 of 2 questions",
            """{"answers":[{"answered":true},{"answered":true},{"answered":true}]}""" to "Answered 3 questions",
            """{"answers":[{"answered":true},{"answered":false},{"answered":true}]}""" to "Answered 2 of 3 questions",
        )) assertEquals(expected, resources.toolSummary(ask.copy(toolResult = result, toolState = ToolExecutionStates.SUCCEEDED)))
        assertEquals("Loop already stopped", resources.toolSummary(MessageSegment(type = "tool", toolName = "stop_loop",
            toolResult = """{"status":"already_stopped"}""", toolState = ToolExecutionStates.SUCCEEDED)))
    }

    @Test
    fun completedWaitRetainsItsActionAndQuestionGroupsDoNotInventAnswers() {
        val wait = MessageSegment(type = "tool", toolName = "wait_for_job",
            toolResult = """{"job_id":"ID","state":"succeeded","exit_code":7}""",
            toolState = ToolExecutionStates.SUCCEEDED)
        assertEquals("Waited for shell job ID", resources.toolSummary(wait))
        for ((answers, expected) in listOf(
            """[{"answered":true}]""" to "Answered 1 question",
            """[{"answered":true},{"answered":true}]""" to "Answered 2 questions",
            """[{"answered":false},{"answered":false}]""" to "Skipped 2 questions",
        )) assertEquals(expected, resources.toolSummary(MessageSegment(type = "tool", toolName = "ask_user",
            toolResult = """{"answers":$answers}""", toolState = ToolExecutionStates.SUCCEEDED)))
        val error = MessageSegment(type = "tool", toolName = "ask_user",
            toolResult = """{"error":"bad_arguments","detail":"arguments are not a JSON object."}""",
            toolState = ToolExecutionStates.FAILED)
        assertEquals("Arguments are not a JSON object", resources.toolSummary(error))
    }

    @Test
    fun reasonSummaryPreservesIdentifiersAndOnlyCapitalizesNaturalLanguage() {
        assertEquals("Command timeout", toolFailureReasonSummary("Error: command timeout."))
        assertEquals("Permission denied: /tmp/MixedCase", toolFailureReasonSummary("permission denied: /tmp/MixedCase"))
        assertEquals("myFile.md not found", toolFailureReasonSummary("Error: myFile.md not found"))
        assertEquals("/tmp/MixedCase missing", toolFailureReasonSummary("/tmp/MixedCase missing"))
        assertEquals("old_string is required", toolFailureReasonSummary("old_string is required"))
        assertNull(toolFailureReasonSummary("Error:"))
        assertNull(toolFailureReasonSummary("error"))
        assertNull(toolFailureReasonSummary(null))
    }

    @Test
    fun structuredReasonAndNoResultsKeepTheirDeclaredSemantics() {
        val error = ToolExecutionResult("protocol", structuredContent = """{"error":"command_timeout","message":"command timeout"}""")
        assertEquals(ToolExecutionStates.FAILED, finalToolState(error, "mcp_test_tool_abcdef"))
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(type = "tool", toolName = "mcp_test_tool_abcdef", toolResult = error.text,
                toolStructuredResult = error.structuredContent, toolState = ToolExecutionStates.FAILED),
        )
        assertEquals("Command timeout", toolFailureReasonSummary(presentation.errorMessage))
        assertEquals(ToolExecutionStates.EMPTY, finalToolState(
            ToolExecutionResult("""{"error":"no_results"}""", isError = true), "web_search",
        ))
        assertEquals(ToolExecutionStates.SUCCEEDED, finalToolState(
            ToolExecutionResult("""{"exit_code":7,"state":"failed"}"""), "execute_shell_command",
        ))
    }
}
