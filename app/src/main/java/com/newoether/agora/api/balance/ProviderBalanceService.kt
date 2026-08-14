package com.newoether.agora.api.balance

import com.newoether.agora.api.HttpClient
import com.newoether.agora.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.IOException

sealed interface ProviderBalanceResult {
    data class Success(
        val provider: String,
        val formattedBalance: String,
        val details: List<Pair<String, String>> = emptyList(),
        val rawUsage: String? = null,
        val isAvailable: Boolean = true,
    ) : ProviderBalanceResult

    data class Error(val message: String) : ProviderBalanceResult
}

object ProviderBalanceService {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun checkBalance(
        providerName: String,
        apiKey: String,
        baseUrl: String? = null,
    ): ProviderBalanceResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext ProviderBalanceResult.Error("API Key 为空")
        }

        try {
            when {
                providerName.equals(Constants.PROVIDER_DEEPSEEK, ignoreCase = true) ||
                providerName.contains("deepseek", ignoreCase = true) -> {
                    checkDeepSeekBalance(apiKey)
                }

                providerName.equals(Constants.PROVIDER_OPEN_ROUTER, ignoreCase = true) ||
                providerName.contains("openrouter", ignoreCase = true) -> {
                    checkOpenRouterBalance(apiKey)
                }

                providerName.contains("silicon", ignoreCase = true) ||
                providerName.contains("硅基", ignoreCase = true) -> {
                    checkSiliconFlowBalance(apiKey)
                }

                // Custom provider with custom base URL: check if it's OneAPI/NewAPI or standard OpenAI
                !baseUrl.isNullOrBlank() -> {
                    checkOneApiOrGenericBalance(providerName, apiKey, baseUrl)
                }

                providerName.equals(Constants.PROVIDER_GROQ, ignoreCase = true) -> {
                    checkGroqStatus(apiKey)
                }

                providerName.equals(Constants.PROVIDER_OPENAI, ignoreCase = true) -> {
                    checkOpenAiStatus(apiKey)
                }

                providerName.equals(Constants.PROVIDER_GOOGLE, ignoreCase = true) -> {
                    checkGoogleStatus(apiKey)
                }

                providerName.equals(Constants.PROVIDER_ANTHROPIC, ignoreCase = true) -> {
                    checkAnthropicStatus(apiKey)
                }

                providerName.equals(Constants.PROVIDER_QWEN, ignoreCase = true) -> {
                    checkQwenStatus(apiKey)
                }

                else -> {
                    ProviderBalanceResult.Error("该服务商暂未开放公开余额查询接口")
                }
            }
        } catch (e: Exception) {
            ProviderBalanceResult.Error("查询失败: ${e.localizedMessage ?: "网络错误"}")
        }
    }

    private fun checkDeepSeekBalance(apiKey: String): ProviderBalanceResult {
        val request = Request.Builder()
            .url("https://api.deepseek.com/user/balance")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()

        HttpClient.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return ProviderBalanceResult.Error("DeepSeek API 错误 (${response.code})")
            }
            val body = response.body?.string() ?: return ProviderBalanceResult.Error("响应体为空")
            val obj = json.parseToJsonElement(body).jsonObject
            val isAvailable = obj["is_available"]?.jsonPrimitive?.booleanOrNull ?: true
            val balanceInfos = obj["balance_infos"]?.jsonArray
            if (balanceInfos != null && balanceInfos.isNotEmpty()) {
                val first = balanceInfos[0].jsonObject
                val currency = first["currency"]?.jsonPrimitive?.content ?: "CNY"
                val symbol = if (currency.equals("USD", ignoreCase = true)) "$" else "¥"
                val total = first["total_balance"]?.jsonPrimitive?.content ?: "0.00"
                val granted = first["granted_balance"]?.jsonPrimitive?.content ?: "0.00"
                val toppedUp = first["topped_up_balance"]?.jsonPrimitive?.content ?: "0.00"

                return ProviderBalanceResult.Success(
                    provider = "DeepSeek",
                    formattedBalance = "$symbol$total",
                    isAvailable = isAvailable,
                    details = listOf(
                        "充值余额" to "$symbol$toppedUp",
                        "赠送余额" to "$symbol$granted",
                        "币种" to currency,
                    )
                )
            }
            return ProviderBalanceResult.Success("DeepSeek", "正常可用", isAvailable = isAvailable)
        }
    }

    private fun checkOpenRouterBalance(apiKey: String): ProviderBalanceResult {
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/auth/key")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()

        HttpClient.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return ProviderBalanceResult.Error("OpenRouter 认证失败 (${response.code})")
            }
            val body = response.body?.string() ?: return ProviderBalanceResult.Error("响应体为空")
            val obj = json.parseToJsonElement(body).jsonObject
            val data = obj["data"]?.jsonObject ?: return ProviderBalanceResult.Error("未找到数据")
            val label = data["label"]?.jsonPrimitive?.content ?: "Default"
            val usage = data["usage"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val limit = data["limit"]?.jsonPrimitive?.doubleOrNull
            val isFreeTier = data["is_free_tier"]?.jsonPrimitive?.booleanOrNull ?: false

            val formatted = if (limit != null) {
                val remaining = (limit - usage).coerceAtLeast(0.0)
                "$" + String.format(java.util.Locale.US, "%.2f", remaining)
            } else {
                "已用 $" + String.format(java.util.Locale.US, "%.2f", usage)
            }

            val details = mutableListOf<Pair<String, String>>()
            details.add("Key 备注" to label)
            details.add("已消费" to "$" + String.format(java.util.Locale.US, "%.4f", usage))
            if (limit != null) {
                details.add("额度上限" to "$" + String.format(java.util.Locale.US, "%.2f", limit))
            }
            details.add("层级" to if (isFreeTier) "免费版 (Free)" else "付费版 (Pro)")

            return ProviderBalanceResult.Success(
                provider = "OpenRouter",
                formattedBalance = formatted,
                details = details,
            )
        }
    }

    private fun checkSiliconFlowBalance(apiKey: String): ProviderBalanceResult {
        val request = Request.Builder()
            .url("https://api.siliconflow.cn/v1/user/info")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()

        HttpClient.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return ProviderBalanceResult.Error("硅基流动 API 错误 (${response.code})")
            }
            val body = response.body?.string() ?: return ProviderBalanceResult.Error("响应体为空")
            val obj = json.parseToJsonElement(body).jsonObject
            val data = obj["data"]?.jsonObject ?: return ProviderBalanceResult.Error("未找到数据")
            val totalBalance = data["totalBalance"]?.jsonPrimitive?.content ?: "0.00"
            val balance = data["balance"]?.jsonPrimitive?.content ?: "0.00"
            val chargeBalance = data["chargeBalance"]?.jsonPrimitive?.content ?: "0.00"

            return ProviderBalanceResult.Success(
                provider = "SiliconFlow",
                formattedBalance = "¥$totalBalance",
                details = listOf(
                    "充值余额" to "¥$chargeBalance",
                    "赠送余额" to "¥$balance",
                )
            )
        }
    }

    private fun checkOneApiOrGenericBalance(providerName: String, apiKey: String, baseUrl: String): ProviderBalanceResult {
        val cleanUrl = baseUrl.trimEnd('/')
        val oneApiUrl = if (cleanUrl.endsWith("/v1")) {
            cleanUrl.removeSuffix("/v1") + "/api/user/self"
        } else {
            "$cleanUrl/api/user/self"
        }

        val request = Request.Builder()
            .url(oneApiUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            HttpClient.client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val obj = json.parseToJsonElement(body).jsonObject
                        val data = obj["data"]?.jsonObject
                        if (data != null) {
                            val quota = data["quota"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                            val usedQuota = data["used_quota"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                            val dollars = quota / 500000.0
                            val usedDollars = usedQuota / 500000.0
                            val formatted = "$" + String.format(java.util.Locale.US, "%.2f", dollars)
                            return ProviderBalanceResult.Success(
                                provider = providerName,
                                formattedBalance = formatted,
                                details = listOf(
                                    "剩余 Quota" to String.format(java.util.Locale.US, "%.0f", quota),
                                    "已用额度" to String.format(java.util.Locale.US, "$%.4f", usedDollars),
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return checkGenericOpenAiConnectivity(providerName, apiKey, cleanUrl)
    }

    private fun checkGenericOpenAiConnectivity(providerName: String, apiKey: String, baseUrl: String): ProviderBalanceResult {
        val modelsUrl = if (baseUrl.endsWith("/v1")) "$baseUrl/models" else "$baseUrl/v1/models"
        val request = Request.Builder()
            .url(modelsUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()

        HttpClient.client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                return ProviderBalanceResult.Success(
                    provider = providerName,
                    formattedBalance = "连接正常 (在线)",
                    details = listOf(
                        "状态" to "在线 (200 OK)",
                        "Base URL" to baseUrl,
                    )
                )
            } else {
                return ProviderBalanceResult.Error("服务响应错误 HTTP ${response.code}")
            }
        }
    }

    private fun checkGroqStatus(apiKey: String): ProviderBalanceResult {
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/models")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()

        HttpClient.client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val remainingTokens = response.header("x-ratelimit-remaining-tokens")
                val remainingRequests = response.header("x-ratelimit-remaining-requests")
                val details = mutableListOf<Pair<String, String>>()
                details.add("状态" to "在线 (有效)")
                if (!remainingTokens.isNullOrBlank()) details.add("TPM 剩余 Token" to remainingTokens)
                if (!remainingRequests.isNullOrBlank()) details.add("RPM 剩余请求" to remainingRequests)
                return ProviderBalanceResult.Success(
                    provider = "Groq",
                    formattedBalance = "已授权有效",
                    details = details,
                )
            } else {
                return ProviderBalanceResult.Error("Groq 认证失败 (${response.code})")
            }
        }
    }

    private fun checkOpenAiStatus(apiKey: String): ProviderBalanceResult {
        val request = Request.Builder()
            .url("https://api.openai.com/v1/models")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()

        HttpClient.client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                return ProviderBalanceResult.Success(
                    provider = "OpenAI",
                    formattedBalance = "已授权有效",
                    details = listOf("状态" to "在线 (有效)", "端点" to "api.openai.com")
                )
            } else {
                return ProviderBalanceResult.Error("OpenAI 认证失败 (${response.code})")
            }
        }
    }

    private fun checkGoogleStatus(apiKey: String): ProviderBalanceResult {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
            .header("Accept", "application/json")
            .get()
            .build()

        HttpClient.client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                return ProviderBalanceResult.Success(
                    provider = "Google Gemini",
                    formattedBalance = "已授权有效",
                    details = listOf("状态" to "在线 (有效)", "端点" to "googleapis.com")
                )
            } else {
                return ProviderBalanceResult.Error("Google API Key 验证失败 (${response.code})")
            }
        }
    }

    private fun checkAnthropicStatus(apiKey: String): ProviderBalanceResult {
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/models")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Accept", "application/json")
            .get()
            .build()

        HttpClient.client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                return ProviderBalanceResult.Success(
                    provider = "Anthropic",
                    formattedBalance = "已授权有效",
                    details = listOf("状态" to "在线 (有效)", "端点" to "api.anthropic.com")
                )
            } else {
                return ProviderBalanceResult.Error("Anthropic 验证失败 (${response.code})")
            }
        }
    }

    private fun checkQwenStatus(apiKey: String): ProviderBalanceResult {
        val request = Request.Builder()
            .url("https://dashscope.aliyuncs.com/compatible-mode/v1/models")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()

        HttpClient.client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                return ProviderBalanceResult.Success(
                    provider = "Qwen",
                    formattedBalance = "已授权有效",
                    details = listOf("状态" to "在线 (有效)", "端点" to "dashscope.aliyuncs.com")
                )
            } else {
                return ProviderBalanceResult.Error("通义千问验证失败 (${response.code})")
            }
        }
    }
}
