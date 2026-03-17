package com.vdown.app.download

import com.vdown.app.cookie.CookieDao
import com.vdown.app.cookie.CookieEntity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class VideoDownloadRepositoryDouyinTest {

    @Test
    fun `should resolve downloadable video stream from provided douyin share link`() {
        val shareUrl = "https://v.douyin.com/HxX4F9AKp9E/"
        val repository = VideoDownloadRepository(EmptyCookieDao())

        val pageContent = fetchDouyinPage(shareUrl)
        val extracted = invokeExtractDouyinVideoUrl(repository, pageContent)

        assertNotNull("抖音页面未解析到视频直链", extracted)
        assertTrue("解析结果不是预期抖音视频地址: $extracted", isLikelyDouyinVideoUrl(extracted!!))

        val mimeType = fetchMimeType(extracted)
        assertTrue("解析出的直链未返回视频 MIME，实际为: $mimeType", mimeType.startsWith("video/"))
    }

    private fun invokeExtractDouyinVideoUrl(repository: VideoDownloadRepository, page: String): String? {
        val method = VideoDownloadRepository::class.java.getDeclaredMethod(
            "extractDouyinVideoUrlFromPage",
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(repository, page) as? String
    }

    private fun fetchDouyinPage(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
            )
            setRequestProperty("Referer", "https://www.douyin.com/")
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Accept-Encoding", "identity")
        }

        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
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
        }

        return try {
            val code = connection.responseCode
            if (code !in 200..299) return ""
            connection.contentType?.substringBefore(';')?.trim().orEmpty()
        } finally {
            connection.disconnect()
        }
    }

    private fun isLikelyDouyinVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/aweme/v1/play/") ||
            lower.contains("playwm") ||
            lower.contains("douyinvod") ||
            (lower.contains(".mp4") && (lower.contains("aweme") || lower.contains("vod")))
    }

    private class EmptyCookieDao : CookieDao {
        override suspend fun upsertAll(cookies: List<CookieEntity>) = Unit

        override suspend fun countAll(): Int = 0

        override suspend fun getAll(): List<CookieEntity> = emptyList()

        override suspend fun getValidCookies(nowEpochSeconds: Long): List<CookieEntity> = emptyList()

        override suspend fun deleteExpired(nowEpochSeconds: Long): Int = 0
    }
}
