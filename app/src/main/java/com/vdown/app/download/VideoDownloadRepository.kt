package com.vdown.app.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.vdown.app.cookie.CookieDao
import com.vdown.app.cookie.CookieEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

private const val MOVIES_RELATIVE_PATH = "Movies/v-down"
private const val DEFAULT_MIME_TYPE = "video/mp4"
private const val BUFFER_SIZE = 8 * 1024
private const val MAX_REDIRECT_FOLLOWS = 8
private const val MAX_YOUTUBE_CANDIDATE_PROBES = 10
private const val MOBILE_SAFARI_UA =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
private const val ANDROID_CHROME_UA =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
private const val DESKTOP_CHROME_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
private val INSTAGRAM_OG_VIDEO_PATTERNS = listOf(
    Regex(
        """<meta[^>]*property=["']og:video(?::secure_url)?["'][^>]*content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    ),
    Regex(
        """<meta[^>]*content=["']([^"']+)["'][^>]*property=["']og:video(?::secure_url)?["']""",
        RegexOption.IGNORE_CASE
    )
)
private val INSTAGRAM_VIDEO_URL_PATTERNS = listOf(
    Regex("""\\\"video_url\\\"\s*:\s*\\\"(.*?)\\\"""", RegexOption.IGNORE_CASE),
    Regex(""""video_url"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE),
    Regex("""['"]video_url['"]\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
)
private val TIKTOK_VIDEO_URL_PATTERNS = listOf(
    Regex("""\\\"(?:downloadAddr|playAddr|playUrl)\\\"\s*:\s*\\\"(.*?)\\\"""", RegexOption.IGNORE_CASE),
    Regex(""""(?:downloadAddr|playAddr|playUrl)"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE),
    Regex("""['"](?:downloadAddr|playAddr|playUrl)['"]\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
)
private val TIKWM_VIDEO_URL_PATTERNS = listOf(
    Regex("""\\\"(?:play|wmplay|hdplay)\\\"\s*:\s*\\\"(.*?)\\\"""", RegexOption.IGNORE_CASE),
    Regex(""""(?:play|wmplay|hdplay)"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE),
    Regex("""['"](?:play|wmplay|hdplay)['"]\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
)
private val DOUYIN_VIDEO_URL_PATTERNS = listOf(
    Regex("""\\\"(?:play_addr|playAddr|download_addr|downloadAddr|video_url)\\\"\s*:\s*\\\"(.*?)\\\"""", RegexOption.IGNORE_CASE),
    Regex(""""(?:play_addr|playAddr|download_addr|downloadAddr|video_url)"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE),
    Regex("""['"](?:play_addr|playAddr|download_addr|downloadAddr|video_url)['"]\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
)
private val XIAOHONGSHU_VIDEO_URL_PATTERNS = listOf(
    Regex("""\\\"(?:masterUrl|master_url|video_url|videoUrl|playUrl|play_url|h264Url|h265Url)\\\"\s*:\s*\\\"(.*?)\\\"""", RegexOption.IGNORE_CASE),
    Regex(""""(?:masterUrl|master_url|video_url|videoUrl|playUrl|play_url|h264Url|h265Url)"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE),
    Regex("""['"](?:masterUrl|master_url|video_url|videoUrl|playUrl|play_url|h264Url|h265Url)['"]\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
)
private val YOUTUBE_VIDEO_URL_PATTERNS = listOf(
    Regex("""\\\"url\\\"\s*:\s*\\\"(https?:\\\\?/\\\\?/[^"\\]*googlevideo[^"\\]*)\\\"""", RegexOption.IGNORE_CASE),
    Regex(""""url"\s*:\s*"(https?://[^"]*googlevideo[^"]*)"""", RegexOption.IGNORE_CASE)
)
private val YOUTUBE_INNERTUBE_API_KEY_PATTERNS = listOf(
    Regex("""['"]INNERTUBE_API_KEY['"]\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
    Regex("""\"INNERTUBE_API_KEY\"\s*:\s*\"([^\"]+)\"""", RegexOption.IGNORE_CASE),
    Regex("""['"]innertubeApiKey['"]\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
    Regex("""\"innertubeApiKey\"\s*:\s*\"([^\"]+)\"""", RegexOption.IGNORE_CASE),
    Regex("""INNERTUBE_API_KEY\\\":\\\"([^\\\"]+)""", RegexOption.IGNORE_CASE)
)
private val YOUTUBE_SIGNATURE_CIPHER_PATTERNS = listOf(
    Regex("""\\\"(?:signatureCipher|cipher)\\\"\s*:\s*\\\"(.*?)\\\"""", RegexOption.IGNORE_CASE),
    Regex(""""(?:signatureCipher|cipher)"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE),
    Regex("""['"](?:signatureCipher|cipher)['"]\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
)
private val X_VIDEO_VARIANT_PATTERNS = listOf(
    Regex("""['"](?:content_type|type)['"]\s*:\s*['"]video/mp4['"][^{}]{0,320}?['"](?:url|src)['"]\s*:\s*['"]([^'"]+)['"]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
    Regex("""['"](?:url|src)['"]\s*:\s*['"]([^'"]+)['"][^{}]{0,320}?['"](?:content_type|type)['"]\s*:\s*['"]video/mp4['"]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
)
private val HTTP_URL_PATTERN = Regex("""https?://[^"'<>\s\\]+""", RegexOption.IGNORE_CASE)
private val STRICT_HTTP_URL_PATTERN = Regex("""https?://[A-Za-z0-9._~:/?#\[\]@!$&'()*+,;=%-]+""", RegexOption.IGNORE_CASE)
private val TIKTOK_VIDEO_ID_PATTERN = Regex("""(?:/video/|/v/)(\d{6,30})""", RegexOption.IGNORE_CASE)
private val DOUYIN_ITEM_ID_PATTERNS = listOf(
    Regex("""(?:/video/|/share/video/)(\d{6,30})""", RegexOption.IGNORE_CASE),
    Regex("""(?:/xg/video/)(\d{6,30})""", RegexOption.IGNORE_CASE),
    Regex("""(?:item_id|itemId|aweme_id|modal_id)["=: ]+"?(\d{6,30})""", RegexOption.IGNORE_CASE)
)
private val XIAOHONGSHU_NOTE_ID_PATTERN = Regex("""(?:/explore/|/discovery/item/)([0-9a-zA-Z]{12,32})""", RegexOption.IGNORE_CASE)
private val YOUTUBE_VIDEO_ID_PATTERN = Regex("""(?:(?:v=|/shorts/|/embed/|youtu\.be/)([A-Za-z0-9_-]{6,20}))""", RegexOption.IGNORE_CASE)
private val X_STATUS_ID_PATTERN = Regex("""(?:/i/status/|/status/)(\d{6,30})""", RegexOption.IGNORE_CASE)
private val UNICODE_ESCAPE_PATTERN = Regex("""\\u([0-9a-fA-F]{4})""")

data class VideoDownloadResult(
    val sourceUrl: String,
    val displayName: String,
    val mimeType: String,
    val outputUri: Uri,
    val bytesWritten: Long
)

private data class DownloadOpenResult(
    val connection: HttpURLConnection,
    val finalUrl: String,
    val responseMimeType: String,
    val contentLength: Long
)

class VideoDownloadRepository(
    private val cookieDao: CookieDao
) {
    suspend fun downloadVideo(
        context: Context,
        sourceUrl: String,
        hasStorageWritePermission: Boolean,
        onProgress: suspend (Int) -> Unit
    ): VideoDownloadResult = withContext(Dispatchers.IO) {
        val normalizedSourceInput = normalizeInputToHttpUrl(sourceUrl)
        if (!normalizedSourceInput.startsWith("http://") && !normalizedSourceInput.startsWith("https://")) {
            throw IllegalArgumentException("仅支持 http/https 链接")
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !hasStorageWritePermission) {
            throw IllegalStateException("未授予文件写入权限，无法保存视频")
        }

        var resolvedSourceUrl = resolveSourceUrlForDownload(normalizedSourceInput)
        var openResult = openDownloadConnection(resolvedSourceUrl)
        var connection = openResult.connection
        var contentLength = openResult.contentLength
        var responseMimeType = openResult.responseMimeType

        var preliminaryFileName = resolveDisplayName(
            connection = connection,
            sourceUrl = normalizedSourceInput,
            mimeType = responseMimeType.ifBlank { DEFAULT_MIME_TYPE }
        )
        var mimeType = resolveVideoMimeType(
            responseMimeType = responseMimeType,
            displayName = preliminaryFileName,
            sourceUrl = normalizedSourceInput
        )

        if (mimeType == null && responseMimeType.startsWith("text/html", ignoreCase = true)) {
            val retryResolvedSourceUrl = resolveRetrySourceUrlForHtmlResponse(
                originalSourceInput = normalizedSourceInput,
                currentResolvedUrl = resolvedSourceUrl,
                currentFinalUrl = openResult.finalUrl
            ) ?: resolvedSourceUrl

            connection.disconnect()
            resolvedSourceUrl = retryResolvedSourceUrl
            openResult = openDownloadConnection(resolvedSourceUrl)
            connection = openResult.connection
            contentLength = openResult.contentLength
            responseMimeType = openResult.responseMimeType
            preliminaryFileName = resolveDisplayName(
                connection = connection,
                sourceUrl = normalizedSourceInput,
                mimeType = responseMimeType.ifBlank { DEFAULT_MIME_TYPE }
            )
            mimeType = resolveVideoMimeType(
                responseMimeType = responseMimeType,
                displayName = preliminaryFileName,
                sourceUrl = normalizedSourceInput
            )
        }

        val finalMimeType = mimeType ?: throw IllegalStateException(
            buildNonVideoMimeError(
                responseMimeType = responseMimeType,
                finalUrl = openResult.finalUrl
            )
        )
        val fileName = ensureVideoExtension(preliminaryFileName, finalMimeType)

        val targetUri = createTargetUri(context, fileName, finalMimeType)
        var bytesWritten = 0L
        var lastProgress = -1

        try {
            context.contentResolver.openOutputStream(targetUri, "w")?.use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        bytesWritten += read

                        if (contentLength > 0) {
                            val progress = ((bytesWritten * 100) / contentLength).toInt().coerceIn(0, 100)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                    output.flush()
                }
            } ?: throw IllegalStateException("无法写入目标文件")

            if (lastProgress < 100) {
                onProgress(100)
            }

            finalizePendingIfNeeded(context, targetUri)

            VideoDownloadResult(
                sourceUrl = normalizedSourceInput,
                displayName = fileName,
                mimeType = finalMimeType,
                outputUri = targetUri,
                bytesWritten = bytesWritten
            )
        } catch (error: Exception) {
            context.contentResolver.delete(targetUri, null, null)
            throw error
        } finally {
            connection.disconnect()
        }
    }

    internal suspend fun resolveSourceUrlOnlyForTest(sourceInput: String): String {
        return resolveSourceUrlForDownload(normalizeInputToHttpUrl(sourceInput))
    }

    private suspend fun openDownloadConnection(sourceUrl: String): DownloadOpenResult {
        var currentUrl = URL(sanitizeHttpUrl(sourceUrl))

        repeat(MAX_REDIRECT_FOLLOWS) {
            val connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 90_000
                setRequestProperty("User-Agent", ANDROID_CHROME_UA)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Accept-Encoding", "identity")
                applySourceSpecificRequestHeaders(this, currentUrl)
            }

            val cookieHeader = buildCookieHeader(currentUrl)
            if (cookieHeader.isNotBlank()) {
                connection.setRequestProperty("Cookie", cookieHeader)
            }

            var keepConnection = false
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        currentUrl = URL(currentUrl, location)
                        return@repeat
                    }
                }

                if (code !in 200..299) {
                    val reason = connection.responseMessage?.trim().orEmpty()
                    val reasonSuffix = if (reason.isNotBlank()) " $reason" else ""
                    throw IllegalStateException(
                        "下载失败：请求视频资源返回 HTTP $code$reasonSuffix。请确认链接可访问，或更新 Cookies 后重试。"
                    )
                }

                val responseMimeType = connection.contentType
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase()
                    .orEmpty()
                val contentLength = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
                val finalUrl = connection.url?.toString().orEmpty().ifBlank { currentUrl.toString() }

                keepConnection = true
                return DownloadOpenResult(
                    connection = connection,
                    finalUrl = finalUrl,
                    responseMimeType = responseMimeType,
                    contentLength = contentLength
                )
            } finally {
                if (!keepConnection) {
                    connection.disconnect()
                }
            }
        }

        throw IllegalStateException("下载失败：重定向次数超过上限（$MAX_REDIRECT_FOLLOWS）")
    }

    private suspend fun resolveRetrySourceUrlForHtmlResponse(
        originalSourceInput: String,
        currentResolvedUrl: String,
        currentFinalUrl: String
    ): String? {
        val normalizedCurrent = sanitizeHttpUrl(currentResolvedUrl)
        val candidates = linkedSetOf<String>()

        fun addCandidate(candidate: String?) {
            if (candidate.isNullOrBlank()) return
            val normalized = sanitizeHttpUrl(candidate)
            if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
                candidates += normalized
            }
        }

        addCandidate(normalizedCurrent)
        addCandidate(currentFinalUrl)
        addCandidate(originalSourceInput)

        runCatching {
            resolveSourceUrlForDownload(originalSourceInput)
        }.getOrNull()?.let(::addCandidate)

        if (currentFinalUrl.startsWith("http://") || currentFinalUrl.startsWith("https://")) {
            runCatching {
                resolveSourceUrlForDownload(currentFinalUrl)
            }.getOrNull()?.let(::addCandidate)
        }

        return candidates.firstOrNull { it != normalizedCurrent }
    }

    private fun buildNonVideoMimeError(
        responseMimeType: String,
        finalUrl: String
    ): String {
        val normalizedMime = responseMimeType.ifBlank { "unknown" }.lowercase()
        val base = if (normalizedMime.startsWith("text/html")) {
            "下载失败：当前链接返回 text/html，不是视频流（可能是网页、鉴权页或风控页）。请先获取视频直链；若已导入 Cookies，请检查是否过期并重新导入。"
        } else {
            "下载失败：当前链接返回 $normalizedMime，不是视频流。请先获取视频直链；若已导入 Cookies，请检查是否过期并重新导入。"
        }

        val host = runCatching { URL(finalUrl).host.lowercase() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        return if (host != null) "$base（最终域名：$host）" else base
    }

    private suspend fun buildCookieHeader(url: URL): String {
        val allCookies = cookieDao.getValidCookies(System.currentTimeMillis() / 1000)
        val host = url.host.lowercase()
        val path = url.path.ifBlank { "/" }
        val isHttps = url.protocol.equals("https", ignoreCase = true)

        val matched = allCookies
            .filter { cookie ->
                matchDomain(host, cookie) &&
                    matchPath(path, cookie.path) &&
                    (!cookie.secure || isHttps)
            }
            .sortedByDescending { it.path.length }

        return matched.joinToString(separator = "; ") { "${it.name}=${it.value}" }
    }

    private fun matchDomain(host: String, cookie: CookieEntity): Boolean {
        val normalized = cookie.domain.trim().lowercase().removePrefix(".")
        if (normalized.isBlank()) return false

        return if (cookie.includeSubDomains) {
            host == normalized || host.endsWith(".$normalized")
        } else {
            host == normalized
        }
    }

    private fun matchPath(requestPath: String, cookiePath: String): Boolean {
        val normalizedCookiePath = cookiePath.ifBlank { "/" }
        return requestPath.startsWith(normalizedCookiePath)
    }

    private fun resolveDisplayName(connection: HttpURLConnection, sourceUrl: String, mimeType: String): String {
        val fromHeader = parseFileNameFromContentDisposition(connection.getHeaderField("Content-Disposition"))
        val fromUrl = Uri.parse(sourceUrl).lastPathSegment
            ?.substringAfterLast('/')
            ?.substringBefore('?')
            ?.takeIf { it.isNotBlank() }

        val base = sanitizeFileName(fromHeader ?: fromUrl ?: "video_${System.currentTimeMillis()}")
        val hasExt = '.' in base
        if (hasExt) return base

        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?: MimeTypeMap.getFileExtensionFromUrl(sourceUrl)
            ?: "mp4"
        return "$base.$ext"
    }

    private fun sanitizeFileName(raw: String): String {
        return raw
            .replace("\"", "")
            .replace(Regex("[\\\\/:*?<>|]"), "_")
            .trim()
            .ifBlank { "video_${System.currentTimeMillis()}" }
    }

    private fun parseFileNameFromContentDisposition(contentDisposition: String?): String? {
        val value = contentDisposition ?: return null
        val marker = "filename="
        val index = value.indexOf(marker, ignoreCase = true)
        if (index == -1) return null
        val filenameValue = value.substring(index + marker.length).trim()
        return filenameValue.trim('"', '\'', ' ')
    }

    private fun resolveVideoMimeType(
        responseMimeType: String,
        displayName: String,
        sourceUrl: String
    ): String? {
        val normalizedMime = responseMimeType.lowercase()
        if (normalizedMime.startsWith("video/")) {
            return normalizedMime
        }

        val inferredByExtension = inferVideoMimeFromExtension(displayName, sourceUrl)
        if (inferredByExtension != null) {
            return inferredByExtension
        }

        if (normalizedMime == "application/octet-stream" || normalizedMime == "binary/octet-stream") {
            return DEFAULT_MIME_TYPE
        }

        return null
    }

    private fun ensureVideoExtension(displayName: String, mimeType: String): String {
        if (displayName.contains('.')) return displayName
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        if (ext.isNullOrBlank()) return displayName
        return "$displayName.$ext"
    }

    private fun inferVideoMimeFromExtension(displayName: String, sourceUrl: String): String? {
        val ext = extensionOf(displayName).ifBlank {
            extensionOf(Uri.parse(sourceUrl).lastPathSegment ?: "")
        }.lowercase()

        if (ext.isBlank()) return null

        val byMap = extensionToVideoMime[ext]
        if (byMap != null) return byMap

        val byMimeTypeMap = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.lowercase()
        return byMimeTypeMap?.takeIf { it.startsWith("video/") }
    }

    private fun extensionOf(path: String): String {
        val fileName = path.substringAfterLast('/')
        if (!fileName.contains('.')) return ""
        return fileName.substringAfterLast('.').substringBefore('?').substringBefore('#')
    }

    private suspend fun resolveSourceUrlForDownload(sourceUrl: String): String {
        val source = sanitizeHttpUrl(sourceUrl.trim())
        val url = URL(source)

        return when {
            isInstagramPageUrl(url) -> resolveInstagramSourceUrl(source, url)
            isTikTokPageUrl(url) -> resolveTikTokSourceUrl(source)
            isDouyinPageUrl(url) -> resolveDouyinSourceUrl(source)
            isXiaohongshuPageUrl(url) -> resolveXiaohongshuSourceUrl(source)
            isYouTubePageUrl(url) -> resolveYouTubeSourceUrl(source)
            isXPageUrl(url) -> resolveXSourceUrl(source)
            else -> source
        }
    }

    private suspend fun resolveInstagramSourceUrl(source: String, url: URL): String {
        val candidates = linkedSetOf<String>()
        val baseCandidates = listOf(buildInstagramEmbedCaptionedUrl(url), source)
        baseCandidates.forEach { candidate ->
            candidates += candidate
            candidates += appendNoScriptParam(candidate)
        }

        for (pageUrl in candidates) {
            val fromMobile = extractInstagramVideoUrlFromPage(fetchInstagramPage(pageUrl))
            if (!fromMobile.isNullOrBlank()) return fromMobile

            val fromDesktop = extractInstagramVideoUrlFromPage(fetchInstagramPageDesktop(pageUrl))
            if (!fromDesktop.isNullOrBlank()) return fromDesktop
        }

        throw IllegalStateException(
            "下载失败：Instagram 页面未解析到视频直链。请确认链接可公开访问，或导入可用的 Instagram Cookies 后重试。"
        )
    }

    private suspend fun resolveTikTokSourceUrl(source: String): String {
        val candidates = linkedSetOf(source)
        extractTikTokVideoId(source)?.let { itemId ->
            candidates += "https://www.tiktok.com/embed/v2/$itemId"
            candidates += "https://m.tiktok.com/v/$itemId.html"
        }

        candidates.forEach { pageUrl ->
            val page = fetchTikTokPage(pageUrl)
            val resolved = extractTikTokVideoUrlFromPage(page)
            if (!resolved.isNullOrBlank()) {
                return resolved
            }
        }

        resolveTikTokViaTikwmApi(source)?.let { return it }

        throw IllegalStateException(
            "下载失败：TikTok 页面未解析到视频直链。请确认链接可公开访问，或导入可用的 TikTok Cookies 后重试。"
        )
    }

    private suspend fun resolveDouyinSourceUrl(source: String): String {
        val candidates = linkedSetOf(source)
        var discoveredItemId = extractDouyinItemId(source)
        if (discoveredItemId.isNullOrBlank()) {
            val redirectedUrl = resolveFinalRedirectUrl(
                pageUrl = source,
                userAgent = MOBILE_SAFARI_UA,
                referer = "https://www.douyin.com/"
            )
            discoveredItemId = redirectedUrl?.let(::extractDouyinItemId)
        }
        discoveredItemId?.let { candidates += "https://m.douyin.com/share/video/$it" }

        candidates.forEach { pageUrl ->
            val page = fetchDouyinPage(pageUrl)
            val resolved = extractDouyinVideoUrlFromPage(page)
            if (!resolved.isNullOrBlank()) {
                return resolved
            }
            if (discoveredItemId.isNullOrBlank()) {
                discoveredItemId = extractDouyinItemId(page)
            }
        }

        discoveredItemId?.let { itemId ->
            val apiUrl = "https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=$itemId"
            val apiBody = fetchDouyinApi(apiUrl)
            val resolvedFromApi = extractDouyinVideoUrlFromPage(apiBody)
            if (!resolvedFromApi.isNullOrBlank()) {
                return resolvedFromApi
            }
        }

        throw IllegalStateException(
            "下载失败：抖音页面未解析到视频直链。请确认链接可公开访问，或导入可用的抖音 Cookies 后重试。"
        )
    }

    private suspend fun resolveXiaohongshuSourceUrl(source: String): String {
        val candidates = linkedSetOf(source)
        extractXiaohongshuNoteId(source)?.let { noteId ->
            candidates += "https://www.xiaohongshu.com/explore/$noteId"
            candidates += "https://www.xiaohongshu.com/discovery/item/$noteId"
        }

        candidates.forEach { pageUrl ->
            val page = fetchXiaohongshuPage(pageUrl)
            val resolved = extractXiaohongshuVideoUrlFromPage(page)
            if (!resolved.isNullOrBlank()) {
                return resolved
            }
        }

        throw IllegalStateException(
            "下载失败：小红书页面未解析到视频直链。若页面提示仅支持 App 内查看，请导入可用的小红书 Cookies 后重试。"
        )
    }

    private suspend fun resolveYouTubeSourceUrl(source: String): String {
        val videoId = extractYouTubeVideoId(source)
        if (!videoId.isNullOrBlank()) {
            resolveYouTubeViaInnertube(source = source, videoId = videoId)?.let { return it }
        }

        val candidates = linkedSetOf(source)
        videoId?.let {
            candidates += "https://www.youtube.com/watch?v=$it"
            candidates += "https://www.youtube.com/shorts/$it"
            candidates += "https://m.youtube.com/watch?v=$it"
        }

        var fallbackCandidate: String? = null
        candidates.forEach { pageUrl ->
            val page = fetchYouTubePage(pageUrl)
            val pageCandidates = extractYouTubeVideoCandidatesFromPage(page)
            selectReachableYouTubeCandidate(pageCandidates)?.let { return it }
            if (fallbackCandidate.isNullOrBlank()) {
                fallbackCandidate = bestYouTubeCandidate(pageCandidates)
            }
        }

        if (!videoId.isNullOrBlank()) {
            resolveYouTubeViaInnertube(source = "https://www.youtube.com/watch?v=$videoId", videoId = videoId)?.let { return it }
        }

        fallbackCandidate?.let { return it }

        throw IllegalStateException(
            "下载失败：YouTube 页面未解析到视频直链。请确认链接可公开访问，或导入可用的 YouTube Cookies 后重试。"
        )
    }

    private suspend fun resolveXSourceUrl(source: String): String {
        val statusId = extractXStatusId(source)
            ?: throw IllegalStateException("下载失败：未识别到 X 帖子 ID（status id）")

        val apiUrl = "https://cdn.syndication.twimg.com/tweet-result?id=$statusId&token=1"
        val body = fetchXApi(apiUrl)
        val resolved = extractXVideoUrlFromApi(body)
        if (!resolved.isNullOrBlank()) {
            return resolved
        }

        throw IllegalStateException(
            "下载失败：X 页面未解析到视频直链。请确认该推文包含视频并且可公开访问。"
        )
    }

    private fun isInstagramPageUrl(url: URL): Boolean {
        val host = url.host.lowercase()
        if (!(host == "instagram.com" || host == "www.instagram.com")) return false
        val path = url.path.lowercase()
        return path.startsWith("/reel/") || path.startsWith("/p/") || path.startsWith("/tv/")
    }

    private fun isTikTokPageUrl(url: URL): Boolean {
        val host = url.host.lowercase()
        if (host.contains("tiktokcdn")) return false
        val isTikTokHost = host == "tiktok.com" ||
            host.endsWith(".tiktok.com") ||
            host == "musical.ly" ||
            host.endsWith(".musical.ly") ||
            host.endsWith(".tiktokv.com")
        if (!isTikTokHost) return false
        return !url.path.lowercase().endsWith(".mp4")
    }

    private fun isDouyinPageUrl(url: URL): Boolean {
        val host = url.host.lowercase()
        val isDouyinHost = host == "douyin.com" ||
            host.endsWith(".douyin.com") ||
            host == "iesdouyin.com" ||
            host.endsWith(".iesdouyin.com")
        if (!isDouyinHost) return false
        return !url.path.lowercase().endsWith(".mp4")
    }

    private fun isXiaohongshuPageUrl(url: URL): Boolean {
        val host = url.host.lowercase()
        if (host.contains("xhscdn")) return false
        val isXiaohongshuHost = host == "xiaohongshu.com" ||
            host.endsWith(".xiaohongshu.com") ||
            host == "xhslink.com" ||
            host.endsWith(".xhslink.com") ||
            host == "rednote.com" ||
            host.endsWith(".rednote.com")
        if (!isXiaohongshuHost) return false
        return !url.path.lowercase().endsWith(".mp4")
    }

    private fun isYouTubePageUrl(url: URL): Boolean {
        val host = url.host.lowercase()
        val isYouTubeHost = host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtu.be" ||
            host.endsWith(".youtu.be")
        if (!isYouTubeHost) return false
        return !url.path.lowercase().endsWith(".mp4")
    }

    private fun isXPageUrl(url: URL): Boolean {
        val host = url.host.lowercase()
        val isXHost = host == "x.com" ||
            host.endsWith(".x.com") ||
            host == "twitter.com" ||
            host.endsWith(".twitter.com")
        if (!isXHost) return false
        val path = url.path.lowercase()
        return path.contains("/status/") || path.contains("/i/status/")
    }

    private fun buildInstagramEmbedCaptionedUrl(url: URL): String {
        val segments = url.path.trim('/').split('/').filter { it.isNotBlank() }
        if (segments.size < 2) return url.toString()
        return "https://www.instagram.com/${segments[0]}/${segments[1]}/embed/captioned/"
    }

    private fun appendNoScriptParam(url: String): String {
        if (url.contains("_fb_noscript=", ignoreCase = true)) return url
        val separator = if (url.contains("?")) "&" else "?"
        return "$url${separator}_fb_noscript=1"
    }

    private suspend fun fetchTikTokPage(pageUrl: String): String {
        return fetchWebPage(
            pageUrl = pageUrl,
            userAgent = ANDROID_CHROME_UA,
            referer = "https://www.tiktok.com/"
        )
    }

    private suspend fun fetchDouyinPage(pageUrl: String): String {
        return fetchWebPage(
            pageUrl = pageUrl,
            userAgent = MOBILE_SAFARI_UA,
            referer = "https://www.douyin.com/"
        )
    }

    private suspend fun fetchDouyinApi(pageUrl: String): String {
        return fetchWebPage(
            pageUrl = pageUrl,
            userAgent = ANDROID_CHROME_UA,
            referer = "https://www.douyin.com/",
            accept = "application/json,text/plain,*/*"
        )
    }

    private suspend fun fetchInstagramPage(pageUrl: String): String {
        return fetchWebPage(
            pageUrl = pageUrl,
            userAgent = MOBILE_SAFARI_UA,
            referer = "https://www.instagram.com/"
        )
    }

    private suspend fun fetchInstagramPageDesktop(pageUrl: String): String {
        return fetchWebPage(
            pageUrl = pageUrl,
            userAgent = DESKTOP_CHROME_UA,
            referer = "https://www.instagram.com/"
        )
    }

    private suspend fun fetchXiaohongshuPage(pageUrl: String): String {
        return fetchWebPage(
            pageUrl = pageUrl,
            userAgent = MOBILE_SAFARI_UA,
            referer = "https://www.xiaohongshu.com/"
        )
    }

    private suspend fun fetchYouTubePage(pageUrl: String): String {
        return fetchWebPage(
            pageUrl = pageUrl,
            userAgent = DESKTOP_CHROME_UA,
            referer = "https://www.youtube.com/",
            includeCookies = false
        )
    }

    private suspend fun fetchXApi(pageUrl: String): String {
        return fetchWebPage(
            pageUrl = pageUrl,
            userAgent = ANDROID_CHROME_UA,
            referer = "https://x.com/",
            accept = "application/json,text/plain,*/*"
        )
    }

    private suspend fun resolveTikTokViaTikwmApi(source: String): String? {
        val encodedSource = URLEncoder.encode(source, StandardCharsets.UTF_8.toString())
        val apiUrl = "https://www.tikwm.com/api/?url=$encodedSource"

        repeat(2) { attempt ->
            val response = fetchWebPage(
                pageUrl = apiUrl,
                userAgent = ANDROID_CHROME_UA,
                referer = "https://www.tikwm.com/",
                accept = "application/json,text/plain,*/*",
                includeCookies = false
            )

            val resolved = extractTikwmVideoUrlFromApiResponse(response)
            if (!resolved.isNullOrBlank()) {
                return resolved
            }

            val isRateLimited = response.contains("1 request/second", ignoreCase = true) ||
                response.contains("free api limit", ignoreCase = true)
            if (attempt == 0 && isRateLimited) {
                delay(1200)
            }
        }

        return null
    }

    private suspend fun resolveYouTubeViaInnertube(source: String, videoId: String): String? {
        val sourcePage = fetchYouTubePage(source)
        val apiKey = extractYouTubeInnertubeApiKey(sourcePage) ?: return null

        var fallbackCandidate: String? = null

        val androidResponse = fetchYouTubePlayerApi(apiKey = apiKey, videoId = videoId, clientName = "ANDROID")
        val androidCandidates = extractYouTubeVideoCandidatesFromPlayerApi(androidResponse)
        selectReachableYouTubeCandidate(androidCandidates)?.let { return it }
        if (fallbackCandidate.isNullOrBlank()) {
            fallbackCandidate = bestYouTubeCandidate(androidCandidates)
        }

        val iosResponse = fetchYouTubePlayerApi(apiKey = apiKey, videoId = videoId, clientName = "IOS")
        val iosCandidates = extractYouTubeVideoCandidatesFromPlayerApi(iosResponse)
        selectReachableYouTubeCandidate(iosCandidates)?.let { return it }
        if (fallbackCandidate.isNullOrBlank()) {
            fallbackCandidate = bestYouTubeCandidate(iosCandidates)
        }

        val tvResponse = fetchYouTubePlayerApi(
            apiKey = apiKey,
            videoId = videoId,
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER"
        )
        val tvCandidates = extractYouTubeVideoCandidatesFromPlayerApi(tvResponse)
        selectReachableYouTubeCandidate(tvCandidates)?.let { return it }
        if (fallbackCandidate.isNullOrBlank()) {
            fallbackCandidate = bestYouTubeCandidate(tvCandidates)
        }

        return fallbackCandidate
    }

    private fun extractTikwmVideoUrlFromApiResponse(content: String): String? {
        val code = Regex(""""code"\s*:\s*(-?\d+)""", RegexOption.IGNORE_CASE)
            .find(content)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (code != 0) return null

        val normalized = normalizeEscapedContent(content)
        val candidates = linkedSetOf<String>()

        TIKWM_VIDEO_URL_PATTERNS.forEach { pattern ->
            pattern.findAll(normalized).forEach { match ->
                val decoded = decodeEscapedUrl(match.groupValues.getOrNull(1).orEmpty())
                if (isLikelyTikTokVideoUrl(decoded)) {
                    candidates += decoded
                }
            }
        }

        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull(::videoQualityScore)
    }

    private fun extractYouTubeInnertubeApiKey(content: String): String? {
        YOUTUBE_INNERTUBE_API_KEY_PATTERNS.forEach { pattern ->
            val key = pattern.find(content)?.groupValues?.getOrNull(1)
            if (!key.isNullOrBlank()) {
                return key
            }
        }
        return null
    }

    private suspend fun fetchYouTubePlayerApi(
        apiKey: String,
        videoId: String,
        clientName: String
    ): String {
        val body = when (clientName) {
            "ANDROID" -> {
                """
                {
                  "context": {
                    "client": {
                      "clientName": "ANDROID",
                      "clientVersion": "20.10.39",
                      "androidSdkVersion": 34
                    }
                  },
                  "videoId": "$videoId"
                }
                """.trimIndent()
            }

            "IOS" -> {
                """
                {
                  "context": {
                    "client": {
                      "clientName": "IOS",
                      "clientVersion": "20.10.4",
                      "deviceMake": "Apple",
                      "deviceModel": "iPhone16,2",
                      "osName": "iOS",
                      "osVersion": "17.4.1",
                      "hl": "en",
                      "timeZone": "UTC"
                    }
                  },
                  "videoId": "$videoId"
                }
                """.trimIndent()
            }

            "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> {
                """
                {
                  "context": {
                    "client": {
                      "clientName": "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                      "clientVersion": "2.0",
                      "clientScreen": "EMBED"
                    }
                  },
                  "videoId": "$videoId",
                  "contentCheckOk": true,
                  "racyCheckOk": true
                }
                """.trimIndent()
            }

            else -> return ""
        }

        return postJson(
            pageUrl = "https://www.youtube.com/youtubei/v1/player?key=$apiKey",
            body = body,
            userAgent = ANDROID_CHROME_UA,
            referer = "https://www.youtube.com/",
            includeCookies = false
        )
    }

    private suspend fun resolveFinalRedirectUrl(
        pageUrl: String,
        userAgent: String,
        referer: String? = null,
        includeCookies: Boolean = true
    ): String? {
        var currentUrl = URL(pageUrl)
        repeat(MAX_REDIRECT_FOLLOWS) {
            val connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Accept-Encoding", "identity")
                if (!referer.isNullOrBlank()) {
                    setRequestProperty("Referer", referer)
                }
                if (includeCookies) {
                    val cookieHeader = buildCookieHeader(currentUrl)
                    if (cookieHeader.isNotBlank()) {
                        setRequestProperty("Cookie", cookieHeader)
                    }
                }
            }

            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return currentUrl.toString()
                    currentUrl = URL(currentUrl, location)
                    return@repeat
                }
                return currentUrl.toString()
            } finally {
                connection.disconnect()
            }
        }
        return currentUrl.toString()
    }

    private fun extractYouTubeVideoUrlFromPlayerApi(content: String): String? {
        return bestYouTubeCandidate(extractYouTubeVideoCandidatesFromPlayerApi(content))
    }

    private fun extractYouTubeVideoCandidatesFromPlayerApi(content: String): List<String> {
        val status = Regex(
            """(?is)"playabilityStatus"\s*:\s*\{.*?"status"\s*:\s*"([A-Z_]+)""""
        ).find(content)?.groupValues?.getOrNull(1)
        if (status != null && status != "OK") {
            return emptyList()
        }

        val candidates = linkedSetOf<String>()
        candidates += extractAllValidVideoUrls(content, YOUTUBE_VIDEO_URL_PATTERNS, ::isLikelyYouTubeVideoUrl)
        val normalized = normalizeEscapedContent(content)
        candidates += extractAllValidVideoUrls(normalized, YOUTUBE_VIDEO_URL_PATTERNS, ::isLikelyYouTubeVideoUrl)
        candidates += extractYouTubeUrlsFromSignatureCipher(content)
        candidates += extractYouTubeUrlsFromSignatureCipher(normalized)
        candidates += extractAllMatchingHttpUrls(normalized, ::isLikelyYouTubeVideoUrl)
        return normalizeYouTubeCandidates(candidates)
    }

    private fun extractYouTubeUrlsFromSignatureCipher(content: String): List<String> {
        val candidates = linkedSetOf<String>()

        YOUTUBE_SIGNATURE_CIPHER_PATTERNS.forEach { pattern ->
            pattern.findAll(content).forEach { match ->
                val rawCipher = match.groupValues.getOrNull(1).orEmpty()
                val decodedCipher = decodeEscapedUrl(rawCipher)
                val candidate = buildYouTubeUrlFromCipher(decodedCipher)
                if (!candidate.isNullOrBlank() && isLikelyYouTubeVideoUrl(candidate)) {
                    candidates += candidate
                }
            }
        }

        return normalizeYouTubeCandidates(candidates)
    }

    private fun normalizeYouTubeCandidates(candidates: Collection<String>): List<String> {
        if (candidates.isEmpty()) return emptyList()

        return candidates.asSequence()
            .map(::sanitizeHttpUrl)
            .filter { isLikelyYouTubeVideoUrl(it) }
            .distinct()
            .sortedByDescending(::youtubeCandidateRank)
            .toList()
    }

    private fun bestYouTubeCandidate(candidates: Collection<String>): String? {
        return normalizeYouTubeCandidates(candidates).firstOrNull()
    }

    private suspend fun selectReachableYouTubeCandidate(candidates: Collection<String>): String? {
        val ordered = normalizeYouTubeCandidates(candidates)
        if (ordered.isEmpty()) return null

        ordered.take(MAX_YOUTUBE_CANDIDATE_PROBES).forEach { candidate ->
            if (isYouTubeCandidateReachable(candidate)) {
                return candidate
            }
        }
        return null
    }

    private fun isYouTubeCandidateReachable(url: String): Boolean {
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", ANDROID_CHROME_UA)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Range", "bytes=0-1")
                setRequestProperty("Referer", "https://www.youtube.com/")
                setRequestProperty("Origin", "https://www.youtube.com")
            }

            try {
                val code = connection.responseCode
                if (code !in 200..299 && code != 206) {
                    return@runCatching false
                }

                val mimeType = connection.contentType
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase()
                    .orEmpty()
                if (mimeType.startsWith("video/")) {
                    return@runCatching true
                }
                if ((mimeType == "application/octet-stream" || mimeType == "binary/octet-stream") &&
                    isLikelyYouTubeVideoUrl(url)
                ) {
                    return@runCatching true
                }
                return@runCatching mimeType.isBlank() && isLikelyYouTubeVideoUrl(url)
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    private fun youtubeCandidateRank(url: String): Int {
        var score = videoQualityScore(url)
        val lower = url.lowercase()
        when {
            lower.contains("mime=video%2fmp4") || lower.contains("mime=video/mp4") -> score += 12_000_000
            lower.contains("mime=video%2fwebm") || lower.contains("mime=video/webm") -> score += 8_000_000
        }

        val itag = Regex("""(?:[?&])itag=([0-9]{1,4})""", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
        if (!itag.isNullOrBlank()) {
            score += youtubePreferredItagScore[itag] ?: 0
        }

        return score
    }

    private fun buildYouTubeUrlFromCipher(cipher: String): String? {
        val params = parseQueryParams(cipher)
        val baseUrl = params["url"]?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null

        if (baseUrl.contains("sig=", ignoreCase = true) || baseUrl.contains("signature=", ignoreCase = true)) {
            return baseUrl
        }

        val signature = params["sig"] ?: params["signature"] ?: return baseUrl
        val sp = params["sp"].orEmpty().ifBlank { "signature" }
        return appendQueryParam(baseUrl, sp, signature)
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        query.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val idx = pair.indexOf('=')
            val keyRaw = if (idx >= 0) pair.substring(0, idx) else pair
            val valueRaw = if (idx >= 0) pair.substring(idx + 1) else ""
            val key = decodeQueryPart(keyRaw)
            val value = decodeQueryPart(valueRaw)
            if (key.isNotBlank()) {
                result[key] = value
            }
        }
        return result
    }

    private fun decodeQueryPart(raw: String): String {
        return runCatching {
            URLDecoder.decode(raw, StandardCharsets.UTF_8.toString())
        }.getOrDefault(raw)
    }

    private fun appendQueryParam(url: String, key: String, value: String): String {
        val separator = if (url.contains("?")) "&" else "?"
        val encodedValue = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
        return "$url$separator$key=$encodedValue"
    }

    private suspend fun fetchWebPage(
        pageUrl: String,
        userAgent: String,
        referer: String? = null,
        accept: String = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        includeCookies: Boolean = true
    ): String {
        var currentUrl = URL(pageUrl)
        repeat(MAX_REDIRECT_FOLLOWS) {
            val connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", accept)
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Accept-Encoding", "identity")
                if (!referer.isNullOrBlank()) {
                    setRequestProperty("Referer", referer)
                }
                if (includeCookies) {
                    val cookieHeader = buildCookieHeader(currentUrl)
                    if (cookieHeader.isNotBlank()) {
                        setRequestProperty("Cookie", cookieHeader)
                    }
                }
            }

            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return ""
                    currentUrl = URL(currentUrl, location)
                    return@repeat
                }

                val stream = if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: return ""
                }
                return stream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }

        return ""
    }

    private suspend fun postJson(
        pageUrl: String,
        body: String,
        userAgent: String,
        referer: String? = null,
        accept: String = "application/json,text/plain,*/*",
        includeCookies: Boolean = true
    ): String {
        val url = URL(pageUrl)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", accept)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Accept-Encoding", "identity")
            if (!referer.isNullOrBlank()) {
                setRequestProperty("Referer", referer)
            }
            if (includeCookies) {
                val cookieHeader = buildCookieHeader(url)
                if (cookieHeader.isNotBlank()) {
                    setRequestProperty("Cookie", cookieHeader)
                }
            }
        }

        return try {
            connection.outputStream.bufferedWriter().use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: return ""
            }
            stream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun applySourceSpecificRequestHeaders(connection: HttpURLConnection, url: URL) {
        val host = url.host.lowercase()
        when {
            host.contains("googlevideo") || host.contains("youtube.com") || host.contains("youtu.be") -> {
                connection.setRequestProperty("Referer", "https://www.youtube.com/")
                connection.setRequestProperty("Origin", "https://www.youtube.com")
            }

            host.contains("video.twimg.com") || host.contains("x.com") || host.contains("twitter.com") -> {
                connection.setRequestProperty("Referer", "https://x.com/")
                connection.setRequestProperty("Origin", "https://x.com")
            }

            host.contains("tiktok") || host.contains("muscdn") -> {
                connection.setRequestProperty("Referer", "https://www.tiktok.com/")
            }

            host.contains("douyin") || host.contains("amemv") || host.contains("snssdk") -> {
                connection.setRequestProperty("Referer", "https://www.douyin.com/")
            }

            host.contains("xiaohongshu") || host.contains("xhscdn") || host.contains("xhslink") -> {
                connection.setRequestProperty("Referer", "https://www.xiaohongshu.com/")
            }
        }
    }

    private fun extractInstagramVideoUrlFromPage(html: String): String? {
        extractFirstValidVideoUrl(html, INSTAGRAM_OG_VIDEO_PATTERNS)?.let { return it }
        extractFirstValidVideoUrl(html, INSTAGRAM_VIDEO_URL_PATTERNS)?.let { return it }

        val normalizedHtml = normalizeEscapedContent(html)
        if (normalizedHtml != html) {
            extractFirstValidVideoUrl(normalizedHtml, INSTAGRAM_OG_VIDEO_PATTERNS)?.let { return it }
            extractFirstValidVideoUrl(normalizedHtml, INSTAGRAM_VIDEO_URL_PATTERNS)?.let { return it }
        }

        extractFirstVideoUrlFromUrlListBlocks(normalizedHtml, ::isLikelyInstagramVideoUrl)?.let { return it }
        extractFirstMatchingHttpUrl(normalizedHtml, ::isLikelyInstagramVideoUrl)?.let { return it }

        return null
    }

    private fun extractTikTokVideoUrlFromPage(html: String): String? {
        extractFirstValidVideoUrl(html, INSTAGRAM_OG_VIDEO_PATTERNS)?.let { return it }
        extractFirstValidVideoUrl(html, TIKTOK_VIDEO_URL_PATTERNS)?.let { return it }

        val normalized = normalizeEscapedContent(html)
        if (normalized != html) {
            extractFirstValidVideoUrl(normalized, INSTAGRAM_OG_VIDEO_PATTERNS)?.let { return it }
            extractFirstValidVideoUrl(normalized, TIKTOK_VIDEO_URL_PATTERNS)?.let { return it }
        }

        extractFirstVideoUrlFromUrlListBlocks(normalized, ::isLikelyTikTokVideoUrl)?.let { return it }
        extractFirstMatchingHttpUrl(normalized, ::isLikelyTikTokVideoUrl)?.let { return it }
        return null
    }

    private fun extractDouyinVideoUrlFromPage(content: String): String? {
        extractFirstValidVideoUrl(content, INSTAGRAM_OG_VIDEO_PATTERNS, ::isLikelyDouyinVideoUrl)?.let { return it }
        extractFirstValidVideoUrl(content, DOUYIN_VIDEO_URL_PATTERNS, ::isLikelyDouyinVideoUrl)?.let { return it }

        val normalized = normalizeEscapedContent(content)
        if (normalized != content) {
            extractFirstValidVideoUrl(normalized, INSTAGRAM_OG_VIDEO_PATTERNS, ::isLikelyDouyinVideoUrl)?.let { return it }
            extractFirstValidVideoUrl(normalized, DOUYIN_VIDEO_URL_PATTERNS, ::isLikelyDouyinVideoUrl)?.let { return it }
        }

        extractFirstVideoUrlFromUrlListBlocks(normalized, ::isLikelyDouyinVideoUrl)?.let { return it }
        extractFirstMatchingHttpUrl(normalized, ::isLikelyDouyinVideoUrl)?.let { return it }
        return null
    }

    private fun extractXiaohongshuVideoUrlFromPage(content: String): String? {
        extractFirstValidVideoUrl(content, INSTAGRAM_OG_VIDEO_PATTERNS)?.let { return it }
        extractFirstValidVideoUrl(content, XIAOHONGSHU_VIDEO_URL_PATTERNS)?.let { return it }

        val normalized = normalizeEscapedContent(content)
        if (normalized != content) {
            extractFirstValidVideoUrl(normalized, INSTAGRAM_OG_VIDEO_PATTERNS)?.let { return it }
            extractFirstValidVideoUrl(normalized, XIAOHONGSHU_VIDEO_URL_PATTERNS)?.let { return it }
        }

        extractFirstVideoUrlFromUrlListBlocks(normalized, ::isLikelyXiaohongshuVideoUrl)?.let { return it }
        extractFirstMatchingHttpUrl(normalized, ::isLikelyXiaohongshuVideoUrl)?.let { return it }
        return null
    }

    private fun extractYouTubeVideoUrlFromPage(content: String): String? {
        return bestYouTubeCandidate(extractYouTubeVideoCandidatesFromPage(content))
    }

    private fun extractYouTubeVideoCandidatesFromPage(content: String): List<String> {
        val candidates = linkedSetOf<String>()
        candidates += extractAllValidVideoUrls(content, YOUTUBE_VIDEO_URL_PATTERNS, ::isLikelyYouTubeVideoUrl)

        val normalized = normalizeEscapedContent(content)
        if (normalized != content) {
            candidates += extractAllValidVideoUrls(normalized, YOUTUBE_VIDEO_URL_PATTERNS, ::isLikelyYouTubeVideoUrl)
        }

        candidates += extractAllMatchingHttpUrls(normalized, ::isLikelyYouTubeVideoUrl)
        return normalizeYouTubeCandidates(candidates)
    }

    private fun extractXVideoUrlFromApi(content: String): String? {
        val normalized = normalizeEscapedContent(content)
        val candidates = linkedSetOf<String>()

        X_VIDEO_VARIANT_PATTERNS.forEach { pattern ->
            pattern.findAll(normalized).forEach { match ->
                val decoded = decodeEscapedUrl(match.groupValues.getOrNull(1).orEmpty())
                if (isLikelyXVideoUrl(decoded)) {
                    candidates += decoded
                }
            }
        }

        if (candidates.isEmpty()) {
            HTTP_URL_PATTERN.findAll(normalized).forEach { match ->
                val decoded = decodeEscapedUrl(match.value).trimEnd('\\', ',', ';')
                if (isLikelyXVideoUrl(decoded)) {
                    candidates += decoded
                }
            }
        }

        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull(::videoQualityScore)
    }

    private fun extractFirstValidVideoUrl(
        content: String,
        patterns: List<Regex>,
        predicate: ((String) -> Boolean)? = null
    ): String? {
        for (pattern in patterns) {
            pattern.findAll(content).forEach { match ->
                val rawMatch = match.groupValues.getOrNull(1).orEmpty()
                if (rawMatch.isBlank()) return@forEach
                val decoded = decodeEscapedUrl(rawMatch)
                if ((decoded.startsWith("https://") || decoded.startsWith("http://")) &&
                    (predicate == null || predicate(decoded))
                ) {
                    return decoded
                }
            }
        }
        return null
    }

    private fun extractAllValidVideoUrls(
        content: String,
        patterns: List<Regex>,
        predicate: ((String) -> Boolean)? = null
    ): List<String> {
        val candidates = linkedSetOf<String>()
        patterns.forEach patternLoop@{ pattern ->
            pattern.findAll(content).forEach matchLoop@{ match ->
                val rawMatch = match.groupValues.getOrNull(1).orEmpty()
                if (rawMatch.isBlank()) return@matchLoop
                val decoded = decodeEscapedUrl(rawMatch)
                if ((decoded.startsWith("https://") || decoded.startsWith("http://")) &&
                    (predicate == null || predicate(decoded))
                ) {
                    candidates += decoded
                }
            }
        }
        return candidates.toList()
    }

    private fun normalizeEscapedContent(content: String): String {
        var normalized = content
        repeat(3) {
            normalized = normalized
                .replace("\\\\/", "/")
                .replace("\\/", "/")
                .replace("\\\"", "\"")
        }
        return decodeUnicodeEscapes(normalized)
    }

    private fun extractFirstVideoUrlFromUrlListBlocks(
        content: String,
        predicate: (String) -> Boolean
    ): String? {
        val blockPattern = Regex("""(?is)(?:url_list|urlList)\s*[:=]\s*\[(.*?)]""")
        blockPattern.findAll(content).forEach { match ->
            val block = match.groupValues.getOrNull(1).orEmpty()
            extractFirstMatchingHttpUrl(block, predicate)?.let { return it }
        }
        return null
    }

    private fun extractFirstMatchingHttpUrl(
        content: String,
        predicate: (String) -> Boolean
    ): String? {
        HTTP_URL_PATTERN.findAll(content).forEach { match ->
            val candidate = decodeEscapedUrl(match.value)
                .trimEnd('\\', ',', ';')
            if (predicate(candidate)) {
                return candidate
            }
        }
        return null
    }

    private fun extractBestMatchingHttpUrl(
        content: String,
        predicate: (String) -> Boolean
    ): String? {
        val candidates = mutableListOf<String>()
        HTTP_URL_PATTERN.findAll(content).forEach { match ->
            val candidate = decodeEscapedUrl(match.value)
                .trimEnd('\\', ',', ';')
            if (predicate(candidate)) {
                candidates += candidate
            }
        }
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull(::videoQualityScore)
    }

    private fun extractAllMatchingHttpUrls(
        content: String,
        predicate: (String) -> Boolean
    ): List<String> {
        val candidates = linkedSetOf<String>()
        HTTP_URL_PATTERN.findAll(content).forEach { match ->
            val candidate = decodeEscapedUrl(match.value).trimEnd('\\', ',', ';')
            if (predicate(candidate)) {
                candidates += candidate
            }
        }
        return candidates.toList()
    }

    private fun isLikelyTikTokVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false
        if (lower.contains("mime=audio") || lower.contains("mime_type=audio") || lower.contains("audio_mp4")) {
            return false
        }
        if (lower.contains(".mp4") || lower.contains("/video/tos/")) return true
        if (lower.contains("/aweme/v1/play/")) return true
        val host = runCatching { URL(url).host.lowercase() }.getOrNull() ?: return false
        if (host.contains("tiktokcdn") || host.contains("tiktokv") || host.contains("muscdn")) {
            return lower.contains("video") || lower.contains("play") || lower.contains("download")
        }
        return false
    }

    private fun isLikelyDouyinVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false
        if (lower.contains("/share/video/")) return false
        if (lower.contains("/aweme/v1/play/") || lower.contains("playwm")) return true
        if (lower.contains(".mp4") && (lower.contains("douyin") || lower.contains("aweme") || lower.contains("vod"))) {
            return true
        }
        val host = runCatching { URL(url).host.lowercase() }.getOrNull() ?: return false
        if (host.contains("douyinvod")) {
            return true
        }
        if (host.contains("amemv") || host.contains("snssdk")) {
            return lower.contains("video") || lower.contains("play") || lower.contains("vod")
        }
        return false
    }

    private fun isLikelyInstagramVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false
        if (lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") || lower.contains(".webp")) {
            return false
        }
        if (!(lower.contains(".mp4") || lower.contains("video_dashinit") || lower.contains("video"))) {
            return false
        }
        val host = runCatching { URL(url).host.lowercase() }.getOrNull() ?: return false
        return host.contains("cdninstagram.com") || host.contains("fbcdn.net") || host.contains("instagram.com")
    }

    private fun isLikelyXiaohongshuVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false
        if (lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") || lower.contains(".webp")) {
            return false
        }
        if (lower.contains(".mp4") && (lower.contains("xhscdn") || lower.contains("xiaohongshu") || lower.contains("xhs"))) {
            return true
        }
        if (lower.contains("stream") && (lower.contains("xhscdn") || lower.contains("xiaohongshu"))) {
            return true
        }
        val host = runCatching { URL(url).host.lowercase() }.getOrNull() ?: return false
        if (host.contains("xhscdn") || host.contains("xiaohongshu")) {
            return lower.contains("video") || lower.contains("stream") || lower.contains("vod") || lower.contains("play")
        }
        return false
    }

    private fun isLikelyYouTubeVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false
        if (!lower.contains("googlevideo.com")) return false
        if (!lower.contains("/videoplayback")) return false
        if (lower.contains("generate_204") || lower.contains("initplayback")) return false
        if (lower.contains("mime=audio%2f") || lower.contains("mime=audio/")) return false

        val itag = Regex("""(?:[?&])itag=([0-9]{1,4})""", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
        if (!itag.isNullOrBlank() && itag in youtubeAudioOnlyItags) return false

        return true
    }

    private fun isLikelyXVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false
        if (!lower.contains("video.twimg.com")) return false
        if (!lower.contains(".mp4")) return false
        return true
    }

    private fun extractTikTokVideoId(text: String): String? {
        return TIKTOK_VIDEO_ID_PATTERN.find(text)?.groupValues?.getOrNull(1)
    }

    private fun extractDouyinItemId(text: String): String? {
        for (pattern in DOUYIN_ITEM_ID_PATTERNS) {
            val match = pattern.find(text)?.groupValues?.getOrNull(1)
            if (!match.isNullOrBlank()) {
                return match
            }
        }
        return null
    }

    private fun extractXiaohongshuNoteId(text: String): String? {
        return XIAOHONGSHU_NOTE_ID_PATTERN.find(text)?.groupValues?.getOrNull(1)
    }

    private fun extractYouTubeVideoId(text: String): String? {
        return YOUTUBE_VIDEO_ID_PATTERN.find(text)?.groupValues?.getOrNull(1)
    }

    private fun extractXStatusId(text: String): String? {
        return X_STATUS_ID_PATTERN.find(text)?.groupValues?.getOrNull(1)
    }

    private fun decodeEscapedUrl(raw: String): String {
        var normalized = raw.trim().trim('"', '\'')
        repeat(4) {
            normalized = decodeUnicodeEscapes(normalized)
                .replace("\\\\/", "/")
                .replace("\\/", "/")
                .replace("&amp;", "&")
                .replace("\\\"", "\"")
                .replace("\\u0026", "&")
                .replace("\\u002F", "/")
                .replace("\\u003D", "=")
                .replace("\\u0025", "%")
        }
        return normalized
    }

    private fun normalizeInputToHttpUrl(sourceInput: String): String {
        val trimmed = sourceInput.trim()
        if (trimmed.isBlank()) {
            throw IllegalArgumentException("请输入有效链接")
        }

        val candidate = STRICT_HTTP_URL_PATTERN.find(trimmed)?.value
            ?: throw IllegalArgumentException("未检测到有效 http/https 链接")
        return sanitizeHttpUrl(candidate)
    }

    private fun sanitizeHttpUrl(raw: String): String {
        var cleaned = raw.trim()
        while (cleaned.isNotEmpty() && cleaned.last() in trailingUrlCharsToTrim) {
            cleaned = cleaned.dropLast(1)
        }
        return forceHttpsForKnownVideoHosts(cleaned)
    }

    private fun forceHttpsForKnownVideoHosts(url: String): String {
        if (!url.startsWith("http://", ignoreCase = true)) return url
        val host = runCatching { URL(url).host.lowercase().removePrefix(".") }
            .getOrNull()
            ?: return url

        val shouldUpgrade = cleartextRestrictedDomainSuffixes.any { suffix ->
            host == suffix || host.endsWith(".$suffix")
        }
        if (!shouldUpgrade) return url

        return url.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
    }

    private fun videoQualityScore(url: String): Int {
        val dimension = Regex("""/(\d{2,5})x(\d{2,5})/""")
            .find(url)
            ?.groupValues
            ?.drop(1)
            ?.mapNotNull { it.toIntOrNull() }
        if (dimension != null && dimension.size == 2) {
            return (dimension[0] * dimension[1]).coerceAtLeast(1)
        }

        val bitrate = Regex("""(?:bitrate|br)=([0-9]{3,7})""", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return bitrate ?: 1
    }

    private fun decodeUnicodeEscapes(input: String): String {
        return UNICODE_ESCAPE_PATTERN.replace(input) { match ->
            val hex = match.groupValues[1]
            hex.toIntOrNull(16)?.toChar()?.toString() ?: match.value
        }
    }

    private fun createTargetUri(context: Context, displayName: String, mimeType: String): Uri {
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, MOVIES_RELATIVE_PATH)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                val targetFile = resolveLegacyTargetFile(displayName)
                put(MediaStore.MediaColumns.DATA, targetFile.absolutePath)
            }
        }

        return resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法创建目标媒体文件")
    }

    private fun finalizePendingIfNeeded(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
        }
        context.contentResolver.update(uri, values, null, null)
    }

    private fun resolveLegacyTargetFile(displayName: String): File {
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val targetDir = File(moviesDir, "v-down")
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IllegalStateException("无法创建 v-down 目录")
        }

        val dot = displayName.lastIndexOf('.')
        val name = if (dot > 0) displayName.substring(0, dot) else displayName
        val ext = if (dot > 0) displayName.substring(dot) else ""

        var candidate = File(targetDir, displayName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(targetDir, "${name}_$index$ext")
            index += 1
        }
        return candidate
    }

    private companion object {
        val trailingUrlCharsToTrim = setOf(
            '，', '。', '！', '？', '；', '：',
            ',', '.', '!', '?', ';', ':',
            ')', ']', '}', '>', '"', '\''
        )
        val extensionToVideoMime = mapOf(
            "mp4" to "video/mp4",
            "m4v" to "video/mp4",
            "mov" to "video/quicktime",
            "webm" to "video/webm",
            "mkv" to "video/x-matroska",
            "avi" to "video/x-msvideo",
            "flv" to "video/x-flv",
            "3gp" to "video/3gpp",
            "ts" to "video/mp2t",
            "m2ts" to "video/mp2t"
        )
        val youtubeAudioOnlyItags = setOf(
            "139", "140", "141", "171", "249", "250", "251", "256", "258", "325", "328", "599", "600"
        )
        val youtubePreferredItagScore = mapOf(
            "22" to 6_000_000,   // 720p mp4 progressive
            "18" to 5_500_000,   // 360p mp4 progressive
            "37" to 5_000_000,   // 1080p mp4 progressive (rare)
            "136" to 4_200_000,  // 720p mp4 video-only
            "135" to 4_000_000,  // 480p mp4 video-only
            "134" to 3_800_000,  // 360p mp4 video-only
            "137" to 3_600_000,  // 1080p mp4 video-only
            "398" to 3_400_000,  // av01 720p
            "399" to 3_200_000,  // av01 1080p
            "247" to 2_800_000,  // webm 720p
            "248" to 2_600_000   // webm 1080p
        )
        val cleartextRestrictedDomainSuffixes = setOf(
            "instagram.com",
            "cdninstagram.com",
            "fbcdn.net",
            "tiktok.com",
            "tiktokcdn.com",
            "tiktokv.com",
            "muscdn.com",
            "douyin.com",
            "iesdouyin.com",
            "amemv.com",
            "snssdk.com",
            "xiaohongshu.com",
            "xhscdn.com",
            "xhslink.com",
            "rednote.com",
            "youtube.com",
            "youtu.be",
            "googlevideo.com",
            "x.com",
            "twitter.com",
            "twimg.com",
            "bilibili.com",
            "bilivideo.com",
            "hdslb.com"
        )
    }
}
