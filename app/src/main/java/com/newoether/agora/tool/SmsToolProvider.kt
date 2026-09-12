package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.SmsDraft
import com.newoether.agora.data.SmsDraftStore
import com.newoether.agora.data.SmsReader
import com.newoether.agora.data.SmsStore
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.sms.SmsSender
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tool provider for SMS operations.
 * Provides tools: check_sms, read_sms, search_sms, send_sms, reply_sms
 *
 * Registration is gated per request in [definitions]: read tools only when the
 * build supports reading AND the user enabled it AND `READ_SMS` is granted; send
 * tools under the equivalent send gate. This keeps unusable tools out of the
 * model's tool list entirely (play flavor / disabled / revoked = invisible).
 */
class SmsToolProvider(
    private val smsStore: SmsStore,
    private val smsReader: SmsReader,
    private val smsSender: SmsSender,
    private val smsDraftStore: SmsDraftStore,
    private val settingsRepository: SettingsRepository,
) : ToolProvider {

    private val json = Json { ignoreUnknownKeys = true }

    private val toolNames = setOf("check_sms", "read_sms", "search_sms", "send_sms", "reply_sms")

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        val readTools = if (
            smsReader.isSupported() &&
            smsReader.hasPermission() &&
            settingsRepository.smsReadEnabled.value
        ) {
            READ_TOOL_DEFINITIONS
        } else {
            emptyList()
        }
        val sendTools = if (
            smsSender.isSupported() &&
            smsSender.hasPermission() &&
            settingsRepository.smsSendEnabled.value
        ) {
            SEND_TOOL_DEFINITIONS
        } else {
            emptyList()
        }
        return readTools + sendTools
    }

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        val argsStr = arguments.ifBlank { "{}" }
        val args = json.decodeFromString<Map<String, JsonElement>>(argsStr)
        fun arg(key: String): String? = (args[key] as? JsonPrimitive)?.content
        fun argLong(key: String): Long? = (args[key] as? JsonPrimitive)?.content?.toLongOrNull()

        when (name) {
            "check_sms" -> checkSms()
            "read_sms" -> readSms(argLong("id"))
            "search_sms" -> searchSms(arg("query"), argLong("limit")?.toInt())
            "send_sms" -> sendSms(arg("address"), arg("body"))
            "reply_sms" -> replySms(argLong("smsId"), arg("body"))
            else -> "Unknown tool: $name"
        }
    }

    override fun handles(name: String): Boolean = name in toolNames

    /** Lists the pending queue — messages the heartbeat has not shown the AI yet. */
    private suspend fun checkSms(): String {
        val pending = smsStore.getPendingSnapshot()
        if (pending.isEmpty()) {
            return "No new SMS messages. Use search_sms to find a known message by sender or text."
        }
        return buildString {
            append("You have ${pending.size} new SMS message(s) not yet shown:\n")
            for (msg in pending.take(20)) {
                append(
                    "ID: ${msg.id}\nFrom: ${msg.address.ifBlank { "(unknown sender)" }}\n" +
                        "Date: ${formatTimestamp(msg.date)}\nPreview: ${msg.preview}\n\n",
                )
            }
            append("Use read_sms with an id to fetch the full body.")
        }
    }

    /** Fetches the full body of a specific SMS by its system id. */
    private suspend fun readSms(id: Long?): String {
        if (id == null) return "Missing required argument: id"
        val msg = smsReader.readById(id)
            ?: return "No SMS found with id $id."
        return "From: ${msg.address.ifBlank { "(unknown sender)" }}\n" +
            "Date: ${formatTimestamp(msg.date)}\n" +
            "Read: ${if (msg.read) "yes" else "no"}\n" +
            "${msg.body}"
    }

    /** Searches the system inbox (address + body), newest first. */
    private suspend fun searchSms(query: String?, limitArg: Int?): String {
        if (query.isNullOrBlank()) return "Missing required argument: query"
        val limit = (limitArg ?: 10).coerceIn(1, 20)
        val matches = smsReader.search(query, limit)
        return if (matches.isEmpty()) {
            "No SMS messages found matching '$query'."
        } else {
            matches.joinToString("\n\n") { msg ->
                "ID: ${msg.id}\nFrom: ${msg.address}\nDate: ${formatTimestamp(msg.date)}\nPreview: ${msg.preview}"
            }
        }
    }

    private suspend fun sendSms(address: String?, body: String?): String {
        if (address.isNullOrBlank()) return "Missing required argument: address"
        if (body.isNullOrBlank()) return "Missing required argument: body"

        val draft = SmsDraft(address = address, body = body)
        smsDraftStore.addDraft(draft)

        return "SMS draft created for ${formatPhoneNumber(address)}:\n\"${body.take(160)}\"\n\nUser must confirm to send. A banner will appear in the chat UI."
    }

    private suspend fun replySms(smsId: Long?, body: String?): String {
        if (smsId == null) return "Invalid SMS ID"
        if (body.isNullOrBlank()) return "Missing required argument: body"

        // Resolve the original sender from the system provider; fall back to the
        // Room mirror when the provider is unavailable (e.g. permission revoked).
        val original = smsReader.readById(smsId)
            ?: smsStore.getMessageById(smsId)?.let { entity ->
                com.newoether.agora.data.SmsMessageData(
                    id = entity.id,
                    address = entity.address,
                    date = entity.date,
                    preview = entity.preview,
                    body = entity.body,
                    read = entity.read,
                )
            }
            ?: return "SMS with ID $smsId not found."
        if (original.address.isBlank()) {
            return "Original SMS has no sender address to reply to."
        }

        val draft = SmsDraft(
            address = original.address,
            body = body,
            inReplyToSmsId = smsId,
        )
        smsDraftStore.addDraft(draft)

        return "Reply draft created for ${formatPhoneNumber(original.address)}:\n\"${body.take(160)}\"\n\nUser must confirm to send. A banner will appear in the chat UI."
    }

    companion object {
        private val READ_TOOL_DEFINITIONS = listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "check_sms",
                    description = "List recently received SMS messages that the user hasn't been shown yet. " +
                        "Returns id, sender, date, and a short preview. Use read_sms with the id to fetch the " +
                        "full body. If nothing is pending, use search_sms to find a known message by sender or text.",
                    parameters = ToolParameters(properties = emptyMap()),
                ),
            ),
            ToolDefinition(
                function = ToolFunction(
                    name = "read_sms",
                    description = "Read the full body of a specific SMS by its id. Use check_sms or search_sms first to find an id.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "id" to ToolProperty("integer", "The SMS id returned by check_sms or search_sms"),
                        ),
                        required = listOf("id"),
                    ),
                ),
            ),
            ToolDefinition(
                function = ToolFunction(
                    name = "search_sms",
                    description = "Search SMS messages by sender (phone number) or body text. Returns newest-first, up to 20 matches.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "query" to ToolProperty("string", "Text to match against sender or body"),
                            "limit" to ToolProperty("integer", "Maximum results (default: 10, max: 20)"),
                        ),
                        required = listOf("query"),
                    ),
                ),
            ),
        )

        private val SEND_TOOL_DEFINITIONS = listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "send_sms",
                    description = "Draft an outgoing SMS. The draft is staged in a banner at the top of the chat " +
                        "so the user must explicitly tap Send before anything is actually sent. You cannot bypass this — " +
                        "the tool only creates the draft. After calling, tell the user what you drafted and ask them to review.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "address" to ToolProperty("string", "Recipient phone number"),
                            "body" to ToolProperty("string", "Message text"),
                        ),
                        required = listOf("address", "body"),
                    ),
                ),
            ),
            ToolDefinition(
                function = ToolFunction(
                    name = "reply_sms",
                    description = "Draft a reply to a received SMS. Looks up the original by id to pick the sender, then stages " +
                        "a draft in the review banner — the user must tap Send to actually send.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "smsId" to ToolProperty("integer", "Id of the SMS being replied to (from check_sms / search_sms)"),
                            "body" to ToolProperty("string", "Reply text"),
                        ),
                        required = listOf("smsId", "body"),
                    ),
                ),
            ),
        )

        private fun formatTimestamp(epochMs: Long): String {
            return java.time.Instant.ofEpochMilli(epochMs)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        }

        private fun formatPhoneNumber(address: String): String {
            return if (address.startsWith("+")) address else "+$address"
        }
    }
}