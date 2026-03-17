package com.vdown.app.download

import com.vdown.app.cookie.CookieDao
import com.vdown.app.cookie.CookieEntity
import com.vdown.app.cookie.NetscapeCookieParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class UrlFileRealDownloadToVideoDirTest {

    @Test
    fun `download all urls in url txt to local video dir`() = runBlocking {
        val projectRoot = locateProjectRoot()
        val urlFile = File(projectRoot, "url.txt")
        val outputDir = File(projectRoot, "video")
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw IllegalStateException("无法创建目录: ${outputDir.absolutePath}")
        }

        val inputLines = urlFile.readLines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val cookieEntities = loadCookieEntitiesForTest(projectRoot)
        val repository = VideoDownloadRepository(InMemoryCookieDao(cookieEntities))
        val failures = mutableListOf<String>()

        inputLines.forEachIndexed { index, input ->
            val lineNo = index + 1
            val resolved = runCatching { repository.resolveSourceUrlOnlyForTest(input) }
                .getOrElse { error ->
                    failures += "Line $lineNo: 解析失败 -> ${error.message} | input=$input"
                    return@forEachIndexed
                }

            val file = runCatching {
                withRetry(maxAttempts = 3, backoffMillis = 1200L) {
                    downloadResolvedUrl(resolved, outputDir, lineNo)
                }
            }.getOrElse { error ->
                failures += "Line $lineNo: 下载失败 -> ${error.message} | resolved=$resolved | input=$input"
                return@forEachIndexed
            }

            println("Line $lineNo 下载完成 -> ${file.absolutePath} (${file.length()} bytes)")
        }

        assertTrue(
            "以下输入下载失败:\n${failures.joinToString(separator = "\n")}",
            failures.isEmpty()
        )
    }

    private fun locateProjectRoot(): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            val dir = current ?: return@repeat
            if (File(dir, "url.txt").exists()) {
                return dir
            }
            current = dir.parentFile
        }
        throw IllegalStateException("未找到项目根目录（缺少 url.txt）")
    }

    private fun loadCookieEntitiesForTest(projectRoot: File): List<CookieEntity> {
        val cookieFile = File(projectRoot, "Cookies/f37a8486-72f3-489b-9877-a9b17bc3ffb6.txt")
        if (!cookieFile.exists()) {
            throw IllegalStateException("未找到 cookies 文件: ${cookieFile.absolutePath}")
        }
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

    private fun downloadResolvedUrl(resolvedUrl: String, outputDir: File, lineNo: Int): File {
        val parsed = URL(resolvedUrl)
        val host = parsed.host.lowercase()

        val connection = (parsed.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 90_000
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
                throw IOException("HTTP $code")
            }

            val responseMime = connection.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            val finalUrl = connection.url.toString()
            if (!responseMime.startsWith("video/") && !isLikelyVideoUrl(finalUrl) && !isLikelyVideoUrl(resolvedUrl)) {
                throw IOException("非视频流: MIME=$responseMime")
            }

            val ext = resolveExtension(responseMime, finalUrl, resolvedUrl)
            val hostPart = sanitizeName(URL(finalUrl).host.ifBlank { parsed.host.ifBlank { "unknown" } })
            val target = uniqueFile(outputDir, "line_${lineNo}_${hostPart}", ext)

            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output, bufferSize = 16 * 1024)
                }
            }

            if (target.length() <= 0L) {
                throw IOException("下载得到空文件")
            }
            target
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveExtension(mimeType: String, finalUrl: String, originalUrl: String): String {
        when {
            mimeType.contains("video/webm") -> return "webm"
            mimeType.contains("video/quicktime") -> return "mov"
            mimeType.startsWith("video/") -> return "mp4"
        }

        listOf(finalUrl.lowercase(), originalUrl.lowercase()).forEach { lower ->
            when {
                lower.contains(".webm") -> return "webm"
                lower.contains(".mov") -> return "mov"
                lower.contains(".mkv") -> return "mkv"
                lower.contains(".mp4") -> return "mp4"
            }
        }
        return "mp4"
    }

    private fun isLikelyVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("mime_type=video") || lower.contains("mime=video%2f") || lower.contains("mime=video/")) {
            return true
        }
        return lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".m3u8")
    }

    private fun sanitizeName(raw: String): String {
        return raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "unknown" }
    }

    private fun uniqueFile(outputDir: File, baseName: String, ext: String): File {
        var candidate = File(outputDir, "$baseName.$ext")
        var index = 1
        while (candidate.exists()) {
            candidate = File(outputDir, "${baseName}_$index.$ext")
            index += 1
        }
        return candidate
    }

    private fun <T> withRetry(maxAttempts: Int, backoffMillis: Long, block: () -> T): T {
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (error: Exception) {
                lastError = error
                if (attempt < maxAttempts - 1) {
                    Thread.sleep(backoffMillis * (attempt + 1))
                }
            }
        }
        throw IOException("连续 $maxAttempts 次尝试失败：${lastError?.message}", lastError)
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
