package com.vdown.app.dedup

import android.content.Context
import org.json.JSONObject

enum class DedupEndingType(val title: String) {
    NONE("无片尾"),
    IMAGE("图片片尾"),
    VIDEO("视频片尾");

    companion object {
        fun fromName(name: String?): DedupEndingType {
            return entries.firstOrNull { it.name == name } ?: NONE
        }
    }
}

enum class DedupIntroCoverMode(val title: String) {
    NONE("不启用"),
    INSERT_FRAMES("插入封面帧"),
    OVERLAY_FRAMES("覆盖前若干帧");

    companion object {
        fun fromName(name: String?): DedupIntroCoverMode {
            return entries.firstOrNull { it.name == name } ?: NONE
        }
    }
}

data class DedupFeatureConfig(
    val coverImageCachePath: String? = null,
    val coverImageName: String? = null,
    val introCoverMode: DedupIntroCoverMode = DedupIntroCoverMode.NONE,
    val introFrameCount: Int = 12,
    val endingType: DedupEndingType = DedupEndingType.NONE,
    val endingMediaUri: String? = null,
    val endingMediaName: String? = null,
    val endingImageDurationMs: Int = 1200
)

class DedupFeatureConfigRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun loadConfig(): DedupFeatureConfig {
        val raw = preferences.getString(KEY_CONFIG_JSON, "").orEmpty()
        if (raw.isBlank()) return DedupFeatureConfig()
        return runCatching {
            val json = JSONObject(raw)
            val coverPath = json.optString("coverImageCachePath").ifBlank { null }
            val hasCover = !coverPath.isNullOrBlank()
            val hasIntroFrameCount = json.has("introFrameCount")
            val legacyCoverDurationMs = json.optInt("coverImageDurationMs", 1200)
            val introFrameCount = if (hasIntroFrameCount) {
                json.optInt("introFrameCount", 12).coerceIn(1, 60)
            } else {
                ((legacyCoverDurationMs / 1000.0) * 30.0).toInt().coerceIn(1, 60)
            }
            val parsedMode = if (json.has("introCoverMode")) {
                DedupIntroCoverMode.fromName(json.optString("introCoverMode").ifBlank { null })
            } else {
                if (hasCover) DedupIntroCoverMode.INSERT_FRAMES else DedupIntroCoverMode.NONE
            }
            DedupFeatureConfig(
                coverImageCachePath = coverPath,
                coverImageName = json.optString("coverImageName").ifBlank { null },
                introCoverMode = parsedMode,
                introFrameCount = introFrameCount,
                endingType = DedupEndingType.fromName(json.optString("endingType").ifBlank { null }),
                endingMediaUri = json.optString("endingMediaUri").ifBlank { null },
                endingMediaName = json.optString("endingMediaName").ifBlank { null },
                endingImageDurationMs = json.optInt("endingImageDurationMs", 1200).coerceIn(300, 10_000)
            ).also { config ->
                if (!hasIntroFrameCount || !json.has("introCoverMode")) {
                    saveConfig(config)
                }
            }
        }.getOrDefault(DedupFeatureConfig())
    }

    fun saveConfig(config: DedupFeatureConfig) {
        val json = JSONObject().apply {
            put("coverImageCachePath", config.coverImageCachePath.orEmpty())
            put("coverImageName", config.coverImageName.orEmpty())
            put("introCoverMode", config.introCoverMode.name)
            put("introFrameCount", config.introFrameCount.coerceIn(1, 60))
            put("endingType", config.endingType.name)
            put("endingMediaUri", config.endingMediaUri.orEmpty())
            put("endingMediaName", config.endingMediaName.orEmpty())
            put("endingImageDurationMs", config.endingImageDurationMs.coerceIn(300, 10_000))
        }
        preferences.edit().putString(KEY_CONFIG_JSON, json.toString()).apply()
    }

    private companion object {
        const val PREF_NAME = "vdown_dedup_feature_config"
        const val KEY_CONFIG_JSON = "dedup_feature_config_json"
    }
}
