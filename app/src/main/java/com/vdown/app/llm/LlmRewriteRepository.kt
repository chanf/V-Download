package com.vdown.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class LlmRewriteRepository {
    suspend fun rewriteTranscript(
        config: LlmProviderConfig,
        transcriptText: String
    ): String = withContext(Dispatchers.IO) {
        when (config.providerType) {
            LlmProviderType.OPENAI,
            LlmProviderType.OPENAI_COMPATIBLE,
            LlmProviderType.DEEPSEEK -> requestOpenAiCompatible(config, transcriptText)

            LlmProviderType.AZURE_OPENAI -> requestAzureOpenAi(config, transcriptText)
            LlmProviderType.GEMINI -> requestGemini(config, transcriptText)
        }
    }

    private fun requestOpenAiCompatible(config: LlmProviderConfig, transcriptText: String): String {
        val baseUrl = config.baseUrl.ifBlank { defaultBaseUrlFor(config.providerType) }.trim()
        val systemPrompt = resolveSystemPrompt(config)
        require(baseUrl.isNotBlank()) { "AI重构失败：请先配置服务地址（Base URL）" }
        require(config.apiKey.isNotBlank()) { "AI重构失败：请先配置 API Key" }
        require(config.model.isNotBlank()) { "AI重构失败：请先配置模型名称（Model）" }

        val endpoint = if (baseUrl.endsWith("/v1", ignoreCase = true)) {
            "$baseUrl/chat/completions"
        } else {
            "${baseUrl.trimEnd('/')}/v1/chat/completions"
        }

        val requestBody = JSONObject().apply {
            put("model", config.model)
            put("temperature", 0.4)
            put("messages", JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
                )
                put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", buildUserPrompt(transcriptText))
                    }
                )
            })
        }

        val headers = mutableMapOf(
            "Authorization" to "Bearer ${config.apiKey}",
            "Content-Type" to "application/json"
        )
        if (config.organization.isNotBlank()) {
            headers["OpenAI-Organization"] = config.organization
        }

        val raw = postJson(endpoint, headers, requestBody.toString())
        val payload = JSONObject(raw)
        val content = extractOpenAiContent(payload).trim()
        if (content.isBlank()) {
            throw IllegalStateException("AI重构失败：模型返回内容为空")
        }
        return content
    }

    private fun requestAzureOpenAi(config: LlmProviderConfig, transcriptText: String): String {
        val baseUrl = config.baseUrl.ifBlank { defaultBaseUrlFor(config.providerType) }.trim()
        val systemPrompt = resolveSystemPrompt(config)
        require(baseUrl.isNotBlank()) { "AI重构失败：请先配置 Azure 资源地址" }
        require(config.apiKey.isNotBlank()) { "AI重构失败：请先配置 Azure API Key" }
        val deployment = config.azureDeployment.ifBlank { config.model }.trim()
        require(deployment.isNotBlank()) { "AI重构失败：请先配置 Azure Deployment" }
        val apiVersion = config.azureApiVersion.ifBlank { "2024-10-21" }

        val endpoint = "${baseUrl.trimEnd('/')}/openai/deployments/$deployment/chat/completions?api-version=${urlEncode(apiVersion)}"
        val requestBody = JSONObject().apply {
            put("temperature", 0.4)
            put("messages", JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
                )
                put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", buildUserPrompt(transcriptText))
                    }
                )
            })
        }

        val headers = mapOf(
            "api-key" to config.apiKey,
            "Content-Type" to "application/json"
        )

        val raw = postJson(endpoint, headers, requestBody.toString())
        val payload = JSONObject(raw)
        val content = extractOpenAiContent(payload).trim()
        if (content.isBlank()) {
            throw IllegalStateException("AI重构失败：AzureOpenAI 返回内容为空")
        }
        return content
    }

    private fun requestGemini(config: LlmProviderConfig, transcriptText: String): String {
        val baseUrl = config.baseUrl.ifBlank { defaultBaseUrlFor(config.providerType) }.trim()
        val systemPrompt = resolveSystemPrompt(config)
        require(baseUrl.isNotBlank()) { "AI重构失败：请先配置 Gemini 服务地址" }
        require(config.apiKey.isNotBlank()) { "AI重构失败：请先配置 Gemini API Key" }
        require(config.model.isNotBlank()) { "AI重构失败：请先配置 Gemini 模型名称" }

        val endpoint = "${baseUrl.trimEnd('/')}/v1beta/models/${urlEncode(config.model)}:generateContent?key=${urlEncode(config.apiKey)}"
        val requestBody = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().apply {
                    put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put("text", systemPrompt)
                        )
                    )
                }
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put("text", buildUserPrompt(transcriptText))
                        )
                    )
                )
            )
        }

        val headers = mapOf("Content-Type" to "application/json")
        val raw = postJson(endpoint, headers, requestBody.toString())
        val payload = JSONObject(raw)
        val content = extractGeminiContent(payload).trim()
        if (content.isBlank()) {
            throw IllegalStateException("AI重构失败：Gemini 返回内容为空")
        }
        return content
    }

    private fun extractOpenAiContent(payload: JSONObject): String {
        val choices = payload.optJSONArray("choices") ?: JSONArray()
        if (choices.length() <= 0) return ""
        val first = choices.optJSONObject(0) ?: JSONObject()
        val message = first.optJSONObject("message") ?: JSONObject()
        val content = message.opt("content")
        return when (content) {
            is String -> content
            is JSONArray -> {
                buildString {
                    for (index in 0 until content.length()) {
                        val item = content.opt(index)
                        when (item) {
                            is JSONObject -> {
                                val text = item.optString("text")
                                if (text.isNotBlank()) append(text)
                            }

                            is String -> if (item.isNotBlank()) append(item)
                        }
                    }
                }
            }

            else -> ""
        }
    }

    private fun extractGeminiContent(payload: JSONObject): String {
        val candidates = payload.optJSONArray("candidates") ?: JSONArray()
        if (candidates.length() <= 0) return ""
        val first = candidates.optJSONObject(0) ?: JSONObject()
        val contentObj = first.optJSONObject("content") ?: JSONObject()
        val parts = contentObj.optJSONArray("parts") ?: JSONArray()

        return buildString {
            for (index in 0 until parts.length()) {
                val text = parts.optJSONObject(index)?.optString("text").orEmpty()
                if (text.isNotBlank()) append(text)
            }
        }
    }

    private fun postJson(
        endpoint: String,
        headers: Map<String, String>,
        body: String
    ): String {
        val url = URL(endpoint)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doInput = true
            doOutput = true
            useCaches = false
        }
        headers.forEach { (key, value) ->
            connection.setRequestProperty(key, value)
        }

        return try {
            connection.outputStream.use { output ->
                output.write(body.toByteArray(StandardCharsets.UTF_8))
                output.flush()
            }

            val code = connection.responseCode
            val responseBody = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (code !in 200..299) {
                val errorMessage = extractRemoteErrorMessage(responseBody)
                throw IllegalStateException("AI重构失败：请求返回 HTTP $code，$errorMessage")
            }

            responseBody
        } finally {
            connection.disconnect()
        }
    }

    private fun extractRemoteErrorMessage(responseBody: String): String {
        if (responseBody.isBlank()) return "无响应正文"
        return runCatching {
            val root = JSONObject(responseBody)
            when {
                root.has("error") -> {
                    val err = root.opt("error")
                    when (err) {
                        is JSONObject -> err.optString("message").ifBlank { err.toString() }
                        is String -> err
                        else -> err?.toString().orEmpty()
                    }
                }

                else -> responseBody.take(240)
            }
        }.getOrElse { responseBody.take(240) }
    }

    private fun buildUserPrompt(transcriptText: String): String {
        return """
            请将以下视频转写文案重构为更流畅、更易读、结构更清晰的新文案。
            要求：
            1. 保持原意，不杜撰事实。
            2. 语句精炼，适当分段，适合发布到短视频平台。
            3. 输出只给最终重构文案，不要解释过程。

            原始文案：
            $transcriptText
        """.trimIndent()
    }

    private fun resolveSystemPrompt(config: LlmProviderConfig): String {
        return config.systemPrompt.trim().ifBlank { defaultLlmSystemPrompt() }
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
    }
}
