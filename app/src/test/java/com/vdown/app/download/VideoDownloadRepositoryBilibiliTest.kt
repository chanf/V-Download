package com.vdown.app.download

import com.vdown.app.cookie.CookieDao
import com.vdown.app.cookie.CookieEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class VideoDownloadRepositoryBilibiliTest {

    @Test
    fun `should resolve bilibili b23 share text to downloadable video stream`() = runBlocking {
        val shareText = "【免费，不翻墙，国内无限制使用Nano Banana2 教程，电脑手机通用！100%成功-哔哩哔哩】 https://b23.tv/OcwAwcx"
        assertShareTextDownloadable(shareText)
    }

    @Test
    fun `should resolve another bilibili b23 share text to downloadable video stream`() = runBlocking {
        val shareText = "【全球最大机场——但是倒闭了【神奇组织49】-哔哩哔哩】 https://b23.tv/xF8HptD"
        assertShareTextDownloadable(shareText)
    }

    private suspend fun assertShareTextDownloadable(shareText: String) {
        val repository = VideoDownloadRepository(EmptyCookieDao())

        val resolved = repository.resolveSourceUrlOnlyForTest(shareText)
        val lower = resolved.lowercase()
        assertTrue(
            "解析结果不是预期 B 站视频流地址: $resolved",
            lower.contains("bilivideo") || lower.contains("hdslb")
        )

        val mimeType = fetchMimeType(resolved)
        assertTrue("解析出的直链未返回视频 MIME，实际为: $mimeType", mimeType.startsWith("video/"))
    }

    private fun fetchMimeType(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
            )
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Origin", "https://www.bilibili.com")
        }

        return try {
            val code = connection.responseCode
            if (code !in 200..299 && code != 206) return ""
            connection.contentType?.substringBefore(';')?.trim().orEmpty()
        } finally {
            connection.disconnect()
        }
    }

    private class EmptyCookieDao : CookieDao {
        override suspend fun upsertAll(cookies: List<CookieEntity>) = Unit

        override suspend fun countAll(): Int = 0

        override suspend fun getAll(): List<CookieEntity> = emptyList()

        override suspend fun getValidCookies(nowEpochSeconds: Long): List<CookieEntity> = emptyList()

        override suspend fun deleteExpired(nowEpochSeconds: Long): Int = 0
    }
}
