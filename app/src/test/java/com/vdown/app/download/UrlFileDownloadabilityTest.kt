package com.vdown.app.download

import com.vdown.app.cookie.CookieDao
import com.vdown.app.cookie.CookieEntity
import com.vdown.app.cookie.NetscapeCookieParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UrlFileDownloadabilityTest {

    @Test
    fun `all urls in url txt should resolve to downloadable video stream`() = runBlocking {
        val inputLines = locateUrlFile()
            .readLines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val cookieEntities = loadCookieEntitiesForTest()
        val repository = VideoDownloadRepository(InMemoryCookieDao(cookieEntities))
        val failures = mutableListOf<String>()

        inputLines.forEachIndexed { index, input ->
            val resolved = runCatching { repository.resolveSourceUrlOnlyForTest(input) }
                .getOrElse { error ->
                    failures += "Line ${index + 1}: 解析失败 -> ${error.message} | input=$input"
                    return@forEachIndexed
                }

            val mimeType = runCatching { fetchMimeTypeWithRetry(resolved) }
                .getOrElse { error ->
                    val inferred = inferMimeTypeFromResolvedUrl(resolved)
                    if (inferred.isNotBlank()) {
                        inferred
                    } else {
                        failures += "Line ${index + 1}: MIME 检测失败 -> ${error.message} | resolved=$resolved | input=$input"
                        return@forEachIndexed
                    }
                }
            if (!mimeType.startsWith("video/")) {
                failures += "Line ${index + 1}: 非视频 MIME($mimeType) | resolved=$resolved | input=$input"
            }
        }

        assertTrue(
            "以下输入仍不可下载:\n${failures.joinToString(separator = "\n")}",
            failures.isEmpty()
        )
    }

    private fun locateUrlFile(): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = current?.let { File(it, "url.txt") }
            if (candidate != null && candidate.exists() && candidate.isFile) {
                return candidate
            }
            current = current?.parentFile
        }
        throw IllegalStateException("未找到 url.txt（请确保位于项目根目录）")
    }

    private fun loadCookieEntitiesForTest(): List<CookieEntity> {
        val cookieFile = locateCookieFile()
        val parsed = NetscapeCookieParser()
            .parse(cookieFile.reader().buffered())
            .cookies
        val now = System.currentTimeMillis() / 1000
        return parsed
            .filter { it.expiresAtEpochSeconds == 0L || it.expiresAtEpochSeconds >= now }
            .mapIndexed { index, parsedCookie ->
                CookieEntity(
                    id = index.toLong() + 1,
                    domain = parsedCookie.domain,
                    includeSubDomains = parsedCookie.includeSubDomains,
                    path = parsedCookie.path,
                    secure = parsedCookie.secure,
                    httpOnly = parsedCookie.httpOnly,
                    expiresAtEpochSeconds = parsedCookie.expiresAtEpochSeconds,
                    name = parsedCookie.name,
                    value = parsedCookie.value,
                    sourceFileName = cookieFile.name,
                    importedAtEpochMillis = System.currentTimeMillis()
                )
            }
    }

    private fun locateCookieFile(): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = current?.let { File(it, "Cookies/f37a8486-72f3-489b-9877-a9b17bc3ffb6.txt") }
            if (candidate != null && candidate.exists() && candidate.isFile) {
                return candidate
            }
            current = current?.parentFile
        }
        throw IllegalStateException("未找到测试 cookies 文件")
    }

    private fun fetchMimeType(url: String): String {
        val parsed = URL(url)
        val host = parsed.host.lowercase()
        val connection = (parsed.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            requestMethod = "GET"
            connectTimeout = 25_000
            readTimeout = 40_000
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
            )
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "identity")
            when {
                host.contains("googlevideo") || host.contains("youtube") || host.contains("youtu.be") -> {
                    setRequestProperty("Referer", "https://www.youtube.com/")
                    setRequestProperty("Origin", "https://www.youtube.com")
                }

                host.contains("video.twimg.com") || host.contains("x.com") || host.contains("twitter.com") -> {
                    setRequestProperty("Referer", "https://x.com/")
                    setRequestProperty("Origin", "https://x.com")
                }

                host.contains("tiktok") || host.contains("muscdn") -> {
                    setRequestProperty("Referer", "https://www.tiktok.com/")
                }

                host.contains("douyin") || host.contains("snssdk") || host.contains("amemv") -> {
                    setRequestProperty("Referer", "https://www.douyin.com/")
                }

                host.contains("xiaohongshu") || host.contains("xhscdn") || host.contains("xhslink") -> {
                    setRequestProperty("Referer", "https://www.xiaohongshu.com/")
                }
            }
        }

        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                ""
            } else {
                connection.contentType?.substringBefore(';')?.trim().orEmpty()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchMimeTypeWithRetry(url: String, maxAttempts: Int = 3): String {
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return fetchMimeType(url)
            } catch (error: Exception) {
                lastError = error
                if (attempt < maxAttempts - 1) {
                    Thread.sleep(800L)
                }
            }
        }
        throw IOException("连续 $maxAttempts 次请求失败：${lastError?.message}", lastError)
    }

    private fun inferMimeTypeFromResolvedUrl(url: String): String {
        val lower = url.lowercase()
        if (lower.contains("mime_type=video") || lower.contains("mime=video%2f") || lower.contains("mime=video/")) {
            return "video/mp4"
        }
        if (lower.contains(".mp4?") || lower.endsWith(".mp4")) {
            return "video/mp4"
        }
        return ""
    }

    private class InMemoryCookieDao(
        private val cookies: List<CookieEntity>
    ) : CookieDao {
        override suspend fun upsertAll(cookies: List<CookieEntity>) = Unit

        override suspend fun countAll(): Int = cookies.size

        override suspend fun getAll(): List<CookieEntity> = cookies

        override suspend fun getValidCookies(nowEpochSeconds: Long): List<CookieEntity> {
            return cookies.filter { cookie ->
                val expires = cookie.expiresAtEpochSeconds
                expires == null || expires == 0L || expires >= nowEpochSeconds
            }
        }

        override suspend fun deleteExpired(nowEpochSeconds: Long): Int = 0
    }
}
