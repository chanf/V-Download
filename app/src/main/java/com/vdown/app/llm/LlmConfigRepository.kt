package com.vdown.app.llm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LlmConfigRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun loadConfigs(): List<LlmProviderConfig> {
        val raw = preferences.getString(KEY_CONFIGS_JSON, "").orEmpty()
        if (raw.isBlank()) return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val providerType = item.optString("providerType")
                        .takeIf { it.isNotBlank() }
                        ?.runCatching { LlmProviderType.valueOf(this) }
                        ?.getOrNull()
                        ?: continue
                    add(
                        LlmProviderConfig(
                            id = item.optString("id"),
                            displayName = item.optString("displayName"),
                            providerType = providerType,
                            baseUrl = item.optString("baseUrl"),
                            apiKey = item.optString("apiKey"),
                            model = item.optString("model"),
                            systemPrompt = item.optString("systemPrompt").ifBlank { defaultLlmSystemPrompt() },
                            organization = item.optString("organization"),
                            azureDeployment = item.optString("azureDeployment"),
                            azureApiVersion = item.optString("azureApiVersion")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveConfigs(configs: List<LlmProviderConfig>) {
        val json = JSONArray()
        configs.forEach { config ->
            json.put(
                JSONObject().apply {
                    put("id", config.id)
                    put("displayName", config.displayName)
                    put("providerType", config.providerType.name)
                    put("baseUrl", config.baseUrl)
                    put("apiKey", config.apiKey)
                    put("model", config.model)
                    put("systemPrompt", config.systemPrompt)
                    put("organization", config.organization)
                    put("azureDeployment", config.azureDeployment)
                    put("azureApiVersion", config.azureApiVersion)
                }
            )
        }
        preferences.edit().putString(KEY_CONFIGS_JSON, json.toString()).apply()
    }

    fun loadSelectedProviderId(): String? {
        return preferences.getString(KEY_SELECTED_PROVIDER_ID, null)
    }

    fun saveSelectedProviderId(id: String?) {
        preferences.edit().putString(KEY_SELECTED_PROVIDER_ID, id).apply()
    }

    private companion object {
        const val PREF_NAME = "vdown_llm_config"
        const val KEY_CONFIGS_JSON = "configs_json"
        const val KEY_SELECTED_PROVIDER_ID = "selected_provider_id"
    }
}
