package com.vdown.app.asr

enum class AsrProviderType(val title: String) {
    OPENAI("OpenAI"),
    OPENAI_COMPATIBLE("兼容OpenAI"),
    AZURE_OPENAI("AzureOpenAI"),
    VOSK_LOCAL("Vosk(本地)")
}

enum class AsrLanguageOption(
    val title: String,
    val iso639_1: String?,
    val voskHint: String?
) {
    AUTO("自动识别", null, null),
    ZH("中文（zh）", "zh", "zh"),
    EN("英文（en）", "en", "en"),
    PT("葡语（pt）", "pt", "pt"),
    ES("西语（es）", "es", "es")
}

data class AsrProviderConfig(
    val id: String,
    val displayName: String,
    val providerType: AsrProviderType,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val organization: String = "",
    val azureDeployment: String = "",
    val azureApiVersion: String = "",
    val localModelPath: String = "",
    val preferredLanguage: AsrLanguageOption = AsrLanguageOption.AUTO
)

fun defaultAsrBaseUrlFor(providerType: AsrProviderType): String {
    return when (providerType) {
        AsrProviderType.OPENAI -> "https://api.openai.com"
        AsrProviderType.OPENAI_COMPATIBLE -> ""
        AsrProviderType.AZURE_OPENAI -> "https://{your-resource}.openai.azure.com"
        AsrProviderType.VOSK_LOCAL -> ""
    }
}

fun defaultAsrModelFor(providerType: AsrProviderType): String {
    return when (providerType) {
        AsrProviderType.OPENAI -> "gpt-4o-mini-transcribe"
        AsrProviderType.OPENAI_COMPATIBLE -> "whisper-1"
        AsrProviderType.AZURE_OPENAI -> "whisper"
        AsrProviderType.VOSK_LOCAL -> "vosk-model"
    }
}
