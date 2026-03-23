package com.vdown.app.asr

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class AsrConfigRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun loadConfigs(): List<AsrProviderConfig> {
        val raw = preferences.getString(KEY_CONFIGS_JSON, "").orEmpty()
        if (raw.isBlank()) return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val providerType = item.optString("providerType")
                        .takeIf { it.isNotBlank() }
                        ?.runCatching { AsrProviderType.valueOf(this) }
                        ?.getOrNull()
                        ?: continue

                    add(
                        AsrProviderConfig(
                            id = item.optString("id"),
                            displayName = item.optString("displayName"),
                            providerType = providerType,
                            baseUrl = item.optString("baseUrl"),
                            apiKey = item.optString("apiKey"),
                            model = item.optString("model"),
                            organization = item.optString("organization"),
                            azureDeployment = item.optString("azureDeployment"),
                            azureApiVersion = item.optString("azureApiVersion"),
                            localModelPath = item.optString("localModelPath"),
                            preferredLanguage = item.optString("preferredLanguage")
                                .takeIf { it.isNotBlank() }
                                ?.runCatching { AsrLanguageOption.valueOf(this) }
                                ?.getOrNull()
                                ?: AsrLanguageOption.AUTO
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveConfigs(configs: List<AsrProviderConfig>) {
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
                    put("organization", config.organization)
                    put("azureDeployment", config.azureDeployment)
                    put("azureApiVersion", config.azureApiVersion)
                    put("localModelPath", config.localModelPath)
                    put("preferredLanguage", config.preferredLanguage.name)
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
        const val PREF_NAME = "vdown_asr_config"
        const val KEY_CONFIGS_JSON = "configs_json"
        const val KEY_SELECTED_PROVIDER_ID = "selected_provider_id"
    }
}
