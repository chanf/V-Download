package com.vdown.app.llm

enum class LlmProviderType(val title: String) {
    OPENAI("OpenAI"),
    OPENAI_COMPATIBLE("兼容OpenAI"),
    AZURE_OPENAI("AzureOpenAI"),
    DEEPSEEK("Deepseek"),
    GEMINI("Gemini")
}

data class LlmProviderConfig(
    val id: String,
    val displayName: String,
    val providerType: LlmProviderType,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String = defaultLlmSystemPrompt(),
    val organization: String = "",
    val azureDeployment: String = "",
    val azureApiVersion: String = ""
)

fun defaultBaseUrlFor(providerType: LlmProviderType): String {
    return when (providerType) {
        LlmProviderType.OPENAI -> "https://api.openai.com"
        LlmProviderType.OPENAI_COMPATIBLE -> ""
        LlmProviderType.AZURE_OPENAI -> "https://{your-resource}.openai.azure.com"
        LlmProviderType.DEEPSEEK -> "https://api.deepseek.com"
        LlmProviderType.GEMINI -> "https://generativelanguage.googleapis.com"
    }
}

fun defaultModelFor(providerType: LlmProviderType): String {
    return when (providerType) {
        LlmProviderType.OPENAI -> "gpt-4o-mini"
        LlmProviderType.OPENAI_COMPATIBLE -> ""
        LlmProviderType.AZURE_OPENAI -> "gpt-4o-mini"
        LlmProviderType.DEEPSEEK -> "deepseek-chat"
        LlmProviderType.GEMINI -> "gemini-1.5-flash"
    }
}

fun defaultLlmSystemPrompt(): String {
    return "你是中文短视频文案编辑，擅长把口语转写内容整理为高可读的新文案。"
}
