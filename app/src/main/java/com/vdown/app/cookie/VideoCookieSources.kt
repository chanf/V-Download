package com.vdown.app.cookie

data class VideoCookieSource(
    val id: String,
    val displayName: String,
    val domainSuffixes: List<String>
)

object VideoCookieSources {
    val all: List<VideoCookieSource> = listOf(
        VideoCookieSource(
            id = "tiktok",
            displayName = "TikTok",
            domainSuffixes = listOf("tiktok.com", "musical.ly", "tiktokv.com")
        ),
        VideoCookieSource(
            id = "youtube",
            displayName = "YouTube",
            domainSuffixes = listOf("youtube.com", "googlevideo.com", "ytimg.com", "youtu.be")
        ),
        VideoCookieSource(
            id = "bilibili",
            displayName = "bilibili",
            domainSuffixes = listOf("bilibili.com", "bilivideo.com")
        ),
        VideoCookieSource(
            id = "douyin",
            displayName = "抖音",
            domainSuffixes = listOf("douyin.com", "iesdouyin.com", "douyinvod.com")
        ),
        VideoCookieSource(
            id = "instagram",
            displayName = "Instagram",
            domainSuffixes = listOf("instagram.com", "cdninstagram.com", "fbcdn.net")
        ),
        VideoCookieSource(
            id = "xiaohongshu",
            displayName = "小红书",
            domainSuffixes = listOf("xiaohongshu.com", "xhscdn.com", "xhslink.com", "rednote.com")
        )
    )

    fun matchByDomain(rawDomain: String): VideoCookieSource? {
        val normalized = normalizeDomain(rawDomain)
        if (normalized.isBlank()) return null

        return all.firstOrNull { source ->
            source.domainSuffixes.any { suffix ->
                normalized == suffix || normalized.endsWith(".$suffix")
            }
        }
    }

    private fun normalizeDomain(rawDomain: String): String {
        return rawDomain.trim().lowercase().removePrefix(".")
    }
}
